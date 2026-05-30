package com.example.myapplication

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AI 模型管理器
 * 整合姿态检测、关键点动画和图像变形，生成唱跳动作帧序列
 */
class AIModelManager(private val context: Context) {
    
    private val poseDetector = PoseDetector(context)
    private val avatarStyleFrameRenderer = AvatarStyleFrameRenderer(context)
    private var isInitialized = false
    val isUsingLocalModel: Boolean
        get() = poseDetector.isUsingLocalModel
    
    companion object {
        private const val TAG = "AIModelManager"
    }
    
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (isInitialized) return@withContext true
            
            Log.d(TAG, "初始化 AI 模型...")
            
            val poseInitialized = poseDetector.initialize()
            
            isInitialized = true
            Log.d(TAG, "AI 模型初始化成功 (姿态检测: ${if (poseInitialized) "本地 MoveNet 模型" else "简化模式"})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "AI 模型初始化失败", e)
            false
        }
    }
    
    /**
     * 生成唱跳动作帧序列
     * @param inputBitmap 输入的静态图片
     * @param frameCount 生成的帧数（必需参数，根据音乐时长和帧率计算）
     * @param danceStyle 舞蹈风格
     * @return 生成的帧序列
     */
    suspend fun generateDanceFrames(
        inputBitmap: Bitmap,
        frameCount: Int,
        danceStyle: DanceStyle = DanceStyle.POWER
    ): List<Bitmap> = withContext(Dispatchers.IO) {
        if (!isInitialized) {
            throw IllegalStateException("AI 模型未初始化")
        }
        
        Log.d(TAG, "开始使用${if (isUsingLocalModel) "本地模型" else "简化模型"}生成 $frameCount 帧唱跳动作 (风格: $danceStyle)...")
        
        val detectedPose = poseDetector.detectPose(inputBitmap)
        
        Log.d(TAG, "姿态检测成功 (置信度: ${String.format("%.2f", detectedPose.confidence)})")

        val avatarStyleFrames = avatarStyleFrameRenderer.generateFrames(
            sourceBitmap = inputBitmap,
            detectedPose = detectedPose,
            frameCount = frameCount
        )
        if (avatarStyleFrames.isEmpty()) {
            throw IllegalStateException("缺少真正的本地姿态驱动图像生成模型 app/src/main/assets/models/pose_driven_generator.tflite，无法逐帧重绘唱跳动作")
        }
        Log.d(TAG, "成功使用本地姿态驱动生成模型逐帧重绘 ${avatarStyleFrames.size} 帧唱跳动作")
        return@withContext avatarStyleFrames
    }
    
    /**
     * 释放资源
     */
    fun release() {
        poseDetector.release()
        avatarStyleFrameRenderer.release()
        isInitialized = false
        Log.d(TAG, "AI 模型资源已释放")
    }
}
