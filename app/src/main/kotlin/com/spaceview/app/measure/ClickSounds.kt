package com.spaceview.app.measure

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Soft audio cues for AR. Android uses sound, not vibration, during an ARCore session: vibration
 * disturbs the IMU that tracking relies on (docs/research/ui-ux.md §2.1). The clicks are
 * synthesised (no audio assets in the APK) and played from static AudioTracks.
 *
 * Create on the main thread when the AR screen opens; [release] when it closes.
 */
class ClickSounds {
    private val tick: AudioTrack? = build(tone(2200.0, 0.028))
    private val done: AudioTrack? = build(tone(1700.0, 0.03) + ShortArray(SAMPLE_RATE * 45 / 1000) + tone(2500.0, 0.04))

    /** A point was placed. */
    fun tick() = play(tick)

    /** The measurement is complete. */
    fun done() = play(done)

    fun release() {
        tick?.release()
        done?.release()
    }

    private fun play(track: AudioTrack?) {
        track ?: return
        runCatching {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
            track.reloadStaticData()
            track.play()
        }
    }

    private fun build(pcm: ShortArray): AudioTrack? = runCatching {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        track.write(pcm, 0, pcm.size)
        track.setVolume(VOLUME)
        track
    }.getOrNull()

    private fun tone(freq: Double, seconds: Double): ShortArray {
        val n = (SAMPLE_RATE * seconds).toInt()
        return ShortArray(n) { i ->
            val t = i.toDouble() / SAMPLE_RATE
            val envelope = exp(-t * 90.0) * (1 - exp(-t * 4000.0))
            (sin(2 * PI * freq * t) * envelope * Short.MAX_VALUE * 0.6).toInt().toShort()
        }
    }

    private operator fun ShortArray.plus(other: ShortArray): ShortArray {
        val out = ShortArray(size + other.size)
        System.arraycopy(this, 0, out, 0, size)
        System.arraycopy(other, 0, out, size, other.size)
        return out
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val VOLUME = 0.5f
    }
}
