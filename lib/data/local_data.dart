import 'dart:convert';
import 'dart:io';
import '../logic/offline.dart';
import 'models.dart';

/// Lokaler Datenspeicher fuer den Offline-Betrieb: kleine JSON-Dateien in einem eigenen Ordner, atomar geschrieben
/// (temporaere Datei, dann Umbenennen), mit Versionsfeld. Nicht lesbare oder fremde Versionen zaehlen als "nichts da".
class LocalData {
  static const int version = 1;

  final Directory dir;

  LocalData(this.dir);

  String _safe(String name) =>
      name.replaceAll(RegExp(r'[^A-Za-z0-9_.-]'), '_');

  File _file(String name) => File('${dir.path}/${_safe(name)}.json');

  void put(String name, Object? data) {
    if (!dir.existsSync()) dir.createSync(recursive: true);
    final tmp = File('${dir.path}/${_safe(name)}.tmp');
    tmp.writeAsStringSync(jsonEncode({'v': version, 'd': data}));
    try {
      tmp.renameSync(_file(name).path);
    } catch (_) {
      tmp.copySync(_file(name).path);
      try {
        tmp.deleteSync();
      } catch (_) {}
    }
  }

  Object? get(String name) {
    final f = _file(name);
    if (!f.existsSync()) return null;
    try {
      final root = jsonDecode(f.readAsStringSync());
      if (root is! Map<String, dynamic> || root['v'] != version) return null;
      return root['d'];
    } catch (_) {
      return null;
    }
  }

  void delete(String name) {
    final f = _file(name);
    if (f.existsSync()) {
      try {
        f.deleteSync();
      } catch (_) {}
    }
  }

  void clear() {
    if (dir.existsSync()) {
      try {
        dir.deleteSync(recursive: true);
      } catch (_) {}
    }
  }

  void saveMe(Me me) => put('me', me.toJson());
  Me? me() => switch (get('me')) {
        final Map<String, dynamic> m => Me.fromJson(m),
        _ => null,
      };

  void saveExams(List<Exam> list) =>
      put('exams', [for (final e in list) e.toJson()]);

  List<Exam>? exams() {
    final raw = get('exams');
    if (raw is! List) return null;
    final list = [
      for (final e in raw)
        if (e is Map<String, dynamic>) Exam.fromJson(e),
    ];
    return list.isNotEmpty ? list : null;
  }

  void saveForecast(String examId, Forecast f) =>
      put('forecast_$examId', f.toJson());

  Forecast? forecast(String examId) => switch (get('forecast_$examId')) {
        final Map<String, dynamic> m => Forecast.fromJson(m),
        _ => null,
      };

  void saveTopics(String examId, List<Topic> list) =>
      put('topics_$examId', [for (final t in list) t.toJson()]);

  List<Topic> topics(String examId) => switch (get('topics_$examId')) {
        final List<dynamic> list => [
            for (final t in list)
              if (t is Map<String, dynamic>) Topic.fromJson(t),
          ],
        _ => const [],
      };

  /// Das Paket wird als Rohtext des Servers abgelegt und beim Lesen neu geparst.
  void savePack(String examId, String rawJson, int days, String? etag) {
    try {
      put('pack_$examId', jsonDecode(rawJson));
      put('packmeta_$examId', {
        'days': days,
        'etag': etag ?? '',
        'at': DateTime.now().millisecondsSinceEpoch,
      });
    } catch (_) {}
  }

  /// ETag des gespeicherten Pakets fuer If-None-Match (nur, wenn das Paket selbst noch da ist).
  String? packEtag(String examId) {
    if (get('pack_$examId') == null) return null;
    final meta = get('packmeta_$examId');
    if (meta is Map<String, dynamic>) {
      final etag = meta['etag'];
      if (etag is String && etag.trim().isNotEmpty) return etag;
    }
    return null;
  }

  OfflinePack? pack(String examId) {
    final raw = get('pack_$examId');
    return raw == null ? null : parseOfflinePack(jsonEncode(raw));
  }

  /// Tage des bereitliegenden Pakets (kleine Datei, ohne das Paket zu parsen) oder null.
  int? packDays(String examId) => switch (get('packmeta_$examId')) {
        {'days': final int d} => d,
        {'days': final num d} => d.toInt(),
        _ => null,
      };

  Set<String> doneKeys(String examId) => switch (get('done_$examId')) {
        final List<dynamic> list => {
            for (final s in list)
              if (s is String) s,
          },
        _ => <String>{},
      };

  void markDone(String examId, String key) =>
      put('done_$examId', {...doneKeys(examId), key}.toList());

  void clearDone(String examId) => delete('done_$examId');
}
