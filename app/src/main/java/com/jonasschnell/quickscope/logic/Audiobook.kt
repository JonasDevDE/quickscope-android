package com.jonasschnell.quickscope.logic

import com.jonasschnell.quickscope.data.AudiobookItem
import kotlin.math.max
import kotlin.math.min

/** Ab diesem Anteil gilt eine Lektion als gehoert (wie im Backend). */
const val HEARD_THRESHOLD = 0.8

/** Wie viele Lektionen vorab angefordert werden. */
const val AUDIOBOOK_AHEAD = 2

/** Stichwort, das zur Zeit [positionMs] hervorgehoben wird: gleichmaessig ueber die Lektionsdauer verteilt; -1 ohne Stichworte/Dauer. */
fun activeKeyPoint(count: Int, positionMs: Long, durationMs: Long): Int {
    if (count <= 0 || durationMs <= 0) return -1
    val f = positionMs.toDouble() / durationMs
    return min(count - 1, max(0, (f * count).toInt()))
}

/** Anteil gehoert (0..1) fuer die Meldung an das Backend. */
fun heardFraction(positionMs: Long, durationMs: Long, finished: Boolean): Double {
    if (finished) return 1.0
    if (durationMs <= 0) return 0.0
    return (positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0)
}

/** "Kapitel 2 von 6" (Reihenfolge ab 1); leer, wenn die Gesamtzahl unbekannt ist. */
fun chapterLabel(order: Int, total: Int): String = if (order > 0 && total > 0) "Kapitel $order von $total" else ""

/** Eselsbruecke erst gegen Ende der Lektion zeigen (letztes Zehntel). */
fun showMnemonic(positionMs: Long, durationMs: Long): Boolean = durationMs > 0 && positionMs >= durationMs * 0.9

/**
 * Warteschlange des Hoerbuchs: zeigt auf die aktuelle Lektion; Weiter/Zurueck bleiben in den Grenzen, am Ende ist es fertig.
 * Liefert zusaetzlich, welche Lektionen als naechstes vorab angefordert werden sollen.
 */
class AudiobookQueue(val items: List<AudiobookItem>) {
    var index = 0
        private set

    val current: AudiobookItem? get() = items.getOrNull(index)
    val finished: Boolean get() = index >= items.size

    fun next(): Boolean {
        if (index >= items.size) return false
        index++
        return true
    }

    fun previous(): Boolean {
        if (index <= 0) return false
        index--
        return true
    }

    fun jumpTo(i: Int) {
        index = i.coerceIn(0, items.size)
    }

    /** Ids der naechsten [count] Lektionen ab der aktuellen (inklusive) fuer den Prefetch. */
    fun ahead(count: Int = AUDIOBOOK_AHEAD + 1): List<String> = items.drop(index).take(count).map { it.lessonId }

    /** Restzeit in Sekunden (nach Schaetzung), fuer eine ruhige Anzeige. */
    fun remainingSeconds(): Int = items.drop(index).sumOf { it.estSeconds }
}
