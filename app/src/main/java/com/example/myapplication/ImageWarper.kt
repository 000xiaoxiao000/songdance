package com.example.myapplication

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * 图像变形处理器
 * 基于关键点变化对图像进行变形处理，生成动作帧
 */
class ImageWarper {

    companion object {
        private const val TAG = "ImageWarper"
    }

    fun warpImage(
        sourceBitmap: Bitmap,
        originalKeypoints: List<PointF>,
        targetKeypoints: List<PointF>
    ): Bitmap {
        require(originalKeypoints.size == targetKeypoints.size) {
            "Original and target keypoints must have the same size"
        }

        val width = sourceBitmap.width
        val height = sourceBitmap.height

        val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)

        val transform = calculateGlobalTransform(originalKeypoints, targetKeypoints)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            alpha = 255
        }
        
        canvas.save()
        canvas.concat(transform)
        canvas.drawBitmap(sourceBitmap, 0f, 0f, paint)
        canvas.restore()

        return resultBitmap
    }
    
    private fun calculateTransformedBounds(width: Float, height: Float, transform: Matrix): android.graphics.RectF {
        val corners = floatArrayOf(
            0f, 0f,
            width, 0f,
            width, height,
            0f, height
        )
        transform.mapPoints(corners)
        
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE
        
        for (i in corners.indices step 2) {
            minX = minOf(minX, corners[i])
            maxX = maxOf(maxX, corners[i])
            minY = minOf(minY, corners[i + 1])
            maxY = maxOf(maxY, corners[i + 1])
        }
        
        return android.graphics.RectF(minX, minY, maxX, maxY)
    }

    private fun calculateGlobalTransform(
        original: List<PointF>,
        target: List<PointF>
    ): Matrix {
        val originalCenter = calculateCenter(original)
        val targetCenter = calculateCenter(target)

        val originalScale = calculateAverageDistance(original, originalCenter)
        val targetScale = calculateAverageDistance(target, targetCenter)
        val scale = if (originalScale > 0) targetScale / originalScale else 1f

        val originalAngle = calculateAverageAngle(original, originalCenter)
        val targetAngle = calculateAverageAngle(target, targetCenter)
        val rotation = targetAngle - originalAngle

        val matrix = Matrix()
        matrix.postTranslate(-originalCenter.x, -originalCenter.y)
        matrix.postScale(scale, scale)
        matrix.postRotate(rotation * 180f / Math.PI.toFloat())
        matrix.postTranslate(targetCenter.x, targetCenter.y)

        return matrix
    }

    private fun calculateCenter(points: List<PointF>): PointF {
        var sumX = 0f
        var sumY = 0f

        points.forEach { point ->
            sumX += point.x
            sumY += point.y
        }

        return PointF(sumX / points.size, sumY / points.size)
    }

    private fun calculateAverageDistance(points: List<PointF>, center: PointF): Float {
        var sumDistance = 0f

        points.forEach { point ->
            val dx = point.x - center.x
            val dy = point.y - center.y
            sumDistance += sqrt(dx * dx + dy * dy)
        }

        return sumDistance / points.size
    }

    private fun calculateAverageAngle(points: List<PointF>, center: PointF): Float {
        var sumAngle = 0f
        var count = 0

        points.forEach { point ->
            val dx = point.x - center.x
            val dy = point.y - center.y
            if (dx != 0f || dy != 0f) {
                sumAngle += atan2(dy, dx)
                count++
            }
        }

        return if (count > 0) sumAngle / count else 0f
    }

    fun createFrameWithPose(
        sourceBitmap: Bitmap,
        animatedKeypoints: List<PointF>,
        originalPose: PoseDetector.Pose
    ): Bitmap {
        val originalKeypoints = originalPose.keypoints.map { it.position }
        val warpedBitmap = warpImage(sourceBitmap, originalKeypoints, animatedKeypoints)
        
        if (warpedBitmap.width != sourceBitmap.width || warpedBitmap.height != sourceBitmap.height) {
            val resizedBitmap = Bitmap.createScaledBitmap(
                warpedBitmap,
                sourceBitmap.width,
                sourceBitmap.height,
                true
            )
            if (warpedBitmap != resizedBitmap) {
                warpedBitmap.recycle()
            }
            return resizedBitmap
        }
        
        return warpedBitmap
    }

    fun applyBodyPartTransform(
        sourceBitmap: Bitmap,
        bodyPart: BodyPart,
        transform: Matrix
    ): Bitmap {
        val width = sourceBitmap.width
        val height = sourceBitmap.height

        val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        canvas.drawBitmap(sourceBitmap, 0f, 0f, paint)

        canvas.save()
        canvas.concat(transform)
        canvas.restore()

        return resultBitmap
    }

    enum class BodyPart {
        HEAD,
        TORSO,
        LEFT_ARM,
        RIGHT_ARM,
        LEFT_LEG,
        RIGHT_LEG
    }

    fun blendFrames(frame1: Bitmap, frame2: Bitmap, alpha: Float): Bitmap {
        require(frame1.width == frame2.width && frame1.height == frame2.height) {
            "Frames must have the same dimensions"
        }

        val width = frame1.width
        val height = frame1.height

        val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)

        val paint1 = Paint().apply {
            this.alpha = ((1f - alpha) * 255).toInt()
        }
        canvas.drawBitmap(frame1, 0f, 0f, paint1)

        val paint2 = Paint().apply {
            this.alpha = (alpha * 255).toInt()
        }
        canvas.drawBitmap(frame2, 0f, 0f, paint2)

        return resultBitmap
    }

    fun smoothTransition(
        sourceBitmap: Bitmap,
        startKeypoints: List<PointF>,
        endKeypoints: List<PointF>,
        steps: Int
    ): List<Bitmap> {
        val frames = mutableListOf<Bitmap>()

        for (i in 0..steps) {
            val progress = i.toFloat() / steps
            val interpolatedKeypoints = interpolateKeypoints(
                startKeypoints,
                endKeypoints,
                progress
            )

            val frame = warpImage(sourceBitmap, startKeypoints, interpolatedKeypoints)
            frames.add(frame)
        }

        return frames
    }

    private fun interpolateKeypoints(
        start: List<PointF>,
        end: List<PointF>,
        progress: Float
    ): List<PointF> {
        return start.zip(end) { startPoint, endPoint ->
            PointF(
                startPoint.x + (endPoint.x - startPoint.x) * progress,
                startPoint.y + (endPoint.y - startPoint.y) * progress
            )
        }
    }
}
