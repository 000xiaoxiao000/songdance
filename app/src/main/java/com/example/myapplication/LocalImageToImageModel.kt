package com.example.myapplication

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 通用本地图像生成/风格迁移 TFLite 适配器。
 * 支持单输入、单输出的 image-to-image 模型：输入/输出形状为 [1, H, W, 3/4]。
 */
class LocalImageToImageModel(private val context: Context) {

    private data class ModelSpec(
        val path: String,
        val inputHeight: Int,
        val inputWidth: Int,
        val inputChannels: Int,
        val inputType: DataType,
        val outputHeight: Int,
        val outputWidth: Int,
        val outputChannels: Int,
        val outputType: DataType
    )

    private var interpreter: Interpreter? = null
    private var spec: ModelSpec? = null

    val isReady: Boolean
        get() = interpreter != null && spec != null

    companion object {
        private const val TAG = "LocalImageToImageModel"
        private val MODEL_CANDIDATES = listOf(
            "models/avatar_image_generator.tflite",
            "models/avatar_style_transfer.tflite",
            "models/anime_style_transfer.tflite"
        )
    }

    fun stylize(sourceBitmap: Bitmap): Bitmap? {
        val activeInterpreter = getOrCreateInterpreter() ?: return null
        val activeSpec = spec ?: return null
        return try {
            val input = buildInputBuffer(sourceBitmap, activeSpec)
            val output = ByteBuffer.allocateDirect(outputByteSize(activeSpec)).order(ByteOrder.nativeOrder())
            activeInterpreter.run(input, output)
            output.rewind()
            decodeOutputBuffer(output, activeSpec)
        } catch (e: Exception) {
            Log.e(TAG, "本地图像生成/风格迁移推理失败", e)
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
                    Log.d(TAG, "已加载本地图像生成/风格迁移模型: $modelPath")
                    return candidate
                }
                candidate.close()
            } catch (_: Exception) {
                // 模型文件不存在或格式不匹配时尝试下一个候选。
            }
        }
        Log.w(TAG, "未找到可用本地图像生成/风格迁移模型，使用程序化 avatar 生成")
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
            ModelSpec(
                path = modelPath,
                inputHeight = inputShape[1].coerceAtLeast(1),
                inputWidth = inputShape[2].coerceAtLeast(1),
                inputChannels = inputChannels,
                inputType = inputTensor.dataType(),
                outputHeight = outputShape[1].coerceAtLeast(1),
                outputWidth = outputShape[2].coerceAtLeast(1),
                outputChannels = outputChannels,
                outputType = outputTensor.dataType()
            )
        } catch (e: Exception) {
            Log.w(TAG, "模型结构不兼容: $modelPath", e)
            null
        }
    }

    private fun buildInputBuffer(sourceBitmap: Bitmap, modelSpec: ModelSpec): ByteBuffer {
        val resized = Bitmap.createBitmap(modelSpec.inputWidth, modelSpec.inputHeight, Bitmap.Config.ARGB_8888)
        Canvas(resized).drawBitmap(
            sourceBitmap,
            null,
            android.graphics.Rect(0, 0, modelSpec.inputWidth, modelSpec.inputHeight),
            null
        )
        val buffer = ByteBuffer.allocateDirect(inputByteSize(modelSpec)).order(ByteOrder.nativeOrder())
        for (y in 0 until modelSpec.inputHeight) {
            for (x in 0 until modelSpec.inputWidth) {
                val color = resized.getPixel(x, y)
                writeInputChannel(buffer, Color.red(color), modelSpec.inputType)
                writeInputChannel(buffer, Color.green(color), modelSpec.inputType)
                writeInputChannel(buffer, Color.blue(color), modelSpec.inputType)
                if (modelSpec.inputChannels == 4) {
                    writeInputChannel(buffer, Color.alpha(color), modelSpec.inputType)
                }
            }
        }
        resized.recycle()
        buffer.rewind()
        return buffer
    }

    private fun writeInputChannel(buffer: ByteBuffer, value: Int, dataType: DataType) {
        when (dataType) {
            DataType.FLOAT32 -> buffer.putFloat(value / 255f)
            DataType.UINT8 -> buffer.put(value.toByte())
            DataType.INT8 -> buffer.put((value - 128).toByte())
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
        for (index in values.indices) values[index] = output.float
        val minValue = values.minOrNull() ?: 0f
        val maxValue = values.maxOrNull() ?: 1f
        var cursor = 0
        for (y in 0 until modelSpec.outputHeight) {
            for (x in 0 until modelSpec.outputWidth) {
                val red = floatToColorChannel(values[cursor++], minValue, maxValue)
                val green = floatToColorChannel(values[cursor++], minValue, maxValue)
                val blue = floatToColorChannel(values[cursor++], minValue, maxValue)
                val alpha = if (modelSpec.outputChannels == 4) {
                    floatToColorChannel(values[cursor++], minValue, maxValue)
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

    private fun floatToColorChannel(value: Float, minValue: Float, maxValue: Float): Int {
        val normalized = when {
            minValue < 0f -> (value + 1f) / 2f
            maxValue > 1.5f -> value / 255f
            else -> value
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
