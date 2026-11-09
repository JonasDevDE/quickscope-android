package com.jonasschnell.quickscope.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.jonasschnell.quickscope.logic.FeedbackTuning
import com.jonasschnell.quickscope.logic.Note
import com.jonasschnell.quickscope.logic.synth

/**
 * Kurze synthetische Toene (Sinus-Arpeggios, ohne Asset-Dateien) ueber AudioTrack. Die Lautstaerke folgt der
 * Medienlautstaerke; bei Lautstaerke 0 oder stummem/vibrierendem Telefon bleibt es still.
 */
class Sfx(context: Context) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val cache = HashMap<List<Note>, ShortArray>()

    private fun audible(): Boolean =
        audio.getStreamVolume(AudioManager.STREAM_MUSIC) > 0 && audio.ringerMode == AudioManager.RINGER_MODE_NORMAL

    fun play(notes: List<Note>) {
        if (!audible()) return
        runCatching {
            val pcm = cache.getOrPut(notes) { synth(notes) }
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(FeedbackTuning.SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(pcm, 0, pcm.size)
            track.setNotificationMarkerPosition(pcm.size - 1)
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack) { runCatching { t.release() } }
                override fun onPeriodicNotification(t: AudioTrack) {}
            })
            track.play()
        }
    }
}

@Composable
fun rememberSfx(): Sfx {
    val context = LocalContext.current
    return remember { Sfx(context) }
}
