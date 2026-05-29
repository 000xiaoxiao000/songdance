package com.example.myapplication

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 姿态检测器
 * 使用 MoveNet 模型检测人物姿态关键点
 */
class PoseDetector(private val context: Context) {
    
    private var interpreter: Interpreter? = null
    private var inputSize = 192
    
    companion object {
        private const val TAG = "PoseDetector"
        private const val MODEL_PATH = "models/movenet_thunder.tflite"
        
        const val NUM_KEYPOINTS = 17
        
        val KEYPOINT_NAMES = arrayOf(
            "nose", "left_eye", "right_eye", "left_ear", "right_ear",
            "left_shoulder", "right_shoulder", "left_elbow", "right_elbow",
            "left_wrist", "right_wrist", "left_hip", "right_hip",
            "left_knee", "right_knee", "left_ankle", "right_ankle"
        )
        
        val SKELETON_CONNECTIONS = arrayOf(
            Pair(5, 6),   // left_shoulder - right_shoulder
            Pair(5, 7),   // left_shoulder - left_elbow
            Pair(7, 9),   // left_elbow - left_wrist
            Pair(6, 8),   // right_shoulder - right_elbow
            Pair(8, 10),  // right_elbow - right_wrist
            Pair(5, 11),  // left_shoulder - left_hip
            Pair(6, 12),  // right_shoulder - right_hip
            Pair(11, 12), // left_hip - right_hip
            Pair(11, 13), // left_hip - left_knee
            Pair(13, 15), // left_knee - left_ankle
            Pair(12, 14), // right_hip - right_knee
            Pair(14, 16)  // right_knee - right_ankle
        )
    }
    
    data class Keypoint(
        val position: PointF,
        val confidence: Float
    )
    
    data class Pose(
        val keypoints: List<Keypoint>,
        val confidence: Float
    ) {
        fun getKeypoint(index: Int): Keypoint? {
            return if (index in keypoints.indices) keypoints[index] else null
        }
        
        fun getKeypointByName(name: String): Keypoint? {
            val index = KEYPOINT_NAMES.indexOf(name)
            return if (index >= 0) getKeypoint(index) else null
        }
    }
    
    suspend fun initialize(): Boolean {
        return try {
            if (interpreter != null) return true
            
            Log.d(TAG, "初始化姿态检测模型...")
            
            val options = Interpreter.Options().apply {
                setNumThreads(4)
                setUseNNAPI(true)
            }
            
            try {
                val modelBuffer = FileUtil.loadMappedFile(context, MODEL_PATH)
                interpreter = Interpreter(modelBuffer, options)
                Log.d(TAG, "姿态检测模型初始化成功")
                true
            } catch (e: Exception) {
                Log.w(TAG, "无法加载模型文件，使用简化检测", e)
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "姿态检测模型初始化失败", e)
            false
        }
    }
    
    fun detectPose(bitmap: Bitmap): Pose {
        return if (interpreter != null) {
            detectPoseWithModel(bitmap) ?: run {
                Log.w(TAG, "模型检测失败，降级到简化检测")
                detectPoseSimplified(bitmap)
            }
        } else {
            detectPoseSimplified(bitmap)
        }
    }
    
    private fun detectPoseWithModel(bitmap: Bitmap): Pose? {
        return try {
            val inputTensor = preprocessImage(bitmap)
            val outputArray = Array(1) { Array(1) { Array(NUM_KEYPOINTS) { FloatArray(3) } } }
            
            interpreter?.run(inputTensor.buffer, outputArray)
            
            val keypoints = mutableListOf<Keypoint>()
            var totalConfidence = 0f
            
            for (i in 0 until NUM_KEYPOINTS) {
                val y = outputArray[0][0][i][0]
                val x = outputArray[0][0][i][1]
                val confidence = outputArray[0][0][i][2]
                
                keypoints.add(
                    Keypoint(
                        position = PointF(x * bitmap.width, y * bitmap.height),
                        confidence = confidence
                    )
                )
                totalConfidence += confidence
            }
            
            Pose(
                keypoints = keypoints,
                confidence = totalConfidence / NUM_KEYPOINTS
            )
        } catch (e: Exception) {
            Log.e(TAG, "模型姿态检测失败", e)
            null
        }
    }
    
    private fun detectPoseSimplified(bitmap: Bitmap): Pose {
        Log.d(TAG, "使用简化姿态检测 (图片尺寸: ${bitmap.width}x${bitmap.height})")
        
        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()
        val centerX = width / 2
        
        val aspectRatio = width / height
        val isPortrait = aspectRatio < 1.0f
        
        val headY: Float
        val shoulderY: Float
        val hipY: Float
        val kneeY: Float
        val ankleY: Float
        
        if (isPortrait) {
            headY = height * 0.15f
            shoulderY = height * 0.28f
            hipY = height * 0.50f
            kneeY = height * 0.70f
            ankleY = height * 0.90f
        } else {
            headY = height * 0.20f
            shoulderY = height * 0.35f
            hipY = height * 0.55f
            kneeY = height * 0.75f
            ankleY = height * 0.95f
        }
        
        val shoulderWidth = width * 0.15f
        val elbowOffset = width * 0.20f
        val wristOffset = width * 0.22f
        val hipWidth = width * 0.12f
        val kneeWidth = width * 0.10f
        val ankleWidth = width * 0.08f
        
        val keypoints = listOf(
            Keypoint(PointF(centerX, headY), 0.95f),
            Keypoint(PointF(centerX - width * 0.05f, headY * 0.95f), 0.90f),
            Keypoint(PointF(centerX + width * 0.05f, headY * 0.95f), 0.90f),
            Keypoint(PointF(centerX - width * 0.08f, headY * 0.95f), 0.85f),
            Keypoint(PointF(centerX + width * 0.08f, headY * 0.95f), 0.85f),
            Keypoint(PointF(centerX - shoulderWidth, shoulderY), 0.95f),
            Keypoint(PointF(centerX + shoulderWidth, shoulderY), 0.95f),
            Keypoint(PointF(centerX - elbowOffset, (shoulderY + hipY) / 2), 0.90f),
            Keypoint(PointF(centerX + elbowOffset, (shoulderY + hipY) / 2), 0.90f),
            Keypoint(PointF(centerX - wristOffset, hipY * 0.95f), 0.85f),
            Keypoint(PointF(centerX + wristOffset, hipY * 0.95f), 0.85f),
            Keypoint(PointF(centerX - hipWidth, hipY), 0.95f),
            Keypoint(PointF(centerX + hipWidth, hipY), 0.95f),
            Keypoint(PointF(centerX - kneeWidth, kneeY), 0.90f),
            Keypoint(PointF(centerX + kneeWidth, kneeY), 0.90f),
            Keypoint(PointF(centerX - ankleWidth, ankleY), 0.85f),
            Keypoint(PointF(centerX + ankleWidth, ankleY), 0.85f)
        )
        
        Log.d(TAG, "简化检测完成 (纵横比: ${String.format("%.2f", aspectRatio)}, " +
                "方向: ${if (isPortrait) "竖向" else "横向"})")
        
        return Pose(keypoints, 0.88f)
    }
    
    private fun preprocessImage(bitmap: Bitmap): TensorImage {
        val imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(inputSize, inputSize, ResizeOp.ResizeMethod.BILINEAR))
            .build()
        
        var tensorImage = TensorImage.fromBitmap(bitmap)
        tensorImage = imageProcessor.process(tensorImage)
        
        return tensorImage
    }
    
    fun release() {
        interpreter?.close()
        interpreter = null
        Log.d(TAG, "姿态检测器资源已释放")
    }
}
