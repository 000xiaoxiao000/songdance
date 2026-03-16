package com.example.myapplication

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 一个保留的、较小的 OpenGL ES 渲染器占位实现，避免项目因遗留文件而无法编译。
 * 该类实现了简单的四边形纹理绘制（用于回退/演示）。
 */
class OpenGLESRenderer_Legacy : GLSurfaceView.Renderer {

    // 顶点 (x,y) 和 纹理坐标 (s,t)
    private val quadCoords = floatArrayOf(
        -1f,  1f,  // left top
        -1f, -1f,  // left bottom
         1f, -1f,  // right bottom
         1f,  1f   // right top
    )
    private val quadTex = floatArrayOf(
        0f, 0f,
        0f, 1f,
        1f, 1f,
        1f, 0f
    )

    private val vertexBuffer: FloatBuffer = run {
        val bb = ByteBuffer.allocateDirect(quadCoords.size * 4)
            .order(ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer()
        fb.put(quadCoords)
        fb.position(0)
        fb
    }

    private val texBuffer: FloatBuffer = run {
        val bb = ByteBuffer.allocateDirect(quadTex.size * 4)
            .order(ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer()
        fb.put(quadTex)
        fb.position(0)
        fb
    }

    private var program = 0
    private var aPositionLoc = 0
    private var aTexCoordLoc = 0
    private var uTextureLoc = 0
    private var uAlphaLoc = 0

    // 单一精灵纹理 id（支持按 key 管理多个纹理）
    private val textureMap = mutableMapOf<String, Int>()

    // 渲染参数
    private var viewW = 0
    private var viewH = 0
    private var alpha = 1f

    override fun onSurfaceCreated(gl: javax.microedition.khronos.opengles.GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        val vs = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """.trimIndent()

        val fs = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            uniform float uAlpha;
            void main() {
                vec4 c = texture2D(uTexture, vTexCoord);
                gl_FragColor = vec4(c.rgb, c.a * uAlpha);
            }
        """.trimIndent()

        val vert = loadShader(GLES20.GL_VERTEX_SHADER, vs)
        val frag = loadShader(GLES20.GL_FRAGMENT_SHADER, fs)
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

        // 绘制主纹理（尝试使用 key "frame"）
        val texId = textureMap["frame"] ?: return

        GLES20.glUseProgram(program)

        // 顶点
        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        // 纹理坐标
        GLES20.glEnableVertexAttribArray(aTexCoordLoc)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texBuffer)

        // 纹理
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glUniform1i(uTextureLoc, 0)
        GLES20.glUniform1f(uAlphaLoc, alpha)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)
    }

    private fun loadShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        return shader
    }

    // 上传位图为纹理（在 GL 线程调用）
    fun uploadTexture(key: String, bitmap: Bitmap) {
        // 如果已有旧纹理，则删除
        textureMap[key]?.let { old ->
            GLES20.glDeleteTextures(1, intArrayOf(old), 0)
            textureMap.remove(key)
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

        textureMap[key] = texId
    }

    fun setAlpha(a: Float) {
        alpha = a.coerceIn(0f, 1f)
    }

    fun onBeat(event: Any) {
        // 占位：可在此触发短暂的缩放/闪烁动画参数
    }

    fun setTargetSize(px: Int) {
        // 占位：如果需要可根据目标纹理尺寸调整绘制逻辑
    }

    fun release() {
        // 删除所有纹理
        for ((_, id) in textureMap) {
            GLES20.glDeleteTextures(1, intArrayOf(id), 0)
        }
        textureMap.clear()
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
    }

}
