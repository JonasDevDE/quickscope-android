package com.jonasschnell.quickscope

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jonasschnell.quickscope.data.AttemptRequest
import com.jonasschnell.quickscope.data.Forecast
import com.jonasschnell.quickscope.data.backendMessage
import com.jonasschnell.quickscope.data.HeardRequest
import com.jonasschnell.quickscope.data.SessionLesson
import com.jonasschnell.quickscope.logic.FeedbackTracker
import com.jonasschnell.quickscope.logic.dontKnowOutcome
import com.jonasschnell.quickscope.logic.lessonInsertIndex
import com.jonasschnell.quickscope.logic.taskOf
import com.jonasschnell.quickscope.logic.teachItem
import com.jonasschnell.quickscope.logic.Outcome
import com.jonasschnell.quickscope.logic.Task
import com.jonasschnell.quickscope.logic.newlySitting
import com.jonasschnell.quickscope.logic.nextDayStreak
import com.jonasschnell.quickscope.logic.scoreOrNull
import com.jonasschnell.quickscope.logic.retryInsertIndex
import com.jonasschnell.quickscope.logic.outcomeFor
import com.jonasschnell.quickscope.logic.topicLabels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException
import java.time.Instant
import java.time.LocalDate

enum class SessionPhase { Closed, Loading, Running, Done, Empty, Error }

/** Zustand der Lernrunde. [feedback] ist gesetzt, solange die Antwort zur aktuellen Aufgabe gezeigt wird. */
data class SessionUi(
    val phase: SessionPhase = SessionPhase.Closed,
    val examId: String = "",
    val tasks: List<Task> = emptyList(),
    val index: Int = 0,
    val reason: String = "",
    val finalRoundStart: Int? = null,
    val scoreBefore: Int? = null,
    val scoreAfter: Int? = null,
    val forecastAfter: Forecast? = null,
    val message: String? = null,
    val feedback: Boolean? = null,
    val audio: Boolean = false,
    /** Themen-Id -> Titel (ggf. "Gruppe › Titel") fuer die Zeile ueber der Frage. */
    val topics: Map<String, String> = emptyMap(),
    /** Parallel zu [tasks]: true bei "Gleich nochmal"-Wiederholungen (zaehlen nicht gegen Zeit und Fortschritt). */
    val isRetry: List<Boolean> = emptyList(),
    /** Einmaliges Feedback-Ereignis der letzten Antwort (neue [Fx.id] = neu abspielen). */
    val fx: Fx? = null,
    val streak: Int = 0,
    val bestStreak: Int = 0,
    val xp: Int = 0,
    val correctCount: Int = 0,
    val answerCount: Int = 0,
    val elapsedSeconds: Int = 0,
    /** Tagesserie nach dieser Runde (Tage in Folge). */
    val dayStreak: Int = 0,
    /** Ein Thema "sitzt" jetzt (Banner). */
    val sits: Sits? = null,
    /** "Weiss nicht": neutrale Rueckmeldung (richtige Antwort markiert, keine Feier). */
    val neutral: Boolean = false,
) {
    val current: Task? get() = tasks.getOrNull(index)
    val currentIsRetry: Boolean get() = isRetry.getOrElse(index) { false }
    val inFinalRound: Boolean get() = finalRoundStart?.let { index >= it } == true
}

data class Fx(val id: Int, val outcome: Outcome)

data class Sits(val id: Int, val title: String)

/** Lernrunde: laedt die Session, merkt Antworten lokal vor (offline-fest) und sendet sie batchweise. */
class SessionViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as QuickscopeApp
    private val api = app.network.api
    private val sync = app.attemptSync
    private val store = app.store

    private val _state = MutableStateFlow(SessionUi(audio = store.audioMode))
    val state: StateFlow<SessionUi> = _state.asStateFlow()

    private var shownAt = 0L
    private var startedAt = 0L
    private var tracker = FeedbackTracker()
    private var fxId = 0
    private var sitsId = 0
    private var prevMastery: Map<String, Double> = emptyMap()
    private var leafIds: Set<String> = emptySet()
    private var topicTitles: Map<String, String> = emptyMap()
    private var lastSitsCheck = 0L

    fun start(examId: String, minutes: Int, scoreBefore: Int?) {
        _state.update { SessionUi(phase = SessionPhase.Loading, examId = examId, scoreBefore = scoreBefore, audio = store.audioMode) }
        viewModelScope.launch {
            try {
                app.audioCache.clearing?.join() // Aufraeumen der letzten Runde abwarten
                sync.flush(examId) // Altes zuerst, damit die Auswahl auf aktuellem Stand beruht
                val s = api.session(examId, minutes)
                val topicList = runCatching { api.topics(examId) }.getOrDefault(emptyList())
                val labels = topicLabels(topicList)
                val parents = topicList.mapNotNull { it.parentId }.toSet()
                leafIds = topicList.filter { it.id !in parents }.map { it.id }.toSet()
                topicTitles = topicList.associate { it.id to it.title }
                prevMastery = topicList.filter { it.id in leafIds }.associate { it.id to it.mastery }
                tracker = FeedbackTracker()
                val tasks = s.items.mapNotNull(::taskOf)
                val lessonIds = tasks.filterIsInstance<Task.Teach>().map { it.lesson.id }
                if (lessonIds.isNotEmpty()) app.audioCache.requestLessonPrefetch(examId, lessonIds.take(3))
                if (tasks.isEmpty()) {
                    _state.update { it.copy(phase = SessionPhase.Empty, reason = s.reason) }
                } else {
                    shownAt = SystemClock.elapsedRealtime()
                    startedAt = shownAt
                    _state.update { it.copy(phase = SessionPhase.Running, tasks = tasks, isRetry = List(tasks.size) { false }, reason = s.reason, finalRoundStart = s.finalRoundStart, topics = labels) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(phase = SessionPhase.Error, message = errorText(e)) }
            }
        }
    }

    /**
     * Antwort zur aktuellen Aufgabe: sofort vormerken und Feedback zeigen; gesendet wird im Hintergrund.
     * Falsche Antworten kommen nach 3 bis 5 Aufgaben als "Gleich nochmal" wieder (nur lokal, ohne erneutes Senden).
     */
    fun answer(correct: Boolean) {
        val s = _state.value
        val task = s.current ?: return
        if (s.feedback != null) return
        val ms = (SystemClock.elapsedRealtime() - shownAt).toInt().coerceIn(0, 10 * 60 * 1000)
        if (task is Task.Teach) return
        if (task is Task.Lesson) {
            // Loesungsweg: wird als gelernt vermerkt, aber ohne Punkte und Feier
            sync.record(s.examId, AttemptRequest(task.item.id, outcomeFor(task, true), ms, Instant.now().toString()))
            _state.update { it.copy(feedback = true) }
            viewModelScope.launch { sync.flush(s.examId) }
            return
        }
        val retry = s.currentIsRetry
        val outcome = tracker.record(correct, task.item.difficulty, retry)
        var tasks = s.tasks
        var flags = s.isRetry
        var finalStart = s.finalRoundStart
        if (!retry) {
            sync.record(s.examId, AttemptRequest(task.item.id, outcomeFor(task, correct), ms, Instant.now().toString()))
            viewModelScope.launch {
                val f = sync.flush(s.examId)
                if (f != null) checkSits(s.examId)
            }
            if (!correct) {
                val at = retryInsertIndex(s.index, tasks.size, task.item.id)
                tasks = tasks.toMutableList().apply { add(at, task) }
                flags = flags.toMutableList().apply { add(at, true) }
                if (finalStart != null && at <= finalStart) finalStart += 1
            }
        }
        fxId++
        _state.update {
            it.copy(
                feedback = correct, tasks = tasks, isRetry = flags, finalRoundStart = finalStart, fx = Fx(fxId, outcome),
                streak = tracker.streak, bestStreak = tracker.best, xp = tracker.xp, correctCount = tracker.correct, answerCount = tracker.answers,
            )
        }
    }

    /**
     * "Weiss nicht": neutral. Sendet outcome "dontknow", markiert nur die richtige Antwort (keine Feier, Serie unveraendert).
     * Liefert die Antwort eine nextLessonId, kommt diese Lektion als Naechstes in der Runde.
     */
    fun dontKnow() {
        val s = _state.value
        val task = s.current ?: return
        if (s.feedback != null || task is Task.Card || task is Task.Lesson || task is Task.Teach) return
        val ms = (SystemClock.elapsedRealtime() - shownAt).toInt().coerceIn(0, 10 * 60 * 1000)
        _state.update { it.copy(feedback = false, neutral = true) }
        if (s.currentIsRetry) return
        sync.record(s.examId, AttemptRequest(task.item.id, dontKnowOutcome(), ms, Instant.now().toString()))
        viewModelScope.launch {
            val r = sync.flush(s.examId) ?: return@launch
            r.nextLessonId?.let { insertLesson(s.examId, it) }
        }
    }

    /** Lektion direkt nach der aktuellen Aufgabe einfuegen (nie doppelt). */
    private suspend fun insertLesson(examId: String, lessonId: String) {
        val l = runCatching { api.lessons(examId) }.getOrNull()?.firstOrNull { it.id == lessonId } ?: return
        val card = SessionLesson(l.id, l.title, l.keyPoints, l.ttsText, l.mnemonic, l.estSeconds)
        _state.update { st ->
            val at = lessonInsertIndex(st.index, st.tasks, lessonId) ?: return@update st
            val teach = Task.Teach(teachItem(card), card)
            st.copy(
                tasks = st.tasks.toMutableList().apply { add(at, teach) },
                isRetry = st.isRetry.toMutableList().apply { add(at, false) },
                finalRoundStart = st.finalRoundStart?.let { f -> if (at <= f) f + 1 else f },
            )
        }
    }

    /** Anteil einer Lektion melden, der gehoert wurde (ab 0.8 gilt sie als gehoert); Fehler sind unkritisch. */
    fun heard(lessonId: String, fraction: Double) {
        val examId = _state.value.examId
        if (examId.isEmpty()) return
        viewModelScope.launch { runCatching { api.heard(examId, lessonId, HeardRequest(fraction.coerceIn(0.0, 1.0))) } }
    }

    /** Pruefen, ob durch die letzten Antworten ein Thema ueber die Schwelle gekommen ist ("Thema sitzt"). */
    private suspend fun checkSits(examId: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSitsCheck < 2_500) return
        lastSitsCheck = now
        val list = runCatching { api.topics(examId) }.getOrNull() ?: return
        val current = list.filter { it.id in leafIds }.associate { it.id to it.mastery }
        val ids = newlySitting(prevMastery, current)
        prevMastery = current
        val id = ids.firstOrNull() ?: return
        sitsId++
        _state.update { it.copy(sits = Sits(sitsId, topicTitles[id] ?: "")) }
    }

    /** Naechste Aufgabe; nach der letzten der Abschluss mit der neuen Prognose. */
    fun next() {
        val s = _state.value
        if (s.phase != SessionPhase.Running) return
        val nextIndex = s.index + 1
        if (nextIndex >= s.tasks.size) {
            val today = LocalDate.now()
            val streakDays = nextDayStreak(store.lastSessionDay?.let { runCatching { LocalDate.parse(it) }.getOrNull() }, store.dayStreak, today)
            store.lastSessionDay = today.toString()
            store.dayStreak = streakDays
            val seconds = ((SystemClock.elapsedRealtime() - startedAt) / 1000).toInt()
            _state.update {
                it.copy(index = nextIndex, feedback = null, neutral = false, phase = SessionPhase.Done, scoreAfter = it.scoreBefore, elapsedSeconds = seconds, dayStreak = streakDays)
            }
            viewModelScope.launch {
                val f = sync.flush(s.examId)
                if (f != null) _state.update { it.copy(forecastAfter = f.forecast, scoreAfter = f.forecast.scoreOrNull()) }
            }
        } else {
            shownAt = SystemClock.elapsedRealtime()
            _state.update { it.copy(index = nextIndex, feedback = null, neutral = false) }
        }
    }

    fun setAudio(on: Boolean) {
        store.audioMode = on
        _state.update { it.copy(audio = on) }
    }

    /** Schliesst die Runde (Abbruch oder "Fertig"); bereits gegebene Antworten bleiben und werden nachgesendet. */
    fun close() {
        val id = _state.value.examId
        _state.value = SessionUi(audio = store.audioMode)
        app.audioCache.clearing = viewModelScope.launch { app.audioCache.clear() }
        if (id.isNotEmpty()) viewModelScope.launch { sync.flush(id) }
    }

    private fun errorText(e: Exception): String = when (e) {
        is HttpException -> backendMessage(e) ?: when (e.code()) {
            409 -> "Die Prüfung ist schon vorbei."
            else -> "Serverfehler (${e.code()})."
        }
        is IOException -> "Keine Verbindung zum Server."
        else -> e.message ?: "Unbekannter Fehler."
    }
}
