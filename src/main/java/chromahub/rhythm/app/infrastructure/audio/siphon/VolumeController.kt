package chromahub.rhythm.app.infrastructure.audio.siphon

import android.hardware.usb.UsbDeviceConnection
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.pow

/**
 * VolumeController
 *
 * Dedicated volume engine for the Siphon / USB-Direct audio path.
 * Completely bypasses Android AudioManager — volume state is owned here.
 *
 * Two operational modes (auto-selected based on DAC capability):
 *   1. HARDWARE  – sends UAC2 SET_CUR control transfers to the DAC's Feature Unit.
 *                  Zero bit modification; DAC analog stage does the attenuation.
 *   2. SOFTWARE  – high-precision 32-bit float multiplication applied to PCM buffers
 *                  before they are handed to DirectBitEngine for USB transmission.
 *                  Includes TPDF dither before truncation back to native bit-depth.
 *
 * Volume is expressed in dB (range MIN_DB … 0.0f).
 * UI and physical button callers always work in dB steps; this class owns conversion.
 */
class VolumeController {

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    companion object {
        private const val TAG = "VolumeController"

        /** Lowest attenuation exposed to the user (silence threshold). */
        const val MIN_DB   = -60f
        /** Unity gain – no attenuation. */
        const val MAX_DB   =   0f
        /** Precision steps available to the user. 100 steps = 0.6 dB/step. */
        const val STEPS    = 100
        /** dB change per physical button press. */
        const val STEP_DB  = (MAX_DB - MIN_DB) / STEPS  // 0.6 dB

        // UAC2 bmRequestType for SET_CUR (class, interface, host→device)
        private const val UAC2_REQUEST_TYPE = 0x21
        // bRequest: SET_CUR
        private const val UAC2_SET_CUR      = 0x01
        // Control selector: VOLUME_CONTROL
        private const val UAC2_VOLUME_CS    = 0x02
        // Master channel
        private const val UAC2_MASTER_CH    = 0x00

        // Timeout for USB control transfers (ms)
        private const val USB_TIMEOUT_MS    = 1000
    }

    // -------------------------------------------------------------------------
    // Mode
    // -------------------------------------------------------------------------

    enum class Mode { HARDWARE, SOFTWARE, UNINITIALIZED }

    // -------------------------------------------------------------------------
    // Internal state
    // -------------------------------------------------------------------------

    /** Current volume in dB – observed by UI and SiphonManager. */
    private val _volumeDb = MutableStateFlow(MAX_DB)
    val volumeDb: StateFlow<Float> = _volumeDb.asStateFlow()

    /** Current 0-100 step position – convenience for UI sliders. */
    val volumeSteps: Int
        get() = dbToSteps(_volumeDb.value)

    private var mode: Mode = Mode.UNINITIALIZED

    private val _isUsbDirectActive = MutableStateFlow(false)
    val isUsbDirectActive: StateFlow<Boolean> = _isUsbDirectActive.asStateFlow()

    /** USB connection reference – only valid in HARDWARE mode. */
    private var usbConnection: UsbDeviceConnection? = null
    /** UAC2 Feature Unit ID – read from USB descriptor before use. */
    private var featureUnitId: Int = 6  // default; override via init()

    // -------------------------------------------------------------------------
    // Initialisation
    // -------------------------------------------------------------------------

    /**
     * Call this when the Siphon engine connects to a DAC.
     *
     * @param connection      Active UsbDeviceConnection to the DAC.
     * @param featureUnit     Feature Unit ID from the DAC's USB audio descriptor.
     * @param hardwareSupport Whether the DAC reports UAC2 volume control support.
     */
    fun init(
        connection: UsbDeviceConnection?,
        featureUnit: Int,
        hardwareSupport: Boolean
    ) {
        usbConnection  = connection
        featureUnitId  = featureUnit
        _isUsbDirectActive.value = true
        mode = if (hardwareSupport && connection != null) Mode.HARDWARE else Mode.SOFTWARE
        Log.i(TAG, "VolumeController initialised – mode=$mode featureUnit=$featureUnit")

        // Sync DAC to current stored level on (re)connect
        if (mode == Mode.HARDWARE) {
            sendHardwareVolume(_volumeDb.value)
        }
    }

    /**
     * Call when the DAC is disconnected or Siphon engine is torn down.
     */
    fun release() {
        usbConnection = null
        _isUsbDirectActive.value = false
        mode = Mode.UNINITIALIZED
        Log.i(TAG, "VolumeController released")
    }

    // -------------------------------------------------------------------------
    // Public volume API  (called by MainActivity key interceptor & UI slider)
    // -------------------------------------------------------------------------

    /**
     * Increment or decrement by one physical button step.
     * Safe to call from any thread.
     *
     * @param direction +1 for volume-up, -1 for volume-down.
     */
    fun step(direction: Int) {
        val newDb = (_volumeDb.value + direction * STEP_DB).coerceIn(MIN_DB, MAX_DB)
        applyVolume(newDb)
    }

    /**
     * Set volume from a UI slider position (0 … STEPS).
     */
    fun setFromSlider(sliderValue: Int) {
        val newDb = stepsToDb(sliderValue.coerceIn(0, STEPS))
        applyVolume(newDb)
    }

    /**
     * Set volume directly in dB.
     */
    fun setDb(db: Float) {
        applyVolume(db.coerceIn(MIN_DB, MAX_DB))
    }

    // -------------------------------------------------------------------------
    // PCM buffer processing  (SOFTWARE mode)
    // -------------------------------------------------------------------------

    /**
     * Apply current software volume to a raw 16-bit PCM buffer.
     * Must be called on every buffer before it is submitted to DirectBitEngine.
     * No-op in HARDWARE mode (returns original buffer).
     *
     * Processing chain:
     *   Short → Float32 (normalise) → gain multiply → TPDF dither → Short
     */
    fun processPcmBuffer(input: ShortArray): ShortArray {
        if (mode != Mode.SOFTWARE) return input
        val gain = dbToLinear(_volumeDb.value)
        if (gain >= 1f) return input  // unity – nothing to do

        val inv = 1.0 / 32768.0
        val ditherAmp = inv  // 1 LSB of 16-bit for TPDF dither

        return ShortArray(input.size) { i ->
            val sample = input[i] * inv * gain          // normalise + gain
            // TPDF dither: two independent uniform random numbers subtracted
            val dither = (Math.random() - Math.random()) * ditherAmp
            val dithered = sample + dither
            (dithered * 32768.0)
                .toLong()
                .coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong())
                .toShort()
        }
    }

    /**
     * Apply current software volume to a 32-bit float PCM buffer (DSD-over-PCM
     * or high-res paths). No-op in HARDWARE mode.
     */
    fun processPcmBufferFloat(input: FloatArray): FloatArray {
        if (mode != Mode.SOFTWARE) return input
        val gain = dbToLinear(_volumeDb.value)
        if (gain >= 1f) return input
        return FloatArray(input.size) { i -> (input[i] * gain).coerceIn(-1f, 1f) }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private fun applyVolume(db: Float) {
        _volumeDb.value = db
        when (mode) {
            Mode.HARDWARE -> sendHardwareVolume(db)
            Mode.SOFTWARE -> { /* buffer processing happens in processPcmBuffer() */ }
            Mode.UNINITIALIZED -> Log.w(TAG, "applyVolume called before init()")
        }
        Log.d(TAG, "Volume → ${db}dB  (${dbToSteps(db)} steps)  mode=$mode")
    }

    /**
     * Send a UAC2 SET_CUR VOLUME_CONTROL control transfer to the DAC.
     * UAC2 volume uses Q7.8 fixed-point (1/256 dB per unit).
     */
    private fun sendHardwareVolume(db: Float) {
        val conn = usbConnection ?: run {
            Log.w(TAG, "sendHardwareVolume: no USB connection")
            return
        }

        // Clamp to UAC2 minimum (-127.996 dB) to avoid underflow
        val clampedDb   = db.coerceIn(-127.996f, 0f)
        val uac2Value   = (clampedDb * 256).toInt().toShort()  // Q7.8 fixed-point
        val data        = ByteArray(2)
        data[0] = (uac2Value.toInt() and 0xFF).toByte()
        data[1] = ((uac2Value.toInt() ushr 8) and 0xFF).toByte()

        val wValue = (UAC2_VOLUME_CS shl 8) or UAC2_MASTER_CH
        val wIndex = featureUnitId shl 8

        val result = conn.controlTransfer(
            UAC2_REQUEST_TYPE,
            UAC2_SET_CUR,
            wValue,
            wIndex,
            data,
            data.size,
            USB_TIMEOUT_MS
        )

        if (result < 0) {
            Log.w(TAG, "UAC2 SET_CUR failed (result=$result) – falling back to software")
            mode = Mode.SOFTWARE
        }
    }

    // -------------------------------------------------------------------------
    // Conversion utilities
    // -------------------------------------------------------------------------

    /** dB → linear multiplier:  M = 10^(G/20) */
    fun dbToLinear(db: Float): Float =
        10f.pow(db / 20f)

    /** Linear multiplier → dB */
    fun linearToDb(linear: Float): Float =
        if (linear <= 0f) MIN_DB else 20f * Math.log10(linear.toDouble()).toFloat()

    /** dB value → slider step (0…STEPS) */
    fun dbToSteps(db: Float): Int =
        ((db - MIN_DB) / (MAX_DB - MIN_DB) * STEPS)
            .toInt()
            .coerceIn(0, STEPS)

    /** Slider step (0…STEPS) → dB value */
    fun stepsToDb(steps: Int): Float =
        MIN_DB + steps.toFloat() / STEPS * (MAX_DB - MIN_DB)
}
val GlobalVolumeController = VolumeController()
