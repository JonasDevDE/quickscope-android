import 'dart:io';

/// App-Konfiguration (BuildConfig-Äquivalent für Flutter iOS & Android).
/// Werte können per `--dart-define=BASE_URL=...` und `--dart-define=GOOGLE_WEB_CLIENT_ID=...`
/// beim Bauen übergeben werden.
class AppConfig {
  static const String baseUrl = String.fromEnvironment(
    'BASE_URL',
    defaultValue: 'https://quickscope.invalid/',
  );

  static const String googleWebClientId = String.fromEnvironment(
    'GOOGLE_WEB_CLIENT_ID',
    defaultValue: '',
  );

  static const int versionCode = 28;
  static const String versionName = '0.6.2';

  static String get normalizedBaseUrl =>
      baseUrl.endsWith('/') ? baseUrl : '$baseUrl/';

  static String get userAgent {
    final platform = Platform.isIOS ? 'iOS' : 'Android';
    return 'Quickscope/$versionName ($platform)';
  }
}
