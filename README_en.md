# Dancing Overlay (Android)

[English](./README_en.md) | [中文](./README.md)

Dancing Overlay is an Android floating-dancer app. It shows a draggable avatar above other apps, captures media playback audio, switches motion based on volume and beats, and can turn a user-uploaded character image into a local AI-generated dancing frame sequence.

## Current Features

- **Floating dancer**: Uses `TYPE_APPLICATION_OVERLAY` to draw above other apps; supports dragging and position lock.
- **Foreground service**: `OverlayService` keeps the overlay alive with a foreground notification and supports hide/show/stop actions.
- **Playback audio capture**: On Android 10+, uses MediaProjection + AudioPlaybackCapture for media/game audio.
- **Beat-driven animation**: `BeatDetector` uses FFT / spectral-flux style onset detection and maps audio events to avatar motion.
- **OpenGL ES rendering**: `OpenGLESView` / `OpenGLESRenderer` apply mesh deformation and beat pulse effects to avatar textures.
- **Two avatar sets**: Settings can manage Avatar Set 1 / Avatar Set 2 and switch the active floating-avatar style.
- **Local AI dance-frame generation**: Upload one image, choose 10–100 frames, and generate a coherent dancing sequence with MoveNet pose detection + `pose_driven_generator.tflite`.
- **Offline-first inference**: Models live under `app/src/main/assets/models/`; the app does not require an external server for inference.
- **Battery optimization guidance**: Includes an entry point to the system whitelist / ignore battery optimization screen.

## Requirements

- Android Studio / Gradle Wrapper
- JDK 17
- Android project config: `compileSdk 36`, `targetSdk 36`, `minSdk 24`
- Floating dancer audio capture works best on Android 10 (API 29) and above
- Package / Application ID: `com.example.myapplication`

## Quick Start

```bash
./gradlew :app:assembleDebug
```

You can also open the project in Android Studio and run the `app` module.

First-run flow:

1. Install and open the app.
2. Tap **Grant Overlay Permission** and allow drawing over other apps.
3. Tap **Start Floating Dancer**.
4. Grant microphone permission, notification permission on Android 13+, and the system capture prompt.
5. Play audio in a supported music/video/game app. The floating dancer reacts to audio activity and beats.
6. Open **Settings** to tune sensitivity, size, opacity, position lock, auto-start, and related options.

## Avatar Sets and AI Generation

The settings page provides two custom avatar-set managers:

- **Manage Avatar Set 1**: Default floating-avatar source, stored in the app-private directory `files/custom_avatars/set1/`.
- **Manage Avatar Set 2**: Alternate avatar source, stored in the app-private directory `files/custom_avatars/set2/`.

The avatar-set page supports:

- Single-image upload, batch upload, preview, deletion, and clear-all.
- Frame count selection from 10 to 100, defaulting to 30.
- Tap **AI Generate Dancing Motion**, select one character image, and generate `dancer_single1.png`, `dancer_single2.png`, ... frame sequences.
- Each AI generation clears old `dancer_single*.png` generated frames in the target set to avoid mixing old and new motion.

AI inference pipeline:

```text
Uploaded image
  -> ImageCompressor compression / EXIF rotation fix
  -> PoseDetector 17-keypoint detection (MoveNet first, simplified pose fallback)
  -> AvatarStyleFrameRenderer / TruePoseDrivenModel target-pose frame generation
  -> DanceFrameGenerator saves frames into the active avatar set
  -> DancerOverlayView loads and plays the frame sequence
```

## Model Assets

Current model paths:

- `app/src/main/assets/models/movenet_thunder.tflite`: MoveNet single-person pose detection model.
- `app/src/main/assets/models/pose_driven_generator.tflite`: Local pose-driven image generation model.

`PoseDetector` tries to load `movenet_thunder.tflite` first. If loading fails, it falls back to a simplified pose estimator based on image proportions.

`TruePoseDrivenModel` expects a compatible TFLite image-generation model:

- Inputs are NHWC image tensors.
- At least two logical inputs are required: reference/source image and target pose/condition/skeleton.
- An optional source-pose input is supported.
- Output must be an RGB/RGBA image tensor.

See these files for model training, distillation, and validation details:

- `docs/pose_driven_generator_contract.md`
- `docs/pose_driven_model_options.md`
- `docs/building_a_pose_driven_model.md`
- `tools/pose_model_factory/README.md`

Validate a model against the app contract with:

```bash
python3 tools/pose_driven_model/inspect_pose_driven_tflite.py \
  app/src/main/assets/models/pose_driven_generator.tflite
```

## Project Structure

```text
app/src/main/java/com/example/myapplication/
├── MainActivity.kt                    # Permission flow and main screen
├── OverlayService.kt                  # Foreground service, overlay lifecycle, audio callbacks
├── DancerOverlayView.kt               # Overlay UI, dragging, frame playback
├── AudioCaptureManager.kt             # MediaProjection playback capture
├── BeatDetector.kt / FftAnalyzer.kt   # FFT beat detection
├── OpenGLESView.kt / OpenGLESRenderer.kt
│                                      # OpenGL ES avatar rendering and mesh deformation
├── AvatarLoader.kt                    # Avatar-set loading, caching, preprocessing
├── AvatarImageManager.kt              # User avatar-set file management
├── AvatarUploadActivity.kt            # Upload, batch management, AI-generation entry
├── AIModelManager.kt                  # Pose detection and dance-frame generation controller
├── PoseDetector.kt                    # MoveNet / simplified pose detection
├── TruePoseDrivenModel.kt             # Real TFLite pose-driven generator adapter
├── DanceFrameGenerator.kt             # Saves generated frames into avatar sets
├── AvatarSpriteChoreographyEngine.kt  # Sprite-frame choreography
├── RhythmStyleEngine.kt               # Rhythm-style calculation
├── SettingsActivity.kt                # Settings screen
├── OverlaySettings.kt                 # Settings persistence
└── BootCompletedReceiver.kt           # Optional boot auto-start
```

Other directories:

- `app/src/main/assets/models/`: TFLite models.
- `app/src/main/assets/lottie/`: Lottie assets.
- `app/src/main/res/xml/root_preferences.xml`: Settings screen configuration.
- `docs/`: Pose-driven model documentation.
- `tools/pose_model_factory/`: Teacher-model distillation and student-model export tools.
- `tools/pose_driven_model/`: TFLite model inspection tools.
- `effect_picture/`: Project screenshots.

## Permissions

The app declares and uses these main permissions:

- `SYSTEM_ALERT_WINDOW`: Draw the floating overlay.
- `RECORD_AUDIO`: Read audio data together with playback capture.
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MEDIA_PROJECTION`: Keep playback capture running in a foreground service.
- `POST_NOTIFICATIONS`: Foreground-service notification on Android 13+.
- `RECEIVE_BOOT_COMPLETED`: Optional boot auto-start.

## Notes

- Android playback capture is limited by OS version, app policy, and DRM; some players or streams cannot be captured.
- Android 14+ is stricter about MediaProjection foreground services; the service must be started with user-authorized capture data.
- AI frame generation can be memory- and time-intensive. Use clear images with a complete, reasonably sized subject.
- Uploaded images larger than 5 MB are rejected for AI generation.
- The current release build uses a debug-keystore fallback signing config. Replace it with production signing before publishing.

## Screenshots

<img src="effect_picture/effect_picture1.jpg" alt="Screenshot 1" width="240" style="max-width:100%;height:auto;" />

<img src="effect_picture/effect_picture2.jpg" alt="Screenshot 2" width="240" style="max-width:100%;height:auto;" />

<img src="effect_picture/effect_picture3.jpg" alt="Screenshot 3" width="240" style="max-width:100%;height:auto;" />

<img src="effect_picture/effect_picture4.jpg" alt="Screenshot 4" width="240" style="max-width:100%;height:auto;" />
