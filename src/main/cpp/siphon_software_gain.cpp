#include <atomic>
#include <cmath>
#include <random>
#include <algorithm>
#include <android/log.h>
#include <cstring>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  "SiphonGain", __VA_ARGS__)

static std::atomic<float> gLinearGain{1.0f};
static std::mt19937 gRng{42};
static std::uniform_real_distribution<float> gDist{-1.0f, 1.0f};

// Called from JNI nativeSetSoftwareVolume(percent: Int)
void setGainFromPercent(int percent) {
  if (percent <= 0) {
    gLinearGain.store(0.0f, std::memory_order_relaxed);
    LOGI("Software gain: 0%% -> mute");
    return;
  }
  if (percent >= 100) {
    gLinearGain.store(1.0f, std::memory_order_relaxed);
    LOGI("Software gain: 100%% -> unity (fast path active)");
    return;
  }
  // Log taper: normalized^2 gives perceptually linear volume
  // At 50% -> 0.25 linear = -12 dB, which "sounds like" half volume
  float normalized = percent / 100.0f;
  float linear = normalized * normalized;
  gLinearGain.store(linear, std::memory_order_relaxed);
  LOGI("Software gain: %d%% -> %.6f linear (%.2f dB)",
       percent, linear, 20.0f * log10f(linear + 1e-10f));
}

// Called from runWriter() on SCHED_FIFO thread for every PCM buffer
// samples: float32 interleaved PCM
// frameCount: number of audio frames in buffer
// channelCount: channels per frame (2 for stereo)
void applySoftwareGain(void* data, int numFrames, int channels, int format, float gain) {
  if (gain >= 1.0f) return;
  
  int total = numFrames * channels;
  if (gain <= 0.0f) {
      int bytesPerSample = (format == 0) ? 2 : (format == 1) ? 3 : 4;
      memset(data, 0, total * bytesPerSample);
      return;
  }

  if (format == 2) {
    float* samples = reinterpret_cast<float*>(data);
    const float ditherScale = 1.0f / 8388608.0f;
    for (int i = 0; i < total; i++) {
      float dither = (gDist(gRng) - gDist(gRng)) * ditherScale;
      float out = (samples[i] * gain) + dither;
      samples[i] = std::max(-1.0f, std::min(1.0f, out));
    }
  } else if (format == 0) {
    int16_t* samples = reinterpret_cast<int16_t*>(data);
    for (int i = 0; i < total; i++) {
      float dither = gDist(gRng) - gDist(gRng);
      float out = (samples[i] * gain) + dither;
      samples[i] = static_cast<int16_t>(std::max(-32768.0f, std::min(32767.0f, out)));
    }
  } else if (format == 1) {
    uint8_t* bytes = reinterpret_cast<uint8_t*>(data);
    for (int i = 0; i < total; i++) {
        int idx = i * 3;
        int32_t sample = (bytes[idx] & 0xFF) | ((bytes[idx+1] & 0xFF) << 8) | (static_cast<int8_t>(bytes[idx+2]) << 16);
        float dither = gDist(gRng) - gDist(gRng);
        float out = (sample * gain) + dither;
        int32_t val = static_cast<int32_t>(std::max(-8388608.0f, std::min(8388607.0f, out)));
        bytes[idx] = val & 0xFF;
        bytes[idx+1] = (val >> 8) & 0xFF;
        bytes[idx+2] = (val >> 16) & 0xFF;
    }
  }
}

float getCurrentGainLinear() {
  return gLinearGain.load(std::memory_order_relaxed);
}
