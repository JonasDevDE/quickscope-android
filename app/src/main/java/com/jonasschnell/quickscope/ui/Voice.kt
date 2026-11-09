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
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.jonasschnell.quickscope.QuickscopeApp
import com.jonasschnell.quickscope.data.AudioUnavailable
import com.jonasschnell.quickscope.logic.CloudHealth
import com.jonasschnell.quickscope.logic.DEFAULT_SPEED
import com.jonasschnell.quickscope.logic.PrefetchPlanner
import com.jonasschnell.quickscope.logic.VoiceKind
import com.jonasschnell.quickscope.logic.VoicePart
import com.jonasschnell.quickscope.logic.chooseVoice
import com.jonasschnell.quickscope.logic.normalizeSpeed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
class Voice(private val context: Context, private val scope: CoroutineScope, initialSpeed: Float) {
    private val app = context.applicationContext as QuickscopeApp
    private val cache = app.audioCache
    private val health = CloudHealth()
    private val planner = PrefetchPlanner(3)
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Aktuelle Stufe (1x / 1.15x / 1.3x). */
    var speed by mutableFloatStateOf(normalizeSpeed(initialSpeed))
        private set

    /** Cloud-Stimme wird gerade geladen (kleine Ladeanzeige, blockiert nichts). */
    var loading by mutableStateOf(false)
        private set

    /** Es liest gerade die Geraetestimme vor (Rueckfall); dann wird ein kleiner Hinweis gezeigt. */
    var usingDevice by mutableStateOf(false)
        private set

    private var player: MediaPlayer? = null
    private var tts: TextToSpeech? = null

    fun selectSpeed(value: Float) {
        speed = normalizeSpeed(value)
        player?.let { p -> runCatching { if (p.isPlaying) p.playbackParams = PlaybackParams().setSpeed(speed).setPitch(1f) } }
    }

    /** Naechste Aufgaben (aktuelle + 2) vorab anfordern und laden. */
    fun prefetch(examId: String, ids: List<String>, index: Int) {
        if (!health.available(SystemClock.elapsedRealtime())) return
        val fresh = planner.next(ids, index)
        if (fresh.isEmpty()) return
        scope.launch {
            cache.requestPrefetch(examId, fresh)
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

    /** Liest [part] der Aufgabe vor und kehrt zurueck, wenn fertig. Bei Abbruch der Coroutine wird die Wiedergabe gestoppt. */
    suspend fun speak(examId: String, itemId: String, part: VoicePart, fallbackText: String) {
        stop()
        if (chooseVoice(health, SystemClock.elapsedRealtime()) == VoiceKind.Cloud) {
            try {
                loading = true
                val file = cache.fetch(examId, itemId, part)
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
                health.failed(SystemClock.elapsedRealtime())
            }
        }
        usingDevice = true
        speakDevice(fallbackText)
    }

    fun stop() {
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
                runCatching { it.playbackParams = PlaybackParams().setSpeed(speed).setPitch(1f) }
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

    private suspend fun speakDevice(text: String) {
        val t = ensureTts() ?: return
        suspendCancellableCoroutine<Unit> { cont ->
            t.setSpeechRate(speed.coerceAtLeast(DEFAULT_SPEED))
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { if (cont.isActive) cont.resume(Unit) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { if (cont.isActive) cont.resume(Unit) }
            })
            cont.invokeOnCancellation { runCatching { t.stop() } }
            if (t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "quickscope") != TextToSpeech.SUCCESS && cont.isActive) cont.resume(Unit)
        }
    }
}

@Composable
fun rememberVoice(initialSpeed: Float): Voice {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val voice = remember { Voice(context, scope, initialSpeed) }
    DisposableEffect(voice) { onDispose { voice.release() } }
    return voice
}
