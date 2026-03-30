package chromahub.rhythm.app.infrastructure.audio.siphon

import androidx.media3.common.C
import androidx.media3.common.Format

data class SiphonAudioFormat(
    val sampleRate: Int,      // e.g., 44100, 48000, 96000, 192000
    val bitDepth: Int,        // e.g., 16, 24, 32
    val channelCount: Int,    // e.g., 2
    val encoding: Int         // AudioFormat.ENCODING_PCM_16BIT, _24BIT_PACKED, _32BIT
) {
    // Convert ExoPlayer Format to SiphonAudioFormat
    companion object {
        fun fromExoPlayerFormat(format: Format): SiphonAudioFormat {
            val sampleRate = format.sampleRate.takeIf { it != Format.NO_VALUE } 
                ?: 44100
            val bitDepth = when (format.pcmEncoding) {
                C.ENCODING_PCM_16BIT -> 16
                C.ENCODING_PCM_24BIT -> 24
                C.ENCODING_PCM_32BIT -> 32
                C.ENCODING_PCM_FLOAT -> 32
                else -> 16  // safe default — NEVER null, fixes the NPE
            }
            val channels = format.channelCount.takeIf { it != Format.NO_VALUE } ?: 2
            return SiphonAudioFormat(sampleRate, bitDepth, channels, 
                format.pcmEncoding.takeIf { it != Format.NO_VALUE } 
                    ?: C.ENCODING_PCM_16BIT)
        }
    }
}
