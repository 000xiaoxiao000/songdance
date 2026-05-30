#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TEACHER_DIR="${ROOT_DIR}/build/teacher_models/MimicMotion"
MODELS_DIR="${TEACHER_DIR}/models"

mkdir -p "$(dirname "${TEACHER_DIR}")"

if [ ! -d "${TEACHER_DIR}/.git" ]; then
  if ! git clone --depth 1 https://github.com/Tencent/MimicMotion.git "${TEACHER_DIR}"; then
    cat >&2 <<ERR
Failed to clone Tencent/MimicMotion. You can manually download or clone:
  https://github.com/Tencent/MimicMotion
Then place it at:
  ${TEACHER_DIR}
ERR
    exit 1
  fi
else
  git -C "${TEACHER_DIR}" pull --ff-only || true
fi

mkdir -p "${MODELS_DIR}/DWPose"

if [ ! -f "${MODELS_DIR}/DWPose/yolox_l.onnx" ]; then
  curl -L "https://huggingface.co/yzd-v/DWPose/resolve/main/yolox_l.onnx?download=true" \
    -o "${MODELS_DIR}/DWPose/yolox_l.onnx"
fi

if [ ! -f "${MODELS_DIR}/DWPose/dw-ll_ucoco_384.onnx" ]; then
  curl -L "https://huggingface.co/yzd-v/DWPose/resolve/main/dw-ll_ucoco_384.onnx?download=true" \
    -o "${MODELS_DIR}/DWPose/dw-ll_ucoco_384.onnx"
fi

if [ ! -f "${MODELS_DIR}/MimicMotion_1-1.pth" ]; then
  curl -L "https://huggingface.co/tencent/MimicMotion/resolve/main/MimicMotion_1-1.pth" \
    -o "${MODELS_DIR}/MimicMotion_1-1.pth"
fi

cat <<MSG
MimicMotion teacher is prepared at:
  ${TEACHER_DIR}

Next:
  cd ${TEACHER_DIR}
  conda env create -f environment.yaml
  conda activate mimicmotion
  python inference.py --inference_config configs/test.yaml

Teacher outputs should be collected under:
  ${ROOT_DIR}/build/pose_teacher/outputs
MSG
