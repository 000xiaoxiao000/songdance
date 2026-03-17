
# Dancing Overlay（Android）

[English](./README_en.md) | [中文](./README.md)

一个在其他应用之上显示可拖拽悬浮跳舞头像，并根据音乐节拍驱动动画的 Android 应用。

## 功能

- 可拖拽的悬浮覆盖层（`TYPE_APPLICATION_OVERLAY`）。
- 前台服务在应用后台时保持覆盖层存活。
- 支持运行时的 OpenGL ES 渲染管线，并提供 PNG 回退帧（例如 `dancer_single1.png` ... `dancer_single9.png`）。
- 使用基于 FFT 的 spectral-flux 风格 onset 检测器进行节拍识别。
- 音频采集模式：
  - 仅限回放捕获（Android 10+，需 MediaProjection 权限）。
- 设置项包含：
  - 节拍灵敏度
  - 头像尺寸
  - 头像透明度
  - 位置锁定
  - 开机自启
- 电池优化建议（可引导用户到忽略电池优化或白名单设置）。

## 项目结构

- `app/src/main/java/com/example/myapplication/MainActivity.kt`：权限流程与主控界面。
- `app/src/main/java/com/example/myapplication/OverlayService.kt`：前台服务与覆盖层生命周期管理。
- `app/src/main/java/com/example/myapplication/AudioCaptureManager.kt`：音频捕获循环与源适配。
- `app/src/main/java/com/example/myapplication/BeatDetector.kt`：基于 FFT 的 onset / 节拍事件生成器。
- `app/src/main/java/com/example/myapplication/FftAnalyzer.kt`：内置 FFT 辅助实现。
- `app/src/main/java/com/example/myapplication/DancerOverlayView.kt`：悬浮覆盖层容器与状态 UI。
- `app/src/main/java/com/example/myapplication/OpenGLESAvatarView.kt`：OpenGL ES 头像宿主，带 PNG 回退支持。
- `app/src/main/java/com/example/myapplication/OpenGLESMotionController.kt`：将节拍/音频事件映射到渲染参数的控制器。
- `app/src/main/java/com/example/myapplication/DancingAvatarView.kt`：内置 2D 角色渲染（Canvas 回退）与节拍响应逻辑。
- `app/src/main/java/com/example/myapplication/SettingsActivity.kt`：设置界面。
- `app/src/main/java/com/example/myapplication/OverlaySettings.kt`：设置模型与持久化。
- `app/src/main/java/com/example/myapplication/BootCompletedReceiver.kt`：可选开机自启接收器。
- `app/src/main/java/com/example/myapplication/PowerOptimizationHelper.kt`：电池优化帮助器。
- `app/src/test/java/com/example/myapplication/BeatDetectorTest.kt`：节拍检测的单元测试。

## 运行方法

1. 可选：如果你打算使用运行时的 OpenGL ES 渲染器，可以将模型或纹理资源放在 `app/src/main/assets/` 下。
2. 推荐：将 PNG 回退的跳舞帧放到 `app/src/main/res/drawable-nodpi/`，文件名以 `dancer_single1.png` 起始，以便运行时优先使用打包资源。

   - 若帧当前位于 `app/src/main/assets/`（旧方式），当 drawable 资源缺失时应用会回退加载 assets 中的帧。但仍推荐把帧放入 `res/drawable(-nodpi)/` 以便打包与密度控制。

   示例（从 assets 复制到 drawable-nodpi）：

   ```bash
   mkdir -p app/src/main/res/drawable-nodpi
   cp app/src/main/assets/avatar/dancer_single{1..18}.png app/src/main/res/drawable-nodpi/  # 如有不同范围请调整
   ./gradlew :app:processDebugResources
   ```
3. 在 Android Studio 中构建/安装，或使用 Gradle wrapper。
4. 打开应用并授予必要权限：
   - 悬浮窗权限（Overlay permission）
   - 录音 / 音频捕获（在提示时允许）
5. 点击 **Start Floating Dancer** 启动悬浮舞者。
6. 同意系统捕获权限提示以允许回放捕获。
7. 在支持的播放器中播放音乐，覆盖层会根据节拍与音频驱动头像动画；在运行时 OpenGL 渲染器就绪前会使用 PNG 回退渲染。
8. 若授予通知权限，覆盖层可显示当前歌曲标题/艺人（用于可选的舞蹈样式解析）。
9. 在设置中调整灵敏度、大小、透明度、位置锁定及开机自启等选项。

## 注意事项与限制

- Android 对回放捕获有权限与策略限制，部分应用或流可能无法捕获（例如被 DRM 或上游策略阻止）。
- 回放捕获仅在 Android 10（API 29）及以上受支持。

## 付款 / 支持

如果你愿意支持该项目，请扫码下方的付款码。

感谢你认可这个项目的价值，你的赞赏是它持续更新的燃料。

## 效果图

下面示例图片按顺序展示效果：

<img src="effect_picture/effect_picture1.jpg" alt="效果图1" width="240" style="max-width:100%;height:auto;" />

<img src="effect_picture/effect_picture2.jpg" alt="效果图2" width="240" style="max-width:100%;height:auto;" />

<img src="effect_picture/effect_picture3.jpg" alt="效果图3" width="240" style="max-width:100%;height:auto;" />

<img src="effect_picture/effect_picture4.jpg" alt="效果图4" width="240" style="max-width:100%;height:auto;" />

<img src="Payment_Receipt_Code.png" alt="付款码" width="300" style="max-width:100%;height:auto;" />


