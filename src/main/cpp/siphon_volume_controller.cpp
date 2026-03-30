#include <algorithm>
#include <cmath>
#include <cstdint>
#include <random>

namespace directbit {

static thread_local std::mt19937_64 gRng{0xC0FFEEu};
static thread_local std::uniform_real_distribution<double> gDist(-0.5, 0.5);

static inline double tpdfDither() {
    return gDist(gRng) + gDist(gRng);
}

void applySoftwareGain(void* buffer, int frames, int channels, int format, double gain) {
    if (!buffer || frames <= 0 || channels <= 0) return;

    const int sampleCount = frames * channels;
    if (format == 0) {  // PCM16
        auto* p = static_cast<int16_t*>(buffer);
        const double lsb = 1.0 / 32768.0;
        for (int i = 0; i < sampleCount; ++i) {
            const double x = static_cast<double>(p[i]) / 32768.0;
            const double y = std::clamp((x * gain) + (tpdfDither() * lsb), -1.0, 1.0);
            p[i] = static_cast<int16_t>(std::lrint(y * 32767.0));
        }
        return;
    }

    if (format == 1) {  // PCM24 packed little-endian
        auto* p = static_cast<uint8_t*>(buffer);
        const double lsb = 1.0 / 8388608.0;
        for (int i = 0; i < sampleCount; ++i) {
            const int idx = i * 3;
            int32_t v = (p[idx] & 0xFF) | ((p[idx + 1] & 0xFF) << 8) | (p[idx + 2] << 16);
            if (v & 0x800000) v |= ~0xFFFFFF;
            const double x = static_cast<double>(v) / 8388608.0;
            const double y = std::clamp((x * gain) + (tpdfDither() * lsb), -1.0, 1.0);
            int32_t out = static_cast<int32_t>(std::lrint(y * 8388607.0));
            p[idx] = static_cast<uint8_t>(out & 0xFF);
            p[idx + 1] = static_cast<uint8_t>((out >> 8) & 0xFF);
            p[idx + 2] = static_cast<uint8_t>((out >> 16) & 0xFF);
        }
        return;
    }

    auto* pf = static_cast<float*>(buffer);  // PCM32F
    const double lsb = 1.0 / 8388608.0;
    for (int i = 0; i < sampleCount; ++i) {
        const double y = std::clamp((static_cast<double>(pf[i]) * gain) + (tpdfDither() * lsb), -1.0, 1.0);
        pf[i] = static_cast<float>(y);
    }
}

}  // namespace directbit
