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
import com.jonasschnell.quickscope.logic.AudioMode
import com.jonasschnell.quickscope.logic.shouldRecheckAudio
import com.jonasschnell.quickscope.logic.canStartSession
import com.jonasschnell.quickscope.logic.StartMode
import com.jonasschnell.quickscope.logic.filterForMode
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
import com.jonasschnell.quickscope.logic.nextOfflineLesson
import com.jonasschnell.quickscope.logic.outcomeFor
import com.jonasschnell.quickscope.logic.topicLabels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
    /** Index, bei dem das Auto-Play nach einem Stopp im Hintergrund ruht (bis zum naechsten Item oder bis Play gedrueckt wird). */
    val autoplaySuppressedAt: Int? = null,
    /** Audiomodus der Runde (Vorlesen an UND Stimme verfuegbar UND online); sonst laeuft die Runde still, ohne Hinweis. */
    val audio: Boolean = false,
    /** Runde aus dem lokalen Offline-Paket (ohne Ton, Antworten werden spaeter gesendet). */
    val offline: Boolean = false,
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

    private val _state = MutableStateFlow(SessionUi())
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

    private var startJob: Job? = null
    private var recheckJob: Job? = null
    private var lastAudioCheck = 0L

    /** [minutes] null = Rest des Tagesbudgets (leer, wenn das Tagesziel geschafft ist); sonst der ausdrueckliche Wunsch. */
    fun start(examId: String, minutes: Int?, scoreBefore: Int?, questionsOnly: Boolean = false) {
        // genau eine aktive Runde: Doppeltipp, doppelte Aufrufe oder ein Start waehrend einer laufenden Runde werden verworfen
        if (!canStartSession(_state.value.phase)) return
        startJob?.cancel()
        recheckJob?.cancel()
        _state.update { SessionUi(phase = SessionPhase.Loading, examId = examId, scoreBefore = scoreBefore) }
        startJob = viewModelScope.launch {
            try {
                if (!app.connectivity.isOnline()) {
                    startOffline(examId, minutes, questionsOnly)
                    return@launch
                }
                sync.flush(examId) // Altes zuerst, damit die Auswahl auf aktuellem Stand beruht
                val s = try {
                    api.session(examId, minutes, if (questionsOnly) false else null)
                } catch (e: IOException) {
                    // Netz weg, obwohl es online schien: mit dem Offline-Paket weitermachen, wenn eines da ist
                    if (app.local.pack(examId) == null) throw e
                    app.connectivity.markOffline()
                    startOffline(examId, minutes, questionsOnly)
                    return@launch
                }
                val topicList = runCatching { api.topics(examId) }.getOrDefault(emptyList())
                val labels = topicLabels(topicList)
                val parents = topicList.mapNotNull { it.parentId }.toSet()
                leafIds = topicList.filter { it.id !in parents }.map { it.id }.toSet()
                topicTitles = topicList.associate { it.id to it.title }
                prevMastery = topicList.filter { it.id in leafIds }.associate { it.id to it.mastery }
                tracker = FeedbackTracker()
                // "Nur Fragen": Lektionen clientseitig weglassen (das Backend hat dafuer noch keinen Parameter)
                val mode = if (questionsOnly) StartMode.QuestionsOnly else StartMode.All
                val tasks = filterForMode(s.items, { it.lesson != null }, mode).mapNotNull(::taskOf)
                if (tasks.isEmpty()) {
                    _state.update { it.copy(phase = SessionPhase.Empty, reason = s.reason) }
                } else {
                    // Preflight: Ton nur, wenn gewuenscht, online und die Stimme verfuegbar ist; sonst still, ohne Hinweis
                    val audioOn = withTimeoutOrNull(4_000) { app.audio.available(force = true) } ?: true
                    lastAudioCheck = SystemClock.elapsedRealtime()
                    shownAt = SystemClock.elapsedRealtime()
                    startedAt = shownAt
                    _state.update {
                        it.copy(
                            phase = SessionPhase.Running, tasks = tasks, isRetry = List(tasks.size) { false }, reason = s.reason, audio = audioOn,
                            finalRoundStart = s.finalRoundStart?.let { f -> f - (s.items.take(f).size - filterForMode(s.items.take(f), { it.lesson != null }, mode).size) },
                            topics = labels,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(phase = SessionPhase.Error, message = errorText(e)) }
            }
        }
    }

    /** Runde aus dem Offline-Paket: Reihenfolge des Pakets, ohne schon Erledigtes, still (kein Ton), Antworten bleiben in der Warteschlange. */
    private fun startOffline(examId: String, minutes: Int?, questionsOnly: Boolean) {
        val pack = app.local.pack(examId)
        if (pack == null) {
            _state.update { it.copy(phase = SessionPhase.Error, message = com.jonasschnell.quickscope.logic.NEEDS_INTERNET) }
            return
        }
        val exam = app.local.exams()?.firstOrNull { it.id == examId }
        val doneSeconds = app.local.forecast(examId)?.doneSecondsToday ?: 0
        val m = minutes ?: com.jonasschnell.quickscope.logic.remainingMinutes(exam?.dailyMinutes ?: 20, doneSeconds)
        val entries = com.jonasschnell.quickscope.logic.offlineRound(pack, app.local.doneKeys(examId), m * 60, questionsOnly)
        val tasks = entries.mapNotNull(::taskOf)
        if (tasks.isEmpty()) {
            _state.update { it.copy(phase = SessionPhase.Empty, offline = true) }
            return
        }
        val topicList = app.local.topics(examId)
        val parents = topicList.mapNotNull { it.parentId }.toSet()
        leafIds = topicList.filter { it.id !in parents }.map { it.id }.toSet()
        topicTitles = topicList.associate { it.id to it.title }
        prevMastery = topicList.filter { it.id in leafIds }.associate { it.id to it.mastery }
        tracker = FeedbackTracker()
        shownAt = SystemClock.elapsedRealtime()
        startedAt = shownAt
        _state.update {
            it.copy(
                phase = SessionPhase.Running, tasks = tasks, isRetry = List(tasks.size) { false }, audio = false, offline = true,
                topics = topicLabels(topicList),
            )
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
            if (s.offline) app.local.markDone(s.examId, "q:${task.item.id}")
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
            if (s.offline && correct) app.local.markDone(s.examId, "q:${task.item.id}")
            if (!correct) {
                val at = if (s.offline) com.jonasschnell.quickscope.logic.offlineRetryIndex(s.index, tasks.size, task.item.id) else retryInsertIndex(s.index, tasks.size, task.item.id)
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
        if (s.offline) {
            // offline: die naechste Lektion des Pakets, falls eine da ist
            val pack = app.local.pack(s.examId) ?: return
            val present = s.tasks.mapNotNull { (it as? Task.Teach)?.lesson?.id }.toSet()
            nextOfflineLesson(pack, present, app.local.doneKeys(s.examId))?.let { insertLessonCard(it) }
            return
        }
        viewModelScope.launch {
            val r = sync.flush(s.examId) ?: return@launch
            r.nextLessonId?.let { insertLesson(s.examId, it) }
        }
    }

    /** Lektion direkt nach der aktuellen Aufgabe einfuegen (nie doppelt). */
    private suspend fun insertLesson(examId: String, lessonId: String) {
        val l = runCatching { api.lessons(examId) }.getOrNull()?.firstOrNull { it.id == lessonId } ?: return
        insertLessonCard(SessionLesson(l.id, l.title, l.keyPoints, l.ttsText, l.mnemonic, l.estSeconds))
    }

    private fun insertLessonCard(card: SessionLesson) {
        val lessonId = card.id
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
        if (s.offline) (s.current as? Task.Teach)?.let { app.local.markDone(s.examId, "l:${it.lesson.id}") }
        if (nextIndex >= s.tasks.size) {
            val today = LocalDate.now()
            val streakDays = nextDayStreak(store.lastSessionDay?.let { runCatching { LocalDate.parse(it) }.getOrNull() }, store.dayStreak, today)
            store.lastSessionDay = today.toString()
            store.dayStreak = streakDays
            val seconds = ((SystemClock.elapsedRealtime() - startedAt) / 1000).toInt()
            _state.update {
                it.copy(index = nextIndex, feedback = null, neutral = false, phase = SessionPhase.Done, scoreAfter = it.scoreBefore, elapsedSeconds = seconds, dayStreak = streakDays)
            }
            com.jonasschnell.quickscope.data.PackSyncScheduler.schedule(app, replace = true)
            viewModelScope.launch {
                val f = sync.flush(s.examId)
                if (f != null) _state.update { it.copy(forecastAfter = f.forecast, scoreAfter = f.forecast.scoreOrNull()) }
            }
        } else {
            shownAt = SystemClock.elapsedRealtime()
            _state.update { it.copy(index = nextIndex, feedback = null, neutral = false, autoplaySuppressedAt = null) }
            maybeRecheckAudio()
        }
    }

    /** War der Modus still, wird beim naechsten Item hoechstens alle 60 s still erneut geprueft (kein Fehler, keine Anzeige). */
    private fun maybeRecheckAudio() {
        val now = SystemClock.elapsedRealtime()
        val mode = if (_state.value.audio) AudioMode.On else AudioMode.Silent
        if (!shouldRecheckAudio(mode, store.audioEnabled, lastAudioCheck, now)) return
        lastAudioCheck = now
        recheckJob?.cancel()
        recheckJob = viewModelScope.launch {
            val ok = app.audio.available(force = true)
            _state.update { it.copy(audio = ok) }
        }
    }

    /** App im Hintergrund: Auto-Play ruht fuer die aktuelle Karte. */
    fun suppressAutoplay() {
        _state.update { it.copy(autoplaySuppressedAt = it.index) }
    }

    /** Play gedrueckt: Auto-Play darf wieder starten. */
    fun allowAutoplay() {
        _state.update { it.copy(autoplaySuppressedAt = null) }
    }

    /** Schliesst die Runde (Abbruch oder "Fertig"); bereits gegebene Antworten bleiben und werden nachgesendet. */
    fun close() {
        val id = _state.value.examId
        // laufendes Laden und Gate beenden, damit eine geschlossene Runde nicht von selbst wieder auftaucht
        startJob?.cancel()
        recheckJob?.cancel()
        _state.value = SessionUi()
        if (id.isNotEmpty()) {
            viewModelScope.launch { sync.flush(id) }
            com.jonasschnell.quickscope.data.PackSyncScheduler.schedule(app, replace = true)
        }
    }

    private fun errorText(e: Exception): String = when (e) {
        is HttpException -> backendMessage(e) ?: when (e.code()) {
            409 -> "Der Termin ist schon vorbei."
            else -> "Serverfehler (${e.code()})."
        }
        is IOException -> "Keine Verbindung zum Server."
        else -> e.message ?: "Unbekannter Fehler."
    }
}
