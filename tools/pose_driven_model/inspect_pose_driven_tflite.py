#!/usr/bin/env python3
"""Inspect whether a TFLite file matches the app's pose-driven generator contract."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path


def load_interpreter(model_path: Path):
    try:
        from tensorflow.lite.python.interpreter import Interpreter
    except Exception:
        try:
            from tflite_runtime.interpreter import Interpreter
        except Exception as exc:
            raise RuntimeError(
                "需要安装 tensorflow 或 tflite-runtime 后才能检查 .tflite："
                "python3 -m pip install tensorflow"
            ) from exc
    return Interpreter(model_path=str(model_path))


def shape_to_nhwc(shape):
    if len(shape) != 4:
        return None
    return int(shape[1]), int(shape[2]), int(shape[3])


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("model", type=Path, help="pose_driven_generator.tflite path")
    args = parser.parse_args()

    if not args.model.is_file():
        print(f"模型不存在: {args.model}", file=sys.stderr)
        return 2

    interpreter = load_interpreter(args.model)
    interpreter.allocate_tensors()
    inputs = interpreter.get_input_details()
    outputs = interpreter.get_output_details()

    print(f"model: {args.model}")
    print("inputs:")
    for index, item in enumerate(inputs):
        print(f"  [{index}] name={item['name']} shape={list(item['shape'])} dtype={item['dtype']}")
    print("outputs:")
    for index, item in enumerate(outputs):
        print(f"  [{index}] name={item['name']} shape={list(item['shape'])} dtype={item['dtype']}")

    errors: list[str] = []
    if len(inputs) < 2:
        errors.append("输入数量必须至少为 2：reference image + target pose map")
    for index, item in enumerate(inputs):
        nhwc = shape_to_nhwc(item["shape"])
        if nhwc is None:
            errors.append(f"输入 {index} 不是 4D NHWC 图像张量")
            continue
        _, _, channels = nhwc
        if channels not in (1, 3, 4):
            errors.append(f"输入 {index} 通道数必须是 1/3/4，当前是 {channels}")
    if not outputs:
        errors.append("至少需要 1 个输出")
    else:
        nhwc = shape_to_nhwc(outputs[0]["shape"])
        if nhwc is None:
            errors.append("输出 0 不是 4D NHWC 图像张量")
        else:
            _, _, channels = nhwc
            if channels not in (3, 4):
                errors.append(f"输出 0 通道数必须是 3/4，当前是 {channels}")

    if errors:
        print("\n不兼容:")
        for error in errors:
            print(f"  - {error}")
        return 1

    print("\n兼容：张量形状满足 APP 接入契约。仍需用真实图片确认身份保持和动作质量。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
