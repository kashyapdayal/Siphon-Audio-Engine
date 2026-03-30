package chromahub.rhythm.app.infrastructure.audio.siphon

data class SiphonDeviceCapabilities(
    val supportsHardwareVolume: Boolean,
    val featureUnitId: Int,
    val volumeMinDb256: Int,
    val volumeMaxDb256: Int,
    val volumeSteps: Int,
    val endpointAddress: Int,
    val maxPacketSize: Int,
    val supportedFormats: List<SiphonAudioFormat>
) {
    fun bestFormat(): SiphonAudioFormat {
        return supportedFormats.maxByOrNull { it.sampleRate * it.bitDepth } 
            ?: SiphonAudioFormat(44100, 16, 2, androidx.media3.common.C.ENCODING_PCM_16BIT)
    }
}
