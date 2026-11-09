package com.jonasschnell.quickscope

import com.jonasschnell.quickscope.logic.CLOUD_WAIT_MS
import com.jonasschnell.quickscope.logic.CardFlow
import com.jonasschnell.quickscope.logic.shouldSpeakQuestion
import com.jonasschnell.quickscope.logic.voiceSource
import com.jonasschnell.quickscope.logic.CloudHealth
import com.jonasschnell.quickscope.logic.SPEECH_SPEED
import com.jonasschnell.quickscope.logic.PrefetchPlanner
import com.jonasschnell.quickscope.logic.VoiceKind
import com.jonasschnell.quickscope.logic.VoicePart
import com.jonasschnell.quickscope.logic.audioFileName
import com.jonasschnell.quickscope.logic.chooseVoice
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

    @Test fun fallsBackToDeviceVoiceAfterFailureThenRetries() {
        val h = CloudHealth(cooldownMs = 1000)
        assertEquals(VoiceKind.Cloud, chooseVoice(h, 0))
        h.failed(100)
        assertEquals(VoiceKind.Device, chooseVoice(h, 500))
        assertEquals(VoiceKind.Cloud, chooseVoice(h, 1100))
        h.failed(2000)
        h.ok()
        assertEquals(VoiceKind.Cloud, chooseVoice(h, 2001))
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

    @Test fun slowAudioIsWaitedForThenDeviceVoiceTakesOver() {
        assertEquals(VoiceKind.Cloud, voiceSource(0))                 // Audio schon da (Prefetch fertig)
        assertEquals(VoiceKind.Cloud, voiceSource(CLOUD_WAIT_MS))     // wird noch rechtzeitig fertig: warten, dann spielen
        assertEquals(VoiceKind.Device, voiceSource(CLOUD_WAIT_MS + 1)) // zu spaet: Geraetestimme statt verwerfen
        assertEquals(VoiceKind.Device, voiceSource(null))              // nie fertig (Fehler/Offline)
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

    @Test fun firstCardWaitsLongerForCloudBeforeDeviceVoice() {
        // Start wartet hoechstens 6 s, die Karte wartet danach weiter auf die Cloud-Stimme (bis 15 s), erst dann Geraetestimme
        assertTrue(com.jonasschnell.quickscope.logic.FIRST_AUDIO_WAIT_MS < CLOUD_WAIT_MS)
        assertEquals(VoiceKind.Cloud, voiceSource(9_000))        // kam nach dem Start-Timeout: wird nachgereicht und gespielt
        assertEquals(VoiceKind.Cloud, voiceSource(CLOUD_WAIT_MS))
        assertEquals(VoiceKind.Device, voiceSource(CLOUD_WAIT_MS + 1))
        assertTrue(shouldSpeakQuestion(true, null, false))        // sobald die Karte sichtbar ist, ohne jede Interaktion
    }
}
