package com.example.myapplication

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 本地 image-to-image 动漫化 TFLite 适配器。
 *
 * 当前优先加载 AnimeGANv2 Hayao 风格模型，输入为上传图片，输出为同尺寸透明背景的动漫化人物图。
 * 不包含 Canvas 程序化角色 fallback；没有可用本地图像模型时返回 null。
 */
class LocalImageToImageModel(private val context: Context) {

    private enum class ModelProfile {
        ANIME_GAN_V2,
        GENERIC_IMAGE_TO_IMAGE
    }

    private data class ModelSpec(
        val path: String,
        val inputHeight: Int,
        val inputWidth: Int,
        val inputChannels: Int,
        val inputType: DataType,
        val outputHeight: Int,
        val outputWidth: Int,
        val outputChannels: Int,
        val outputType: DataType,
        val profile: ModelProfile
    )

    private data class ModelInput(
        val buffer: ByteBuffer,
        val contentRect: RectF
    )

    private var interpreter: Interpreter? = null
    private var spec: ModelSpec? = null

    val isReady: Boolean
        get() = interpreter != null && spec != null

    companion object {
        private const val TAG = "LocalImageToImageModel"
        private val MODEL_CANDIDATES = listOf(
            "models/anime_style_transfer.tflite",
            "models/animeganv2_hayao_256x256.tflite",
            "models/avatar_image_generator.tflite",
            "models/avatar_style_transfer.tflite"
        )
    }

    fun stylize(sourceBitmap: Bitmap): Bitmap? {
        val activeInterpreter = getOrCreateInterpreter() ?: return null
        val activeSpec = spec ?: return null
        return try {
            val input = buildInputBuffer(sourceBitmap, activeSpec)
            val output = ByteBuffer.allocateDirect(outputByteSize(activeSpec)).order(ByteOrder.nativeOrder())
            activeInterpreter.run(input.buffer, output)
            output.rewind()
            val modelOutput = decodeOutputBuffer(output, activeSpec)
            postProcessToSourceCanvas(
                sourceBitmap = sourceBitmap,
                modelOutput = modelOutput,
                contentRect = input.contentRect,
                modelSpec = activeSpec
            )
        } catch (e: Exception) {
            Log.e(TAG, "本地 image-to-image 动漫化推理失败", e)
            null
        }
    }

    fun release() {
        interpreter?.close()
        interpreter = null
        spec = null
    }

    private fun getOrCreateInterpreter(): Interpreter? {
        if (interpreter != null) return interpreter
        MODEL_CANDIDATES.forEach { modelPath ->
            try {
                val options = Interpreter.Options().apply {
                    setNumThreads(4)
                    setUseNNAPI(true)
                }
                val modelBuffer = FileUtil.loadMappedFile(context, modelPath)
                val candidate = Interpreter(modelBuffer, options)
                val candidateSpec = inspectModel(modelPath, candidate)
                if (candidateSpec != null) {
                    interpreter = candidate
                    spec = candidateSpec
                    Log.d(TAG, "已加载本地 image-to-image 动漫化模型: $modelPath")
                    return candidate
                }
                candidate.close()
            } catch (e: Exception) {
                Log.w(TAG, "跳过不可用 image-to-image 模型: $modelPath", e)
            }
        }
        Log.e(TAG, "未找到可用本地 image-to-image 动漫化 TFLite 模型")
        return null
    }

    private fun inspectModel(modelPath: String, candidate: Interpreter): ModelSpec? {
        return try {
            val inputTensor = candidate.getInputTensor(0)
            val outputTensor = candidate.getOutputTensor(0)
            val inputShape = inputTensor.shape()
            val outputShape = outputTensor.shape()
            if (inputShape.size != 4 || outputShape.size != 4) return null
            val inputChannels = inputShape[3]
            val outputChannels = outputShape[3]
            if (inputChannels !in 3..4 || outputChannels !in 3..4) return null
            val outputName = outputTensor.name().lowercase()
            val profile = if (modelPath.contains("anime", ignoreCase = true) || outputName.contains("tanh")) {
                ModelProfile.ANIME_GAN_V2
            } else {
                ModelProfile.GENERIC_IMAGE_TO_IMAGE
            }
            ModelSpec(
                path = modelPath,
                inputHeight = inputShape[1].coerceAtLeast(1),
                inputWidth = inputShape[2].coerceAtLeast(1),
                inputChannels = inputChannels,
                inputType = inputTensor.dataType(),
                outputHeight = outputShape[1].coerceAtLeast(1),
                outputWidth = outputShape[2].coerceAtLeast(1),
                outputChannels = outputChannels,
                outputType = outputTensor.dataType(),
                profile = profile
            )
        } catch (e: Exception) {
            Log.w(TAG, "模型结构不兼容: $modelPath", e)
            null
        }
    }

    private fun buildInputBuffer(sourceBitmap: Bitmap, modelSpec: ModelSpec): ModelInput {
        val resized = Bitmap.createBitmap(modelSpec.inputWidth, modelSpec.inputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resized)
        canvas.drawColor(Color.WHITE)
        val contentRect = fitCenterRect(
            sourceWidth = sourceBitmap.width.toFloat(),
            sourceHeight = sourceBitmap.height.toFloat(),
            targetWidth = modelSpec.inputWidth.toFloat(),
            targetHeight = modelSpec.inputHeight.toFloat()
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        canvas.drawBitmap(sourceBitmap, null, contentRect, paint)

        val buffer = ByteBuffer.allocateDirect(inputByteSize(modelSpec)).order(ByteOrder.nativeOrder())
        for (y in 0 until modelSpec.inputHeight) {
            for (x in 0 until modelSpec.inputWidth) {
                val color = resized.getPixel(x, y)
                writeInputChannel(buffer, Color.red(color), modelSpec.inputType, modelSpec.profile)
                writeInputChannel(buffer, Color.green(color), modelSpec.inputType, modelSpec.profile)
                writeInputChannel(buffer, Color.blue(color), modelSpec.inputType, modelSpec.profile)
                if (modelSpec.inputChannels == 4) {
                    writeInputChannel(buffer, Color.alpha(color), modelSpec.inputType, modelSpec.profile)
                }
            }
        }
        resized.recycle()
        buffer.rewind()
        return ModelInput(buffer = buffer, contentRect = contentRect)
    }

    private fun writeInputChannel(buffer: ByteBuffer, value: Int, dataType: DataType, profile: ModelProfile) {
        when (dataType) {
            DataType.FLOAT32 -> {
                val normalized = when (profile) {
                    ModelProfile.ANIME_GAN_V2 -> value / 255f
                    ModelProfile.GENERIC_IMAGE_TO_IMAGE -> value / 255f
                }
                buffer.putFloat(normalized)
            }
            DataType.UINT8 -> buffer.put(value.coerceIn(0, 255).toByte())
            DataType.INT8 -> buffer.put((value - 128).coerceIn(-128, 127).toByte())
            else -> throw IllegalArgumentException("不支持的输入类型: $dataType")
        }
    }

    private fun decodeOutputBuffer(output: ByteBuffer, modelSpec: ModelSpec): Bitmap {
        val bitmap = Bitmap.createBitmap(modelSpec.outputWidth, modelSpec.outputHeight, Bitmap.Config.ARGB_8888)
        when (modelSpec.outputType) {
            DataType.FLOAT32 -> decodeFloatOutput(output, modelSpec, bitmap)
            DataType.UINT8 -> decodeByteOutput(output, modelSpec, bitmap, signed = false)
            DataType.INT8 -> decodeByteOutput(output, modelSpec, bitmap, signed = true)
            else -> throw IllegalArgumentException("不支持的输出类型: ${modelSpec.outputType}")
        }
        return bitmap
    }

    private fun decodeFloatOutput(output: ByteBuffer, modelSpec: ModelSpec, bitmap: Bitmap) {
        val values = FloatArray(modelSpec.outputWidth * modelSpec.outputHeight * modelSpec.outputChannels)
        for (index in values.indices) values[index] = output.getFloat()
        val minValue = values.minOrNull() ?: 0f
        val maxValue = values.maxOrNull() ?: 1f
        var cursor = 0
        for (y in 0 until modelSpec.outputHeight) {
            for (x in 0 until modelSpec.outputWidth) {
                val red = floatToColorChannel(values[cursor++], minValue, maxValue, modelSpec.profile)
                val green = floatToColorChannel(values[cursor++], minValue, maxValue, modelSpec.profile)
                val blue = floatToColorChannel(values[cursor++], minValue, maxValue, modelSpec.profile)
                val alpha = if (modelSpec.outputChannels == 4) {
                    floatToColorChannel(values[cursor++], minValue, maxValue, modelSpec.profile)
                } else {
                    255
                }
                bitmap.setPixel(x, y, Color.argb(alpha, red, green, blue))
            }
        }
    }

    private fun decodeByteOutput(output: ByteBuffer, modelSpec: ModelSpec, bitmap: Bitmap, signed: Boolean) {
        for (y in 0 until modelSpec.outputHeight) {
            for (x in 0 until modelSpec.outputWidth) {
                val red = byteToColorChannel(output.get(), signed)
                val green = byteToColorChannel(output.get(), signed)
                val blue = byteToColorChannel(output.get(), signed)
                val alpha = if (modelSpec.outputChannels == 4) byteToColorChannel(output.get(), signed) else 255
                bitmap.setPixel(x, y, Color.argb(alpha, red, green, blue))
            }
        }
    }

    private fun postProcessToSourceCanvas(
        sourceBitmap: Bitmap,
        modelOutput: Bitmap,
        contentRect: RectF,
        modelSpec: ModelSpec
    ): Bitmap {
        val restored = restoreAspectToSourceSize(
            modelOutput = modelOutput,
            contentRect = contentRect,
            inputWidth = modelSpec.inputWidth,
            inputHeight = modelSpec.inputHeight,
            sourceWidth = sourceBitmap.width,
            sourceHeight = sourceBitmap.height
        )
        val foregroundMask = buildForegroundMask(sourceBitmap)
        val result = Bitmap.createBitmap(sourceBitmap.width, sourceBitmap.height, Bitmap.Config.ARGB_8888)
        val outputPixels = IntArray(restored.width * restored.height)
        restored.getPixels(outputPixels, 0, restored.width, 0, 0, restored.width, restored.height)
        for (index in outputPixels.indices) {
            outputPixels[index] = if (foregroundMask[index]) {
                val x = index % sourceBitmap.width
                val y = index / sourceBitmap.width
                val sourceColor = sourceBitmap.getPixel(x, y)
                val sourceAlpha = Color.alpha(sourceColor)
                val alpha = if (sourceAlpha < 255) sourceAlpha else 255
                blendAnimeWithSourceColor(outputPixels[index], sourceColor, alpha)
            } else {
                Color.TRANSPARENT
            }
        }
        result.setPixels(outputPixels, 0, sourceBitmap.width, 0, 0, sourceBitmap.width, sourceBitmap.height)
        val danceReady = normalizeForDanceCanvas(result)
        modelOutput.recycle()
        restored.recycle()
        return danceReady
    }


    private fun blendAnimeWithSourceColor(animeColor: Int, sourceColor: Int, alpha: Int): Int {
        val animeWeight = 0.38f
        val sourceWeight = 1f - animeWeight
        val red = boostColor(Color.red(sourceColor) * sourceWeight + Color.red(animeColor) * animeWeight)
        val green = boostColor(Color.green(sourceColor) * sourceWeight + Color.green(animeColor) * animeWeight)
        val blue = boostColor(Color.blue(sourceColor) * sourceWeight + Color.blue(animeColor) * animeWeight)
        return Color.argb(alpha, red, green, blue)
    }

    private fun boostColor(value: Float): Int {
        val contrasted = (value - 128f) * 1.08f + 128f
        return contrasted.toInt().coerceIn(0, 255)
    }

    private fun normalizeForDanceCanvas(personBitmap: Bitmap): Bitmap {
        val bounds = findOpaqueBounds(personBitmap) ?: return personBitmap
        val output = Bitmap.createBitmap(personBitmap.width, personBitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.TRANSPARENT)
        val maxWidth = personBitmap.width * 0.68f
        val maxHeight = personBitmap.height * 0.74f
        val scale = minOf(maxWidth / bounds.width().coerceAtLeast(1), maxHeight / bounds.height().coerceAtLeast(1), 1f)
        val drawWidth = bounds.width() * scale
        val drawHeight = bounds.height() * scale
        val dst = RectF(
            (personBitmap.width - drawWidth) / 2f,
            personBitmap.height * 0.52f - drawHeight / 2f,
            (personBitmap.width + drawWidth) / 2f,
            personBitmap.height * 0.52f + drawHeight / 2f
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        canvas.drawBitmap(personBitmap, bounds, dst, paint)
        personBitmap.recycle()
        return output
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

    private fun restoreAspectToSourceSize(
        modelOutput: Bitmap,
        contentRect: RectF,
        inputWidth: Int,
        inputHeight: Int,
        sourceWidth: Int,
        sourceHeight: Int
    ): Bitmap {
        val output = Bitmap.createBitmap(sourceWidth, sourceHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.TRANSPARENT)
        val scaleX = modelOutput.width.toFloat() / inputWidth.coerceAtLeast(1)
        val scaleY = modelOutput.height.toFloat() / inputHeight.coerceAtLeast(1)
        val src = Rect(
            (contentRect.left * scaleX).toInt().coerceIn(0, modelOutput.width - 1),
            (contentRect.top * scaleY).toInt().coerceIn(0, modelOutput.height - 1),
            (contentRect.right * scaleX).toInt().coerceIn(1, modelOutput.width),
            (contentRect.bottom * scaleY).toInt().coerceIn(1, modelOutput.height)
        )
        val dst = Rect(0, 0, sourceWidth, sourceHeight)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        canvas.drawBitmap(modelOutput, src, dst, paint)
        return output
    }

    private fun fitCenterRect(sourceWidth: Float, sourceHeight: Float, targetWidth: Float, targetHeight: Float): RectF {
        val scale = minOf(targetWidth / sourceWidth.coerceAtLeast(1f), targetHeight / sourceHeight.coerceAtLeast(1f))
        val width = sourceWidth * scale
        val height = sourceHeight * scale
        val left = (targetWidth - width) / 2f
        val top = (targetHeight - height) / 2f
        return RectF(left, top, left + width, top + height)
    }

    private fun buildForegroundMask(bitmap: Bitmap): BooleanArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        if (pixels.any { Color.alpha(it) < 245 }) {
            return BooleanArray(pixels.size) { index -> Color.alpha(pixels[index]) > 20 }
        }

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
        val samples = ArrayList<Int>()
        val xStep = maxOf(1, width / 16)
        val yStep = maxOf(1, height / 16)
        for (x in 0 until width step xStep) {
            samples.add(pixels[x])
            samples.add(pixels[(height - 1) * width + x])
        }
        for (y in 0 until height step yStep) {
            samples.add(pixels[y * width])
            samples.add(pixels[y * width + width - 1])
        }
        var red = 0
        var green = 0
        var blue = 0
        var alpha = 0
        samples.forEach { color ->
            red += Color.red(color)
            green += Color.green(color)
            blue += Color.blue(color)
            alpha += Color.alpha(color)
        }
        val count = samples.size.coerceAtLeast(1)
        return Color.argb(alpha / count, red / count, green / count, blue / count)
    }

    private fun isBackgroundLike(color: Int, backgroundColor: Int): Boolean {
        if (Color.alpha(color) <= 20) return true
        val redDiff = Color.red(color) - Color.red(backgroundColor)
        val greenDiff = Color.green(color) - Color.green(backgroundColor)
        val blueDiff = Color.blue(color) - Color.blue(backgroundColor)
        val distanceSquared = redDiff * redDiff + greenDiff * greenDiff + blueDiff * blueDiff
        return distanceSquared < 52 * 52 * 3 || isNearWhite(color) && isNearWhite(backgroundColor)
    }

    private fun isNearWhite(color: Int): Boolean {
        return Color.red(color) > 232 && Color.green(color) > 232 && Color.blue(color) > 232
    }

    private fun floatToColorChannel(value: Float, minValue: Float, maxValue: Float, profile: ModelProfile): Int {
        val normalized = when (profile) {
            ModelProfile.ANIME_GAN_V2 -> value.coerceIn(0f, 1f)
            ModelProfile.GENERIC_IMAGE_TO_IMAGE -> when {
                minValue < 0f -> (value + 1f) / 2f
                maxValue > 1.5f -> value / 255f
                else -> value
            }
        }
        return (normalized.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)
    }

    private fun byteToColorChannel(value: Byte, signed: Boolean): Int {
        return if (signed) {
            (value.toInt() + 128).coerceIn(0, 255)
        } else {
            (value.toInt() and 0xFF).coerceIn(0, 255)
        }
    }

    private fun inputByteSize(modelSpec: ModelSpec): Int {
        return modelSpec.inputWidth * modelSpec.inputHeight * modelSpec.inputChannels * bytesPerElement(modelSpec.inputType)
    }

    private fun outputByteSize(modelSpec: ModelSpec): Int {
        return modelSpec.outputWidth * modelSpec.outputHeight * modelSpec.outputChannels * bytesPerElement(modelSpec.outputType)
    }

    private fun bytesPerElement(dataType: DataType): Int {
        return when (dataType) {
            DataType.FLOAT32 -> 4
            DataType.UINT8, DataType.INT8 -> 1
            else -> throw IllegalArgumentException("不支持的数据类型: $dataType")
        }
    }
}
