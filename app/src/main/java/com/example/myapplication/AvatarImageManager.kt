package com.example.myapplication

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * 管理用户上传的小人图片
 * 负责保存、加载和管理用户自定义的小人图片序列
 */
object AvatarImageManager {
    private const val AVATAR_DIR_NAME = "custom_avatars"
    private const val AVATAR_SET_1 = "set1"
    private const val AVATAR_SET_2 = "set2"
    
    fun getAvatarDirectory(context: Context, setName: String): File {
        val dir = File(context.filesDir, "$AVATAR_DIR_NAME/$setName")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }
    
    fun saveAvatarImage(
        context: Context,
        uri: Uri,
        setName: String,
        imageName: String
    ): Boolean {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return false
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            
            val dir = getAvatarDirectory(context, setName)
            val file = File(dir, "$imageName.png")
            
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            
            bitmap.recycle()
            true
        } catch (e: Exception) {
            android.util.Log.e("AvatarImageManager", "保存图片失败", e)
            false
        }
    }
    
    fun loadAvatarImage(context: Context, setName: String, imageName: String): Bitmap? {
        return try {
            val dir = getAvatarDirectory(context, setName)
            val file = File(dir, "$imageName.png")
            if (!file.exists()) return null
            
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: Exception) {
            android.util.Log.e("AvatarImageManager", "加载图片失败", e)
            null
        }
    }
    
    fun hasCustomAvatars(context: Context, setName: String): Boolean {
        val dir = getAvatarDirectory(context, setName)
        return dir.exists() && dir.listFiles()?.isNotEmpty() == true
    }
    
    fun deleteAvatarSet(context: Context, setName: String): Boolean {
        return try {
            val dir = getAvatarDirectory(context, setName)
            dir.deleteRecursively()
            true
        } catch (e: Exception) {
            android.util.Log.e("AvatarImageManager", "删除图片集失败", e)
            false
        }
    }
    
    fun getAvailableImageNames(context: Context, setName: String): List<String> {
        val dir = getAvatarDirectory(context, setName)
        return dir.listFiles()
            ?.filter { it.extension == "png" }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            ?: emptyList()
    }
}
