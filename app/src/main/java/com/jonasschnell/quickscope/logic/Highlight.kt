package com.jonasschnell.quickscope.logic

import com.jonasschnell.quickscope.data.AudioTimings
import com.jonasschnell.quickscope.data.WordTiming

/** Stand der Hervorhebung: [spoken] Woerter sind fertig gesprochen, [active] ist das aktuelle Wort (-1 = gerade keins). */
data class WordPos(val spoken: Int, val active: Int)

/**
 * Wort zur Zeit [tMs] (Inhaltszeit der Audiodatei). Binaersuche nach dem letzten Wort, das schon begonnen hat:
 * liegt [tMs] darin, ist es das aktuelle; in Luecken, vor dem Start und nach dem Ende gibt es kein aktuelles Wort.
 */
fun wordPosAt(words: List<WordTiming>, tMs: Long): WordPos {
    var lo = 0
    var hi = words.size - 1
    var last = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (words[mid].fromMs <= tMs) {
            last = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    if (last < 0) return WordPos(0, -1)
    return if (tMs < words[last].toMs) WordPos(last, last) else WordPos(last + 1, -1)
}

/** Passt die Zeitung zum angezeigten Text? Nur dann darf hervorgehoben werden (nie falsch markieren). */
fun timingsMatch(display: String, t: AudioTimings): Boolean {
    if (t.words.isEmpty() || t.text != display) return false
    var prevEnd = 0
    for (w in t.words) {
        if (w.start < prevEnd || w.end <= w.start || w.end > display.length || w.toMs < w.fromMs) return false
        prevEnd = w.end
    }
    return true
}

/** Woerter eines Textes ohne Zeiten (fuer die Geraetestimme, die nur Zeichenpositionen meldet). */
fun splitWords(text: String): List<WordTiming> =
    Regex("\\S+").findAll(text).map { WordTiming(it.range.first, it.range.last + 1) }.toList()

/** Index des Wortes, das Zeichen [charIndex] enthaelt oder als naechstes folgt; -1, wenn keins. */
fun wordAtChar(words: List<WordTiming>, charIndex: Int): Int {
    var lo = 0
    var hi = words.size - 1
    var found = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (words[mid].end > charIndex) {
            found = mid
            hi = mid - 1
        } else {
            lo = mid + 1
        }
    }
    return found
}
