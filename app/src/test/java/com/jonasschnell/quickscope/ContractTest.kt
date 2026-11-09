package com.jonasschnell.quickscope

import com.jonasschnell.quickscope.data.AppJson
import com.jonasschnell.quickscope.data.AttemptsResponse
import com.jonasschnell.quickscope.data.AudioTimings
import com.jonasschnell.quickscope.data.WordTiming
import com.jonasschnell.quickscope.logic.WordPos
import com.jonasschnell.quickscope.logic.timingsMatch
import com.jonasschnell.quickscope.logic.wordPosAt
import com.jonasschnell.quickscope.data.CheatSheet
import com.jonasschnell.quickscope.data.Exam
import com.jonasschnell.quickscope.data.Forecast
import com.jonasschnell.quickscope.data.LearnSession
import com.jonasschnell.quickscope.data.Material
import com.jonasschnell.quickscope.data.Me
import com.jonasschnell.quickscope.data.Topic
import com.jonasschnell.quickscope.data.backendMessage
import com.jonasschnell.quickscope.logic.Task
import com.jonasschnell.quickscope.logic.parseTask
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/** Vertragstests: echte Antworten des Backends (src/test/resources/contract) muessen in die Modelle passen, mit Werten. */
class ContractTest {
    private fun raw(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("contract/$name")!!.readBytes().toString(Charsets.UTF_8)

    private fun <T> parse(name: String, s: kotlinx.serialization.KSerializer<T>): T = AppJson.decodeFromString(s, raw(name))

    private fun exams() = parse("exams.json", ListSerializer(Exam.serializer()))

    @Test fun me() {
        val me = parse("me.json", Me.serializer())
        assertEquals("u1", me.id)
        assertEquals("Lena Beispiel", me.name)
        assertEquals("https://example.org/lena.png", me.pictureUrl)
    }

    @Test fun examsInAllStates() {
        val list = exams().associateBy { it.id }
        assertEquals(listOf("over", "failed", "processing", "ready", "empty"), exams().map { it.status })
        val ready = list.getValue("e-ready")
        assertEquals("IHK Fachinformatiker AP2", ready.name)
        assertEquals("2026-11-30", ready.date)
        assertEquals("08:00", ready.time)
        assertEquals("2026-11-30T07:00:00Z", ready.startsAt)
        assertEquals(494L, ready.hoursLeft)
        assertEquals(30, ready.dailyMinutes)
        assertEquals(13L, ready.itemCount)
        assertEquals(5L, ready.materialCount)
        assertEquals(21, ready.daysLeft)
        assertTrue(ready.description!!.startsWith("IHK Fachinformatiker AP2, Wirtschaft"))
        assertEquals("Im Material wurden keine Themen gefunden", list.getValue("e-failed").error)
        assertNull(list.getValue("e-ready").error)
    }

    @Test fun singleAndCreatedExam() {
        val exam = parse("exam.json", Exam.serializer())
        assertEquals("e-ready", exam.id)
        assertEquals("ready", exam.status)
        val created = parse("exam-created.json", Exam.serializer())
        assertEquals("e-new", created.id)
        assertEquals("processing", created.status)
        assertEquals("09:00", created.time)
        assertEquals("Abitur Biologie, Schwerpunkt Genetik", created.description)
        assertEquals(30, created.dailyMinutes)
    }

    @Test fun materials() {
        val list = parse("materials.json", ListSerializer(Material.serializer())).associateBy { it.id }
        assertEquals(5, list.size)
        val scan = list.getValue("m-scan")
        assertEquals("scan", scan.kind)
        assertEquals("exam", scan.role)
        assertEquals(3, scan.pages)
        assertEquals("Seite 3 schwer lesbar", scan.warning)
        assertEquals(5_100_000L, scan.sizeBytes)
        assertEquals("script", list.getValue("m-pdf").role)
        assertEquals("failed", list.getValue("m-image").status)
        assertEquals("KI nicht eingerichtet", list.getValue("m-image").error)
        assertEquals("processing", list.getValue("m-audio").status)
        assertEquals("audio", list.getValue("m-audio").kind)
        val created = parse("material-created.json", Material.serializer())
        assertEquals("m-new", created.id)
        assertEquals("queued", created.status)
        assertEquals("exam", created.role)
    }

    @Test fun topics() {
        val list = parse("topics.json", ListSerializer(Topic.serializer()))
        val leaf = list.first { it.title == "Zinsrechnung" }
        assertEquals(0.4, leaf.weight, 0.0001)
        assertEquals(1, leaf.level)
        assertEquals(0.58, leaf.mastery, 0.0001)
        assertNotNull(leaf.parentId)
        val group = list.first { it.title == "Rechnen" }
        assertEquals(0, group.level)
        assertNull(group.parentId)
        val labels = com.jonasschnell.quickscope.logic.topicLabels(list)
        assertEquals("Rechnen › Zinsrechnung", labels[leaf.id])
        assertEquals("Rechnen", labels[group.id])
    }

    @Test fun forecasts() {
        val far = parse("forecast-far.json", Forecast.serializer())
        assertEquals(30, far.scoreNowPercent)
        assertEquals(95, far.scoreAtExamPercent)
        assertEquals(21, far.daysLeft)
        assertEquals(494L, far.hoursLeft)
        assertEquals(3, far.weakTopics.size)
        assertEquals("Zinsrechnung", far.weakTopics[0].title)
        assertEquals(0.1, far.weakTopics[0].mastery, 0.0001)
        assertEquals(listOf(10, 20, 30, 45, 60), far.projections.map { it.minutes })
        assertEquals(95, far.projections[1].scoreAtExamPercent)
        assertEquals("low", far.confidence)
        assertEquals("Schätzung, keine Garantie", far.note)

        val proj = parse("forecast-projections.json", Forecast.serializer())
        assertEquals(20, proj.scoreNowPercent)
        assertEquals(65, proj.scoreAtExamPercent)
        assertEquals(85, proj.projections.first { it.minutes == 10 }.scoreAtExamPercent)
        assertEquals(95, proj.projections.first { it.minutes == 20 }.scoreAtExamPercent)
        assertEquals("Bei 20 min täglich: ca. 65 % in der Prüfung, noch grob", com.jonasschnell.quickscope.logic.projectionLine(proj, 20))
        assertEquals("10 min · ca. 85 %", com.jonasschnell.quickscope.logic.minutesChoiceLabel(proj, 10))

        val low = parse("forecast-confidence-low.json", Forecast.serializer())
        assertEquals("low", low.confidence)
        assertEquals(55, low.scoreNowPercent)
        assertEquals(45, low.coveragePercent)
        assertEquals("medium", parse("forecast-confidence-medium.json", Forecast.serializer()).confidence)
        val high = parse("forecast-confidence-high.json", Forecast.serializer())
        assertEquals("high", high.confidence)
        assertEquals(95, high.scoreNowPercent)
        assertEquals(100, high.coveragePercent)

        val over = parse("forecast-over.json", Forecast.serializer())
        assertEquals(75, over.scoreNowPercent)
        assertEquals(0, over.daysLeft)
        assertTrue(over.projections.isEmpty())
    }

    @Test fun attemptsResponse() {
        val r = parse("attempts.json", AttemptsResponse.serializer())
        assertEquals(55, r.forecast.scoreNowPercent)
        assertEquals(95, r.forecast.scoreAtExamPercent)
        assertEquals(0, r.doneSecondsToday)
        assertEquals(45, r.forecast.coveragePercent)
        assertEquals(5, r.forecast.projections.size)
    }

    @Test fun audioTimingsMatchTheQuestionAndDriveTheHighlight() {
        val q = parse("timings-question.json", AudioTimings.serializer())
        assertEquals("Zins: 1.000 € bei 3 %, 1 Jahr?", q.text)
        assertEquals(2200L, q.durationMs)
        assertEquals(8, q.words.size)
        assertEquals(WordTiming(0, 5, 200, 438), q.words[0])
        assertEquals("Zins:", q.text.substring(q.words[0].start, q.words[0].end))
        assertEquals("Jahr?", q.text.substring(q.words.last().start, q.words.last().end))
        assertTrue(timingsMatch("Zins: 1.000 € bei 3 %, 1 Jahr?", q))
        assertFalse(timingsMatch("Zins: 1.000 € bei 3 %, 1 Jahr", q))
        assertEquals(WordPos(0, -1), wordPosAt(q.words, 100))
        assertEquals(WordPos(1, 1), wordPosAt(q.words, 500))
        assertEquals(WordPos(8, -1), wordPosAt(q.words, 2200))
        val a = parse("timings-answer.json", AudioTimings.serializer())
        assertEquals("Richtig: 30 €.", a.text)
        assertEquals(3, a.words.size)
        assertTrue(timingsMatch(a.text, a))
    }

    @Test fun regenerateAndPrefetchResponses() {
        val e = parse("regenerate.json", Exam.serializer())
        assertEquals("e-ready", e.id)
        assertEquals("processing", e.status)
        assertEquals(13L, e.itemCount)
        val queued = Json.parseToJsonElement(raw("audio-prefetch.json")).jsonObject["queued"]!!.jsonPrimitive.content
        assertEquals("2", queued)
    }

    @Test fun cheatsheet() {
        val c = parse("cheatsheet.json", CheatSheet.serializer())
        assertEquals(5, c.minutes)
        assertEquals(70, c.words)
        assertEquals(10, c.bullets.size)
        assertEquals("Zinsrechnung", c.bullets[0].topic)
        assertTrue(c.bullets[0].text.startsWith("Zinseszins berechnen"))
        assertTrue(c.note.startsWith("Nur aus deinem Material"))
    }

    @Test fun diagnosticSessionAllTypes() {
        val s = parse("session-diagnostic.json", LearnSession.serializer())
        assertEquals("diagnostic", s.kind)
        assertEquals(129, s.plannedSeconds)
        assertEquals(11, s.items.size)
        assertTrue(s.reason.startsWith("Einstufung: 11 Fragen"))
        assertNull(s.finalRoundStart)
        assertNotNull(s.forecast)
        assertEquals(setOf("card", "cloze", "number", "match", "choice2", "mc", "order", "truefalse", "spot"), s.items.map { it.type }.toSet())
        val tasks = s.items.associate { it.id to parseTask(it) }
        // jeder Typ wird als eigene Aufgabe erkannt, nie nur als Rueckfall-Karte
        assertTrue(tasks.getValue("i-card") is Task.Card)
        val cloze = tasks.getValue("i-cloze") as Task.Cloze
        assertEquals("Gewinn", cloze.answer)
        assertEquals(listOf("Gewinn", "Skonto", "Rabatt"), cloze.bank)
        val num = tasks.getValue("i-aisol") as Task.Choice
        assertEquals(4, num.options.size)
        assertEquals("1.081,60 €", num.options[num.correct])
        assertTrue(num.item.aiSolution)
        assertEquals(3, num.item.solutionSteps!!.size)
        assertEquals("Faktor zweimal", num.item.trick)
        val c2 = tasks.getValue("i-choice2") as Task.Choice
        assertEquals(listOf("Verkäufer", "Käufer"), c2.options)
        assertEquals(1, c2.correct)
        val mc = tasks.getValue("i-mc") as Task.Choice
        assertEquals(0, mc.correct)
        val tf = tasks.getValue("i-tf") as Task.Choice
        assertEquals(0, tf.correct)
        val match = tasks.getValue("i-match") as Task.Match
        assertEquals(3, match.pairs.size)
        assertEquals("Selbstkosten" to "Herstellkosten + Verwaltung", match.pairs[0])
        val order = tasks.getValue("i-order") as Task.Order
        assertEquals(listOf("Selbstkosten", "Gewinnzuschlag", "Barverkaufspreis", "Listenpreis"), order.steps)
        val spot = tasks.getValue("i-spot") as Task.Spot
        assertEquals(0, spot.falseIndex)
        assertEquals(3, spot.statements.size)
        val card = s.items.first { it.id == "i-card" }
        assertEquals("Steigen mit Betrag, Laufzeit.", card.explanation)
        assertEquals("material", card.sourceType)
        assertEquals("m-pdf", card.source?.materialId)
        assertEquals(2, card.source?.page)
        assertEquals(8, card.estSeconds)
        assertTrue(card.ttsText!!.isNotBlank())
    }

    @Test fun crashSessionLessonFinalRoundAndVariants() {
        val s = parse("session-crash.json", LearnSession.serializer())
        assertEquals(19, s.items.size)
        assertEquals(13, s.finalRoundStart)
        assertTrue(s.showCheatsheetSuggestion)
        assertEquals(65.0, s.forecast!!.expectedGainPercentThisSession!!, 0.0001)
        // Abschlussrunde wie geliefert, ohne Deduplizierung: dieselbe Aufgabe darf zweimal vorkommen
        assertEquals(2, s.items.count { it.id == "i-tf" })
        val lesson = s.items.first { it.lesson }
        val task = parseTask(lesson) as Task.Lesson
        assertEquals(3, task.steps.size)
        assertEquals("Faktor 1,0x merken", task.trick)
        assertTrue(lesson.fromExam)
        assertEquals("p-1", lesson.patternId)
        val variant = s.items.first { it.id == "i-variant" }
        assertEquals("i-aisol", variant.variantOf)
        assertFalse(variant.aiSolution)
    }

    @Test fun dailySessionAndOver() {
        val d = parse("session-daily.json", LearnSession.serializer())
        assertEquals("daily", d.kind)
        assertEquals(12, d.items.size)
        assertNull(d.finalRoundStart)
        assertFalse(d.showCheatsheetSuggestion)
        val over = parse("session-over.json", LearnSession.serializer())
        assertEquals("over", over.kind)
        assertTrue(over.items.isEmpty())
        assertEquals("Die Prüfung ist vorbei.", over.reason)
        assertEquals(75, over.forecast!!.scoreNowPercent)
    }

    @Test fun errorMessagesAreReadable() {
        fun msg(file: String, code: Int): String? {
            val body = raw(file).toResponseBody("application/json".toMediaType())
            return backendMessage(HttpException(Response.error<Any>(code, body)))
        }
        assertEquals("Die Datei ist größer als 30 MB", msg("error-413-file-too-large.json", 413))
        assertEquals("Dieses Format kann ich noch nicht lesen: .xyz", msg("error-415-format.json", 415))
        assertTrue(msg("error-415-legacy-format.json", 415)!!.contains(".docx"))
        assertEquals("Die Prüfung ist vorbei", msg("error-409-attempt-over.json", 409))
        assertEquals("Höchstens 20 Prüfungen je Nutzer", msg("error-409-exam-limit.json", 409))
        assertTrue(msg("error-400-validation.json", 400)!!.startsWith("dailyMinutes"))
        assertNotNull(msg("error-400-time.json", 400))
        assertNotNull(msg("error-400-attempt-outcome.json", 400))
        assertNotNull(msg("error-400-broken-pdf.json", 400))
        assertEquals("Sprach-Limit erreicht", msg("error-503-audio-limit.json", 503))
        assertEquals("KI nicht eingerichtet", msg("error-503-audio-not-configured.json", 503))
        assertEquals("KI nicht eingerichtet", msg("error-503-timings-not-configured.json", 503))
        assertNotNull(msg("error-503-prefetch-not-configured.json", 503))
        assertEquals("Noch keine Themen", msg("error-409-regenerate-no-topics.json", 409))
        assertEquals("part: question oder answer", msg("error-400-audio-part.json", 400))
        assertEquals("part: question oder answer", msg("error-400-timings-part.json", 400))
        assertNotNull(msg("error-404-exam.json", 404))
        assertNotNull(msg("error-404-material.json", 404))
        assertEquals("Bitte aktualisiere die App.", msg("error-426-upgrade.json", 426))
    }

    @Test fun patternsFileIsValidJson() {
        val arr = Json.parseToJsonElement(raw("patterns.json"))
        val first = (arr as kotlinx.serialization.json.JsonArray)[0].jsonObject
        assertEquals("p-1", first["id"]!!.jsonPrimitive.content)
        assertEquals("4", first["occurrences"]!!.jsonPrimitive.content)
    }
}
