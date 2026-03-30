#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cstring>
#include <mutex>

#include "siphon_usb_driver.cpp"
#include "siphon_software_gain.h"

JavaVM* gJvm = nullptr;

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    gJvm = vm;
    return JNI_VERSION_1_6;
}

void notifyDeviceLost() {
    if (!gJvm) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    int status = gJvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        if (gJvm->AttachCurrentThread(&env, nullptr) == 0) attached = true;
    }
    if (env) {
        jclass clazz = env->FindClass("chromahub/rhythm/app/infrastructure/audio/siphon/SiphonIsochronousEngine");
        if (clazz) {
            jmethodID method = env->GetStaticMethodID(clazz, "onDeviceLost", "()V");
            if (method) env->CallStaticVoidMethod(clazz, method);
            env->DeleteLocalRef(clazz);
        }
        if (attached) gJvm->DetachCurrentThread();
    }
}

#define LOG_TAG "SiphonEngineJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)


// ── Global state ──────────────────────────────────────────────────────
// FIX #6: Mutex protects gUsbDriver against concurrent init/release calls
// triggered by rapid MTK HAL disconnect bursts (multiple port state callbacks)
static std::mutex gDriverMutex;
static SiphonUsbDriver* gUsbDriver = nullptr;
static bool gUsbIsHardwareMode = false;
static int gChannels = 2;
static int gBitDepth = 16;
static float gCurrentVolume = 1.0f;

static void releaseAll() {
    std::lock_guard<std::mutex> lock(gDriverMutex);
    if (gUsbDriver) {
        gUsbDriver->release();
        delete gUsbDriver;
        gUsbDriver = nullptr;
    }
}

// ── JNI: Core Siphon functions ───────────────────────────────────────

extern "C" {

/**
 * Single init path — USB isochronous via libusb.
 * No tiers, no AAudio, no fallback.
 */
JNIEXPORT jint JNICALL
Java_chromahub_rhythm_app_infrastructure_audio_siphon_SiphonIsochronousEngine_nativeInit(
    JNIEnv*, jclass,
    jint fd,
    jint packetSize,
    jint sampleRate,
    jint channelCount,
    jint bitDepth,
    jint interfaceId,
    jint endpointAddress
) {
    releaseAll();

    std::lock_guard<std::mutex> lock(gDriverMutex);
    gChannels = channelCount > 0 ? channelCount : 2;
    gBitDepth = bitDepth > 0 ? bitDepth : 16;
    gCurrentVolume = 1.0f;

    if (fd <= 0) {
        LOGE("Invalid file descriptor: %d", fd);
        return -1;
    }

    gUsbDriver = new SiphonUsbDriver();
    const int rc = gUsbDriver->initialize(fd, sampleRate, gChannels, gBitDepth, packetSize, interfaceId, endpointAddress);
    if (rc != 0) {
        LOGE("SiphonUsbDriver init failed: %d", rc);
        delete gUsbDriver;
        gUsbDriver = nullptr;
        return rc;
    }
    gUsbDriver->start();

    LOGI("Siphon Direct active: USB isochronous | rate=%d ch=%d depth=%d fd=%d pktSize=%d",
         sampleRate, gChannels, gBitDepth, fd, packetSize);
    return 0;
}

JNIEXPORT jint JNICALL
Java_chromahub_rhythm_app_infrastructure_audio_siphon_SiphonIsochronousEngine_nativeWritePcm(
    JNIEnv* env, jclass, jbyteArray audioData, jint sizeBytes
) {
    if (!audioData || sizeBytes <= 0 || !gUsbDriver) return -1;
    jbyte* data = env->GetByteArrayElements(audioData, nullptr);
    if (!data) return -1;

    // Get the current software gain from the atomic variable in siphon_software_gain.cpp
    float currentGain = getCurrentGainLinear();
    
    // Apply software gain if needed (only when NOT in hardware mode)
    // When hardware mode is ON, data passes through UNMODIFIED = BIT-PERFECT
    if (!gUsbIsHardwareMode && currentGain < 0.999f) {
        const int bytesPerFrame = gChannels * ((gBitDepth == 24) ? 3 : (gBitDepth / 8));
        const int frames = bytesPerFrame > 0 ? (sizeBytes / bytesPerFrame) : 0;
        int format = 0;
        if (gBitDepth == 24) format = 1;
        else if (gBitDepth == 32) format = 2;
        applySoftwareGain(data, frames, gChannels, format, currentGain);
    }

    int result = gUsbDriver->submitAudioData(
        reinterpret_cast<uint8_t*>(data),
        static_cast<size_t>(sizeBytes),
        gUsbIsHardwareMode,
        currentGain
    );

    env->ReleaseByteArrayElements(audioData, data, JNI_ABORT);
    return result;
}

JNIEXPORT jint JNICALL
Java_chromahub_rhythm_app_infrastructure_audio_siphon_SiphonIsochronousEngine_nativeSetHardwareVolume(
    JNIEnv*, jclass, jint percent
) {
    if (!gUsbDriver) return -3;
    return gUsbDriver->setHardwareVolume((int)percent);
}

JNIEXPORT void JNICALL
Java_chromahub_rhythm_app_infrastructure_audio_siphon_SiphonIsochronousEngine_nativeSetSoftwareVolume(
    JNIEnv*, jclass, jint percent
) {
    setGainFromPercent((int)percent);
}

JNIEXPORT void JNICALL
Java_chromahub_rhythm_app_infrastructure_audio_siphon_SiphonIsochronousEngine_nativeSetVolumeMode(
    JNIEnv*, jclass, jboolean useHardware
) {
    gUsbIsHardwareMode = useHardware;
}

JNIEXPORT jfloat JNICALL
Java_chromahub_rhythm_app_infrastructure_audio_siphon_SiphonIsochronousEngine_nativeGetCurrentVolume(
    JNIEnv*, jclass
) {
    return (jfloat)getCurrentGainLinear();
}

JNIEXPORT jint JNICALL
Java_chromahub_rhythm_app_infrastructure_audio_siphon_SiphonIsochronousEngine_nativeAttachKernelDriver(
    JNIEnv* env, jclass clazz, jint fd
) {
    libusb_context* ctx = nullptr;
    
    libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY, nullptr);
    
    int init_err = libusb_init(&ctx);
    if (init_err != 0 || ctx == nullptr) {
        LOGE("nativeAttachKernelDriver: libusb_init failed %d. Aborting sys device wrap.", init_err);
        if (ctx) libusb_exit(ctx);
        return init_err;
    }

    libusb_device_handle* handle = nullptr;
    int r = libusb_wrap_sys_device(ctx, (intptr_t)fd, &handle);
    if (r < 0) {
      LOGW("nativeAttachKernelDriver: wrap failed: %s", libusb_error_name(r));
      libusb_exit(ctx);
      return r;
    }

    r = libusb_attach_kernel_driver(handle, 1);
    if (r == 0) {
      LOGI("nativeAttachKernelDriver: snd-usb-audio re-attached. Zombie cleared.");
    } else if (r == LIBUSB_ERROR_BUSY || r == LIBUSB_ERROR_NOT_FOUND) {
      LOGI("nativeAttachKernelDriver: %s - already clean", libusb_error_name(r));
      r = 0;
    } else if (r == LIBUSB_ERROR_NO_DEVICE) {
      LOGI("nativeAttachKernelDriver: device already unplugged — nothing to re-attach (clean exit)");
      r = 0;
    } else {
      LOGW("nativeAttachKernelDriver: failed: %s", libusb_error_name(r));
    }

    libusb_close(handle);
    libusb_exit(ctx);
    return r;
}

JNIEXPORT void JNICALL
Java_chromahub_rhythm_app_infrastructure_audio_siphon_SiphonIsochronousEngine_nativeRelease(
    JNIEnv*, jclass
) {
    releaseAll();
}

}  // extern "C"
