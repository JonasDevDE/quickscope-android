import 'dart:async';
import 'dart:io';
import 'dart:math' as math;
import 'audio.dart';

enum UploadItemState { waiting, uploading, done, failed, cancelled }

/// Eine Datei der Warteschlange mit Status; [error] ist ein ruhiger Klartext (Servermeldung, wenn es eine gibt).
class UploadItem {
  final String name;
  final UploadItemState state;
  final String? error;

  const UploadItem({
    required this.name,
    this.state = UploadItemState.waiting,
    this.error,
  });

  UploadItem copyWith({
    String? name,
    UploadItemState? state,
    String? error,
    bool clearError = false,
  }) =>
      UploadItem(
        name: name ?? this.name,
        state: state ?? this.state,
        error: clearError ? null : (error ?? this.error),
      );
}

/// Ergebnis eines Durchlaufs: erledigte Dateien, Indizes der fehlgeschlagenen, Gesamtzahl.
class UploadSummary {
  final int done;
  final List<int> failed;
  final int total;
  const UploadSummary(this.done, this.failed, this.total);
}

/// Exception mit HTTP-Statuscode fuer wiederholbare Serverfehler.
class UploadHttpException implements Exception {
  final int code;
  final String? serverMessage;
  const UploadHttpException(this.code, [this.serverMessage]);

  @override
  String toString() => 'HTTP $code ${serverMessage ?? ''}';
}

/// Cancellation-Marker fuer abgebrochene Uploads.
class UploadCancelledException implements Exception {
  const UploadCancelledException();
}

/// Bis zu so viele Versuche je Datei (der erste plus Wiederholungen mit Backoff), dann "Fehler" und weiter mit der naechsten.
const int uploadMaxAttempts = 3;

/// Wiederholen bei Netzfehler, Zeitueberschreitung, 429 und 5xx; andere Fehler (z. B. zu gross, falsches Format) sofort als Fehler.
bool uploadRetryable(Object e) => switch (e) {
      UploadHttpException(:final code) =>
        code == 408 || code == 429 || code >= 500,
      SocketException() || TimeoutException() || HttpException() => true,
      _ => false,
    };

/// Laedt strikt eine Datei nach der anderen hoch (1 parallel), in der Reihenfolge der Liste.
Future<UploadSummary> runUploadQueue<T>({
  required int Function() size,
  required T Function(int) itemAt,
  bool Function(Object)? retryable,
  int maxAttempts = uploadMaxAttempts,
  int Function(int)? backoffMs,
  Future<void> Function(int)? wait,
  void Function(int index, UploadItemState state, Object? error)? onState,
  required Future<void> Function(int index, T item) upload,
}) async {
  final isRetryable = retryable ?? uploadRetryable;
  final getBackoff = backoffMs ?? ((attempt) => retryDelayMs(attempt));
  final doWait =
      wait ?? ((ms) => Future<void>.delayed(Duration(milliseconds: ms)));
  final report = onState ?? ((_, __, ___) {});

  var done = 0;
  final failed = <int>[];
  var i = 0;
  while (i < size()) {
    final item = itemAt(i);
    report(i, UploadItemState.uploading, null);
    var attempt = 1;
    while (true) {
      try {
        await upload(i, item);
        done++;
        report(i, UploadItemState.done, null);
        break;
      } on UploadCancelledException {
        rethrow;
      } catch (e) {
        if (isRetryable(e) && attempt < maxAttempts) {
          await doWait(getBackoff(attempt - 1));
          attempt++;
          continue;
        }
        failed.add(i);
        report(i, UploadItemState.failed, e);
        break;
      }
    }
    i++;
  }
  return UploadSummary(done, failed, size());
}

/// "3 von 10" waehrend des Hochladens (laufende Datei von allen).
String uploadProgressText(int current, int total) => '$current von $total';

/// Ruhige Zusammenfassung am Ende: "8 von 10 hochgeladen".
String uploadSummaryText(int done, int total) => '$done von $total hochgeladen';

/// Fehlermeldungen: Servermeldung im Klartext, sonst ein neutraler Satz; nie eine feste Zahl wie "30 MB".
String uploadFailureText(String? serverMessage, int? code, bool network) {
  if (serverMessage != null && serverMessage.trim().isNotEmpty) {
    return serverMessage;
  }
  if (network) return 'Kein Netz.';
  if (code == 413) return 'Die Datei ist zu groß.';
  if (code == 429) return 'Gerade zu viele Anfragen. Später nochmal.';
  if (code != null && code >= 500) {
    return 'Der Server ist gerade nicht erreichbar.';
  }
  return 'Hochladen fehlgeschlagen.';
}

/// Laengste Kante, auf die Fotos vor dem Upload verkleinert werden, und JPEG-Qualitaet.
const int uploadMaxEdge = 2560;
const int uploadJpegQuality = 85;

/// Ab dieser Groesse werden Bilder auch dann neu kodiert, wenn die Kantenlaenge schon passt (Handyfotos mit 8-12 MB).
const int uploadRecodeBytes = 1500000;

/// Neue Groesse bei laengster Kante <= [maxEdge], Seitenverhaeltnis bleibt; nie vergroessern.
(int, int) scaledSize(int width, int height, [int maxEdge = uploadMaxEdge]) {
  final longest = math.max(width, height);
  if (longest <= maxEdge || longest <= 0) return (width, height);
  final f = maxEdge / longest;
  return (math.max(1, (width * f).round()), math.max(1, (height * f).round()));
}

/// Bilder (ausser GIF) werden verkleinert/neu kodiert, wenn sie zu gross sind (Kante oder Dateigroesse); Dokumente nie.
bool needsImageCompression(
  String mime,
  int sizeBytes,
  int width,
  int height,
) =>
    mime.startsWith('image/') &&
    mime != 'image/gif' &&
    (math.max(width, height) > uploadMaxEdge ||
        sizeBytes > uploadRecodeBytes ||
        sizeBytes < 0);
