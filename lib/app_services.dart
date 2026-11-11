import 'dart:async';
import 'dart:io';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:path_provider/path_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'config.dart';
import 'data/api.dart';
import 'data/attempt_sync.dart';
import 'data/audio_client.dart';
import 'data/auth.dart';
import 'data/connectivity.dart';
import 'data/local_data.dart';
import 'data/material_preview.dart';
import 'data/network.dart';
import 'data/offline_sync.dart';
import 'data/store.dart';
import 'data/updater.dart';
import 'logic/attempt_queue.dart';
import 'ui/sfx.dart';
import 'ui/voice.dart';

/// Zentrale Anwendungs-Dienste (Pendant zu QuickscopeApp).
class AppServices {
  static AppServices? _instance;
  static AppServices get instance => _instance!;

  final SharedPreferences prefs;
  final Directory filesDir;
  final Directory cacheDir;

  late final Store store = Store(prefs);
  late final AuthManager auth = AuthManager(
    prefs,
    const FlutterSecureStorage(),
    serverClientId: AppConfig.googleWebClientId,
    iosClientId: AppConfig.googleIosClientId,
  );
  late final Network network = Network(auth, store);
  late final QuickscopeApi api = QuickscopeApi(network);
  late final AttemptSync attemptSync = AttemptSync(api, AttemptQueue(store));
  late final LocalData local = LocalData(Directory('${filesDir.path}/offline'));
  late final OfflineSync offlineSync = OfflineSync(api, local, attemptSync);
  late final Connectivity connectivity = Connectivity();
  late final AudioClient audio = AudioClient(api, auth, store, connectivity);
  late final MaterialPreview preview = MaterialPreview(network);
  late final Sfx sfx = Sfx();
  late final Voice voice = Voice(audio, network);
  late final Updater updater = Updater(prefs, network, api);

  AppServices._({
    required this.prefs,
    required this.filesDir,
    required this.cacheDir,
  });

  static Future<AppServices> initialize() async {
    if (_instance != null) return _instance!;
    final prefs = await SharedPreferences.getInstance();
    final filesDir = await getApplicationSupportDirectory();
    final cacheDir = await getTemporaryDirectory();
    final services = AppServices._(
      prefs: prefs,
      filesDir: filesDir,
      cacheDir: cacheDir,
    );
    await services.auth.init();
    unawaited(services.sfx.init());
    services._cleanupVoiceDrafts();
    services.network.onUpgradeRequired = () => services.updater.markForced();
    _instance = services;
    return services;
  }

  void _cleanupVoiceDrafts() {
    try {
      if (cacheDir.existsSync()) {
        for (final entity in cacheDir.listSync()) {
          final name = entity.path.split('/').last;
          if (name.startsWith('voice_')) {
            entity.deleteSync(recursive: true);
          }
        }
      }
    } catch (_) {}
  }

  /// Beim Abmelden und Konto loeschen: alles lokal entfernen.
  Future<void> wipeLocalData() async {
    local.clear();
    await store.clearAll();
    for (final folder in ['previews', 'camera', 'exports', 'image_cache']) {
      final d = Directory('${cacheDir.path}/$folder');
      if (d.existsSync()) {
        try {
          d.deleteSync(recursive: true);
        } catch (_) {}
      }
    }
    _cleanupVoiceDrafts();
  }
}
