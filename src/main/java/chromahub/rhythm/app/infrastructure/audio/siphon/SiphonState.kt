package chromahub.rhythm.app.infrastructure.audio.siphon

import android.hardware.usb.UsbDevice

/**
 * Siphon state machine — tracks USB DAC connection lifecycle.
 */
sealed class SiphonState {
    data object Disconnected : SiphonState()
    data object RequestingPermission : SiphonState()
    data class Connecting(val device: UsbDevice) : SiphonState()
    data class PermissionDenied(val device: UsbDevice) : SiphonState()
    data class Connected(
        val device: UsbDevice,
        val connection: android.hardware.usb.UsbDeviceConnection,
        val capabilities: SiphonDeviceCapabilities,
        val deviceName: String,
        val sampleRate: Int,
        val bitDepth: Int,
        val channelCount: Int,
        val volumeMode: VolumeMode,
        val packetSize: Int,
        val altSetting: Int
    ) : SiphonState()
    data class Error(val message: String, val cause: Throwable? = null) : SiphonState()
}

/**
 * Volume control mode for the connected USB DAC.
 */
enum class VolumeMode {
    /** UAC2 SET_CUR hardware control transfer to DAC volume feature unit */
    HARDWARE,
    /** 64-bit float multiply + TPDF dither applied to PCM samples before USB transfer */
    SOFTWARE
}

/**
 * Routing mode for Siphon output.
 */
enum class SiphonRoutingMode {
    HARDWARE,
    SOFTWARE
}
