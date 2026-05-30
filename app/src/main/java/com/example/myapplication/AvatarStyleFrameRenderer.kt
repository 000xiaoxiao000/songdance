package com.example.myapplication

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * True local pose-driven frame renderer.
 * No Canvas/bitmap animation fallback is allowed in this path.
 */
class AvatarStyleFrameRenderer(private val context: Context) {

    private val poseDrivenGenerator = LocalPoseDrivenGenerator(context)
    private val poseMapRenderer = PoseMapRenderer()

    companion object {
        private const val TAG = "AvatarStyleFrameRenderer"
    }

    fun generateFrames(
        sourceBitmap: Bitmap,
        detectedPose: PoseDetector.Pose,
        frameCount: Int
    ): List<Bitmap> {
        if (!poseDrivenGenerator.initialize()) {
            Log.e(TAG, "缺少真正的本地姿态驱动图像生成模型，停止生成")
            return emptyList()
        }

        val safeFrameCount = frameCount.coerceAtLeast(1)
        val poseSize = poseDrivenGenerator.targetPoseSize() ?: (sourceBitmap.width to sourceBitmap.height)
        val sourceKeypoints = detectedPose.keypoints.map { it.position }
        val sourcePoseMap = poseMapRenderer.render(
            keypoints = sourceKeypoints,
            sourceWidth = sourceBitmap.width,
            sourceHeight = sourceBitmap.height,
            targetWidth = poseSize.first,
            targetHeight = poseSize.second
        ).bitmap

        val dancingKeypoints = generateLearnedDanceSequence(
            detectedPose = detectedPose,
            sourceWidth = sourceBitmap.width,
            sourceHeight = sourceBitmap.height,
            frameCount = safeFrameCount
        )
        val frames = ArrayList<Bitmap>(safeFrameCount)
        try {
            dancingKeypoints.forEach { keypoints ->
                val targetPoseMap = poseMapRenderer.render(
                    keypoints = keypoints,
                    sourceWidth = sourceBitmap.width,
                    sourceHeight = sourceBitmap.height,
                    targetWidth = poseSize.first,
                    targetHeight = poseSize.second
                ).bitmap
                val generated = poseDrivenGenerator.generate(
                    referenceBitmap = sourceBitmap,
                    targetPoseBitmap = targetPoseMap,
                    sourcePoseBitmap = sourcePoseMap,
                    sourceMaskBitmap = null,
                    outputWidth = sourceBitmap.width,
                    outputHeight = sourceBitmap.height
                )
                targetPoseMap.recycle()
                if (generated == null) {
                    frames.forEach { it.recycle() }
                    return emptyList()
                }
                frames.add(generated)
            }
        } finally {
            sourcePoseMap.recycle()
        }
        return frames
    }

    fun release() {
        poseDrivenGenerator.release()
    }

    private fun generateLearnedDanceSequence(
        detectedPose: PoseDetector.Pose,
        sourceWidth: Int,
        sourceHeight: Int,
        frameCount: Int
    ): List<List<PointF>> {
        val bounds = poseBounds(detectedPose, sourceWidth, sourceHeight)
        return (0 until frameCount).map { frameIndex ->
            val progress = if (frameCount <= 1) 0f else frameIndex.toFloat() / frameCount.toFloat()
            danceKeypoints(bounds, progress, sourceWidth, sourceHeight)
        }
    }

    private fun poseBounds(
        detectedPose: PoseDetector.Pose,
        sourceWidth: Int,
        sourceHeight: Int
    ): Bounds {
        val usable = detectedPose.keypoints
            .filter { it.confidence >= 0.15f }
            .map { it.position }
            .filter { it.x.isFinite() && it.y.isFinite() }
        if (usable.size < 4) {
            val width = sourceWidth * 0.58f
            val height = sourceHeight * 0.82f
            val left = (sourceWidth - width) / 2f
            val top = sourceHeight * 0.08f
            return Bounds(left, top, left + width, top + height)
        }
        var left = usable.minOf { it.x }
        var top = usable.minOf { it.y }
        var right = usable.maxOf { it.x }
        var bottom = usable.maxOf { it.y }
        val expandX = max(8f, (right - left) * 0.18f)
        val expandY = max(8f, (bottom - top) * 0.12f)
        left = (left - expandX).coerceIn(0f, sourceWidth.toFloat())
        right = (right + expandX).coerceIn(0f, sourceWidth.toFloat())
        top = (top - expandY).coerceIn(0f, sourceHeight.toFloat())
        bottom = (bottom + expandY).coerceIn(0f, sourceHeight.toFloat())
        if (right - left < sourceWidth * 0.20f || bottom - top < sourceHeight * 0.35f) {
            val width = sourceWidth * 0.58f
            val height = sourceHeight * 0.82f
            left = (sourceWidth - width) / 2f
            top = sourceHeight * 0.08f
            right = left + width
            bottom = top + height
        }
        return Bounds(left, top, right, bottom)
    }

    private fun danceKeypoints(bounds: Bounds, progress: Float, sourceWidth: Int, sourceHeight: Int): List<PointF> {
        val width = max(1f, bounds.right - bounds.left)
        val height = max(1f, bounds.bottom - bounds.top)
        val centerX = (bounds.left + bounds.right) / 2f
        val cycle = progress * 2f * PI.toFloat()
        val phase = ((progress * 8f).toInt() % 8).coerceIn(0, 7)
        val sway = sin(cycle * 2f)
        val hop = abs(sin(cycle * 4f))
        val wave = sin(cycle * 5f)
        val torsoShift = sway * width * 0.07f
        val headShift = sway * width * 0.10f
        val bodyLift = -hop * height * 0.035f
        val leftArmUp = phase == 0 || phase == 4 || phase == 6
        val rightArmUp = phase == 1 || phase == 4 || phase == 7
        val leftKick = phase == 3 || phase == 6
        val rightKick = phase == 2 || phase == 5

        val nose = PointF(centerX + headShift, bounds.top + height * 0.16f + bodyLift)
        val leftEye = PointF(nose.x - width * 0.035f, nose.y - height * 0.025f)
        val rightEye = PointF(nose.x + width * 0.035f, nose.y - height * 0.025f)
        val leftEar = PointF(nose.x - width * 0.075f, nose.y + height * 0.010f)
        val rightEar = PointF(nose.x + width * 0.075f, nose.y + height * 0.010f)
        val shoulderY = bounds.top + height * 0.31f + bodyLift
        val hipY = bounds.top + height * (0.60f + sin(cycle * 4f) * 0.025f)
        val leftShoulder = PointF(centerX - width * 0.20f + torsoShift, shoulderY)
        val rightShoulder = PointF(centerX + width * 0.20f + torsoShift, shoulderY)
        val leftHip = PointF(centerX - width * 0.11f - torsoShift * 0.25f, hipY)
        val rightHip = PointF(centerX + width * 0.11f - torsoShift * 0.25f, hipY)
        val leftElbow = PointF(
            leftShoulder.x - width * if (leftArmUp) 0.27f else 0.16f,
            leftShoulder.y + height * if (leftArmUp) -0.15f else 0.14f + wave * 0.045f
        )
        val rightElbow = PointF(
            rightShoulder.x + width * if (rightArmUp) 0.27f else 0.16f,
            rightShoulder.y + height * if (rightArmUp) -0.15f else 0.14f - wave * 0.045f
        )
        val leftWrist = PointF(
            leftShoulder.x - width * if (leftArmUp) 0.43f else 0.30f,
            leftShoulder.y + height * if (leftArmUp) -0.29f else 0.30f + wave * 0.055f
        )
        val rightWrist = PointF(
            rightShoulder.x + width * if (rightArmUp) 0.43f else 0.30f,
            rightShoulder.y + height * if (rightArmUp) -0.29f else 0.30f - wave * 0.055f
        )
        val leftKnee = PointF(
            leftHip.x - width * if (leftKick) 0.25f else 0.08f,
            leftHip.y + height * if (leftKick) 0.08f else 0.20f
        )
        val rightKnee = PointF(
            rightHip.x + width * if (rightKick) 0.25f else 0.08f,
            rightHip.y + height * if (rightKick) 0.08f else 0.20f
        )
        val leftAnkle = PointF(
            leftHip.x - width * if (leftKick) 0.44f else 0.12f,
            leftHip.y + height * if (leftKick) 0.18f else 0.34f
        )
        val rightAnkle = PointF(
            rightHip.x + width * if (rightKick) 0.44f else 0.12f,
            rightHip.y + height * if (rightKick) 0.18f else 0.34f
        )

        return listOf(
            nose, leftEye, rightEye, leftEar, rightEar,
            leftShoulder, rightShoulder, leftElbow, rightElbow,
            leftWrist, rightWrist, leftHip, rightHip,
            leftKnee, rightKnee, leftAnkle, rightAnkle
        ).map { point ->
            PointF(
                min(max(point.x, 0f), sourceWidth.toFloat()),
                min(max(point.y, 0f), sourceHeight.toFloat())
            )
        }
    }

    private data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float)
}
