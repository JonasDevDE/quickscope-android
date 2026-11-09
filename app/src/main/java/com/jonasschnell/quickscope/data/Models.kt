package com.jonasschnell.quickscope.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
data class Me(
    val id: String,
    val email: String? = null,
    val name: String? = null,
    val pictureUrl: String? = null,
)

@Serializable
data class SessionResponse(val token: String)

@Serializable
data class AppRelease(val versionCode: Int, val versionName: String, val notes: String? = null, val size: Long = 0, val sha256: String = "")

/** Pruefung. status: empty, processing, ready, failed oder over (Beginn vorbei). */
@Serializable
data class Exam(
    val id: String,
    val name: String = "",
    val date: String = "",
    val time: String = "08:00",
    val startsAt: String = "",
    val hoursLeft: Long = 0,
    val description: String? = null,
    val dailyMinutes: Int = 20,
    /** Vorwissen: none, some, much (steuert Einstufung und Lektionen). */
    val priorKnowledge: String = "some",
    val status: String = "empty",
    val mode: String? = null,
    val topicCount: Long = 0,
    val itemCount: Long = 0,
    val materialCount: Long = 0,
    val createdAt: String? = null,
    val daysLeft: Int = 0,
    /** Fehlertext, wenn status = failed (z. B. "KI nicht eingerichtet", "KI-Limit erreicht, später wieder"). */
    val error: String? = null,
    /** "exam" (mit Termin) oder "open" (dauerhaft lernen, ohne date/time/startsAt/daysLeft). */
    val kind: String = "exam",
    /** Fortschritt der Verarbeitung (optional). */
    val stage: String? = null,
    val progressPercent: Int? = null,
    /** Icon-Schluessel aus der festen Liste (von der KI gewaehlt oder vom Nutzer gesetzt); fehlt/unbekannt: graduation. */
    val icon: String? = null,
)

@Serializable
data class Material(
    val id: String,
    val examId: String = "",
    val kind: String = "",
    val role: String = "script",
    val name: String = "",
    val sizeBytes: Long = 0,
    val status: String = "queued",
    val error: String? = null,
    val warning: String? = null,
    val pages: Int? = null,
    val createdAt: String? = null,
)

@Serializable
data class Source(val materialId: String? = null, val page: Int? = null)

/** Lernaufgabe; typabhaengige Daten stehen in [payload] (bei mc/choice2/number zusaetzlich options/correctIndex). */
@Serializable
data class Item(
    val id: String = "",
    val examId: String = "",
    val topicId: String = "",
    val type: String = "card",
    val question: String = "",
    val answer: String = "",
    val options: List<String>? = null,
    val correctIndex: Int? = null,
    val payload: JsonObject? = null,
    val explanation: String? = null,
    val mnemonic: String? = null,
    val solutionSteps: List<String>? = null,
    val trick: String? = null,
    val difficulty: Int = 3,
    val estSeconds: Int = 15,
    val source: Source? = null,
    val sourceType: String? = null,
    val fromExam: Boolean = false,
    val aiSolution: Boolean = false,
    val patternId: String? = null,
    val variantOf: String? = null,
    val lesson: Boolean = false,
    val ttsText: String? = null,
)

@Serializable
data class WeakTopic(val topicId: String = "", val title: String = "", val mastery: Double = 0.0)

@Serializable
data class Plan(val todayChapters: List<String> = emptyList(), val chaptersTotal: Int = 0, val chaptersHeard: Int = 0, val plannedMinutes: Int? = null)

@Serializable
data class Projection(val minutes: Int = 0, val scoreAtExamPercent: Int = 0)

@Serializable
data class Milestone(val date: String = "", val coveragePercent: Int = 0, val scorePercent: Int = 0)

@Serializable
data class Forecast(
    val scoreNowPercent: Int = 0,
    val scoreAtExamPercent: Int = 0,
    val daysLeft: Int = 0,
    val hoursLeft: Long = 0,
    val doneSecondsToday: Int = 0,
    val weakTopics: List<WeakTopic> = emptyList(),
    val confidence: String = "low",
    val mode: String? = null,
    val coveragePercent: Int = 0,
    /** Voraussichtlicher Stand zur Pruefung je Lernzeit pro Tag (10, 20, 30, 45, 60 min). */
    val projections: List<Projection> = emptyList(),
    val expectedGainPercentThisSession: Double? = null,
    val milestones: List<Milestone> = emptyList(),
    val showCheatsheetSuggestion: Boolean = false,
    /** Tage in Folge mit mindestens einer abgeschlossenen Runde (Backend, optional). */
    val streakDays: Int? = null,
    val bestStreakDays: Int? = null,
    /** Lernplan-Kurzinfo fuer die Heute-Ansicht. */
    val plan: Plan? = null,
    val note: String = "Schätzung, keine Garantie",
    /** Nur bei offenem Lernen: Wissensstand (5er-Schritte abgerundet); fehlt bis zur ersten Antwort. */
    val knowledgePercent: Int? = null,
)

/** Lektion als Element der Session (`type:"lesson"`); nicht zu verwechseln mit [Item.lesson] (Loesungsweg-Karte). */
@Serializable
data class SessionLesson(
    val id: String,
    val title: String = "",
    val keyPoints: List<String> = emptyList(),
    val ttsText: String = "",
    val mnemonic: String? = null,
    val estSeconds: Int = 60,
)

/** Ein Element der Session: entweder eine Aufgabe oder eine Lektion (streng ueber `type == "lesson"` unterschieden). */
@Serializable(with = SessionEntrySerializer::class)
data class SessionEntry(val item: Item? = null, val lesson: SessionLesson? = null)

object SessionEntrySerializer : KSerializer<SessionEntry> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

    override fun deserialize(decoder: Decoder): SessionEntry {
        val input = decoder as JsonDecoder
        val obj = input.decodeJsonElement() as JsonObject
        val type = (obj["type"] as? JsonPrimitive)?.contentOrNull
        val json = input.json
        return if (type == "lesson") {
            val l = obj["lesson"] as? JsonObject ?: return SessionEntry()
            SessionEntry(lesson = json.decodeFromJsonElement(SessionLesson.serializer(), l))
        } else {
            SessionEntry(item = json.decodeFromJsonElement(Item.serializer(), obj))
        }
    }

    override fun serialize(encoder: Encoder, value: SessionEntry) = throw UnsupportedOperationException("nur lesen")
}

/** Antwort von GET session: die passende Runde fuer heute. */
@Serializable
data class LearnSession(
    val items: List<SessionEntry> = emptyList(),
    val plannedSeconds: Int = 0,
    val kind: String = "daily",
    val reason: String = "",
    val forecast: Forecast? = null,
    val showCheatsheetSuggestion: Boolean = false,
    /** Index, ab dem die Abschlussrunde beginnt (so, wie das Backend sie liefert). */
    val finalRoundStart: Int? = null,
    /** Zeitbudget des Tages (additiv). */
    val budget: Budget? = null,
) {
    /** Nur die Aufgaben (ohne Lektionen). */
    val quiz: List<Item> get() = items.mapNotNull { it.item }
}

/** Lektion der Lektionsliste (GET lessons). */
@Serializable
data class Lesson(
    val id: String,
    val examId: String = "",
    val topicId: String = "",
    val chapterId: String = "",
    val title: String = "",
    val keyPoints: List<String> = emptyList(),
    val ttsText: String = "",
    val mnemonic: String? = null,
    val estSeconds: Int = 60,
    val sourceType: String? = null,
    val source: Source? = null,
    val heard: Boolean = false,
    val heardAt: String? = null,
)

@Serializable
data class Chapter(
    val chapterId: String,
    val title: String = "",
    val order: Int = 0,
    val lessonCount: Int = 0,
    val minutes: Int = 0,
    /** new, heard, learning, solid */
    val status: String = "new",
    val mastery: Double = 0.0,
)

@Serializable
data class AudiobookItem(
    val lessonId: String,
    val chapterId: String = "",
    val chapterTitle: String = "",
    val title: String = "",
    val estSeconds: Int = 60,
)

@Serializable
data class Audiobook(val items: List<AudiobookItem> = emptyList(), val totalSeconds: Int = 0, val reason: String = "", val budget: Budget? = null)

@Serializable
data class HeardRequest(val fraction: Double)

@Serializable
data class AttemptRequest(
    val itemId: String, val outcome: String, val ms: Int, val at: String? = null,
    /** Eindeutige Id je Antwort (UUID): schon verbuchte clientIds uebergeht der Server, so ist das erneute Senden wiederholbar. */
    val clientId: String? = null,
)

@Serializable
data class AttemptsResponse(val forecast: Forecast, val doneSecondsToday: Int = 0, val nextLessonId: String? = null)

@Serializable
data class Bullet(val topicId: String = "", val topic: String = "", val text: String = "")

@Serializable
data class CheatSheet(val minutes: Int = 5, val words: Int = 0, val bullets: List<Bullet> = emptyList(), val note: String = "")

@Serializable
data class Topic(
    val id: String,
    val examId: String = "",
    val title: String = "",
    val oneLiner: String? = null,
    val weight: Double = 0.0,
    val order: Int = 0,
    val parentId: String? = null,
    val level: Int = 0,
    val mastery: Double = 0.0,
)

@Serializable
data class ExamCreate(
    val name: String,
    val date: String? = null,
    val time: String? = null,
    /** "exam" (Standard) oder "open" (ohne Termin; date/time entfallen). */
    val mode: String? = null,
    val description: String? = null,
    val dailyMinutes: Int? = null,
    val priorKnowledge: String? = null,
)

/** Teil-Aenderung: nur gesetzte Felder werden gesendet (explicitNulls = false). */
@Serializable
data class ExamUpdate(
    val name: String? = null,
    val mode: String? = null,
    val date: String? = null,
    val time: String? = null,
    val description: String? = null,
    val dailyMinutes: Int? = null,
    val priorKnowledge: String? = null,
)

/** Ein Wort im Vorlesetext: [start]/[end] sind Zeichenindizes (UTF-16, end exklusiv), Zeiten in Inhaltszeit der Audiodatei. */
@Serializable
data class WordTiming(val start: Int = 0, val end: Int = 0, val fromMs: Long = 0, val toMs: Long = 0)

@Serializable
data class AudioTimings(val text: String = "", val durationMs: Long = 0, val words: List<WordTiming> = emptyList())

/** Profil: Alter, Bildung und kurze Beschreibung; nur als Inspiration fuer passende Beispiele und Erklaerungen. */
@Serializable
data class Profile(
    val about: String = "",
    val age: Int? = null,
    /** hauptschule, realschule, abitur, ausbildung, studium, other */
    val educationLevel: String? = null,
    val onboardingDone: Boolean = false,
    /** Vorlesen an/aus (Standard an); bei aus gibt es keinerlei Audio-Aufrufe. */
    val audioEnabled: Boolean? = null,
)

/** Teil-Aenderung des Profils: nur gesetzte Felder werden gesendet. onboardingDone nur false -> true. */
@Serializable
data class ProfileUpdate(
    val about: String? = null,
    val age: Int? = null,
    val educationLevel: String? = null,
    val onboardingDone: Boolean? = null,
    val audioEnabled: Boolean? = null,
)

/** Antwort der Sprachnachricht: Zusammenfassung zum Pruefen (wird nicht gespeichert). */
@Serializable
data class VoiceSummary(val about: String = "")

/** Zeitbudget des Tages: dailyMinutes ist die Gesamtzeit (Zuhoeren plus Fragen). */
@Serializable
data class Budget(val dailyMinutes: Int = 0, val usedMinutesToday: Int = 0, val remainingMinutes: Int = 0)

/** Antwort von GET audio/status: gibt es gerade eine Stimme (billig, serverseitig gecacht)? */
@Serializable
data class AudioStatus(val available: Boolean = true, val latencyMs: Int? = null, val reason: String? = null, val retryAfterSeconds: Int? = null)

/** Fehlermeldung an das Backend (best effort, ohne Nutzertext und ohne Token). */
@Serializable
data class ClientLog(val kind: String, val code: String, val detail: String, val appVersion: String, val itemType: String)
