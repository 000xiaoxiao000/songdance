# 如何造一个满足需求的本地 pose-driven 生成模型

你的需求不是“找一个普通 TFLite 模型”能直接满足的，它同时包含：

1. 任意上传人物外观保持：头发、脸、衣服、四肢、轮廓尽量像上传图。
2. 动作来自唱跳序列：每帧姿态不同且连续、大幅、有想象力。
3. 本地 APP 运行：不能连接外部服务器。
4. 输出图片尺寸与上传处理后的尺寸一致。

这类能力最接近 **pose-guided human image animation / image-to-video**，例如 MimicMotion、MusePose、MagicAnimate、AnimateAnyone 系列。它们通常是扩散视频大模型，适合在电脑/GPU 上当“教师模型”，不适合完整塞进手机 APK。

## 结论

最可落地路线：

```text
电脑/GPU 离线教师模型生成训练集
        ↓
训练/蒸馏一个轻量学生模型
        ↓
导出 pose_driven_generator.tflite
        ↓
APP 本地加载并逐帧生成 dancer_single*.png
```

也就是：我帮你“造模型”的方式，不是凭空写一个假的程序化生成，而是用真正图像生成教师模型批量造数据，再训练一个 APP 能跑的学生模型。

## 为什么不能直接把大模型打包进 APP

- MimicMotion/MusePose/MagicAnimate 这类模型通常由 VAE、UNet、ControlNet/pose guider、CLIP/image encoder、temporal module 组成。
- 原始权重动辄数 GB，推理显存远超普通手机。
- 直接转 TFLite 成功率和运行速度都不可控。
- 即使能跑，逐帧生成 30~100 帧也会非常慢、耗电并容易 OOM。

所以 APP 内应使用轻量学生模型；大模型只用于离线训练。

## 需要的数据

`图片集合1.zip` 只有一个角色几十帧，只能作为动作风格参考，不能训练“任意上传人物”。要接近需求标准，建议准备：

- 200+ 个不同人物/角色 reference。
- 每个 reference 生成 16~72 帧唱跳动作。
- 姿态覆盖：大幅抬手、挥手、跳跃、踢腿、转身、左右摆、上下弹跳。
- 每帧保存：reference、source_pose、target_pose、teacher_frame。

## 已新增工具

- `tools/pose_model_factory/prepare_mimicmotion_teacher.sh`：准备 MimicMotion 教师模型代码和部分权重。
- `tools/pose_model_factory/build_distill_dataset.py`：把教师输出整理成学生训练集。
- `tools/pose_model_factory/train_pose_student.py`：训练轻量 U-Net 学生模型并导出 TFLite。
- `tools/pose_driven_model/inspect_pose_driven_tflite.py`：检查 TFLite 是否符合 APP 接口。

## 最终 APP 接入文件

```text
app/src/main/assets/models/pose_driven_generator.tflite
```

生成后 APP 只走 `TruePoseDrivenModel` 加载 `pose_driven_generator.tflite`，不再保留其他不合适模型兜底。

## 质量预期

- 第一版学生模型：能明显比 Canvas/假动作更接近“上传人物 + 目标 pose 重绘”。
- 要达到稳定商业级：需要更多教师数据、更多角色、多尺度损失、感知损失、时序一致性损失、量化感知训练。
- 如果只用很少素材训练：会过拟合到某个古风/固定人物，无法满足“任意上传人物”。
