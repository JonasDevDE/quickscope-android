import 'dart:async';
import 'dart:convert';
import 'package:flutter/widgets.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:google_sign_in/google_sign_in.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../logic/security.dart';

sealed class SignInResult {
  const SignInResult();
}

class SignInSuccess extends SignInResult {
  const SignInSuccess();
}

class SignInCancelled extends SignInResult {
  const SignInCancelled();
}

class SignInFailure extends SignInResult {
  final String message;
  const SignInFailure(this.message);
}

class AuthManager {
  static const _keyToken = 'id_token';
  static const _keyPicture = 'picture_url';

  final SharedPreferences _prefs;
  final FlutterSecureStorage _secrets;
  final String serverClientId;
  late final GoogleSignIn _googleSignIn;

  final _sessionExpired = StreamController<void>.broadcast();
  Future<String?>? _refreshing;
  String? _token;
  bool inForeground = true;

  AuthManager(this._prefs, this._secrets, {required this.serverClientId}) {
    _googleSignIn = GoogleSignIn(
      serverClientId: serverClientId.isNotEmpty ? serverClientId : null,
      scopes: const ['email', 'profile', 'openid'],
    );
  }

  Future<void> init() async {
    final legacyPlain = _prefs.getString(_keyToken);
    if (needsSecretMigration(legacyPlain)) {
      await _secrets.write(key: _keyToken, value: legacyPlain);
      await _prefs.remove(_keyToken);
      _token = legacyPlain;
    } else {
      try {
        _token = await _secrets.read(key: _keyToken);
      } catch (_) {
        _token = null;
      }
    }
  }

  String? get token => _token;

  /// Profilbild aus dem Google-Credential (sofort verfuegbar, bevor /api/me geantwortet hat).
  String? get pictureUrl => _prefs.getString(_keyPicture);

  Stream<void> get sessionExpired => _sessionExpired.stream;
  bool get isConfigured => serverClientId.trim().isNotEmpty;

  bool get canPrompt =>
      inForeground &&
      (WidgetsBinding.instance.lifecycleState == null ||
          WidgetsBinding.instance.lifecycleState == AppLifecycleState.resumed);

  /// Endgueltiges 401 trotz frischem Token: Login anfordern, aber nur im Vordergrund.
  void notifyRejectedAfterRefresh() {
    if (canPrompt) _sessionExpired.add(null);
  }

  bool hasValidToken() {
    final t = _token;
    return t != null && (_isSession(t) || !_isExpired(t));
  }

  /// Das Token ist noch ein kurzlebiges Google-Token und sollte gegen eine Sitzung getauscht werden.
  bool get needsSessionUpgrade => _token != null && !_isSession(_token!);

  Future<void> storeSession(String sessionToken) => _store(sessionToken);

  bool _isSession(String t) => t.startsWith('bst_');

  Future<SignInResult> signIn() async {
    if (!isConfigured) {
      return const SignInFailure(
        'Google-Client-ID fehlt. Bitte GOOGLE_WEB_CLIENT_ID setzen und neu bauen.',
      );
    }
    try {
      final account = await _googleSignIn.signIn();
      if (account == null) return const SignInCancelled();
      final auth = await account.authentication;
      final idToken = auth.idToken;
      if (idToken == null || idToken.isEmpty) {
        return const SignInFailure('Anmeldung lieferte kein Google-Token.');
      }
      await _store(idToken);
      if (account.photoUrl != null) {
        await _prefs.setString(_keyPicture, account.photoUrl!);
      }
      return const SignInSuccess();
    } catch (e) {
      return SignInFailure('Anmeldung fehlgeschlagen: $e');
    }
  }

  Future<String?> _refreshSilently() async {
    if (!isConfigured) return null;
    try {
      final account = await _googleSignIn.signInSilently();
      if (account == null) return null;
      final auth = await account.authentication;
      final idToken = auth.idToken;
      if (idToken != null && idToken.isNotEmpty) {
        await _store(idToken);
        return idToken;
      }
      return null;
    } catch (_) {
      return null;
    }
  }

  /// Nach einem 401: liefert ein neues Token (anders als das abgelehnte), aktualisiert hoechstens einmal gleichzeitig.
  Future<String?> refreshAfterRejection(String? rejected) async {
    if (_refreshing != null) return _refreshing;
    final completer = Completer<String?>();
    _refreshing = completer.future;
    try {
      final current = _token;
      if (current != null &&
          current != rejected &&
          (_isSession(current) || !_isExpired(current))) {
        completer.complete(current);
        return current;
      }
      if (rejected != null && _isSession(rejected) && current == rejected) {
        _token = null;
        try {
          await _secrets.delete(key: _keyToken);
        } catch (_) {}
      }
      if (!canPrompt) {
        completer.complete(null);
        return null;
      }
      final fresh = await _refreshSilently();
      if (fresh == null) {
        _sessionExpired.add(null);
      }
      completer.complete(fresh);
      return fresh;
    } finally {
      _refreshing = null;
    }
  }

  Future<bool> ensureToken() async {
    if (hasValidToken()) return true;
    final fresh = await _refreshSilently();
    return fresh != null;
  }

  Future<void> signOut() async {
    _token = null;
    try {
      await _secrets.delete(key: _keyToken);
    } catch (_) {}
    await _prefs.remove(_keyToken);
    await _prefs.remove(_keyPicture);
    try {
      await _googleSignIn.signOut();
    } catch (_) {}
  }

  Future<void> _store(String idToken) async {
    _token = idToken;
    try {
      await _secrets.write(key: _keyToken, value: idToken);
    } catch (_) {}
  }

  bool _isExpired(String jwt) {
    try {
      final parts = jwt.split('.');
      if (parts.length < 2) return true;
      final normalized = base64Url.normalize(parts[1]);
      final payload = utf8.decode(base64Url.decode(normalized));
      final match = RegExp(r'"exp"\s*:\s*(\d+)').firstMatch(payload);
      final exp = int.tryParse(match?.group(1) ?? '');
      if (exp == null) return true;
      return exp * 1000 - 60000 < DateTime.now().millisecondsSinceEpoch;
    } catch (_) {
      return true;
    }
  }
}
