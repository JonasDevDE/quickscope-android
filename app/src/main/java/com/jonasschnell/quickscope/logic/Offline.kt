package com.jonasschnell.quickscope.logic

import com.jonasschnell.quickscope.data.AppJson
import com.jonasschnell.quickscope.data.SessionEntry
import com.jonasschnell.quickscope.data.SessionLesson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlin.math.abs

/** Meldung, wenn eine Aktion Internet braucht. */
const val NEEDS_INTERNET = "Dafür brauchst du Internet."

/** "Nur zuhören" gibt es nur online, mit "Vorlesen an" und wenn Lektionen existieren. */
fun canListen(online: Boolean, audioEnabled: Boolean, hasLessons: Boolean): Boolean = online && audioEnabled && hasLessons

/** Wie viele Tage das Offline-Paket abdeckt. */
const val OFFLINE_DAYS = 3

/** Grobe Dauer einer Frage beim Fuellen einer Offline-Runde. */
const val OFFLINE_ITEM_SECONDS = 20

/** Offline-Paket der Pruefung: Aufgaben/Lektionen in Reihenfolge (queue) plus Reserve. */
data class OfflinePack(val entries: List<SessionEntry>, val reserve: List<SessionEntry>, val days: Int = OFFLINE_DAYS)

/**
 * Liest das Paket tolerant (Schicht klein und austauschbar): Eintraege unter `queue` oder `items`, Reserve unter
 * `reserveItems` oder `reserve`; unbekannte Felder und kaputte Eintraege werden ignoriert. Ohne Liste: null.
 */
fun parseOfflinePack(text: String): OfflinePack? {
    val root = runCatching { AppJson.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
    fun list(vararg keys: String): List<SessionEntry>? {
        val arr = keys.firstNotNullOfOrNull { root[it] as? JsonArray } ?: return null
        return arr.mapNotNull { runCatching { AppJson.decodeFromJsonElement(SessionEntry.serializer(), it) }.getOrNull() }
            .filter { it.item?.id?.isNotBlank() == true || it.lesson != null }
    }
    val entries = list("queue", "items") ?: return null
    val reserve = list("reserveItems", "reserve").orEmpty()
    val days = (root["days"] as? JsonPrimitive)?.intOrNull ?: OFFLINE_DAYS
    return OfflinePack(entries, reserve, days)
}

/** Schluessel fuer "schon erledigt": Aufgaben `q:<id>`, Lektionen `l:<id>`. */
fun entryKey(e: SessionEntry): String? = e.lesson?.let { "l:${it.id}" } ?: e.item?.let { "q:${it.id}" }

/**
 * Offline-Runde: die Reihenfolge des Pakets, ohne schon Erledigtes (und ohne Doppelte), bis [seconds] gefuellt sind;
 * danach nur noch bei Bedarf aus der Reserve. [questionsOnly] laesst Lektionen weg.
 */
fun offlineRound(pack: OfflinePack, done: Set<String>, seconds: Int, questionsOnly: Boolean = false): List<SessionEntry> {
    val out = mutableListOf<SessionEntry>()
    val seen = mutableSetOf<String>()
    var used = 0
    for (e in pack.entries + pack.reserve) {
        if (used >= seconds) break
        val key = entryKey(e) ?: continue
        if (key in done || !seen.add(key)) continue
        if (questionsOnly && e.lesson != null) continue
        out += e
        used += e.lesson?.estSeconds ?: OFFLINE_ITEM_SECONDS
    }
    return out
}

/** Einfuegeposition einer falsch beantworteten Aufgabe offline: nach 4 bis 8 weiteren Aufgaben, hoechstens ans Ende. */
fun offlineRetryIndex(current: Int, size: Int, key: String): Int {
    val gap = 4 + abs(key.hashCode() % 5)
    return minOf(current + 1 + gap, size)
}

/** Bei "Weiss nicht" offline: die naechste Lektion des Pakets, die weder in der Runde ist noch erledigt wurde. */
fun nextOfflineLesson(pack: OfflinePack, presentLessonIds: Set<String>, done: Set<String>): SessionLesson? =
    (pack.entries + pack.reserve).firstNotNullOfOrNull { e ->
        e.lesson?.takeIf { it.id !in presentLessonIds && "l:${it.id}" !in done }
    }
