#!/usr/bin/env python3
"""Inspect a pose-driven TFLite model's input/output tensors."""
from __future__ import annotations

import argparse
from pathlib import Path
import sys


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Inspect pose-driven TFLite tensors")
    parser.add_argument("--model", type=Path, default=Path("app/src/main/assets/models/pose_driven_generator.tflite"))
    return parser.parse_args()


def load_interpreter(model_path: Path):
    try:
        import tensorflow as tf
        return tf.lite.Interpreter(model_path=str(model_path))
    except Exception:
        try:
            from tflite_runtime.interpreter import Interpreter
            return Interpreter(model_path=str(model_path))
        except Exception as exc:
            print("Install tensorflow or tflite-runtime to inspect .tflite files", file=sys.stderr)
            print(exc, file=sys.stderr)
            raise


def main() -> int:
    args = parse_args()
    if not args.model.exists():
        print(f"Model not found: {args.model}", file=sys.stderr)
        return 2
    interpreter = load_interpreter(args.model)
    interpreter.allocate_tensors()
    print(f"Model: {args.model}")
    print("Inputs:")
    for item in interpreter.get_input_details():
        print(f"  #{item['index']} name={item['name']} shape={item['shape'].tolist()} dtype={item['dtype']}")
    print("Outputs:")
    for item in interpreter.get_output_details():
        print(f"  #{item['index']} name={item['name']} shape={item['shape'].tolist()} dtype={item['dtype']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
