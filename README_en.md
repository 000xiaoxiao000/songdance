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
- Git LFS for large repository assets
- Android project config: `compileSdk 36`, `targetSdk 36`, `minSdk 24`
- Floating dancer audio capture works best on Android 10 (API 29) and above
- Package / Application ID: `com.example.myapplication`

## Quick Start

For users:

```bash
git clone https://github.com/000xiaoxiao000/songdance.git
cd songdance
git lfs install
git lfs pull
./gradlew :app:assembleDebug
```

> The repository includes `.lfsconfig`, which disables the GitHub-unsupported LFS locking check. Read-only clone and build flows do not require the author's GitHub credentials.

For contributors:

- Fork the repository and open a PR, or point `origin` to a remote where you have write access.
- To push directly to `000xiaoxiao000/songdance`, configure your own GitHub HTTPS token or SSH key first.
- SSH remotes are recommended to avoid IDE askpass credential prompts: `git remote set-url origin git@github.com:000xiaoxiao000/songdance.git`.
- If you only want to build or run the project, push access is not required.

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

## AI Model Training (Optional)

Regular users do not need to train a model. After cloning the repository, pulling Git LFS assets, and building the APK, they can use the included local inference resources. The training flow is mainly for two groups:

- **Advanced users**: Replace `pose_driven_generator.tflite` to better match their character style, motion style, or device-performance target.
- **Contributors/maintainers**: Reproduce, improve, or submit a new lightweight on-device model.

The recommended training path is an offline teacher model plus a lightweight student model:

```text
Run a pose-guided teacher model on a desktop/GPU machine
  -> Build a reference image / source pose / target pose / teacher frame dataset
  -> Train or distill a lightweight student model
  -> Export app/src/main/assets/models/pose_driven_generator.tflite
  -> Validate the TFLite input/output contract with the inspection script
```

Note: diffusion-based video/image animation models such as MimicMotion, MusePose, MagicAnimate, and AnimateAnyone are usually large and GPU-heavy, so they are not suitable for direct APK packaging. They are better used as offline teacher models; the app should only package a lightweight TFLite student model that can run on-device.

Dataset recommendation: prepare 200+ different person/character references, with 16–72 dancing frames per reference, covering large poses such as waving, jumping, kicking, and turning. Training with a single character or a small image set usually overfits and will not generalize to arbitrary uploaded people.

Training entry points:

```bash
bash tools/pose_model_factory/prepare_mimicmotion_teacher.sh
python3 tools/pose_model_factory/build_distill_dataset.py \
  --teacher-output build/pose_teacher/outputs \
  --dataset build/pose_student_dataset \
  --size 256
python3 tools/pose_model_factory/train_pose_student.py \
  --dataset build/pose_student_dataset \
  --tflite app/src/main/assets/models/pose_driven_generator.tflite \
  --size 256 \
  --epochs 80
python3 tools/pose_driven_model/inspect_pose_driven_tflite.py \
  app/src/main/assets/models/pose_driven_generator.tflite
```

For complete training guidance, model choices, data requirements, and export contracts, see `docs/building_a_pose_driven_model.md` and `tools/pose_model_factory/README.md`.

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
