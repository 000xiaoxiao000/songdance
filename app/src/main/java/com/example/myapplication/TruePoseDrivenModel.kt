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
 * Optional adapter for a real local pose-driven generation model.
 *
 * Put a compatible model at assets/models/pose_driven_generator.tflite.
 * If the file is absent, this adapter stays disabled and adds no asset size.
 */
class TruePoseDrivenModel(private val context: Context) {

    private data class TensorSpec(
        val inputIndex: Int,
        val name: String,
        val width: Int,
        val height: Int,
        val channels: Int,
        val dataType: DataType,
        val byteSize: Int
    )

    private var interpreter: Interpreter? = null
    private var inputs: List<TensorSpec> = emptyList()
    private var output: TensorSpec? = null

    val isReady: Boolean
        get() = interpreter != null && output != null

    companion object {
        private const val TAG = "TruePoseDrivenModel"
        private const val MODEL_PATH = "models/pose_driven_generator.tflite"
    }

    fun initializeIfAvailable(): Boolean {
        if (isReady) return true
        return try {
            val modelBuffer = FileUtil.loadMappedFile(context, MODEL_PATH)
            val candidate = Interpreter(modelBuffer, Interpreter.Options().apply { setNumThreads(4) })
            inputs = (0 until candidate.inputTensorCount).map { index -> spec(index, candidate.getInputTensor(index)) }
            output = spec(0, candidate.getOutputTensor(0))
            require(inputs.size >= 2) { "pose-driven model requires at least reference + target pose inputs" }
            require(output?.channels in 3..4) { "pose-driven model output must be RGB/RGBA" }
            interpreter = candidate
            Log.d(TAG, "Loaded real pose-driven generation model: $MODEL_PATH")
            true
        } catch (e: Exception) {
            release()
            Log.i(TAG, "No real pose-driven generation model bundled; using lightweight retargeting")
            false
        }
    }

    fun targetPoseSize(): Pair<Int, Int>? {
        val poseInput = findInput(listOf("target", "pose", "condition", "skeleton")) ?: inputs.getOrNull(1) ?: return null
        return poseInput.width to poseInput.height
    }

    fun generate(
        referenceBitmap: Bitmap,
        sourcePoseBitmap: Bitmap,
        targetPoseBitmap: Bitmap,
        outputWidth: Int,
        outputHeight: Int
    ): Bitmap? {
        val activeInterpreter = interpreter ?: return null
        val activeOutput = output ?: return null
        return try {
            val referenceInput = findInput(listOf("ref", "reference", "source", "image", "person")) ?: inputs[0]
            val targetPoseInput = findInput(listOf("target", "pose", "condition", "skeleton")) ?: inputs.getOrElse(1) { referenceInput }
            val sourcePoseInput = findInput(listOf("source_pose", "src_pose", "input_pose"))
            val inputBuffers = Array<Any>(inputs.size) { position ->
                val input = inputs[position]
                val bitmap = when (input.inputIndex) {
                    referenceInput.inputIndex -> referenceBitmap
                    targetPoseInput.inputIndex -> targetPoseBitmap
                    sourcePoseInput?.inputIndex -> sourcePoseBitmap
                    else -> targetPoseBitmap
                }
                bitmapToBuffer(bitmap, input)
            }
            val outputBuffer = ByteBuffer.allocateDirect(activeOutput.byteSize).order(ByteOrder.nativeOrder())
            activeInterpreter.runForMultipleInputsOutputs(inputBuffers, mutableMapOf<Int, Any>(0 to outputBuffer))
            outputBuffer.rewind()
            resizeOutput(bufferToBitmap(outputBuffer, activeOutput), outputWidth, outputHeight)
        } catch (e: Exception) {
            Log.e(TAG, "Real pose-driven model inference failed", e)
            null
        }
    }

    fun release() {
        interpreter?.close()
        interpreter = null
        inputs = emptyList()
        output = null
    }

    private fun findInput(tokens: List<String>): TensorSpec? {
        return inputs.firstOrNull { spec -> tokens.any { token -> spec.name.lowercase().contains(token) } }
    }

    private fun spec(inputIndex: Int, tensor: Tensor): TensorSpec {
        val shape = tensor.shape()
        require(shape.size == 4) { "Only NHWC image tensors are supported" }
        val dataType = tensor.dataType()
        val channels = shape[3]
        require(channels in 1..4) { "Only 1/3/4-channel tensors are supported" }
        val height = shape[1].coerceAtLeast(1)
        val width = shape[2].coerceAtLeast(1)
        return TensorSpec(inputIndex, tensor.name(), width, height, channels, dataType, width * height * channels * bytesPerElement(dataType))
    }

    private fun bitmapToBuffer(bitmap: Bitmap, spec: TensorSpec): ByteBuffer {
        val resized = Bitmap.createBitmap(spec.width, spec.height, Bitmap.Config.ARGB_8888)
        Canvas(resized).drawBitmap(bitmap, null, fitCenterRect(bitmap.width.toFloat(), bitmap.height.toFloat(), spec.width.toFloat(), spec.height.toFloat()), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
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

    private fun bufferToBitmap(buffer: ByteBuffer, spec: TensorSpec): Bitmap {
        val bitmap = Bitmap.createBitmap(spec.width, spec.height, Bitmap.Config.ARGB_8888)
        for (y in 0 until spec.height) {
            for (x in 0 until spec.width) {
                val red = readChannel(buffer, spec.dataType)
                val green = if (spec.channels > 1) readChannel(buffer, spec.dataType) else red
                val blue = if (spec.channels > 2) readChannel(buffer, spec.dataType) else red
                val alpha = if (spec.channels > 3) readChannel(buffer, spec.dataType) else 255
                bitmap.setPixel(x, y, Color.argb(alpha, red, green, blue))
            }
        }
        return bitmap
    }

    private fun resizeOutput(bitmap: Bitmap, width: Int, height: Int): Bitmap {
        if (bitmap.width == width && bitmap.height == height) return bitmap
        val outputBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(outputBitmap).drawBitmap(bitmap, null, Rect(0, 0, width, height), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG))
        bitmap.recycle()
        return outputBitmap
    }

    private fun writeChannel(buffer: ByteBuffer, value: Int, dataType: DataType) {
        when (dataType) {
            DataType.FLOAT32 -> buffer.putFloat(value / 255f)
            DataType.UINT8 -> buffer.put(value.coerceIn(0, 255).toByte())
            DataType.INT8 -> buffer.put((value - 128).coerceIn(-128, 127).toByte())
            else -> throw IllegalArgumentException("Unsupported tensor data type: $dataType")
        }
    }

    private fun readChannel(buffer: ByteBuffer, dataType: DataType): Int {
        val value = when (dataType) {
            DataType.FLOAT32 -> buffer.getFloat()
            DataType.UINT8 -> (buffer.get().toInt() and 0xFF) / 255f
            DataType.INT8 -> (buffer.get().toInt() + 128).coerceIn(0, 255) / 255f
            else -> throw IllegalArgumentException("Unsupported tensor data type: $dataType")
        }
        return (value.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)
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
            else -> throw IllegalArgumentException("Unsupported tensor data type: $dataType")
        }
    }
}
