
# Dancing Overlay (Android)

[English](./README_en.md) | [中文](./README.md)

An Android application that shows a draggable floating dancing avatar on top of other apps and reacts to music beats.

## Features

- Draggable floating overlay (TYPE_APPLICATION_OVERLAY).
- Foreground service to keep the overlay alive while the app is backgrounded.
- OpenGL ES rendering pipeline prepared for a runtime avatar renderer, with a built-in PNG fallback sequence (e.g. `dancer_single1.png` ... `dancer_single9.png`).
- Beat detection implemented with a spectral-flux style onset detector using FFT frequency streams.
- Audio capture mode:
  - Playback capture only (Android 10+, requires MediaProjection permission).
- Settings include:
  - Beat sensitivity
  - Avatar size
  - Avatar opacity
  - Position lock
  - Auto-start on boot
- Guidance for battery optimization (can direct users to whitelist/ignore battery optimizations).

## Project structure

- `app/src/main/java/com/example/myapplication/MainActivity.kt` — Permission flow and main controls.
- `app/src/main/java/com/example/myapplication/OverlayService.kt` — Foreground service and overlay lifecycle.
- `app/src/main/java/com/example/myapplication/AudioCaptureManager.kt` — Audio capture loop and source plumbing.
- `app/src/main/java/com/example/myapplication/BeatDetector.kt` — FFT-based onset / beat event generator.
- `app/src/main/java/com/example/myapplication/FftAnalyzer.kt` — In-app FFT helper.
- `app/src/main/java/com/example/myapplication/DancerOverlayView.kt` — Floating overlay container and status UI.
- `app/src/main/java/com/example/myapplication/OpenGLESAvatarView.kt` — OpenGL ES avatar host with PNG fallback support.
- `app/src/main/java/com/example/myapplication/OpenGLESMotionController.kt` — Maps beat/audio events to renderer parameters.
- `app/src/main/java/com/example/myapplication/DancingAvatarView.kt` — Built-in 2D avatar renderer (Canvas fallback) and beat-response logic.
- `app/src/main/java/com/example/myapplication/SettingsActivity.kt` — Settings UI.
- `app/src/main/java/com/example/myapplication/OverlaySettings.kt` — Settings model and persistence helpers.
- `app/src/main/java/com/example/myapplication/BootCompletedReceiver.kt` — Optional boot-start receiver.
- `app/src/main/java/com/example/myapplication/PowerOptimizationHelper.kt` — Battery optimization helper.
- `app/src/test/java/com/example/myapplication/BeatDetectorTest.kt` — Unit tests for beat detection.

## How to build & run

1. (Optional) If you want to use the runtime OpenGL ES renderer, place model/texture assets into `app/src/main/assets/`.
2. Place the floating-avatar PNG resources directly in:

   - `app/src/main/res/drawable/avatar/`
   - `app/src/main/res/drawable/avatar1/`

   Use `dancer_single_begin.png` as the startup sprite, `dancer_single_end.png` as the idle/ending sprite, and `dancer_single1.png`, `dancer_single2.png` ... as the dance frame sequence.

   > Note: the old `app/src/main/assets/avatar*` directories are no longer used as the source of avatar images.
   > The current build exposes `res/drawable/avatar*` as path-readable raw files for runtime loading.

3. Build and install via Android Studio or with the Gradle wrapper.
4. Open the app and grant the required permissions:
   - Overlay (draw over other apps)
   - Recording / audio capture (when prompted)
5. Tap "Start Floating Dancer" to launch the overlay.
6. Accept the system capture prompt to allow playback capture.
7. Play music in a supported player. The overlay will react to beats and audio to drive avatar animations; PNG fallbacks are used until the runtime OpenGL renderer is initialized.
8. If notification access is granted, the overlay can also display the current song title/artist (used by optional dance-style heuristics).
9. Open Settings to tune sensitivity, size, opacity, position lock, and auto-start behavior.

## Notes & limitations

- Android enforces policies and permission restrictions on playback capture; some apps or streams may not be capturable due to DRM or upstream policy.
- Playback capture is supported on Android 10 (API 29) and above.

## Payment / Support

If you'd like to support the project, scan the payment QR code below.

Thank you for recognizing the value of this project — your support is the fuel that keeps it being updated.

## Screenshots

The images below illustrate the overlay and example behavior (in order):

<img src="effect_picture/effect_picture1.jpg" alt="Screenshot 1" width="240" style="max-width:100%;height:auto;" />

<img src="effect_picture/effect_picture2.jpg" alt="Screenshot 2" width="240" style="max-width:100%;height:auto;" />

<img src="effect_picture/effect_picture3.jpg" alt="Screenshot 3" width="240" style="max-width:100%;height:auto;" />

<img src="effect_picture/effect_picture4.jpg" alt="Screenshot 4" width="240" style="max-width:100%;height:auto;" />

<img src="Payment_Receipt_Code.png" alt="Payment Receipt Code" width="300" style="max-width:100%;height:auto;" />


