package com.example.myapplication

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
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
            postProcessStylizedPerson(sourceBitmap, stylizedBitmap)
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

        paint.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), Color.rgb(255, 145, 198), Color.rgb(55, 195, 238), Shader.TileMode.CLAMP)
        canvas.drawCircle(width * 0.50f, height * 0.55f, width * 0.38f, paint)
        paint.shader = null

        paint.color = Color.rgb(255, 232, 224)
        canvas.drawCircle(width * 0.45f, height * 0.34f, width * 0.18f, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 8f
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.rgb(50, 45, 95)
        canvas.drawCircle(width * 0.45f, height * 0.34f, width * 0.18f, paint)
        canvas.drawLine(width * 0.25f, height * 0.50f, width * 0.06f, height * 0.42f, paint)
        canvas.drawLine(width * 0.67f, height * 0.50f, width * 0.90f, height * 0.58f, paint)
        paint.style = Paint.Style.FILL

        paint.color = Color.rgb(42, 55, 145)
        canvas.drawCircle(width * 0.38f, height * 0.31f, width * 0.10f, paint)
        canvas.drawCircle(width * 0.53f, height * 0.31f, width * 0.10f, paint)

        paint.color = Color.rgb(255, 122, 178)
        val dress = Path().apply {
            moveTo(width * 0.35f, height * 0.43f)
            lineTo(width * 0.61f, height * 0.43f)
            lineTo(width * 0.72f, height * 0.78f)
            cubicTo(width * 0.56f, height * 0.88f, width * 0.34f, height * 0.84f, width * 0.25f, height * 0.78f)
            close()
        }
        canvas.drawPath(dress, paint)

        paint.color = Color.rgb(38, 42, 92)
        paint.strokeWidth = 7f
        paint.style = Paint.Style.STROKE
        canvas.drawPath(dress, paint)
        paint.color = Color.rgb(80, 210, 238)
        canvas.drawLine(width * 0.36f, height * 0.49f, width * 0.63f, height * 0.49f, paint)
        paint.style = Paint.Style.FILL
        return bitmap
    }

    private fun postProcessStylizedPerson(sourceBitmap: Bitmap, stylizedBitmap: Bitmap): Bitmap {
        val width = stylizedBitmap.width
        val height = stylizedBitmap.height
        val sourceResized = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(sourceResized).drawBitmap(sourceBitmap, null, Rect(0, 0, width, height), null)
        val foregroundMask = buildForegroundMask(sourceResized)
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                if (foregroundMask[index]) {
                    result.setPixel(x, y, enhanceStylizedColor(stylizedBitmap.getPixel(x, y)))
                } else {
                    result.setPixel(x, y, Color.TRANSPARENT)
                }
            }
        }

        sourceResized.recycle()
        stylizedBitmap.recycle()
        return result
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
        val contrasted = ((value - 128) * 1.32f + 128).toInt()
        return (contrasted - 8).coerceIn(0, 255)
    }
}
