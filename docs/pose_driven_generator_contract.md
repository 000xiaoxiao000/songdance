# 本地 Pose-Driven 图像生成模型契约

APP 现在只接受真正的姿态驱动图像生成模型，不再使用 Canvas/网格/查表兜底生成。

模型文件必须打包到：

```text
app/src/main/assets/models/pose_driven_generator.tflite
```

## 模型能力要求

模型必须完成如下任务：

```text
reference image + source pose map + target pose map -> generated character frame
```

也就是：输入上传人物外观和目标唱跳骨架，模型逐帧重绘人物；输出必须已经是最终人物图片，不能是查表 avatar、不能只输出骨架、不能输出训练集固定人物。

## TFLite 输入输出要求

当前 Android 适配器支持 NHWC 图像张量：

- 输入数量：至少 2 个。
- 输入 1：上传人物参考图，名称建议包含 `ref`、`reference`、`source`、`image` 或 `person`。
- 输入 2：目标姿态图，名称建议包含 `target`、`pose`、`condition` 或 `skeleton`。
- 可选输入 3：原始姿态图，名称建议包含 `source_pose`、`src_pose` 或 `input_pose`。
- 输入类型：`FLOAT32`、`UINT8` 或 `INT8`。
- 输入通道：1、3 或 4。
- 输出数量：至少 1 个。
- 输出 1：最终 RGB/RGBA 人物帧。
- 输出通道：3 或 4。

APP 会把模型输出缩放回上传图片的原始尺寸，保证生成帧尺寸一致。

## 不能使用的模型

以下模型不符合当前需求：

- 只记住 `avatar` 目录图片的 lookup/查表模型。
- 只能输出固定古风人物的模型。
- 只能输出 pose map、mask、骨架或 latent 的模型。
- 不接收 reference image 的纯文本/纯姿态生成模型。

## 接入步骤

1. 准备兼容的 `pose_driven_generator.tflite`。
2. 放入 `app/src/main/assets/models/pose_driven_generator.tflite`。
3. 运行校验脚本：

```bash
python3 tools/pose_driven_model/inspect_pose_driven_tflite.py app/src/main/assets/models/pose_driven_generator.tflite
```

4. 重新打包：

```bash
./gradlew :app:assembleDebug
```

5. 安装 APK 后重新生成帧。
