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
import org.tensorflow.lite.Tensor
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * True local pose-driven image generation adapter.
 *
 * Required model asset: assets/models/pose_driven_generator.tflite
 * Expected concept: reference image + target pose map (+ optional source pose/mask) -> generated image.
 * There is no Canvas/bitmap animation fallback here. If the model is absent or incompatible, generation fails.
 */
class LocalPoseDrivenGenerator(private val context: Context) {

    data class GeneratedFrame(val bitmap: Bitmap)

    private data class TensorSpec(
        val index: Int,
        val name: String,
        val width: Int,
        val height: Int,
        val channels: Int,
        val dataType: DataType,
        val byteSize: Int
    )

    private data class ModelSpec(
        val inputs: List<TensorSpec>,
        val output: TensorSpec,
        val referenceInput: TensorSpec,
        val targetPoseInput: TensorSpec,
        val sourcePoseInput: TensorSpec?,
        val maskInput: TensorSpec?
    )

    private var interpreter: Interpreter? = null
    private var modelSpec: ModelSpec? = null

    val isReady: Boolean
        get() = interpreter != null && modelSpec != null

    companion object {
        private const val TAG = "LocalPoseDrivenGenerator"
        private const val MODEL_PATH = "models/pose_driven_generator.tflite"
    }

    fun initialize(): Boolean {
        if (interpreter != null && modelSpec != null) return true
        return try {
            val options = Interpreter.Options().apply {
                setNumThreads(4)
                setUseNNAPI(true)
            }
            val modelBuffer = FileUtil.loadMappedFile(context, MODEL_PATH)
            val candidate = Interpreter(modelBuffer, options)
            val inspected = inspectModel(candidate)
            interpreter = candidate
            modelSpec = inspected
            Log.d(TAG, "已加载本地姿态驱动图像生成模型: $MODEL_PATH")
            true
        } catch (e: Exception) {
            Log.e(TAG, "未找到或无法加载真正的本地姿态驱动图像生成模型: $MODEL_PATH", e)
            release()
            false
        }
    }

    fun generate(
        referenceBitmap: Bitmap,
        targetPoseBitmap: Bitmap,
        sourcePoseBitmap: Bitmap?,
        sourceMaskBitmap: Bitmap?,
        outputWidth: Int,
        outputHeight: Int
    ): Bitmap? {
        val activeInterpreter = interpreter ?: return null
        val activeSpec = modelSpec ?: return null
        return try {
            val inputs = Array<Any>(activeSpec.inputs.size) { index ->
                val spec = activeSpec.inputs[index]
                val source = when (spec.index) {
                    activeSpec.referenceInput.index -> referenceBitmap
                    activeSpec.targetPoseInput.index -> targetPoseBitmap
                    activeSpec.sourcePoseInput?.index -> sourcePoseBitmap ?: targetPoseBitmap
                    activeSpec.maskInput?.index -> sourceMaskBitmap ?: createOpaqueMask(referenceBitmap)
                    else -> referenceBitmap
                }
                bitmapToTensor(source, spec)
            }
            val outputBuffer = ByteBuffer.allocateDirect(activeSpec.output.byteSize).order(ByteOrder.nativeOrder())
            val outputs = mutableMapOf<Int, Any>(0 to outputBuffer)
            activeInterpreter.runForMultipleInputsOutputs(inputs, outputs)
            outputBuffer.rewind()
            val generated = tensorToBitmap(outputBuffer, activeSpec.output)
            restoreOutputSize(generated, outputWidth, outputHeight)
        } catch (e: Exception) {
            Log.e(TAG, "本地姿态驱动图像生成推理失败", e)
            null
        }
    }

    fun release() {
        interpreter?.close()
        interpreter = null
        modelSpec = null
    }

    fun targetPoseSize(): Pair<Int, Int>? {
        val spec = modelSpec?.targetPoseInput ?: return null
        return spec.width to spec.height
    }

    private fun inspectModel(candidate: Interpreter): ModelSpec {
        val inputs = (0 until candidate.inputTensorCount).map { index ->
            tensorSpec(index, candidate.getInputTensor(index))
        }
        require(inputs.size >= 2) { "pose_driven_generator.tflite 至少需要 2 个输入: reference image + target pose" }
        val output = tensorSpec(0, candidate.getOutputTensor(0))
        require(output.channels in 3..4) { "输出必须是 RGB/RGBA 图像张量" }

        val reference = findInput(inputs, listOf("ref", "reference", "source", "image", "person")) ?: inputs[0]
        val targetPose = findInput(inputs.filter { it.index != reference.index }, listOf("target", "pose", "condition", "skeleton"))
            ?: inputs.first { it.index != reference.index }
        val sourcePose = findInput(
            inputs.filter { it.index != reference.index && it.index != targetPose.index },
            listOf("source_pose", "src_pose", "input_pose")
        )
        val mask = findInput(
            inputs.filter { it.index != reference.index && it.index != targetPose.index && it.index != sourcePose?.index },
            listOf("mask", "seg", "alpha")
        )
        return ModelSpec(
            inputs = inputs,
            output = output,
            referenceInput = reference,
            targetPoseInput = targetPose,
            sourcePoseInput = sourcePose,
            maskInput = mask
        )
    }

    private fun findInput(inputs: List<TensorSpec>, tokens: List<String>): TensorSpec? {
        return inputs.firstOrNull { spec ->
            val name = spec.name.lowercase()
            tokens.any { token -> name.contains(token) }
        }
    }

    private fun tensorSpec(index: Int, tensor: Tensor): TensorSpec {
        val shape = tensor.shape()
        require(shape.size == 4) { "只支持 NHWC 图像张量，当前 shape=${shape.joinToString()}" }
        val channels = shape[3]
        require(channels in 1..4) { "只支持 1/3/4 通道图像张量" }
        val height = shape[1].coerceAtLeast(1)
        val width = shape[2].coerceAtLeast(1)
        val dataType = tensor.dataType()
        return TensorSpec(
            index = index,
            name = tensor.name(),
            width = width,
            height = height,
            channels = channels,
            dataType = dataType,
            byteSize = width * height * channels * bytesPerElement(dataType)
        )
    }

    private fun bitmapToTensor(bitmap: Bitmap, spec: TensorSpec): ByteBuffer {
        val resized = Bitmap.createBitmap(spec.width, spec.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resized)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(bitmap, null, fitCenterRect(bitmap.width.toFloat(), bitmap.height.toFloat(), spec.width.toFloat(), spec.height.toFloat()), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        val buffer = ByteBuffer.allocateDirect(spec.byteSize).order(ByteOrder.nativeOrder())
        for (y in 0 until spec.height) {
            for (x in 0 until spec.width) {
                val color = resized.getPixel(x, y)
                if (spec.channels == 1) {
                    writeChannel(buffer, Color.alpha(color), spec.dataType)
                } else {
                    writeChannel(buffer, Color.red(color), spec.dataType)
                    writeChannel(buffer, Color.green(color), spec.dataType)
                    writeChannel(buffer, Color.blue(color), spec.dataType)
                    if (spec.channels == 4) writeChannel(buffer, Color.alpha(color), spec.dataType)
                }
            }
        }
        resized.recycle()
        buffer.rewind()
        return buffer
    }

    private fun tensorToBitmap(output: ByteBuffer, spec: TensorSpec): Bitmap {
        val bitmap = Bitmap.createBitmap(spec.width, spec.height, Bitmap.Config.ARGB_8888)
        when (spec.dataType) {
            DataType.FLOAT32 -> decodeFloatOutput(output, spec, bitmap)
            DataType.UINT8 -> decodeByteOutput(output, spec, bitmap, signed = false)
            DataType.INT8 -> decodeByteOutput(output, spec, bitmap, signed = true)
            else -> throw IllegalArgumentException("不支持输出类型: ${spec.dataType}")
        }
        return bitmap
    }

    private fun decodeFloatOutput(output: ByteBuffer, spec: TensorSpec, bitmap: Bitmap) {
        val values = FloatArray(spec.width * spec.height * spec.channels)
        for (index in values.indices) values[index] = output.getFloat()
        val minValue = values.minOrNull() ?: 0f
        val maxValue = values.maxOrNull() ?: 1f
        var cursor = 0
        for (y in 0 until spec.height) {
            for (x in 0 until spec.width) {
                val red = floatToChannel(values[cursor++], minValue, maxValue)
                val green = if (spec.channels > 1) floatToChannel(values[cursor++], minValue, maxValue) else red
                val blue = if (spec.channels > 2) floatToChannel(values[cursor++], minValue, maxValue) else red
                val alpha = if (spec.channels > 3) floatToChannel(values[cursor++], minValue, maxValue) else 255
                bitmap.setPixel(x, y, Color.argb(alpha, red, green, blue))
            }
        }
    }

    private fun decodeByteOutput(output: ByteBuffer, spec: TensorSpec, bitmap: Bitmap, signed: Boolean) {
        for (y in 0 until spec.height) {
            for (x in 0 until spec.width) {
                val red = byteToChannel(output.get(), signed)
                val green = if (spec.channels > 1) byteToChannel(output.get(), signed) else red
                val blue = if (spec.channels > 2) byteToChannel(output.get(), signed) else red
                val alpha = if (spec.channels > 3) byteToChannel(output.get(), signed) else 255
                bitmap.setPixel(x, y, Color.argb(alpha, red, green, blue))
            }
        }
    }

    private fun writeChannel(buffer: ByteBuffer, value: Int, dataType: DataType) {
        when (dataType) {
            DataType.FLOAT32 -> buffer.putFloat(value / 255f)
            DataType.UINT8 -> buffer.put(value.coerceIn(0, 255).toByte())
            DataType.INT8 -> buffer.put((value - 128).coerceIn(-128, 127).toByte())
            else -> throw IllegalArgumentException("不支持输入类型: $dataType")
        }
    }

    private fun floatToChannel(value: Float, minValue: Float, maxValue: Float): Int {
        val normalized = when {
            minValue < 0f -> (value + 1f) / 2f
            maxValue > 1.5f -> value / 255f
            else -> value
        }
        return (normalized.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)
    }

    private fun byteToChannel(value: Byte, signed: Boolean): Int {
        return if (signed) (value.toInt() + 128).coerceIn(0, 255) else (value.toInt() and 0xFF).coerceIn(0, 255)
    }

    private fun restoreOutputSize(bitmap: Bitmap, width: Int, height: Int): Bitmap {
        if (bitmap.width == width && bitmap.height == height) return bitmap
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val targetRect = fitCenterRect(
            bitmap.width.toFloat(),
            bitmap.height.toFloat(),
            width.toFloat(),
            height.toFloat()
        )
        Canvas(output).drawBitmap(
            bitmap,
            null,
            targetRect,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        )
        bitmap.recycle()
        return output
    }

    private fun createOpaqueMask(bitmap: Bitmap): Bitmap {
        return Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
    }

    private fun fitCenterRect(sourceWidth: Float, sourceHeight: Float, targetWidth: Float, targetHeight: Float): RectF {
        val scale = minOf(targetWidth / sourceWidth.coerceAtLeast(1f), targetHeight / sourceHeight.coerceAtLeast(1f))
        val width = sourceWidth * scale
        val height = sourceHeight * scale
        val left = (targetWidth - width) / 2f
        val top = (targetHeight - height) / 2f
        return RectF(left, top, left + width, top + height)
    }

    private fun bytesPerElement(dataType: DataType): Int {
        return when (dataType) {
            DataType.FLOAT32 -> 4
            DataType.UINT8, DataType.INT8 -> 1
            else -> throw IllegalArgumentException("不支持数据类型: $dataType")
        }
    }
}
