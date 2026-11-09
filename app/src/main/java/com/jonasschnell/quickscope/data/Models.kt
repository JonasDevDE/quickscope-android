package com.jonasschnell.quickscope.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

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
    val targetPercent: Int = 80,
    val dailyMinutes: Int = 20,
    val status: String = "empty",
    val mode: String? = null,
    val topicCount: Long = 0,
    val itemCount: Long = 0,
    val materialCount: Long = 0,
    val createdAt: String? = null,
    val daysLeft: Int = 0,
    /** Fehlertext, wenn status = failed (z. B. "KI nicht eingerichtet", "KI-Limit erreicht, später wieder"). */
    val error: String? = null,
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
    val id: String,
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
data class Milestone(val date: String = "", val coveragePercent: Int = 0, val scorePercent: Int = 0)

@Serializable
data class Forecast(
    val scoreNowPercent: Int = 0,
    val scoreAtExamPercent: Int = 0,
    val targetPercent: Int = 80,
    val onTrack: Boolean = false,
    val extraMinutesPerDayNeeded: Int? = null,
    val daysLeft: Int = 0,
    val hoursLeft: Long = 0,
    val doneSecondsToday: Int = 0,
    val weakTopics: List<WeakTopic> = emptyList(),
    val confidence: String = "low",
    val mode: String? = null,
    val coveragePercent: Int = 0,
    val expectedGainPercentThisSession: Double? = null,
    val milestones: List<Milestone> = emptyList(),
    val showCheatsheetSuggestion: Boolean = false,
    val note: String = "Schätzung, keine Garantie",
)

/** Antwort von GET session: die passende Runde fuer heute. */
@Serializable
data class LearnSession(
    val items: List<Item> = emptyList(),
    val plannedSeconds: Int = 0,
    val kind: String = "daily",
    val reason: String = "",
    val forecast: Forecast? = null,
    val showCheatsheetSuggestion: Boolean = false,
    /** Index, ab dem die Abschlussrunde beginnt (so, wie das Backend sie liefert). */
    val finalRoundStart: Int? = null,
)

@Serializable
data class AttemptRequest(val itemId: String, val outcome: String, val ms: Int, val at: String? = null)

@Serializable
data class AttemptsResponse(val forecast: Forecast, val doneSecondsToday: Int = 0)

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
    val date: String,
    val time: String,
    val description: String? = null,
    val dailyMinutes: Int? = null,
    val targetPercent: Int? = null,
)

/** Teil-Aenderung: nur gesetzte Felder werden gesendet (explicitNulls = false). */
@Serializable
data class ExamUpdate(
    val name: String? = null,
    val date: String? = null,
    val time: String? = null,
    val description: String? = null,
    val dailyMinutes: Int? = null,
    val targetPercent: Int? = null,
)
