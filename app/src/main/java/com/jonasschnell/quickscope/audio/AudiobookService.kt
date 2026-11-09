package com.jonasschnell.quickscope.audio

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.jonasschnell.quickscope.BuildConfig
import com.jonasschnell.quickscope.QuickscopeApp
import com.jonasschnell.quickscope.R
import com.jonasschnell.quickscope.data.HeardRequest
import com.jonasschnell.quickscope.logic.SPEECH_SPEED
import com.jonasschnell.quickscope.logic.heardFraction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/** Extras je Lektion im MediaItem (Schluessel). */
object LessonExtras {
    const val EXAM_ID = "examId"
    const val TTS_TEXT = "ttsText"
    const val KEY_POINTS = "keyPoints"
    const val MNEMONIC = "mnemonic"
    const val CHAPTER_ORDER = "chapterOrder"
    const val CHAPTER_TOTAL = "chapterTotal"
    const val EST_SECONDS = "estSeconds"
    /** Session-Extras: Geraetestimme springt gerade ein. */
    const val FALLBACK = "fallback"
}

/**
 * Hoerbuch im Hintergrund: ExoPlayer in einer MediaSession (Foreground-Dienst mediaPlayback, Benachrichtigung mit
 * Play/Pause/Weiter/Zurueck, Audiofokus, Kopfhoerer-/Bluetooth-Tasten, Pause bei Telefonaten). Die Stimme kommt vom Backend
 * (WAV per Streaming mit Anmelde-Header); bei Fehlern oder Offline liest die Geraetestimme den Lektionstext.
 * Der gehoerte Anteil jeder Lektion wird ans Backend gemeldet.
 */
class AudiobookService : MediaSessionService() {
    private var session: MediaSession? = null
    private var tts: TextToSpeech? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val reported = HashMap<String, Double>()
    private val durations = HashMap<String, Long>()
    private lateinit var player: ExoPlayer

    override fun onCreate() {
        super.onCreate()
        val app = application as QuickscopeApp
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        val data = ResolvingDataSource.Factory(http) { spec ->
            spec.withAdditionalHeaders(
                mapOf("Authorization" to "Bearer ${app.auth.token.orEmpty()}", "X-App-Version" to BuildConfig.VERSION_CODE.toString()),
            )
        }
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(data))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.playbackParameters = PlaybackParameters(SPEECH_SPEED, 1f) // feste Geschwindigkeit, Tonhoehe bleibt
        player.addListener(listener)
        session = MediaSession.Builder(this, player).build()
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_launcher_foreground) })
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        player.currentMediaItem?.let { reportPartial(it) }
        session?.run { player.release(); release() }
        session = null
        runCatching { tts?.shutdown() }
        scope.cancel()
        super.onDestroy()
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            val item = player.currentMediaItem ?: return
            when (state) {
                Player.STATE_READY -> player.duration.takeIf { it > 0 }?.let { durations[item.mediaId] = it }
                Player.STATE_ENDED -> report(item, 1.0)
                Player.STATE_IDLE -> reportPartial(item)
            }
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            val old = oldPosition.mediaItem ?: return
            val finished = reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION
            report(old, heardFraction(oldPosition.positionMs, durations[old.mediaId] ?: 0L, finished))
        }

        override fun onPlayerError(error: PlaybackException) {
            fallbackSpeak()
        }
    }

    private fun report(item: MediaItem, fraction: Double) {
        val id = item.mediaId
        if (fraction <= (reported[id] ?: -1.0)) return
        reported[id] = fraction
        val examId = item.mediaMetadata.extras?.getString(LessonExtras.EXAM_ID) ?: return
        val api = (application as QuickscopeApp).network.api
        scope.launch { runCatching { api.heard(examId, id, HeardRequest(fraction)) } }
    }

    private fun reportPartial(item: MediaItem) {
        val f = heardFraction(player.currentPosition, durations[item.mediaId] ?: 0L, false)
        if (f > 0.0) report(item, f)
    }

    // ---- Rueckfall: Geraetestimme liest den Lektionstext (Rate wie die Cloud-Stimme)

    private fun fallbackSpeak() {
        val item = player.currentMediaItem ?: return
        val text = item.mediaMetadata.extras?.getString(LessonExtras.TTS_TEXT).orEmpty()
        val index = player.currentMediaItemIndex
        session?.setSessionExtras(Bundle().apply { putBoolean(LessonExtras.FALLBACK, true) })
        withTts { t ->
            t.setSpeechRate(SPEECH_SPEED)
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onError(utteranceId: String?) { advance(index, item) }
                override fun onDone(utteranceId: String?) { advance(index, item) }
            })
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "lesson-$index")
        }
    }

    /** Nach der Geraetestimme zur naechsten Lektion: Cloud-Wiedergabe wird dort wieder versucht. */
    private fun advance(index: Int, item: MediaItem) {
        scope.launch {
            report(item, 1.0)
            if (index + 1 < player.mediaItemCount) {
                session?.setSessionExtras(Bundle().apply { putBoolean(LessonExtras.FALLBACK, false) })
                player.seekTo(index + 1, 0)
                player.prepare()
                player.play()
            } else {
                player.stop()
            }
        }
    }

    private fun withTts(block: (TextToSpeech) -> Unit) {
        tts?.let { block(it); return }
        var created: TextToSpeech? = null
        created = TextToSpeech(applicationContext) { status ->
            val t = created
            if (status == TextToSpeech.SUCCESS && t != null) {
                runCatching { t.setLanguage(Locale.GERMANY) }
                tts = t
                block(t)
            }
        }
    }
}
