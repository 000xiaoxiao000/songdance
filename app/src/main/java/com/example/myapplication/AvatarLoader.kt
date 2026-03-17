package com.example.myapplication

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 小人图片加载与预处理入口。
 *
 * 主要职责：
 * - 按业务目录切换 `avatar` / `avatar1`
 * - 读取单帧序列、begin 图、end 图和分层部件图
 * - 对原图做去背景、裁掉透明边、统一输出尺寸
 * - 通过内存缓存避免重复解码与重复预处理
 *
 * 资源约定：
 * - 图片权威来源：`res/drawable/avatar`、`res/drawable/avatar1`
 * - 运行时主读取方式：通过构建阶段暴露出来的 `avatar/...`、`avatar1/...` 原始文件路径读取
 * - 兼容兜底方式：尝试读取扁平化的 drawable 名称，例如 `avatar_dancer_single_begin`
 */
object AvatarLoader {
    private const val TAG = "AvatarLoader"
    private const val TARGET_WIDTH = 500
    private const val TARGET_HEIGHT = 400

    data class LoadedSprite(
        val bitmap: Bitmap,
        val pivotX: Float = 0.5f,
        val pivotY: Float = 0.5f,
    )

    data class SingleSpriteSet(
        val frames: List<LoadedSprite>,
        val begin: LoadedSprite?,
        val end: LoadedSprite?,
    )

    private data class SpriteSetRequest(
        val dir: String,
        val maxFrames: Int,
    ) {
        val normalizedDir: String = dir.trim().trim('/')
        val cacheKey: String = if (normalizedDir.isEmpty()) {
            "sprite_set::$maxFrames"
        } else {
            "sprite_set::$normalizedDir#$maxFrames"
        }
    }

    private val preferredExtensions = listOf("png", "webp", "jpg", "jpeg")

    // 内存缓存，避免重复处理相同的资源文件
    private val processedCache = android.util.LruCache<String, Bitmap>(60)
    // 资源集缓存只保留最近几组热点结果，避免来回切换时反复遍历所有文件。
    private val spriteSetCache = android.util.LruCache<String, SingleSpriteSet>(3)
    private val spriteSetCacheLock = Any()
    private val spriteSetInFlight = mutableMapOf<String, CompletableDeferred<SingleSpriteSet>>()
    private val preloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class SpriteRequest(
        val dir: String,
        val baseName: String,
    ) {
        val normalizedDir: String = dir.trim().trim('/')
        val cacheKey: String = if (normalizedDir.isEmpty()) {
            "sprite::$baseName"
        } else {
            "sprite::$normalizedDir/$baseName"
        }
    }

    /**
     * 统一加载入口：
     * 1. 优先读取由 `res/drawable/avatar*` 暴露出来的原始文件路径；
     * 2. 再兼容尝试扁平化 drawable 资源名（如 `avatar_dancer_single_begin`）。
     *
     * 说明：业务上图片来源已经迁移到 `drawable/avatar`、`drawable/avatar1`，
     * 不再依赖旧的 `src/main/assets/avatar*` 目录；这里只是复用打包后的文件读取能力。
     */
    private fun loadAvatarBitmap(context: Context, dir: String, baseName: String): Bitmap? {
        val request = SpriteRequest(dir = dir, baseName = baseName)
        processedCache.get(request.cacheKey)?.takeIf { !it.isRecycled }?.let { return it }

        val decoded = decodeFromDrawableSubdir(context, request)
            ?: decodeFromDrawableResource(context, request)

        if (decoded == null) {
            android.util.Log.w(TAG, "未找到小人图片: dir=${request.normalizedDir}, baseName=${request.baseName}")
            return null
        }

        return try {
            prepareAvatarBitmap(decoded).also { processed ->
                processedCache.put(request.cacheKey, processed)
            }
        } catch (e: Exception) {
            if (!decoded.isRecycled) decoded.recycle()
            android.util.Log.e(TAG, "处理小人图片失败: dir=${request.normalizedDir}, baseName=${request.baseName}", e)
            null
        }
    }

    // 主路径：读取构建后可通过 avatar/...、avatar1/... 访问到的原始图片文件。
    private fun decodeFromDrawableSubdir(context: Context, request: SpriteRequest): Bitmap? {
        val assets = context.assets
        val basePath = if (request.normalizedDir.isEmpty()) {
            request.baseName
        } else {
            "${request.normalizedDir}/${request.baseName}"
        }

        for (ext in preferredExtensions) {
            val assetPath = "$basePath.$ext"
            val bitmap = decodeBitmapFromAssetPath(assets, assetPath)
            if (bitmap != null) {
                android.util.Log.d(TAG, "从 drawable 子目录加载成功: $assetPath")
                return bitmap
            }
        }

        return null
    }

    private fun decodeBitmapFromAssetPath(
        assetManager: android.content.res.AssetManager,
        assetPath: String,
    ): Bitmap? {
        return try {
            assetManager.open(assetPath).use { input ->
                BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inMutable = true
                })
            }
        } catch (_: Exception) {
            null
        }
    }

    // 兼容兜底：若未来资源被扁平化到 drawable 根目录，则继续支持按资源名查找。
    @Suppress("DiscouragedApi")
    private fun decodeFromDrawableResource(context: Context, request: SpriteRequest): Bitmap? {
        val resourceNames = buildList {
            if (request.normalizedDir.isNotEmpty()) {
                add("${request.normalizedDir}_${request.baseName}")
            }
            add(request.baseName)
        }

        for (resName in resourceNames) {
            val resId = context.resources.getIdentifier(resName, "drawable", context.packageName)
            if (resId == 0) continue

            val bitmap = BitmapFactory.decodeResource(context.resources, resId, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = true
            })
            if (bitmap != null) {
                android.util.Log.d(TAG, "从 drawable 资源名加载成功: $resName")
                return bitmap
            }
        }

        return null
    }

    /** 加载单人跳舞序列帧。 */
    fun loadSingleSpriteFrames(
        context: Context,
        preferredDir: String,
        maxFrames: Int = 18,
    ): List<LoadedSprite> {
        val result = mutableListOf<LoadedSprite>()

        for (i in 1..maxFrames) {
            val base = "dancer_single$i"

            val bmp = loadAvatarBitmap(context, preferredDir, base)

            if (bmp != null) {
                result.add(LoadedSprite(bmp, 0.5f, 0.5f))
            } else {
                if (i > 1) break // 如果中间断了则停止
            }
        }

        return result
    }

    private fun buildSingleSpriteSet(
        context: Context,
        preferredDir: String,
        maxFrames: Int = 18,
    ): SingleSpriteSet {
        val frames = loadSingleSpriteFrames(
            context = context,
            preferredDir = preferredDir,
            maxFrames = maxFrames,
        )
        val begin = loadNamedSingleSprite(
            context = context,
            preferredDir = preferredDir,
            baseName = "dancer_single_begin",
        )
        val end = loadNamedSingleSprite(
            context = context,
            preferredDir = preferredDir,
            baseName = "dancer_single_end",
        )
        return SingleSpriteSet(frames = frames, begin = begin, end = end)
    }

    fun getCachedSingleSpriteSet(
        preferredDir: String,
        maxFrames: Int,
    ): SingleSpriteSet? {
        val request = SpriteSetRequest(preferredDir, maxFrames)
        return synchronized(spriteSetCacheLock) {
            spriteSetCache.get(request.cacheKey)
        }
    }

    suspend fun loadOrAwaitSingleSpriteSet(
        context: Context,
        preferredDir: String,
        maxFrames: Int = 18,
    ): SingleSpriteSet {
        val appContext = context.applicationContext
        val request = SpriteSetRequest(preferredDir, maxFrames)

        synchronized(spriteSetCacheLock) {
            spriteSetCache.get(request.cacheKey)?.let { return it }
        }

        val deferredToAwait: CompletableDeferred<SingleSpriteSet>
        val shouldLoad = synchronized(spriteSetCacheLock) {
            spriteSetCache.get(request.cacheKey)?.let { return it }

            val running = spriteSetInFlight[request.cacheKey]
            if (running != null) {
                deferredToAwait = running
                false
            } else {
                CompletableDeferred<SingleSpriteSet>().also { created ->
                    spriteSetInFlight[request.cacheKey] = created
                    deferredToAwait = created
                }
                true
            }
        }

        if (!shouldLoad) {
            return deferredToAwait.await()
        }

        return try {
            val loaded = buildSingleSpriteSet(
                context = appContext,
                preferredDir = preferredDir,
                maxFrames = maxFrames,
            )
            synchronized(spriteSetCacheLock) {
                spriteSetCache.put(request.cacheKey, loaded)
                spriteSetInFlight.remove(request.cacheKey)
            }
            deferredToAwait.complete(loaded)
            loaded
        } catch (t: Throwable) {
            synchronized(spriteSetCacheLock) {
                spriteSetInFlight.remove(request.cacheKey)
            }
            deferredToAwait.completeExceptionally(t)
            throw t
        }
    }

    fun preloadSingleSpriteSet(
        context: Context,
        preferredDir: String,
        maxFrames: Int = 18,
    ) {
        val request = SpriteSetRequest(preferredDir, maxFrames)
        synchronized(spriteSetCacheLock) {
            if (spriteSetCache.get(request.cacheKey) != null || spriteSetInFlight.containsKey(request.cacheKey)) {
                return
            }
        }

        val appContext = context.applicationContext
        preloadScope.launch {
            runCatching {
                loadOrAwaitSingleSpriteSet(
                    context = appContext,
                    preferredDir = preferredDir,
                    maxFrames = maxFrames,
                )
            }.onSuccess { loaded ->
                android.util.Log.d(
                    TAG,
                    "小人资源预热完成: dir=${request.normalizedDir}, frames=${loaded.frames.size}, maxFrames=$maxFrames"
                )
            }.onFailure { error ->
                android.util.Log.w(
                    TAG,
                    "小人资源预热失败: dir=${request.normalizedDir}, maxFrames=$maxFrames",
                    error
                )
            }
        }
    }

    fun loadSingleSpriteSet(
        context: Context,
        preferredDir: String,
        maxFrames: Int = 18,
    ): SingleSpriteSet {
        getCachedSingleSpriteSet(preferredDir, maxFrames)?.let { return it }

        return buildSingleSpriteSet(
            context = context,
            preferredDir = preferredDir,
            maxFrames = maxFrames,
        ).also { spriteSet ->
            val request = SpriteSetRequest(preferredDir, maxFrames)
            synchronized(spriteSetCacheLock) {
                spriteSetCache.put(request.cacheKey, spriteSet)
            }
        }
    }


    fun loadNamedSingleSprite(
        context: Context,
        preferredDir: String,
        baseName: String,
    ): LoadedSprite? {
        val bmp = loadAvatarBitmap(context, preferredDir, baseName)
        if (bmp != null) return LoadedSprite(bmp, 0.5f, 0.5f)

        return null
    }

    /** 按名称加载分层小人部件，例如 `avatar_body`、`avatar_head`。 */
    fun loadLayeredSprites(
        context: Context,
        keys: List<String>,
        avatarDir: String,
    ): Map<String, LoadedSprite> {
        val out = mutableMapOf<String, LoadedSprite>()
        for (key in keys) {
            val bmp = loadAvatarBitmap(context, avatarDir, key) ?: continue
            out[key] = LoadedSprite(bmp, 0.5f, 0.5f)
        }

        return out
    }

    // ----- 预处理辅助方法 -----
    private fun prepareAvatarBitmap(bitmap: Bitmap): Bitmap {
        var src = bitmap
        // 如果是宽图序列，尝试裁剪第一格（避免误用图集）
        if (src.width > src.height * 2) {
            val oneWidth = src.width / 5
            src = Bitmap.createBitmap(src, 0, 0, oneWidth, src.height)
        }

        val transparent = autoRemoveBackground(src)
        val cropped = cropBitmapTransparency(transparent)

        // 统一输出尺寸: 500x400 (宽x高)
        val result = Bitmap.createBitmap(TARGET_WIDTH, TARGET_HEIGHT, Bitmap.Config.ARGB_8888).apply {
            setHasAlpha(true)
            setPremultiplied(true)
        }
        val canvas = Canvas(result)
        canvas.drawColor(Color.TRANSPARENT)

        val scale = minOf(TARGET_WIDTH.toFloat() / cropped.width, TARGET_HEIGHT.toFloat() / cropped.height)
        val drawWidth = (cropped.width * scale).toInt()
        val drawHeight = (cropped.height * scale).toInt()
        val left = (TARGET_WIDTH - drawWidth) / 2
        val top = TARGET_HEIGHT - drawHeight // 底部对齐
        val rect = android.graphics.Rect(left, top, left + drawWidth, top + drawHeight)
        canvas.drawBitmap(cropped, null, rect, null)

        // 输出结果已经生成，及时释放本次处理链上所有中间位图，避免累计占用内存。
        recycleBitmaps(bitmap, src, transparent, cropped)

        return result
    }

    private fun recycleBitmaps(vararg bitmaps: Bitmap?) {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Bitmap, Boolean>())
        bitmaps.forEach { bitmap ->
            if (bitmap != null && seen.add(bitmap) && !bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
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
            if (y > 0) checkAndPush(pixels, visited, queue, idx - width, bgColor, tolerance)
            if (y < height - 1) checkAndPush(pixels, visited, queue, idx + width, bgColor, tolerance)
            if (x > 0) checkAndPush(pixels, visited, queue, idx - 1, bgColor, tolerance)
            if (x < width - 1) checkAndPush(pixels, visited, queue, idx + 1, bgColor, tolerance)
        }

        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).apply {
            setHasAlpha(true)
            setPremultiplied(true)
        }
    }

    private fun checkAndPush(
        pixels: IntArray,
        visited: BooleanArray,
        queue: java.util.ArrayDeque<Int>,
        idx: Int,
        bgColor: Int,
        tolerance: Int,
    ) {
        if (visited[idx]) return
        visited[idx] = true
        val color = pixels[idx]
        val alpha = (color ushr 24) and 0xFF
        if (alpha == 0 || isSimilarColor(color, bgColor, tolerance)) {
            queue.add(idx)
            return
        }
    }

    private fun isSimilarColor(c1: Int, c2: Int, tolerance: Int = 20): Boolean {
        val r = kotlin.math.abs(((c1 shr 16) and 0xFF) - ((c2 shr 16) and 0xFF))
        val g = kotlin.math.abs(((c1 shr 8) and 0xFF) - ((c2 shr 8) and 0xFF))
        val b = kotlin.math.abs((c1 and 0xFF) - (c2 and 0xFF))
        return r <= tolerance && g <= tolerance && b <= tolerance
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
