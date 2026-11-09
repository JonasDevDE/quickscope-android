package com.jonasschnell.quickscope

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jonasschnell.quickscope.data.AttemptRequest
import com.jonasschnell.quickscope.data.Forecast
import com.jonasschnell.quickscope.data.backendMessage
import com.jonasschnell.quickscope.logic.Task
import com.jonasschnell.quickscope.logic.outcomeFor
import com.jonasschnell.quickscope.logic.parseTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException
import java.time.Instant

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
) {
    val current: Task? get() = tasks.getOrNull(index)
    val inFinalRound: Boolean get() = finalRoundStart?.let { index >= it } == true
}

/** Lernrunde: laedt die Session, merkt Antworten lokal vor (offline-fest) und sendet sie batchweise. */
class SessionViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as QuickscopeApp
    private val api = app.network.api
    private val sync = app.attemptSync
    private val store = app.store

    private val _state = MutableStateFlow(SessionUi(audio = store.audioMode))
    val state: StateFlow<SessionUi> = _state.asStateFlow()

    private var shownAt = 0L

    fun start(examId: String, minutes: Int, scoreBefore: Int?) {
        _state.update { SessionUi(phase = SessionPhase.Loading, examId = examId, scoreBefore = scoreBefore, audio = store.audioMode) }
        viewModelScope.launch {
            try {
                sync.flush(examId) // Altes zuerst, damit die Auswahl auf aktuellem Stand beruht
                val s = api.session(examId, minutes)
                val tasks = s.items.map(::parseTask)
                if (tasks.isEmpty()) {
                    _state.update { it.copy(phase = SessionPhase.Empty, reason = s.reason) }
                } else {
                    shownAt = SystemClock.elapsedRealtime()
                    _state.update { it.copy(phase = SessionPhase.Running, tasks = tasks, reason = s.reason, finalRoundStart = s.finalRoundStart) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(phase = SessionPhase.Error, message = errorText(e)) }
            }
        }
    }

    /** Antwort zur aktuellen Aufgabe: sofort vormerken und Feedback zeigen; gesendet wird im Hintergrund. */
    fun answer(correct: Boolean) {
        val s = _state.value
        val task = s.current ?: return
        if (s.feedback != null) return
        val ms = (SystemClock.elapsedRealtime() - shownAt).toInt().coerceIn(0, 10 * 60 * 1000)
        sync.record(s.examId, AttemptRequest(task.item.id, outcomeFor(task, correct), ms, Instant.now().toString()))
        _state.update { it.copy(feedback = correct) }
        viewModelScope.launch { sync.flush(s.examId) }
    }

    /** Naechste Aufgabe; nach der letzten der Abschluss mit der neuen Prognose. */
    fun next() {
        val s = _state.value
        if (s.phase != SessionPhase.Running) return
        val nextIndex = s.index + 1
        if (nextIndex >= s.tasks.size) {
            _state.update { it.copy(index = nextIndex, feedback = null, phase = SessionPhase.Done, scoreAfter = it.scoreBefore) }
            viewModelScope.launch {
                val f = sync.flush(s.examId)
                if (f != null) _state.update { it.copy(forecastAfter = f, scoreAfter = f.scoreNowPercent) }
            }
        } else {
            shownAt = SystemClock.elapsedRealtime()
            _state.update { it.copy(index = nextIndex, feedback = null) }
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
