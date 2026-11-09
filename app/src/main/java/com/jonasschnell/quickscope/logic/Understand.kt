package com.jonasschnell.quickscope.logic

import com.jonasschnell.quickscope.data.Chapter
import com.jonasschnell.quickscope.data.SessionLesson

/** Alle Lektionen in Kapitel-Reihenfolge (ohne Limit) und die Startposition: die erste noch nicht gehoerte Lektion. */
data class UnderstandList(val lessons: List<SessionLesson>, val start: Int)

/**
 * Baut die Verstehen-Liste: Lektionen nach Kapitelreihenfolge (innerhalb eines Kapitels bleibt die gelieferte Reihenfolge), dazu die
 * Kapitelangaben fuer die Kopfzeile ("Kapitel 2 von 6", "Lektion 3 von 5"). Start = erste ungehoerte, sonst die erste.
 */
fun buildUnderstandList(chapters: List<Chapter>, lessons: List<SessionLesson>, heard: Set<String>): UnderstandList {
    val orderOf = chapters.associate { it.chapterId to it.order }
    val sorted = lessons.withIndex()
        .sortedWith(compareBy({ orderOf[it.value.chapterId] ?: Int.MAX_VALUE }, { it.index }))
        .map { enrichLesson(it.value, emptyMap(), chapters) }
    val chapterIds = sorted.mapNotNull { it.chapterId }.distinct()
    val withPos = sorted.map { l ->
        val id = l.chapterId
        if (id == null) l else {
            val same = sorted.filter { it.chapterId == id }
            l.copy(
                chapterOrder = l.chapterOrder ?: (chapterIds.indexOf(id) + 1),
                chapterCount = l.chapterCount ?: chapterIds.size,
                lessonIndexInChapter = same.indexOfFirst { it.id == l.id } + 1,
                lessonCountInChapter = same.size,
            )
        }
    }
    return UnderstandList(withPos, withPos.indexOfFirst { it.id !in heard }.takeIf { it >= 0 } ?: 0)
}

/** Naechster Index bei [delta] (+1/-1) in der flachen Liste; null ausserhalb (Weiter auf der letzten = fertig, Zurueck auf der ersten = nichts). Kapitelgrenzen ergeben sich aus der Reihenfolge. */
fun stepIndex(index: Int, size: Int, delta: Int): Int? = (index + delta).takeIf { it in 0 until size }

/** Eintrag der Kapitelliste: Titel, Status (neu, gehört, sitzt) und die Lektion, zu der ein Tipp springt. */
data class ChapterEntry(val chapterId: String, val title: String, val order: Int, val status: String, val jumpIndex: Int)

private fun statusLabel(apiStatus: String?): String? = when (apiStatus) {
    "new" -> "neu"
    "heard", "learning" -> "gehört"
    "solid" -> "sitzt"
    else -> null
}

/** Kapitelliste aus den Lektionen der Runde; Status aus dem Backend, sonst abgeleitet (alle gehoert = gehoert, sonst neu). Sprung = erste ungehoerte Lektion des Kapitels, sonst die erste. */
fun chapterEntries(list: List<SessionLesson>, chapters: List<Chapter>, heard: Set<String>): List<ChapterEntry> {
    val ids = list.mapNotNull { it.chapterId }.distinct()
    return ids.mapIndexed { n, id ->
        val indices = list.indices.filter { list[it].chapterId == id }
        val api = chapters.firstOrNull { it.chapterId == id }
        val allHeard = indices.all { list[it].id in heard }
        val status = when {
            api?.status == "solid" -> "sitzt"
            allHeard -> "gehört"
            else -> statusLabel(api?.status) ?: "neu"
        }
        ChapterEntry(
            id, list[indices.first()].chapterTitle ?: api?.title ?: "Kapitel ${n + 1}", list[indices.first()].chapterOrder ?: (n + 1), status,
            indices.firstOrNull { list[it].id !in heard } ?: indices.first(),
        )
    }
}

/**
 * Was beim Verlassen einer Lektion in "Verstehen" als gehoert gemeldet wird: mit Ton der gehoerte Anteil (nur wenn er etwas ausmacht);
 * im Lesemodus nichts, ausser bei Weiter (nicht Zurueck) nach mindestens 5 s: dann 0,8 (gilt als gelesen).
 */
fun heardToReport(audioPlayed: Boolean, positionMs: Long, durationMs: Long, finished: Boolean, shownMs: Long, forward: Boolean): Double? =
    if (audioPlayed) heardFraction(positionMs, durationMs, finished).takeIf { it > 0.05 }
    else if (forward && shownMs >= 5_000) 0.8 else null
