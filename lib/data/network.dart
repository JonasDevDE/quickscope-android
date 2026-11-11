import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'package:http/http.dart' as http;
import '../config.dart';
import 'auth.dart';
import 'store.dart';

class ApiHttpException implements Exception {
  final int code;
  final String body;
  final Map<String, String> headers;

  const ApiHttpException(this.code, [this.body = '', this.headers = const {}]);

  @override
  String toString() => 'HTTP $code';
}

/// Meldungen ohne Hinweise auf KI oder Dienstleister: solche Texte werden neutral ersetzt.
String neutralMessage(String text) => RegExp(
      r'\bKI\b|Gemini|Google|Sprach-Limit|nicht eingerichtet',
      caseSensitive: false,
    ).hasMatch(text)
        ? 'Gerade nicht verfügbar.'
        : text;

/// Liest das Feld `message` aus dem Spring-Fehlerbody (`{timestamp,status,error,message,path}`); null, wenn nicht vorhanden.
String? backendMessage(Object e) {
  if (e is! ApiHttpException || e.body.isEmpty) return null;
  try {
    final decoded = jsonDecode(e.body);
    if (decoded is Map<String, dynamic>) {
      final msg = decoded['message'];
      if (msg is String && msg.trim().isNotEmpty) {
        return neutralMessage(msg.trim());
      }
    }
  } catch (_) {}
  return null;
}

class Network {
  static const _retryDelaysMs = [1000, 2000, 4000];
  static const _retryCodes = {502, 503, 504};

  final AuthManager auth;
  final Store store;
  final Uri apiUrl = Uri.parse(AppConfig.baseUrl);
  final http.Client rawClient = http.Client();

  /// Wird bei HTTP 426 (Upgrade Required) auf API-Pfaden ausser /api/app/ aufgerufen.
  void Function()? onUpgradeRequired;

  Network(this.auth, this.store);

  /// Das Token gehoert nur an den eigenen API-Host (nicht an Bild-CDNs wie lh3.googleusercontent.com).
  bool isApiHost(Uri url) =>
      url.host == apiUrl.host &&
      url.port == apiUrl.port &&
      url.scheme == apiUrl.scheme;

  Uri resolve(String path, [Map<String, String?>? query]) {
    final base = apiUrl.resolve(path);
    if (query == null || query.isEmpty) return base;
    final clean = <String, String>{
      for (final e in query.entries)
        if (e.value != null) e.key: e.value!,
    };
    return clean.isEmpty ? base : base.replace(queryParameters: clean);
  }

  Map<String, String> decorateHeaders(
    Uri url, [
    Map<String, String>? extra,
    String? overrideToken,
  ]) {
    final headers = <String, String>{...?extra};
    if (!isApiHost(url)) {
      final platform = Platform.isIOS ? 'iOS' : 'Android';
      headers['User-Agent'] = 'Quickscope/${AppConfig.versionName} ($platform)';
      headers.remove('Authorization');
      headers.remove('X-App-Version');
      return headers;
    }
    headers['X-App-Version'] = AppConfig.reportedVersionCode.toString();
    final t = overrideToken ?? auth.token;
    if (t != null && t.isNotEmpty) {
      headers['Authorization'] = 'Bearer $t';
    }
    return headers;
  }

  void _checkUpgrade(Uri url, int statusCode) {
    if (AppConfig.disableAutoUpdate) return;
    if (statusCode == 426 &&
        isApiHost(url) &&
        !url.path.startsWith('/api/app/')) {
      onUpgradeRequired?.call();
    }
  }

  Future<http.Response> send({
    required String method,
    required Uri url,
    Map<String, String>? headers,
    Object? body,
    Duration timeout = const Duration(seconds: 60),
  }) async {
    Future<http.Response> executeOnce([String? tokenOverride]) async {
      final req = http.Request(method, url);
      req.headers.addAll(decorateHeaders(url, headers, tokenOverride));
      if (body is String) {
        req.headers.putIfAbsent(
          'Content-Type',
          () => 'application/json; charset=UTF-8',
        );
        req.body = body;
      } else if (body is List<int>) {
        req.bodyBytes = body;
      }
      final streamed = await rawClient.send(req).timeout(timeout);
      final resp = await http.Response.fromStream(streamed).timeout(timeout);
      _checkUpgrade(url, resp.statusCode);
      return resp;
    }

    var attempt = 0;
    while (true) {
      http.Response? response;
      Object? failure;
      try {
        response = await executeOnce();
      } on IOException catch (e) {
        failure = e;
      } on TimeoutException catch (e) {
        failure = e;
      } on http.ClientException catch (e) {
        failure = e;
      }

      if (response != null && response.statusCode == 401 && isApiHost(url)) {
        final rejected = auth.token;
        final fresh = await auth.refreshAfterRejection(rejected);
        if (fresh == null) {
          auth.notifyRejectedAfterRefresh();
          return response;
        }
        final retried = await executeOnce(fresh);
        if (retried.statusCode == 401) {
          auth.notifyRejectedAfterRefresh();
        }
        return retried;
      }

      final canRetry = method == 'GET' &&
          isApiHost(url) &&
          (failure != null || _retryCodes.contains(response?.statusCode));
      if (!canRetry || attempt >= _retryDelaysMs.length) {
        if (response != null) return response;
        Error.throwWithStackTrace(failure!, StackTrace.current);
      }
      await Future<void>.delayed(Duration(milliseconds: _retryDelaysMs[attempt]));
      attempt++;
    }
  }

  Future<http.StreamedResponse> sendStream({
    required String method,
    required Uri url,
    Map<String, String>? headers,
    Duration? timeout,
  }) async {
    Future<http.StreamedResponse> executeOnce([String? tokenOverride]) async {
      final req = http.Request(method, url);
      req.headers.addAll(decorateHeaders(url, headers, tokenOverride));
      final future = rawClient.send(req);
      final resp = timeout != null ? await future.timeout(timeout) : await future;
      _checkUpgrade(url, resp.statusCode);
      return resp;
    }

    final resp = await executeOnce();
    if (resp.statusCode == 401 && isApiHost(url)) {
      final rejected = auth.token;
      final fresh = await auth.refreshAfterRejection(rejected);
      if (fresh == null) {
        auth.notifyRejectedAfterRefresh();
        return resp;
      }
      final retried = await executeOnce(fresh);
      if (retried.statusCode == 401) {
        auth.notifyRejectedAfterRefresh();
      }
      return retried;
    }
    return resp;
  }
}
