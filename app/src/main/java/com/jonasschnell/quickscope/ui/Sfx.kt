package com.jonasschnell.quickscope.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.SoundPool
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.jonasschnell.quickscope.QuickscopeApp
import com.jonasschnell.quickscope.logic.FeedbackTuning
import com.jonasschnell.quickscope.logic.Note
import com.jonasschnell.quickscope.logic.Tones
import com.jonasschnell.quickscope.logic.synth
import com.jonasschnell.quickscope.logic.wavBytes
import java.io.File

private const val TAG = "Sfx"

/**
 * Kurze synthetische Toene (Sinus-Arpeggios, ohne Asset-Dateien). Sie werden beim App-Start als WAV in den Cache geschrieben
 * und in einen SoundPool geladen (schneller Start, kein Audiofokus, laeuft auch neben dem Vorlesen). Faellt SoundPool aus,
 * spielt ein AudioTrack; Fehler werden geloggt statt verschluckt. Nur bei Medienlautstaerke 0 bleibt es still.
 */
class Sfx(context: Context) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val pcm = HashMap<List<Note>, ShortArray>()
    private val soundIds = HashMap<List<Note>, Int>()
    private val loaded = HashSet<Int>()
    private val pool: SoundPool? = runCatching {
        SoundPool.Builder().setMaxStreams(4)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .build()
    }.onFailure { com.jonasschnell.quickscope.data.AppLog.w(TAG, "SoundPool nicht verfuegbar", it) }.getOrNull()

    init {
        pool?.setOnLoadCompleteListener { _, id, status -> if (status == 0) loaded.add(id) else com.jonasschnell.quickscope.data.AppLog.w(TAG, "Laden fehlgeschlagen: $status") }
        val dir = File(context.cacheDir, "sfx").apply { mkdirs() }
        for ((i, notes) in Tones.all.withIndex()) {
            val samples = synth(notes)
            pcm[notes] = samples
            runCatching {
                val file = File(dir, "tone_$i.wav").apply { writeBytes(wavBytes(samples)) }
                pool?.load(file.path, 1)?.let { soundIds[notes] = it }
            }.onFailure { com.jonasschnell.quickscope.data.AppLog.w(TAG, "Ton $i nicht vorbereitet", it) }
        }
    }

    fun play(notes: List<Note>) {
        // Nur bei Medienlautstaerke 0 ist nichts zu hoeren; Klingelton-Modus (stumm/vibrieren) und das Vorlesen sind egal
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) <= 0) return
        val id = soundIds[notes]
        if (pool != null && id != null && id in loaded) {
            if (pool.play(id, 1f, 1f, 1, 0, 1f) != 0) return
            com.jonasschnell.quickscope.data.AppLog.w(TAG, "SoundPool.play lieferte 0, Rueckfall auf AudioTrack")
        }
        playTrack(notes)
    }

    private fun playTrack(notes: List<Note>) {
        try {
            val samples = pcm.getOrPut(notes) { synth(notes) }
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(FeedbackTuning.SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(samples.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(samples, 0, samples.size)
            track.setNotificationMarkerPosition(samples.size - 1)
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack) { runCatching { t.release() } }
                override fun onPeriodicNotification(t: AudioTrack) {}
            })
            track.play()
        } catch (e: Exception) {
            com.jonasschnell.quickscope.data.AppLog.w(TAG, "Ton konnte nicht abgespielt werden", e)
        }
    }
}

@Composable
fun rememberSfx(): Sfx {
    val context = LocalContext.current
    return remember { (context.applicationContext as QuickscopeApp).sfx }
}
