package chromahub.rhythm.app.infrastructure.audio.siphon

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log

class SiphonUsbDriver(
    private val context: Context,
    private val usbManager: UsbManager
) {
    companion object {
        private const val TAG = "SiphonUsbDriver"
    }

    private val claimedInterfacesByConnection = mutableMapOf<UsbDeviceConnection, MutableList<UsbInterface>>()

    // Discover connected USB audio devices
    fun findUsbAudioDevice(): UsbDevice? {
        return usbManager.deviceList.values.firstOrNull { device ->
            isUsbAudioDevice(device)
        }
    }
    
    private fun isUsbAudioDevice(device: UsbDevice): Boolean {
        // Check device class OR interface class for AUDIO (0x01)
        if (device.deviceClass == UsbConstants.USB_CLASS_AUDIO) return true
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                iface.interfaceSubclass == 0x02) { // AudioStreaming subclass
                return true
            }
        }
        return false
    }
    
    // Open connection — returns file descriptor for libusb
    fun openDevice(device: UsbDevice): Pair<UsbDeviceConnection, Int>? {
        if (!usbManager.hasPermission(device)) return null
        val connection = usbManager.openDevice(device) ?: return null
        val fd = connection.fileDescriptor
        return Pair(connection, fd)
    }
    
    // Parse USB Audio descriptors and fetch hardware capabilities
    fun parseAudioDescriptors(device: UsbDevice, connection: UsbDeviceConnection): 
        SiphonDeviceCapabilities {
        if (!claimRequiredInterfaces(device, connection)) {
            Log.w(TAG, "Failed to claim required USB interfaces before descriptor scan")
            return SiphonDescriptorParser.parse(null)
        }

        val rawDescriptors = connection.rawDescriptors
        val baseCaps = SiphonDescriptorParser.parse(rawDescriptors)
        
        if (baseCaps.supportsHardwareVolume) {
            val refinedCaps = refineVolumeCapabilities(connection, baseCaps)
            return refinedCaps
        }
        
        return baseCaps
    }

    fun cleanup(device: UsbDevice, connection: UsbDeviceConnection) {
        val claimed = claimedInterfacesByConnection.remove(connection) ?: return
        for (iface in claimed.reversed()) {
            try {
                connection.releaseInterface(iface)
            } catch (_: Throwable) {
            }
        }
    }

    private fun claimRequiredInterfaces(device: UsbDevice, connection: UsbDeviceConnection): Boolean {
        val claimed = mutableListOf<UsbInterface>()
        val iface0 = device.getInterfaceOrNull(0)
        val iface1 = device.getInterfaceOrNull(1)

        if (iface0 != null && connection.claimInterface(iface0, true)) {
            claimed.add(iface0)
        } else {
            releaseClaimed(connection, claimed)
            return false
        }

        if (iface1 != null && connection.claimInterface(iface1, true)) {
            claimed.add(iface1)
        } else {
            releaseClaimed(connection, claimed)
            return false
        }

        claimedInterfacesByConnection[connection] = claimed
        return true
    }

    private fun releaseClaimed(connection: UsbDeviceConnection, claimed: List<UsbInterface>) {
        for (iface in claimed.reversed()) {
            try {
                connection.releaseInterface(iface)
            } catch (_: Throwable) {
            }
        }
    }

    private fun UsbDevice.getInterfaceOrNull(index: Int): UsbInterface? {
        return if (index in 0 until interfaceCount) getInterface(index) else null
    }

    private fun refineVolumeCapabilities(
        connection: UsbDeviceConnection, 
        baseCaps: SiphonDeviceCapabilities
    ): SiphonDeviceCapabilities {
        val featureUnitId = baseCaps.featureUnitId
        val channelNumber = 0 // Master channel
        val index = (featureUnitId shl 8) or 0 // Interface 0
        val value = (0x02 shl 8) or channelNumber // VOLUME_CONTROL selector

        val min = getControlValue(connection, 0x82, value, index) // GET_MIN
        val max = getControlValue(connection, 0x83, value, index) // GET_MAX
        val res = getControlValue(connection, 0x84, value, index) // GET_RES
        
        return baseCaps.copy(
            volumeMinDb256 = min ?: baseCaps.volumeMinDb256,
            volumeMaxDb256 = max ?: baseCaps.volumeMaxDb256,
            volumeSteps = if (res != null && res > 0) ((max!! - min!!) / res) + 1 else baseCaps.volumeSteps
        )
    }

    private fun getControlValue(
        connection: UsbDeviceConnection, 
        request: Int, 
        value: Int, 
        index: Int
    ): Int? {
        val data = ByteArray(2)
        val result = connection.controlTransfer(
            0xA1, // bmRequestType: Device-to-Host, Class, Interface
            request,
            value,
            index,
            data,
            2,
            500
        )
        
        if (result >= 2) {
            return (data[0].toInt() and 0xFF) or (data[1].toInt() shl 8)
        }
        return null
    }
}
