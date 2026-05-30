#!/usr/bin/env python3
"""
Export a trained pose-driven TensorFlow SavedModel to the Android asset expected by the app.

Expected SavedModel signature concept:
  reference image + target pose map (+ optional source pose / mask) -> generated frame

The script does not train a model. It converts a real trained model into:
  app/src/main/assets/models/pose_driven_generator.tflite
"""
from __future__ import annotations

import argparse
from pathlib import Path
import sys


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Export pose-driven SavedModel to TFLite")
    parser.add_argument("--saved-model", required=True, type=Path, help="Path to trained TensorFlow SavedModel directory")
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("app/src/main/assets/models/pose_driven_generator.tflite"),
        help="Output .tflite path used by the Android app",
    )
    parser.add_argument(
        "--float16",
        action="store_true",
        help="Enable float16 weight quantization to reduce APK size",
    )
    parser.add_argument(
        "--select-tf-ops",
        action="store_true",
        help="Allow SELECT_TF_OPS if the model contains ops not natively supported by TFLite",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not args.saved_model.exists():
        print(f"SavedModel not found: {args.saved_model}", file=sys.stderr)
        return 2

    try:
        import tensorflow as tf
    except Exception as exc:  # pragma: no cover - environment dependent
        print("TensorFlow is required: pip install tensorflow", file=sys.stderr)
        print(exc, file=sys.stderr)
        return 2

    converter = tf.lite.TFLiteConverter.from_saved_model(str(args.saved_model))
    if args.float16:
        converter.optimizations = [tf.lite.Optimize.DEFAULT]
        converter.target_spec.supported_types = [tf.float16]
    if args.select_tf_ops:
        converter.target_spec.supported_ops = [
            tf.lite.OpsSet.TFLITE_BUILTINS,
            tf.lite.OpsSet.SELECT_TF_OPS,
        ]

    model = converter.convert()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(model)
    print(f"Wrote {args.output} ({len(model) / 1024 / 1024:.2f} MB)")
    print("Next: python tools/pose_driven_model/inspect_tflite.py --model", args.output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
