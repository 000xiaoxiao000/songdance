package com.example.myapplication

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF

/**
 * Renders MoveNet keypoints into a pose-condition image for a pose-driven generator.
 * This is model input only; it is not used as a visible fallback frame.
 */
class PoseMapRenderer {

    data class PoseFrame(
        val bitmap: Bitmap,
        val transformedKeypoints: List<PointF>
    )

    fun render(
        keypoints: List<PointF>,
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): PoseFrame {
        val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)

        val contentRect = fitCenterRect(
            sourceWidth = sourceWidth.toFloat(),
            sourceHeight = sourceHeight.toFloat(),
            targetWidth = targetWidth.toFloat(),
            targetHeight = targetHeight.toFloat()
        )
        val scale = contentRect.width() / sourceWidth.coerceAtLeast(1).toFloat()
        val transformed = keypoints.map { point ->
            PointF(contentRect.left + point.x * scale, contentRect.top + point.y * scale)
        }

        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = maxOf(2f, minOf(targetWidth, targetHeight) * 0.018f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        PoseDetector.SKELETON_CONNECTIONS.forEachIndexed { index, connection ->
            val start = transformed.getOrNull(connection.first) ?: return@forEachIndexed
            val end = transformed.getOrNull(connection.second) ?: return@forEachIndexed
            linePaint.color = connectionColor(index)
            canvas.drawLine(start.x, start.y, end.x, end.y, linePaint)
        }

        val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        val radius = maxOf(2f, minOf(targetWidth, targetHeight) * 0.014f)
        transformed.forEach { point ->
            canvas.drawCircle(point.x, point.y, radius, pointPaint)
        }

        return PoseFrame(bitmap = bitmap, transformedKeypoints = transformed)
    }

    private fun fitCenterRect(sourceWidth: Float, sourceHeight: Float, targetWidth: Float, targetHeight: Float): RectF {
        val scale = minOf(targetWidth / sourceWidth.coerceAtLeast(1f), targetHeight / sourceHeight.coerceAtLeast(1f))
        val width = sourceWidth * scale
        val height = sourceHeight * scale
        val left = (targetWidth - width) / 2f
        val top = (targetHeight - height) / 2f
        return RectF(left, top, left + width, top + height)
    }

    private fun connectionColor(index: Int): Int {
        val palette = intArrayOf(
            Color.rgb(255, 64, 64),
            Color.rgb(255, 160, 64),
            Color.rgb(255, 224, 64),
            Color.rgb(96, 224, 96),
            Color.rgb(64, 224, 192),
            Color.rgb(64, 160, 255),
            Color.rgb(96, 96, 255),
            Color.rgb(192, 96, 255),
            Color.rgb(255, 96, 192),
            Color.rgb(192, 255, 96),
            Color.rgb(96, 255, 255),
            Color.rgb(255, 255, 255)
        )
        return palette[index % palette.size]
    }
}
