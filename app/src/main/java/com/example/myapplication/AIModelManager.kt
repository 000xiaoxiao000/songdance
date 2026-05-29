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
    private val keypointAnimator = KeypointAnimator()
    private val imageWarper = ImageWarper()
    private var isInitialized = false
    
    companion object {
        private const val TAG = "AIModelManager"
    }
    
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (isInitialized) return@withContext true
            
            Log.d(TAG, "初始化 AI 模型...")
            
            val poseInitialized = poseDetector.initialize()
            
            isInitialized = true
            Log.d(TAG, "AI 模型初始化成功 (姿态检测: ${if (poseInitialized) "真实模型" else "简化模式"})")
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
        
        Log.d(TAG, "开始生成 $frameCount 帧唱跳动作 (风格: $danceStyle)...")
        
        val detectedPose = poseDetector.detectPose(inputBitmap)
        
        Log.d(TAG, "姿态检测成功 (置信度: ${String.format("%.2f", detectedPose.confidence)})")
        
        val animatedSequence = keypointAnimator.generateDanceSequence(
            basePose = detectedPose,
            frameCount = frameCount,
            danceStyle = danceStyle
        )
        
        Log.d(TAG, "动作序列生成完成，开始渲染帧...")
        
        val frames = mutableListOf<Bitmap>()
        animatedSequence.forEachIndexed { index, animatedPose ->
            try {
                val frame = imageWarper.createFrameWithPose(
                    sourceBitmap = inputBitmap,
                    animatedKeypoints = animatedPose.keypoints,
                    originalPose = detectedPose
                )
                frames.add(frame)
                
                if ((index + 1) % 10 == 0) {
                    Log.d(TAG, "已渲染 ${index + 1}/$frameCount 帧")
                }
            } catch (e: Exception) {
                Log.e(TAG, "渲染第 ${index + 1} 帧失败", e)
            }
        }
        
        Log.d(TAG, "成功生成 ${frames.size} 帧 (无混合处理，保持原图清晰度)")
        frames
    }
    
    /**
     * 释放资源
     */
    fun release() {
        poseDetector.release()
        isInitialized = false
        Log.d(TAG, "AI 模型资源已释放")
    }
}
