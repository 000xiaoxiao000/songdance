# Pose-Driven Generator Model Pipeline

The Android app now expects a real local model at:

```text
app/src/main/assets/models/pose_driven_generator.tflite
```

This file is not a style-transfer model. It must be a pose-driven image generation model:

```text
reference character image + target pose map (+ optional source pose / mask) -> generated character frame
```

## Why no bundled model?

A ready-to-use public `.tflite` for high-quality pose-guided character/person generation is not currently part of this repo. Existing research projects such as Pose-Guided Person Image Generation, AnimateAnyone, and MagicAnimate are commonly PyTorch/diffusion pipelines, not drop-in Android TFLite assets. The app therefore fails clearly when this model is missing instead of using Canvas/bitmap fake motion.

## Required Android contract

`LocalPoseDrivenGenerator` supports a flexible multi-input TFLite model, but the recommended tensor contract is:

| Tensor | Shape | Type | Required |
| --- | --- | --- | --- |
| `reference` | `[1, 256, 256, 3]` or `[1, 256, 256, 4]` | `float32`/`uint8`/`int8` | yes |
| `target_pose` | `[1, 256, 256, 3]` | `float32`/`uint8`/`int8` | yes |
| `source_pose` | `[1, 256, 256, 3]` | `float32`/`uint8`/`int8` | optional |
| `mask` | `[1, 256, 256, 1]` | `float32`/`uint8`/`int8` | optional |
| `output` | `[1, 256, 256, 3]` or `[1, 256, 256, 4]` | `float32`/`uint8`/`int8` | yes |

Input tensor names should contain useful tokens when possible:

- Reference: `reference`, `ref`, `source`, `image`, or `person`
- Target pose: `target_pose`, `target`, `pose`, `condition`, or `skeleton`
- Optional source pose: `source_pose`, `src_pose`, or `input_pose`
- Optional mask: `mask`, `seg`, or `alpha`

If names do not match, the adapter falls back to input order:

1. reference image
2. target pose map
3. optional source pose
4. optional mask

## Conversion from TensorFlow SavedModel

After training/exporting your pose-driven generator as a TensorFlow SavedModel:

```bash
python tools/pose_driven_model/export_tflite.py \
  --saved-model /path/to/saved_model \
  --output app/src/main/assets/models/pose_driven_generator.tflite \
  --float16
```

If conversion fails due to unsupported ops, try:

```bash
python tools/pose_driven_model/export_tflite.py \
  --saved-model /path/to/saved_model \
  --output app/src/main/assets/models/pose_driven_generator.tflite \
  --select-tf-ops
```

Then inspect the result:

```bash
python tools/pose_driven_model/inspect_tflite.py \
  --model app/src/main/assets/models/pose_driven_generator.tflite
```

Finally rebuild the Android app:

```bash
./gradlew :app:assembleDebug
```

## Practical model recommendation

For the quality requirement "no ghosting, no overlap, no broken body, every frame is a different continuous dance pose", a small plain TFLite style transfer model is not enough. You need a model trained specifically for pose transfer / character animation. A practical training target is a U-Net / diffusion-distilled generator conditioned on:

- the reference person image,
- a rendered target pose map,
- optionally the source pose and foreground mask.

The Android adapter is already wired for that contract.
