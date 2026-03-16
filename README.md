# Dancing Overlay（Android）

一个在其他应用之上显示悬浮跳舞头像，并根据音乐节拍驱动动画的 Android 应用。

## 功能

- 可拖拽的悬浮覆盖层（`TYPE_APPLICATION_OVERLAY`）。
- 前台服务在应用后台时保持覆盖层存活。
- 已准备好使用 OpenGL ES 的头像渲染管线（用于节拍驱动的关键帧/过渡），并内置 PNG 回退帧（示例名 `dancer_single1..` ~ `dancer_single9..`）。
- 使用 FFT 频谱流（spectral-flux）的方法进行节拍检测。
- 音频采集模式：
  - 仅限回放捕获（Android 10+，需 MediaProjection 权限）。
- 设置页面：
  - 节拍灵敏度
  - 头像尺寸
  - 头像透明度
  - 位置锁定
  - 开机自启
- 电池优化建议（可引导用户到白名单设置）。

## 项目结构

- `app/src/main/java/com/example/myapplication/MainActivity.kt`：权限流程与控制。
- `app/src/main/java/com/example/myapplication/OverlayService.kt`：前台服务与覆盖层生命周期管理。
- `app/src/main/java/com/example/myapplication/AudioCaptureManager.kt`：音频采集循环。
- `app/src/main/java/com/example/myapplication/BeatDetector.kt`：基于 FFT 的 onset 节拍事件生成器。
- `app/src/main/java/com/example/myapplication/FftAnalyzer.kt`：应用内的 FFT 实现。
- `app/src/main/java/com/example/myapplication/DancerOverlayView.kt`：悬浮覆盖层容器与文本/状态布局。
- `app/src/main/java/com/example/myapplication/OpenGLESAvatarView.kt`：支持 OpenGL ES 的头像宿主视图，带 PNG 回退。
- `app/src/main/java/com/example/myapplication/OpenGLESMotionController.kt`：把节拍/音频映射到 OpenGL ES 参数/运动的控制器。
- `app/src/main/java/com/example/myapplication/DancingAvatarView.kt`：内置的二次元跳舞角色渲染与节拍响应逻辑（Canvas/回退实现）。
- `app/src/main/java/com/example/myapplication/SettingsActivity.kt`：应用内设置界面。
- `app/src/main/java/com/example/myapplication/OverlaySettings.kt`：设置模型与持久化。
- `app/src/main/java/com/example/myapplication/BootCompletedReceiver.kt`：可选的开机自启接收器。
- `app/src/main/java/com/example/myapplication/PowerOptimizationHelper.kt`：电池优化帮助器。
- `app/src/test/java/com/example/myapplication/BeatDetectorTest.kt`：简单节拍检测单元测试。

## 运行方法

1. 可选：如果你打算使用运行时的 OpenGL ES 渲染器，可以将模型或纹理资源放在 `app/src/main/assets/` 下。
2. 推荐：将 PNG 回退的跳舞帧放到 `app/src/main/res/drawable-nodpi/`，文件名使用 `dancer_single1...png` 到 `dancer_single9...png`，以便运行时优先使用打包进 APK 的资源。

   - 如果你已有帧放在 `app/src/main/assets/`（旧的做法），当 drawable 资源不存在时应用仍会回退到从 assets 加载。不过推荐把帧放在 `res/drawable(-nodpi)/` 中以便于打包、密度控制和更快的资源查找。

   示例（把 assets 中的文件复制到 drawable-nodpi）：

   ```bash
   mkdir -p app/src/main/res/drawable-nodpi
   cp app/src/main/assets/avatar/dancer_single{1..18}.png app/src/main/res/drawable-nodpi/  # 若只有 1..18 之外的范围请调整
   ./gradlew :app:processDebugResources
   ```
3. 在 Android Studio 中构建/安装，或使用 Gradle wrapper。
4. 打开应用并授予：
   - 悬浮窗权限（Overlay permission）
   - 录音/音频捕获权限
5. 点击 **Start Floating Dancer**（启动悬浮舞者）。
6. 同意系统的捕获提示以允许回放捕获。
7. 在支持的播放器中播放音乐；覆盖层将基于节拍与音频驱动 OpenGL ES 风格的关键帧/过渡状态，且在运行时 OpenGL 渲染器就绪前会使用 PNG 回退渲染。
8. 如果授予了通知权限，覆盖层还会显示当前歌曲标题/艺术家信息（用于舞蹈样式解析）。
9. 打开 **Settings** 调整灵敏度、大小、透明度、位置锁定和可选的开机自启设置。

## 注意事项与限制

- Android 对回放捕获（playback capture）有权限与策略限制，部分应用或流可能无法被捕获。
- 回放捕获仅在 Android 10 及以上可用。
- 回放捕获的可用性还受来源应用的策略/DRM 限制。

## 付款码

如果你愿意支持该项目，请扫码下方的付款码：

## 效果图

下面按顺序展示效果图：

<img src="effect_picture/effect_picture1.jpg" alt="效果图1" width="240" style="max-width:100%;height:auto;" />

<img src="effect_picture/effect_picture2.jpg" alt="效果图2" width="240" style="max-width:100%;height:auto;" />

<img src="effect_picture/effect_picture3.jpg" alt="效果图3" width="240" style="max-width:100%;height:auto;" />

<img src="effect_picture/effect_picture4.jpg" alt="效果图4" width="240" style="max-width:100%;height:auto;" />

<img src="Payment_Receipt_Code.png" alt="付款码" width="300" style="max-width:100%;height:auto;" />


