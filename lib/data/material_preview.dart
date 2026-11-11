import 'dart:io';
import 'package:open_filex/open_filex.dart';
import 'package:path_provider/path_provider.dart';
import 'models.dart';
import 'network.dart';

/// Original eines Materials fuer die Vorschau: wird mit dem Anmelde-Header in den Cache geladen
/// und ueber den nativen System-Viewer (iOS QuickLook / Android FileProvider) geoeffnet.
class MaterialPreview {
  final Network _network;

  MaterialPreview(this._network);

  Future<void> open(String examId, MaterialItem material) async {
    final (file, mime) = await _download(examId, material);
    await OpenFilex.open(file.path, type: mime);
  }

  Future<(File, String)> _download(String examId, MaterialItem material) async {
    final tempRoot = await getTemporaryDirectory();
    final dir = Directory('${tempRoot.path}/previews');
    if (dir.existsSync()) {
      try {
        dir.deleteSync(recursive: true);
      } catch (_) {}
    }
    dir.createSync(recursive: true);

    final url = _network.resolve('api/exams/$examId/materials/${material.id}/file');
    final resp = await _network.send(method: 'GET', url: url);
    if (resp.statusCode < 200 || resp.statusCode >= 300) {
      throw HttpException('HTTP ${resp.statusCode}');
    }
    final mime = resp.headers['content-type']?.split(';').first.trim() ??
        'application/octet-stream';
    final file = File('${dir.path}/${_safeName(material.name, mime)}');
    await file.writeAsBytes(resp.bodyBytes);
    return (file, mime);
  }

  String _safeName(String name, String mime) {
    final cleaned = name.replaceAll(RegExp(r'[^A-Za-z0-9._-]'), '_');
    final base = (cleaned.isEmpty ? 'material' : cleaned);
    final truncated = base.length <= 60 ? base : base.substring(0, 60);
    final ext = switch (mime) {
      final m when m.startsWith('image/jpeg') => '.jpg',
      final m when m.startsWith('image/png') => '.png',
      'application/pdf' => '.pdf',
      _ => '',
    };
    return (ext.isNotEmpty && !truncated.toLowerCase().endsWith(ext))
        ? '$truncated$ext'
        : truncated;
  }
}
