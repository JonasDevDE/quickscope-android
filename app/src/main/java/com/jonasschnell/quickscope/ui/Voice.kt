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
import com.jonasschnell.quickscope.logic.PlaybackMachine
import com.jonasschnell.quickscope.logic.PrefetchPlanner
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
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
    private val cache = app.audioCache
    private val planner = PrefetchPlanner(3)
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

    /** Live-Hervorhebung des gerade gesprochenen Wortes (leer, wenn nichts zu markieren ist). */
    var highlight by mutableStateOf(Highlight())
        private set

    private var player: MediaPlayer? = null

    /** Weckt wartende Wiederholungen sofort (Tipp auf den Knopf) oder beendet sie (Stopp). */
    private val kick = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    /** Tipp waehrend des Ladens: sofort neu versuchen. */
    fun retryNow() {
        kick.trySend(Unit)
    }

    /** Beendet die laufende Wiedergabe sofort (gibt Player, Audiofokus und Empfaenger frei). */
    private var cancelHook: (() -> Unit)? = null

    /** Naechste Aufgaben (aktuelle + 2) vorab anfordern und laden. */
    fun prefetch(examId: String, ids: List<String>, index: Int) {
        val fresh = planner.next(ids, index)
        if (fresh.isEmpty()) return
        scope.launch {
            cache.requestPrefetch(examId, fresh)
            for (id in fresh) launch { cache.timings(examId, id, VoicePart.Question) }
            for (id in fresh) launch {
                try {
                    cache.fetchThrottled(examId, id, VoicePart.Question)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    planner.forget(id) // spaeter erneut versuchen
                }
            }
        }
    }

    /** Liest die Frage vor. [displayText] ist der angezeigte Fragetext: nur wenn er exakt zu den Wortzeiten passt, wird hervorgehoben. */
    suspend fun speak(examId: String, itemId: String, displayText: String?): Boolean =
        play(
            label = "Frage $itemId",
            fetch = { cache.fetch(examId, itemId, VoicePart.Question) },
            timings = if (displayText != null) ({ cache.timings(examId, itemId, VoicePart.Question) }) else null,
            displayText = displayText,
        )

    /** Spielt eine Lektion (Session-Lektionskarte) ab. */
    suspend fun speakLesson(examId: String, lessonId: String): Boolean =
        play(label = "Lektion $lessonId", fetch = { cache.fetchLesson(examId, lessonId) }, timings = null, displayText = null)

    /**
     * Laedt und spielt. Fehler (503, 429, Timeout, Netz) werden unsichtbar mit Backoff wiederholt (1, 2, 4, 8 ... 60 s, Retry-After
     * wird beachtet), waehrend der Knopf den Spinner zeigt; ein Tipp versucht sofort neu. Endet erst, wenn gespielt wurde oder die
     * Wiedergabe ersetzt/gestoppt ist (Itemwechsel, Hintergrund). Liefert true, wenn bis zum Ende gespielt wurde.
     */
    private suspend fun play(label: String, fetch: suspend () -> File, timings: (suspend () -> AudioTimings?)?, displayText: String?): Boolean {
        val gen = machine.begin() // alles Vorherige ist damit ungueltig
        sync()
        releasePlayer()
        highlight = Highlight()
        val t0 = SystemClock.elapsedRealtime()
        var attempt = 0
        try {
            while (true) {
                if (!machine.isCurrent(gen)) { return false } // ersetzt oder gestoppt
                var file: File? = null
                var retryAfter: Long? = null
                try {
                    file = fetch()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    retryAfter = (e as? com.jonasschnell.quickscope.data.AudioUnavailable)?.retryAfterMs
                    com.jonasschnell.quickscope.data.AppLog.w("Voice", "$label: Versuch ${attempt + 1} fehlgeschlagen", e)
                }
                if (file != null) {
                    if (!machine.isCurrent(gen)) return false
                    com.jonasschnell.quickscope.data.AppLog.d("Voice", "$label: Audio bereit nach ${SystemClock.elapsedRealtime() - t0} ms")
                    val usable = timings?.let { t -> runCatching { t() }.getOrNull()?.takeIf { displayText != null && timingsMatch(displayText, it) } }
                    if (!machine.isCurrent(gen)) return false
                    var played = true
                    try {
                        coroutineScope {
                            val ticker = usable?.let { t ->
                                highlight = Highlight(t.text, t.words, 0, -1)
                                launch {
                                    while (isActive) {
                                        val p = player
                                        val pos = if (p != null && machine.isCurrent(gen) && runCatching { p.isPlaying }.getOrDefault(false)) wordPosAt(t.words, runCatching { p.currentPosition.toLong() }.getOrDefault(0L)) else null
                                        if (pos != null && (pos.spoken != highlight.spoken || pos.active != highlight.active)) highlight = highlight.copy(spoken = pos.spoken, active = pos.active)
                                        delay(40)
                                    }
                                }
                            }
                            try {
                                playFile(gen, file)
                            } finally {
                                ticker?.cancel()
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Datei evtl. defekt: verwerfen und erneut laden
                        com.jonasschnell.quickscope.data.AppLog.w("Voice", "$label: Wiedergabe fehlgeschlagen", e)
                        runCatching { file.delete() }
                        played = false
                    }
                    if (played) {
                        val stillCurrent = machine.isCurrent(gen)
                        val ended = machine.finish(gen)
                        sync()
                        if (ended && usable != null) highlight = highlight.copy(spoken = usable.words.size, active = -1)
                        return stillCurrent && ended
                    }
                }
                // unsichtbar erneut versuchen: Backoff oder Weckruf (Tipp, Stopp)
                val wait = com.jonasschnell.quickscope.logic.retryDelayMs(attempt++, retryAfter)
                kotlinx.coroutines.withTimeoutOrNull(wait) { kick.receive() }
            }
        } catch (e: CancellationException) {
            if (machine.isCurrent(gen)) { machine.stop(); releasePlayer() }
            sync()
            throw e
        } finally {
        }
    }

    /** Stoppt alles sofort (neue Karte, Stumm, Verlassen). */
    fun stop() {
        machine.stop()
        kick.trySend(Unit) // wartende Wiederholungen beenden
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

    private suspend fun playFile(gen: Long, file: File) = suspendCancellableCoroutine<Unit> { cont ->
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
            p.setDataSource(file.absolutePath)
            p.setOnPreparedListener {
                // verspaetetes "prepared" eines alten Elements verwerfen
                if (!machine.playing(gen)) { cleanup(); if (cont.isActive) cont.resume(Unit); return@setOnPreparedListener }
                sync()
                runCatching { it.playbackParams = PlaybackParams().setSpeed(SPEECH_SPEED).setPitch(1f) }
                it.start()
            }
            p.setOnCompletionListener { cleanup(); if (cont.isActive) cont.resume(Unit) }
            p.setOnErrorListener { _, what, _ -> cleanup(); if (cont.isActive) cont.resumeWithException(java.io.IOException("Wiedergabefehler $what")); true }
            p.prepareAsync()
        } catch (e: Exception) {
            cleanup()
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
