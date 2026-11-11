import 'dart:convert';
import 'package:http/http.dart' as http;
import 'models.dart';
import 'network.dart';

class OfflinePackResponse {
  final int statusCode;
  final String body;
  final String? etag;

  const OfflinePackResponse({
    required this.statusCode,
    required this.body,
    this.etag,
  });

  bool get isSuccessful => statusCode >= 200 && statusCode < 300;
}

/// Vertrag mit dem Backend. Der SSE-Stream (`api/stream`) laeuft ueber [LiveSync], die Original-Vorschau ueber [MaterialPreview].
class QuickscopeApi {
  final Network network;

  QuickscopeApi(this.network);

  Future<Map<String, dynamic>> _getJson(
    String path, [
    Map<String, String?>? query,
    Duration timeout = const Duration(seconds: 60),
  ]) async {
    final resp = await network.send(
      method: 'GET',
      url: network.resolve(path, query),
      timeout: timeout,
    );
    _ensureSuccess(resp);
    return jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>;
  }

  Future<List<dynamic>> _getJsonList(
    String path, [
    Map<String, String?>? query,
  ]) async {
    final resp = await network.send(
      method: 'GET',
      url: network.resolve(path, query),
    );
    _ensureSuccess(resp);
    return jsonDecode(utf8.decode(resp.bodyBytes)) as List<dynamic>;
  }

  void _ensureSuccess(http.Response resp) {
    if (resp.statusCode < 200 || resp.statusCode >= 300) {
      throw ApiHttpException(
        resp.statusCode,
        utf8.decode(resp.bodyBytes, allowMalformed: true),
        resp.headers,
      );
    }
  }

  Future<Me> me() async => Me.fromJson(await _getJson('api/me'));

  Future<SessionResponse> createSession() async {
    final resp = await network.send(
      method: 'POST',
      url: network.resolve('api/auth/session'),
    );
    _ensureSuccess(resp);
    return SessionResponse.fromJson(
      jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>,
    );
  }

  Future<void> deleteMe() async {
    final resp = await network.send(
      method: 'DELETE',
      url: network.resolve('api/me'),
    );
    _ensureSuccess(resp);
  }

  /// Abmelden: die Sitzung auf dem Server beenden (neu im Backend; 404/405 werden ruhig behandelt).
  Future<void> logout() async {
    final resp = await network.send(
      method: 'POST',
      url: network.resolve('api/auth/logout'),
    );
    if (resp.statusCode == 404 || resp.statusCode == 405) return;
    _ensureSuccess(resp);
  }

  /// Eigene Daten als Datei (Streaming, nicht komplett im Speicher).
  Future<Stream<List<int>>> exportData() async {
    final resp = await network.sendStream(
      method: 'GET',
      url: network.resolve('api/me/export'),
    );
    if (resp.statusCode < 200 || resp.statusCode >= 300) {
      throw ApiHttpException(resp.statusCode);
    }
    return resp.stream;
  }

  Future<Profile> profile() async =>
      Profile.fromJson(await _getJson('api/me/profile'));

  Future<Profile> putProfile(ProfileUpdate body) async {
    final resp = await network.send(
      method: 'PUT',
      url: network.resolve('api/me/profile'),
      body: jsonEncode(body.toJson()),
    );
    _ensureSuccess(resp);
    return Profile.fromJson(
      jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>,
    );
  }

  /// Sprachnachricht (aac/m4a, bis ca. 60 s) -> Zusammenfassung zum Bearbeiten.
  Future<VoiceSummary> profileVoice(http.MultipartFile file) async {
    final url = network.resolve('api/me/profile/voice');
    final req = http.MultipartRequest('POST', url)
      ..headers.addAll(network.decorateHeaders(url))
      ..files.add(file);
    final streamed = await network.rawClient.send(req);
    final resp = await http.Response.fromStream(streamed);
    _ensureSuccess(resp);
    return VoiceSummary.fromJson(
      jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>,
    );
  }

  Future<AppRelease> latestRelease() async =>
      AppRelease.fromJson(await _getJson('api/app/latest'));

  Future<List<Exam>> exams() async => [
        for (final e in await _getJsonList('api/exams'))
          if (e is Map<String, dynamic>) Exam.fromJson(e),
      ];

  Future<Exam> createExam(ExamCreate body) async {
    final resp = await network.send(
      method: 'POST',
      url: network.resolve('api/exams'),
      body: jsonEncode(body.toJson()),
    );
    _ensureSuccess(resp);
    return Exam.fromJson(
      jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>,
    );
  }

  Future<Exam> exam(String id) async =>
      Exam.fromJson(await _getJson('api/exams/$id'));

  Future<Exam> updateExam(String id, ExamUpdate body) async {
    final resp = await network.send(
      method: 'PUT',
      url: network.resolve('api/exams/$id'),
      body: jsonEncode(body.toJson()),
    );
    _ensureSuccess(resp);
    return Exam.fromJson(
      jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>,
    );
  }

  Future<void> deleteExam(String id) async {
    final resp = await network.send(
      method: 'DELETE',
      url: network.resolve('api/exams/$id'),
    );
    _ensureSuccess(resp);
  }

  Future<List<MaterialItem>> materials(String id) async => [
        for (final m in await _getJsonList('api/exams/$id/materials'))
          if (m is Map<String, dynamic>) MaterialItem.fromJson(m),
      ];

  Future<void> deleteMaterial(String id, String materialId) async {
    final resp = await network.send(
      method: 'DELETE',
      url: network.resolve('api/exams/$id/materials/$materialId'),
    );
    _ensureSuccess(resp);
  }

  Future<List<Chapter>> chapters(String id) async => [
        for (final c in await _getJsonList('api/exams/$id/chapters'))
          if (c is Map<String, dynamic>) Chapter.fromJson(c),
      ];

  Future<List<Lesson>> lessons(String id, {String? chapterId}) async => [
        for (final l in await _getJsonList(
          'api/exams/$id/lessons',
          {'chapterId': chapterId},
        ))
          if (l is Map<String, dynamic>) Lesson.fromJson(l),
      ];

  Future<Audiobook> audiobook(
    String id, {
    int? minutes,
    bool? repeat,
  }) async =>
      Audiobook.fromJson(
        await _getJson('api/exams/$id/audiobook', {
          'minutes': minutes?.toString(),
          'repeat': repeat?.toString(),
        }),
      );

  Future<void> heard(String id, String lessonId, HeardRequest body) async {
    final resp = await network.send(
      method: 'POST',
      url: network.resolve('api/exams/$id/lessons/$lessonId/heard'),
      body: jsonEncode(body.toJson()),
    );
    _ensureSuccess(resp);
  }

  /// Fehlerprotokoll der App (gedrosselt); 404 oder Fehler werden ignoriert.
  Future<void> clientLog(ClientLog body) async {
    final resp = await network.send(
      method: 'POST',
      url: network.resolve('api/client-log'),
      body: jsonEncode(body.toJson()),
    );
    if (resp.statusCode == 404) return;
    _ensureSuccess(resp);
  }

  Future<AudioStatus> audioStatus({
    Duration timeout = const Duration(milliseconds: 6000),
  }) async =>
      AudioStatus.fromJson(
        await _getJson('api/audio/status', null, timeout),
      );

  Future<AudioTimings> itemTimings(
    String id,
    String itemId, {
    String part = 'question',
  }) async =>
      AudioTimings.fromJson(
        await _getJson('api/exams/$id/items/$itemId/audio/timings', {
          'part': part,
        }),
      );

  Future<List<Topic>> topics(String id) async => [
        for (final t in await _getJsonList('api/exams/$id/topics'))
          if (t is Map<String, dynamic>) Topic.fromJson(t),
      ];

  Future<LearnSession> session(
    String id, {
    int? minutes,
    bool? lessons,
  }) async =>
      LearnSession.fromJson(
        await _getJson('api/exams/$id/session', {
          'minutes': minutes?.toString(),
          'lessons': lessons?.toString(),
        }),
      );

  Future<AttemptsResponse> attempts(
    String id,
    List<AttemptRequest> body,
  ) async {
    final resp = await network.send(
      method: 'POST',
      url: network.resolve('api/exams/$id/attempts'),
      body: jsonEncode([for (final a in body) a.toJson()]),
    );
    _ensureSuccess(resp);
    return AttemptsResponse.fromJson(
      jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>,
    );
  }

  /// Offline-Paket der naechsten [days] Tage (1..7); mit ETag/If-None-Match (304 = unveraendert).
  Future<OfflinePackResponse> offlinePack(
    String id, {
    int days = 3,
    String? etag,
  }) async {
    final resp = await network.send(
      method: 'GET',
      url: network.resolve('api/exams/$id/offline-pack', {
        'days': days.toString(),
      }),
      headers: {if (etag != null && etag.isNotEmpty) 'If-None-Match': etag},
    );
    return OfflinePackResponse(
      statusCode: resp.statusCode,
      body: utf8.decode(resp.bodyBytes, allowMalformed: true),
      etag: resp.headers['etag'] ?? resp.headers['ETag'],
    );
  }

  Future<Forecast> forecast(String id) async =>
      Forecast.fromJson(await _getJson('api/exams/$id/forecast'));

  Future<CheatSheet> cheatsheet(String id, {int minutes = 5}) async =>
      CheatSheet.fromJson(
        await _getJson('api/exams/$id/cheatsheet', {
          'minutes': minutes.toString(),
        }),
      );

  /// Aufgaben neu erzeugen (202); der Lernstand wird zurueckgesetzt, die Pruefung ist danach wieder "processing".
  Future<void> regenerate(String id, {bool? resetProgress}) async {
    final resp = await network.send(
      method: 'POST',
      url: network.resolve('api/exams/$id/regenerate', {
        'resetProgress': resetProgress?.toString(),
      }),
    );
    _ensureSuccess(resp);
  }
}
