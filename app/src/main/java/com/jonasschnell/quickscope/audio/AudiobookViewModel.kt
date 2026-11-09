package com.jonasschnell.quickscope.audio

import android.app.Application
import android.content.ComponentName
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.jonasschnell.quickscope.BuildConfig
import com.jonasschnell.quickscope.QuickscopeApp
import com.jonasschnell.quickscope.data.AudiobookItem
import com.jonasschnell.quickscope.data.backendMessage
import com.jonasschnell.quickscope.logic.AudiobookQueue
import com.jonasschnell.quickscope.logic.chapterLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException

enum class BookPhase { Closed, Loading, Playing, Empty, Finished, Error }

/** Anzeige des Hoerbuchs: aktuelle Lektion mit Stichworten, Fortschritt und Wiedergabestand. */
data class BookUi(
    val phase: BookPhase = BookPhase.Closed,
    val index: Int = 0,
    val count: Int = 0,
    val title: String = "",
    val chapterTitle: String = "",
    val chapterLabel: String = "",
    val keyPoints: List<String> = emptyList(),
    val mnemonic: String? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val message: String? = null,
)

/** Steuert das Hoerbuch: Warteschlange laden, an den Dienst uebergeben und dessen Zustand anzeigen. */
class AudiobookViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as QuickscopeApp
    private val api = app.network.api

    private val _state = MutableStateFlow(BookUi())
    val state: StateFlow<BookUi> = _state.asStateFlow()

    private var controller: MediaController? = null
    private var ticker: Job? = null
    private var examId = ""
    private var queue = AudiobookQueue(emptyList())
    private var playerErrors = 0
    private var everPlayed = false
    private var watchdog: Job? = null

    fun start(examId: String, minutes: Int?, repeat: Boolean = false) {
        if (!com.jonasschnell.quickscope.logic.canStartBook(_state.value.phase.name)) return // nur eine Wiedergabe gleichzeitig
        this.examId = examId
        _state.value = BookUi(phase = BookPhase.Loading)
        playerErrors = 0
        everPlayed = false
        viewModelScope.launch {
            try {
                val book = api.audiobook(examId, minutes, if (repeat) true else null)
                if (book.items.isEmpty()) {
                    _state.update { it.copy(phase = BookPhase.Empty, message = book.reason.ifBlank { "Nichts Neues zu hören." }) }
                    return@launch
                }
                val chapters = runCatching { api.chapters(examId) }.getOrDefault(emptyList())
                val lessons = runCatching { api.lessons(examId) }.getOrDefault(emptyList()).associateBy { it.id }
                val order = chapters.associate { it.chapterId to it.order }
                queue = AudiobookQueue(book.items)
// Preflight: ohne Stimme (offline, nicht verfuegbar) gibt es kein Hoerbuch; ruhige Meldung statt Fehlerbild
                if (!app.audio.available(force = true)) {
                    _state.value = BookUi(phase = BookPhase.Error)
                    return@launch
                }
                val mediaItems = book.items.map { mediaItem(it, lessons[it.lessonId], order[it.chapterId] ?: 0, chapters.size) }
                connect {
                    it.setMediaItems(mediaItems)
                    it.prepare()
                    it.play()
                }
                _state.update { it.copy(phase = BookPhase.Playing, count = book.items.size) }
                // Kein "Ton laedt" ueber laengere Zeit: laeuft der Stream nicht binnen ~8 s, sauber beenden (Dienst und Benachrichtigung weg)
                watchdog = launch {
                    delay(8_000L)
                    if (controller?.isPlaying != true && _state.value.phase == BookPhase.Playing && !everPlayed) failQuietly()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(phase = BookPhase.Error, message = errorText(e)) }
            }
        }
    }

    private fun mediaItem(item: AudiobookItem, lesson: com.jonasschnell.quickscope.data.Lesson?, chapterOrder: Int, chapterTotal: Int): MediaItem {
        val extras = Bundle().apply {
            putString(LessonExtras.EXAM_ID, examId)
            putStringArray(LessonExtras.KEY_POINTS, lesson?.keyPoints?.toTypedArray() ?: emptyArray())
            putString(LessonExtras.MNEMONIC, lesson?.mnemonic)
            putInt(LessonExtras.CHAPTER_ORDER, chapterOrder)
            putInt(LessonExtras.CHAPTER_TOTAL, chapterTotal)
            putInt(LessonExtras.EST_SECONDS, item.estSeconds)
            putString(LessonExtras.TITLE, item.title)
            putString(LessonExtras.CHAPTER_TITLE, item.chapterTitle)
        }
        return MediaItem.Builder()
            .setMediaId(item.lessonId)
            .setUri(app.audio.lessonUri(examId, item.lessonId))
            // generischer Titel: keine Titel oder Kapitel nach aussen (Sperrbildschirm)
            .setMediaMetadata(MediaMetadata.Builder().setTitle("Quickscope").setExtras(extras).build())
            .build()
    }


    private fun connect(onReady: (MediaController) -> Unit) {
        controller?.let { onReady(it); return }
        val token = SessionToken(app, ComponentName(app, AudiobookService::class.java))
        val future = MediaController.Builder(app, token).setListener(object : MediaController.Listener {
        }).buildAsync()
        future.addListener({
            val c = runCatching { future.get() }.getOrNull()
            if (c == null) {
                _state.update { it.copy(phase = BookPhase.Error, message = "Wiedergabe nicht verfügbar.") }
                return@addListener
            }
            controller = c
            c.addListener(object : Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) { playerErrors++ }
                override fun onEvents(player: Player, events: Player.Events) {
                    if (player.isPlaying) everPlayed = true
                    refresh(player)
                }
            })
            onReady(c)
            startTicker()
        }, ContextCompat.getMainExecutor(app))
    }

    private fun refresh(p: Player) {
        if (p.playbackState == Player.STATE_ENDED) {
            // Ende durch ausgefallene Streams (nach 3 Versuchen uebersprungen): sauber beenden statt "Fertig" zu zeigen
            if (playerErrors > 0 && !everPlayed) failQuietly() else _state.update { it.copy(phase = BookPhase.Finished, playing = false) }
            return
        }
        val item = p.currentMediaItem ?: return
        val extras = item.mediaMetadata.extras
        val idx = p.currentMediaItemIndex
        if (idx != queue.index) {
            queue.jumpTo(idx)
        }
        _state.update {
            if (it.phase != BookPhase.Playing && it.phase != BookPhase.Loading) it
            else it.copy(
                phase = BookPhase.Playing,
                index = idx,
                count = p.mediaItemCount,
                title = extras?.getString(LessonExtras.TITLE).orEmpty(),
                chapterTitle = extras?.getString(LessonExtras.CHAPTER_TITLE).orEmpty(),
                chapterLabel = chapterLabel(extras?.getInt(LessonExtras.CHAPTER_ORDER) ?: 0, extras?.getInt(LessonExtras.CHAPTER_TOTAL) ?: 0),
                keyPoints = extras?.getStringArray(LessonExtras.KEY_POINTS)?.toList().orEmpty(),
                mnemonic = extras?.getString(LessonExtras.MNEMONIC),
                playing = p.isPlaying,
                durationMs = p.duration.takeIf { d -> d > 0 } ?: ((extras?.getInt(LessonExtras.EST_SECONDS) ?: 0) * 1000L),
            )
        }
    }

    /** Wiedergabeposition ca. 4 mal pro Sekunde fuer die Stichwort-Hervorhebung. */
    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (true) {
                controller?.let { c ->
                    if (_state.value.phase == BookPhase.Playing) _state.update { it.copy(positionMs = c.currentPosition, playing = c.isPlaying) }
                }
                delay(250)
            }
        }
    }

    fun playPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun next() {
        val c = controller ?: return
        if (c.hasNextMediaItem()) c.seekToNextMediaItem() else {
            c.stop()
            _state.update { it.copy(phase = BookPhase.Finished, playing = false) }
        }
    }

    fun previous() {
        val c = controller ?: return
        if (c.hasPreviousMediaItem()) c.seekToPreviousMediaItem() else c.seekTo(0)
    }

    /** Ausfall ohne Fehlertext: Dienst loslassen und zurueck auf Heute (die Seite schliesst sich selbst). */
    private fun failQuietly() {
        watchdog?.cancel()
        ticker?.cancel()
        controller?.let { c -> runCatching { c.stop(); c.clearMediaItems(); c.release() } }
        controller = null
        _state.value = BookUi(phase = BookPhase.Error)
    }

    /** Beenden: Wiedergabe stoppen (der gehoerte Anteil wird vom Dienst gemeldet) und den Dienst loslassen. */
    fun close() {
        ticker?.cancel()
        controller?.let { c ->
            c.stop()
            c.clearMediaItems()
            c.release()
        }
        controller = null
        _state.value = BookUi()
    }

    override fun onCleared() {
        ticker?.cancel()
        controller?.release()
        controller = null
    }

    private fun errorText(e: Exception): String = when (e) {
        is HttpException -> backendMessage(e) ?: "Serverfehler (${e.code()})."
        is IOException -> "Keine Verbindung zum Server."
        else -> e.message ?: "Unbekannter Fehler."
    }
}
