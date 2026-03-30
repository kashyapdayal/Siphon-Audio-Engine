package chromahub.rhythm.app.infrastructure.audio.siphon

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import chromahub.rhythm.app.infrastructure.service.player.toDeviceCapabilities
import chromahub.rhythm.app.infrastructure.audio.usb.UsbAudioManager
import chromahub.rhythm.app.infrastructure.service.player.OutputRouter
import chromahub.rhythm.app.shared.data.model.AudioQualityDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SiphonSessionManager — Centralized USB DAC Lifecycle Gatekeeper
 *
 * Enforces the correct sequence for USB DAC acquisition:
 *   1. Check Exclusive Mode toggle in Settings
 *   2. Detect if USB DAC is connected
 *   3. Request USB permission (only after 1+2 pass)
 *   4. Stop ExoPlayer to release AudioFlinger ALSA handle
 *   5. Enumerate USB descriptors (with timeout + retry)
 *   6. Claim USB ALSA interface via libusb
 *   7. Switch to Siphon audio sink
 *
 * FIX: Previous version had a dead-end in onDeviceArrived() when caps weren't
 * cached, and proceedAfterPermission() never triggered enumeration. Now both
 * paths always trigger enumeration when needed.
 */
class SiphonSessionManager(
    private val context: Context,
    private val audioQualityDataStore: AudioQualityDataStore,
    private val usbAudioManager: UsbAudioManager,
    private val siphonManager: SiphonManager
) {
    companion object {
        private const val TAG = "SiphonSessionManager"
        private const val ENUMERATION_TIMEOUT_MS = 5000L
        private const val ENUMERATION_RETRY_DELAY_MS = 500L
        private const val MAX_ENUMERATION_RETRIES = 2
        private const val ALSA_RELEASE_DELAY_MS = 500L
        private const val EVICTION_SETTLE_MS = 300L
    }

    // ── Session States ──────────────────────────────────────────

    enum class SessionState {
        /** No USB DAC activity. Normal audio path. */
        IDLE,
        /** Exclusive mode is ON but no DAC detected yet. */
        WAITING_FOR_DEVICE,
        /** DAC detected, requesting USB permission from user. */
        WAITING_FOR_PERMISSION,
        /** Permission granted. Stopping ExoPlayer to release AudioFlinger. */
        EVICTING_AUDIOFLINGER,
        /** AudioFlinger released. Enumerating USB descriptors. */
        ENUMERATING,
        /** Enumeration complete. Claiming USB interface via Siphon. */
        CLAIMING_INTERFACE,
        /** Full Siphon pipeline active. Bit-perfect USB audio. */
        ACTIVE,
        /** Releasing Siphon resources, returning to AudioFlinger. */
        RELEASING,
        /** Permission was denied by user. */
        PERMISSION_DENIED,
        /** Enumeration or claim failed. */
        FAILED
    }

    // ── Public State Flows ──────────────────────────────────────

    private val _sessionState = MutableStateFlow(SessionState.IDLE)
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    private val _isRoutingTransitionInProgress = AtomicBoolean(false)

    fun isRoutingTransitionInProgress(): Boolean = _isRoutingTransitionInProgress.get()

    // ── Internal State ──────────────────────────────────────────

    @Volatile
    private var parkedDevice: UsbDevice? = null

    private val handledDeviceExpiry = mutableMapOf<String, Long>()

    @Volatile
    private var activeDevice: UsbDevice? = null

    lateinit var scope: CoroutineScope

    var outputRouter: OutputRouter? = null

    private var sessionJob: Job? = null
    private var enumerationTimeoutJob: Job? = null

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // ── Public API ──────────────────────────────────────────────

    /**
     * Called by MediaPlaybackService after full player initialization.
     * If exclusive mode is ON and a DAC is already connected, begin claim.
     */
    fun onAppReady() {
        Log.i(TAG, "onAppReady — checking for USB DAC to claim")
        sessionJob = scope.launch {
            val exclusiveEnabled = audioQualityDataStore.usbExclusiveModeEnabled.first()
            if (!exclusiveEnabled) {
                Log.d(TAG, "Exclusive mode is OFF — staying on AudioFlinger")
                _sessionState.value = SessionState.IDLE
                return@launch
            }

            val device = parkedDevice ?: findConnectedUsbDac()
            if (device != null) {
                parkedDevice = null
                Log.i(TAG, "Found USB DAC: ${device.productName} — beginning claim sequence")
                beginClaimSequence(device)
            } else {
                Log.d(TAG, "Exclusive mode ON but no DAC connected — waiting for attachment")
                _sessionState.value = SessionState.WAITING_FOR_DEVICE
            }
        }
    }

    /**
     * Called when a USB audio device is physically attached.
     *
     * FIX: Previously dead-ended when caps weren't cached. Now always begins
     * the claim sequence when exclusive mode is ON, which triggers enumeration.
     */
    fun onDeviceArrived(device: UsbDevice) {
        val now = System.currentTimeMillis()
        val lastEvent = handledDeviceExpiry[device.deviceName] ?: 0L
        if (now - lastEvent < 2000L) {
            Log.d(TAG, "Ignoring duplicate onDeviceArrived for ${device.productName}")
            return
        }
        handledDeviceExpiry[device.deviceName] = now

        Log.i(TAG, "onDeviceArrived: ${device.productName} (vid=0x${device.vendorId.toString(16)}, pid=0x${device.productId.toString(16)})")

        if (activeDevice?.deviceId == device.deviceId &&
            _sessionState.value == SessionState.ACTIVE) {
            Log.d(TAG, "Device already active, ignoring duplicate attach")
            return
        }

        scope.launch {
            val exclusiveEnabled = audioQualityDataStore.usbExclusiveModeEnabled.first()
            if (!exclusiveEnabled) {
                Log.d(TAG, "USB DAC attached but Exclusive Mode OFF — parking device")
                parkedDevice = device
                _sessionState.value = SessionState.IDLE
                usbAudioManager.onUsbDeviceAttached(device)
                return@launch
            }

            // FIX: Always begin claim sequence — don't check cached caps here.
            // beginClaimSequence() handles permission and triggers enumeration as needed.
            Log.i(TAG, "Exclusive mode ON — beginning claim sequence for ${device.productName}")
            beginClaimSequence(device)
        }
    }

    /**
     * Called when a USB device is detached.
     */
    fun onDeviceRemoved(device: UsbDevice) {
        val now = System.currentTimeMillis()
        val lastEvent = handledDeviceExpiry[device.deviceName] ?: 0L
        if (now - lastEvent < 2000L) {
            Log.d(TAG, "Ignoring duplicate onDeviceRemoved for ${device.productName}")
            return
        }
        handledDeviceExpiry[device.deviceName] = now

        Log.i(TAG, "onDeviceRemoved: ${device.productName}")

        sessionJob?.cancel()
        enumerationTimeoutJob?.cancel()

        if (activeDevice?.deviceId == device.deviceId ||
            _sessionState.value == SessionState.ACTIVE) {
            releaseSession()
        }

        parkedDevice = null
        activeDevice = null
        _sessionState.value = SessionState.IDLE
        _isRoutingTransitionInProgress.set(false)
    }

    /**
     * Called when the user toggles Exclusive Mode in settings.
     */
    fun onExclusiveModeChanged(enabled: Boolean) {
        Log.i(TAG, "onExclusiveModeChanged: $enabled")
        scope.launch {
            if (enabled) {
                val device = parkedDevice ?: findConnectedUsbDac()
                if (device != null) {
                    parkedDevice = null
                    beginClaimSequence(device)
                } else {
                    _sessionState.value = SessionState.WAITING_FOR_DEVICE
                }
            } else {
                enumerationTimeoutJob?.cancel()
                if (_sessionState.value == SessionState.ACTIVE) {
                    releaseSession()
                }
                _sessionState.value = SessionState.IDLE
            }
        }
    }

    /**
     * Called when USB permission result arrives from the system.
     */
    fun onPermissionResult(device: UsbDevice, granted: Boolean) {
        Log.i(TAG, "onPermissionResult: granted=$granted for ${device.productName}")
        if (_sessionState.value != SessionState.WAITING_FOR_PERMISSION) {
            Log.w(TAG, "Permission result arrived in unexpected state: ${_sessionState.value}")
        }

        if (granted) {
            scope.launch {
                proceedAfterPermission(device)
            }
        } else {
            Log.w(TAG, "USB permission denied for ${device.productName}")
            parkedDevice = device
            _sessionState.value = SessionState.PERMISSION_DENIED
        }
    }

    // ── Internal Claim Sequence ─────────────────────────────────

    /**
     * Gate 1: Exclusive mode already checked by caller.
     * Gate 2: Device is provided by caller.
     * Gate 3: Request USB permission if needed.
     */
    private suspend fun beginClaimSequence(device: UsbDevice) {
        activeDevice = device
        _sessionState.value = SessionState.WAITING_FOR_PERMISSION

        if (usbManager.hasPermission(device)) {
            Log.d(TAG, "USB permission already granted for ${device.productName}")
            proceedAfterPermission(device)
        } else {
            Log.d(TAG, "Requesting USB permission for ${device.productName}")
            usbAudioManager.onUsbDeviceAttached(device)
        }
    }

    /**
     * Gate 4: Evict AudioFlinger.
     * Gate 5: Enumerate USB descriptors.
     * Gate 6: Claim interface and switch to Siphon.
     *
     * FIX: Previously this method had a dead-end when caps weren't cached.
     * Now it always triggers enumeration via the orchestrator when needed,
     * with a timeout watchdog and retry logic.
     */
    private suspend fun proceedAfterPermission(device: UsbDevice) {
        _isRoutingTransitionInProgress.set(true)
        val startTime = System.currentTimeMillis()
        try {
            // Gate 4: Evict AudioFlinger — stop ExoPlayer to release ALSA handle
            _sessionState.value = SessionState.EVICTING_AUDIOFLINGER
            Log.i(TAG, "Gate 4: Evicting AudioFlinger for ${device.productName}")
            evictAudioFlinger()
            Log.d(TAG, "Gate 4 complete (${System.currentTimeMillis() - startTime}ms elapsed)")

            // Gate 5: Enumerate USB descriptors
            _sessionState.value = SessionState.ENUMERATING
            val cachedCaps = usbAudioManager.orchestrator.getCachedCaps(device)
            if (cachedCaps != null) {
                Log.i(TAG, "Gate 5: Using cached enumeration for ${device.productName}")
                _sessionState.value = SessionState.CLAIMING_INTERFACE
                claimInterface(device, cachedCaps.toDeviceCapabilities())
            } else {
                Log.i(TAG, "Gate 5: No cached caps — triggering enumeration for ${device.productName}")
                triggerEnumerationWithTimeout(device)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in claim sequence: ${e.message}", e)
            _sessionState.value = SessionState.FAILED
            // Auto-recover to IDLE after 2 seconds
            scope.launch {
                delay(2000L)
                if (_sessionState.value == SessionState.FAILED) {
                    _sessionState.value = SessionState.IDLE
                }
            }
        } finally {
            // Note: _isRoutingTransitionInProgress is cleared by claimInterface() on success
            // or here on failure
            if (_sessionState.value != SessionState.CLAIMING_INTERFACE &&
                _sessionState.value != SessionState.ACTIVE &&
                _sessionState.value != SessionState.ENUMERATING) {
                _isRoutingTransitionInProgress.set(false)
            }
        }
    }

    /**
     * Trigger USB descriptor enumeration through the orchestrator and wait
     * for the onEnumerationComplete callback, with timeout + retry.
     *
     * The orchestrator opens the device, claims interfaces with force=true
     * (detaches kernel driver), reads raw descriptors, and fires the callback.
     * The callback chain: Orchestrator → UsbAudioManager → OutputRouter → here.
     */
    private fun triggerEnumerationWithTimeout(device: UsbDevice, retryCount: Int = 0) {
        Log.i(TAG, "Triggering enumeration (attempt ${retryCount + 1}/${MAX_ENUMERATION_RETRIES + 1}) for ${device.productName}")

        // Trigger the orchestrator to enumerate (opens device + claims + parses descriptors)
        usbAudioManager.onUsbDeviceAttached(device)

        // Set up timeout watchdog
        enumerationTimeoutJob?.cancel()
        enumerationTimeoutJob = scope.launch {
            delay(ENUMERATION_TIMEOUT_MS)

            // If we're still waiting for enumeration, it failed
            if (_sessionState.value == SessionState.ENUMERATING ||
                _sessionState.value == SessionState.CLAIMING_INTERFACE) {

                if (retryCount < MAX_ENUMERATION_RETRIES) {
                    Log.w(TAG, "Enumeration timed out — retrying in ${ENUMERATION_RETRY_DELAY_MS}ms (retry ${retryCount + 1})")
                    delay(ENUMERATION_RETRY_DELAY_MS)

                    // Re-evict before retry
                    evictAudioFlinger()
                    triggerEnumerationWithTimeout(device, retryCount + 1)
                } else {
                    Log.e(TAG, "Enumeration failed after ${MAX_ENUMERATION_RETRIES + 1} attempts for ${device.productName}")
                    _sessionState.value = SessionState.FAILED
                    _isRoutingTransitionInProgress.set(false)

                    // Auto-recover to IDLE
                    scope.launch {
                        delay(2000L)
                        if (_sessionState.value == SessionState.FAILED) {
                            _sessionState.value = SessionState.IDLE
                        }
                    }
                }
            }
        }
    }

    /**
     * Gate 6 execution. Called from two paths:
     *   A) Directly by proceedAfterPermission() when caps are cached.
     *   B) Via OutputRouter.onUsbDeviceAttached() → debounce → claimInterface()
     *      when the orchestrator's onEnumerationComplete fires.
     */
    fun claimInterface(device: UsbDevice, caps: SiphonDeviceCapabilities) {
        // If we're in IDLE or WAITING_FOR_DEVICE, this means the orchestrator
        // fired enumeration complete from a cold start. Begin the full sequence.
        if (_sessionState.value == SessionState.IDLE || _sessionState.value == SessionState.WAITING_FOR_DEVICE) {
            Log.i(TAG, "Received caps in ${_sessionState.value} — lifting deferral and beginning claim")
            scope.launch { beginClaimSequence(device) }
            return
        }

        // Only proceed if we're in the right state
        if (_sessionState.value != SessionState.CLAIMING_INTERFACE &&
            _sessionState.value != SessionState.ENUMERATING) {
            Log.w(TAG, "claimInterface called but state is ${_sessionState.value} — ignoring")
            return
        }

        // Cancel the timeout watchdog — enumeration succeeded
        enumerationTimeoutJob?.cancel()
        _sessionState.value = SessionState.CLAIMING_INTERFACE

        Log.i(TAG, "Gate 6: Claiming interface for ${device.productName} — " +
                "formats=${caps.supportedFormats.size}, " +
                "maxPacket=${caps.maxPacketSize}, " +
                "endpoint=0x${caps.endpointAddress.toString(16)}, " +
                "hwVol=${caps.supportsHardwareVolume}")

        scope.launch {
            try {
                outputRouter?.executeSwitchToSiphon(device, caps)
                _sessionState.value = SessionState.ACTIVE
                activeDevice = device
                _isRoutingTransitionInProgress.set(false)
                Log.i(TAG, "═══ Session ACTIVE — Siphon pipeline running for ${device.productName} ═══")
            } catch (e: Exception) {
                Log.e(TAG, "executeSwitchToSiphon failed: ${e.message}", e)
                _sessionState.value = SessionState.FAILED
                _isRoutingTransitionInProgress.set(false)

                // Auto-recover to IDLE
                scope.launch {
                    delay(2000L)
                    if (_sessionState.value == SessionState.FAILED) {
                        _sessionState.value = SessionState.IDLE
                    }
                }
            }
        }
    }

    /**
     * Gate 4 execution. Force AudioFlinger to release the ALSA PCM on the USB device.
     *
     * FIX: Previous version used a silent AudioTrack trick which was unreliable.
     * Now we:
     *   1. Ask OutputRouter to stop ExoPlayer completely (releases AudioTrack → AudioFlinger closes ALSA)
     *   2. Tell audio policy to disconnect USB output (setParameters)
     *   3. Create a brief silent AudioTrack to flush any lingering ALSA refs
     *   4. Wait for the kernel driver to fully release (500ms)
     */
    private suspend fun evictAudioFlinger() {
        val startTime = System.currentTimeMillis()

        // Step 1: Stop ExoPlayer via OutputRouter to fully release AudioTrack
        // This is the most important step — it causes AudioFlinger to close the ALSA pcm handle
        try {
            outputRouter?.playerEngine?.let { engine ->
                engine.pauseForRouting()
                Log.d(TAG, "Eviction step 1: ExoPlayer paused (${System.currentTimeMillis() - startTime}ms)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Eviction step 1 failed (non-fatal): ${e.message}")
        }

        // Step 1b: Listen for AudioEffect disconnects (e.g. ViPER4Android)
        // If an audio effect attached to our session, it must release it before we can claim USB.
        val v4aClosedDeferred = kotlinx.coroutines.CompletableDeferred<Unit>()
        val vipCloseReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: android.content.Intent) {
                if (intent.action == android.media.audiofx.AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION) {
                    Log.d(TAG, "AudioEffect session closed — safe to proceed")
                    v4aClosedDeferred.complete(Unit)
                }
            }
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    vipCloseReceiver,
                    android.content.IntentFilter(android.media.audiofx.AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION),
                    Context.RECEIVER_EXPORTED // Specify flag for Android 14+
                )
            } else {
                context.registerReceiver(
                    vipCloseReceiver,
                    android.content.IntentFilter(android.media.audiofx.AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
                )
            }
            // Safety timeout — if V4A doesn't respond in 500ms, ignore
            kotlinx.coroutines.withTimeoutOrNull(500L) {
                v4aClosedDeferred.await()
            }
        } catch (e: Exception) {
        } finally {
            try { context.unregisterReceiver(vipCloseReceiver) } catch (e: Exception) {}
        }
        Log.d(TAG, "Eviction step 1b: Wait for AudioEffect close done (${System.currentTimeMillis() - startTime}ms)")

        // Step 2: Tell audio policy to disconnect USB output
        try {
            val siphonAudioManager = context.createAttributionContext("siphon")
                .getSystemService(AudioManager::class.java)
            siphonAudioManager.setParameters("usb_out_connected=false")
            Log.d(TAG, "Eviction step 2: setParameters done (${System.currentTimeMillis() - startTime}ms)")
        } catch (e: Exception) {
            Log.w(TAG, "Eviction step 2 failed (non-fatal): ${e.message}")
        }

        // Step 3: Silent AudioTrack to force AudioFlinger to close ALSA handle
        try {
            val usbDeviceInfo = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
            if (usbDeviceInfo != null) {
                val bufferSize = 48000 * 2 * 2 / 10 // ~100ms at 48kHz stereo 16-bit
                val silentTrack = android.media.AudioTrack.Builder()
                    .setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        android.media.AudioFormat.Builder()
                            .setSampleRate(48000)
                            .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_STEREO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                    .build()
                silentTrack.setPreferredDevice(usbDeviceInfo)
                val silentBuf = ByteArray(bufferSize)
                silentTrack.write(silentBuf, 0, silentBuf.size)
                silentTrack.play()
                delay(100)
                silentTrack.stop()
                silentTrack.release()
                Log.d(TAG, "Eviction step 3: silent track done (${System.currentTimeMillis() - startTime}ms)")
            } else {
                Log.d(TAG, "Eviction step 3: no USB audio device found in AudioManager")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Eviction step 3 failed (non-fatal): ${e.message}")
        }

        // Step 4: Wait for ALSA kernel driver to fully release
        delay(ALSA_RELEASE_DELAY_MS)
        Log.d(TAG, "Eviction step 4: ALSA release delay done (${System.currentTimeMillis() - startTime}ms total)")
    }

    /**
     * Release Siphon session and return to AudioFlinger standard path.
     */
    private fun releaseSession() {
        _sessionState.value = SessionState.RELEASING
        _isRoutingTransitionInProgress.set(true)

        try {
            Log.i(TAG, "Releasing Siphon session")
            siphonManager.disconnect()
            outputRouter?.switchToStandard()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing session: ${e.message}", e)
        } finally {
            _isRoutingTransitionInProgress.set(false)
            activeDevice = null
            _sessionState.value = SessionState.IDLE
        }
    }

    // ── Helpers ──────────────────────────────────────────────────

    private fun findConnectedUsbDac(): UsbDevice? {
        val hasUsbAudio = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .any { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }

        if (!hasUsbAudio) return null

        return usbManager.deviceList.values.firstOrNull { device ->
            isUsbAudioDevice(device)
        }
    }

    private fun isUsbAudioDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == 0x01) return true
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == android.hardware.usb.UsbConstants.USB_CLASS_AUDIO) {
                return true
            }
        }
        return false
    }

    /**
     * Clean shutdown — call from service onDestroy.
     */
    fun release() {
        sessionJob?.cancel()
        enumerationTimeoutJob?.cancel()
        if (_sessionState.value == SessionState.ACTIVE) {
            releaseSession()
        }
        parkedDevice = null
        activeDevice = null
        _isRoutingTransitionInProgress.set(false)
        Log.d(TAG, "SiphonSessionManager released")
    }
}
