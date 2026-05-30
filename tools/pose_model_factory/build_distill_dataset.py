#!/usr/bin/env python3
"""Build a student distillation dataset from teacher-generated frames.

Expected layout:

teacher-output/
  sample_0001/
    reference.png
    source_pose.png              # optional
    frames/frame_0000.png
    poses/frame_0000.png         # target pose map matching frame_0000

The script normalizes every sample into:

dataset/
  manifest.jsonl
  references/*.png
  source_poses/*.png
  target_poses/*.png
  targets/*.png
"""
from __future__ import annotations

import argparse
import json
import shutil
from pathlib import Path

from PIL import Image


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--teacher-output", required=True, type=Path)
    parser.add_argument("--dataset", required=True, type=Path)
    parser.add_argument("--size", type=int, default=256)
    return parser.parse_args()


def resize_rgba(src: Path, dst: Path, size: int):
    image = Image.open(src).convert("RGBA")
    image.thumbnail((size, size), Image.Resampling.LANCZOS)
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    canvas.alpha_composite(image, ((size - image.width) // 2, (size - image.height) // 2))
    canvas.save(dst)


def resize_rgb(src: Path, dst: Path, size: int):
    image = Image.open(src).convert("RGB").resize((size, size), Image.Resampling.BILINEAR)
    image.save(dst)


def main() -> int:
    args = parse_args()
    if not args.teacher_output.is_dir():
        raise SystemExit(f"teacher output directory not found: {args.teacher_output}")

    references_dir = args.dataset / "references"
    source_poses_dir = args.dataset / "source_poses"
    target_poses_dir = args.dataset / "target_poses"
    targets_dir = args.dataset / "targets"
    for directory in (references_dir, source_poses_dir, target_poses_dir, targets_dir):
        directory.mkdir(parents=True, exist_ok=True)

    manifest_path = args.dataset / "manifest.jsonl"
    count = 0
    with manifest_path.open("w", encoding="utf-8") as manifest:
        for sample_dir in sorted(p for p in args.teacher_output.iterdir() if p.is_dir()):
            reference = sample_dir / "reference.png"
            frames_dir = sample_dir / "frames"
            poses_dir = sample_dir / "poses"
            if not reference.is_file() or not frames_dir.is_dir() or not poses_dir.is_dir():
                continue

            source_pose = sample_dir / "source_pose.png"
            if not source_pose.is_file():
                first_pose = next(iter(sorted(poses_dir.glob("*.png"))), None)
                source_pose = first_pose
            if source_pose is None or not source_pose.is_file():
                continue

            for frame_path in sorted(frames_dir.glob("*.png")):
                pose_path = poses_dir / frame_path.name
                if not pose_path.is_file():
                    continue
                stem = f"{sample_dir.name}_{frame_path.stem}"
                ref_out = references_dir / f"{stem}.png"
                src_pose_out = source_poses_dir / f"{stem}.png"
                pose_out = target_poses_dir / f"{stem}.png"
                target_out = targets_dir / f"{stem}.png"
                resize_rgba(reference, ref_out, args.size)
                resize_rgb(source_pose, src_pose_out, args.size)
                resize_rgb(pose_path, pose_out, args.size)
                resize_rgba(frame_path, target_out, args.size)
                manifest.write(json.dumps({
                    "reference": str(ref_out),
                    "source_pose": str(src_pose_out),
                    "target_pose": str(pose_out),
                    "target": str(target_out),
                }, ensure_ascii=False) + "\n")
                count += 1

    print(f"Wrote {count} training pairs to {args.dataset}")
    if count < 1000:
        print("WARNING: 数据量偏少。要泛化到任意上传人物，建议至少数千到数万帧。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
