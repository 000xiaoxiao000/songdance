#!/usr/bin/env python3
"""Train a compact pose-driven student model and export TFLite.

This is a practical mobile adapter, not a full diffusion/video model. It learns:
reference person + source pose + target pose -> target generated frame.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", required=True, type=Path)
    parser.add_argument("--tflite", required=True, type=Path)
    parser.add_argument("--saved-model", type=Path, default=Path("build/pose_student/saved_model"))
    parser.add_argument("--size", type=int, default=256)
    parser.add_argument("--batch", type=int, default=8)
    parser.add_argument("--epochs", type=int, default=80)
    parser.add_argument("--base-channels", type=int, default=32)
    return parser.parse_args()


def require_tensorflow():
    try:
        import tensorflow as tf
    except Exception as exc:
        raise SystemExit(
            "需要安装 TensorFlow 后才能训练/导出：\n"
            "  python3 -m pip install 'tensorflow>=2.15' pillow numpy\n"
            "建议在有 GPU 的电脑上训练。"
        ) from exc
    return tf


def load_manifest(path: Path):
    manifest = path / "manifest.jsonl"
    if not manifest.is_file():
        raise SystemExit(f"dataset manifest not found: {manifest}")
    rows = [json.loads(line) for line in manifest.read_text(encoding="utf-8").splitlines() if line.strip()]
    if not rows:
        raise SystemExit(f"empty dataset: {manifest}")
    return rows


def build_model(tf, size: int, base_channels: int):
    layers = tf.keras.layers

    reference = layers.Input(shape=(size, size, 4), name="reference")
    source_pose = layers.Input(shape=(size, size, 3), name="source_pose")
    target_pose = layers.Input(shape=(size, size, 3), name="target_pose")
    x = layers.Concatenate(name="conditioning")([reference, source_pose, target_pose])

    skips = []
    for channels in (base_channels, base_channels * 2, base_channels * 4):
        x = layers.Conv2D(channels, 3, padding="same", activation="swish")(x)
        x = layers.Conv2D(channels, 3, padding="same", activation="swish")(x)
        skips.append(x)
        x = layers.AveragePooling2D()(x)

    x = layers.Conv2D(base_channels * 8, 3, padding="same", activation="swish")(x)
    x = layers.Conv2D(base_channels * 8, 3, padding="same", activation="swish")(x)

    for channels, skip in reversed(list(zip((base_channels, base_channels * 2, base_channels * 4), skips))):
        x = layers.UpSampling2D(interpolation="bilinear")(x)
        x = layers.Concatenate()([x, skip])
        x = layers.Conv2D(channels, 3, padding="same", activation="swish")(x)
        x = layers.Conv2D(channels, 3, padding="same", activation="swish")(x)

    output = layers.Conv2D(4, 1, padding="same", activation="sigmoid", name="generated_rgba")(x)
    return tf.keras.Model([reference, source_pose, target_pose], output, name="pose_driven_generator_student")


def main() -> int:
    args = parse_args()
    tf = require_tensorflow()
    rows = load_manifest(args.dataset)

    def decode(path, channels):
        image = tf.io.read_file(path)
        image = tf.image.decode_png(image, channels=channels)
        image = tf.image.resize(image, (args.size, args.size), method="bilinear")
        return tf.cast(image, tf.float32) / 255.0

    def row_to_tensors(row):
        return (
            {
                "reference": decode(row["reference"], 4),
                "source_pose": decode(row["source_pose"], 3),
                "target_pose": decode(row["target_pose"], 3),
            },
            decode(row["target"], 4),
        )

    dataset = tf.data.Dataset.from_tensor_slices(rows)
    dataset = dataset.shuffle(min(len(rows), 4096), reshuffle_each_iteration=True)
    dataset = dataset.map(row_to_tensors, num_parallel_calls=tf.data.AUTOTUNE)
    dataset = dataset.batch(args.batch).prefetch(tf.data.AUTOTUNE)

    model = build_model(tf, args.size, args.base_channels)
    model.compile(
        optimizer=tf.keras.optimizers.Adam(learning_rate=1e-4),
        loss=tf.keras.losses.MeanAbsoluteError(),
        metrics=[tf.keras.metrics.MeanSquaredError()],
    )
    model.fit(dataset, epochs=args.epochs)

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
