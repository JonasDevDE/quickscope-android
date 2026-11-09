package com.jonasschnell.quickscope

import com.jonasschnell.quickscope.logic.CLOUD_WAIT_MS
import com.jonasschnell.quickscope.logic.CardFlow
import com.jonasschnell.quickscope.logic.shouldSpeakQuestion
import com.jonasschnell.quickscope.logic.SPEECH_SPEED
import com.jonasschnell.quickscope.logic.PrefetchPlanner
import com.jonasschnell.quickscope.logic.VoicePart
import com.jonasschnell.quickscope.logic.audioFileName
import com.jonasschnell.quickscope.logic.filesToPrune
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTest {
    @Test fun prefetchPlansNextThreeOnce() {
        val ids = listOf("a", "b", "c", "d", "e")
        val p = PrefetchPlanner(3)
        assertEquals(listOf("a", "b", "c"), p.next(ids, 0))
        assertEquals(listOf("d"), p.next(ids, 1))
        assertEquals(listOf("e"), p.next(ids, 2))
        assertTrue(p.next(ids, 3).isEmpty())
        p.forget("d")
        assertEquals(listOf("d"), p.next(ids, 3))
        p.reset()
        assertEquals(listOf("c", "d", "e"), p.next(ids, 2))
        assertTrue(p.next(ids, 99).isEmpty())
    }

    @Test fun speechSpeedIsFixed() {
        assertEquals(1.15f, SPEECH_SPEED, 0.0001f)
    }

    @Test fun cacheKeepsNewestWithinLimit() {
        val files = listOf("old" to (400L to 1L), "mid" to (400L to 2L), "new" to (400L to 3L))
        assertEquals(listOf("old"), filesToPrune(files, 1000))
        assertEquals(listOf("old", "mid"), filesToPrune(files, 500))
        assertTrue(filesToPrune(files, 5000).isEmpty())
        assertEquals("e1_i-1_question.wav", audioFileName("e1", "i-1", VoicePart.Question))
        assertEquals("e1_i_1_question.wav", audioFileName("e1", "i/1", VoicePart.Question))
    }

    @Test fun cardFlowNeedsRevealBeforeAnswer() {
        val f = CardFlow()
        assertNull(f.answer(true))
        assertFalse(f.revealed)
        f.reveal()
        assertEquals("known", f.answer(true))
        assertEquals("unknown", f.answer(false))
    }

    @Test fun speechStartsWheneverAnItemAppearsWithSoundOn() {
        assertTrue(shouldSpeakQuestion(audioOn = true, feedback = null, isTeach = false))   // auch das erste Item
        assertFalse(shouldSpeakQuestion(audioOn = false, feedback = null, isTeach = false))
        assertFalse(shouldSpeakQuestion(audioOn = true, feedback = true, isTeach = false))   // schon beantwortet
        assertFalse(shouldSpeakQuestion(audioOn = true, feedback = null, isTeach = true))    // Lektion spielt sich selbst
    }

    private fun card(id: String) = com.jonasschnell.quickscope.logic.parseTask(com.jonasschnell.quickscope.data.Item(id = id, type = "card"))

    private fun teach(id: String): com.jonasschnell.quickscope.logic.Task {
        val l = com.jonasschnell.quickscope.data.SessionLesson(id, "T", listOf("a"), "text", null, 40)
        return com.jonasschnell.quickscope.logic.Task.Teach(com.jonasschnell.quickscope.logic.teachItem(l), l)
    }

    @Test fun roundStartWarmsTheFirstFourWithTheFirstFirst() {
        val tasks = listOf(teach("L1"), card("q1"), card("q2"), card("q3"), card("q4"), card("q5"))
        val plan = com.jonasschnell.quickscope.logic.warmPlan(tasks)
        assertEquals(4, plan.size)
        assertEquals(com.jonasschnell.quickscope.logic.WarmTarget("L1", true), plan[0])   // erste Karte zuerst
        assertEquals(listOf("q1", "q2", "q3"), plan.drop(1).map { it.id })
        assertTrue(plan.drop(1).none { it.lesson })
        assertTrue(com.jonasschnell.quickscope.logic.warmPlan(emptyList()).isEmpty())
        assertEquals(2, com.jonasschnell.quickscope.logic.warmPlan(listOf(card("a"), card("b"))).size) // weniger Karten als 4
    }

    @Test fun startupWaitsOnlyWhenSpeechIsExpected() {
        val tasks = listOf(card("q1"))
        assertTrue(com.jonasschnell.quickscope.logic.audioNeededAtStart(true, tasks))
        assertFalse(com.jonasschnell.quickscope.logic.audioNeededAtStart(false, tasks))
        assertTrue(com.jonasschnell.quickscope.logic.audioNeededAtStart(false, listOf(teach("L1"), card("q1")))) // Lektion spielt sich selbst ab
        assertFalse(com.jonasschnell.quickscope.logic.audioNeededAtStart(true, emptyList()))
    }

    @Test fun firstCardSpeaksWithoutAnyInteraction() {
        assertTrue(com.jonasschnell.quickscope.logic.FIRST_AUDIO_WAIT_MS < CLOUD_WAIT_MS)
        assertTrue(shouldSpeakQuestion(true, null, false))
    }

    @Test fun neverTwoPlaybacksAtOnceAndOldCallbacksAreDropped() {
        val m = com.jonasschnell.quickscope.logic.PlaybackMachine()
        val first = m.begin()
        assertEquals(com.jonasschnell.quickscope.logic.PlayState.Loading, m.state)
        assertTrue(m.playing(first))
        assertEquals(com.jonasschnell.quickscope.logic.PlayState.Playing, m.state)
        // neues Element startet: das alte ist sofort ungueltig, es gibt nur eine aktuelle Wiedergabe
        val second = m.begin()
        assertFalse(m.isCurrent(first))
        assertTrue(m.isCurrent(second))
        // verspaetetes "prepared" und "complete" des alten Elements werden verworfen
        assertFalse(m.playing(first))
        assertFalse(m.finish(first))
        assertEquals(com.jonasschnell.quickscope.logic.PlayState.Loading, m.state)
        assertTrue(m.playing(second))
        // zweimal "prepared" fuer dasselbe Element startet nicht doppelt
        assertFalse(m.playing(second))
        m.pause(second)
        assertEquals(com.jonasschnell.quickscope.logic.PlayState.Paused, m.state)
        m.resume(second)
        assertEquals(com.jonasschnell.quickscope.logic.PlayState.Playing, m.state)
        assertTrue(m.finish(second))
        assertEquals(com.jonasschnell.quickscope.logic.PlayState.Idle, m.state)
        assertFalse(m.isCurrent(second))
    }

    @Test fun stopInvalidatesEverythingInFlight() {
        val m = com.jonasschnell.quickscope.logic.PlaybackMachine()
        val g = m.begin()
        m.stop()                        // z. B. Item gewechselt oder Stumm waehrend das Audio noch laedt
        assertFalse(m.isCurrent(g))
        assertFalse(m.playing(g))       // das spaet fertige Audio startet nicht mehr
        assertEquals(com.jonasschnell.quickscope.logic.PlayState.Idle, m.state)
        // "Anhoeren" mehrfach schnell hintereinander: nur die letzte Wiedergabe zaehlt
        val a = m.begin(); val b = m.begin(); val c = m.begin()
        assertFalse(m.isCurrent(a)); assertFalse(m.isCurrent(b)); assertTrue(m.isCurrent(c))
        assertEquals(c, m.generation)
    }
}
