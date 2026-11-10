import 'dart:async';
import 'dart:io';
import '../config.dart';
import '../logic/audio.dart';
import 'api.dart';
import 'app_log.dart';
import 'auth.dart';
import 'connectivity.dart';
import 'models.dart';
import 'network.dart';
import 'store.dart';

/// Vorlesen per Live-Streaming: nichts wird vorgeladen oder gespeichert. Bei "Vorlesen aus" gibt es gar keine Audio-Aufrufe
/// (weder Status noch Stream noch Wortzeiten). Der Status (GET audio/status) wird beim Rundenstart geprueft und danach hoechstens
/// alle 60 s erneut; offline oder bei jedem Fehler laeuft die Runde still ohne Ton.
class AudioClient {
  final QuickscopeApi _api;
  final AuthManager _auth;
  final Store _store;
  final Connectivity _connectivity;
  final Uri _base = Uri.parse(AppConfig.baseUrl);

  int _lastCheck = 0;
  bool _lastResult = true;
  int _recheckMs = audioRecheckMs;
  Future<void>? _prefetching;

  AudioClient(this._api, this._auth, this._store, this._connectivity);

  /// Ton gewuenscht (Einstellung) UND Stimme verfuegbar; [force] ignoriert den 60-s-Zwischenspeicher.
  Future<bool> available({bool force = false}) async {
    if (!_store.audioEnabled) return false;
    if (!_connectivity.isOnline()) return false;
    final now = DateTime.now().millisecondsSinceEpoch;
    if (!force && _lastCheck != 0 && now - _lastCheck < _recheckMs) {
      return _lastResult;
    }

    AudioStatus? status;
    try {
      status = await _api.audioStatus(
        timeout: const Duration(milliseconds: audioStatusTimeoutMs),
      );
    } catch (_) {
      status = null;
    }

    _lastResult = statusAvailable(status);
    _recheckMs = !_lastResult
        ? ((status?.retryAfterSeconds ?? 0) * 1000).clamp(5000, 300000)
        : audioRecheckMs;
    if (_recheckMs <= 0) _recheckMs = audioRecheckMs;
    _lastCheck = now;
    return _lastResult;
  }

  /// Stoesst das Audio einer kommenden Karte an, damit das Backend es im Cache hat: ein Request mit Range auf 1 Byte.
  Future<void> prefetch(
    Network network,
    String examId,
    PrefetchTarget target,
  ) async {
    if (!_store.audioEnabled || !_connectivity.isOnline()) return;
    while (_prefetching != null) {
      try {
        await _prefetching;
      } catch (_) {}
    }
    final completer = Completer<void>();
    _prefetching = completer.future;
    try {
      final uri = target.lesson
          ? lessonUri(examId, target.id)
          : itemUri(examId, target.id);
      await network
          .send(
            method: 'GET',
            url: uri,
            headers: const {'Range': 'bytes=0-0'},
            timeout: Duration(
              milliseconds: streamStartTimeoutMs(target.lesson),
            ),
          )
          .catchError((_) => throw const SocketException('ignored'));
    } catch (_) {
    } finally {
      completer.complete();
      _prefetching = null;
    }
  }

  /// Zentrale Fehlerprotokollierung: Debug-Log immer, ans Backend gedrosselt (best effort, 404/Fehler egal).
  Future<void> report(
    String code,
    String detail, {
    required bool lesson,
  }) async {
    AppLog.d(
      'Audio',
      'Fehler $code (${lesson ? 'Lektion' : 'Frage'}): $detail',
    );
    if (!_connectivity.isOnline()) return;
    final now = DateTime.now().millisecondsSinceEpoch;
    final today = DateTime.now().toIso8601String().split('T').first;
    if (_store.logDay != today) {
      _store.logDay = today;
      _store.logCount = 0;
    }
    if (!clientLogAllowed(now, _store.logLastMs, _store.logCount)) return;
    _store.logLastMs = now;
    _store.logCount = _store.logCount + 1;
    try {
      await _api.clientLog(
        ClientLog(
          kind: 'audio',
          code: code,
          detail: clientLogDetail(detail),
          appVersion: AppConfig.versionName,
          itemType: lesson ? 'lesson' : 'question',
        ),
      );
    } catch (_) {}
  }

  /// Nach einem Fehlschlag: ein kleiner Range-Request zeigt HTTP-Status und Content-Type. Null = unauffaellig.
  Future<String?> diagnose(Network network, Uri uri) async {
    try {
      final resp = await network.send(
        method: 'GET',
        url: uri,
        headers: const {'Range': 'bytes=0-0'},
        timeout: const Duration(seconds: 4),
      );
      final ct = resp.headers['content-type']?.split(';').first.trim();
      final code = audioErrorCode(httpCode: resp.statusCode, contentType: ct);
      return code != 'error' ? code : null;
    } on IOException catch (e) {
      return 'io:${e.runtimeType}';
    } catch (e) {
      return 'io:${e.runtimeType}';
    }
  }

  Uri itemUri(String examId, String itemId) => Uri.parse(
        '${AppConfig.baseUrl}api/exams/$examId/items/$itemId/audio?part=question',
      );

  Uri lessonUri(String examId, String lessonId) =>
      Uri.parse('${AppConfig.baseUrl}api/exams/$examId/lessons/$lessonId/audio');

  /// Anmelde-Header nur fuer den eigenen API-Host.
  Map<String, String> headers(Uri uri) => (uri.scheme == _base.scheme &&
          uri.host == _base.host &&
          uri.port == _base.port)
      ? {
          'Authorization': 'Bearer ${_auth.token ?? ''}',
          'X-App-Version': AppConfig.versionCode.toString(),
        }
      : const {};

  /// Wortzeiten live parallel zum Stream; null bei Aus, Fehler oder Offline.
  Future<AudioTimings?> timings(String examId, String itemId) async {
    if (!_store.audioEnabled || !_connectivity.isOnline()) return null;
    try {
      return await _api.itemTimings(examId, itemId);
    } catch (_) {
      return null;
    }
  }
}
