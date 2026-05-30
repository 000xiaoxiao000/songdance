# 真正姿态驱动大模型接入路线

当前 APP 已经停止 Canvas/网格伪生成；下一步必须接入真正的 pose-driven image/video generation 模型。

## 结论

要达到“任意上传人物保持外观，按连续唱跳骨架逐帧重绘”的效果，需要 diffusion/pose-guided animation 级别模型，而不是 MoveNet、抠图、Canvas 变形或 lookup 蒸馏模型。

公开资料中符合方向的代表模型/服务：

- Animate Anyone：单张人物图 + 姿态控制 + 时间建模，用于角色动画。
- MagicAnimate：参考图 + DensePose motion sequence + temporal diffusion，用于时间一致的人像动画。
- 云端 API：例如 AnimateAnyone 类 API，可通过人物图和动作模板生成动画。

这些模型通常是 PyTorch/服务端推理，不是直接可放进 Android 的小型 TFLite。

## 推荐落地顺序

### 阶段 1：远程大模型服务

先用远程服务验证效果标准：

```text
Android 上传 reference image
Android 上传/生成 target pose sequence
Server 使用 AnimateAnyone/MagicAnimate/同类模型生成视频或 PNG 帧
Server 返回 dancer_single*.png
Android 保存到图片集
```

优点：最快验证真实效果，GPU 在服务端，质量高。

缺点：需要服务器/GPU/API 成本，不能完全离线。

### 阶段 2：训练/蒸馏本地模型

确认效果后，再从服务端模型蒸馏到轻量模型：

```text
reference image + source pose map + target pose map -> generated frame
```

要求：

- 多人物、多服装、多动作训练数据。
- 同一人物跨姿态的配对或伪配对数据。
- 不能只用 `avatar` 单角色数据，否则模型只会记住固定古风人物。
- 输出必须是 RGB/RGBA 最终图，不是 latent、mask 或 skeleton。

### 阶段 3：TFLite/端侧优化

满足质量后再做：

- 模型裁剪。
- INT8/FP16 量化。
- 分辨率控制。
- Android NNAPI/GPU delegate 测试。

## 当前 APP 接入点

Android 已经预留真实本地模型入口：

```text
app/src/main/assets/models/pose_driven_generator.tflite
```

模型契约见：

```text
docs/pose_driven_generator_contract.md
```

检查模型格式：

```bash
python3 tools/pose_driven_model/inspect_pose_driven_tflite.py app/src/main/assets/models/pose_driven_generator.tflite
```

## 不应该再做的事

- 不再使用 Canvas/网格变形冒充 AI 生成。
- 不再使用 `avatar` 单角色查表模型。
- 不再把古风训练帧当任意上传人物的动作模型。
- 不再在没有真实模型时生成低质量图片。

## 参考资料

- Animate Anyone 论文：`https://arxiv.org/abs/2311.17117`
- Animate Anyone 项目页：`https://ygas.github.io/animate-anyone/`
- MagicAnimate 论文：`https://arxiv.org/abs/2311.16498`
- MagicAnimate 项目页：`https://www.magicanimate.org/`
- Animate Anyone 2 论文：`https://huggingface.co/papers/2502.06145`
