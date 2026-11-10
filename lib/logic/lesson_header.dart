import '../data/models.dart';

/// Kopfzeile einer Lektionskarte: kleine Kapitelzeile, Lektionstitel, dezenter Fortschritt (Punkte). Fehlende Angaben werden weggelassen.
class LessonHeader {
  /// "Kapitel 2 von 6 · Titel", nur "Titel" ohne Nummer, null ohne Kapitelangabe.
  final String? chapterLine;
  final String title;

  /// (aktuelle Lektion, Anzahl), 1-basiert; null ohne Angaben.
  final (int, int)? progress;
  final bool newChapter;

  const LessonHeader({
    required this.chapterLine,
    required this.title,
    required this.progress,
    required this.newChapter,
  });

  String? get progressText =>
      progress == null ? null : 'Lektion ${progress!.$1} von ${progress!.$2}';
}

/// Fehlende Kapitelangaben einer Lektion aus der Kapitelliste und der Zuordnung Lektion -> Kapitel ergaenzen (vorhandene bleiben).
SessionLesson enrichLesson(
  SessionLesson lesson,
  Map<String, String> chapterIdByLesson,
  List<Chapter> chapters,
) {
  final chapterId = lesson.chapterId ?? chapterIdByLesson[lesson.id];
  Chapter? chapter;
  for (final c in chapters) {
    if (c.chapterId == chapterId) {
      chapter = c;
      break;
    }
  }
  return lesson.copyWith(
    chapterId: chapterId,
    chapterTitle: lesson.chapterTitle ?? chapter?.title,
    chapterOrder: lesson.chapterOrder ?? chapter?.order,
    chapterCount: lesson.chapterCount ??
        ((chapters.isNotEmpty && chapter != null) ? chapters.length : null),
  );
}

/// Kopfzeile fuer [lesson] innerhalb der Lektionen der Runde [run] (in Reihenfolge).
LessonHeader lessonHeader(
  SessionLesson lesson,
  List<SessionLesson> run, {
  required bool lessonsOnly,
}) {
  final title = (lesson.chapterTitle?.trim().isNotEmpty ?? false)
      ? lesson.chapterTitle
      : null;
  final order = lesson.chapterOrder;
  final count = lesson.chapterCount;
  final line = (title != null && order != null && count != null && count > 0)
      ? 'Kapitel $order von $count · $title'
      : title;

  final idx = run.indexWhere((e) => e.id == lesson.id);
  final (int, int)? progress;
  if (lesson.lessonIndexInChapter != null &&
      lesson.lessonCountInChapter != null &&
      lesson.lessonCountInChapter! > 0) {
    progress = (
      lesson.lessonIndexInChapter!.clamp(1, lesson.lessonCountInChapter!),
      lesson.lessonCountInChapter!,
    );
  } else if (lesson.chapterId != null && idx >= 0) {
    final same = [for (final e in run) if (e.chapterId == lesson.chapterId) e];
    final sameIdx = same.indexWhere((e) => e.id == lesson.id);
    progress = (sameIdx + 1, same.length);
  } else if (lessonsOnly && idx >= 0) {
    progress = (idx + 1, run.length);
  } else {
    progress = null;
  }

  final prev = idx > 0 ? run[idx - 1] : null;
  final newChapter = idx > 0 &&
      lesson.chapterId != null &&
      prev?.chapterId != null &&
      prev!.chapterId != lesson.chapterId;

  return LessonHeader(
    chapterLine: line,
    title: lesson.title,
    progress: progress,
    newChapter: newChapter,
  );
}
