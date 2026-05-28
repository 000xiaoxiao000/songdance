
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

### 核心模块
- `MainActivity.kt`：权限流程与主控界面。
- `OverlayService.kt`：前台服务与覆盖层生命周期管理。
- `DancerOverlayView.kt`：悬浮覆盖层容器与状态 UI。

### 音频处理
- `AudioCaptureManager.kt`：音频捕获循环与源适配。
- `BeatDetector.kt`：基于 FFT 的 onset / 节拍事件生成器。
- `FftAnalyzer.kt`：内置 FFT 辅助实现。

### 头像渲染系统
- `OpenGLESView.kt`：OpenGL ES 视图容器。
- `OpenGLESRenderer.kt`：OpenGL ES 渲染器主实现。
- `OpenGLESRenderer_Legacy.kt`：OpenGL ES 渲染器遗留版本。
- `OpenGLESMotionController.kt`：将节拍/音频事件映射到渲染参数的控制器。
- `OpenGLESFallbackRenderState.kt`：OpenGL ES 回退渲染状态管理。
- `BeatReactiveAvatar.kt`：节拍响应头像逻辑。

### 头像资源管理
- `AvatarAssets.kt`：头像资源定义。
- `AvatarLoader.kt`：头像加载器。
- `AvatarImageManager.kt`：头像图片管理器。
- `AvatarImagePagingSource.kt`：头像图片分页数据源。
- `AvatarImagePagingAdapter.kt`：头像图片分页适配器。
- `AvatarUploadActivity.kt`：头像上传界面。
- `ImageCompressor.kt`：图片压缩工具。

### 舞蹈编排系统
- `AvatarSpriteChoreographyEngine.kt`：头像精灵编排引擎。
- `RhythmStyleEngine.kt`：节奏风格引擎。
- `SongDanceStyleResolver.kt`：歌曲舞蹈风格解析器。
- `DanceStyle.kt`：舞蹈风格定义。

### 设置与配置
- `SettingsActivity.kt`：设置界面。
- `OverlaySettings.kt`：设置模型与持久化。
- `BootCompletedReceiver.kt`：可选开机自启接收器。
- `PowerOptimizationHelper.kt`：电池优化帮助器。

## 运行方法

1. 可选：如果你打算使用运行时的 OpenGL ES 渲染器，可以将模型或纹理资源放在 `app/src/main/assets/` 下。
2. 当前悬浮小人 PNG 图片请直接放在以下目录：

   - `app/src/main/res/drawable/avatar/`
   - `app/src/main/res/drawable/avatar1/`

   其中启动首帧应使用 `dancer_single_begin.png`，待机图使用 `dancer_single_end.png`，跳舞序列帧使用 `dancer_single1.png`、`dancer_single2.png` ...

   > 说明：旧的 `app/src/main/assets/avatar*` 目录已废弃，不再作为小人图片来源。
   > 当前构建会把 `res/drawable/avatar*` 暴露为可按路径读取的原始文件，供运行时加载。
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
