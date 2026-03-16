package com.example.myapplication

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 更完整的 OpenGL ES 2.0 渲染器：支持网格(meshed)变形、顶点动画与纹理管理（带 LRU 限制）。
 * 注：为性能使用 VBO（顶点缓冲）与索引缓冲，顶点更新通过 glBufferSubData 完成。
 */
class OpenGLESRenderer() : android.opengl.GLSurfaceView.Renderer {
    // 简单通用着色器：接受位置和纹理坐标
    private val vsSource = """
        attribute vec2 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = vec4(aPosition, 0.0, 1.0);
            vTexCoord = aTexCoord;
        }
    """.trimIndent()

    private val fsSource = """
        precision mediump float;
        varying vec2 vTexCoord;
        uniform sampler2D uTexture;
        uniform float uAlpha;
        void main() {
            vec4 c = texture2D(uTexture, vTexCoord);
            gl_FragColor = vec4(c.rgb, c.a * uAlpha);
        }
    """.trimIndent()

    private var program = 0
    private var aPositionLoc = 0
    private var aTexCoordLoc = 0
    private var uTextureLoc = 0
    private var uAlphaLoc = 0

    // 每个纹理对应的 GL 资源与网格数据
    private data class MeshData(
        var texId: Int = 0,
        var vboPos: Int = 0,
        var vboTex: Int = 0,
        var ibo: Int = 0,
        var indexCount: Int = 0,
        var meshW: Int = 0,
        var meshH: Int = 0,
        var basePos: FloatArray? = null,
    )

    private val meshes = mutableMapOf<String, MeshData>()

    // LRU 管理纹理键（最常保留最近 N 个纹理），减少内存占用
    private val textureLru = ArrayDeque<String>()
    private val maxTextures = 12

    // 节拍触发脉冲，用于顶点动画（key -> pulse strength）
    private val pulseMap = mutableMapOf<String, Float>()

    // 临时 buffers（复用以减少分配）
    private var tmpFloatBuffer: FloatBuffer? = null

    // 渲染参数
    private var viewW = 0
    private var viewH = 0
    private var alpha = 1f
    private var targetSizePx: Int = 0

    override fun onSurfaceCreated(gl: javax.microedition.khronos.opengles.GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        val vert = loadShader(GLES20.GL_VERTEX_SHADER, vsSource)
        val frag = loadShader(GLES20.GL_FRAGMENT_SHADER, fsSource)
        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vert)
            GLES20.glAttachShader(it, frag)
            GLES20.glLinkProgram(it)
        }

        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTextureLoc = GLES20.glGetUniformLocation(program, "uTexture")
        uAlphaLoc = GLES20.glGetUniformLocation(program, "uAlpha")
    }

    override fun onSurfaceChanged(gl: javax.microedition.khronos.opengles.GL10?, width: Int, height: Int) {
        viewW = width
        viewH = height
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        GLES20.glUseProgram(program)
        GLES20.glUniform1f(uAlphaLoc, alpha)

        // 渲染所有已注册 mesh（按 LRU 顺序小到大，以保持稳定）
        val now = System.currentTimeMillis() / 1000f
        for (key in textureLru) {
            val mesh = meshes[key] ?: continue
            if (mesh.texId == 0 || mesh.vboPos == 0 || mesh.vboTex == 0 || mesh.ibo == 0) continue
            // 先根据当前脉冲和时间计算顶点位移并更新 VBO（如果存在 basePos）
            val base = mesh.basePos
            val pulse = pulseMap.getOrDefault(key, 0f)
            if (base != null && pulse > 0.001f) {
                // 计算变形顶点（NDC 空间），对每个 vertex 应用基于 u/v 的正弦偏移
                val vCount = base.size
                val displaced = tmpFloatBuffer ?: ByteBuffer.allocateDirect(vCount * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().also { tmpFloatBuffer = it }
                displaced.rewind()
                var i = 0
                while (i < vCount) {
                    val x = base[i]
                    val y = base[i + 1]
                    // 将 NDC -> uv: u = (x+1)/2, v = (1-y)/2
                    val u = (x + 1f) * 0.5f
                    val v = (1f - y) * 0.5f
                    // 简易变形: 垂直方向基于 u 和时间的正弦偏移，v 控制幅度
                    val deform = kotlin.math.sin(now * 6.0f + u * 6.28f) * pulse * (0.02f + 0.06f * (1f - v))
                    displaced.put(i, x)
                    displaced.put(i + 1, y + deform)
                    i += 2
                }
                displaced.position(0)
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mesh.vboPos)
                GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, 0, vCount * 4, displaced)
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
            }

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mesh.texId)
            GLES20.glUniform1i(uTextureLoc, 0)

            // 绑定位置 VBO
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mesh.vboPos)
            GLES20.glEnableVertexAttribArray(aPositionLoc)
            GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 0, 0)

            // 绑定 texcoord VBO
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mesh.vboTex)
            GLES20.glEnableVertexAttribArray(aTexCoordLoc)
            GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, 0)

            // 绑定索引并绘制
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, mesh.ibo)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, mesh.indexCount, GLES20.GL_UNSIGNED_SHORT, 0)

            GLES20.glDisableVertexAttribArray(aPositionLoc)
            GLES20.glDisableVertexAttribArray(aTexCoordLoc)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
            // 衰减脉冲
            pulseMap[key] = (pulseMap.getOrDefault(key, 0f) * 0.88f).coerceAtLeast(0f)
        }
    }

    private fun loadShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        return shader
    }

    // 创建或更新纹理，并返回 texId（在 GL 线程上下文中调用）
    fun uploadTexture(key: String, bitmap: Bitmap) {
        // 若已存在则替换
        val existing = meshes[key]
        if (existing != null && existing.texId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(existing.texId), 0)
            existing.texId = 0
        }

        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        val texId = texIds[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)

        val mesh = meshes.getOrPut(key) { MeshData() }
        mesh.texId = texId

        // LRU 管理
        textureLru.remove(key)
        textureLru.addFirst(key)
        // 如果超出限制，逐出最老的
        while (textureLru.size > maxTextures) {
            val removeKey = textureLru.removeLast()
            meshes.remove(removeKey)?.let { md ->
                if (md.texId != 0) GLES20.glDeleteTextures(1, intArrayOf(md.texId), 0)
                if (md.vboPos != 0) GLES20.glDeleteBuffers(1, intArrayOf(md.vboPos), 0)
                if (md.vboTex != 0) GLES20.glDeleteBuffers(1, intArrayOf(md.vboTex), 0)
                if (md.ibo != 0) GLES20.glDeleteBuffers(1, intArrayOf(md.ibo), 0)
            }
        }
    }

    // 创建网格（meshW x meshH），并生成 VBO/IBO，初始顶点为标准单位正方形（NDC -1..1）
    fun createMesh(key: String, meshW: Int, meshH: Int) {
        if (meshW <= 0 || meshH <= 0) return
        val md = meshes.getOrPut(key) { MeshData() }
        // 若已有 mesh，删除旧缓冲
        if (md.vboPos != 0) GLES20.glDeleteBuffers(1, intArrayOf(md.vboPos), 0)
        if (md.vboTex != 0) GLES20.glDeleteBuffers(1, intArrayOf(md.vboTex), 0)
        if (md.ibo != 0) GLES20.glDeleteBuffers(1, intArrayOf(md.ibo), 0)

        val vertexCount = (meshW + 1) * (meshH + 1)
        // 位置 buffer 初始化为单元网格（NDC）
        val pos = FloatArray(vertexCount * 2)
        val tex = FloatArray(vertexCount * 2)
        var idx = 0
        for (y in 0..meshH) {
            val v = y.toFloat() / meshH.toFloat()
            for (x in 0..meshW) {
                val u = x.toFloat() / meshW.toFloat()
                // NDC coords, origin center: map u,v to -1..1 (flip Y)
                pos[idx * 2] = u * 2f - 1f
                pos[idx * 2 + 1] = 1f - v * 2f
                tex[idx * 2] = u
                tex[idx * 2 + 1] = v
                idx++
            }
        }

        // indices for triangles
        val indices = ShortArray(meshW * meshH * 6)
        var id = 0
        for (y in 0 until meshH) {
            for (x in 0 until meshW) {
                val topLeft = (y * (meshW + 1) + x).toShort()
                val topRight = (topLeft + 1).toShort()
                val bottomLeft = ((y + 1) * (meshW + 1) + x).toShort()
                val bottomRight = (bottomLeft + 1).toShort()

                indices[id++] = topLeft
                indices[id++] = bottomLeft
                indices[id++] = topRight

                indices[id++] = topRight
                indices[id++] = bottomLeft
                indices[id++] = bottomRight
            }
        }

        // create VBOs and IBO
        val vbos = IntArray(2)
        GLES20.glGenBuffers(2, vbos, 0)
        val vboPos = vbos[0]
        val vboTex = vbos[1]

        val ibos = IntArray(1)
        GLES20.glGenBuffers(1, ibos, 0)
        val ibo = ibos[0]

        // upload pos
        val posBuf = ByteBuffer.allocateDirect(pos.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(pos); position(0) }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboPos)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, pos.size * 4, posBuf, GLES20.GL_DYNAMIC_DRAW)

        // upload tex
        val texBuf = ByteBuffer.allocateDirect(tex.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(tex); position(0) }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboTex)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, tex.size * 4, texBuf, GLES20.GL_STATIC_DRAW)

        // upload indices
        val idxBuf = ByteBuffer.allocateDirect(indices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().apply { put(indices); position(0) }
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, indices.size * 2, idxBuf, GLES20.GL_STATIC_DRAW)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)

        md.vboPos = vboPos
        md.vboTex = vboTex
        md.ibo = ibo
        md.indexCount = indices.size
        md.meshW = meshW
        md.meshH = meshH
        md.basePos = pos

        // ensure key in LRU
        textureLru.remove(key)
        textureLru.addFirst(key)
        if (textureLru.size > maxTextures) {
            val removeKey = textureLru.removeLast()
            meshes.remove(removeKey)?.let { md2 ->
                if (md2.texId != 0) GLES20.glDeleteTextures(1, intArrayOf(md2.texId), 0)
                if (md2.vboPos != 0) GLES20.glDeleteBuffers(1, intArrayOf(md2.vboPos), 0)
                if (md2.vboTex != 0) GLES20.glDeleteBuffers(1, intArrayOf(md2.vboTex), 0)
                if (md2.ibo != 0) GLES20.glDeleteBuffers(1, intArrayOf(md2.ibo), 0)
            }
        }
    }

    // 更新网格顶点位置（NDC 空间坐标，长度应为 (meshW+1)*(meshH+1)*2），在 GL 线程调用
    fun updateMeshVertices(key: String, vertsNdc: FloatArray) {
        val md = meshes[key] ?: return
        if (md.vboPos == 0) return
        val expected = (md.meshW + 1) * (md.meshH + 1) * 2
        if (vertsNdc.size < expected) return

        // 复用临时 buffer
        val fb = tmpFloatBuffer ?: ByteBuffer.allocateDirect(expected * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().also { tmpFloatBuffer = it }
        fb.rewind()
        fb.put(vertsNdc, 0, expected)
        fb.position(0)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, md.vboPos)
        GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, 0, expected * 4, fb)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

        // mark as recently used
        textureLru.remove(key)
        textureLru.addFirst(key)
    }

    fun setAlpha(a: Float) {
        alpha = a.coerceIn(0f, 1f)
    }

    fun onBeat(event: BeatEvent) {
        // 将节拍强度记录到 pulseMap，用于在下一帧渲染时触发顶点脉冲动画
        val strength = event.strength.coerceIn(0f, 1f)
        // 使用 key "frame" 作为默认精灵键（overlay 上传时使用该 key）
        val key = "frame"
        val prev = pulseMap.getOrDefault(key, 0f)
        pulseMap[key] = maxOf(prev * 0.7f, strength)
    }

    fun setTargetSize(px: Int) {
        // 暂存目标纹理尺寸（px），可用于后续 LOD 或渲染调整
        targetSizePx = px
    }

    fun release() {
        for ((_, md) in meshes) {
            if (md.texId != 0) GLES20.glDeleteTextures(1, intArrayOf(md.texId), 0)
            if (md.vboPos != 0) GLES20.glDeleteBuffers(1, intArrayOf(md.vboPos), 0)
            if (md.vboTex != 0) GLES20.glDeleteBuffers(1, intArrayOf(md.vboTex), 0)
            if (md.ibo != 0) GLES20.glDeleteBuffers(1, intArrayOf(md.ibo), 0)
        }
        meshes.clear()
        textureLru.clear()
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
    }
}
