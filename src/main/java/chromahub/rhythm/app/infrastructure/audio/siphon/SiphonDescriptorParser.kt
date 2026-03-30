package chromahub.rhythm.app.infrastructure.audio.siphon

import android.util.Log

object SiphonDescriptorParser {
    fun parse(rawDescriptors: ByteArray?): SiphonDeviceCapabilities {
        if (rawDescriptors == null) return safeDefault()
        
        var supportsHardwareVolume = false
        var featureUnitId = 0
        val sampleRates = mutableSetOf<Int>()
        var maxBitDepth = 16
        var endpointAddress = 0
        var maxPacketSize = 0

        var i = 0
        while (i < rawDescriptors.size) {
            val length = rawDescriptors[i].toInt() and 0xFF
            if (length <= 2) {
                i++ 
                continue
            }
            if (i + length > rawDescriptors.size) break
            
            val type = rawDescriptors[i + 1].toInt() and 0xFF
            
            // 0x24 is CS_INTERFACE (Class-Specific)
            if (type == 0x24) {
                val subtype = rawDescriptors[i + 2].toInt() and 0xFF
                when (subtype) {
                    0x06 -> { // FEATURE_UNIT
                        supportsHardwareVolume = true
                        featureUnitId = rawDescriptors[i + 3].toInt() and 0xFF
                        Log.d("SiphonParser", "Found Feature Unit ID: $featureUnitId")
                    }
                    0x02 -> { // FORMAT_TYPE (UAC1)
                        if (length >= 8) {
                            val formatType = rawDescriptors[i + 3].toInt() and 0xFF
                            if (formatType == 0x01) { // FORMAT_TYPE_I
                                val bitDepth = rawDescriptors[i + 6].toInt() and 0xFF
                                maxBitDepth = maxOf(maxBitDepth, bitDepth)
                                
                                val nrRates = rawDescriptors[i + 7].toInt() and 0xFF
                                if (nrRates == 0) { // Continuous range
                                    if (length >= 14) {
                                        val minRate = parse24Bit(rawDescriptors, i + 8)
                                        val maxRate = parse24Bit(rawDescriptors, i + 11)
                                        for (rate in listOf(44100, 48000, 88200, 96000, 176400, 192000)) {
                                            if (rate in minRate..maxRate) sampleRates.add(rate)
                                        }
                                    }
                                } else {
                                    for (k in 0 until nrRates) {
                                        val offset = i + 8 + (k * 3)
                                        if (offset + 2 < i + length) {
                                            val rate = parse24Bit(rawDescriptors, offset)
                                            if (rate > 0) sampleRates.add(rate)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (type == 0x05) { // ENDPOINT descriptor
                if (length >= 7) {
                    val addr = rawDescriptors[i + 2].toInt() and 0xFF
                    val attr = rawDescriptors[i + 3].toInt() and 0xFF
                    if ((attr and 0x03) == 0x01) { // Isochronous
                        endpointAddress = addr
                        maxPacketSize = (rawDescriptors[i + 4].toInt() and 0xFF) or 
                                       ((rawDescriptors[i + 5].toInt() and 0xFF) shl 8)
                        Log.d("SiphonParser", "Found Isochronous Endpoint: 0x${endpointAddress.toString(16)}, MaxSize: $maxPacketSize")
                    }
                }
            }
            
            i += length
        }

        if (sampleRates.isEmpty()) {
            sampleRates.addAll(listOf(44100, 48000))
        }

        val formats = sampleRates.map { rate ->
            val encoding = when (maxBitDepth) {
                32 -> androidx.media3.common.C.ENCODING_PCM_32BIT
                24 -> androidx.media3.common.C.ENCODING_PCM_24BIT
                else -> androidx.media3.common.C.ENCODING_PCM_16BIT
            }
            SiphonAudioFormat(rate, maxBitDepth, 2, encoding)
        }

        return SiphonDeviceCapabilities(
            supportsHardwareVolume = supportsHardwareVolume,
            featureUnitId = featureUnitId,
            volumeMinDb256 = -32768, // Default min, will be refined via control transfer
            volumeMaxDb256 = 0,      // Default max
            volumeSteps = 256,
            endpointAddress = endpointAddress,
            maxPacketSize = maxPacketSize,
            supportedFormats = formats
        )
    }

    private fun parse24Bit(data: ByteArray, offset: Int): Int {
        if (offset + 2 >= data.size) return 0
        return (data[offset].toInt() and 0xFF) or 
               ((data[offset+1].toInt() and 0xFF) shl 8) or 
               ((data[offset+2].toInt() and 0xFF) shl 16)
    }

    private fun safeDefault() = SiphonDeviceCapabilities(
        supportsHardwareVolume = false,
        featureUnitId = 0,
        volumeMinDb256 = -32768,
        volumeMaxDb256 = 0,
        volumeSteps = 256,
        endpointAddress = 0,
        maxPacketSize = 0,
        supportedFormats = listOf(
            SiphonAudioFormat(44100, 16, 2, androidx.media3.common.C.ENCODING_PCM_16BIT),
            SiphonAudioFormat(48000, 16, 2, androidx.media3.common.C.ENCODING_PCM_16BIT)
        )
    )
}
