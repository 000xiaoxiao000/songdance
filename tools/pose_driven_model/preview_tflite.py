#!/usr/bin/env python3
"""Run pose_driven_generator.tflite on prepared samples and save preview PNGs."""
from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
from PIL import Image
import tensorflow as tf


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", type=Path, default=Path("app/src/main/assets/models/pose_driven_generator.tflite"))
    parser.add_argument("--workdir", type=Path, required=True)
    parser.add_argument("--out", type=Path, default=Path("build/pose_dataset/tflite_preview"))
    parser.add_argument("--reference", type=int, default=0)
    parser.add_argument("--frames", nargs="*", type=int, default=[0, 4, 8, 12, 16, 20, 24, 28, 32])
    return parser.parse_args()


def load_rgba(path: Path):
    return np.asarray(Image.open(path).convert("RGBA"), dtype=np.float32) / 255.0


def load_rgb(path: Path):
    return np.asarray(Image.open(path).convert("RGB"), dtype=np.float32) / 255.0


def main() -> int:
    args = parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    interpreter = tf.lite.Interpreter(model_path=str(args.model))
    interpreter.allocate_tensors()
    inputs = interpreter.get_input_details()
    output = interpreter.get_output_details()[0]
    reference = load_rgba(args.workdir / f"images/frame_{args.reference:04d}.png")
    for index in args.frames:
        pose_path = args.workdir / f"poses/frame_{index:04d}.png"
        if not pose_path.exists():
            continue
        target_pose = load_rgb(pose_path)
        interpreter.set_tensor(inputs[0]["index"], reference[None, ...])
        interpreter.set_tensor(inputs[1]["index"], target_pose[None, ...])
        interpreter.invoke()
        prediction = interpreter.get_tensor(output["index"])[0]
        rgba = (np.clip(prediction, 0.0, 1.0) * 255.0).astype(np.uint8)
        Image.fromarray(rgba, "RGBA").save(args.out / f"preview_{index:04d}.png")
        alpha = rgba[..., 3]
        visible = int((alpha > 8).sum())
        print(f"frame={index:04d} alpha_visible={visible} alpha_mean={alpha.mean():.2f}")
    print(f"wrote {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
