package com.example.myapplication

import android.content.Context
import android.graphics.Bitmap

/**
 * 本地图像生成/风格迁移模型驱动的 avatar 风格生成器。
 * 不包含 Canvas 程序化 fallback；没有本地图像模型时直接生成失败。
 */
class AvatarStyleFrameRenderer(private val context: Context) {

    private val localImageToImageModel = LocalImageToImageModel(context)
    private val imageWarper = ImageWarper()

    fun generateFrames(
        sourceBitmap: Bitmap,
        detectedPose: PoseDetector.Pose,
        frameCount: Int
    ): List<Bitmap> {
        @Suppress("UNUSED_VARIABLE")
        val poseForDetectionOnly = detectedPose
        val stylizedBase = localImageToImageModel.stylize(sourceBitmap) ?: return emptyList()
        val safeFrameCount = frameCount.coerceAtLeast(1)

        val frames = ArrayList<Bitmap>(safeFrameCount)
        for (index in 0 until safeFrameCount) {
            val progress = if (safeFrameCount <= 1) 0f else index.toFloat() / safeFrameCount
            val frame = imageWarper.createContinuousDanceFrame(
                sourceBitmap = stylizedBase,
                frameProgress = progress
            )
            frames.add(frame)
        }
        stylizedBase.recycle()
        return frames
    }

    fun release() {
        localImageToImageModel.release()
    }

}
