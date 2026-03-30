#  Siphon Audio Engine

**Siphon Audio Engine** is an open-source, bit-perfect custom USB audio engine for Android. 

Built with Kotlin and C++, Siphon completely bypasses the Android OS audio stack (AudioFlinger / AudioTrack) to deliver unmodified, raw audio streams directly to external USB Digital-to-Analog Converters (DACs).

## Features

* **Bit-Perfect Playback**: Prevents Android from resampling, downmixing, or altering your audio data. What goes into the engine is exactly what arrives at the DAC.
* **Direct USB Isochronous Transfers**: Powered by a statically-linked `libusb` core, Siphon communicates directly with USB audio hardware in user-space.
* **ExoPlayer Ready**: Includes a `SiphonUsbAudioSink` that seamlessly plugs into Android's `ExoPlayer` / `Media3` ecosystem.
* **Custom C++ DSP Pipeline**: A highly optimized native pipeline featuring:
  * Bit-perfect software volume scaling (`siphon_software_gain`)
  * Parametric audio effects & EQ (`siphon_dsp_pipeline`)
  * Dynamic format resampling (`siphon_resampler`)
* **Hardware Interrogation**: Automatically parses USB Audio Class (UAC) descriptors to identify supported sample rates, bit depths, and channel masks (`SiphonDescriptorParser`).

## Architecture

Siphon is divided into two layers:
1. **The Native Core (C++)**: Handles the heavy lifting—memory management, fast-math DSP execution, and `libusb` asynchronous USB transfers.
2. **The Kotlin Framework**: Manages Android lifecycles, USB permission requests, ExoPlayer integration, and component routing (`SiphonManager`).

## Installation & Setup

1. Clone or download the `siphon-engine` module into your project.
2. Add it to your `settings.gradle.kts`:
   ```kotlin
   include(":siphon-engine")
   ```
3. Add the dependency to your app module's `build.gradle.kts`:
   ```kotlin
   implementation(project(":siphon-engine"))
   ```
4. *Note: Ensure your project supports NDK/CMake as this library compiles a custom C++ JNI bridge.*

## Usage

To hook Siphon into your ExoPlayer instance:

```kotlin
// 1. Initialize Siphon Manager
val siphonManager = SiphonManager(context)

// 2. Request USB permissions and attach device
siphonManager.start(usbDevice)

// 3. Inject the custom AudioSink into ExoPlayer
val siphonSink = SiphonUsbAudioSink(siphonManager)
val player = ExoPlayer.Builder(context)
    .setRenderersFactory(SiphonRenderersFactory(context, siphonSink))
    .build()
```

## Contributing
Contributions are welcome! If you want to add support for UAC3, new DSD formats, or optimize the DSP pipeline, feel free to open a Pull Request.

##  License
MIT License
