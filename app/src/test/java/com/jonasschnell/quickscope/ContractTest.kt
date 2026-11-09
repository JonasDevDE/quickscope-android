package com.jonasschnell.quickscope

import com.jonasschnell.quickscope.data.AppJson
import com.jonasschnell.quickscope.data.AttemptsResponse
import com.jonasschnell.quickscope.data.Audiobook
import com.jonasschnell.quickscope.data.Chapter
import com.jonasschnell.quickscope.data.Lesson
import com.jonasschnell.quickscope.logic.AudiobookQueue
import com.jonasschnell.quickscope.logic.forecastReliable
import com.jonasschnell.quickscope.logic.scoreOrNull
import com.jonasschnell.quickscope.logic.planLine
import com.jonasschnell.quickscope.logic.priorLabel
import com.jonasschnell.quickscope.logic.taskOf
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
        assertTrue(ready.hoursLeft in 400L..500L)
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
        assertEquals(70, far.scoreNowPercent)
        assertEquals(65, far.coveragePercent)
        assertEquals(95, far.scoreAtExamPercent)
        assertEquals(21, far.daysLeft)
        assertTrue(far.hoursLeft in 400L..500L)
        assertEquals(3, far.weakTopics.size)
        assertTrue(far.weakTopics[0].title.isNotBlank())
        assertTrue(far.weakTopics[0].mastery in 0.0..1.0)
        assertEquals(listOf(10, 20, 30, 45, 60), far.projections.map { it.minutes })
        assertEquals(95, far.projections[1].scoreAtExamPercent)
        assertEquals("medium", far.confidence)
        assertEquals("Schätzung, keine Garantie", far.note)

        val proj = parse("forecast-projections.json", Forecast.serializer())
        assertEquals(40, proj.scoreNowPercent)
        assertEquals(70, proj.scoreAtExamPercent)
        assertEquals(85, proj.projections.first { it.minutes == 10 }.scoreAtExamPercent)
        assertEquals(95, proj.projections.first { it.minutes == 20 }.scoreAtExamPercent)
        assertEquals("Bei 20 min täglich: ca. 70 % in der Prüfung, noch grob", com.jonasschnell.quickscope.logic.projectionLine(proj, 20))
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

    @Test fun streakDaysIsOptional() {
        val without = parse("forecast-far.json", Forecast.serializer())
        // Backend liefert streakDays (noch) optional: fehlt es, bleibt es null und die App blendet die Serie aus
        if (without.streakDays == null) {
            val with = AppJson.decodeFromString(Forecast.serializer(), """{"scoreNowPercent":50,"scoreAtExamPercent":70,"streakDays":7}""")
            assertEquals(7, with.streakDays)
        } else {
            assertTrue(without.streakDays!! >= 0)
        }
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
        assertEquals(85, s.plannedSeconds)
        assertEquals(8, s.quiz.size)
        assertTrue(s.reason.startsWith("Einstufung: 8 Fragen"))
        assertNull(s.finalRoundStart)
        assertNotNull(s.forecast)
        assertEquals(setOf("card", "cloze", "number", "match", "choice2", "mc", "truefalse"), s.quiz.map { it.type }.toSet())
        val tasks = s.quiz.associate { it.id to parseTask(it) }
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
        val card = s.quiz.first { it.id == "i-card" }
        assertEquals("Steigen mit Betrag, Laufzeit.", card.explanation)
        assertEquals("material", card.sourceType)
        assertEquals("m-pdf", card.source?.materialId)
        assertEquals(2, card.source?.page)
        assertEquals(8, card.estSeconds)
        assertTrue(card.ttsText!!.isNotBlank())
    }

    @Test fun crashSessionLessonFinalRoundAndVariants() {
        val s = parse("session-crash.json", LearnSession.serializer())
        assertEquals(7, s.quiz.size)
        assertEquals(4, s.finalRoundStart)
        assertTrue(s.showCheatsheetSuggestion)
        assertEquals(35.0, s.forecast!!.expectedGainPercentThisSession!!, 0.0001)
        // Abschlussrunde wie geliefert, ohne Deduplizierung: dieselbe Aufgabe darf zweimal vorkommen
        val crashTasks = s.quiz.associate { it.id to parseTask(it) }
        val order = crashTasks.getValue("i-order") as Task.Order
        assertEquals(listOf("Selbstkosten", "Gewinnzuschlag", "Barverkaufspreis", "Listenpreis"), order.steps)
        val spot = crashTasks.getValue("i-spot") as Task.Spot
        assertEquals(0, spot.falseIndex)
        assertEquals(3, spot.statements.size)
        val lesson = s.quiz.first { it.lesson }
        val task = parseTask(lesson) as Task.Lesson
        assertEquals(3, task.steps.size)
        assertEquals("Faktor 1,0x merken", task.trick)
        assertTrue(lesson.fromExam)
        assertEquals("p-1", lesson.patternId)
        val variant = s.quiz.first { it.id == "i-variant" }
        assertEquals("i-aisol", variant.variantOf)
        assertFalse(variant.aiSolution)
    }

    @Test fun dailySessionAndOver() {
        val d = parse("session-daily.json", LearnSession.serializer())
        assertEquals("daily", d.kind)
        assertEquals(12, d.quiz.size)
        assertNull(d.finalRoundStart)
        assertFalse(d.showCheatsheetSuggestion)
        val over = parse("session-over.json", LearnSession.serializer())
        assertEquals("over", over.kind)
        assertTrue(over.quiz.isEmpty())
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
        assertTrue(msg("error-400-validation.json", 400)!!.contains(":"))
        assertNotNull(msg("error-400-time.json", 400))
        assertNotNull(msg("error-400-attempt-outcome.json", 400))
        assertNotNull(msg("error-400-broken-pdf.json", 400))
        assertEquals("Gerade nicht verfügbar.", msg("error-503-audio-limit.json", 503))
        assertEquals("Gerade nicht verfügbar.", msg("error-503-audio-not-configured.json", 503))
        assertEquals("Gerade nicht verfügbar.", msg("error-503-timings-not-configured.json", 503))
        assertNotNull(msg("error-503-prefetch-not-configured.json", 503))
        assertEquals("Noch keine Themen", msg("error-409-regenerate-no-topics.json", 409))
        assertEquals("part: question oder answer", msg("error-400-audio-part.json", 400))
        assertEquals("part: question oder answer", msg("error-400-timings-part.json", 400))
        assertEquals("Aufgabe nicht gefunden", msg("error-404-audio-item.json", 404))
        assertEquals("Aufgabe nicht gefunden", msg("error-404-timings-item.json", 404))
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

    @Test fun lessonsChaptersAndAudiobook() {
        val chapters = parse("chapters.json", ListSerializer(Chapter.serializer()))
        assertEquals(2, chapters.size)
        assertEquals("Rechnen", chapters[0].title)
        assertEquals(1, chapters[0].order)
        assertEquals(2, chapters[0].lessonCount)
        assertEquals(2, chapters[0].minutes)
        assertEquals("learning", chapters[0].status)
        assertEquals(0.22, chapters[0].mastery, 0.0001)
        assertEquals("new", chapters[1].status)
        assertEquals(2, chapters[1].order)

        val lessons = parse("lessons.json", ListSerializer(Lesson.serializer()))
        assertEquals(3, lessons.size)
        val first = lessons[0]
        assertEquals("Zinsen verstehen", first.title)
        assertEquals(4, first.keyPoints.size)
        assertEquals("Zins = Preis für Geld", first.keyPoints[0])
        assertTrue(first.ttsText.startsWith("Zinsen sind der Preis für geliehenes Geld."))
        assertEquals("Zins = Miete für Geld", first.mnemonic)
        assertEquals(48, first.estSeconds)
        assertTrue(first.heard)
        assertEquals("material", first.sourceType)
        assertEquals("m-pdf", first.source?.materialId)
        assertFalse(lessons[1].heard)
        assertNull(lessons[1].heardAt)
        assertEquals(2, parse("lessons-chapter.json", ListSerializer(Lesson.serializer())).size)

        val book = parse("audiobook.json", Audiobook.serializer())
        assertEquals(2, book.items.size)
        assertEquals(83, book.totalSeconds)
        assertEquals("Preis kalkulieren", book.items[0].title)
        assertEquals("Rechnen", book.items[0].chapterTitle)
        assertEquals(42, book.items[0].estSeconds)
        assertEquals("Zuhören: 2 Lektionen, 1 Min.", book.reason)
        // die Warteschlange folgt der Reihenfolge, haelt Grenzen und meldet die naechsten Lektionen fuer den Prefetch
        val q = AudiobookQueue(book.items)
        assertEquals(book.items[0].lessonId, q.current?.lessonId)
        assertEquals(2, q.ahead().size)
        assertTrue(q.next())
        assertEquals(1, q.ahead().size)
        assertTrue(q.next())
        assertTrue(q.finished)
        assertFalse(q.next())
        assertTrue(q.previous())
        assertEquals(41, q.remainingSeconds())
        assertTrue(parse("audiobook-repeat.json", Audiobook.serializer()).items.isNotEmpty())
        val t = parse("lesson-timings.json", AudioTimings.serializer())
        assertTrue(t.words.isNotEmpty())
        assertTrue(timingsMatch(t.text, t))
    }

    @Test fun sessionWithLessonElementsKeepsLessonAndQuestionApart() {
        val s = parse("session-lesson.json", LearnSession.serializer())
        assertEquals(14, s.items.size)
        // type "lesson" ist eine Lektion, alles andere eine Aufgabe (auch wenn Item.lesson true ist)
        assertNotNull(s.items[0].lesson)
        assertNull(s.items[0].item)
        assertEquals("Zinsen verstehen", s.items[0].lesson!!.title)
        assertEquals(4, s.items[0].lesson!!.keyPoints.size)
        assertEquals(48, s.items[0].lesson!!.estSeconds)
        assertEquals("Zins = Miete für Geld", s.items[0].lesson!!.mnemonic)
        assertEquals(3, s.items.count { it.lesson != null })
        assertEquals(11, s.quiz.size)
        val tasks = s.items.mapNotNull { taskOf(it) }
        assertEquals(14, tasks.size)
        assertTrue(tasks[0] is Task.Teach)
        assertTrue(tasks[1] is Task.Card)
        assertTrue(tasks[5] is Task.Teach)
        assertEquals("lesson-${s.items[0].lesson!!.id}", tasks[0].item.id)
        assertEquals(48, tasks[0].item.estSeconds)
        assertEquals(3, tasks.count { it is Task.Teach })
    }

    @Test fun dontKnowResponseCarriesNextLesson() {
        val r = parse("attempts-dontknow.json", AttemptsResponse.serializer())
        assertTrue(r.nextLessonId!!.isNotBlank())
        assertEquals(55, r.forecast.scoreNowPercent)
        assertEquals(2, r.forecast.streakDays)
        assertEquals(3, r.forecast.bestStreakDays)
        assertEquals(listOf("Rechnen", "Vertragsrecht"), r.forecast.plan?.todayChapters)
        assertEquals(2, r.forecast.plan?.chaptersTotal)
        assertEquals(0, r.forecast.plan?.chaptersHeard)
        assertEquals("Heute: Rechnen und Vertragsrecht", planLine(r.forecast.plan))
        assertNull(parse("attempts.json", AttemptsResponse.serializer()).nextLessonId)
    }

    @Test fun priorKnowledgeOnExams() {
        val exams = exams()
        assertTrue(exams.all { it.priorKnowledge == "some" })
        assertEquals("Nichts", priorLabel("none"))
        assertEquals("Etwas", priorLabel("some"))
        assertEquals("Viel", priorLabel("much"))
        assertEquals("Etwas", priorLabel("unbekannt"))
    }

    @Test fun lessonErrorMessagesAndEmptyHeardResponse() {
        fun msg(file: String, code: Int): String? {
            val body = raw(file).toResponseBody("application/json".toMediaType())
            return backendMessage(HttpException(Response.error<Any>(code, body)))
        }
        assertEquals("fraction: 0 bis 1", msg("error-400-heard.json", 400))
        assertEquals("Lektion nicht gefunden", msg("error-404-lesson.json", 404))
        assertNotNull(msg("error-404-lesson-audio.json", 404))
        // 204 ohne Body: die Datei ist leer
        assertEquals("", raw("lesson-heard.json").trim())
    }

    @Test fun forecastOnlyShownWhenReliable() {
        val far = parse("forecast-none.json", Forecast.serializer())      // nichts beantwortet: keine Zahlen, confidence none
        val ready = exams().first { it.id == "e-ready" }
        assertFalse(forecastReliable(ready, far))
        val answered = parse("forecast-confidence-low.json", Forecast.serializer())
        assertTrue(forecastReliable(ready, answered))
        assertFalse(forecastReliable(ready.copy(status = "processing"), answered))
        assertFalse(forecastReliable(null, answered))
        assertFalse(forecastReliable(ready, null))
    }

    @Test fun forecastWithoutNumbersIsParsedAndShowsNoScore() {
        val none = parse("forecast-none.json", Forecast.serializer())
        assertEquals("none", none.confidence)
        assertEquals(0, none.coveragePercent)
        assertTrue(none.projections.isEmpty())
        assertTrue(none.milestones.isEmpty())
        assertNull(none.expectedGainPercentThisSession)
        assertEquals(3, none.streakDays)
        assertEquals(3, none.weakTopics.size)
        assertEquals(2, none.plan?.chaptersTotal)
        assertNull(none.scoreOrNull())
        val ready = exams().first { it.id == "e-ready" }
        assertFalse(forecastReliable(ready, none))
        // mit Zahlen: Stand wird gezeigt
        val far = parse("forecast-far.json", Forecast.serializer())
        assertNotNull(far.scoreOrNull())
        // fehlende Felder auch im Antwortobjekt der Session und der Antworten
        val s = AppJson.decodeFromString(LearnSession.serializer(), """{"items":[],"plannedSeconds":0,"kind":"daily","reason":"x","forecast":{"confidence":"none","coveragePercent":0}}""")
        assertNull(s.forecast!!.scoreOrNull())
        assertEquals("none", s.forecast!!.confidence)
    }

    @Test fun profileContract() {
        val done = parse("profile-get-done.json", com.jonasschnell.quickscope.data.Profile.serializer())
        assertEquals("Ich bin Snowboarder, arbeite im Marketing und interessiere mich fuer Autos.", done.about)
        assertEquals(17, done.age)
        assertEquals("realschule", done.educationLevel)
        assertTrue(done.onboardingDone)
        assertEquals(com.jonasschnell.quickscope.logic.OnboardingGate.Done, com.jonasschnell.quickscope.logic.gateFor(done))
        assertTrue(com.jonasschnell.quickscope.logic.aboutValid(done.about))
        // frisches Profil: age und educationLevel fehlen, onboardingDone false -> Onboarding laeuft
        val fresh = parse("profile-get-new.json", com.jonasschnell.quickscope.data.Profile.serializer())
        assertEquals("", fresh.about)
        assertNull(fresh.age)
        assertNull(fresh.educationLevel)
        assertFalse(fresh.onboardingDone)
        assertEquals(com.jonasschnell.quickscope.logic.OnboardingGate.Needed, com.jonasschnell.quickscope.logic.gateFor(fresh))
        assertEquals(done, parse("profile-put-done.json", com.jonasschnell.quickscope.data.Profile.serializer()))
        val partial = parse("profile-put-partial.json", com.jonasschnell.quickscope.data.Profile.serializer())
        assertEquals(17, partial.age)
        assertNull(partial.educationLevel)
        assertFalse(partial.onboardingDone)
        // Sprachnachricht: nur die Zusammenfassung
        val v = parse("profile-voice-response.json", com.jonasschnell.quickscope.data.VoiceSummary.serializer())
        assertEquals("Ich bin Snowboarder und arbeite im Marketing. Ich interessiere mich fuer Autos.", v.about)
        assertTrue(com.jonasschnell.quickscope.logic.aboutValid(v.about))
        // Einstellungen senden nie onboardingDone (nur das Onboarding beim Abschluss)
        val settings = AppJson.encodeToString(com.jonasschnell.quickscope.data.ProfileUpdate.serializer(), com.jonasschnell.quickscope.data.ProfileUpdate(about = "x", age = 20, educationLevel = "abitur"))
        assertFalse(settings.contains("onboardingDone"))
        assertEquals("""{"about":"x","age":20,"educationLevel":"abitur"}""", settings)
        val finish = AppJson.encodeToString(com.jonasschnell.quickscope.data.ProfileUpdate.serializer(), com.jonasschnell.quickscope.data.ProfileUpdate(age = 20, onboardingDone = true))
        assertEquals("""{"age":20,"onboardingDone":true}""", finish)
    }

    @Test fun profileErrorsAndVoiceOutcomes() {
        fun msg(file: String, code: Int): String? {
            val body = raw(file).toResponseBody("application/json".toMediaType())
            return backendMessage(HttpException(Response.error<Any>(code, body)))
        }
        assertEquals("age: 10 bis 99", msg("error-400-profile-age.json", 400))
        assertEquals("onboardingDone: nur von false auf true", msg("error-400-profile-onboarding.json", 400))
        assertNotNull(msg("error-400-profile-about.json", 400))
        assertNotNull(msg("error-400-profile-education.json", 400))
        assertTrue(msg("error-400-profile-voice-format.json", 400)!!.startsWith("file:"))
        assertEquals("Gerade nicht verfügbar.", msg("error-503-profile-voice.json", 503)) // keine KI-Hinweise in der App
        val o = com.jonasschnell.quickscope.logic.voiceOutcomeForStatus(422)
        assertEquals(com.jonasschnell.quickscope.logic.VoiceOutcome.NothingHeard, o)
        assertEquals("Nichts verstanden. Nochmal aufnehmen?", com.jonasschnell.quickscope.logic.voiceOutcomeMessage(o))
        assertEquals(com.jonasschnell.quickscope.logic.VoiceOutcome.Unavailable, com.jonasschnell.quickscope.logic.voiceOutcomeForStatus(503))
        assertEquals("Gerade nicht verfügbar. Du kannst auch tippen.", com.jonasschnell.quickscope.logic.voiceOutcomeMessage(com.jonasschnell.quickscope.logic.VoiceOutcome.Unavailable))
        assertNull(com.jonasschnell.quickscope.logic.voiceOutcomeMessage(com.jonasschnell.quickscope.logic.VoiceOutcome.Text("x")))
        assertEquals("aac", com.jonasschnell.quickscope.logic.VOICE_FILE_EXT)
        assertEquals("audio/aac", com.jonasschnell.quickscope.logic.VOICE_MIME)
    }

    @Test fun questionsOnlySessionBudgetPlanAndPrefetch() {
        val s = parse("session-questions-only.json", LearnSession.serializer())
        assertEquals(12, s.items.size)
        assertEquals(12, s.quiz.size)
        assertTrue(s.items.none { it.lesson != null })      // lessons=false: keine Lektionen
        assertEquals("Heute: 12 neue Fragen, 3 Themen.", s.reason)
        assertEquals(149, s.plannedSeconds)
        assertEquals(30, s.budget?.dailyMinutes)
        assertEquals(0, s.budget?.usedMinutesToday)
        assertEquals(30, s.budget?.remainingMinutes)
        // das Filtern bleibt als Rueckfall: gleiche Eintraege ohne Lektionen
        val mixed = parse("session-lesson.json", LearnSession.serializer())
        assertNotNull(mixed.budget)
        val filtered = com.jonasschnell.quickscope.logic.filterForMode(mixed.items, { it.lesson != null }, com.jonasschnell.quickscope.logic.StartMode.QuestionsOnly)
        assertEquals(mixed.quiz.size, filtered.size)
        val plan = parse("forecast-far.json", Forecast.serializer()).plan!!
        assertEquals(5, plan.plannedMinutes)
        assertEquals(listOf("Rechnen", "Vertragsrecht"), plan.todayChapters)
        val book = parse("audiobook-default.json", Audiobook.serializer())
        assertEquals(2, book.items.size)
        assertEquals(83, book.totalSeconds)
        assertNotNull(book.budget)
        val pre = parse("audio-prefetch.json", com.jonasschnell.quickscope.data.PrefetchResponse.serializer())
        assertEquals(2, pre.queued)
        assertEquals(2, pre.ready)
        assertEquals(0, pre.pending)
        assertEquals(0, pre.failed)
        assertEquals(listOf("i-number:question", "i-number:answer"), pre.items.map { it.id })
        assertTrue(pre.items.all { it.status == "ready" })
        // pending ist kein Fehler
        val p = AppJson.decodeFromString(com.jonasschnell.quickscope.data.PrefetchResponse.serializer(), """{"queued":2,"ready":1,"pending":1,"failed":0,"items":[{"id":"a","status":"ready"},{"id":"b","status":"pending"}]}""")
        assertEquals(1, p.pending)
        assertEquals(0, p.failed)
    }

    @Test fun dayGoalAndBudgetLine() {
        assertTrue(com.jonasschnell.quickscope.logic.dayGoalReached(20, 20 * 60))
        assertTrue(com.jonasschnell.quickscope.logic.dayGoalReached(20, 20 * 60 - 44))
        assertFalse(com.jonasschnell.quickscope.logic.dayGoalReached(20, 20 * 60 - 45))
        assertFalse(com.jonasschnell.quickscope.logic.dayGoalReached(20, 0))
        assertEquals("Heute: 12 von 30 min", com.jonasschnell.quickscope.logic.budgetLine(30, 12 * 60 + 59))
        assertEquals(10, com.jonasschnell.quickscope.logic.EXTRA_MINUTES)
    }
}
