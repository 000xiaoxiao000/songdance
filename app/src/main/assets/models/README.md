# Local AI Models

This directory is packaged into the APK and is the only source for local AI image processing.

Required runtime flow for AI singing/dancing frames:

1. `movenet_thunder.tflite` detects the uploaded person's source pose.
2. Kotlin generates a continuous singing/dancing target-pose sequence.
3. `PoseMapRenderer` renders each target pose into a pose-condition image.
4. `pose_driven_generator.tflite` must locally run: reference image + target pose map (+ optional source pose/mask) -> generated frame.
5. The app saves the generated frames as `dancer_single1.png`, `dancer_single2.png`, ... with the same output size as the uploaded image.

There is intentionally no Canvas/bitmap animation fallback in this path.
If `pose_driven_generator.tflite` is missing or incompatible, AI generation fails instead of silently producing fake motion.

Required model asset:

- File: `pose_driven_generator.tflite`
- Type: true pose-driven image generation model
- Minimum inputs: reference image tensor and target pose image tensor
- Optional inputs: source pose image tensor, person mask tensor
- Output: RGB/RGBA generated image tensor

Recommended tensor shapes:

- `reference`: `[1, 256, 256, 3]` or `[1, 256, 256, 4]`
- `target_pose`: `[1, 256, 256, 3]`
- `source_pose`: `[1, 256, 256, 3]` optional
- `mask`: `[1, 256, 256, 1]` optional
- `output`: `[1, 256, 256, 3]` or `[1, 256, 256, 4]`

Legacy/auxiliary models currently present:

- `movenet_thunder.tflite`: pose detection.
- `anime_style_transfer.tflite`, `style_predict.tflite`, `style_transform.tflite`: not used by the true pose-driven frame generation path.

Current bundled `pose_driven_generator.tflite`:

- Trained/distilled locally from `/Users/xiaoxiao/javaProject/songdance/图片集合1.zip`.
- Resolution: 128x128 model input/output, restored by the app to uploaded image size.
- Inputs verified:
  - `serving_default_reference:0` `[1, 128, 128, 4]` `float32`
  - `serving_default_target_pose:0` `[1, 128, 128, 3]` `float32`
- Output verified:
  - `StatefulPartitionedCall_1:0` `[1, 128, 128, 4]` `float32`
- This is a small-data avatar-specific distilled model, not a general-purpose AnimateAnyone/MagicAnimate-class model.
