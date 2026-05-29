package com.example.myapplication

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF

/**
 * 本地图像生成/风格迁移模型驱动的 avatar 风格生成器。
 * 不包含 Canvas 程序化 fallback；没有本地图像模型时直接生成失败。
 */
class AvatarStyleFrameRenderer(private val context: Context) {

    private val localImageToImageModel = LocalImageToImageModel(context)
    private val keypointAnimator = KeypointAnimator()
    private val imageWarper = ImageWarper()

    fun generateFrames(
        sourceBitmap: Bitmap,
        detectedPose: PoseDetector.Pose,
        frameCount: Int
    ): List<Bitmap> {
        val stylizedBase = localImageToImageModel.stylize(sourceBitmap) ?: return emptyList()

        val scaledPose = scalePose(
            pose = detectedPose,
            sourceWidth = sourceBitmap.width.toFloat(),
            sourceHeight = sourceBitmap.height.toFloat(),
            targetWidth = stylizedBase.width.toFloat(),
            targetHeight = stylizedBase.height.toFloat()
        )
        val animatedSequence = keypointAnimator.generateDanceSequence(
            basePose = scaledPose,
            frameCount = frameCount,
            danceStyle = DanceStyle.POWER
        )
        val frames = ArrayList<Bitmap>(frameCount)
        animatedSequence.forEachIndexed { index, animatedPose ->
            val frame = imageWarper.createFrameWithPose(
                sourceBitmap = stylizedBase,
                animatedKeypoints = animatedPose.keypoints,
                originalPose = scaledPose,
                frameProgress = animatedPose.timestamp
            )
            frames.add(frame)
        }
        stylizedBase.recycle()
        return frames
    }

    fun release() {
        localImageToImageModel.release()
    }

    private fun scalePose(
        pose: PoseDetector.Pose,
        sourceWidth: Float,
        sourceHeight: Float,
        targetWidth: Float,
        targetHeight: Float
    ): PoseDetector.Pose {
        val scaleX = targetWidth / sourceWidth.coerceAtLeast(1f)
        val scaleY = targetHeight / sourceHeight.coerceAtLeast(1f)
        return PoseDetector.Pose(
            keypoints = pose.keypoints.map { keypoint ->
                PoseDetector.Keypoint(
                    position = PointF(keypoint.position.x * scaleX, keypoint.position.y * scaleY),
                    confidence = keypoint.confidence
                )
            },
            confidence = pose.confidence
        )
    }
}
