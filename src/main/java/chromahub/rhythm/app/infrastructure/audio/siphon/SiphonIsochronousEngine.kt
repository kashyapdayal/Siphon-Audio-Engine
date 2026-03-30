package chromahub.rhythm.app.infrastructure.audio.siphon

object SiphonIsochronousEngine {
    @Volatile
    var deviceLostListener: (() -> Unit)? = null

    @Volatile
    var firstTransferListener: (() -> Unit)? = null

    @JvmStatic
    fun notifyDeviceLost() {
        deviceLostListener?.invoke()
    }

    @JvmStatic
    fun notifyFirstTransferStarted() {
        firstTransferListener?.invoke()
    }
    
    @Volatile
    var isNativeLoaded = false
        private set

    init {
        try {
            System.loadLibrary("siphon_engine")
            isNativeLoaded = true
        } catch (e: UnsatisfiedLinkError) {
            // Ignore if native library is not built yet
            isNativeLoaded = false
        }
    }
    
    @JvmStatic
    external fun nativeInit(
        usbFd: Int,
        packetSize: Int,
        sampleRate: Int,
        channelCount: Int,
        bitDepth: Int,
        interfaceId: Int = 1,
        endpointAddress: Int = 0x01
    ): Int

    @JvmStatic
    external fun nativeWritePcm(audioData: ByteArray, sizeBytes: Int): Int

    @JvmStatic
    external fun nativeSetHardwareVolume(percent: Int): Int

    @JvmStatic
    external fun nativeSetSoftwareVolume(percent: Int)

    @JvmStatic
    external fun nativeSetVolumeMode(useHardware: Boolean)

    @JvmStatic
    external fun nativeAttachKernelDriver(fd: Int): Int

    @JvmStatic
    external fun nativeGetCurrentVolume(): Float

    @JvmStatic
    external fun nativeRelease()

    @JvmStatic
    fun initAAudio(deviceId: Int, sampleRate: Int, channelCount: Int, bitDepth: Int, bufferCapacityFrames: Int): Int {
        // AAudio removed, fallback to basic error
        return -1
    }

    @JvmStatic
    fun initLibUSB(usbFd: Int, sampleRate: Int, channelCount: Int, bitDepth: Int, routingMode: Int, maxPacketSize: Int = 0): Int {
        return nativeInit(
            usbFd = usbFd,
            packetSize = maxPacketSize,
            sampleRate = sampleRate,
            channelCount = channelCount,
            bitDepth = bitDepth
        )
    }

    @JvmStatic
    fun startEngine() {
        // Engine is started implicitly by native init in the current pipeline.
    }

    @JvmStatic
    fun stopEngine() {
        // Stop is represented by nativeRelease to avoid stale stream handles.
    }

    @JvmStatic
    fun releaseEngine() {
        nativeRelease()
    }

    @JvmStatic
    fun writeAudio(audioData: ByteArray, numFrames: Int, bitDepth: Int, channelCount: Int): Int {
        if (!isNativeLoaded) return -1
        return nativeWritePcm(audioData, audioData.size)
    }

    @JvmStatic
    fun isExclusive(): Boolean = true

    @JvmStatic
    fun claimInterface(usbFd: Int): Boolean = usbFd > 0

    @JvmStatic
    fun setSoftwareGain(gain: Float) {
        // Gain is managed directly in C++ via nativeSetSoftwareVolume
    }

    @JvmStatic
    fun setHardwareVolume(level: Float): Int {
        return nativeSetHardwareVolume((level * 100).toInt())
    }

    @JvmStatic
    fun setVolumePercent(percent: Int, hardware: Boolean) {
        if (hardware) {
            nativeSetHardwareVolume(percent)
        } else {
            nativeSetSoftwareVolume(percent)
        }
    }

    @JvmStatic
    fun setHardwareRoutingMode(hardware: Boolean) {
        // Kept for compatibility. Routing mode is controlled by init/nativeSetGain.
    }

    fun initBestAvailable(
        deviceId: Int,
        usbFd: Int,
        sampleRate: Int,
        channelCount: Int,
        bitDepth: Int,
        maxPacketSize: Int,
        interfaceId: Int = 1,
        endpointAddress: Int = 0x01
    ): SiphonEngineType {
        if (usbFd > 0) {
            val tier2 = nativeInit(usbFd, maxPacketSize, sampleRate, channelCount, bitDepth, interfaceId, endpointAddress)
            if (tier2 == 0) {
                return SiphonEngineType.LIBUSB_DIRECT
            }
            nativeRelease()
        }

        return SiphonEngineType.UNAVAILABLE
    }
}

enum class SiphonEngineType {
    AAUDIO_EXCLUSIVE,
    LIBUSB_DIRECT,
    UNAVAILABLE
}


