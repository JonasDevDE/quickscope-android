package com.jonasschnell.quickscope

import com.jonasschnell.quickscope.logic.Celebration
import com.jonasschnell.quickscope.logic.FeedbackTracker
import com.jonasschnell.quickscope.logic.FeedbackTuning
import com.jonasschnell.quickscope.logic.Tones
import com.jonasschnell.quickscope.logic.celebrationFor
import com.jonasschnell.quickscope.logic.newlySitting
import com.jonasschnell.quickscope.logic.nextDayStreak
import com.jonasschnell.quickscope.logic.retryInsertIndex
import com.jonasschnell.quickscope.logic.synth
import com.jonasschnell.quickscope.logic.visibleDayStreak
import com.jonasschnell.quickscope.logic.xpFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.math.abs

class FeedbackTest {
    @Test fun xpGrowsWithDifficultyAndStreak() {
        assertEquals(10, xpFor(3, 1))
        assertEquals(10, xpFor(1, 1))
        assertEquals(13, xpFor(4, 1))
        assertEquals(16, xpFor(5, 1))
        assertEquals(15, xpFor(3, 5))
        assertEquals(20, xpFor(3, 10))
        assertEquals(25, xpFor(3, 15))   // Serienbonus ist gedeckelt
        assertEquals(25, xpFor(3, 40))
    }

    @Test fun celebrationsAtMilestones() {
        assertEquals(Celebration.Small, celebrationFor(1))
        assertEquals(Celebration.Small, celebrationFor(4))
        assertEquals(Celebration.Medium, celebrationFor(5))
        assertEquals(Celebration.Medium, celebrationFor(15))
        assertEquals(Celebration.Big, celebrationFor(10))
        assertEquals(Celebration.Big, celebrationFor(20))
        assertEquals(Celebration.Small, celebrationFor(0))
    }

    @Test fun trackerCountsStreakBestXpAndComeback() {
        val t = FeedbackTracker()
        repeat(4) { assertTrue(t.record(true, 3).correct) }
        val fifth = t.record(true, 3)
        assertEquals(5, fifth.streak)
        assertEquals(Celebration.Medium, fifth.celebration)
        assertEquals(15, fifth.xp)
        assertFalse(fifth.comeback)
        val miss = t.record(false, 3)
        assertEquals(0, miss.streak)
        assertEquals(5, miss.bestStreak)
        assertEquals(0, miss.xp)
        val back = t.record(true, 3)
        assertTrue(back.comeback)
        assertEquals(1, back.streak)
        assertFalse(t.record(true, 3).comeback)
        assertEquals(7, t.correct)
        assertEquals(8, t.answers)
        assertEquals(4 * 10 + 15 + 10 + 10, t.xp)
        assertEquals(5, t.best)
    }

    @Test fun retriesGiveNoXpAndDoNotCountTwice() {
        val t = FeedbackTracker()
        t.record(false, 3)
        val r = t.record(true, 4, retry = true)
        assertTrue(r.correct)
        assertEquals(0, r.xp)
        assertEquals(1, r.streak)
        assertEquals(1, t.answers)
        assertEquals(0, t.correct)
        assertEquals(0, t.xp)
    }

    @Test fun retryComesBackAfterThreeToFiveTasks() {
        for (key in listOf("a", "b", "i-aisol", "i-spot", "x1", "zzz", "")) {
            val idx = retryInsertIndex(2, 30, key)
            assertTrue("idx=$idx", idx in (2 + 1 + FeedbackTuning.RETRY_MIN_GAP)..(2 + 1 + FeedbackTuning.RETRY_MAX_GAP))
        }
        // gleicher Schluessel, gleiche Position (stabil)
        assertEquals(retryInsertIndex(0, 20, "k"), retryInsertIndex(0, 20, "k"))
        // am Ende der Runde: ans Ende angehaengt
        assertEquals(5, retryInsertIndex(4, 5, "k"))
        assertEquals(7, retryInsertIndex(10, 7, "k").coerceAtMost(7))
    }

    @Test fun topicSitsOnlyWhenCrossingThreshold() {
        val prev = mapOf("a" to 0.7, "b" to 0.85, "c" to 0.2)
        val now = mapOf("a" to 0.82, "b" to 0.9, "c" to 0.5, "d" to 0.81)
        assertEquals(setOf("a", "d"), newlySitting(prev, now).toSet())
        assertTrue(newlySitting(now, now).isEmpty())
        assertEquals(listOf("a"), newlySitting(mapOf("a" to 0.79), mapOf("a" to 0.8)))
    }

    @Test fun dayStreakCountsConsecutiveDays() {
        val today = LocalDate.of(2026, 11, 9)
        assertEquals(1, nextDayStreak(null, 0, today))
        assertEquals(4, nextDayStreak(today.minusDays(1), 3, today))
        assertEquals(3, nextDayStreak(today, 3, today))
        assertEquals(1, nextDayStreak(today.minusDays(2), 9, today))
        assertEquals(7, visibleDayStreak(today, 7, today))
        assertEquals(7, visibleDayStreak(today.minusDays(1), 7, today))
        assertNull(visibleDayStreak(today.minusDays(2), 7, today))
        assertNull(visibleDayStreak(null, 7, today))
        assertNull(visibleDayStreak(today, 0, today))
    }

    @Test fun synthesizedTonesAreShortQuietAndNonEmpty() {
        for (notes in listOf(Tones.correct, Tones.medium, Tones.big, Tones.wrong, Tones.done)) {
            val pcm = synth(notes)
            val expected = notes.sumOf { it.ms * FeedbackTuning.SAMPLE_RATE / 1000 }
            assertEquals(expected, pcm.size)
            val peak = pcm.maxOf { abs(it.toInt()) }
            assertTrue(peak > 1000)
            assertTrue(peak <= (FeedbackTuning.SFX_VOLUME * Short.MAX_VALUE).toInt() + 1)
            assertTrue(pcm.size < FeedbackTuning.SAMPLE_RATE * 2) // unter 2 Sekunden
        }
        // Richtig ist kuerzer/heller als der Abschlussklang, falsch ist tiefer
        assertTrue(synth(Tones.correct).size < synth(Tones.done).size)
        assertTrue(Tones.wrong[0].hz < Tones.correct[0].hz)
        assertTrue(Tones.medium.last().hz > Tones.correct.last().hz)
    }
}
