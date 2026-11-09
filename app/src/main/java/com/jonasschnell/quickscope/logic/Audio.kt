package com.jonasschnell.quickscope.logic

import kotlin.math.abs

/** Teile einer Aufgabe, die vorgelesen werden: die Frage und die Antwort (mit Kurzerklaerung). */
enum class VoicePart(val query: String) { Question("question"), Answer("answer") }

val SPEEDS = listOf(1.0f, 1.15f, 1.3f)
const val DEFAULT_SPEED = 1.15f

/** Gespeicherte Geschwindigkeit auf eine der drei erlaubten Stufen abbilden (Standard 1.15). */
fun normalizeSpeed(v: Float): Float = if (v <= 0f) DEFAULT_SPEED else SPEEDS.minByOrNull { abs(it - v) } ?: DEFAULT_SPEED

fun speedLabel(v: Float): String = if (v == 1.0f) "1x" else "${v}x".replace('.', ',')

/**
 * Plant das Vorladen: bei jeder Aufgabe die aktuelle und die naechsten ([window] insgesamt) anfordern,
 * jede Aufgabe nur einmal. [reset] beim Start einer neuen Runde.
 */
class PrefetchPlanner(private val window: Int = 3) {
    private val requested = mutableSetOf<String>()

    /** Ids aus [ids] ab [index], die noch nicht angefordert wurden; sie gelten danach als angefordert. */
    fun next(ids: List<String>, index: Int): List<String> {
        val fresh = ids.drop(index.coerceAtLeast(0)).take(window).filter { requested.add(it) }
        return fresh
    }

    /** Fehlgeschlagene Anforderung zuruecknehmen, damit sie spaeter erneut versucht wird. */
    fun forget(id: String) {
        requested.remove(id)
    }

    fun reset() = requested.clear()
}

/**
 * Merkt, ob die Cloud-Stimme gerade nutzbar ist: nach einem Fehler (Offline, 503 Limit/nicht eingerichtet)
 * wird [cooldownMs] lang die Geraetestimme genommen, danach wird es wieder versucht.
 */
class CloudHealth(private val cooldownMs: Long = 120_000L) {
    private var failedAt: Long? = null

    fun available(now: Long): Boolean = failedAt?.let { now - it >= cooldownMs } ?: true

    fun failed(now: Long) {
        failedAt = now
    }

    fun ok() {
        failedAt = null
    }
}

/** Welche Stimme liest vor: bereit/gesund -> Cloud, sonst Geraet. */
enum class VoiceKind { Cloud, Device }

fun chooseVoice(health: CloudHealth, now: Long): VoiceKind = if (health.available(now)) VoiceKind.Cloud else VoiceKind.Device

/** Dateiname im Audio-Cache. */
fun audioFileName(examId: String, itemId: String, part: VoicePart) = "${examId}_${itemId}_${part.query}.wav".replace(Regex("[^A-Za-z0-9._-]"), "_")

/** Aelteste Dateien zuerst entfernen, bis die Summe unter [maxBytes] liegt. Liefert die Namen der zu loeschenden Dateien. */
fun filesToPrune(files: List<Pair<String, Pair<Long, Long>>>, maxBytes: Long): List<String> {
    // Eintrag: Name -> (Groesse, letzte Aenderung)
    var total = files.sumOf { it.second.first }
    val out = mutableListOf<String>()
    for ((name, meta) in files.sortedBy { it.second.second }) {
        if (total <= maxBytes) break
        out += name
        total -= meta.first
    }
    return out
}
