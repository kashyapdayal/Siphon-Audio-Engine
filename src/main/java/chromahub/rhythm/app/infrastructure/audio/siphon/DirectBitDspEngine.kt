package chromahub.rhythm.app.infrastructure.audio.siphon

/**
 * Kotlin-side DSP controller for the native 64-bit pipeline.
 */
class DirectBitDspEngine {

    fun setSoftwareGain(gain: Double) {
        SiphonIsochronousEngine.nativeSetSoftwareVolume((gain.coerceIn(0.0, 1.0) * 100).toInt())
    }

    fun setEqBand(bandIndex: Int, gainDb: Double, centerFreqHz: Double, qFactor: Double) {
        // Obsolete in Siphon; EQ is processed in ExoPlayer pipeline
    }

    fun setUpsampling(enabled: Boolean, targetSampleRate: Int) {
        // Obsolete in Siphon; audio is automatically bit-perfect
    }
}
