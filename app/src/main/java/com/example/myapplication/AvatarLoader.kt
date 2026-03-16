package com.example.myapplication

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF

/**
 * AvatarLoader
 *
 * 推荐将与 avatar 图片相关的加载/预处理逻辑集中在这里：
 * - 支持 assets/{avatar,avatar1} 两个目录优先级切换
 * - 提供按序号加载的单帧序列（dancer_singleN.*）
 * - 提供按名称加载的分层部件（avatar_body/avatar_head 等）
 * - 预处理：可选的自动去背景、裁剪透明像素、统一输出尺寸（缓存结果以避免重复开销）
 *
 * 设计要点：
 * - 读取时一次性做昂贵操作（去背/裁切/缩放），在内存允许时缓存 Bitmap
 * - 如果图片本身带 alpha，尽量保留不做去背景
 * - 对于过渡混合（避免重影），建议在视图层面使用离屏合成或共享网格，本类只负责提供干净的位图
 */
object AvatarLoader {
    data class LoadedSprite(
        val bitmap: Bitmap,
        val pivotX: Float = 0.5f,
        val pivotY: Float = 0.5f,
    )

    private val preferredExtensions = listOf("png", "webp", "jpg", "jpeg")

    /**
     * Load single-sprite animation frames from assets directories.
     * Searches in order: preferredDir, otherDir, root assets. Returns at most maxFrames frames.
     */
    fun loadSingleSpriteFrames(
        context: Context,
        preferredDir: String,
        otherDir: String,
        maxFrames: Int = 18,
    ): List<LoadedSprite> {
        val assets = context.assets
        val result = mutableListOf<LoadedSprite>()

        for (i in 1..maxFrames) {
            val base = "dancer_single$i"
            val candidates = buildList {
                for (ext in preferredExtensions) add("$preferredDir/$base.$ext")
                for (ext in preferredExtensions) add("$otherDir/$base.$ext")
                for (ext in preferredExtensions) add("$base.$ext")
            }

            var loaded: LoadedSprite? = null
            for (path in candidates) {
                loaded = tryLoadFromAssets(assets, path)?.let { LoadedSprite(it, 0.5f, 0.5f) }
                if (loaded != null) break
            }
            if (loaded != null) result.add(loaded) else break
        }

        return result
    }

    /**
     * Load layered sprite parts by name -> attempts to find drawable names under assets.
     * keys expects names like "avatar_body", "avatar_head" etc. If prefersVariant is true,
     * tries avatar1-prefixed names first (avatar1_body) then avatar_body then root.
     */
    fun loadLayeredSprites(
        context: Context,
        keys: List<String>,
        prefersVariant: Boolean,
        avatarDir: String,
        avatarVariantDir: String,
    ): Map<String, LoadedSprite> {
        val assets = context.assets
        val out = mutableMapOf<String, LoadedSprite>()
        for (key in keys) {
            val candidateNames = if (prefersVariant && key.startsWith("avatar")) {
                val alt = key.replaceFirst("avatar", "avatar1")
                listOf(alt, key)
            } else listOf(key)

            var found: LoadedSprite? = null
            // Try asset directories first with extensions
            val dirs = buildList {
                // prefer chosen folders
                if (prefersVariant) add(avatarVariantDir) else add(avatarDir)
                if (prefersVariant) add(avatarDir) else add(avatarVariantDir)
                add("")
            }

            run loop@{
                for (name in candidateNames) {
                    for (dir in dirs) {
                        for (ext in preferredExtensions) {
                            val path = if (dir.isEmpty()) "$name.$ext" else "$dir/$name.$ext"
                            val bmp = tryLoadFromAssets(assets, path)
                            if (bmp != null) {
                                found = LoadedSprite(bmp, 0.5f, 0.5f)
                                return@loop
                            }
                        }
                    }
                }
            }

            if (found != null) out[key] = found
        }

        return out
    }

    private fun tryLoadFromAssets(assetManager: android.content.res.AssetManager, path: String): Bitmap? {
        return try {
            assetManager.open(path).use { stream ->
                BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inMutable = true
                })?.let { prepareAvatarBitmap(it) }
            }
        } catch (e: Exception) {
            null
        }
    }

    // ----- Preprocessing helpers (copied/adapted behavior) -----
    private fun prepareAvatarBitmap(bitmap: Bitmap): Bitmap {
        var src = bitmap
        // If image is an extremely wide sprite sheet, try to crop a single column to avoid accidental sheets
        if (src.width > src.height * 2) {
            val oneWidth = src.width / 5
            src = Bitmap.createBitmap(src, 0, 0, oneWidth, src.height)
        }

        val transparent = autoRemoveBackground(src)
        val cropped = cropBitmapTransparency(transparent)

        val targetWidth = 500
        val targetHeight = 400
        val result = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888).apply {
            setHasAlpha(true)
            setPremultiplied(true)
        }
        val canvas = Canvas(result)
        canvas.drawColor(Color.TRANSPARENT)

        val scale = minOf(targetWidth.toFloat() / cropped.width, targetHeight.toFloat() / cropped.height)
        val drawWidth = (cropped.width * scale).toInt()
        val drawHeight = (cropped.height * scale).toInt()
        val left = (targetWidth - drawWidth) / 2
        val top = targetHeight - drawHeight // bottom align
        val rect = android.graphics.Rect(left, top, left + drawWidth, top + drawHeight)
        canvas.drawBitmap(cropped, null, rect, null)
        return result
    }

    private fun autoRemoveBackground(source: Bitmap): Bitmap {
        if (source.hasAlpha()) return source

        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        val bgColor = pixels[0]
        val queue = java.util.ArrayDeque<Int>()
        val visited = BooleanArray(width * height)

        val corners = listOf(0, width - 1, (height - 1) * width, (height - 1) * width + width - 1)
        var seedsFound = false
        for (seed in corners) {
            if (isSimilarColor(pixels[seed], bgColor)) {
                queue.add(seed)
                visited[seed] = true
                seedsFound = true
            }
        }

        if (!seedsFound) return source

        val tolerance = 20
        while (queue.isNotEmpty()) {
            val idx = queue.removeFirst()
            pixels[idx] = 0
            val x = idx % width
            val y = idx / width

            // neighbors
            if (y > 0) checkAndPush(pixels, visited, queue, idx - width, width, tolerance)
            if (y < height - 1) checkAndPush(pixels, visited, queue, idx + width, width, tolerance)
            if (x > 0) checkAndPush(pixels, visited, queue, idx - 1, width, tolerance)
            if (x < width - 1) checkAndPush(pixels, visited, queue, idx + 1, width, tolerance)
        }

        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).apply {
            setHasAlpha(true)
            setPremultiplied(true)
        }
    }

    private fun checkAndPush(pixels: IntArray, visited: BooleanArray, queue: java.util.ArrayDeque<Int>, idx: Int, stride: Int, tolerance: Int) {
        if (visited[idx]) return
        visited[idx] = true
        // compare to top-left original bg sample is not available here; rely on approximate check by alpha
        val color = pixels[idx]
        if (((color ushr 24) and 0xFF) >= 250) {
            // nearly opaque -> not background
            return
        }
        // If RGB very similar to pixel[0] this will have been added earlier; keep conservative approach
        queue.add(idx)
    }

    private fun isSimilarColor(c1: Int, c2: Int): Boolean {
        val r = kotlin.math.abs(((c1 shr 16) and 0xFF) - ((c2 shr 16) and 0xFF))
        val g = kotlin.math.abs(((c1 shr 8) and 0xFF) - ((c2 shr 8) and 0xFF))
        val b = kotlin.math.abs((c1 and 0xFF) - (c2 and 0xFF))
        return r <= 20 && g <= 20 && b <= 20
    }

    private fun cropBitmapTransparency(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1

        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = pixels[y * width + x]
                val alpha = (pixel ushr 24) and 0xFF
                if (alpha > 0) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        if (maxX < minX || maxY < minY) return source

        val pad = 2
        val outMinX = (minX - pad).coerceAtLeast(0)
        val outMinY = (minY - pad).coerceAtLeast(0)
        val outMaxX = (maxX + pad).coerceAtMost(width - 1)
        val outMaxY = (maxY + pad).coerceAtMost(height - 1)

        val w = (outMaxX - outMinX) + 1
        val h = (outMaxY - outMinY) + 1
        return Bitmap.createBitmap(source, outMinX, outMinY, w, h).apply {
            setHasAlpha(true)
            setPremultiplied(true)
        }
    }
}

