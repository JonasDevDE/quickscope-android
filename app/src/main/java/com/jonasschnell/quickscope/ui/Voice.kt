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
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
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
import com.jonasschnell.quickscope.logic.CLOUD_WAIT_MS
import com.jonasschnell.quickscope.logic.PlaybackMachine
import com.jonasschnell.quickscope.logic.PrefetchPlanner
import com.jonasschnell.quickscope.logic.SPEECH_SPEED
import com.jonasschnell.quickscope.logic.VoicePart
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

    /** Cloud-Stimme wird gerade geladen (kleine Ladeanzeige, blockiert nichts). */
    var loading by mutableStateOf(false)
        private set

    /** Audio kam nicht (Timeout, 503, offline): still bleiben, "Audio gerade nicht verfuegbar" und "Nochmal". */
    var unavailable by mutableStateOf(false)
        private set

    /** Live-Hervorhebung des gerade gesprochenen Wortes (leer, wenn nichts zu markieren ist). */
    var highlight by mutableStateOf(Highlight())
        private set

    private var player: MediaPlayer? = null

    /** Naechste Aufgaben (aktuelle + 2) vorab anfordern und laden. */
    fun prefetch(examId: String, ids: List<String>, index: Int) {
        val fresh = planner.next(ids, index)
        if (fresh.isEmpty()) return
        scope.launch {
            cache.requestPrefetch(examId, fresh)
            for (id in fresh) launch { cache.timings(examId, id, VoicePart.Question) }
            for (id in fresh) launch {
                try {
                    cache.fetch(examId, id, VoicePart.Question)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    planner.forget(id) // spaeter erneut versuchen
                }
            }
        }
    }

    /** Liest die Frage vor. [displayText] ist der angezeigte Fragetext: nur wenn er exakt zu den Wortzeiten passt, wird hervorgehoben. */
    suspend fun speak(examId: String, itemId: String, displayText: String?) {
        play(
            label = "Frage $itemId",
            fetch = { cache.fetch(examId, itemId, VoicePart.Question) },
            timings = if (displayText != null) ({ cache.timings(examId, itemId, VoicePart.Question) }) else null,
            displayText = displayText,
        )
    }

    /** Spielt eine Lektion (Session-Lektionskarte) ab. */
    suspend fun speakLesson(examId: String, lessonId: String) {
        play(label = "Lektion $lessonId", fetch = { cache.fetchLesson(examId, lessonId) }, timings = null, displayText = null)
    }

    private suspend fun play(label: String, fetch: suspend () -> File, timings: (suspend () -> AudioTimings?)?, displayText: String?) {
        val gen = machine.begin() // alles Vorherige ist damit ungueltig
        releasePlayer()
        highlight = Highlight()
        unavailable = false
        val t0 = SystemClock.elapsedRealtime()
        try {
            loading = true
            val file = try {
                withTimeoutOrNull(CLOUD_WAIT_MS) { fetch() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Voice", "$label: Audio nicht geladen", e)
                null
            }
            loading = false
            if (!machine.isCurrent(gen)) return // inzwischen etwas anderes gestartet oder gestoppt
            if (file == null) {
                unavailable = true
                machine.finish(gen)
                return
            }
            Log.d("Voice", "$label: Audio bereit nach ${SystemClock.elapsedRealtime() - t0} ms")
            val usable = timings?.let { t -> t()?.takeIf { displayText != null && timingsMatch(displayText, it) } }
            if (!machine.isCurrent(gen)) return
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
            if (machine.finish(gen) && usable != null) highlight = highlight.copy(spoken = usable.words.size, active = -1)
        } catch (e: CancellationException) {
            loading = false
            if (machine.isCurrent(gen)) { machine.stop(); releasePlayer() }
            throw e
        } catch (e: Exception) {
            Log.w("Voice", "$label: Wiedergabe fehlgeschlagen", e)
            loading = false
            highlight = Highlight()
            if (machine.isCurrent(gen)) { unavailable = true; machine.finish(gen) }
        }
    }

    /** Stoppt alles sofort (neue Karte, Stumm, Verlassen). */
    fun stop() {
        machine.stop()
        loading = false
        highlight = Highlight()
        releasePlayer()
    }

    private fun releasePlayer() {
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
    }

    private suspend fun playFile(gen: Long, file: File) = suspendCancellableCoroutine<Unit> { cont ->
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        var mp: MediaPlayer? = null
        var paused = false
        val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
            if (!machine.isCurrent(gen)) return@OnAudioFocusChangeListener
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> if (cont.isActive) cont.resume(Unit) // andere App: Runde geht ohne Ton weiter
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                    mp?.let { if (it.isPlaying) { it.pause(); paused = true; machine.pause(gen) } }
                AudioManager.AUDIOFOCUS_GAIN -> mp?.let { if (paused) { it.start(); paused = false; machine.resume(gen) } }
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
        cont.invokeOnCancellation { cleanup() }
        try {
            audio.requestAudioFocus(request)
            context.registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            registered = true
            val p = MediaPlayer()
            mp = p
            player = p
            p.setAudioAttributes(attrs)
            p.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            p.setDataSource(file.absolutePath)
            p.setOnPreparedListener {
                // verspaetetes "prepared" eines alten Elements verwerfen
                if (!machine.playing(gen)) { cleanup(); if (cont.isActive) cont.resume(Unit); return@setOnPreparedListener }
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

/** Die EINE Voice-Instanz der App; beim Verlassen des Bildschirms wird gestoppt. */
@Composable
fun rememberVoice(): Voice {
    val context = LocalContext.current
    val voice = remember { (context.applicationContext as QuickscopeApp).voice }
    DisposableEffect(voice) { onDispose { voice.stop() } }
    return voice
}
