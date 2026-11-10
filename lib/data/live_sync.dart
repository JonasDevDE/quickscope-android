import 'dart:async';
import 'dart:convert';
import 'package:http/http.dart' as http;
import 'network.dart';

/// Nutzlast des SSE-Ereignisses `change`. kind benennt den geaenderten Datenbereich, by den ausloesenden Nutzer.
class LiveChange {
  final String kind;
  final String? scopeId;
  final String? by;

  const LiveChange({required this.kind, this.scopeId, this.by});

  factory LiveChange.fromJson(Map<String, dynamic> json) => LiveChange(
        kind: json['kind'] is String ? json['kind'] as String : 'unknown',
        scopeId: json['scopeId'] is String ? json['scopeId'] as String : null,
        by: json['by'] is String ? json['by'] as String : null,
      );
}

/// Nutzlast des SSE-Ereignisses `update`.
class LiveUpdate {
  final int versionCode;
  final String? versionName;
  final String? notes;

  const LiveUpdate({
    required this.versionCode,
    this.versionName,
    this.notes,
  });

  factory LiveUpdate.fromJson(Map<String, dynamic> json) => LiveUpdate(
        versionCode: switch (json['versionCode']) {
          final int v => v,
          final num v => v.toInt(),
          _ => 0,
        },
        versionName:
            json['versionName'] is String ? json['versionName'] as String : null,
        notes: json['notes'] is String ? json['notes'] as String : null,
      );
}

class _Session {
  bool gotHello = false;
  bool stalled = false;
  int code = 0;
}

/// Haelt eine SSE-Verbindung (`GET api/stream`) fuer den Nutzer offen und meldet Aenderungen.
/// Reconnect mit Backoff (1, 2, 4 ... max 30 s); nach jedem (Wieder-)Verbinden [onConnected].
/// Fallback auf Polling ([onPoll] alle 10 s), wenn 3 Versuche in Folge scheitern oder der Proxy den Stream puffert.
class LiveSync {
  static const int _initialBackoffMs = 1000;
  static const int _maxBackoffMs = 30000;
  static const int _maxFailures = 3;
  static const int _heartbeatTimeoutMs = 45000;
  static const int _helloTimeoutMs = 20000;
  static const int _watchdogStepMs = 5000;
  static const int _pollIntervalMs = 10000;
  static const int _pollPhaseMs = 120000;

  final Network _network;
  final void Function(LiveChange) onChange;
  final void Function() onConnected;
  final void Function() onPoll;
  final void Function() onForbidden;
  final void Function(LiveUpdate) onUpdate;

  bool _running = false;
  int _runGeneration = 0;
  http.Client? _activeClient;

  LiveSync({
    required Network network,
    required this.onChange,
    required this.onConnected,
    required this.onPoll,
    void Function()? onForbidden,
    void Function(LiveUpdate)? onUpdate,
  })  : _network = network,
        onForbidden = onForbidden ?? (() {}),
        onUpdate = onUpdate ?? ((_) {});

  void start() {
    if (_running) return;
    _running = true;
    final gen = ++_runGeneration;
    unawaited(_loop(gen));
  }

  void stop() {
    _running = false;
    _runGeneration++;
    _activeClient?.close();
    _activeClient = null;
  }

  bool _isActive(int gen) => _running && _runGeneration == gen;

  Future<void> _loop(int gen) async {
    var failures = 0;
    var backoff = _initialBackoffMs;
    while (_isActive(gen)) {
      final session = _Session();
      try {
        await _connectOnce(gen, session);
      } catch (_) {}
      if (!_isActive(gen)) break;

      if (session.code == 403 || session.code == 404) onForbidden();
      if (session.gotHello) {
        failures = 0;
        backoff = _initialBackoffMs;
      } else {
        failures++;
      }

      if (session.stalled || failures >= _maxFailures) {
        await _pollFor(gen, _pollPhaseMs);
        failures = _maxFailures - 1;
        backoff = _initialBackoffMs;
        continue;
      }

      await Future<void>.delayed(Duration(milliseconds: backoff));
      backoff = (backoff * 2).clamp(_initialBackoffMs, _maxBackoffMs);
    }
  }

  Future<void> _pollFor(int gen, int durationMs) async {
    final until = DateTime.now().millisecondsSinceEpoch + durationMs;
    if (_isActive(gen)) onPoll();
    while (_isActive(gen) && DateTime.now().millisecondsSinceEpoch < until) {
      await Future<void>.delayed(const Duration(milliseconds: _pollIntervalMs));
      if (_isActive(gen)) onPoll();
    }
  }

  Future<void> _connectOnce(int gen, _Session session) async {
    final url = _network.resolve('api/stream');
    final client = http.Client();
    _activeClient = client;
    var lastActivity = DateTime.now().millisecondsSinceEpoch;

    final watchdog = Timer.periodic(
      const Duration(milliseconds: _watchdogStepMs),
      (timer) {
        if (!_isActive(gen)) {
          timer.cancel();
          client.close();
          return;
        }
        final limit = session.gotHello ? _heartbeatTimeoutMs : _helloTimeoutMs;
        if (DateTime.now().millisecondsSinceEpoch - lastActivity > limit) {
          if (!session.gotHello) session.stalled = true;
          timer.cancel();
          client.close();
        }
      },
    );

    try {
      final req = http.Request('GET', url)
        ..headers.addAll(
          _network.decorateHeaders(url, {
            'Accept': 'text/event-stream',
            'Cache-Control': 'no-cache',
          }),
        );
      final resp = await client.send(req);
      session.code = resp.statusCode;
      if (resp.statusCode == 401 && _network.isApiHost(url)) {
        final fresh = await _network.auth.refreshAfterRejection(
          _network.auth.token,
        );
        if (fresh == null) {
          _network.auth.notifyRejectedAfterRefresh();
        }
        return;
      }
      if (resp.statusCode < 200 || resp.statusCode >= 300) return;

      lastActivity = DateTime.now().millisecondsSinceEpoch;
      var name = 'message';
      final data = StringBuffer();

      await for (final line
          in resp.stream.transform(utf8.decoder).transform(const LineSplitter())) {
        if (!_isActive(gen)) break;
        lastActivity = DateTime.now().millisecondsSinceEpoch;
        if (line.isEmpty) {
          if (data.isNotEmpty || name != 'message') {
            _dispatch(name, data.toString(), session);
          }
          name = 'message';
          data.clear();
        } else if (line.startsWith(':')) {
          // Heartbeat-Kommentar
        } else if (line.startsWith('event:')) {
          name = line.substring(6).trim();
        } else if (line.startsWith('data:')) {
          if (data.isNotEmpty) data.write('\n');
          final payload = line.substring(5);
          data.write(payload.startsWith(' ') ? payload.substring(1) : payload);
        }
      }
    } finally {
      watchdog.cancel();
      client.close();
      if (identical(_activeClient, client)) _activeClient = null;
    }
  }

  void _dispatch(String name, String data, _Session session) {
    switch (name) {
      case 'hello':
        session.gotHello = true;
        onConnected();
      case 'change':
        LiveChange change;
        try {
          change = LiveChange.fromJson(
            jsonDecode(data) as Map<String, dynamic>,
          );
        } catch (_) {
          change = const LiveChange(kind: 'unknown');
        }
        onChange(change);
      case 'update':
        try {
          final update = LiveUpdate.fromJson(
            jsonDecode(data) as Map<String, dynamic>,
          );
          onUpdate(update);
        } catch (_) {}
    }
  }
}
