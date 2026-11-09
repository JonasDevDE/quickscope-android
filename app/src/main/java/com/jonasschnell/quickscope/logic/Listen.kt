package com.jonasschnell.quickscope.logic

import com.jonasschnell.quickscope.data.Lesson
import com.jonasschnell.quickscope.data.SessionEntry
import com.jonasschnell.quickscope.data.SessionLesson

/** "Verstehen" ist sichtbar, sobald es Lektionen gibt (online aus der Planung, offline aus dem Offline-Paket); Audio spielt dabei keine Rolle. */
fun understandVisible(hasLessons: Boolean): Boolean = hasLessons

/** Lektionen der Session-Antwort in ihrer Reihenfolge. */
fun understandEntries(sessionItems: List<SessionEntry>): List<SessionEntry> = sessionItems.filter { it.lesson != null }

/** Rueckfall, wenn die Session keine neue Lektion liefert: noch Ungehoerte zuerst, hoechstens [limit]. */
fun lessonEntriesFrom(all: List<Lesson>, limit: Int = 8): List<SessionEntry> =
    all.sortedBy { it.heard }.take(limit).map { SessionEntry(lesson = SessionLesson(it.id, it.title, it.keyPoints, it.ttsText, it.mnemonic, it.estSeconds)) }

/** Ton nachtraeglich: laeuft im Hintergrund der Preflight, wird der Audiomodus erst danach (und nur bei An, online, verfuegbar) eingeschaltet. */
fun audioAfterCheck(enabled: Boolean, online: Boolean, available: Boolean): Boolean = enabled && online && available

/** In "Verstehen" blaettert die Seite nach dem Ende des Streams von selbst weiter; ohne Ton bleibt sie lesbar mit "Weiter". */
fun autoAdvance(lessonsOnly: Boolean, finishedPlaying: Boolean): Boolean = lessonsOnly && finishedPlaying

/** Offline-Lesemodus: Lektionen des Pakets, noch nicht gelesene zuerst; sind alle gelesen, die ersten [limit] noch einmal. */
fun offlineLessonRound(pack: OfflinePack, done: Set<String>, limit: Int = 8): List<SessionEntry> {
    val lessons = (pack.entries + pack.reserve).filter { it.lesson != null }.distinctBy { it.lesson!!.id }
    val fresh = lessons.filter { "l:${it.lesson!!.id}" !in done }
    return (fresh.ifEmpty { lessons }).take(limit)
}
