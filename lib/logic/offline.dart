import 'dart:convert';
import 'dart:math' as math;
import '../data/models.dart';

/// Meldung, wenn eine Aktion Internet braucht.
const String needsInternet = 'Dafür brauchst du Internet.';

/// "Nur zuhören" gibt es nur online, mit "Vorlesen an" und wenn Lektionen existieren.
bool canListen(bool online, bool audioEnabled, bool hasLessons) =>
    online && audioEnabled && hasLessons;

/// Wie viele Tage das Offline-Paket abdeckt.
const int offlineDays = 3;

/// Grobe Dauer einer Frage beim Fuellen einer Offline-Runde.
const int offlineItemSeconds = 20;

/// Offline-Paket (GET offline-pack): `queue` = Elemente der naechsten Tage in Lernreihenfolge (Lektionen und Fragen wie in der Session),
/// `reserveItems` = weitere Fragen fuer Wiederholungen, dazu `version` (= ETag-Inhalt), `generatedAt` und die Themen.
class OfflinePack {
  final List<SessionEntry> entries;
  final List<SessionEntry> reserve;
  final String version;
  final String generatedAt;
  final List<Topic> topics;
  final List<Chapter> chapters;

  const OfflinePack({
    required this.entries,
    required this.reserve,
    this.version = '',
    this.generatedAt = '',
    this.topics = const [],
    this.chapters = const [],
  });
}

/// Liest das Paket nach dem Vertrag; unbekannte Felder und einzelne kaputte Eintraege werden uebergangen. Ohne `queue`: null.
OfflinePack? parseOfflinePack(String text) {
  final Map<String, dynamic> root;
  try {
    final decoded = jsonDecode(text);
    if (decoded is! Map<String, dynamic>) return null;
    root = decoded;
  } catch (_) {
    return null;
  }

  List<SessionEntry>? parseEntries(String key) {
    final arr = root[key];
    if (arr is! List) return null;
    final out = <SessionEntry>[];
    for (final raw in arr) {
      if (raw is! Map<String, dynamic>) continue;
      try {
        final e = SessionEntry.fromJson(raw);
        if ((e.item?.id.trim().isNotEmpty ?? false) ||
            (e.lesson?.id.trim().isNotEmpty ?? false)) {
          out.add(e);
        }
      } catch (_) {}
    }
    return out;
  }

  final queue = parseEntries('queue');
  if (queue == null) return null;

  final topicsRaw = root['topics'];
  final topics = <Topic>[
    if (topicsRaw is List)
      for (final t in topicsRaw)
        if (t is Map<String, dynamic>)
          if ((t['id'] as String?)?.isNotEmpty ?? false) Topic.fromJson(t),
  ];

  final chaptersRaw = root['chapters'];
  final chapters = <Chapter>[
    if (chaptersRaw is List)
      for (final c in chaptersRaw)
        if (c is Map<String, dynamic>)
          if ((c['chapterId'] as String?)?.isNotEmpty ?? false) Chapter.fromJson(c),
  ];

  return OfflinePack(
    entries: queue,
    reserve: parseEntries('reserveItems') ?? const [],
    version: root['version'] is String ? root['version'] as String : '',
    generatedAt: root['generatedAt'] is String ? root['generatedAt'] as String : '',
    topics: topics,
    chapters: chapters,
  );
}

/// Schluessel fuer "schon erledigt": Aufgaben `q:<id>`, Lektionen `l:<id>`.
String? entryKey(SessionEntry e) {
  if (e.lesson != null) return 'l:${e.lesson!.id}';
  if (e.item != null) return 'q:${e.item!.id}';
  return null;
}

/// Offline-Runde: die Reihenfolge des Pakets, ohne schon Erledigtes (und ohne Doppelte), bis [seconds] gefuellt sind;
/// danach nur noch bei Bedarf aus der Reserve. [questionsOnly] laesst Lektionen weg.
List<SessionEntry> offlineRound(
  OfflinePack pack,
  Set<String> done,
  int seconds, {
  bool questionsOnly = false,
}) {
  final out = <SessionEntry>[];
  final seen = <String>{};
  var used = 0;
  for (final e in [...pack.entries, ...pack.reserve]) {
    if (used >= seconds) break;
    final key = entryKey(e);
    if (key == null || done.contains(key) || !seen.add(key)) continue;
    if (questionsOnly && e.lesson != null) continue;
    out.add(e);
    used += e.lesson?.estSeconds ?? e.item?.estSeconds ?? offlineItemSeconds;
  }
  return out;
}

/// Einfuegeposition einer falsch beantworteten Aufgabe offline: nach 4 bis 8 weiteren Aufgaben, hoechstens ans Ende.
int offlineRetryIndex(int current, int size, String key) {
  final gap = 4 + (key.hashCode.abs() % 5);
  return math.min(current + 1 + gap, size);
}

/// Bei "Weiss nicht" offline: die naechste Lektion des Pakets, die weder in der Runde ist noch erledigt wurde.
SessionLesson? nextOfflineLesson(
  OfflinePack pack,
  Set<String> presentLessonIds,
  Set<String> done,
) {
  for (final e in [...pack.entries, ...pack.reserve]) {
    final l = e.lesson;
    if (l != null &&
        !presentLessonIds.contains(l.id) &&
        !done.contains('l:${l.id}')) {
      return l;
    }
  }
  return null;
}
