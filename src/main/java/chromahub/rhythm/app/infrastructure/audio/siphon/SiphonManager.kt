package chromahub.rhythm.app.infrastructure.audio.siphon

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.media3.exoplayer.audio.AudioSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SiphonManager(
    private val context: Context,
    private val audioQualityDataStore: chromahub.rhythm.app.shared.data.model.AudioQualityDataStore? = null
) {
    
    companion object {
        @Volatile
        var currentProtocolRouting: SiphonRoutingMode = SiphonRoutingMode.SOFTWARE

        @Volatile
        var isSiphonActive: Boolean = false

        private const val PREFS = "siphon_prefs"
        private const val KEY_LAST_ACTIVE = "siphon_was_active"
        private const val KEY_VOLUME = "siphon_volume"
        private const val KEY_VOLUME_MODE = "volume_mode"
    }

    private val usbManager = context.getSystemService(UsbManager::class.java)
    private val permissionHandler = SiphonPermissionHandler(context, usbManager)
    private val usbDriver = SiphonUsbDriver(context, usbManager)
    
    private val _state = MutableStateFlow<SiphonState>(SiphonState.Disconnected)
    val state: StateFlow<SiphonState> = _state.asStateFlow()
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _siphonVolume = MutableStateFlow(loadVolume())
    val siphonVolume: StateFlow<Int> = _siphonVolume.asStateFlow()
    private val _currentVolumeMode = MutableStateFlow(loadVolumeMode())
    val currentVolumeMode: StateFlow<VolumeMode> = _currentVolumeMode.asStateFlow()
    
    private var usbSink: SiphonUsbAudioSink? = null
    private var activeDevice: UsbDevice? = null
    private var activeConnection: android.hardware.usb.UsbDeviceConnection? = null
    
    /**
     * Initialize Siphon engine listeners.
     * NOTE: USB lifecycle (attach/detach/permission) is now managed by SiphonSessionManager.
     * This method only sets up the native engine callbacks and zombie recovery.
     */
    fun start() {
        if (!SiphonIsochronousEngine.isNativeLoaded) {
            android.util.Log.e("SiphonManager", "Siphon engine native library failed to load - aborting Siphon start")
            return
        }

        SiphonIsochronousEngine.deviceLostListener = {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                disconnect()
            }
        }
        
        SiphonIsochronousEngine.firstTransferListener = {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(context, "Siphon mode successfully connected!", android.widget.Toast.LENGTH_SHORT).show()
                markSiphonActive(true)
            }
        }
        
        // Recover from zombie state (crash loop prevention)
        recoverFromZombieState()
    }

    fun recoverFromZombieState() {
        if (!SiphonIsochronousEngine.isNativeLoaded) return
        if (wasSiphonActiveOnLastClose()) {
            android.util.Log.w("SiphonManager", "Recovering from zombie state — clearing persisted event, waiting for fresh broadcast")
            markSiphonActive(false) // Clear immediately to break crash loops
            chromahub.rhythm.app.infrastructure.audio.usb.UsbAudioManager.clearPendingAttach(context)
            return // MUST return here to prevent fallthrough to onUsbDeviceAttached()
        }
    }

    
    /**
     * Connect to a USB DAC device. Called by SiphonSessionManager after all gates
     * (exclusive mode, permission, AudioFlinger eviction) have passed.
     */
    fun connectToDevice(device: UsbDevice) {
        _state.value = SiphonState.Connecting(device)
        
        val pair = usbDriver.openDevice(device)
        if (pair == null) {
            _state.value = SiphonState.Error("Failed to open USB device", null)
            return
        }
        val (connection, fd) = pair
        activeDevice = device
        activeConnection = connection
        
        // Parse USB descriptors for capabilities
        val capabilities = usbDriver.parseAudioDescriptors(device, connection)
        
        // Negotiate best format
        val negotiatedFormat = capabilities.bestFormat()
        
        // Build audio sink
        val sink = SiphonUsbAudioSink(
            context, connection, device, capabilities, 
            if (_currentVolumeMode.value == VolumeMode.HARDWARE) SiphonRoutingMode.HARDWARE else SiphonRoutingMode.SOFTWARE
        )
        usbSink = sink

        GlobalVolumeController.init(
            connection = connection,
            featureUnit = capabilities.featureUnitId,
            hardwareSupport = capabilities.supportsHardwareVolume
        )
        
        // Hardware volume logic moved to direct JNI
        
        SiphonIsochronousEngine.nativeSetVolumeMode(_currentVolumeMode.value == VolumeMode.HARDWARE)
        applyVolume(_siphonVolume.value)

        val packetSize = capabilities.maxPacketSize
        val altSetting = if (packetSize <= 192) 1 else if (packetSize <= 288) 2 else 3

        _state.value = SiphonState.Connected(
            device = device,
            connection = connection,
            capabilities = capabilities,
            deviceName = device.productName ?: "USB DAC",
            sampleRate = negotiatedFormat.sampleRate,
            bitDepth = negotiatedFormat.bitDepth,
            channelCount = negotiatedFormat.channelCount,
            volumeMode = _currentVolumeMode.value,
            packetSize = packetSize,
            altSetting = altSetting
        )
        isSiphonActive = true
        markSiphonActive(true)
    }
    
    // Returns the custom AudioSink to inject into ExoPlayer
    fun getAudioSink(): AudioSink? = usbSink
    
    // Called from volume buttons (KEYCODE_VOLUME_UP / DOWN)
    fun adjustVolume(keyCode: Int) {
        val delta = when (keyCode) {
            android.view.KeyEvent.KEYCODE_VOLUME_UP -> 5
            android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> -5
            else -> 0
        }
        if (delta == 0) return
        setVolume((_siphonVolume.value + delta).coerceIn(0, 100))
    }
    
    fun setRoutingMode(mode: SiphonRoutingMode) {
        currentProtocolRouting = mode
        val volumeMode = if (mode == SiphonRoutingMode.HARDWARE) VolumeMode.HARDWARE else VolumeMode.SOFTWARE
        _currentVolumeMode.value = volumeMode
        saveVolumeMode(volumeMode)
        SiphonIsochronousEngine.nativeSetVolumeMode(volumeMode == VolumeMode.HARDWARE)
        usbSink?.setRoutingMode(mode)
        val current = _state.value
        if (current is SiphonState.Connected) {
            _state.value = current.copy(volumeMode = volumeMode)
        }
    }

    fun setVolume(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        _siphonVolume.value = clamped
        saveVolume(clamped)
        applyVolume(clamped)
    }

    private fun applyVolume(percent: Int) {
        when (_currentVolumeMode.value) {
            VolumeMode.HARDWARE -> SiphonIsochronousEngine.nativeSetHardwareVolume(percent)
            VolumeMode.SOFTWARE -> SiphonIsochronousEngine.nativeSetSoftwareVolume(percent)
        }
    }
    
    fun disconnect() {
        // usbSink?.flush() 
        val device = activeDevice
        val connection = activeConnection
        if (device != null && connection != null) {
            usbDriver.cleanup(device, connection)
        }
        activeDevice = null
        activeConnection = null
        GlobalVolumeController.release()
        usbSink = null
        _state.value = SiphonState.Disconnected
        isSiphonActive = false
        markSiphonActive(false)
        prefs.edit().remove("persisted_usb_device").apply()
    }

    fun onDestroy() {
        prefs.edit()
            .putBoolean("had_clean_shutdown", true)
            .remove("persisted_usb_device")
            .apply()
        disconnect()
    }
    
    fun isConnected() = state.value is SiphonState.Connected

    fun markSiphonActive(active: Boolean) {
        prefs.edit().putBoolean(KEY_LAST_ACTIVE, active).apply()
    }

    private fun wasSiphonActiveOnLastClose(): Boolean {
        return prefs.getBoolean(KEY_LAST_ACTIVE, false)
    }

    fun saveVolume(percent: Int) {
        prefs.edit().putInt(KEY_VOLUME, percent.coerceIn(0, 100)).apply()
    }

    fun loadVolume(): Int = prefs.getInt(KEY_VOLUME, 80)

    fun saveVolumeMode(mode: VolumeMode) {
        prefs.edit().putString(KEY_VOLUME_MODE, mode.name).apply()
    }

    fun loadVolumeMode(): VolumeMode {
        val name = prefs.getString(KEY_VOLUME_MODE, VolumeMode.SOFTWARE.name) ?: VolumeMode.SOFTWARE.name
        return runCatching { VolumeMode.valueOf(name) }.getOrDefault(VolumeMode.SOFTWARE)
    }
}
