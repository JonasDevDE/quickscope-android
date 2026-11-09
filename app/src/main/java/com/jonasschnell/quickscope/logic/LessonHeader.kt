package com.jonasschnell.quickscope.logic

import com.jonasschnell.quickscope.data.Chapter
import com.jonasschnell.quickscope.data.SessionLesson

/** Kopfzeile einer Lektionskarte: kleine Kapitelzeile, Lektionstitel, dezenter Fortschritt (Punkte). Fehlende Angaben werden weggelassen. */
data class LessonHeader(
    /** "Kapitel 2 von 6 · Titel", nur "Titel" ohne Nummer, null ohne Kapitelangabe. */
    val chapterLine: String?,
    val title: String,
    /** (aktuelle Lektion, Anzahl), 1-basiert; null ohne Angaben. */
    val progress: Pair<Int, Int>?,
    val newChapter: Boolean,
) {
    val progressText: String? get() = progress?.let { "Lektion ${it.first} von ${it.second}" }
}

/** Fehlende Kapitelangaben einer Lektion aus der Kapitelliste und der Zuordnung Lektion -> Kapitel ergaenzen (vorhandene bleiben). */
fun enrichLesson(lesson: SessionLesson, chapterIdByLesson: Map<String, String>, chapters: List<Chapter>): SessionLesson {
    val chapterId = lesson.chapterId ?: chapterIdByLesson[lesson.id]
    val chapter = chapters.firstOrNull { it.chapterId == chapterId }
    return lesson.copy(
        chapterId = chapterId,
        chapterTitle = lesson.chapterTitle ?: chapter?.title,
        chapterOrder = lesson.chapterOrder ?: chapter?.order,
        chapterCount = lesson.chapterCount ?: chapters.size.takeIf { it > 0 && chapter != null },
    )
}

/**
 * Kopfzeile fuer [lesson] innerhalb der Lektionen der Runde [run] (in Reihenfolge). Fortschritt: Angaben des Backends
 * (lessonIndexInChapter/lessonCountInChapter), sonst die Lektionen desselben Kapitels in der Runde, sonst in "Verstehen" die Position
 * in der Runde; sonst keiner. [newChapter]: das Kapitel unterscheidet sich von dem der vorherigen Lektion.
 */
fun lessonHeader(lesson: SessionLesson, run: List<SessionLesson>, lessonsOnly: Boolean): LessonHeader {
    val title = lesson.chapterTitle?.takeIf { it.isNotBlank() }
    val order = lesson.chapterOrder
    val count = lesson.chapterCount
    val line = when {
        title != null && order != null && count != null && count > 0 -> "Kapitel $order von $count · $title"
        title != null -> title
        else -> null
    }
    val idx = run.indexOfFirst { it.id == lesson.id }
    val progress: Pair<Int, Int>? = when {
        lesson.lessonIndexInChapter != null && lesson.lessonCountInChapter != null && lesson.lessonCountInChapter > 0 ->
            lesson.lessonIndexInChapter.coerceIn(1, lesson.lessonCountInChapter) to lesson.lessonCountInChapter
        lesson.chapterId != null && idx >= 0 -> {
            val same = run.filter { it.chapterId == lesson.chapterId }
            (same.indexOfFirst { it.id == lesson.id } + 1) to same.size
        }
        lessonsOnly && idx >= 0 -> (idx + 1) to run.size
        else -> null
    }
    val prev = run.getOrNull(idx - 1)
    val newChapter = idx > 0 && lesson.chapterId != null && prev?.chapterId != null && prev.chapterId != lesson.chapterId
    return LessonHeader(line, lesson.title, progress, newChapter)
}
