#!/usr/bin/env python3
"""Train a tiny pose-driven generator from the provided avatar sequence.

Important: this is a small-data distillation/overfit pipeline for the supplied avatar style.
It is not a general AnimateAnyone-class model. It creates the Android contract model:
  inputs: reference [1,H,W,4], target_pose [1,H,W,3]
  output: generated [1,H,W,4]
"""
from __future__ import annotations

import argparse
from pathlib import Path
import subprocess
import sys


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--zip", required=True, type=Path)
    parser.add_argument("--workdir", type=Path, default=Path("build/pose_dataset/avatar_256"))
    parser.add_argument("--size", type=int, default=256)
    parser.add_argument("--epochs", type=int, default=600)
    parser.add_argument("--batch-size", type=int, default=4)
    parser.add_argument("--learning-rate", type=float, default=2e-4)
    parser.add_argument("--steps-per-epoch", type=int, default=32)
    parser.add_argument("--saved-model", type=Path, default=Path("build/pose_dataset/pose_driven_saved_model"))
    parser.add_argument("--tflite", type=Path, default=Path("app/src/main/assets/models/pose_driven_generator.tflite"))
    return parser.parse_args()


def ensure_dataset(zip_path: Path, workdir: Path, size: int):
    manifest = workdir / "manifest.json"
    if manifest.exists():
        return
    cmd = [
        sys.executable,
        "tools/pose_driven_model/prepare_avatar_dataset.py",
        "--zip",
        str(zip_path),
        "--out",
        str(workdir),
        "--size",
        str(size),
    ]
    subprocess.check_call(cmd)


def main() -> int:
    args = parse_args()
    ensure_dataset(args.zip, args.workdir, args.size)

    try:
        import numpy as np
        import tensorflow as tf
        from PIL import Image
    except Exception as exc:
        print("Missing training dependencies. Run:", file=sys.stderr)
        print("  python3 -m pip install -r tools/pose_driven_model/requirements.txt", file=sys.stderr)
        print(exc, file=sys.stderr)
        return 2

    image_paths = sorted((args.workdir / "images").glob("*.png"))
    pose_paths = sorted((args.workdir / "poses").glob("*.png"))
    if len(image_paths) < 4 or len(image_paths) != len(pose_paths):
        raise SystemExit("Prepared dataset is invalid or too small")

    reference = np.asarray(Image.open(image_paths[0]).convert("RGBA"), dtype=np.float32) / 255.0
    targets = np.stack([np.asarray(Image.open(path).convert("RGBA"), dtype=np.float32) / 255.0 for path in image_paths])
    poses = np.stack([np.asarray(Image.open(path).convert("RGB"), dtype=np.float32) / 255.0 for path in pose_paths])
    references = np.repeat(reference[None, ...], len(targets), axis=0)

    # Small data augmentation by cyclic shifting pose/target pairs only a few pixels.
    def make_dataset():
        dataset = tf.data.Dataset.from_tensor_slices(((references, poses), targets))
        dataset = dataset.shuffle(len(targets) * 8, reshuffle_each_iteration=True).repeat()
        dataset = dataset.batch(args.batch_size).prefetch(tf.data.AUTOTUNE)
        return dataset

    def conv_block(x, filters):
        x = tf.keras.layers.Conv2D(filters, 3, padding="same", use_bias=False)(x)
        x = tf.keras.layers.BatchNormalization()(x)
        x = tf.keras.layers.Activation("relu")(x)
        x = tf.keras.layers.Conv2D(filters, 3, padding="same", use_bias=False)(x)
        x = tf.keras.layers.BatchNormalization()(x)
        x = tf.keras.layers.Activation("relu")(x)
        return x

    reference_input = tf.keras.Input(shape=(args.size, args.size, 4), name="reference")
    pose_input = tf.keras.Input(shape=(args.size, args.size, 3), name="target_pose")
    x = tf.keras.layers.Concatenate(name="conditioning")([reference_input, pose_input])

    c1 = conv_block(x, 48)
    p1 = tf.keras.layers.MaxPooling2D()(c1)
    c2 = conv_block(p1, 96)
    p2 = tf.keras.layers.MaxPooling2D()(c2)
    c3 = conv_block(p2, 192)
    p3 = tf.keras.layers.MaxPooling2D()(c3)
    b = conv_block(p3, 256)
    u3 = tf.keras.layers.Conv2DTranspose(192, 3, strides=2, padding="same", activation="relu")(b)
    u3 = tf.keras.layers.Concatenate()([u3, c3])
    c4 = conv_block(u3, 192)
    u2 = tf.keras.layers.Conv2DTranspose(96, 3, strides=2, padding="same", activation="relu")(c4)
    u2 = tf.keras.layers.Concatenate()([u2, c2])
    c5 = conv_block(u2, 96)
    u1 = tf.keras.layers.Conv2DTranspose(48, 3, strides=2, padding="same", activation="relu")(c5)
    u1 = tf.keras.layers.Concatenate()([u1, c1])
    c6 = conv_block(u1, 48)
    output = tf.keras.layers.Conv2D(4, 1, padding="same", activation="sigmoid", name="generated")(c6)
    model = tf.keras.Model(inputs=[reference_input, pose_input], outputs=output)

    def alpha_weighted_l1(y_true, y_pred):
        alpha = y_true[..., 3:4]
        pred_alpha = tf.clip_by_value(y_pred[..., 3:4], 1e-5, 1.0 - 1e-5)
        foreground_weight = 0.05 + alpha * 8.00
        rgb_loss = tf.abs(y_true[..., :3] - y_pred[..., :3]) * foreground_weight
        alpha_bce = -(alpha * tf.math.log(pred_alpha) + (1.0 - alpha) * tf.math.log(1.0 - pred_alpha))
        alpha_l1 = tf.abs(alpha - y_pred[..., 3:4])
        background_rgb = tf.abs(y_pred[..., :3]) * (1.0 - alpha) * 0.10
        background_alpha = y_pred[..., 3:4] * (1.0 - alpha)
        return (
            tf.reduce_mean(rgb_loss)
            + tf.reduce_mean(alpha_bce) * 8.0
            + tf.reduce_mean(alpha_l1) * 6.0
            + tf.reduce_mean(background_rgb)
            + tf.reduce_mean(background_alpha) * 2.0
        )

    model.compile(
        optimizer=tf.keras.optimizers.Adam(args.learning_rate),
        loss=alpha_weighted_l1,
    )
    steps = max(args.steps_per_epoch, len(targets) // args.batch_size)
    model.fit(make_dataset(), epochs=args.epochs, steps_per_epoch=steps, verbose=2)

    args.saved_model.parent.mkdir(parents=True, exist_ok=True)
    model.export(str(args.saved_model))

    converter = tf.lite.TFLiteConverter.from_saved_model(str(args.saved_model))
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.target_spec.supported_types = [tf.float16]
    tflite_model = converter.convert()
    args.tflite.parent.mkdir(parents=True, exist_ok=True)
    args.tflite.write_bytes(tflite_model)
    print(f"Wrote {args.tflite} ({len(tflite_model) / 1024 / 1024:.2f} MB)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
