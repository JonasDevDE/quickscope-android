import 'dart:io';

/// App-Konfiguration (BuildConfig-Äquivalent für Flutter iOS & Android).
/// Werte können per `--dart-define=BASE_URL=...` und `--dart-define=GOOGLE_WEB_CLIENT_ID=...`
/// beim Bauen übergeben werden.
class AppConfig {
  static const String baseUrl = String.fromEnvironment(
    'BASE_URL',
    defaultValue: 'http://fast-hawk.cloudgate.darkstone.de:40029/',
  );

  static const String googleWebClientId = String.fromEnvironment(
    'GOOGLE_WEB_CLIENT_ID',
    defaultValue:
        '792487328287-1qjj1ugt3qgj7v0bukrou8e3qc9h7i2j.apps.googleusercontent.com',
  );

  static const String googleIosClientId = String.fromEnvironment(
    'GOOGLE_IOS_CLIENT_ID',
    defaultValue:
        '792487328287-3mh81pmv88ap8hh5oenevsm1h29203hd.apps.googleusercontent.com',
  );

  // Muss zu pubspec.yaml (version: NAME+CODE) passen; test/version_test.dart prueft das.
  static const int versionCode = 31;
  static const String versionName = '0.7.0';

  /// Entwickler-Flag (`--dart-define=DISABLE_AUTO_UPDATE=true`), um den Auto-Updater
  /// und den Server-Versionszwang beim lokalen Flashen zu umgehen.
  static const bool disableAutoUpdate = bool.fromEnvironment(
    'DISABLE_AUTO_UPDATE',
    defaultValue: false,
  );

  static int get reportedVersionCode =>
      disableAutoUpdate ? 999999 : versionCode;

  static String get normalizedBaseUrl =>
      baseUrl.endsWith('/') ? baseUrl : '$baseUrl/';

  static String get userAgent {
    final platform = Platform.isIOS ? 'iOS' : 'Android';
    return 'Quickscope/$versionName ($platform)';
  }
}
