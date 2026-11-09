package com.jonasschnell.quickscope

import com.jonasschnell.quickscope.data.AttemptRequest
import com.jonasschnell.quickscope.data.Exam
import com.jonasschnell.quickscope.data.Forecast
import com.jonasschnell.quickscope.data.Item
import com.jonasschnell.quickscope.data.Projection
import com.jonasschnell.quickscope.logic.AttemptQueue
import com.jonasschnell.quickscope.logic.MatchState
import com.jonasschnell.quickscope.logic.OrderState
import com.jonasschnell.quickscope.logic.QueueStorage
import com.jonasschnell.quickscope.logic.Task
import com.jonasschnell.quickscope.logic.audioPrompt
import com.jonasschnell.quickscope.logic.beforeAfterLine
import com.jonasschnell.quickscope.logic.formatExamWhen
import com.jonasschnell.quickscope.logic.floor5
import com.jonasschnell.quickscope.logic.formatRemaining
import com.jonasschnell.quickscope.logic.masteryPercent
import com.jonasschnell.quickscope.logic.minutesChoiceLabel
import com.jonasschnell.quickscope.logic.percentText
import com.jonasschnell.quickscope.logic.isInPast
import com.jonasschnell.quickscope.logic.outcomeFor
import com.jonasschnell.quickscope.logic.parseTask
import com.jonasschnell.quickscope.logic.pickActiveExam
import com.jonasschnell.quickscope.logic.projectionLine
import com.jonasschnell.quickscope.logic.remainingMinutes
import com.jonasschnell.quickscope.logic.sessionProgress
import com.jonasschnell.quickscope.logic.shortExplanation
import com.jonasschnell.quickscope.logic.sortExams
import com.jonasschnell.quickscope.logic.startLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class LogicTest {
    private val now = Instant.parse("2026-11-09T12:00:00Z")

    private fun exam(id: String, startsAt: String, status: String = "ready") =
        Exam(id = id, name = id, startsAt = startsAt, status = status)

    private fun item(id: String, type: String, answer: String = "x") = Item(id = id, type = type, question = "Frage?", answer = answer)

    // --- aktive Pruefung ---

    @Test fun activeExamPrefersChosenThenNextThenLastOver() {
        val soon = exam("soon", "2026-11-12T08:00:00Z")
        val later = exam("later", "2026-12-01T08:00:00Z")
        val over1 = exam("over1", "2026-11-01T08:00:00Z", "over")
        val over2 = exam("over2", "2026-11-05T08:00:00Z", "over")
        assertEquals("later", pickActiveExam(listOf(soon, later, over1), "later", now)?.id)
        assertEquals("soon", pickActiveExam(listOf(later, soon, over1), null, now)?.id)
        assertEquals("soon", pickActiveExam(listOf(later, soon), "gibtsnicht", now)?.id)
        assertEquals("over2", pickActiveExam(listOf(over1, over2), null, now)?.id)
        assertNull(pickActiveExam(emptyList(), null, now))
    }

    @Test fun examListSortsUpcomingBeforePast() {
        val list = listOf(exam("o", "2026-11-01T08:00:00Z", "over"), exam("b", "2026-12-01T08:00:00Z"), exam("a", "2026-11-12T08:00:00Z"))
        assertEquals(listOf("a", "b", "o"), sortExams(list, now).map { it.id })
    }

    // --- Zeiten ---

    @Test fun remainingTextFollowsTime() {
        assertEquals("noch 12 Tage 4 Std", formatRemaining(Instant.parse("2026-11-21T16:00:00Z"), now))
        assertEquals("noch 1 Tag", formatRemaining(Instant.parse("2026-11-10T12:00:00Z"), now))
        assertEquals("noch 5 Std 20 Min", formatRemaining(Instant.parse("2026-11-09T17:20:00Z"), now))
        assertEquals("noch 20 Min", formatRemaining(Instant.parse("2026-11-09T12:20:00Z"), now))
        assertEquals("Prüfung vorbei", formatRemaining(Instant.parse("2026-11-09T11:00:00Z"), now))
        assertEquals("Prüfung vorbei", formatRemaining(null, now))
    }

    @Test fun whenTextAndPastCheck() {
        assertEquals("Fr., 13. Nov., 09:00", formatExamWhen("2026-11-13", "09:00"))
        assertEquals("kaputt 09:00", formatExamWhen("kaputt", "09:00"))
        assertTrue(isInPast(LocalDate.of(2026, 11, 9), LocalTime.of(8, 0), now))
        assertFalse(isInPast(LocalDate.of(2026, 11, 9), LocalTime.of(18, 0), now))
        assertFalse(isInPast(LocalDate.of(2026, 11, 10), LocalTime.of(8, 0), now))
    }

    // --- Prognosetexte ---

    @Test fun percentsAreAlwaysFlooredToFive() {
        assertEquals(0, floor5(0))
        assertEquals(0, floor5(4))
        assertEquals(5, floor5(5))
        assertEquals(75, floor5(78))
        assertEquals(75, floor5(79))
        assertEquals(80, floor5(80))
        assertEquals(95, floor5(99))
        assertEquals(100, floor5(100))
        assertEquals(100, floor5(140))
        assertEquals(0, floor5(-3))
        assertEquals("75 %", percentText(79))
        assertEquals(35, masteryPercent(0.39))
    }

    @Test fun forecastTextsAreShortAndHonest() {
        val f = Forecast(scoreNowPercent = 40, scoreAtExamPercent = 78, confidence = "low",
            projections = listOf(Projection(10, 62), Projection(20, 74)))
        assertEquals("Bei 20 min täglich: ca. 75 % in der Prüfung, noch grob", projectionLine(f, 20))
        assertEquals("Bei 30 min täglich: ca. 75 % in der Prüfung", projectionLine(f.copy(confidence = "medium"), 30))
        assertEquals("10 min · ca. 60 %", minutesChoiceLabel(f, 10))
        assertEquals("20 min · ca. 70 %", minutesChoiceLabel(f, 20))
        assertEquals("45 min", minutesChoiceLabel(f, 45))
        assertEquals("45 min", minutesChoiceLabel(null, 45))
        assertEquals("Prognose 40 % → 50 %", beforeAfterLine(42, 52))
        assertEquals("Prognose 50 %", beforeAfterLine(51, 52))
        assertEquals(20, remainingMinutes(30, 600))
        assertEquals(3, remainingMinutes(10, 3000))
        assertEquals("Los (20 min)", startLabel(20))
    }

    // --- Antwort-Warteschlange ---

    private class MemoryStorage : QueueStorage {
        val map = mutableMapOf<String, String>()
        override fun read(key: String) = map[key]
        override fun write(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
    }

    @Test fun queueKeepsAttemptsUntilSent() {
        val storage = MemoryStorage()
        val q = AttemptQueue(storage)
        q.add("e1", AttemptRequest("a", "right", 1200, "2026-11-09T12:00:00Z"))
        q.add("e1", AttemptRequest("b", "unknown", 800))
        q.add("e2", AttemptRequest("c", "known", 500))
        // nach "App-Neustart": neue Queue auf derselben Ablage sieht alles
        val again = AttemptQueue(storage)
        assertEquals(listOf("a", "b"), again.pending("e1").map { it.itemId })
        assertEquals("right", again.pending("e1")[0].outcome)
        assertEquals(1200, again.pending("e1")[0].ms)
        // waehrend des Sendens kommt Neues dazu: nur die gesendeten werden entfernt
        again.add("e1", AttemptRequest("d", "wrong", 900))
        again.removeSent("e1", 2)
        assertEquals(listOf("d"), again.pending("e1").map { it.itemId })
        again.removeSent("e1", 1)
        assertTrue(again.pending("e1").isEmpty())
        assertFalse(storage.map.containsKey("attempts_e1"))
        assertEquals(1, again.pending("e2").size)
    }

    // --- Aufgaben ---

    @Test fun brokenPayloadFallsBackToCard() {
        assertTrue(parseTask(item("1", "match")) is Task.Card)
        assertTrue(parseTask(item("2", "mc")) is Task.Card)
        assertTrue(parseTask(item("3", "unbekannt")) is Task.Card)
    }

    @Test fun outcomesMatchTaskKind() {
        val card = parseTask(item("1", "card"))
        assertEquals("known", outcomeFor(card, true))
        assertEquals("unknown", outcomeFor(card, false))
        val tf = parseTask(item("2", "truefalse", "falsch")) as Task.Choice
        assertEquals(1, tf.correct)
        assertEquals("right", outcomeFor(tf, true))
        assertEquals("wrong", outcomeFor(tf, false))
    }

    @Test fun matchStateCountsMistakesAndFinishes() {
        val s = MatchState(listOf("a", "b"), listOf("2", "1"), mapOf("a" to "1", "b" to "2"))
        assertNull(s.tapRight("1"))
        s.tapLeft("a")
        assertEquals(false, s.tapRight("2"))
        s.tapLeft("a")
        assertEquals(true, s.tapRight("1"))
        s.tapLeft("b")
        assertEquals(true, s.tapRight("2"))
        assertTrue(s.done)
        assertEquals(1, s.mistakes)
    }

    @Test fun orderStateNeedsRightSequence() {
        val s = OrderState(listOf("A", "B", "C"), listOf("C", "A", "B"))
        assertFalse(s.tap("B"))
        assertTrue(s.tap("A"))
        assertTrue(s.tap("B"))
        assertTrue(s.tap("C"))
        assertTrue(s.done)
        assertEquals(1, s.mistakes)
    }

    @Test fun explanationIsAtMostTwoSentences() {
        assertEquals("Eins. Zwei.", shortExplanation("Eins. Zwei. Drei."))
        assertNull(shortExplanation("  "))
        assertNull(shortExplanation(null))
    }

    @Test fun audioPromptIsOnlyTheQuestion() {
        val mc = parseTask(Item(id = "x", type = "mc", question = "Q?", answer = "A", options = listOf("A", "B"), correctIndex = 0, ttsText = "Q? A: A. B: B."))
        assertEquals("Q?", audioPrompt(mc))
        val cloze = parseTask(Item(id = "c", type = "cloze", question = "Plus ___ ergibt Preis.", answer = "Gewinn",
            payload = kotlinx.serialization.json.buildJsonObject {
                put("text", kotlinx.serialization.json.JsonPrimitive("Plus ___ ergibt Preis."))
                put("bank", kotlinx.serialization.json.JsonArray(listOf("Gewinn", "Skonto").map { kotlinx.serialization.json.JsonPrimitive(it) }))
                put("answer", kotlinx.serialization.json.JsonPrimitive("Gewinn"))
            }))
        assertEquals("Plus Lücke ergibt Preis.", audioPrompt(cloze))
    }

    @Test fun progressFollowsEstimatedSeconds() {
        val items = listOf(Item(id = "a", estSeconds = 10), Item(id = "b", estSeconds = 30))
        assertEquals(0f, sessionProgress(items, 0), 0.001f)
        assertEquals(0.25f, sessionProgress(items, 1), 0.001f)
        assertEquals(1f, sessionProgress(items, 2), 0.001f)
        assertNotEquals(0f, sessionProgress(items, 1))
    }
}
