package com.jonasschnell.quickscope

import com.jonasschnell.quickscope.data.AudioTimings
import com.jonasschnell.quickscope.data.WordTiming
import com.jonasschnell.quickscope.logic.WordPos
import com.jonasschnell.quickscope.logic.splitWords
import com.jonasschnell.quickscope.logic.timingsMatch
import com.jonasschnell.quickscope.logic.wordAtChar
import com.jonasschnell.quickscope.logic.wordPosAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightTest {
    // "Was ist Zins" mit Luecke zwischen "ist" und "Zins"
    private val text = "Was ist Zins"
    private val words = listOf(WordTiming(0, 3, 100, 300), WordTiming(4, 7, 300, 500), WordTiming(8, 12, 700, 1000))

    @Test fun currentWordFollowsTime() {
        assertEquals(WordPos(0, -1), wordPosAt(words, 0))        // vor dem Start
        assertEquals(WordPos(0, -1), wordPosAt(words, 99))
        assertEquals(WordPos(0, 0), wordPosAt(words, 100))       // erstes Wort beginnt
        assertEquals(WordPos(0, 0), wordPosAt(words, 299))
        assertEquals(WordPos(1, 1), wordPosAt(words, 300))       // nahtloser Wechsel
        assertEquals(WordPos(2, -1), wordPosAt(words, 500))      // Luecke: zwei Woerter fertig, keins aktiv
        assertEquals(WordPos(2, -1), wordPosAt(words, 699))
        assertEquals(WordPos(2, 2), wordPosAt(words, 700))
        assertEquals(WordPos(3, -1), wordPosAt(words, 1000))     // nach dem Ende
        assertEquals(WordPos(3, -1), wordPosAt(words, 99_999))
        assertEquals(WordPos(0, -1), wordPosAt(emptyList(), 500))
    }

    @Test fun timingsMustMatchDisplayedTextExactly() {
        val t = AudioTimings(text, 1000, words)
        assertTrue(timingsMatch(text, t))
        assertFalse(timingsMatch("Was ist Zins?", t))
        assertFalse(timingsMatch("was ist Zins", t))
        assertFalse(timingsMatch(text, t.copy(words = emptyList())))
        assertFalse(timingsMatch(text, t.copy(words = listOf(WordTiming(0, 99, 0, 10)))))      // ausserhalb des Textes
        assertFalse(timingsMatch(text, t.copy(words = listOf(WordTiming(4, 7, 0, 10), WordTiming(0, 3, 10, 20))))) // unsortiert
        assertFalse(timingsMatch(text, t.copy(words = listOf(WordTiming(0, 3, 50, 10)))))      // Zeit rueckwaerts
    }

    @Test fun deviceVoiceMapsCharactersToWords() {
        val w = splitWords("Was ist  Zins?")
        assertEquals(listOf(0 to 3, 4 to 7, 9 to 14), w.map { it.start to it.end })
        assertEquals(0, wordAtChar(w, 0))
        assertEquals(1, wordAtChar(w, 4))
        assertEquals(2, wordAtChar(w, 7))   // Leerzeichen: naechstes Wort folgt
        assertEquals(2, wordAtChar(w, 9))
        assertEquals(-1, wordAtChar(w, 14))
    }
}
