import 'dart:async';
import 'package:connectivity_plus/connectivity_plus.dart' as cp;
import 'package:flutter/foundation.dart';

/// Online-Zustand ueber ConnectivityPlus: beobachtet Netzaenderungen und erlaubt [markOffline] bei fehlgeschlagenen Requests.
class Connectivity {
  final cp.Connectivity _plugin = cp.Connectivity();
  final ValueNotifier<bool> online = ValueNotifier<bool>(true);
  StreamSubscription<List<cp.ConnectivityResult>>? _sub;

  Connectivity() {
    _init();
  }

  Future<void> _init() async {
    try {
      final initial = await _plugin.checkConnectivity();
      online.value = _hasConnection(initial);
      _sub = _plugin.onConnectivityChanged.listen((results) {
        online.value = _hasConnection(results);
      });
    } catch (_) {}
  }

  bool _hasConnection(List<cp.ConnectivityResult> results) =>
      results.isNotEmpty &&
      !results.every((r) => r == cp.ConnectivityResult.none);

  bool isOnline() => online.value;

  /// Ein fehlgeschlagener Request deutet auf "offline" hin (zusaetzlich zur Netzwerk-Meldung).
  void markOffline() => online.value = false;

  void markOnline() => online.value = true;

  void dispose() => _sub?.cancel();
}
