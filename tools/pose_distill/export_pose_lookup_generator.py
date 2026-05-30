#!/usr/bin/env python3
"""Distill a small local pose-driven lookup generator into TFLite.

This embeds prepared avatar motion frames in the model weights. It is a practical
small local model for APK packaging, not a general AnimateAnyone-class generator.
"""
from __future__ import annotations

import argparse
import json
import math
import re
import subprocess
import sys
import zipfile
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

SKELETON_CONNECTIONS = [
    (5, 6), (5, 7), (7, 9), (6, 8), (8, 10), (5, 11),
    (6, 12), (11, 12), (11, 13), (13, 15), (12, 14), (14, 16),
]
PALETTE = [
    (255, 64, 64), (255, 160, 64), (255, 224, 64), (96, 224, 96),
    (64, 224, 192), (64, 160, 255), (96, 96, 255), (192, 96, 255),
    (255, 96, 192), (192, 255, 96), (96, 255, 255), (255, 255, 255),
]


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--zip", required=True, type=Path)
    parser.add_argument("--workdir", type=Path, default=Path("build/pose_dataset/avatar_lookup_256"))
    parser.add_argument("--saved-model", type=Path, default=Path("build/pose_dataset/pose_lookup_saved_model"))
    parser.add_argument("--tflite", type=Path, default=Path("app/src/main/assets/models/pose_driven_generator.tflite"))
    parser.add_argument("--size", type=int, default=256)
    parser.add_argument("--sharpness", type=float, default=50000.0)
    return parser.parse_args()


def frame_sort_key(path: Path):
    name = path.stem
    if name.endswith("begin"):
        return -1
    if name.endswith("end"):
        return 9999
    match = re.search(r"(\d+)$", name)
    return int(match.group(1)) if match else 9998


def alpha_bounds(image: Image.Image):
    alpha = np.asarray(image.getchannel("A"))
    ys, xs = np.where(alpha > 8)
    if len(xs) == 0:
        return None
    return int(xs.min()), int(ys.min()), int(xs.max()) + 1, int(ys.max()) + 1


def fit_on_canvas(image: Image.Image, size: int) -> Image.Image:
    image = image.convert("RGBA")
    bounds = alpha_bounds(image)
    if not bounds:
        return Image.new("RGBA", (size, size), (0, 0, 0, 0))
    crop = image.crop(bounds)
    scale = min(size * 0.82 / crop.width, size * 0.88 / crop.height)
    crop = crop.resize((max(1, int(crop.width * scale)), max(1, int(crop.height * scale))), Image.Resampling.LANCZOS)
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    canvas.alpha_composite(crop, ((size - crop.width) // 2, int(size * 0.52 - crop.height / 2)))
    return canvas


def clamp(value: float, low: float, high: float) -> float:
    return max(low, min(high, value))


def dance_keypoints(bounds: tuple[int, int, int, int], frame_index: int, total_frames: int) -> list[tuple[float, float]]:
    left, top, right, bottom = bounds
    width = max(1.0, float(right - left))
    height = max(1.0, float(bottom - top))
    cx = (left + right) / 2.0
    progress = 0.0 if total_frames <= 1 else frame_index / float(total_frames)
    cycle = progress * 2.0 * math.pi
    phase = int(progress * 8.0) % 8
    sway = math.sin(cycle * 2.0)
    hop = abs(math.sin(cycle * 4.0))
    wave = math.sin(cycle * 5.0)
    torso_shift = sway * width * 0.07
    head_shift = sway * width * 0.10
    body_lift = -hop * height * 0.035
    left_arm_up = phase in (0, 4, 6)
    right_arm_up = phase in (1, 4, 7)
    left_kick = phase in (3, 6)
    right_kick = phase in (2, 5)

    nose = (cx + head_shift, top + height * 0.16 + body_lift)
    left_eye = (nose[0] - width * 0.035, nose[1] - height * 0.025)
    right_eye = (nose[0] + width * 0.035, nose[1] - height * 0.025)
    left_ear = (nose[0] - width * 0.075, nose[1] + height * 0.010)
    right_ear = (nose[0] + width * 0.075, nose[1] + height * 0.010)
    shoulder_y = top + height * 0.31 + body_lift
    hip_y = top + height * (0.60 + math.sin(cycle * 4.0) * 0.025)
    left_shoulder = (cx - width * 0.20 + torso_shift, shoulder_y)
    right_shoulder = (cx + width * 0.20 + torso_shift, shoulder_y)
    left_hip = (cx - width * 0.11 - torso_shift * 0.25, hip_y)
    right_hip = (cx + width * 0.11 - torso_shift * 0.25, hip_y)
    left_elbow = (left_shoulder[0] - width * (0.27 if left_arm_up else 0.16), left_shoulder[1] + height * (-0.15 if left_arm_up else 0.14 + wave * 0.045))
    right_elbow = (right_shoulder[0] + width * (0.27 if right_arm_up else 0.16), right_shoulder[1] + height * (-0.15 if right_arm_up else 0.14 - wave * 0.045))
    left_wrist = (left_shoulder[0] - width * (0.43 if left_arm_up else 0.30), left_shoulder[1] + height * (-0.29 if left_arm_up else 0.30 + wave * 0.055))
    right_wrist = (right_shoulder[0] + width * (0.43 if right_arm_up else 0.30), right_shoulder[1] + height * (-0.29 if right_arm_up else 0.30 - wave * 0.055))
    left_knee = (left_hip[0] - width * (0.25 if left_kick else 0.08), left_hip[1] + height * (0.08 if left_kick else 0.20))
    right_knee = (right_hip[0] + width * (0.25 if right_kick else 0.08), right_hip[1] + height * (0.08 if right_kick else 0.20))
    left_ankle = (left_hip[0] - width * (0.44 if left_kick else 0.12), left_hip[1] + height * (0.18 if left_kick else 0.34))
    right_ankle = (right_hip[0] + width * (0.44 if right_kick else 0.12), right_hip[1] + height * (0.18 if right_kick else 0.34))
    points = [nose, left_eye, right_eye, left_ear, right_ear, left_shoulder, right_shoulder, left_elbow, right_elbow, left_wrist, right_wrist, left_hip, right_hip, left_knee, right_knee, left_ankle, right_ankle]
    return [(clamp(x, 0, right + width * 0.18), clamp(y, 0, bottom + height * 0.08)) for x, y in points]


def render_pose(size: int, points: list[tuple[float, float]]) -> Image.Image:
    pose = Image.new("RGB", (size, size), (0, 0, 0))
    draw = ImageDraw.Draw(pose)
    stroke = max(2, int(size * 0.018))
    for index, (start, end) in enumerate(SKELETON_CONNECTIONS):
        draw.line([points[start], points[end]], fill=PALETTE[index % len(PALETTE)], width=stroke)
    radius = max(2, int(size * 0.014))
    for x, y in points:
        draw.ellipse((x - radius, y - radius, x + radius, y + radius), fill=(255, 255, 255))
    return pose


def apply_frame_marker(pose: Image.Image, frame_index: int, total_frames: int) -> Image.Image:
    phase = 0.0 if total_frames <= 1 else frame_index / float(total_frames - 1)
    red = int(round(phase * 255.0))
    green = 255 - red
    blue = 64 if frame_index % 2 == 0 else 192
    marker_rows = max(4, pose.size[1] // 16)
    draw = ImageDraw.Draw(pose)
    draw.rectangle((0, 0, pose.size[0] - 1, marker_rows - 1), fill=(red, green, blue))
    return pose


def prepare_dataset(zip_path: Path, workdir: Path, size: int):
    manifest = workdir / "manifest.json"
    if manifest.exists():
        return
    raw_dir = workdir / "raw"
    images_dir = workdir / "images"
    poses_dir = workdir / "poses"
    raw_dir.mkdir(parents=True, exist_ok=True)
    images_dir.mkdir(parents=True, exist_ok=True)
    poses_dir.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(zip_path) as archive:
        archive.extractall(raw_dir)
    source_files = sorted((raw_dir / "avatar").glob("*.png"), key=frame_sort_key)
    records = []
    for index, path in enumerate(source_files):
        image = fit_on_canvas(Image.open(path), size)
        bounds = alpha_bounds(image)
        pose = Image.new("RGB", (size, size), (0, 0, 0)) if not bounds else render_pose(size, dance_keypoints(bounds, index, len(source_files)))
        pose = apply_frame_marker(pose, index, len(source_files))
        name = f"frame_{index:04d}.png"
        image.save(images_dir / name)
        pose.save(poses_dir / name)
        records.append({"image": str(images_dir / name), "pose": str(poses_dir / name), "source": path.name})
    manifest.write_text(json.dumps(records, ensure_ascii=False, indent=2))
    print(f"Prepared {len(records)} frames at {workdir}")


def main() -> int:
    args = parse_args()
    prepare_dataset(args.zip, args.workdir, args.size)
    import tensorflow as tf

    image_paths = sorted((args.workdir / "images").glob("*.png"))
    pose_paths = sorted((args.workdir / "poses").glob("*.png"))
    frames = np.stack([np.asarray(Image.open(path).convert("RGBA"), dtype=np.float32) / 255.0 for path in image_paths], axis=0)
    poses = np.stack([np.asarray(Image.open(path).convert("RGB"), dtype=np.float32) / 255.0 for path in pose_paths], axis=0)

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
    output = PoseLookupLayer(poses, frames, args.size, args.sharpness, name="pose_lookup")([reference_input, pose_input])
    model = tf.keras.Model(inputs=[reference_input, pose_input], outputs=output)
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
