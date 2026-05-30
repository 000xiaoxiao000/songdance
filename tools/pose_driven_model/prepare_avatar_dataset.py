#!/usr/bin/env python3
"""Prepare avatar frames as paired data for the local pose-driven generator.

The generated pose maps intentionally mirror Android's PoseMapRenderer contract:
black background, MoveNet 17-keypoint layout, the same skeleton connections,
line colors, stroke widths, and white joint dots. Keeping the training condition
identical to app-side inference avoids the model seeing one pose-map style during
training and a different one on device.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import math
import re
import zipfile

import numpy as np
from PIL import Image, ImageDraw


SKELETON_CONNECTIONS = [
    (5, 6),
    (5, 7),
    (7, 9),
    (6, 8),
    (8, 10),
    (5, 11),
    (6, 12),
    (11, 12),
    (11, 13),
    (13, 15),
    (12, 14),
    (14, 16),
]

PALETTE = [
    (255, 64, 64),
    (255, 160, 64),
    (255, 224, 64),
    (96, 224, 96),
    (64, 224, 192),
    (64, 160, 255),
    (96, 96, 255),
    (192, 96, 255),
    (255, 96, 192),
    (192, 255, 96),
    (96, 255, 255),
    (255, 255, 255),
]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--zip", required=True, type=Path)
    parser.add_argument("--out", type=Path, default=Path("build/pose_dataset/avatar_256"))
    parser.add_argument("--size", type=int, default=256)
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
    new_size = (max(1, int(crop.width * scale)), max(1, int(crop.height * scale)))
    crop = crop.resize(new_size, Image.Resampling.LANCZOS)
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    left = (size - crop.width) // 2
    top = int(size * 0.52 - crop.height / 2)
    canvas.alpha_composite(crop, (left, top))
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

    left_elbow = (
        left_shoulder[0] - width * (0.27 if left_arm_up else 0.16),
        left_shoulder[1] + height * (-0.15 if left_arm_up else 0.14 + wave * 0.045),
    )
    right_elbow = (
        right_shoulder[0] + width * (0.27 if right_arm_up else 0.16),
        right_shoulder[1] + height * (-0.15 if right_arm_up else 0.14 - wave * 0.045),
    )
    left_wrist = (
        left_shoulder[0] - width * (0.43 if left_arm_up else 0.30),
        left_shoulder[1] + height * (-0.29 if left_arm_up else 0.30 + wave * 0.055),
    )
    right_wrist = (
        right_shoulder[0] + width * (0.43 if right_arm_up else 0.30),
        right_shoulder[1] + height * (-0.29 if right_arm_up else 0.30 - wave * 0.055),
    )

    left_knee = (
        left_hip[0] - width * (0.25 if left_kick else 0.08),
        left_hip[1] + height * (0.08 if left_kick else 0.20),
    )
    right_knee = (
        right_hip[0] + width * (0.25 if right_kick else 0.08),
        right_hip[1] + height * (0.08 if right_kick else 0.20),
    )
    left_ankle = (
        left_hip[0] - width * (0.44 if left_kick else 0.12),
        left_hip[1] + height * (0.18 if left_kick else 0.34),
    )
    right_ankle = (
        right_hip[0] + width * (0.44 if right_kick else 0.12),
        right_hip[1] + height * (0.18 if right_kick else 0.34),
    )

    points = [
        nose, left_eye, right_eye, left_ear, right_ear,
        left_shoulder, right_shoulder, left_elbow, right_elbow,
        left_wrist, right_wrist, left_hip, right_hip,
        left_knee, right_knee, left_ankle, right_ankle,
    ]
    image_right = right + width * 0.18
    image_left = left - width * 0.18
    image_bottom = bottom + height * 0.08
    image_top = top - height * 0.18
    return [(clamp(x, image_left, image_right), clamp(y, image_top, image_bottom)) for x, y in points]


def render_pose_map(size: int, keypoints: list[tuple[float, float]]) -> Image.Image:
    pose = Image.new("RGB", (size, size), (0, 0, 0))
    draw = ImageDraw.Draw(pose)
    stroke = max(2, int(size * 0.018))
    for index, (start_index, end_index) in enumerate(SKELETON_CONNECTIONS):
        draw.line([keypoints[start_index], keypoints[end_index]], fill=PALETTE[index % len(PALETTE)], width=stroke)
    radius = max(2, int(size * 0.014))
    for point in keypoints:
        x, y = point
        draw.ellipse((x - radius, y - radius, x + radius, y + radius), fill=(255, 255, 255))
    return pose


def approximate_pose_map(image: Image.Image, frame_index: int = 0, total_frames: int = 1) -> Image.Image:
    bounds = alpha_bounds(image)
    if not bounds:
        return Image.new("RGB", image.size, (0, 0, 0))
    keypoints = dance_keypoints(bounds, frame_index, total_frames)
    return render_pose_map(image.size[0], keypoints)


def main() -> int:
    args = parse_args()
    raw_dir = args.out / "raw"
    images_dir = args.out / "images"
    poses_dir = args.out / "poses"
    raw_dir.mkdir(parents=True, exist_ok=True)
    images_dir.mkdir(parents=True, exist_ok=True)
    poses_dir.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.zip) as archive:
        archive.extractall(raw_dir)
    source_files = sorted((raw_dir / "avatar").glob("*.png"), key=frame_sort_key)
    if not source_files:
        raise SystemExit("No PNG files found under avatar/ in zip")

    manifest = []
    for index, path in enumerate(source_files):
        image = fit_on_canvas(Image.open(path), args.size)
        pose = approximate_pose_map(image, index, len(source_files))
        name = f"frame_{index:04d}.png"
        image.save(images_dir / name)
        pose.save(poses_dir / name)
        manifest.append({"image": str(images_dir / name), "pose": str(poses_dir / name), "source": path.name})
    (args.out / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2))
    print(f"Prepared {len(manifest)} frames at {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
