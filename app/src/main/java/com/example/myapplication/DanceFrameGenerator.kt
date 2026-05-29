package com.example.myapplication

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 舞蹈帧生成器
 * 负责将单张静态图片生成为唱跳动作的帧序列
 */
class DanceFrameGenerator(
    private val context: Context,
    private val aiModel: AIModelManager
) {
    
    private val settingsRepo = OverlaySettingsRepository(context)
    
    companion object {
        private const val TAG = "DanceFrameGenerator"
    }
    
    /**
     * 生成并保存唱跳动作帧序列
     * @param sourceUri 源图片 URI
     * @param setName 目标图片集名称
     * @param frameCount 生成的帧数（如果为null则从设置中读取）
     * @param danceStyle 舞蹈风格
     * @param progressCallback 进度回调 (当前帧, 总帧数)
     * @return 成功生成的帧数
     */
    suspend fun generateAndSave(
        sourceUri: Uri,
        setName: String,
        frameCount: Int? = null,
        danceStyle: DanceStyle = DanceStyle.POWER,
        progressCallback: suspend (Int, Int) -> Unit
    ): Result<Int> = withContext(Dispatchers.IO) {
        val actualFrameCount = frameCount ?: settingsRepo.get().aiFrameCount
        try {
            Log.d(TAG, "开始生成唱跳动作帧序列...")
            
            // 1. 按上传图片相同规则加载，确保 AI 帧与普通上传图片尺寸一致
            progressCallback(0, actualFrameCount)
            val sourceBitmap = loadGenerationBitmap(sourceUri)
            
            if (sourceBitmap == null) {
                return@withContext Result.failure(
                    Exception("无法加载图片，请检查图片格式")
                )
            }
            
            Log.d(TAG, "图片加载成功（与上传尺寸规则一致）: ${sourceBitmap.width}x${sourceBitmap.height}")
            
            // 2. 初始化 AI 模型
            if (!aiModel.initialize()) {
                sourceBitmap.recycle()
                return@withContext Result.failure(
                    Exception("AI 模型初始化失败")
                )
            }
            
            // 3. 生成帧序列
            Log.d(TAG, "开始生成 $actualFrameCount 帧动作...")
            val frames = aiModel.generateDanceFrames(
                inputBitmap = sourceBitmap,
                frameCount = actualFrameCount,
                danceStyle = danceStyle
            )
            
            sourceBitmap.recycle()
            
            if (frames.isEmpty()) {
                return@withContext Result.failure(
                    Exception("生成帧序列失败")
                )
            }
            
            // 4. 保存到图片集
            val existingImages = AvatarImageManager
                .getAvailableImageNames(context, setName)
            var nextIndex = getNextFrameIndex(existingImages)
            
            var savedCount = 0
            frames.forEachIndexed { index, frame ->
                val imageName = "dancer_single$nextIndex"
                
                if (saveBitmapToSet(frame, setName, imageName)) {
                    savedCount++
                    nextIndex++
                }
                
                frame.recycle()
                progressCallback(index + 1, actualFrameCount)
            }
            
            Log.d(TAG, "成功保存 $savedCount 帧到图片集 $setName")
            Result.success(savedCount)
            
        } catch (e: Exception) {
            Log.e(TAG, "生成帧序列失败", e)
            Result.failure(e)
        }
    }
    
    /**
     * 使用普通上传相同的压缩/旋转处理链加载图片。
     * 这样 AI 生成帧与手动上传的同一张图片会得到一致的宽高。
     */
    private fun loadGenerationBitmap(uri: Uri): Bitmap? {
        return try {
            val bitmap = ImageCompressor.compressImage(context, uri) ?: return null
            if (bitmap.config == Bitmap.Config.ARGB_8888) {
                bitmap
            } else {
                val converted = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                bitmap.recycle()
                converted
            }
        } catch (e: Exception) {
            Log.e(TAG, "加载图片失败", e)
            null
        }
    }
    
    /**
     * 获取下一个帧索引
     */
    private fun getNextFrameIndex(existingImages: List<String>): Int {
        val indices = existingImages.mapNotNull { name ->
            val match = Regex("dancer_single(\\d+)").find(name)
            match?.groupValues?.get(1)?.toIntOrNull()
        }
        
        return (indices.maxOrNull() ?: 0) + 1
    }
    
    /**
     * 保存 Bitmap 到图片集
     */
    private fun saveBitmapToSet(
        bitmap: Bitmap,
        setName: String,
        imageName: String
    ): Boolean {
        return try {
            val dir = AvatarImageManager.getAvatarDirectory(context, setName)
            val file = File(dir, "$imageName.png")
            
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            
            Log.d(TAG, "保存帧: $imageName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "保存帧失败: $imageName", e)
            false
        }
    }
}
