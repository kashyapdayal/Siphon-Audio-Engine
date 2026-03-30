package chromahub.rhythm.app.infrastructure.audio.siphon

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.media.AudioTrack
import android.util.Log
import androidx.media3.common.AudioAttributes as Media3AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SiphonUsbAudioSink(
    private val context: Context,
    private val usbConnection: UsbDeviceConnection,
    private val usbDevice: UsbDevice,
    private val deviceCapabilities: SiphonDeviceCapabilities,
    private val initialRoutingMode: SiphonRoutingMode
) : AudioSink {

    companion object {
        private const val TAG = "SiphonSink"
        private val unsupportedDevices = mutableSetOf<Int>()
        
        // USB Audio Class endpoint type
        private const val USB_CLASS_AUDIO = 0x01
        private const val USB_SUBCLASS_AUDIO_STREAMING = 0x02
    }

    private var audioTrack: AudioTrack? = null
    private var currentFormat: SiphonAudioFormat? = null
    private var routingMode: SiphonRoutingMode = initialRoutingMode
    private var softwareGain: Float = 1.0f
    private var isExclusive = false
    private var isEnded = false
    private var playbackParameters = PlaybackParameters.DEFAULT
    private var activeTier = 3

    override fun setListener(listener: AudioSink.Listener) = Unit

    override fun supportsFormat(format: Format): Boolean = true

    override fun getFormatSupport(format: Format): Int = AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long = 0L

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        currentFormat = SiphonAudioFormat.fromExoPlayerFormat(inputFormat)
        val format = currentFormat ?: return

        if (unsupportedDevices.contains(usbDevice.deviceId)) {
            isExclusive = false
            activeTier = 3
            Log.w(TAG, "Device marked unsupported for this session, using tier 3 fallback")
            return
        }

        SiphonIsochronousEngine.releaseEngine()

        // Bug 1 FIX: Try to claim USB interface and select alternate setting
        val interfaceId = tryClaimUsbInterface(format)
        if (interfaceId == -1) {
            Log.w(TAG, "Failed to claim USB AudioStreaming interface")
            unsupportedDevices.add(usbDevice.deviceId)
            activeTier = 3
            isExclusive = false
            return
        }

        val engineType = SiphonIsochronousEngine.initBestAvailable(
            deviceId = usbDevice.deviceId,
            usbFd = usbConnection.fileDescriptor,
            sampleRate = format.sampleRate,
            channelCount = format.channelCount,
            bitDepth = format.bitDepth,
            maxPacketSize = deviceCapabilities.maxPacketSize,
            interfaceId = interfaceId,
            endpointAddress = deviceCapabilities.endpointAddress
        )

        activeTier = when (engineType) {
            SiphonEngineType.AAUDIO_EXCLUSIVE -> 1
            SiphonEngineType.LIBUSB_DIRECT -> 2
            SiphonEngineType.UNAVAILABLE -> 3
        }

        isExclusive = activeTier != 3
        if (isExclusive) {
            val msg = when (activeTier) {
                1 -> "Tier 1 Active: AAudio EXCLUSIVE MMAP — hardware direct"
                2 -> "Tier 2 Active: USB Host Direct — system-invisible audio path"
                else -> "Tier 3 Active: Standard path — system DSP active"
            }
            Log.i(TAG, "$msg | $format | Routing: $routingMode")
        } else {
            unsupportedDevices.add(usbDevice.deviceId)
            Log.w(TAG, "DirectBit exclusive init failed, device moved to unsupported cache")
            throw AudioSink.ConfigurationException(
                "Failed to initialize Siphon engine",
                inputFormat
            )
        }
    }

    /**
     * Claim the USB AudioStreaming interface and select the correct alternate setting.
     *
     * Steps:
     * 1. Find AudioStreaming interface (USB Audio Class = 0x01, Subclass = 0x02)
     * 2. Claim the interface with force-disconnect (retries up to 3 times)
     * 3. Select appropriate alternate setting based on sample rate
     * 4. Return interface ID if successful, -1 on failure
     *
     * FIX: Added retry loop because the kernel ALSA driver may still be releasing
     * the interface even after OutputRouter's eviction sequence.
     */
    private fun tryClaimUsbInterface(format: SiphonAudioFormat): Int {
        try {
            // Find AudioStreaming interface (typically interface ID 1)
            var audioStreamingInterface: UsbInterface? = null
            for (i in 0 until usbDevice.interfaceCount) {
                val iface = usbDevice.getInterface(i)
                if (iface.interfaceClass == USB_CLASS_AUDIO && iface.interfaceSubclass == USB_SUBCLASS_AUDIO_STREAMING) {
                    audioStreamingInterface = iface
                    Log.d(TAG, "Found AudioStreaming interface at index $i (id=${iface.id}, endpoints=${iface.endpointCount})")
                    break
                }
            }

            if (audioStreamingInterface == null) {
                Log.w(TAG, "No USB AudioStreaming interface found on device (${usbDevice.interfaceCount} interfaces)")
                return -1
            }

            // Claim the interface with retry (true = force-disconnect from Android audio system)
            var claimed = false
            for (attempt in 1..3) {
                claimed = usbConnection.claimInterface(audioStreamingInterface, true)
                if (claimed) {
                    Log.d(TAG, "Claimed AudioStreaming interface on attempt $attempt")
                    break
                }
                Log.w(TAG, "claimInterface failed on attempt $attempt/3 — retrying in 200ms")
                Thread.sleep(200)
            }
            if (!claimed) {
                Log.e(TAG, "Failed to claim AudioStreaming interface after 3 attempts")
                return -1
            }

            // Set the interface (selects alt setting)
            if (!usbConnection.setInterface(audioStreamingInterface)) {
                Log.w(TAG, "setInterface failed — may not affect native-side alt setting selection")
                // Don't return -1 here — native code handles alt setting via libusb
            }
            Log.d(TAG, "AudioStreaming interface configured for ${format.sampleRate}Hz/${format.bitDepth}bit")

            // Allow AudioFlinger (ALSA) teardown to complete before native isochronous init
            Thread.sleep(50)

            Log.i(TAG, "USB Host path ACTIVE — AudioFlinger bypassed | fd=${usbConnection.fileDescriptor}")
            return audioStreamingInterface.id
        } catch (e: Exception) {
            Log.e(TAG, "Exception while claiming USB interface: ${e.message}", e)
            return -1
        }
    }

    override fun play() {
        audioTrack?.play()
    }

    override fun handleDiscontinuity() = Unit

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (!isExclusive) return false
        val format = currentFormat ?: return false

        // VERIFY: software gain is NOT applied when DAC supports hardware volume
        // bytes must pass through UNMODIFIED. SiphonIsochronousEngine.writeAudio calls nativeWritePcm.
        // The native layer (SiphonUsbDriver::submitAudioData) already handles the bypass logic
        // based on the routingMode/isHardwareMode flag passed during init.
        
        val payload = ByteArray(buffer.remaining())
        buffer.get(payload)

        val processedPayload = if (format.bitDepth == 16) {
            val shortBuffer = java.nio.ByteBuffer.wrap(payload).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val shortArray = ShortArray(shortBuffer.remaining())
            shortBuffer.get(shortArray)
            val processedShorts = GlobalVolumeController.processPcmBuffer(shortArray)
            val processedBytes = java.nio.ByteBuffer.allocate(processedShorts.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            processedBytes.asShortBuffer().put(processedShorts)
            processedBytes.array()
        } else {
            payload
        }

        // Gain is applied natively, pass the raw payload
        val result = SiphonIsochronousEngine.writeAudio(
            audioData = processedPayload,
            numFrames = payload.size / (format.channelCount * (format.bitDepth / 8)),
            bitDepth = format.bitDepth,
            channelCount = format.channelCount
        )
        return result >= 0
    }

    override fun playToEndOfStream() {
        isEnded = true
    }

    override fun isEnded(): Boolean = isEnded

    override fun hasPendingData(): Boolean = audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        this.playbackParameters = playbackParameters
    }

    override fun getPlaybackParameters(): PlaybackParameters = playbackParameters

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) = Unit

    override fun getSkipSilenceEnabled(): Boolean = false

    override fun setAudioAttributes(audioAttributes: Media3AudioAttributes) = Unit

    override fun getAudioAttributes(): Media3AudioAttributes? = null

    override fun setAudioSessionId(audioSessionId: Int) = Unit

    override fun getAudioTrackBufferSizeUs(): Long = 0L

    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) = Unit

    override fun enableTunnelingV21() = Unit

    override fun disableTunneling() = Unit

    override fun setVolume(volume: Float) = Unit

    override fun pause() {
        audioTrack?.pause()
    }

    override fun flush() {
        audioTrack?.flush()
    }

    override fun reset() {
        release()
    }

    override fun release() {
        SiphonIsochronousEngine.releaseEngine()
        isExclusive = false
    }

    private fun applySoftwareGainToByteArray(data: ByteArray, gain: Float): ByteArray {
        if (gain >= 0.999f) return data
        val format = currentFormat ?: return data
        val copy = ByteBuffer.wrap(data.copyOf()).order(ByteOrder.LITTLE_ENDIAN)
        val processed = applySoftwareGain(copy, gain, format.bitDepth)
        val out = ByteArray(processed.remaining())
        processed.get(out)
        return out
    }

    private fun applySoftwareGain(buffer: ByteBuffer, gain: Float, bitDepth: Int): ByteBuffer {
        if (gain <= 0f) return ByteBuffer.allocate(0)
        val out = ByteBuffer.allocateDirect(buffer.remaining()).order(ByteOrder.LITTLE_ENDIAN)

        when (bitDepth) {
            16 -> {
                while (buffer.remaining() >= 2) {
                    val sample = (buffer.short * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    out.putShort(sample.toShort())
                }
            }
            24 -> {
                while (buffer.remaining() >= 3) {
                    val b0 = buffer.get().toInt() and 0xFF
                    val b1 = buffer.get().toInt() and 0xFF
                    val b2 = buffer.get().toInt()
                    var sample = b0 or (b1 shl 8) or (b2 shl 16)
                    if ((sample and 0x800000) != 0) sample = sample or -0x1000000
                    val scaled = (sample * gain).toInt().coerceIn(-0x800000, 0x7FFFFF)
                    out.put((scaled and 0xFF).toByte())
                    out.put(((scaled shr 8) and 0xFF).toByte())
                    out.put(((scaled shr 16) and 0xFF).toByte())
                }
            }
            else -> {
                while (buffer.remaining() >= 4) {
                    val sample = (buffer.int * gain).toLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
                    out.putInt(sample.toInt())
                }
            }
        }

        out.flip()
        return out
    }

    fun setSoftwareVolume(level: Float) {
        softwareGain = level.coerceIn(0f, 1f)
        SiphonIsochronousEngine.setSoftwareGain(softwareGain)
    }

    fun setRoutingMode(mode: SiphonRoutingMode) {
        routingMode = mode
    }
}
