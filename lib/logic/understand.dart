import '../data/models.dart';
import 'audiobook.dart';
import 'lesson_header.dart';

/// Alle Lektionen in Kapitel-Reihenfolge (ohne Limit) und die Startposition: die erste noch nicht gehoerte Lektion.
class UnderstandList {
  final List<SessionLesson> lessons;
  final int start;
  const UnderstandList(this.lessons, this.start);
}

/// Baut die Verstehen-Liste: Lektionen nach Kapitelreihenfolge (innerhalb eines Kapitels bleibt die gelieferte Reihenfolge), dazu die
/// Kapitelangaben fuer die Kopfzeile ("Kapitel 2 von 6", "Lektion 3 von 5"). Start = erste ungehoerte, sonst die erste.
UnderstandList buildUnderstandList(
  List<Chapter> chapters,
  List<SessionLesson> lessons,
  Set<String> heard,
) {
  final orderOf = {for (final c in chapters) c.chapterId: c.order};
  final indexed = [for (var i = 0; i < lessons.length; i++) (i, lessons[i])];
  indexed.sort((a, b) {
    final oa = orderOf[a.$2.chapterId] ?? 0x7fffffff;
    final ob = orderOf[b.$2.chapterId] ?? 0x7fffffff;
    final cmp = oa.compareTo(ob);
    return cmp != 0 ? cmp : a.$1.compareTo(b.$1);
  });

  final sorted = [
    for (final entry in indexed) enrichLesson(entry.$2, const {}, chapters),
  ];

  final chapterIds = <String>[];
  for (final l in sorted) {
    if (l.chapterId != null && !chapterIds.contains(l.chapterId)) {
      chapterIds.add(l.chapterId!);
    }
  }

  final withPos = [
    for (final l in sorted)
      if (l.chapterId == null)
        l
      else
        () {
          final id = l.chapterId!;
          final same = [for (final s in sorted) if (s.chapterId == id) s];
          return l.copyWith(
            chapterOrder: l.chapterOrder ?? (chapterIds.indexOf(id) + 1),
            chapterCount: l.chapterCount ?? chapterIds.length,
            lessonIndexInChapter: same.indexWhere((s) => s.id == l.id) + 1,
            lessonCountInChapter: same.length,
          );
        }(),
  ];

  final firstUnheard = withPos.indexWhere((l) => !heard.contains(l.id));
  return UnderstandList(withPos, firstUnheard >= 0 ? firstUnheard : 0);
}

/// Naechster Index bei [delta] (+1/-1) in der flachen Liste; null ausserhalb.
int? stepIndex(int index, int size, int delta) {
  final next = index + delta;
  return (next >= 0 && next < size) ? next : null;
}

/// Eintrag der Kapitelliste: Titel, Status (neu, gehört, sitzt) und die Lektion, zu der ein Tipp springt.
class ChapterEntry {
  final String chapterId;
  final String title;
  final int order;
  final String status;
  final int jumpIndex;

  const ChapterEntry({
    required this.chapterId,
    required this.title,
    required this.order,
    required this.status,
    required this.jumpIndex,
  });

  @override
  bool operator ==(Object other) =>
      other is ChapterEntry &&
      other.chapterId == chapterId &&
      other.title == title &&
      other.order == order &&
      other.status == status &&
      other.jumpIndex == jumpIndex;

  @override
  int get hashCode =>
      Object.hash(chapterId, title, order, status, jumpIndex);
}

String? _statusLabel(String? apiStatus) => switch (apiStatus) {
      'new' => 'neu',
      'heard' || 'learning' => 'gehört',
      'solid' => 'sitzt',
      _ => null,
    };

/// Kapitelliste aus den Lektionen der Runde; Status aus dem Backend, sonst abgeleitet.
List<ChapterEntry> chapterEntries(
  List<SessionLesson> list,
  List<Chapter> chapters,
  Set<String> heard,
) {
  final ids = <String>[];
  for (final l in list) {
    if (l.chapterId != null && !ids.contains(l.chapterId)) {
      ids.add(l.chapterId!);
    }
  }
  final out = <ChapterEntry>[];
  for (var n = 0; n < ids.length; n++) {
    final id = ids[n];
    final indices = [
      for (var i = 0; i < list.length; i++)
        if (list[i].chapterId == id) i,
    ];
    Chapter? api;
    for (final c in chapters) {
      if (c.chapterId == id) {
        api = c;
        break;
      }
    }
    final allHeard = indices.every((i) => heard.contains(list[i].id));
    final status = api?.status == 'solid'
        ? 'sitzt'
        : allHeard
            ? 'gehört'
            : (_statusLabel(api?.status) ?? 'neu');
    final first = list[indices.first];
    int? unheardJump;
    for (final i in indices) {
      if (!heard.contains(list[i].id)) {
        unheardJump = i;
        break;
      }
    }
    out.add(
      ChapterEntry(
        chapterId: id,
        title: first.chapterTitle ?? api?.title ?? 'Kapitel ${n + 1}',
        order: first.chapterOrder ?? (n + 1),
        status: status,
        jumpIndex: unheardJump ?? indices.first,
      ),
    );
  }
  return out;
}

/// Was beim Verlassen einer Lektion in "Verstehen" als gehoert gemeldet wird.
double? heardToReport({
  required bool audioPlayed,
  required int positionMs,
  required int durationMs,
  required bool finished,
  required int shownMs,
  required bool forward,
}) {
  if (audioPlayed) {
    final f = heardFraction(positionMs, durationMs, finished);
    return f > 0.05 ? f : null;
  }
  return (forward && shownMs >= 5000) ? 0.8 : null;
}
