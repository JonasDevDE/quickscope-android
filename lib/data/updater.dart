import 'dart:async';
import 'dart:io';
import 'package:crypto/crypto.dart';
import 'package:flutter/foundation.dart';
import 'package:open_filex/open_filex.dart';
import 'package:path_provider/path_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:url_launcher/url_launcher.dart';
import '../config.dart';
import 'api.dart';
import 'app_log.dart';
import 'models.dart';
import 'network.dart';

sealed class UpdateStatus {
  const UpdateStatus();
}

class UpdateIdle extends UpdateStatus {
  const UpdateIdle();
}

class UpdateUpToDate extends UpdateStatus {
  const UpdateUpToDate();
}

class UpdatePreparing extends UpdateStatus {
  final String versionName;
  final double? progress;
  final String? notes;
  const UpdatePreparing(this.versionName, this.progress, [this.notes]);
}

class UpdateReady extends UpdateStatus {
  final String versionName;
  final bool needsConfirm;
  final String? notes;
  const UpdateReady(this.versionName, this.needsConfirm, [this.notes]);
}

class UpdateInstalling extends UpdateStatus {
  const UpdateInstalling();
}

class UpdateError extends UpdateStatus {
  final String message;
  final bool blocking;
  const UpdateError(this.message, {this.blocking = false});
}

class _UpdateException implements Exception {
  final String message;
  const _UpdateException(this.message);
}

/// Selbst-Update: leise im Hintergrund laden, SHA-256 pruefen, auf Android per Installer öffnen
/// und auf iOS über den Download-/Release-Link.
class Updater {
  static const String _tag = 'Updater';
  static const String _keyCode = 'u_readyCode';
  static const String _keyName = 'u_readyName';
  static const String _keyNotes = 'u_readyNotes';
  static const String _keyConfirm = 'u_needsConfirm';
  static const String _keyLastCheck = 'u_lastCheck';
  static const String _keyFailed = 'u_failedCode';
  static const String _keyForcedAt = 'u_forcedAtVersion';
  static const int _maxApkBytes = 100 * 1024 * 1024;
  static const int _checkIntervalMs = 2 * 60 * 1000;

  final SharedPreferences _prefs;
  final Network _network;
  final QuickscopeApi _api;

  late final ValueNotifier<UpdateStatus> status;
  late final ValueNotifier<bool> forced;
  Future<void>? _checking;
  Directory? _dir;

  Updater(this._prefs, this._network, this._api) {
    forced = ValueNotifier<bool>(
      _prefs.getInt(_keyForcedAt) == AppConfig.versionCode,
    );
    status = ValueNotifier<UpdateStatus>(const UpdateIdle());
    _initDir();
  }

  Future<void> _initDir() async {
    try {
      final temp = await getTemporaryDirectory();
      _dir = Directory('${temp.path}/updates');
      final code = _prefs.getInt(_keyCode) ?? 0;
      if (code > AppConfig.versionCode && _apkFile(code).existsSync()) {
        status.value = UpdateReady(
          _prefs.getString(_keyName) ?? '',
          _prefs.getBool(_keyConfirm) ?? false,
          _prefs.getString(_keyNotes),
        );
      } else {
        _clearReady();
      }
    } catch (_) {}
  }

  File _apkFile(int code) =>
      File('${_dir?.path ?? Directory.systemTemp.path}/quickscope-$code.apk');

  void _clearReady() {
    _prefs
      ..remove(_keyCode)
      ..remove(_keyName)
      ..remove(_keyConfirm)
      ..remove(_keyNotes);
    try {
      if (_dir?.existsSync() ?? false) {
        for (final f in _dir!.listSync()) {
          f.deleteSync(recursive: true);
        }
      }
    } catch (_) {}
  }

  void _clearForced() {
    _prefs.remove(_keyForcedAt);
    forced.value = false;
  }

  /// Sofort sperren und die Update-Pruefung ohne Drosselung anstossen.
  void markForced() {
    if (!forced.value) {
      _prefs.setInt(_keyForcedAt, AppConfig.versionCode);
      forced.value = true;
    }
    checkInBackground(ignoreThrottle: true);
  }

  bool canInstall() => true;

  /// Automatische Pruefung beim App-Start; hoechstens alle paar Stunden.
  void checkInBackground({bool ignoreThrottle = false}) {
    unawaited(_enqueueCheck(manual: false, ignoreThrottle: ignoreThrottle));
  }

  /// Manuelle Suche aus dem Profil: zeigt Fortschritt und Fehler.
  void checkManually() {
    unawaited(_enqueueCheck(manual: true, ignoreThrottle: true));
  }

  Future<void> _enqueueCheck({
    required bool manual,
    required bool ignoreThrottle,
  }) async {
    while (_checking != null) {
      try {
        await _checking;
      } catch (_) {}
    }
    final completer = Completer<void>();
    _checking = completer.future;
    try {
      await _runCheck(manual: manual, ignoreThrottle: ignoreThrottle);
    } finally {
      completer.complete();
      _checking = null;
    }
  }

  Future<void> _runCheck({
    required bool manual,
    required bool ignoreThrottle,
  }) async {
    final before = status.value;
    var newerKnown = false;
    if (before is UpdateInstalling || before is UpdatePreparing) return;
    try {
      if (!manual) {
        if (before is UpdateReady) return;
        final last = _prefs.getInt(_keyLastCheck) ?? 0;
        if (!ignoreThrottle &&
            DateTime.now().millisecondsSinceEpoch - last < _checkIntervalMs) {
          return;
        }
      }
      final release = await _api.latestRelease();
      await _prefs.setInt(_keyLastCheck, DateTime.now().millisecondsSinceEpoch);
      if (release.versionCode <= AppConfig.versionCode) {
        _clearReady();
        _clearForced();
        status.value = const UpdateUpToDate();
        return;
      }
      newerKnown = true;
      if (!manual && release.versionCode <= (_prefs.getInt(_keyFailed) ?? 0)) {
        return;
      }
      if (Platform.isAndroid) {
        final have = _prefs.getInt(_keyCode) == release.versionCode &&
            _apkFile(release.versionCode).existsSync();
        if (!have) {
          status.value = UpdatePreparing(
            release.versionName,
            manual ? 0.0 : null,
            release.notes,
          );
          await _download(release, manual);
          await _prefs.setInt(_keyCode, release.versionCode);
          await _prefs.setString(_keyName, release.versionName);
          if (release.notes != null) {
            await _prefs.setString(_keyNotes, release.notes!);
          }
          await _prefs.setBool(_keyConfirm, false);
        }
      } else {
        await _prefs.setInt(_keyCode, release.versionCode);
        await _prefs.setString(_keyName, release.versionName);
        if (release.notes != null) {
          await _prefs.setString(_keyNotes, release.notes!);
        }
      }
      status.value = UpdateReady(
        release.versionName,
        _prefs.getBool(_keyConfirm) ?? false,
        release.notes,
      );
      if (manual) {
        await installNow();
      }
    } catch (e) {
      AppLog.w(_tag, 'Update-Pruefung fehlgeschlagen', e);
      if (e is ApiHttpException && e.code == 404) {
        _clearForced();
        status.value = const UpdateUpToDate();
        return;
      }
      final blocking = newerKnown ||
          forced.value ||
          (before is UpdateError && before.blocking) ||
          before is UpdateReady;
      final text = switch (e) {
        _UpdateException(:final message) => message,
        SocketException() || HttpException() => newerKnown
            ? 'Der Download ist fehlgeschlagen. Bitte Verbindung prüfen.'
            : 'Keine Verbindung zum Server.',
        _ => 'Update-Suche fehlgeschlagen.',
      };
      if (manual || newerKnown || forced.value) {
        status.value = UpdateError(text, blocking: blocking);
      } else if (before is UpdatePreparing) {
        status.value = const UpdateIdle();
      } else {
        status.value = before;
      }
    }
  }

  /// "Erneut versuchen" im Pflicht-Update-Screen.
  void retry() {
    _prefs.remove(_keyFailed);
    checkManually();
  }

  Future<void> _download(AppRelease release, bool withProgress) async {
    _clearReady();
    _dir?.createSync(recursive: true);
    final part = File(
      '${_dir?.path ?? Directory.systemTemp.path}/quickscope-${release.versionCode}.apk.part',
    );
    final target = _apkFile(release.versionCode);
    try {
      final url = _network.resolve('api/app/latest/apk');
      final resp = await _network.sendStream(method: 'GET', url: url);
      if (resp.statusCode < 200 || resp.statusCode >= 300) {
        throw HttpException('HTTP ${resp.statusCode}');
      }
      final maxBytes = release.size > 0
          ? release.size.clamp(0, _maxApkBytes)
          : _maxApkBytes;
      final contentLen = resp.contentLength ?? -1;
      if (contentLen > maxBytes) {
        throw const _UpdateException('Update ist zu groß. Abgebrochen.');
      }
      final total = contentLen > 0 ? contentLen : release.size;
      final sink = part.openWrite();
      final chunks = <int>[];
      var done = 0;
      var lastPct = -1;
      try {
        await for (final chunk in resp.stream) {
          sink.add(chunk);
          chunks.addAll(chunk);
          done += chunk.length;
          if (done > maxBytes) {
            throw const _UpdateException('Update ist zu groß. Abgebrochen.');
          }
          if (withProgress && total > 0) {
            final pct = (done * 100 ~/ total);
            if (pct != lastPct) {
              lastPct = pct;
              status.value = UpdatePreparing(
                release.versionName,
                pct / 100.0,
                release.notes,
              );
            }
          }
        }
      } finally {
        await sink.close();
      }
      final digest = sha256.convert(chunks).toString();
      if (release.sha256.isNotEmpty &&
          digest.toLowerCase() != release.sha256.toLowerCase()) {
        throw const _UpdateException(
          'Prüfsumme stimmt nicht überein. Update abgebrochen.',
        );
      }
      part.renameSync(target.path);
    } finally {
      if (part.existsSync()) {
        try {
          part.deleteSync();
        } catch (_) {}
      }
    }
  }

  void installIfReady() {
    // Auf Mobilgeräten erfordert die APK-Installation eine Nutzerbestätigung über den System-Installer
  }

  Future<void> installNow() async {
    if (status.value is! UpdateReady) return;
    final code = _prefs.getInt(_keyCode) ?? 0;
    final name = _prefs.getString(_keyName) ?? '';
    final notes = _prefs.getString(_keyNotes);
    if (Platform.isAndroid) {
      final file = _apkFile(code);
      if (!file.existsSync()) {
        _clearReady();
        status.value = const UpdateIdle();
        return;
      }
      status.value = const UpdateInstalling();
      try {
        final res = await OpenFilex.open(
          file.path,
          type: 'application/vnd.android.package-archive',
        );
        if (res.type != ResultType.done) {
          status.value = UpdateReady(name, true, notes);
        } else {
          status.value = UpdateReady(name, false, notes);
        }
      } catch (_) {
        status.value = const UpdateError(
          'Installation fehlgeschlagen.',
          blocking: true,
        );
      }
    } else {
      final url = _network.resolve('api/app/latest/apk');
      await launchUrl(url, mode: LaunchMode.externalApplication);
    }
  }
}
