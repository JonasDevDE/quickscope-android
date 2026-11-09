package com.jonasschnell.quickscope

import com.jonasschnell.quickscope.data.Item
import com.jonasschnell.quickscope.logic.CardFlow
import com.jonasschnell.quickscope.logic.CloudHealth
import com.jonasschnell.quickscope.logic.DEFAULT_SPEED
import com.jonasschnell.quickscope.logic.PrefetchPlanner
import com.jonasschnell.quickscope.logic.VoiceKind
import com.jonasschnell.quickscope.logic.VoicePart
import com.jonasschnell.quickscope.logic.audioFeedback
import com.jonasschnell.quickscope.logic.audioFileName
import com.jonasschnell.quickscope.logic.chooseVoice
import com.jonasschnell.quickscope.logic.filesToPrune
import com.jonasschnell.quickscope.logic.normalizeSpeed
import com.jonasschnell.quickscope.logic.parseTask
import com.jonasschnell.quickscope.logic.speedLabel
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

    @Test fun speedChoiceDefaultsAndSnaps() {
        assertEquals(1.15f, DEFAULT_SPEED, 0.0001f)
        assertEquals(1.15f, normalizeSpeed(0f), 0.0001f)
        assertEquals(1.0f, normalizeSpeed(1.02f), 0.0001f)
        assertEquals(1.3f, normalizeSpeed(1.5f), 0.0001f)
        assertEquals(1.15f, normalizeSpeed(1.17f), 0.0001f)
        assertEquals("1x", speedLabel(1.0f))
        assertEquals("1,15x", speedLabel(1.15f))
        assertEquals("1,3x", speedLabel(1.3f))
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
        assertEquals("e1_i_1_answer.wav", audioFileName("e1", "i/1", VoicePart.Answer))
    }

    @Test fun cardFlowNeedsRevealBeforeAnswer() {
        val f = CardFlow()
        assertNull(f.answer(true))
        assertFalse(f.revealed)
        f.reveal()
        assertEquals("known", f.answer(true))
        assertEquals("unknown", f.answer(false))
    }

    @Test fun deviceVoiceFeedbackIsShort() {
        val t = parseTask(Item(id = "a", type = "card", question = "Q?", answer = "42", explanation = "Weil so."))
        assertEquals("Richtig. Weil so.", audioFeedback(t, true))
        assertEquals("Falsch. 42. Weil so.", audioFeedback(t, false))
    }
}
