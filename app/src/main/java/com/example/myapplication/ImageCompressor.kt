package com.example.myapplication

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

object ImageCompressor {
    
    private const val MAX_WIDTH = 512
    private const val MAX_HEIGHT = 512
    private const val JPEG_QUALITY = 85
    
    fun compressImage(context: Context, uri: Uri): Bitmap? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeStream(inputStream, null, options)
            inputStream.close()
            
            val sampleSize = calculateInSampleSize(options, MAX_WIDTH, MAX_HEIGHT)
            
            val decodedStream = context.contentResolver.openInputStream(uri) ?: return null
            val decodedOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inJustDecodeBounds = false
            }
            
            var bitmap = BitmapFactory.decodeStream(decodedStream, null, decodedOptions)
            decodedStream.close()
            
            bitmap = bitmap?.let { fixOrientation(context, uri, it) }
            
            bitmap = bitmap?.let { resizeBitmap(it, MAX_WIDTH, MAX_HEIGHT) }
            
            bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    fun compressAndSave(context: Context, uri: Uri, outputFile: File): Boolean {
        return try {
            val compressedBitmap = compressImage(context, uri) ?: return false
            
            val outputStream = ByteArrayOutputStream()
            compressedBitmap.compress(Bitmap.CompressFormat.PNG, JPEG_QUALITY, outputStream)
            
            FileOutputStream(outputFile).use { fos ->
                fos.write(outputStream.toByteArray())
            }
            
            compressedBitmap.recycle()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            
            while (halfHeight / inSampleSize >= reqHeight && 
                   halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        
        return inSampleSize
    }
    
    private fun resizeBitmap(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        
        if (width <= maxWidth && height <= maxHeight) {
            return bitmap
        }
        
        val scale = minOf(
            maxWidth.toFloat() / width,
            maxHeight.toFloat() / height
        )
        
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()
        
        val resized = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        
        if (resized != bitmap) {
            bitmap.recycle()
        }
        
        return resized
    }
    
    private fun fixOrientation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return bitmap
            val exif = ExifInterface(inputStream)
            inputStream.close()
            
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                else -> return bitmap
            }
            
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) {
                bitmap.recycle()
            }
            rotated
        } catch (e: Exception) {
            e.printStackTrace()
            bitmap
        }
    }
}
