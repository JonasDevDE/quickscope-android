package com.jonasschnell.quickscope

import com.jonasschnell.quickscope.data.AttemptRequest
import com.jonasschnell.quickscope.data.Exam
import com.jonasschnell.quickscope.data.Forecast
import com.jonasschnell.quickscope.data.Item
import com.jonasschnell.quickscope.data.Projection
import com.jonasschnell.quickscope.logic.AttemptQueue
import com.jonasschnell.quickscope.SessionPhase as P
import com.jonasschnell.quickscope.logic.MatchResult
import com.jonasschnell.quickscope.logic.MoreEntry as M
import com.jonasschnell.quickscope.logic.MatchState
import com.jonasschnell.quickscope.logic.OrderState
import com.jonasschnell.quickscope.logic.QueueStorage
import com.jonasschnell.quickscope.logic.Task
import com.jonasschnell.quickscope.logic.beforeAfterLine
import com.jonasschnell.quickscope.logic.formatExamWhen
import com.jonasschnell.quickscope.logic.floor5
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
import com.jonasschnell.quickscope.logic.START_LABEL
import com.jonasschnell.quickscope.logic.scoreOrNull
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
        assertEquals("Starten", START_LABEL)
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
        assertTrue(com.jonasschnell.quickscope.logic.ageValid(14))
        assertTrue(com.jonasschnell.quickscope.logic.ageValid(99))
        assertFalse(com.jonasschnell.quickscope.logic.ageValid(13))
        assertFalse(com.jonasschnell.quickscope.logic.ageValid(100))
        assertEquals(14, com.jonasschnell.quickscope.logic.clampAge(l - 5))
        assertEquals(14, l)
        assertEquals(14, com.jonasschnell.quickscope.logic.draftOf(com.jonasschnell.quickscope.data.Profile(age = 10)).age)   // bestehende Werte unter 14 werden angehoben
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

    @Test fun secretPackingRoundTripsAndRejectsGarbage() {
        val iv = ByteArray(12) { it.toByte() }
        val ct = ByteArray(40) { (it * 3).toByte() }
        val packed = com.jonasschnell.quickscope.logic.packSecret(iv, ct)
        val (iv2, ct2) = com.jonasschnell.quickscope.logic.unpackSecret(packed)!!
        assertTrue(iv.contentEquals(iv2))
        assertTrue(ct.contentEquals(ct2))
        assertNull(com.jonasschnell.quickscope.logic.unpackSecret(ByteArray(0)))
        assertNull(com.jonasschnell.quickscope.logic.unpackSecret(byteArrayOf(0, 1, 2)))
        assertNull(com.jonasschnell.quickscope.logic.unpackSecret(byteArrayOf(12, 1, 2)))
        // Migration: nur wenn ein alter Klartextwert da ist
        assertTrue(com.jonasschnell.quickscope.logic.needsSecretMigration("bst_x"))
        assertFalse(com.jonasschnell.quickscope.logic.needsSecretMigration(null))
    }

    @Test fun todayShowsOnlyCountdownRingAndStartWithTheRestBehindMore() {
        val now = Instant.parse("2026-11-09T12:00:00Z")
        // mit Lektionen und Plan: alles hinter "Mehr", in Gruppen (Ueben, Heute, Pruefung)
        assertEquals(
            listOf(M.QuestionsOnly, M.ListenOnly, M.Plan, M.Used, M.Streak, M.Material, M.Settings),
            com.jonasschnell.quickscope.logic.moreEntries(hasLessons = true, hasPlan = true, streak = 3),
        )
        // ohne Lektionen, Plan und Serie bleibt nur das Noetigste
        assertEquals(listOf(M.Used, M.Material, M.Settings), com.jonasschnell.quickscope.logic.moreEntries(false, false, null))
        assertEquals(listOf(M.Used, M.Material, M.Settings), com.jonasschnell.quickscope.logic.moreEntries(false, false, 0))
    }

    @Test fun doubleStartAndDoubleTapAreDiscarded() {
        // Start nur, wenn keine Runde laeuft oder laedt (auch kein Start waehrend Gate/Done)
        assertTrue(com.jonasschnell.quickscope.logic.canStartSession(P.Closed))
        assertTrue(com.jonasschnell.quickscope.logic.canStartSession(P.Empty))   // "Noch eine Runde"
        assertTrue(com.jonasschnell.quickscope.logic.canStartSession(P.Error))   // "Nochmal"
        assertFalse(com.jonasschnell.quickscope.logic.canStartSession(P.Loading))
        assertFalse(com.jonasschnell.quickscope.logic.canStartSession(P.Running))
        assertFalse(com.jonasschnell.quickscope.logic.canStartSession(P.Done))
        // Hoerbuch: eine Wiedergabe zur Zeit
        assertTrue(com.jonasschnell.quickscope.logic.canStartBook("Closed"))
        assertFalse(com.jonasschnell.quickscope.logic.canStartBook("Loading"))
        assertFalse(com.jonasschnell.quickscope.logic.canStartBook("Playing"))
        // Mehrfach-Tap-Schutz fuer Schliessen und Navigation
        assertTrue(com.jonasschnell.quickscope.logic.acceptClick(0, 100))
        assertFalse(com.jonasschnell.quickscope.logic.acceptClick(1_000, 1_300))
        assertTrue(com.jonasschnell.quickscope.logic.acceptClick(1_000, 1_500))
    }

    // --- Relative Tagesangabe aus Kalendertagen ---

    private val berlin = java.time.ZoneId.of("Europe/Berlin")
    private fun day(start: String, now: String, startOfSentence: Boolean = false, zone: java.time.ZoneId = berlin) =
        com.jonasschnell.quickscope.logic.relativeDayLabel(Instant.parse(start), Instant.parse(now), zone, startOfSentence)

    @Test fun relativeDayUsesCalendarDaysNotHours() {
        // jetzt Mo 21:00 Berlin (20:00Z); Pruefung Mi 08:00 Berlin (07:00Z) = nur 35 Stunden, aber uebermorgen
        assertEquals("übermorgen", day("2026-11-11T07:00:00Z", "2026-11-09T20:00:00Z"))
        assertEquals("morgen", day("2026-11-10T07:00:00Z", "2026-11-09T20:00:00Z"))
        assertEquals("heute, in 5 Std", day("2026-11-09T20:00:00Z", "2026-11-09T15:00:00Z"))
        assertEquals("in 20 Min", day("2026-11-09T12:20:00Z", "2026-11-09T12:00:00Z"))
        assertEquals("in 5 Tagen", day("2026-11-14T08:00:00Z", "2026-11-09T12:00:00Z"))
        assertEquals("vorbei", day("2026-11-08T08:00:00Z", "2026-11-09T12:00:00Z"))   // gestern
        assertEquals("vorbei", com.jonasschnell.quickscope.logic.relativeDayLabel(null, Instant.parse("2026-11-09T12:00:00Z"), berlin))
    }

    @Test fun relativeDayAroundMidnight() {
        // 23:59 Berlin, Pruefung 00:30 Berlin am Folgetag: nur 31 Minuten, aber "morgen" im Kalender
        assertEquals("morgen", day("2026-11-09T23:30:00Z", "2026-11-09T22:59:00Z"))
        // 00:01 Berlin am Pruefungstag, Pruefung 08:00 Berlin: heute
        assertEquals("heute, in 7 Std", day("2026-11-10T07:00:00Z", "2026-11-09T23:59:00Z"))
    }

    @Test fun relativeDayDependsOnTimeZone() {
        // gleicher Zeitpunkt: in Berlin schon der naechste Tag, in New York noch derselbe
        val start = "2026-11-10T04:00:00Z"; val now = "2026-11-09T14:00:00Z"
        assertEquals("morgen", day(start, now, zone = berlin))
        assertEquals("heute, in 14 Std", day(start, now, zone = java.time.ZoneId.of("America/New_York")))
    }

    @Test fun relativeDayCapitalisation() {
        assertEquals("Morgen", day("2026-11-10T07:00:00Z", "2026-11-09T20:00:00Z", startOfSentence = true))
        assertEquals("Übermorgen", day("2026-11-11T07:00:00Z", "2026-11-09T20:00:00Z", startOfSentence = true))
        assertEquals("In 5 Tagen", day("2026-11-14T08:00:00Z", "2026-11-09T12:00:00Z", startOfSentence = true))
        assertEquals("Vorbei", day("2026-11-08T08:00:00Z", "2026-11-09T12:00:00Z", startOfSentence = true))
        assertEquals("Prüfung morgen", "Prüfung " + day("2026-11-10T07:00:00Z", "2026-11-09T20:00:00Z"))   // mitten im Satz klein
    }

    // --- "–" statt "0 %", solange nie geuebt wurde ---

    private fun f(scoreNowPercent: Int = 0, scoreAtExamPercent: Int = 0, confidence: String = "low", coveragePercent: Int = 0, mode: String? = null, knowledgePercent: Int? = null) =
        com.jonasschnell.quickscope.data.Forecast(scoreNowPercent = scoreNowPercent, scoreAtExamPercent = scoreAtExamPercent, confidence = confidence, coveragePercent = coveragePercent, mode = mode, knowledgePercent = knowledgePercent)

    private fun exam(kind: String = "exam") = com.jonasschnell.quickscope.data.Exam(id = "e", status = "ready", kind = kind)

    @Test fun ringShowsZeroPercentNeverADash() {
        val never = f(scoreNowPercent = 0, scoreAtExamPercent = 0, confidence = "none", coveragePercent = 0)
        assertEquals("0 %", com.jonasschnell.quickscope.logic.ringLabel(exam(), never))
        assertEquals("0 %", com.jonasschnell.quickscope.logic.ringLabel(exam(), f(confidence = "low", coveragePercent = 0)))
        assertEquals("0 %", com.jonasschnell.quickscope.logic.ringLabel(exam(), null))                                // noch keine Prognose
        assertEquals("0 %", com.jonasschnell.quickscope.logic.ringLabel(null, null))
        assertEquals("0 %", com.jonasschnell.quickscope.logic.ringLabel(exam("open"), f(mode = "open", knowledgePercent = null)))   // Wissensstand
        // mit Zahl: wie gehabt in 5er-Schritten abgerundet
        assertEquals("0 %", com.jonasschnell.quickscope.logic.ringLabel(exam(), f(scoreNowPercent = 0, confidence = "low", coveragePercent = 10)))
        assertEquals("60 %", com.jonasschnell.quickscope.logic.ringLabel(exam(), f(scoreNowPercent = 63, confidence = "low", coveragePercent = 40)))
        assertEquals("45 %", com.jonasschnell.quickscope.logic.ringLabel(exam("open"), f(mode = "open", knowledgePercent = 47)))
        for (fc in listOf(never, f(coveragePercent = 0), f(mode = "open"))) assertTrue(!com.jonasschnell.quickscope.logic.ringLabel(exam(), fc).contains("–"))
    }

    @Test fun scoreForComparisonIsNullWithoutAnswers() {
        assertEquals(null, f(scoreNowPercent = 0, confidence = "none", coveragePercent = 0).scoreOrNull())
        assertEquals(null, f(scoreNowPercent = 0, confidence = "low", coveragePercent = 0).scoreOrNull())
        assertEquals(0, f(scoreNowPercent = 0, confidence = "low", coveragePercent = 5).scoreOrNull())
        assertEquals(null, f(mode = "open", knowledgePercent = null).scoreOrNull())
        assertEquals(35, f(mode = "open", knowledgePercent = 35).scoreOrNull())
    }

    @Test fun startButtonHasNoMinutes() {
        assertTrue(!START_LABEL.contains("min") && !START_LABEL.any { it.isDigit() })
    }

    // --- Kopfzeile der Lektionskarten ---

    private fun les(id: String, chapterId: String? = null, title: String? = null, order: Int? = null, count: Int? = null, idx: Int? = null, n: Int? = null) =
        com.jonasschnell.quickscope.data.SessionLesson(
            id, "Lektion $id", listOf("a"), "t", null, 30, chapterId = chapterId, chapterTitle = title, chapterOrder = order, chapterCount = count,
            lessonIndexInChapter = idx, lessonCountInChapter = n,
        )

    @Test fun lessonHeaderWithBackendFields() {
        val a = les("1", "c1", "Rechnen", 2, 6, 3, 5)
        val h = com.jonasschnell.quickscope.logic.lessonHeader(a, listOf(a), lessonsOnly = true)
        assertEquals("Kapitel 2 von 6 · Rechnen", h.chapterLine)
        assertEquals("Lektion 1", h.title)
        assertEquals(3 to 5, h.progress)
        assertEquals("Lektion 3 von 5", h.progressText)
    }

    @Test fun lessonHeaderDetectsChapterChange() {
        val run = listOf(les("1", "c1", "Rechnen", 1, 2), les("2", "c1", "Rechnen", 1, 2), les("3", "c2", "Recht", 2, 2))
        assertFalse(com.jonasschnell.quickscope.logic.lessonHeader(run[1], run, true).newChapter)
        assertTrue(com.jonasschnell.quickscope.logic.lessonHeader(run[2], run, true).newChapter)
        assertFalse(com.jonasschnell.quickscope.logic.lessonHeader(run[0], run, true).newChapter)   // erste Lektion: kein Wechsel
        // Fortschritt aus der Runde: Lektion 2 von 2 im Kapitel c1, 1 von 1 in c2
        assertEquals(2 to 2, com.jonasschnell.quickscope.logic.lessonHeader(run[1], run, true).progress)
        assertEquals(1 to 1, com.jonasschnell.quickscope.logic.lessonHeader(run[2], run, true).progress)
    }

    @Test fun lessonHeaderToleratesMissingFields() {
        val bare = les("1")
        val h = com.jonasschnell.quickscope.logic.lessonHeader(bare, listOf(bare, les("2")), lessonsOnly = true)
        assertEquals(null, h.chapterLine)                 // nur der Lektionstitel
        assertEquals(1 to 2, h.progress)                  // Position in der Verstehen-Runde
        // normale Runde ohne Angaben: gar kein Fortschritt
        assertEquals(null, com.jonasschnell.quickscope.logic.lessonHeader(bare, listOf(bare), lessonsOnly = false).progress)
        // Kapiteltitel ohne Nummer
        assertEquals("Rechnen", com.jonasschnell.quickscope.logic.lessonHeader(les("1", "c1", "Rechnen"), listOf(), false).chapterLine)
    }

    @Test fun enrichFillsOnlyMissingChapterFields() {
        val chapters = listOf(
            com.jonasschnell.quickscope.data.Chapter(chapterId = "c1", title = "Rechnen", order = 1),
            com.jonasschnell.quickscope.data.Chapter(chapterId = "c2", title = "Recht", order = 2),
        )
        val e = com.jonasschnell.quickscope.logic.enrichLesson(les("7"), mapOf("7" to "c2"), chapters)
        assertEquals("c2", e.chapterId)
        assertEquals("Recht", e.chapterTitle)
        assertEquals(2, e.chapterOrder)
        assertEquals(2, e.chapterCount)
        val keep = com.jonasschnell.quickscope.logic.enrichLesson(les("7", "c1", "Eigener Titel", 9, 9), mapOf("7" to "c2"), chapters)
        assertEquals("Eigener Titel", keep.chapterTitle)
        assertEquals(9, keep.chapterOrder)
        // unbekanntes Kapitel: nichts erfinden
        val none = com.jonasschnell.quickscope.logic.enrichLesson(les("8"), emptyMap(), chapters)
        assertEquals(null, none.chapterTitle)
        assertEquals(null, none.chapterCount)
    }

    // --- Verstehen: Navigation ueber Kapitel und Lektionen ---

    private fun ch(id: String, order: Int, status: String = "new", title: String = "K$order") = com.jonasschnell.quickscope.data.Chapter(chapterId = id, title = title, order = order, status = status)

    private fun sl(id: String, chapterId: String?) = com.jonasschnell.quickscope.data.SessionLesson(id, "L$id", listOf("a"), "t", null, 30, chapterId = chapterId)

    @Test fun understandListIsOrderedByChapterAndStartsAtFirstUnheard() {
        val chapters = listOf(ch("c2", 2), ch("c1", 1))
        // gelieferte Reihenfolge durcheinander: Kapitel 2 zuerst
        val lessons = listOf(sl("3", "c2"), sl("1", "c1"), sl("4", "c2"), sl("2", "c1"))
        val u = com.jonasschnell.quickscope.logic.buildUnderstandList(chapters, lessons, heard = setOf("1"))
        assertEquals(listOf("1", "2", "3", "4"), u.lessons.map { it.id })     // ohne Limit, nach Kapitelreihenfolge
        assertEquals(1, u.start)                                               // erste ungehoerte
        assertEquals(1 to 2, u.lessons[0].lessonIndexInChapter to u.lessons[0].lessonCountInChapter)
        assertEquals(2 to 2, u.lessons[3].lessonIndexInChapter to u.lessons[3].lessonCountInChapter)
        assertEquals(2, u.lessons[2].chapterOrder)
        assertEquals(2, u.lessons[0].chapterCount)
        // alles gehoert: Start bei der ersten Lektion
        assertEquals(0, com.jonasschnell.quickscope.logic.buildUnderstandList(chapters, lessons, setOf("1", "2", "3", "4")).start)
        // mehr als 8 Lektionen: kein Limit
        val many = (1..20).map { sl("$it", "c1") }
        assertEquals(20, com.jonasschnell.quickscope.logic.buildUnderstandList(listOf(ch("c1", 1)), many, emptySet()).lessons.size)
    }

    @Test fun stepsCrossChapterBoundariesAndStopAtTheEnds() {
        val step = { i: Int, d: Int -> com.jonasschnell.quickscope.logic.stepIndex(i, 5, d) }
        assertEquals(3, step(2, 1))
        assertEquals(1, step(2, -1))      // Zurueck auf der ersten Lektion eines Kapitels = letzte des vorherigen (flache Liste)
        assertEquals(null, step(0, -1))   // erste Lektion: Zurueck tut nichts
        assertEquals(null, step(4, 1))    // letzte Lektion: Weiter = fertig
        // Kapitelgrenze in der Liste: letzte von c1 -> erste von c2 und zurueck
        val u = com.jonasschnell.quickscope.logic.buildUnderstandList(listOf(ch("c1", 1), ch("c2", 2)), listOf(sl("1", "c1"), sl("2", "c1"), sl("3", "c2")), emptySet())
        val next = com.jonasschnell.quickscope.logic.stepIndex(1, u.lessons.size, 1)!!
        assertEquals("c2", u.lessons[next].chapterId)
        assertEquals("c1", u.lessons[com.jonasschnell.quickscope.logic.stepIndex(next, u.lessons.size, -1)!!].chapterId)
    }

    @Test fun chapterListShowsStatusAndJumpsToFirstUnheard() {
        val chapters = listOf(ch("c1", 1, "solid"), ch("c2", 2, "heard"), ch("c3", 3, "new"))
        val u = com.jonasschnell.quickscope.logic.buildUnderstandList(chapters, listOf(sl("1", "c1"), sl("2", "c2"), sl("3", "c2"), sl("4", "c3")), setOf("2"))
        val e = com.jonasschnell.quickscope.logic.chapterEntries(u.lessons, chapters, setOf("2"))
        assertEquals(listOf("sitzt", "gehört", "neu"), e.map { it.status })
        assertEquals(listOf(0, 2, 3), e.map { it.jumpIndex })     // Kapitel 2: Lektion 2 gehoert, Sprung auf die ungehoerte 3
        // Status ohne Backend-Angabe: alle gehoert = gehoert
        val noApi = com.jonasschnell.quickscope.logic.chapterEntries(u.lessons, emptyList(), setOf("2", "3"))
        assertEquals("gehört", noApi.first { it.chapterId == "c2" }.status)
        assertEquals("neu", noApi.first { it.chapterId == "c3" }.status)
        // alle gehoert: Sprung auf die erste Lektion des Kapitels
        assertEquals(1, com.jonasschnell.quickscope.logic.chapterEntries(u.lessons, chapters, setOf("2", "3")).first { it.chapterId == "c2" }.jumpIndex)
    }

    @Test fun heardReportRules() {
        val r = { played: Boolean, pos: Long, dur: Long, fin: Boolean, shown: Long, fwd: Boolean -> com.jonasschnell.quickscope.logic.heardToReport(played, pos, dur, fin, shown, fwd) }
        assertEquals(1.0, r(true, 30_000, 30_000, true, 31_000, true)!!, 0.0001)         // Audio bis zum Ende
        assertEquals(0.5, r(true, 15_000, 30_000, false, 16_000, true)!!, 0.0001)        // nur der gehoerte Anteil
        assertEquals(null, r(true, 500, 30_000, false, 600, true))                       // kaum etwas gehoert
        assertEquals(0.8, r(false, 0, 30_000, false, 5_000, true)!!, 0.0001)             // Lesemodus: Weiter nach 5 s
        assertEquals(null, r(false, 0, 30_000, false, 4_999, true))                      // zu kurz
        assertEquals(null, r(false, 0, 30_000, false, 20_000, false))                    // Zurueck meldet nichts
    }
}
