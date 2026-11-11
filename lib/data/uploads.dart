import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:isolate';
import 'package:http/http.dart' as http;
import 'package:http_parser/http_parser.dart';
import 'package:image/image.dart' as img;
import 'package:mime/mime.dart';
import 'package:path_provider/path_provider.dart';
import '../logic/upload_queue.dart';
import 'models.dart';
import 'network.dart';

/// Eine Datei zum Hochladen: Pfad, Anzeigename, MIME-Typ und Groesse in Bytes.
class UploadFile {
  final String path;
  final String name;
  final String mime;
  final int size;

  const UploadFile({
    required this.path,
    required this.name,
    required this.mime,
    required this.size,
  });
}

/// Was hochgeladen wird: ein Dokument ([scan] = false) oder mehrere Seiten als EIN Scan.
class UploadJob {
  final List<UploadFile> files;
  final String role;
  final bool scan;

  const UploadJob({
    required this.files,
    required this.role,
    required this.scan,
  });
}

/// Liest Name, Typ und Groesse einer lokalen Datei, ohne den Inhalt komplett zu laden.
Future<UploadFile> describeFile(String path, {String? displayName}) async {
  final f = File(path);
  final name = displayName ?? path.split('/').last;
  var size = -1;
  try {
    if (await f.exists()) size = await f.length();
  } catch (_) {}
  final mime = lookupMimeType(path) ?? 'application/octet-stream';
  return UploadFile(path: path, name: name, mime: mime, size: size);
}

/// Gesamtgroesse in Byte fuer den Fortschritt; 0, wenn eine Groesse unbekannt ist.
int totalSize(UploadJob job) => job.files.any((f) => f.size < 0)
    ? 0
    : job.files.fold<int>(0, (sum, f) => sum + f.size);

/// Verkleinert ein Foto vor dem Upload: laengste Kante hoechstens 2560 px, JPEG 85, EXIF-Ausrichtung wird angewendet.
Future<UploadFile> compressForUpload(UploadFile file) async {
  if (!file.mime.startsWith('image/') || file.mime == 'image/gif') return file;
  try {
    final tempRoot = await getTemporaryDirectory();
    final dir = Directory('${tempRoot.path}/uploads')
      ..createSync(recursive: true);
    final outPath =
        '${dir.path}/u_${DateTime.now().microsecondsSinceEpoch}.jpg';

    final compressed = await Isolate.run(() {
      final bytes = File(file.path).readAsBytesSync();
      final decoded = img.decodeImage(bytes);
      if (decoded == null) return false;
      final oriented = img.bakeOrientation(decoded);
      if (!needsImageCompression(
        file.mime,
        file.size,
        oriented.width,
        oriented.height,
      )) {
        return false;
      }
      final (targetW, targetH) =
          scaledSize(oriented.width, oriented.height, uploadMaxEdge);
      final resized =
          (targetW != oriented.width || targetH != oriented.height)
              ? img.copyResize(oriented, width: targetW, height: targetH)
              : oriented;
      final jpg = img.encodeJpg(resized, quality: uploadJpegQuality);
      File(outPath).writeAsBytesSync(jpg);
      return true;
    });

    if (!compressed) return file;
    final outFile = File(outPath);
    final len = await outFile.length();
    final dotIdx = file.name.lastIndexOf('.');
    final baseName = dotIdx > 0 ? file.name.substring(0, dotIdx) : file.name;
    return UploadFile(
      path: outFile.path,
      name: '$baseName.jpg',
      mime: 'image/jpeg',
      size: len,
    );
  } catch (_) {
    return file;
  }
}

/// Temporaere Kopien aus [compressForUpload] wieder loeschen (nur Dateien im eigenen Upload-Cache).
void cleanupUploadCopies(List<UploadFile> files) {
  for (final f in files) {
    if (f.path.contains('/uploads/')) {
      try {
        File(f.path).deleteSync();
      } catch (_) {}
    }
  }
}

/// Sendet einen [UploadJob] per Multipart-Streaming an `POST api/exams/{id}/materials` und meldet gesendete Bytes.
Future<MaterialItem> uploadMaterialJob({
  required Network network,
  required String examId,
  required UploadJob job,
  required void Function(int bytes) onBytes,
}) async {
  final url = network.resolve('api/exams/$examId/materials');
  final req = http.MultipartRequest('POST', url)
    ..headers.addAll(network.decorateHeaders(url))
    ..fields['role'] = job.role;

  final field = job.scan ? 'files[]' : 'file';
  for (final f in job.files) {
    final fileObj = File(f.path);
    final length = f.size >= 0 ? f.size : await fileObj.length();
    final stream = fileObj.openRead().transform(
      StreamTransformer<List<int>, List<int>>.fromHandlers(
        handleData: (chunk, sink) {
          onBytes(chunk.length);
          sink.add(chunk);
        },
      ),
    );
    MediaType? contentType;
    try {
      contentType = MediaType.parse(f.mime);
    } catch (_) {}
    req.files.add(
      http.MultipartFile(
        field,
        stream,
        length,
        filename: f.name,
        contentType: contentType,
      ),
    );
  }

  final streamed = await network.rawClient.send(req);
  final resp = await http.Response.fromStream(streamed);
  if (resp.statusCode < 200 || resp.statusCode >= 300) {
    final errException = ApiHttpException(
      resp.statusCode,
      utf8.decode(resp.bodyBytes, allowMalformed: true),
      resp.headers,
    );
    throw UploadHttpException(resp.statusCode, backendMessage(errException));
  }
  return MaterialItem.fromJson(
    jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>,
  );
}
