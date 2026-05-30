package com.example.myapplication

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Pose-driven renderer backed by a real local image-generation model.
 * It intentionally does not fall back to Canvas warping, because that cannot preserve identity/clothes well enough.
 */
class AvatarStyleFrameRenderer(private val context: Context) {

    private val poseMapRenderer = PoseMapRenderer()
    private val truePoseDrivenModel = TruePoseDrivenModel(context)

    var lastBackendName: String = "未生成"
        private set

    fun generateFrames(
        sourceBitmap: Bitmap,
        detectedPose: PoseDetector.Pose,
        frameCount: Int
    ): List<Bitmap> {
        val safeFrameCount = frameCount.coerceAtLeast(1)
        val dancingKeypoints = generateDanceSequence(
            detectedPose = detectedPose,
            sourceWidth = sourceBitmap.width,
            sourceHeight = sourceBitmap.height,
            frameCount = safeFrameCount
        )
        if (!truePoseDrivenModel.initializeIfAvailable()) {
            val tfliteDetail = truePoseDrivenModel.loadFailureMessage?.let { " 详情：$it" }.orEmpty()
            throw IllegalStateException(
                "自研本地 pose-driven 图像生成模型不可用：请确认 " +
                    "app/src/main/assets/models/pose_driven_generator.tflite 已打包且与 APP 接口兼容。" +
                    "当前版本只使用自研 TFLite，不再使用其他不合适模型兜底。" +
                    tfliteDetail
            )
        }
        lastBackendName = "自研 pose-driven TFLite"
        return generateWithRealModel(sourceBitmap, detectedPose, dancingKeypoints, safeFrameCount)
    }

    fun release() {
        truePoseDrivenModel.release()
    }

    private fun generateWithRealModel(
        sourceBitmap: Bitmap,
        detectedPose: PoseDetector.Pose,
        dancingKeypoints: List<List<PointF>>,
        frameCount: Int
    ): List<Bitmap> {
        val poseSize = truePoseDrivenModel.targetPoseSize() ?: (sourceBitmap.width to sourceBitmap.height)
        val sourcePoseMap = poseMapRenderer.render(
            keypoints = detectedPose.keypoints.map { it.position },
            sourceWidth = sourceBitmap.width,
            sourceHeight = sourceBitmap.height,
            targetWidth = poseSize.first,
            targetHeight = poseSize.second
        )
        val generatedFrames = ArrayList<Bitmap>(frameCount)
        try {
            dancingKeypoints.forEach { keypoints ->
                val targetPoseMap = poseMapRenderer.render(
                    keypoints = keypoints,
                    sourceWidth = sourceBitmap.width,
                    sourceHeight = sourceBitmap.height,
                    targetWidth = poseSize.first,
                    targetHeight = poseSize.second
                )
                val generated = truePoseDrivenModel.generate(
                    referenceBitmap = sourceBitmap,
                    sourcePoseBitmap = sourcePoseMap,
                    targetPoseBitmap = targetPoseMap,
                    outputWidth = sourceBitmap.width,
                    outputHeight = sourceBitmap.height
                )
                targetPoseMap.recycle()
                if (generated == null) {
                    generatedFrames.forEach { it.recycle() }
                    val detail = truePoseDrivenModel.loadFailureMessage?.let { " 详情：$it" }.orEmpty()
                    throw IllegalStateException("自研 pose-driven 模型推理失败，请确认模型输入/输出格式与 APP 适配器兼容。$detail")
                }
                generatedFrames.add(generated)
            }
        } finally {
            sourcePoseMap.recycle()
        }
        return generatedFrames
    }

    private fun generateDanceSequence(
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
            .filter { it.confidence >= 0.25f }
            .map { it.position }
            .filter { it.x.isFinite() && it.y.isFinite() }
        if (usable.size < 8 || detectedPose.confidence < 0.20f || !isPlausiblePose(detectedPose, sourceWidth, sourceHeight)) {
            return centeredHumanBounds(sourceWidth, sourceHeight)
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
            return centeredHumanBounds(sourceWidth, sourceHeight)
        }
        return Bounds(left, top, right, bottom)
    }

    private fun centeredHumanBounds(sourceWidth: Int, sourceHeight: Int): Bounds {
        val width = sourceWidth * 0.58f
        val height = sourceHeight * 0.82f
        val left = (sourceWidth - width) / 2f
        val top = sourceHeight * 0.08f
        return Bounds(left, top, left + width, top + height)
    }

    private fun isPlausiblePose(detectedPose: PoseDetector.Pose, sourceWidth: Int, sourceHeight: Int): Boolean {
        fun point(index: Int): PointF? {
            val keypoint = detectedPose.keypoints.getOrNull(index) ?: return null
            if (keypoint.confidence < 0.20f) return null
            val position = keypoint.position
            if (!position.x.isFinite() || !position.y.isFinite()) return null
            if (position.x !in -sourceWidth * 0.1f..sourceWidth * 1.1f) return null
            if (position.y !in -sourceHeight * 0.1f..sourceHeight * 1.1f) return null
            return position
        }
        val nose = point(0) ?: return false
        val leftShoulder = point(5) ?: return false
        val rightShoulder = point(6) ?: return false
        val leftHip = point(11) ?: return false
        val rightHip = point(12) ?: return false
        val shoulderWidth = kotlin.math.hypot(leftShoulder.x - rightShoulder.x, leftShoulder.y - rightShoulder.y)
        val hipWidth = kotlin.math.hypot(leftHip.x - rightHip.x, leftHip.y - rightHip.y)
        val shoulderY = (leftShoulder.y + rightShoulder.y) / 2f
        val hipY = (leftHip.y + rightHip.y) / 2f
        val torsoHeight = hipY - shoulderY
        return shoulderWidth in sourceWidth * 0.08f..sourceWidth * 0.70f &&
            hipWidth in sourceWidth * 0.04f..sourceWidth * 0.65f &&
            torsoHeight > sourceHeight * 0.08f &&
            nose.y < shoulderY
    }

    private fun danceKeypoints(bounds: Bounds, progress: Float, sourceWidth: Int, sourceHeight: Int): List<PointF> {
        val width = max(1f, bounds.right - bounds.left)
        val height = max(1f, bounds.bottom - bounds.top)
        val centerX = (bounds.left + bounds.right) / 2f
        val cycle = progress * 2f * PI.toFloat()
        val sway = sin(cycle * 2f)
        val counterSway = sin(cycle * 2f + PI.toFloat())
        val bounce = abs(sin(cycle * 4f))
        val wave = sin(cycle * 6f)
        val leftArmLift = ((sin(cycle * 2f + PI.toFloat() * 0.20f) + 1f) / 2f).coerceIn(0f, 1f)
        val rightArmLift = ((sin(cycle * 2f + PI.toFloat() * 1.20f) + 1f) / 2f).coerceIn(0f, 1f)
        val leftKick = ((sin(cycle * 2f + PI.toFloat()) + 1f) / 2f).coerceIn(0f, 1f)
        val rightKick = ((sin(cycle * 2f) + 1f) / 2f).coerceIn(0f, 1f)
        val torsoShift = sway * width * 0.11f
        val headShift = sway * width * 0.16f
        val bodyLift = -bounce * height * 0.055f
        val shoulderTilt = sway * height * 0.035f

        val nose = PointF(centerX + headShift, bounds.top + height * 0.15f + bodyLift - bounce * height * 0.020f)
        val leftEye = PointF(nose.x - width * 0.035f, nose.y - height * 0.025f)
        val rightEye = PointF(nose.x + width * 0.035f, nose.y - height * 0.025f)
        val leftEar = PointF(nose.x - width * 0.080f, nose.y + height * 0.012f)
        val rightEar = PointF(nose.x + width * 0.080f, nose.y + height * 0.012f)
        val shoulderY = bounds.top + height * 0.31f + bodyLift
        val hipY = bounds.top + height * (0.60f + bounce * 0.030f)
        val leftShoulder = PointF(centerX - width * 0.22f + torsoShift, shoulderY + shoulderTilt)
        val rightShoulder = PointF(centerX + width * 0.22f + torsoShift, shoulderY - shoulderTilt)
        val leftHip = PointF(centerX - width * 0.12f - torsoShift * 0.28f, hipY - shoulderTilt * 0.35f)
        val rightHip = PointF(centerX + width * 0.12f - torsoShift * 0.28f, hipY + shoulderTilt * 0.35f)

        val leftElbow = PointF(
            leftShoulder.x - width * (0.16f + leftArmLift * 0.27f) + wave * width * 0.035f,
            leftShoulder.y + height * (0.20f - leftArmLift * 0.43f)
        )
        val rightElbow = PointF(
            rightShoulder.x + width * (0.16f + rightArmLift * 0.27f) - wave * width * 0.035f,
            rightShoulder.y + height * (0.20f - rightArmLift * 0.43f)
        )
        val leftWrist = PointF(
            leftShoulder.x - width * (0.28f + leftArmLift * 0.34f) + wave * width * 0.060f,
            leftShoulder.y + height * (0.35f - leftArmLift * 0.78f)
        )
        val rightWrist = PointF(
            rightShoulder.x + width * (0.28f + rightArmLift * 0.34f) - wave * width * 0.060f,
            rightShoulder.y + height * (0.35f - rightArmLift * 0.78f)
        )
        val leftKnee = PointF(
            leftHip.x - width * (0.08f + leftKick * 0.23f),
            leftHip.y + height * (0.25f - leftKick * 0.16f)
        )
        val rightKnee = PointF(
            rightHip.x + width * (0.08f + rightKick * 0.23f),
            rightHip.y + height * (0.25f - rightKick * 0.16f)
        )
        val leftAnkle = PointF(
            leftHip.x - width * (0.12f + leftKick * 0.38f) + counterSway * width * 0.035f,
            leftHip.y + height * (0.42f - leftKick * 0.24f)
        )
        val rightAnkle = PointF(
            rightHip.x + width * (0.12f + rightKick * 0.38f) + sway * width * 0.035f,
            rightHip.y + height * (0.42f - rightKick * 0.24f)
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
