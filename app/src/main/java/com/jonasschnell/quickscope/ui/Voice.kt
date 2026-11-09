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
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.jonasschnell.quickscope.QuickscopeApp
import com.jonasschnell.quickscope.data.AudioUnavailable
import com.jonasschnell.quickscope.data.WordTiming
import com.jonasschnell.quickscope.logic.splitWords
import com.jonasschnell.quickscope.logic.timingsMatch
import com.jonasschnell.quickscope.logic.wordAtChar
import com.jonasschnell.quickscope.logic.wordPosAt
import com.jonasschnell.quickscope.logic.CLOUD_WAIT_MS
import com.jonasschnell.quickscope.logic.CloudHealth
import com.jonasschnell.quickscope.logic.SPEECH_SPEED
import com.jonasschnell.quickscope.logic.PrefetchPlanner
import com.jonasschnell.quickscope.logic.VoiceKind
import com.jonasschnell.quickscope.logic.VoicePart
import com.jonasschnell.quickscope.logic.chooseVoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Vorlesen: Cloud-Stimme (WAV vom Backend, lokal im Cache, MediaPlayer mit Audiofokus) und bei Offline/Fehler/503
 * automatischer Rueckfall auf die Geraetestimme (TextToSpeech). Die Wiedergabe laeuft, solange die Runde laeuft
 * (auch bei ausgeschaltetem Bildschirm, per Wake-Lock des Players), ohne Foreground-Dienst.
 */
class Voice(private val context: Context, private val scope: CoroutineScope) {
    private val app = context.applicationContext as QuickscopeApp
    private val cache = app.audioCache
    private val health = CloudHealth()
    private val planner = PrefetchPlanner(3)
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Cloud-Stimme wird gerade geladen (kleine Ladeanzeige, blockiert nichts). */
    var loading by mutableStateOf(false)
        private set

    /** Es liest gerade die Geraetestimme vor (Rueckfall); dann wird ein kleiner Hinweis gezeigt. */
    var usingDevice by mutableStateOf(false)
        private set

    /** Live-Hervorhebung des gerade gesprochenen Wortes (leer, wenn nichts zu markieren ist). */
    var highlight by mutableStateOf(Highlight())
        private set

    private var player: MediaPlayer? = null
    private var tts: TextToSpeech? = null

    /** Naechste Aufgaben (aktuelle + 2) vorab anfordern und laden. */
    fun prefetch(examId: String, ids: List<String>, index: Int) {
        if (!health.available(SystemClock.elapsedRealtime())) return
        val fresh = planner.next(ids, index)
        if (fresh.isEmpty()) return
        scope.launch {
            cache.requestPrefetch(examId, fresh)
            for (id in fresh) launch { cache.timings(examId, id, VoicePart.Question) }
            for (id in fresh) for (part in VoicePart.entries) launch {
                try {
                    cache.fetch(examId, id, part)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (e is AudioUnavailable || e is IOException) health.failed(SystemClock.elapsedRealtime())
                    planner.forget(id)
                }
            }
        }
    }

    /**
     * Liest [part] der Aufgabe vor und kehrt zurueck, wenn fertig. [displayText] ist der angezeigte Fragetext: nur wenn
     * er exakt zu den Wortzeiten (bzw. zum gesprochenen Text) passt, wird live hervorgehoben.
     * Bei Abbruch der Coroutine wird die Wiedergabe gestoppt.
     */
    suspend fun speak(examId: String, itemId: String, part: VoicePart, fallbackText: String, displayText: String? = null) {
        stop()
        if (chooseVoice(health, SystemClock.elapsedRealtime()) == VoiceKind.Cloud) {
            try {
                loading = true
                val file = withTimeoutOrNull(CLOUD_WAIT_MS) { cache.fetch(examId, itemId, part) } ?: throw SlowCloud()
                loading = false
                val timings = if (part == VoicePart.Question && displayText != null) cache.timings(examId, itemId, part) else null
                val usable = if (timings != null && displayText != null && timingsMatch(displayText, timings)) timings else null
                coroutineScope {
                    val ticker = usable?.let { t ->
                        highlight = Highlight(t.text, t.words, 0, -1)
                        launch {
                            while (isActive) {
                                val p = player
                                val pos = if (p != null && runCatching { p.isPlaying }.getOrDefault(false)) wordPosAt(t.words, runCatching { p.currentPosition.toLong() }.getOrDefault(0L)) else null
                                // nur bei Wortwechsel neu zeichnen
                                if (pos != null && (pos.spoken != highlight.spoken || pos.active != highlight.active)) highlight = highlight.copy(spoken = pos.spoken, active = pos.active)
                                delay(40)
                            }
                        }
                    }
                    try {
                        playFile(file)
                    } finally {
                        ticker?.cancel()
                    }
                }
                if (usable != null) highlight = highlight.copy(spoken = usable.words.size, active = -1)
                health.ok()
                usingDevice = false
                return
            } catch (e: CancellationException) {
                loading = false
                throw e
            } catch (e: Exception) {
                loading = false
                highlight = Highlight()
                if (e !is SlowCloud) health.failed(SystemClock.elapsedRealtime())
            }
        }
        usingDevice = true
        speakDevice(fallbackText, if (displayText == fallbackText) displayText else null)
    }

    /** Spielt eine Lektion (Session-Lektionskarte) ab; sonst wie [speak], ohne Hervorhebung. Rueckfall: Geraetestimme mit dem Lektionstext. */
    suspend fun speakLesson(examId: String, lessonId: String, text: String) {
        stop()
        if (chooseVoice(health, SystemClock.elapsedRealtime()) == VoiceKind.Cloud) {
            try {
                loading = true
                val file = withTimeoutOrNull(CLOUD_WAIT_MS) { cache.fetchLesson(examId, lessonId) } ?: throw SlowCloud()
                loading = false
                playFile(file)
                health.ok()
                usingDevice = false
                return
            } catch (e: CancellationException) {
                loading = false
                throw e
            } catch (e: Exception) {
                loading = false
                if (e !is SlowCloud) health.failed(SystemClock.elapsedRealtime())
            }
        }
        usingDevice = true
        speakDevice(text, null)
    }

    fun stop() {
        highlight = Highlight()
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
        tts?.let { runCatching { it.stop() } }
    }

    fun release() {
        stop()
        tts?.let { runCatching { it.shutdown() } }
        tts = null
    }

    // ---- Cloud-Wiedergabe

    private suspend fun playFile(file: File) = suspendCancellableCoroutine<Unit> { cont ->
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        var mp: MediaPlayer? = null
        var paused = false
        val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> if (cont.isActive) cont.resume(Unit) // z. B. andere App: Runde geht ohne Ton weiter
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                    mp?.let { if (it.isPlaying) { it.pause(); paused = true } }
                AudioManager.AUDIOFOCUS_GAIN -> mp?.let { if (paused) { it.start(); paused = false } }
            }
        }
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs).setOnAudioFocusChangeListener(focusListener).build()
        val noisy = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (cont.isActive) cont.resume(Unit) // Kopfhoerer abgezogen: nicht ploetzlich laut ueber den Lautsprecher
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
                runCatching { it.playbackParams = PlaybackParams().setSpeed(SPEECH_SPEED).setPitch(1f) }
                it.start()
            }
            p.setOnCompletionListener { cleanup(); if (cont.isActive) cont.resume(Unit) }
            p.setOnErrorListener { _, what, _ -> cleanup(); if (cont.isActive) cont.resumeWithException(IOException("Wiedergabefehler $what")); true }
            p.prepareAsync()
        } catch (e: Exception) {
            cleanup()
            if (cont.isActive) cont.resumeWithException(e)
        }
    }

    // ---- Geraetestimme (Rueckfall)

    private suspend fun ensureTts(): TextToSpeech? = tts ?: suspendCancellableCoroutine { cont ->
        var created: TextToSpeech? = null
        created = TextToSpeech(context.applicationContext) { status ->
            val t = created
            if (status == TextToSpeech.SUCCESS && t != null) {
                val result = runCatching { t.setLanguage(Locale.GERMANY) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
                if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) tts = t
            }
            if (cont.isActive) cont.resume(tts)
        }
        cont.invokeOnCancellation { runCatching { created?.shutdown() } }
    }

    private suspend fun speakDevice(text: String, highlightText: String?) {
        val t = ensureTts() ?: return
        val words = highlightText?.let { splitWords(it) }.orEmpty()
        if (highlightText != null && words.isNotEmpty()) highlight = Highlight(highlightText, words, 0, -1)
        val main = Handler(Looper.getMainLooper())
        suspendCancellableCoroutine<Unit> { cont ->
            t.setSpeechRate(SPEECH_SPEED)
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { if (cont.isActive) cont.resume(Unit) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { if (cont.isActive) cont.resume(Unit) }
                // Genaue Position der Geraetestimme (ab API 26): Zeichenbereich im gesprochenen Text
                override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                    if (words.isEmpty()) return
                    val idx = wordAtChar(words, start)
                    if (idx >= 0) main.post { highlight = highlight.copy(spoken = idx, active = idx) }
                }
            })
            cont.invokeOnCancellation { runCatching { t.stop() } }
            if (t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "quickscope") != TextToSpeech.SUCCESS && cont.isActive) cont.resume(Unit)
        }
        if (words.isNotEmpty()) highlight = highlight.copy(spoken = words.size, active = -1)
    }
}

/** Cloud-Audio war nach [CLOUD_WAIT_MS] noch nicht da: dieses Item liest die Geraetestimme (kein dauerhafter Ausfall). */
private class SlowCloud : Exception()

/** Zustand der Live-Hervorhebung fuer [text]; [spoken] Woerter sind fertig, [active] ist das aktuelle Wort (-1 = keins). */
data class Highlight(val text: String? = null, val words: List<WordTiming> = emptyList(), val spoken: Int = 0, val active: Int = -1)

val LocalHighlight = compositionLocalOf { Highlight() }

@Composable
fun rememberVoice(): Voice {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val voice = remember { Voice(context, scope) }
    DisposableEffect(voice) { onDispose { voice.release() } }
    return voice
}
