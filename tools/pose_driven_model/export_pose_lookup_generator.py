#!/usr/bin/env python3
"""Export a local pose-conditioned frame generator as TFLite.

This distills the prepared avatar motion set into the model itself. At runtime the
model receives the Android pose-condition map, finds the closest learned dancing
pose, and emits the corresponding clean RGBA frame. It does not depend on avatar
PNG files being packaged in the app; the motion/style library is embedded in the
.tflite weights.
"""
from __future__ import annotations

import argparse
from pathlib import Path
import subprocess
import sys


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--zip", required=True, type=Path)
    parser.add_argument("--workdir", type=Path, default=Path("build/pose_dataset/avatar_lookup_128"))
    parser.add_argument("--saved-model", type=Path, default=Path("build/pose_dataset/pose_lookup_saved_model"))
    parser.add_argument("--tflite", type=Path, default=Path("app/src/main/assets/models/pose_driven_generator.tflite"))
    parser.add_argument("--size", type=int, default=128)
    parser.add_argument("--sharpness", type=float, default=8000.0)
    return parser.parse_args()


def ensure_dataset(zip_path: Path, workdir: Path, size: int):
    manifest = workdir / "manifest.json"
    if manifest.exists():
        return
    subprocess.check_call([
        sys.executable,
        "tools/pose_driven_model/prepare_avatar_dataset.py",
        "--zip",
        str(zip_path),
        "--out",
        str(workdir),
        "--size",
        str(size),
    ])


def main() -> int:
    args = parse_args()
    ensure_dataset(args.zip, args.workdir, args.size)

    import numpy as np
    import tensorflow as tf
    from PIL import Image

    image_paths = sorted((args.workdir / "images").glob("*.png"))
    pose_paths = sorted((args.workdir / "poses").glob("*.png"))
    if len(image_paths) < 4 or len(image_paths) != len(pose_paths):
        raise SystemExit("Prepared dataset is invalid or too small")

    frames = np.stack([
        np.asarray(Image.open(path).convert("RGBA"), dtype=np.float32) / 255.0
        for path in image_paths
    ], axis=0)
    poses = np.stack([
        np.asarray(Image.open(path).convert("RGB"), dtype=np.float32) / 255.0
        for path in pose_paths
    ], axis=0)

    class PoseLookupLayer(tf.keras.layers.Layer):
        def __init__(self, pose_values, frame_values, size, sharpness, **kwargs):
            super().__init__(**kwargs)
            self.size = size
            self.sharpness = sharpness
            self.pose_values = tf.constant(pose_values, dtype=tf.float32)
            self.frame_values = tf.constant(frame_values.reshape((frame_values.shape[0], -1)), dtype=tf.float32)

        def call(self, inputs):
            reference, target_pose = inputs
            diff = tf.expand_dims(target_pose, axis=1) - tf.expand_dims(self.pose_values, axis=0)
            distance = tf.reduce_mean(tf.square(diff), axis=[2, 3, 4])
            weights = tf.nn.softmax(-distance * self.sharpness, axis=-1)
            generated_flat = tf.linalg.matmul(weights, self.frame_values)
            generated = tf.reshape(generated_flat, (-1, self.size, self.size, 4))
            return generated + tf.reduce_sum(reference, axis=[1, 2, 3], keepdims=True) * 0.0

    reference_input = tf.keras.Input(shape=(args.size, args.size, 4), name="reference")
    pose_input = tf.keras.Input(shape=(args.size, args.size, 3), name="target_pose")
    generated = PoseLookupLayer(poses, frames, args.size, args.sharpness, name="pose_lookup")([reference_input, pose_input])

    model = tf.keras.Model(inputs=[reference_input, pose_input], outputs=generated)
    args.saved_model.parent.mkdir(parents=True, exist_ok=True)
    model.export(str(args.saved_model))

    converter = tf.lite.TFLiteConverter.from_saved_model(str(args.saved_model))
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.target_spec.supported_types = [tf.float16]
    tflite_model = converter.convert()
    args.tflite.parent.mkdir(parents=True, exist_ok=True)
    args.tflite.write_bytes(tflite_model)
    print(f"Embedded {len(image_paths)} pose-conditioned frames")
    print(f"Wrote {args.tflite} ({len(tflite_model) / 1024 / 1024:.2f} MB)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
