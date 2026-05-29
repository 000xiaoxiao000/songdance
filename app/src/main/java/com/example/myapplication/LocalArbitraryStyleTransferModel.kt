package com.example.myapplication

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Google Magenta Arbitrary Image Stylization TFLite pipeline.
 * Uses style_predict.tflite + style_transform.tflite, both stored locally in assets/models.
 */
class LocalArbitraryStyleTransferModel(private val context: Context) {

    private var stylePredictInterpreter: Interpreter? = null
    private var styleTransformInterpreter: Interpreter? = null

    companion object {
        private const val TAG = "LocalArbitraryStyleTransferModel"
        private const val STYLE_PREDICT_MODEL = "models/style_predict.tflite"
        private const val STYLE_TRANSFORM_MODEL = "models/style_transform.tflite"
    }

    fun stylize(sourceBitmap: Bitmap): Bitmap? {
        if (!ensureInitialized()) return null
        val predict = stylePredictInterpreter ?: return null
        val transform = styleTransformInterpreter ?: return null

        return try {
            val styleImage = createAvatarStyleReference(256, 256)
            val styleInput = bitmapToTensorBuffer(styleImage, predict.getInputTensor(0))
            val styleOutputTensor = predict.getOutputTensor(0)
            val styleBottleneck = allocateTensorBuffer(styleOutputTensor)
            predict.run(styleInput, styleBottleneck)
            styleBottleneck.rewind()
            styleImage.recycle()

            val contentTensor = transform.getInputTensor(0)
            val contentInput = bitmapToTensorBuffer(sourceBitmap, contentTensor)
            val outputTensor = transform.getOutputTensor(0)
            val outputBuffer = allocateTensorBuffer(outputTensor)
            val inputs = arrayOf<Any>(contentInput, styleBottleneck)
            val outputs = mutableMapOf<Int, Any>(0 to outputBuffer)

            try {
                transform.runForMultipleInputsOutputs(inputs, outputs)
            } catch (first: Exception) {
                Log.w(TAG, "content/style 输入顺序失败，尝试交换输入顺序", first)
                styleBottleneck.rewind()
                contentInput.rewind()
                outputBuffer.rewind()
                transform.runForMultipleInputsOutputs(arrayOf<Any>(styleBottleneck, contentInput), outputs)
            }

            outputBuffer.rewind()
            val stylizedBitmap = tensorBufferToBitmap(outputBuffer, outputTensor)
            postProcessStylizedPerson(sourceBitmap, stylizedBitmap)?.takeIf { isUsableStylizedPerson(it) }
        } catch (e: Exception) {
            Log.e(TAG, "本地 arbitrary style transfer 推理失败", e)
            null
        }
    }

    fun release() {
        stylePredictInterpreter?.close()
        styleTransformInterpreter?.close()
        stylePredictInterpreter = null
        styleTransformInterpreter = null
    }

    private fun ensureInitialized(): Boolean {
        if (stylePredictInterpreter != null && styleTransformInterpreter != null) return true
        return try {
            val options = Interpreter.Options().apply {
                setNumThreads(4)
                setUseNNAPI(true)
            }
            stylePredictInterpreter = Interpreter(FileUtil.loadMappedFile(context, STYLE_PREDICT_MODEL), options)
            styleTransformInterpreter = Interpreter(FileUtil.loadMappedFile(context, STYLE_TRANSFORM_MODEL), options)
            Log.d(TAG, "已加载本地 arbitrary style transfer 模型")
            true
        } catch (e: Exception) {
            Log.w(TAG, "未找到或无法加载 arbitrary style transfer 模型", e)
            release()
            false
        }
    }

    private fun bitmapToTensorBuffer(bitmap: Bitmap, tensor: Tensor): ByteBuffer {
        val shape = tensor.shape()
        val width = shape[2].coerceAtLeast(1)
        val height = shape[1].coerceAtLeast(1)
        val channels = shape[3].coerceAtLeast(1)
        val resized = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(resized).drawBitmap(bitmap, null, Rect(0, 0, width, height), null)
        val buffer = ByteBuffer.allocateDirect(tensorByteSize(tensor)).order(ByteOrder.nativeOrder())
        for (y in 0 until height) {
            for (x in 0 until width) {
                val color = resized.getPixel(x, y)
                writeChannel(buffer, Color.red(color), tensor.dataType())
                if (channels > 1) writeChannel(buffer, Color.green(color), tensor.dataType())
                if (channels > 2) writeChannel(buffer, Color.blue(color), tensor.dataType())
                if (channels > 3) writeChannel(buffer, Color.alpha(color), tensor.dataType())
            }
        }
        resized.recycle()
        buffer.rewind()
        return buffer
    }

    private fun tensorBufferToBitmap(buffer: ByteBuffer, tensor: Tensor): Bitmap {
        val shape = tensor.shape()
        val width = shape[2].coerceAtLeast(1)
        val height = shape[1].coerceAtLeast(1)
        val channels = shape[3].coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        when (tensor.dataType()) {
            DataType.FLOAT32 -> decodeFloatBitmap(buffer, bitmap, width, height, channels)
            DataType.UINT8 -> decodeByteBitmap(buffer, bitmap, width, height, channels, signed = false)
            DataType.INT8 -> decodeByteBitmap(buffer, bitmap, width, height, channels, signed = true)
            else -> throw IllegalArgumentException("不支持的输出类型: ${tensor.dataType()}")
        }
        return bitmap
    }

    private fun writeChannel(buffer: ByteBuffer, value: Int, dataType: DataType) {
        when (dataType) {
            DataType.FLOAT32 -> buffer.putFloat(value / 255f)
            DataType.UINT8 -> buffer.put(value.toByte())
            DataType.INT8 -> buffer.put((value - 128).toByte())
            else -> throw IllegalArgumentException("不支持的输入类型: $dataType")
        }
    }

    private fun decodeFloatBitmap(buffer: ByteBuffer, bitmap: Bitmap, width: Int, height: Int, channels: Int) {
        val totalValues = width * height * channels
        val values = FloatArray(totalValues)
        for (index in values.indices) values[index] = buffer.float
        val minValue = values.minOrNull() ?: 0f
        val maxValue = values.maxOrNull() ?: 1f
        var cursor = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val red = floatToColorChannel(values[cursor++], minValue, maxValue)
                val green = if (channels > 1) floatToColorChannel(values[cursor++], minValue, maxValue) else red
                val blue = if (channels > 2) floatToColorChannel(values[cursor++], minValue, maxValue) else green
                val alpha = if (channels > 3) floatToColorChannel(values[cursor++], minValue, maxValue) else 255
                bitmap.setPixel(x, y, Color.argb(alpha, red, green, blue))
            }
        }
    }

    private fun decodeByteBitmap(buffer: ByteBuffer, bitmap: Bitmap, width: Int, height: Int, channels: Int, signed: Boolean) {
        for (y in 0 until height) {
            for (x in 0 until width) {
                val red = byteToColorChannel(buffer.get(), signed)
                val green = if (channels > 1) byteToColorChannel(buffer.get(), signed) else red
                val blue = if (channels > 2) byteToColorChannel(buffer.get(), signed) else green
                val alpha = if (channels > 3) byteToColorChannel(buffer.get(), signed) else 255
                bitmap.setPixel(x, y, Color.argb(alpha, red, green, blue))
            }
        }
    }

    private fun floatToColorChannel(value: Float, minValue: Float, maxValue: Float): Int {
        val normalized = when {
            minValue < 0f -> (value + 1f) / 2f
            maxValue > 1.5f -> value / 255f
            else -> value
        }
        return (normalized.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)
    }

    private fun byteToColorChannel(value: Byte, signed: Boolean): Int {
        return if (signed) (value.toInt() + 128).coerceIn(0, 255) else (value.toInt() and 0xFF)
    }

    private fun allocateTensorBuffer(tensor: Tensor): ByteBuffer {
        return ByteBuffer.allocateDirect(tensorByteSize(tensor)).order(ByteOrder.nativeOrder())
    }

    private fun tensorByteSize(tensor: Tensor): Int {
        return tensor.shape().fold(1) { acc, value -> acc * value.coerceAtLeast(1) } * bytesPerElement(tensor.dataType())
    }

    private fun bytesPerElement(dataType: DataType): Int {
        return when (dataType) {
            DataType.FLOAT32 -> 4
            DataType.UINT8, DataType.INT8 -> 1
            else -> throw IllegalArgumentException("不支持的数据类型: $dataType")
        }
    }

    private fun createAvatarStyleReference(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        canvas.drawColor(Color.WHITE)

        paint.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), Color.rgb(255, 210, 230), Color.rgb(135, 225, 248), Shader.TileMode.CLAMP)
        canvas.drawCircle(width * 0.52f, height * 0.58f, width * 0.42f, paint)
        paint.shader = null

        paint.color = Color.rgb(255, 234, 224)
        canvas.drawCircle(width * 0.45f, height * 0.34f, width * 0.18f, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 7f
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.rgb(60, 58, 118)
        canvas.drawCircle(width * 0.45f, height * 0.34f, width * 0.18f, paint)
        canvas.drawLine(width * 0.31f, height * 0.50f, width * 0.08f, height * 0.39f, paint)
        canvas.drawLine(width * 0.63f, height * 0.50f, width * 0.91f, height * 0.34f, paint)
        canvas.drawLine(width * 0.49f, height * 0.74f, width * 0.35f, height * 0.96f, paint)
        canvas.drawLine(width * 0.56f, height * 0.74f, width * 0.85f, height * 0.62f, paint)
        paint.style = Paint.Style.FILL

        paint.color = Color.rgb(58, 62, 138)
        canvas.drawCircle(width * 0.35f, height * 0.25f, width * 0.09f, paint)
        canvas.drawCircle(width * 0.55f, height * 0.24f, width * 0.09f, paint)
        paint.shader = LinearGradient(width * 0.58f, height * 0.28f, width * 0.96f, height * 0.57f, Color.rgb(82, 205, 236), Color.rgb(205, 245, 255), Shader.TileMode.CLAMP)
        val ponytail = Path().apply {
            moveTo(width * 0.58f, height * 0.28f)
            cubicTo(width * 0.88f, height * 0.30f, width * 1.00f, height * 0.46f, width * 0.78f, height * 0.60f)
            cubicTo(width * 0.96f, height * 0.45f, width * 0.82f, height * 0.34f, width * 0.58f, height * 0.28f)
            close()
        }
        canvas.drawPath(ponytail, paint)
        paint.shader = null

        paint.color = Color.rgb(255, 196, 220)
        val dress = Path().apply {
            moveTo(width * 0.35f, height * 0.43f)
            lineTo(width * 0.61f, height * 0.43f)
            lineTo(width * 0.78f, height * 0.76f)
            cubicTo(width * 0.58f, height * 0.92f, width * 0.30f, height * 0.86f, width * 0.20f, height * 0.76f)
            close()
        }
        canvas.drawPath(dress, paint)

        paint.color = Color.rgb(78, 86, 150)
        paint.strokeWidth = 5f
        paint.style = Paint.Style.STROKE
        canvas.drawPath(dress, paint)
        paint.color = Color.rgb(80, 210, 238)
        canvas.drawLine(width * 0.35f, height * 0.49f, width * 0.64f, height * 0.49f, paint)
        paint.strokeWidth = 3f
        paint.color = Color.WHITE
        canvas.drawArc(android.graphics.RectF(width * 0.25f, height * 0.66f, width * 0.74f, height * 0.86f), 18f, 145f, false, paint)
        paint.style = Paint.Style.FILL
        return bitmap
    }

    private fun postProcessStylizedPerson(sourceBitmap: Bitmap, stylizedBitmap: Bitmap): Bitmap? {
        val width = stylizedBitmap.width
        val height = stylizedBitmap.height
        val sourceResized = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(sourceResized).drawBitmap(sourceBitmap, null, Rect(0, 0, width, height), null)
        val foregroundMask = buildForegroundMask(sourceResized)
        val transparentPerson = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                if (foregroundMask[index]) {
                    transparentPerson.setPixel(x, y, enhanceStylizedColor(stylizedBitmap.getPixel(x, y)))
                } else {
                    transparentPerson.setPixel(x, y, Color.TRANSPARENT)
                }
            }
        }

        sourceResized.recycle()
        stylizedBitmap.recycle()
        return normalizePersonCanvas(transparentPerson)
    }

    private fun normalizePersonCanvas(personBitmap: Bitmap): Bitmap {
        val bounds = findOpaqueBounds(personBitmap)
        if (bounds == null) return personBitmap

        val outputSize = 640
        val result = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.TRANSPARENT)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        val maxPersonWidth = outputSize * 0.54f
        val maxPersonHeight = outputSize * 0.68f
        val scale = minOf(maxPersonWidth / bounds.width(), maxPersonHeight / bounds.height()).coerceAtMost(1.3f)
        val drawWidth = bounds.width() * scale
        val drawHeight = bounds.height() * scale
        val dst = RectF(
            (outputSize - drawWidth) / 2f,
            outputSize * 0.54f - drawHeight / 2f,
            (outputSize + drawWidth) / 2f,
            outputSize * 0.54f + drawHeight / 2f
        )
        canvas.drawBitmap(personBitmap, bounds, dst, paint)
        personBitmap.recycle()
        return result
    }

    private fun findOpaqueBounds(bitmap: Bitmap): Rect? {
        var left = bitmap.width
        var top = bitmap.height
        var right = -1
        var bottom = -1
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (Color.alpha(bitmap.getPixel(x, y)) > 24) {
                    left = minOf(left, x)
                    top = minOf(top, y)
                    right = maxOf(right, x)
                    bottom = maxOf(bottom, y)
                }
            }
        }
        return if (right >= left && bottom >= top) Rect(left, top, right + 1, bottom + 1) else null
    }

    private fun buildForegroundMask(bitmap: Bitmap): BooleanArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val backgroundColor = estimateEdgeBackgroundColor(pixels, width, height)
        val background = BooleanArray(pixels.size)
        val queue = java.util.ArrayDeque<Int>()

        fun tryAdd(index: Int) {
            if (!background[index] && isBackgroundLike(pixels[index], backgroundColor)) {
                background[index] = true
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

        return BooleanArray(pixels.size) { index ->
            !background[index] && Color.alpha(pixels[index]) > 20
        }
    }

    private fun estimateEdgeBackgroundColor(pixels: IntArray, width: Int, height: Int): Int {
        val indices = intArrayOf(0, width - 1, (height - 1) * width, height * width - 1)
        var red = 0
        var green = 0
        var blue = 0
        var alpha = 0
        indices.forEach { index ->
            val color = pixels[index]
            red += Color.red(color)
            green += Color.green(color)
            blue += Color.blue(color)
            alpha += Color.alpha(color)
        }
        return Color.argb(alpha / indices.size, red / indices.size, green / indices.size, blue / indices.size)
    }

    private fun isBackgroundLike(color: Int, backgroundColor: Int): Boolean {
        if (Color.alpha(color) <= 20) return true
        val redDiff = Color.red(color) - Color.red(backgroundColor)
        val greenDiff = Color.green(color) - Color.green(backgroundColor)
        val blueDiff = Color.blue(color) - Color.blue(backgroundColor)
        val distanceSquared = redDiff * redDiff + greenDiff * greenDiff + blueDiff * blueDiff
        return distanceSquared < 46 * 46 * 3 || isNearWhite(color) && isNearWhite(backgroundColor)
    }

    private fun isNearWhite(color: Int): Boolean {
        return Color.red(color) > 232 && Color.green(color) > 232 && Color.blue(color) > 232
    }

    private fun enhanceStylizedColor(color: Int): Int {
        val red = enhanceChannel(Color.red(color))
        val green = enhanceChannel(Color.green(color))
        val blue = enhanceChannel(Color.blue(color))
        return Color.argb(Color.alpha(color), red, green, blue)
    }

    private fun enhanceChannel(value: Int): Int {
        val contrasted = ((value - 128) * 1.08f + 128).toInt()
        return (contrasted - 18).coerceIn(0, 245)
    }

    private fun isUsableStylizedPerson(bitmap: Bitmap): Boolean {
        var opaqueCount = 0
        var brightCount = 0
        var colorEnergy = 0L
        val totalPixels = bitmap.width * bitmap.height
        val step = maxOf(1, minOf(bitmap.width, bitmap.height) / 160)
        for (y in 0 until bitmap.height step step) {
            for (x in 0 until bitmap.width step step) {
                val color = bitmap.getPixel(x, y)
                if (Color.alpha(color) > 24) {
                    opaqueCount++
                    val maxChannel = maxOf(Color.red(color), Color.green(color), Color.blue(color))
                    val minChannel = minOf(Color.red(color), Color.green(color), Color.blue(color))
                    if (maxChannel > 238) brightCount++
                    colorEnergy += (maxChannel - minChannel)
                }
            }
        }
        if (opaqueCount == 0) return false
        val sampledPixels = ((bitmap.width + step - 1) / step) * ((bitmap.height + step - 1) / step)
        val opaqueRatio = opaqueCount.toFloat() / sampledPixels
        val brightRatio = brightCount.toFloat() / opaqueCount
        val averageColorEnergy = colorEnergy.toFloat() / opaqueCount
        return opaqueRatio in 0.04f..0.58f && brightRatio < 0.72f && averageColorEnergy > 12f && totalPixels > 0
    }
}
