package com.jonasschnell.quickscope.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.jonasschnell.quickscope.QuickscopeApp
import com.jonasschnell.quickscope.data.AudioTimings
import com.jonasschnell.quickscope.data.WordTiming
import com.jonasschnell.quickscope.logic.PlayState
import com.jonasschnell.quickscope.logic.AudioHealth
import com.jonasschnell.quickscope.logic.retryDelayMs
import com.jonasschnell.quickscope.logic.audioErrorCode
import com.jonasschnell.quickscope.logic.streamStartTimeoutMs
import com.jonasschnell.quickscope.logic.PlaybackMachine
import com.jonasschnell.quickscope.logic.SPEECH_SPEED
import com.jonasschnell.quickscope.logic.VoicePart
import com.jonasschnell.quickscope.logic.shouldStopForLifecycle
import com.jonasschnell.quickscope.logic.timingsMatch
import com.jonasschnell.quickscope.logic.wordPosAt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Vorlesen: EINE zentrale Instanz fuer die ganze App (Cloud-Stimme, WAV vom Backend, lokal im Cache, MediaPlayer mit Audiofokus).
 * Eine Zustandsmaschine ([PlaybackMachine]: idle, loading, playing, paused) mit Generationsnummer sorgt dafuer, dass nie
 * zwei Wiedergaben gleichzeitig laufen und verspaetete Rueckmeldungen alter Elemente verworfen werden. Es gibt keine
 * Geraetestimme: kommt die Cloud-Stimme nicht, bleibt es still und [unavailable] zeigt eine ruhige Zeile mit "Nochmal".
 */
class Voice(private val context: Context, private val scope: CoroutineScope) {
    private val app = context.applicationContext as QuickscopeApp
    private val client = app.audio
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Zustand der Wiedergabe (nie mehr als eine Wiedergabe gleichzeitig). */
    val machine = PlaybackMachine()

    /** Zustand der Wiedergabe fuer den Play-/Pause-Knopf (beobachtbar). */
    var playState by mutableStateOf(PlayState.Idle)
        private set

    private fun sync() {
        playState = machine.state
    }

    /** Pause nur fuer die aktuelle Karte; die naechste Karte startet wieder von selbst. */
    fun pause() {
        val g = machine.generation
        player?.let { p -> if (runCatching { p.isPlaying }.getOrDefault(false)) { p.pause(); machine.pause(g); sync() } }
    }

    fun resume() {
        val g = machine.generation
        player?.let { p -> if (machine.state == PlayState.Paused) { p.start(); machine.resume(g); sync() } }
    }

    /** Ausfall-Erkennung fuer die ganze Runde; der Knopf haengt nur an diesem stabilen Zustand, nie an loading/failed je Versuch. */
    private val health = AudioHealth()

    /** Sichtbarkeit des Play/Pause-Knopfs (nur bei Ereignissen neu gesetzt, nie pro Frame). */
    var buttonVisible by mutableStateOf(true)
        private set

    /** Neue Runde: Knopf wieder sichtbar, Fehlerzaehler zurueck. */
    fun resetHealth() {
        health.reset()
        buttonVisible = true
    }

    /** Live-Hervorhebung des gerade gesprochenen Wortes (leer, wenn nichts zu markieren ist). */
    var highlight by mutableStateOf(Highlight())
        private set

    private var player: MediaPlayer? = null


    /** Beendet die laufende Wiedergabe sofort (gibt Player, Audiofokus und Empfaenger frei). */
    private var cancelHook: (() -> Unit)? = null

    /** Liest die Frage per Live-Streaming vor. [displayText]: nur wenn er exakt zu den Wortzeiten passt, wird hervorgehoben. */
    suspend fun speak(examId: String, itemId: String, displayText: String?): Boolean =
        play("Frage $itemId", client.itemUri(examId, itemId), if (displayText != null) ({ client.timings(examId, itemId) }) else null, displayText, lesson = false)

    /** Streamt eine Lektion (Session-Lektionskarte). */
    suspend fun speakLesson(examId: String, lessonId: String): Boolean =
        play("Lektion $lessonId", client.lessonUri(examId, lessonId), null, null, lesson = true)

    /**
     * Streamt und spielt. Startet der Stream nicht innerhalb von 12 s (Frage) bzw. 35 s (Lektion) oder faellt er aus, bleibt diese Karte still
     * (kein Fehler, kein Text); die naechste Karte versucht es neu. Liefert true, wenn bis zum Ende gespielt wurde.
     */
    private sealed interface Attempt {
        data object Played : Attempt
        data object Timeout : Attempt
        data class Error(val code: String, val started: Boolean) : Attempt
    }

    private fun codeOf(e: Throwable): String = e.message?.takeIf { it.startsWith("player_error") } ?: "error:${e.javaClass.simpleName}"

    private suspend fun play(label: String, uri: android.net.Uri, timings: (suspend () -> AudioTimings?)?, displayText: String?, lesson: Boolean): Boolean {
        val gen = machine.begin() // alles Vorherige ist damit ungueltig
        sync()
        releasePlayer()
        highlight = Highlight()
        val t0 = SystemClock.elapsedRealtime()
        val total = streamStartTimeoutMs(lesson)
        var outcome: Attempt
        try {
            var attempt = 0
            while (true) {
                val left = total - (SystemClock.elapsedRealtime() - t0)
                outcome = if (left <= 0) Attempt.Timeout else attemptOnce(gen, uri, timings, displayText, label, left, t0)
                val e = outcome as? Attempt.Error
                // stille Wiederholung mit Backoff, solange die Karte aktuell ist und Zeit bleibt; keine Aenderung in der Oberflaeche
                if (e != null && !e.started && attempt < 2 && machine.isCurrent(gen)) {
                    val wait = retryDelayMs(attempt)
                    attempt++
                    if (total - (SystemClock.elapsedRealtime() - t0) > wait + 1_000) {
                        scope.launch { client.report(e.code, "retry $attempt after ${SystemClock.elapsedRealtime() - t0} ms", lesson) }
                        delay(wait)
                        if (!machine.isCurrent(gen)) break
                        continue
                    }
                }
                break
            }
            val stillCurrent = machine.isCurrent(gen)
            releasePlayer()
            val ended = machine.finish(gen)
            sync()
            if (stillCurrent) {
                val now = SystemClock.elapsedRealtime()
                when (val o = outcome) {
                    Attempt.Played -> health.success(now)
                    Attempt.Timeout -> { health.failure(now); reportFailure(audioErrorCode(timeout = true), uri, lesson, now - t0) }
                    is Attempt.Error -> { health.failure(now); reportFailure(o.code, uri, lesson, now - t0) }
                }
                buttonVisible = health.visible
            }
            if (outcome == Attempt.Played && ended && highlight.words.isNotEmpty()) highlight = highlight.copy(spoken = highlight.words.size, active = -1)
            return outcome == Attempt.Played && stillCurrent && ended
        } catch (e: CancellationException) {
            if (machine.isCurrent(gen)) { machine.stop(); releasePlayer() }
            sync()
            throw e
        }
    }

    /** Meldet einen endgueltigen Fehlschlag (mit HTTP-Status/Content-Type aus einem kleinen Diagnose-Request, wenn moeglich). */
    private fun reportFailure(code: String, uri: android.net.Uri, lesson: Boolean, elapsedMs: Long) {
        scope.launch {
            val diagnosed = client.diagnose(app.network.streamClient, uri)
            client.report(diagnosed ?: code, "$code after $elapsedMs ms", lesson)
        }
    }

    private suspend fun attemptOnce(
        gen: Long, uri: android.net.Uri, timings: (suspend () -> AudioTimings?)?, displayText: String?, label: String, timeoutMs: Long, t0: Long,
    ): Attempt = coroutineScope {
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        // Fehler des Players werden als Wert zurueckgegeben, nie als Ausnahme, die die Karte oder die App abbricht
        val playJob = async {
            try {
                playStream(gen, uri, started)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            }
        }
        var startError: Exception? = null
        val ok = try {
            withTimeoutOrNull(timeoutMs) { started.await(); true } ?: false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            startError = e
            false
        }
        if (!ok) {
            playJob.cancel()
            com.jonasschnell.quickscope.data.AppLog.d("Voice", "$label: Stream nicht gestartet (${startError?.let { codeOf(it) } ?: "timeout"})")
            return@coroutineScope startError?.let { Attempt.Error(codeOf(it), false) } ?: Attempt.Timeout
        }
        com.jonasschnell.quickscope.data.AppLog.d("Voice", "$label: Stream laeuft nach ${SystemClock.elapsedRealtime() - t0} ms")
        // Wortzeiten live parallel holen; passen sie nicht exakt zum angezeigten Text, wird nicht hervorgehoben
        val ticker = if (timings != null && displayText != null) launch {
            val t = runCatching { timings() }.getOrNull()?.takeIf { timingsMatch(displayText, it) } ?: return@launch
            highlight = Highlight(t.text, t.words, 0, -1)
            while (isActive) {
                val p = player
                val pos = if (p != null && machine.isCurrent(gen) && runCatching { p.isPlaying }.getOrDefault(false)) wordPosAt(t.words, runCatching { p.currentPosition.toLong() }.getOrDefault(0L)) else null
                if (pos != null && (pos.spoken != highlight.spoken || pos.active != highlight.active)) highlight = highlight.copy(spoken = pos.spoken, active = pos.active)
                delay(40)
            }
        } else null
        try {
            val err = playJob.await()
            if (err == null) Attempt.Played else {
                com.jonasschnell.quickscope.data.AppLog.w("Voice", "$label: Stream abgebrochen", err)
                Attempt.Error(codeOf(err), true)
            }
        } finally {
            ticker?.cancel()
        }
    }

    /** Stoppt alles sofort (neue Karte, Stumm, Verlassen). */
    fun stop() {
        machine.stop()
        cancelHook?.invoke()
        cancelHook = null
        sync()
        highlight = Highlight()
        releasePlayer()
    }

    private fun releasePlayer() {
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
    }

    /** Spielt den Stream (MediaPlayer ohne MediaSession); meldet [started], sobald er wirklich laeuft. */
    private suspend fun playStream(gen: Long, uri: android.net.Uri, started: kotlinx.coroutines.CompletableDeferred<Unit>) = suspendCancellableCoroutine<Unit> { cont ->
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build() // keine MediaSession, keine Systemkarte
        var mp: MediaPlayer? = null
        var paused = false
        val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
            if (!machine.isCurrent(gen)) return@OnAudioFocusChangeListener
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> if (cont.isActive) cont.resume(Unit) // andere App: Runde geht ohne Ton weiter
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                    mp?.let { if (it.isPlaying) { it.pause(); paused = true; machine.pause(gen); sync() } }
                AudioManager.AUDIOFOCUS_GAIN -> mp?.let { if (paused) { it.start(); paused = false; machine.resume(gen); sync() } }
            }
        }
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs).setOnAudioFocusChangeListener(focusListener).build()
        val noisy = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (cont.isActive) cont.resume(Unit) // Kopfhoerer abgezogen: nicht ploetzlich ueber den Lautsprecher
            }
        }
        var registered = false
        fun cleanup() {
            if (registered) runCatching { context.unregisterReceiver(noisy) }
            registered = false
            runCatching { mp?.release() }
            if (player === mp) player = null
            audio.abandonAudioFocusRequest(request)
        }
        cancelHook = { cleanup(); if (cont.isActive) cont.resume(Unit) }
        cont.invokeOnCancellation { cleanup(); cancelHook = null }
        try {
            audio.requestAudioFocus(request)
            context.registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            registered = true
            val p = MediaPlayer()
            mp = p
            player = p
            p.setAudioAttributes(attrs)
            p.setDataSource(context, uri, client.headers(uri))
            p.setOnPreparedListener {
                // verspaetetes "prepared" eines alten Elements verwerfen
                if (!machine.playing(gen)) { cleanup(); started.complete(Unit); if (cont.isActive) cont.resume(Unit); return@setOnPreparedListener }
                sync()
                runCatching { it.playbackParams = PlaybackParams().setSpeed(SPEECH_SPEED).setPitch(1f) }
                it.start()
                started.complete(Unit)
            }
            p.setOnCompletionListener { cleanup(); if (cont.isActive) cont.resume(Unit) }
            p.setOnErrorListener { _, what, extra ->
                val e = java.io.IOException("player_error:$what:$extra")
                cleanup()
                started.completeExceptionally(e)
                if (cont.isActive) cont.resumeWithException(e)
                true
            }
            p.prepareAsync()
        } catch (e: Exception) {
            cleanup()
            started.completeExceptionally(e)
            if (cont.isActive) cont.resumeWithException(e)
        }
    }
}

/** Zustand der Live-Hervorhebung fuer [text]; [spoken] Woerter sind fertig, [active] ist das aktuelle Wort (-1 = keins). */
data class Highlight(val text: String? = null, val words: List<WordTiming> = emptyList(), val spoken: Int = 0, val active: Int = -1)

val LocalHighlight = compositionLocalOf { Highlight() }

/**
 * Die EINE Voice-Instanz der App; beim Verlassen des Bildschirms wird gestoppt. Geht die App in den Hintergrund
 * (Home, App-Wechsel, Bildschirm aus), stoppt die Wiedergabe sofort und gibt den Audiofokus ab; [onBackground] meldet das,
 * damit nach der Rueckkehr nicht automatisch weitergespielt wird (der Play-Knopf bleibt).
 */
@Composable
fun rememberVoice(onBackground: () -> Unit = {}): Voice {
    val context = LocalContext.current
    val voice = remember { (context.applicationContext as QuickscopeApp).voice }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val latest = androidx.compose.runtime.rememberUpdatedState(onBackground)
    DisposableEffect(voice, owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE && shouldStopForLifecycle(false, voice.playState)) {
                voice.stop()
                latest.value()
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); voice.stop() }
    }
    return voice
}
