# Pose-Driven Generator Model Factory

目标：把“任意上传人物 + 连续唱跳动作”拆成可落地的两阶段方案。

1. **教师模型**：在电脑/GPU 上用真正 pose-guided image-to-video 模型生成高质量训练样本。
2. **学生模型**：把教师模型输出蒸馏成 APP 能加载的 `pose_driven_generator.tflite`。

> 说明：手机端直接运行 MusePose/MimicMotion/MagicAnimate 这类扩散视频大模型不现实，模型和显存需求太大。APP 中应该放一个轻量学生模型；教师模型只用于离线造数据/训练，不打包进 APK。

## 推荐教师模型

优先用 `MimicMotion` 做教师模型：它是 pose-guided arbitrary human motion video generation，目标更接近“上传一个人物，按动作序列生成连续动作”。如果机器或许可证不合适，再用 `MusePose` / `MagicAnimate`。

- MimicMotion：质量和时序更好，但推理要求高，官方提示 72 帧模型在 576x1024 下需要较高显存。
- MusePose：有训练代码，适合作为研究/改造起点，但官方模型只限非商业研究用途。
- MagicAnimate：官方代码和 checkpoint 可用，但更偏研究推理，转手机端仍需蒸馏。

## 输出契约

最终导出文件必须是：

```text
app/src/main/assets/models/pose_driven_generator.tflite
```

APP 侧 `TruePoseDrivenModel` 支持如下输入名关键词：

- `reference` / `ref` / `source` / `image`：上传人物图
- `target_pose` / `pose` / `condition` / `skeleton`：目标动作姿态图
- 可选 `source_pose` / `src_pose` / `input_pose`：上传图原始姿态图

输出：RGB/RGBA 图像张量，APP 会 resize 回上传图尺寸。

## 完整流程

```bash
# 1) 准备教师模型代码和权重（默认放 build/teacher_models，不进 APP）
bash tools/pose_model_factory/prepare_mimicmotion_teacher.sh

# 2) 用教师模型离线生成训练数据
#    需要你先在 build/pose_teacher/jobs 放入 reference 图片和 dance pose/video 配置。
python3 tools/pose_model_factory/build_distill_dataset.py \
  --teacher-output build/pose_teacher/outputs \
  --dataset build/pose_student_dataset \
  --size 256

# 3) 训练轻量学生模型并导出 TFLite
python3 tools/pose_model_factory/train_pose_student.py \
  --dataset build/pose_student_dataset \
  --tflite app/src/main/assets/models/pose_driven_generator.tflite \
  --size 256 \
  --epochs 80

# 4) 检查是否符合 APP 接入契约
python3 tools/pose_driven_model/inspect_pose_driven_tflite.py \
  app/src/main/assets/models/pose_driven_generator.tflite
```

## 数据要求

只靠 `图片集合1.zip` 这种几十帧、单一角色素材，无法训练出“任意上传人物都保持外观”的泛化模型。要达到你的需求，蒸馏数据至少需要：

- 角色/人物：建议 200+ 个，越多越好，包含动漫、真人、不同衣服/发型。
- 每个角色：至少 16~72 帧唱跳动作教师输出。
- 姿态：覆盖大幅摆手、踢腿、转身、跳跃，不要只有轻微左右摆。
- 标注：每帧对应目标 pose map；每个样本保留 reference image。

## 为什么这样做

- APP 只负责本地推理，保证不连接外部服务器。
- 教师模型负责“真实 AI 重绘”和连续动作质量。
- 学生模型负责手机端速度、体积和可部署性。


## 当前限制和下一步

我已经把“造模型”的工程入口放进项目，但本机当前没有 TensorFlow/PyTorch 训练环境，且 GitHub 教师模型下载可能受网络影响。下一步需要在有 GPU 和稳定网络的机器上执行：

```bash
bash tools/pose_model_factory/prepare_mimicmotion_teacher.sh
```

如果 `git clone` 卡住，可手动下载 `Tencent/MimicMotion` 到 `build/teacher_models/MimicMotion`，再继续下载权重。

训练完成后，将 `pose_driven_generator.tflite` 放入 `app/src/main/assets/models/`，APP 会优先使用它。
