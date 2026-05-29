package com.example.myapplication

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 图像变形处理器
 * 基于关键点变化对图像进行变形处理，生成动作帧
 */
class ImageWarper {

    companion object {
        private const val TAG = "ImageWarper"
        private const val ENABLE_ARTICULATED_OVERLAYS = false
    }

    private data class BodySegment(
        val startIndex: Int,
        val endIndex: Int,
        val paddingRatio: Float,
        val maxRotationDegrees: Float,
        val alpha: Int
    )

    private data class PersonLayers(
        val foreground: Bitmap,
        val background: Bitmap,
        val foregroundBounds: RectF
    )

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
        originalPose: PoseDetector.Pose,
        frameProgress: Float = 0f
    ): Bitmap {
        val originalKeypoints = originalPose.keypoints.map { it.position }
        return createFullCharacterDanceFrame(sourceBitmap, originalKeypoints, animatedKeypoints, frameProgress)
    }

    private fun createFullCharacterDanceFrame(
        sourceBitmap: Bitmap,
        originalKeypoints: List<PointF>,
        animatedKeypoints: List<PointF>,
        frameProgress: Float
    ): Bitmap {
        val width = sourceBitmap.width
        val height = sourceBitmap.height
        val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)
        canvas.drawColor(Color.TRANSPARENT)
        val layers = extractPersonLayers(sourceBitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        canvas.drawBitmap(layers.background, 0f, 0f, paint)

        val originalCenter = calculateBodyCenter(originalKeypoints)
        val animatedCenter = calculateBodyCenter(animatedKeypoints)
        val cycle = frameProgress.coerceIn(0f, 1f) * 2f * PI.toFloat()
        val poseDx = animatedCenter.x - originalCenter.x
        val poseDy = animatedCenter.y - originalCenter.y
        val danceSway = sin(cycle * 2f) * width * 0.20f
        val danceBounce = -kotlin.math.abs(sin(cycle * 4f)) * height * 0.12f
        val shoulderPulse = cos(cycle * 2f) * height * 0.04f
        val dx = (poseDx * 1.8f + danceSway).coerceIn(-width * 0.26f, width * 0.26f)
        val dy = (poseDy * 1.6f + danceBounce + shoulderPulse).coerceIn(-height * 0.20f, height * 0.12f)

        val poseRotation = calculateShoulderRotation(originalKeypoints, animatedKeypoints)
        val danceRotation = sin(cycle * 2f) * 16f + sin(cycle * 4f) * 5f
        val rotation = (poseRotation * 2.2f + danceRotation).coerceIn(-24f, 24f)
        val scale = 0.78f + kotlin.math.abs(sin(cycle * 4f)) * 0.10f
        val skew = sin(cycle * 3f) * 0.055f

        val matrix = Matrix().apply {
            postTranslate(-originalCenter.x, -originalCenter.y)
            postScale(scale, scale)
            postSkew(skew, 0f)
            postRotate(rotation)
            postTranslate(originalCenter.x + dx, originalCenter.y + dy)
        }
        keepBoundsInsideCanvas(matrix, layers.foregroundBounds, width.toFloat(), height.toFloat())

        canvas.drawBitmap(layers.foreground, matrix, paint)
        if (ENABLE_ARTICULATED_OVERLAYS) {
            drawArticulatedBodyOverlays(
                canvas = canvas,
                sourceBitmap = layers.foreground,
                originalKeypoints = originalKeypoints,
                animatedKeypoints = animatedKeypoints,
                width = width.toFloat(),
                height = height.toFloat()
            )
        }
        layers.foreground.recycle()
        layers.background.recycle()
        return resultBitmap
    }

    private fun extractPersonLayers(sourceBitmap: Bitmap): PersonLayers {
        val width = sourceBitmap.width
        val height = sourceBitmap.height
        val pixels = IntArray(width * height)
        sourceBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val backgroundColor = estimateBackgroundColor(pixels, width, height)
        val backgroundMask = findConnectedBackground(pixels, width, height, backgroundColor)
        val foregroundPixels = IntArray(pixels.size)
        val backgroundPixels = IntArray(pixels.size)
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1

        for (index in pixels.indices) {
            val isForeground = !backgroundMask[index] && Color.alpha(pixels[index]) > 20
            if (isForeground) {
                foregroundPixels[index] = pixels[index]
                val x = index % width
                val y = index / width
                minX = minOf(minX, x)
                minY = minOf(minY, y)
                maxX = maxOf(maxX, x)
                maxY = maxOf(maxY, y)
            } else {
                backgroundPixels[index] = pixels[index]
            }
        }

        if (maxX < minX || maxY < minY) {
            for (index in pixels.indices) {
                foregroundPixels[index] = pixels[index]
                backgroundPixels[index] = Color.TRANSPARENT
            }
            minX = 0
            minY = 0
            maxX = width - 1
            maxY = height - 1
        }

        val foreground = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        foreground.setPixels(foregroundPixels, 0, width, 0, 0, width, height)
        val background = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        background.setPixels(backgroundPixels, 0, width, 0, 0, width, height)
        val bounds = RectF(minX.toFloat(), minY.toFloat(), (maxX + 1).toFloat(), (maxY + 1).toFloat())
        return PersonLayers(foreground, background, bounds)
    }

    private fun estimateBackgroundColor(pixels: IntArray, width: Int, height: Int): Int {
        val cornerIndices = intArrayOf(0, width - 1, (height - 1) * width, height * width - 1)
        var alpha = 0
        var red = 0
        var green = 0
        var blue = 0
        cornerIndices.forEach { index ->
            val color = pixels[index]
            alpha += Color.alpha(color)
            red += Color.red(color)
            green += Color.green(color)
            blue += Color.blue(color)
        }
        return Color.argb(alpha / 4, red / 4, green / 4, blue / 4)
    }

    private fun findConnectedBackground(
        pixels: IntArray,
        width: Int,
        height: Int,
        backgroundColor: Int
    ): BooleanArray {
        val visited = BooleanArray(pixels.size)
        val queue = java.util.ArrayDeque<Int>()

        fun tryAdd(index: Int) {
            if (!visited[index] && isBackgroundLike(pixels[index], backgroundColor)) {
                visited[index] = true
                queue.add(index)
            }
        }

        for (x in 0 until width) {
            tryAdd(x)
            tryAdd((height - 1) * width + x)
        }
        for (y in 0 until height) {
            tryAdd(y * width)
            tryAdd(y * width + width - 1)
        }

        while (queue.isNotEmpty()) {
            val index = queue.removeFirst()
            val x = index % width
            val y = index / width
            if (x > 0) tryAdd(index - 1)
            if (x < width - 1) tryAdd(index + 1)
            if (y > 0) tryAdd(index - width)
            if (y < height - 1) tryAdd(index + width)
        }

        return visited
    }

    private fun isBackgroundLike(color: Int, backgroundColor: Int): Boolean {
        if (Color.alpha(color) <= 20) return true
        val redDiff = Color.red(color) - Color.red(backgroundColor)
        val greenDiff = Color.green(color) - Color.green(backgroundColor)
        val blueDiff = Color.blue(color) - Color.blue(backgroundColor)
        val distanceSquared = redDiff * redDiff + greenDiff * greenDiff + blueDiff * blueDiff
        return distanceSquared < 38 * 38 * 3
    }

    private fun drawArticulatedBodyOverlays(
        canvas: Canvas,
        sourceBitmap: Bitmap,
        originalKeypoints: List<PointF>,
        animatedKeypoints: List<PointF>,
        width: Float,
        height: Float
    ) {
        val segments = listOf(
            BodySegment(5, 7, 0.36f, 14f, 185),
            BodySegment(7, 9, 0.42f, 18f, 195),
            BodySegment(6, 8, 0.36f, 14f, 185),
            BodySegment(8, 10, 0.42f, 18f, 195),
            BodySegment(11, 13, 0.34f, 10f, 175),
            BodySegment(13, 15, 0.36f, 12f, 185),
            BodySegment(12, 14, 0.34f, 10f, 175),
            BodySegment(14, 16, 0.36f, 12f, 185),
            BodySegment(5, 6, 0.50f, 6f, 165),
            BodySegment(0, 5, 0.48f, 8f, 170),
            BodySegment(0, 6, 0.48f, 8f, 170)
        )

        segments.forEach { segment ->
            drawSegmentOverlay(
                canvas = canvas,
                sourceBitmap = sourceBitmap,
                originalKeypoints = originalKeypoints,
                animatedKeypoints = animatedKeypoints,
                segment = segment,
                width = width,
                height = height
            )
        }
    }

    private fun drawSegmentOverlay(
        canvas: Canvas,
        sourceBitmap: Bitmap,
        originalKeypoints: List<PointF>,
        animatedKeypoints: List<PointF>,
        segment: BodySegment,
        width: Float,
        height: Float
    ) {
        val originalStart = originalKeypoints.getOrNull(segment.startIndex) ?: return
        val originalEnd = originalKeypoints.getOrNull(segment.endIndex) ?: return
        val targetStart = animatedKeypoints.getOrNull(segment.startIndex) ?: return
        val targetEnd = animatedKeypoints.getOrNull(segment.endIndex) ?: return
        val originalMid = midpoint(originalStart, originalEnd)
        val targetMid = midpoint(targetStart, targetEnd)

        val originalLength = distance(originalStart, originalEnd).coerceAtLeast(minOf(width, height) * 0.08f)
        val targetLength = distance(targetStart, targetEnd).coerceAtLeast(originalLength * 0.75f)
        val scale = (targetLength / originalLength).coerceIn(0.92f, 1.08f)
        val dx = (targetMid.x - originalMid.x).coerceIn(-width * 0.075f, width * 0.075f)
        val dy = (targetMid.y - originalMid.y).coerceIn(-height * 0.075f, height * 0.075f)
        val rotation = angleDelta(originalStart, originalEnd, targetStart, targetEnd)
            .coerceIn(-segment.maxRotationDegrees, segment.maxRotationDegrees)

        val targetRect = segmentRect(
            start = targetStart,
            end = targetEnd,
            padding = originalLength * segment.paddingRatio,
            width = width,
            height = height
        )
        val clipPath = Path().apply {
            addOval(targetRect, Path.Direction.CW)
        }
        val matrix = Matrix().apply {
            postTranslate(-originalMid.x, -originalMid.y)
            postScale(scale, scale)
            postRotate(rotation)
            postTranslate(originalMid.x + dx, originalMid.y + dy)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
            alpha = segment.alpha
        }

        canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawBitmap(sourceBitmap, matrix, paint)
        canvas.restore()
    }

    private fun calculateBodyCenter(points: List<PointF>): PointF {
        val bodyIndices = intArrayOf(5, 6, 11, 12)
        val bodyPoints = mutableListOf<PointF>()
        bodyIndices.forEach { index ->
            points.getOrNull(index)?.let { bodyPoints.add(it) }
        }
        return if (bodyPoints.size >= 2) {
            calculateCenter(bodyPoints)
        } else {
            calculateCenter(points)
        }
    }

    private fun calculateShoulderRotation(original: List<PointF>, target: List<PointF>): Float {
        val originalLeft = original.getOrNull(5) ?: return 0f
        val originalRight = original.getOrNull(6) ?: return 0f
        val targetLeft = target.getOrNull(5) ?: return 0f
        val targetRight = target.getOrNull(6) ?: return 0f
        val originalAngle = atan2(originalRight.y - originalLeft.y, originalRight.x - originalLeft.x)
        val targetAngle = atan2(targetRight.y - targetLeft.y, targetRight.x - targetLeft.x)
        return normalizeDegrees((targetAngle - originalAngle) * 180f / Math.PI.toFloat())
    }

    private fun midpoint(start: PointF, end: PointF): PointF {
        return PointF((start.x + end.x) / 2f, (start.y + end.y) / 2f)
    }

    private fun distance(start: PointF, end: PointF): Float {
        val dx = end.x - start.x
        val dy = end.y - start.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun angleDelta(
        originalStart: PointF,
        originalEnd: PointF,
        targetStart: PointF,
        targetEnd: PointF
    ): Float {
        val originalAngle = atan2(originalEnd.y - originalStart.y, originalEnd.x - originalStart.x)
        val targetAngle = atan2(targetEnd.y - targetStart.y, targetEnd.x - targetStart.x)
        return normalizeDegrees((targetAngle - originalAngle) * 180f / Math.PI.toFloat())
    }

    private fun segmentRect(start: PointF, end: PointF, padding: Float, width: Float, height: Float): RectF {
        return RectF(
            minOf(start.x, end.x) - padding,
            minOf(start.y, end.y) - padding,
            maxOf(start.x, end.x) + padding,
            maxOf(start.y, end.y) + padding
        ).apply {
            left = left.coerceIn(0f, width)
            top = top.coerceIn(0f, height)
            right = right.coerceIn(0f, width)
            bottom = bottom.coerceIn(0f, height)
        }
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
