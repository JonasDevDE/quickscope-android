import 'dart:async';
import 'package:uuid/uuid.dart';
import '../logic/attempt_queue.dart';
import 'api.dart';
import 'models.dart';
import 'network.dart';

/// Sendet die lokal gesammelten Antworten batchweise. Fehlt das Netz, bleiben sie in der Warteschlange und
/// gehen beim naechsten Versuch mit. Abgelehnte Antworten (4xx ausser 401/408/429) werden verworfen, damit nichts haengt.
class AttemptSync {
  static const int maxBatch = 500;
  static const _uuid = Uuid();

  final QuickscopeApi _api;
  final AttemptQueue queue;
  Future<AttemptsResponse?>? _flushing;

  AttemptSync(this._api, this.queue);

  /// Antwort vormerken (sofort gespeichert, noch nicht gesendet).
  void record(String examId, AttemptRequest attempt) => queue.add(
        examId,
        attempt.copyWith(clientId: attempt.clientId ?? _uuid.v4()),
      );

  bool hasPending(String examId) => queue.pending(examId).isNotEmpty;

  /// Sendet alles Vorgemerkte; liefert die Antwort (Prognose, ggf. nextLessonId) oder null (nichts zu senden oder kein Netz).
  Future<AttemptsResponse?> flush(String examId) async {
    while (_flushing != null) {
      try {
        await _flushing;
      } catch (_) {}
    }
    final completer = Completer<AttemptsResponse?>();
    _flushing = completer.future;
    try {
      AttemptsResponse? last;
      while (true) {
        final batch = queue.pending(examId).take(maxBatch).toList();
        if (batch.isEmpty) break;
        try {
          last = await _api.attempts(examId, batch);
          queue.removeSent(examId, batch.length);
        } on ApiHttpException catch (e) {
          if (e.code >= 400 &&
              e.code < 500 &&
              e.code != 401 &&
              e.code != 408 &&
              e.code != 429) {
            queue.removeSent(examId, batch.length);
          } else {
            break;
          }
        } catch (_) {
          break;
        }
      }
      completer.complete(last);
      return last;
    } finally {
      _flushing = null;
    }
  }
}
