package com.jonasschnell.quickscope

import com.jonasschnell.quickscope.data.AttemptRequest
import com.jonasschnell.quickscope.data.Exam
import com.jonasschnell.quickscope.data.Forecast
import com.jonasschnell.quickscope.data.Item
import com.jonasschnell.quickscope.data.Projection
import com.jonasschnell.quickscope.logic.AttemptQueue
import com.jonasschnell.quickscope.logic.MatchResult
import com.jonasschnell.quickscope.logic.MatchState
import com.jonasschnell.quickscope.logic.OrderState
import com.jonasschnell.quickscope.logic.QueueStorage
import com.jonasschnell.quickscope.logic.Task
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

    @Test fun matchEvaluatesEachPairAtOnce() {
        val s = MatchState(listOf("a", "b", "c"), listOf("3", "1", "2"), mapOf("a" to "1", "b" to "2", "c" to "3"))
        assertEquals(MatchResult.None, s.tapRight("1"))               // ohne Auswahl links passiert nichts
        s.tapLeft("a")
        assertEquals(MatchResult.Wrong, s.tapRight("2"))              // falsch: Verbindung loest sich, Fehler zaehlt
        assertEquals(1, s.mistakes)
        assertFalse("a" in s.matched)
        assertNull(s.selectedLeft)
        s.tapLeft("a")
        assertEquals(MatchResult.Right, s.tapRight("1"))              // richtig: bleibt verbunden, eigene Farbe
        assertTrue("a" in s.matched)
        assertEquals(0, s.colorOf("a"))
        assertEquals(0, s.colorOfRight("1"))
        s.tapLeft("b")
        assertEquals(MatchResult.Right, s.tapRight("2"))
        assertEquals(1, s.colorOf("b"))
        assertFalse(s.done)
        s.tapLeft("c")
        assertEquals(MatchResult.Right, s.tapRight("3"))
        assertTrue(s.done)
        assertFalse(s.known)                                          // ein Fehler: nicht gewusst
    }

    @Test fun matchRevealsPairAfterTwoWrongTries() {
        val s = MatchState(listOf("a", "b"), listOf("2", "1"), mapOf("a" to "1", "b" to "2"))
        s.tapLeft("a"); assertEquals(MatchResult.Wrong, s.tapRight("2"))
        s.tapLeft("a"); assertEquals(MatchResult.Revealed, s.tapRight("2"))
        assertEquals(setOf("a"), s.revealed)
        assertTrue("a" in s.matched)                                  // richtiges Paar wird gezeigt
        assertEquals(2, s.mistakes)
        s.tapLeft("b"); assertEquals(MatchResult.Right, s.tapRight("2"))
        assertTrue(s.done)
        assertFalse(s.known)                                          // aufgedeckt: gilt als nicht gewusst
    }

    @Test fun matchWithoutMistakesIsKnown() {
        val s = MatchState(listOf("a", "b"), listOf("2", "1"), mapOf("a" to "1", "b" to "2"))
        s.tapLeft("a"); s.tapRight("1")
        s.tapLeft("b"); s.tapRight("2")
        assertTrue(s.done)
        assertTrue(s.known)
        // erneut tippen auf ein verbundenes Paar aendert nichts
        s.tapLeft("a")
        assertNull(s.selectedLeft)
    }

    @Test fun matchTapSelectedLeftAgainClearsSelection() {
        val s = MatchState(listOf("a", "b"), listOf("2", "1"), mapOf("a" to "1", "b" to "2"))
        s.tapLeft("a"); s.tapLeft("a")
        assertNull(s.selectedLeft)
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

    @Test fun progressFollowsEstimatedSeconds() {
        val items = listOf(Item(id = "a", estSeconds = 10), Item(id = "b", estSeconds = 30))
        assertEquals(0f, sessionProgress(items, 0), 0.001f)
        assertEquals(0.25f, sessionProgress(items, 1), 0.001f)
        assertEquals(1f, sessionProgress(items, 2), 0.001f)
        assertNotEquals(0f, sessionProgress(items, 1))
    }

    @Test fun aboutCounterValidationAndSaveLogic() {
        assertEquals("0/600", com.jonasschnell.quickscope.logic.aboutCounter(""))
        assertEquals("12/600", com.jonasschnell.quickscope.logic.aboutCounter("a".repeat(12)))
        assertTrue(com.jonasschnell.quickscope.logic.aboutValid(""))
        assertTrue(com.jonasschnell.quickscope.logic.aboutValid("a".repeat(600)))
        assertFalse(com.jonasschnell.quickscope.logic.aboutValid("a".repeat(601)))
        assertEquals(600, com.jonasschnell.quickscope.logic.clampAbout("a".repeat(900)).length)
        assertEquals("Snowboarder", com.jonasschnell.quickscope.logic.normalizeAbout("  Snowboarder \n"))
        assertFalse(com.jonasschnell.quickscope.logic.aboutNeedsSave("abc", " abc "))
        assertTrue(com.jonasschnell.quickscope.logic.aboutNeedsSave("abc", ""))
    }

    @Test fun ageAndProfileDraftValidation() {
        val l = com.jonasschnell.quickscope.logic.AGE_MIN
        assertEquals(18, com.jonasschnell.quickscope.logic.AGE_DEFAULT)
        assertTrue(com.jonasschnell.quickscope.logic.ageValid(null))
        assertTrue(com.jonasschnell.quickscope.logic.ageValid(10))
        assertTrue(com.jonasschnell.quickscope.logic.ageValid(99))
        assertFalse(com.jonasschnell.quickscope.logic.ageValid(9))
        assertFalse(com.jonasschnell.quickscope.logic.ageValid(100))
        assertEquals(10, com.jonasschnell.quickscope.logic.clampAge(l - 5))
        assertEquals(99, com.jonasschnell.quickscope.logic.clampAge(120))
        val saved = com.jonasschnell.quickscope.logic.ProfileDraft(18, "abitur", "x")
        assertFalse(com.jonasschnell.quickscope.logic.draftNeedsSave(saved, saved.copy(about = " x ")))
        assertTrue(com.jonasschnell.quickscope.logic.draftNeedsSave(saved, saved.copy(age = 19)))
        assertTrue(com.jonasschnell.quickscope.logic.draftNeedsSave(saved, saved.copy(education = "studium")))
        assertFalse(com.jonasschnell.quickscope.logic.draftNeedsSave(saved, saved.copy(age = 120)))
        assertFalse(com.jonasschnell.quickscope.logic.draftNeedsSave(saved, saved.copy(about = "a".repeat(601))))
        assertEquals("Studium", com.jonasschnell.quickscope.logic.educationLabel("studium"))
        assertEquals("Anderes", com.jonasschnell.quickscope.logic.educationLabel("other"))
        assertEquals("–", com.jonasschnell.quickscope.logic.educationLabel(null))
        assertEquals(6, com.jonasschnell.quickscope.logic.EDUCATION.size)
    }

    @Test fun onboardingRunsOnlyWhenNotDoneAndNeverAgain() {
        val gate = { p: com.jonasschnell.quickscope.data.Profile? -> com.jonasschnell.quickscope.logic.gateFor(p) }
        assertEquals(com.jonasschnell.quickscope.logic.OnboardingGate.Needed, gate(com.jonasschnell.quickscope.data.Profile(onboardingDone = false)))
        assertEquals(com.jonasschnell.quickscope.logic.OnboardingGate.Done, gate(com.jonasschnell.quickscope.data.Profile(onboardingDone = true)))
        // Netzfehler: nie endlos blockieren, sondern Retry
        assertEquals(com.jonasschnell.quickscope.logic.OnboardingGate.Failed, gate(null))
    }

    @Test fun onboardingPagesFollowAFixedOrder() {
        val p = com.jonasschnell.quickscope.logic.OnboardingPage.entries
        assertEquals(listOf("Welcome", "Age", "Education", "Voice"), p.map { it.name })
        assertEquals(com.jonasschnell.quickscope.logic.OnboardingPage.Age, com.jonasschnell.quickscope.logic.nextPage(p[0]))
        assertNull(com.jonasschnell.quickscope.logic.nextPage(p.last()))
        assertNull(com.jonasschnell.quickscope.logic.previousPage(p.first()))
        assertEquals("Hi Lena", com.jonasschnell.quickscope.logic.greeting("Lena Beispiel"))
        assertEquals("Hi", com.jonasschnell.quickscope.logic.greeting(null))
        assertEquals("Hi", com.jonasschnell.quickscope.logic.greeting("  "))
        assertEquals("0:07", com.jonasschnell.quickscope.logic.recordTime(7))
        assertEquals("1:00", com.jonasschnell.quickscope.logic.recordTime(com.jonasschnell.quickscope.logic.RECORD_MAX_SECONDS))
    }
}
