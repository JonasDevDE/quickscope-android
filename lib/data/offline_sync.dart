import 'dart:async';
import '../logic/offline.dart';
import 'api.dart';
import 'app_log.dart';
import 'attempt_sync.dart';
import 'local_data.dart';

enum PackSyncResult { synced, pending, unavailable }

/// Haelt das Offline-Paket aktuell: zuerst offene Antworten senden (idempotent ueber clientId), dann das Paket holen,
/// erst danach das lokal "Erledigte" zuruecksetzen; so gehen Antworten nie verloren und das neue Paket kennt den neuen Stand.
class OfflineSync {
  final QuickscopeApi _api;
  final LocalData _local;
  final AttemptSync _attempts;

  OfflineSync(this._api, this._local, this._attempts);

  Future<PackSyncResult> syncExam(String examId) async {
    await _attempts.flush(examId);
    if (_attempts.hasPending(examId)) return PackSyncResult.pending;

    final OfflinePackResponse response;
    try {
      response = await _api.offlinePack(
        examId,
        days: offlineDays,
        etag: _local.packEtag(examId),
      );
    } catch (e) {
      AppLog.w('Offline', 'Paket nicht geladen: ${e.runtimeType}');
      return PackSyncResult.unavailable;
    }

    if (response.statusCode == 304) {
      _local.clearDone(examId);
      return PackSyncResult.synced;
    }
    if (!response.isSuccessful) {
      AppLog.w('Offline', 'Paket nicht verfuegbar: HTTP ${response.statusCode}');
      return PackSyncResult.unavailable;
    }

    final text = response.body;
    final pack = parseOfflinePack(text);
    if (pack == null) return PackSyncResult.unavailable;

    _local.savePack(examId, text, offlineDays, response.etag);
    _local.clearDone(examId);
    if (pack.topics.isNotEmpty) {
      _local.saveTopics(examId, pack.topics);
    } else {
      try {
        _local.saveTopics(examId, await _api.topics(examId));
      } catch (_) {}
    }
    try {
      _local.saveForecast(examId, await _api.forecast(examId));
    } catch (_) {}
    return PackSyncResult.synced;
  }

  /// Alle lernbaren Pruefungen; speichert auch die Pruefungsliste fuer den Offline-Start.
  Future<int> syncAll() async {
    final exams = await _api.exams();
    _local.saveExams(exams);
    var ok = 0;
    for (final e in exams) {
      if (e.status == 'ready' && await syncExam(e.id) == PackSyncResult.synced) {
        ok++;
      }
    }
    return ok;
  }
}
