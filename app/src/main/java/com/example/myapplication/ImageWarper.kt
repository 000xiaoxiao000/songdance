package com.example.myapplication

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Lightweight uploaded-person retargeting fallback.
 * This is intentionally small: real image generation should be handled by a provided model.
 */
class ImageWarper {

    private data class PersonLayer(
        val foreground: Bitmap,
        val foregroundBounds: RectF
    )

    fun createFrameWithPose(
        sourceBitmap: Bitmap,
        animatedKeypoints: List<PointF>,
        originalPose: PoseDetector.Pose,
        frameProgress: Float = 0f
    ): Bitmap {
        val originalKeypoints = originalPose.keypoints.map { it.position }
        val width = sourceBitmap.width
        val height = sourceBitmap.height
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.TRANSPARENT)

        val layer = extractPersonLayer(sourceBitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        val originalCenter = bodyCenter(originalKeypoints, layer.foregroundBounds)
        val animatedCenter = bodyCenter(animatedKeypoints, layer.foregroundBounds)
        val cycle = frameProgress.coerceIn(0f, 1f) * 2f * PI.toFloat()
        val poseDx = animatedCenter.x - originalCenter.x
        val poseDy = animatedCenter.y - originalCenter.y
        val dx = (poseDx * 1.7f + sin(cycle * 2f) * width * 0.16f).coerceIn(-width * 0.24f, width * 0.24f)
        val dy = (poseDy * 1.6f - abs(sin(cycle * 4f)) * height * 0.10f + cos(cycle * 2f) * height * 0.06f)
            .coerceIn(-height * 0.18f, height * 0.12f)
        val rotation = (shoulderRotation(originalKeypoints, animatedKeypoints) * 3.0f + sin(cycle * 2f) * 30f + sin(cycle * 4f) * 12f)
            .coerceIn(-42f, 42f)
        val scaleX = 0.82f + abs(sin(cycle * 4f)) * 0.10f
        val scaleY = 0.84f + abs(cos(cycle * 4f)) * 0.10f
        val skew = sin(cycle * 3f) * 0.10f

        val matrix = Matrix().apply {
            postTranslate(-originalCenter.x, -originalCenter.y)
            postScale(scaleX, scaleY)
            postSkew(skew, 0f)
            postRotate(rotation)
            postTranslate(originalCenter.x + dx, originalCenter.y + dy)
        }
        keepBoundsInsideCanvas(matrix, layer.foregroundBounds, width.toFloat(), height.toFloat())
        canvas.drawBitmap(layer.foreground, matrix, paint)
        layer.foreground.recycle()
        return result
    }

    private fun extractPersonLayer(source: Bitmap): PersonLayer {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val background = estimateBackground(pixels, width, height)
        val foregroundPixels = IntArray(pixels.size)
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        for (index in pixels.indices) {
            val color = pixels[index]
            val foreground = Color.alpha(color) > 20 && !looksLikeBackground(color, background)
            if (foreground) {
                foregroundPixels[index] = color
                val x = index % width
                val y = index / width
                minX = min(minX, x)
                minY = min(minY, y)
                maxX = max(maxX, x)
                maxY = max(maxY, y)
            }
        }
        if (maxX < minX || maxY < minY) {
            source.getPixels(foregroundPixels, 0, width, 0, 0, width, height)
            minX = 0
            minY = 0
            maxX = width - 1
            maxY = height - 1
        }
        val foreground = Bitmap.createBitmap(foregroundPixels, width, height, Bitmap.Config.ARGB_8888)
        return PersonLayer(foreground, RectF(minX.toFloat(), minY.toFloat(), (maxX + 1).toFloat(), (maxY + 1).toFloat()))
    }

    private fun estimateBackground(pixels: IntArray, width: Int, height: Int): Int {
        val samples = intArrayOf(0, width - 1, (height - 1) * width, height * width - 1)
        var red = 0
        var green = 0
        var blue = 0
        var count = 0
        samples.forEach { index ->
            if (index in pixels.indices) {
                val color = pixels[index]
                red += Color.red(color)
                green += Color.green(color)
                blue += Color.blue(color)
                count++
            }
        }
        return if (count == 0) Color.TRANSPARENT else Color.rgb(red / count, green / count, blue / count)
    }

    private fun looksLikeBackground(color: Int, background: Int): Boolean {
        if (Color.alpha(color) <= 20) return true
        val dr = Color.red(color) - Color.red(background)
        val dg = Color.green(color) - Color.green(background)
        val db = Color.blue(color) - Color.blue(background)
        val distance = dr * dr + dg * dg + db * db
        val brightness = (Color.red(color) + Color.green(color) + Color.blue(color)) / 3
        return distance < 32 * 32 || brightness > 246
    }

    private fun bodyCenter(points: List<PointF>, fallbackBounds: RectF): PointF {
        val indices = intArrayOf(5, 6, 11, 12)
        val selected = ArrayList<PointF>(indices.size)
        indices.forEach { index -> points.getOrNull(index)?.let { selected.add(it) } }
        if (selected.size < 2) return PointF(fallbackBounds.centerX(), fallbackBounds.centerY())
        var sumX = 0f
        var sumY = 0f
        selected.forEach { point ->
            sumX += point.x
            sumY += point.y
        }
        return PointF(sumX / selected.size, sumY / selected.size)
    }

    private fun shoulderRotation(original: List<PointF>, target: List<PointF>): Float {
        val originalLeft = original.getOrNull(5) ?: return 0f
        val originalRight = original.getOrNull(6) ?: return 0f
        val targetLeft = target.getOrNull(5) ?: return 0f
        val targetRight = target.getOrNull(6) ?: return 0f
        val originalAngle = atan2(originalRight.y - originalLeft.y, originalRight.x - originalLeft.x)
        val targetAngle = atan2(targetRight.y - targetLeft.y, targetRight.x - targetLeft.x)
        return normalizeDegrees((targetAngle - originalAngle) * 180f / PI.toFloat())
    }

    private fun normalizeDegrees(value: Float): Float {
        var degrees = value
        while (degrees > 180f) degrees -= 360f
        while (degrees < -180f) degrees += 360f
        return degrees
    }

    private fun keepBoundsInsideCanvas(matrix: Matrix, sourceBounds: RectF, width: Float, height: Float) {
        val bounds = RectF(sourceBounds)
        matrix.mapRect(bounds)
        var translateX = 0f
        var translateY = 0f
        if (bounds.width() <= width) {
            translateX = when {
                bounds.left < 0f -> -bounds.left
                bounds.right > width -> width - bounds.right
                else -> 0f
            }
        }
        if (bounds.height() <= height) {
            translateY = when {
                bounds.top < 0f -> -bounds.top
                bounds.bottom > height -> height - bounds.bottom
                else -> 0f
            }
        }
        matrix.postTranslate(translateX, translateY)
    }
}
