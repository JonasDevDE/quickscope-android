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
        '583865549790-v58o5eu75ltkm8a7ugn8trd1o3p1bfic.apps.googleusercontent.com',
  );

  static const String googleIosClientId = String.fromEnvironment(
    'GOOGLE_IOS_CLIENT_ID',
    defaultValue: '',
  );

  static const int versionCode = 29;
  static const String versionName = '0.6.3';

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
