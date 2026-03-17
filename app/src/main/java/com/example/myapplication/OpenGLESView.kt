package com.example.myapplication

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.util.AttributeSet

/**
 * 简单的 GLSurfaceView 封装，用于承载 OpenGL ES 渲染器。
 * 提供上传纹理、设置目标尺寸、透明度、节拍回调等基础 API。
 */
class OpenGLESView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,

) : GLSurfaceView(context, attrs) {

    private val renderer: OpenGLESRenderer

    init {
        // 使用 OpenGL ES 2.0
        setEGLContextClientVersion(2)
        // Request an EGL config with 8 bits for R,G,B,A so the surface supports alpha
        // This must be set before setRenderer to take effect.
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        renderer = OpenGLESRenderer()
        setRenderer(renderer)
        // 默认不连续渲染，仅在需要时 requestRender
        renderMode = RENDERMODE_WHEN_DIRTY
        // 透明背景
        holder.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
        setZOrderMediaOverlay(true)
    }

    // 设置目标纹理尺寸（px）
    fun setSize(displayPx: Int) {
        // 目前占位：GL 渲染器会在需要时使用此信息
        queueEvent { renderer.setTargetSize(displayPx) }
    }

    fun setAlphaValue(alpha: Float) {
        queueEvent { renderer.setAlpha(alpha) }
        // 请求一次重绘以应用 alpha
        post { requestRender() }
    }

    fun onBeat(event: BeatEvent) {
        queueEvent { renderer.onBeat(event) }
        post { requestRender() }
    }

    fun uploadBitmapTexture(key: String, bitmap: Bitmap) {
        queueEvent { renderer.uploadTexture(key, bitmap) }
        post { requestRender() }
    }

    // 为纹理创建网格（meshW x meshH），用于后续顶点更新与变形
    fun createMeshForKey(key: String, meshW: Int, meshH: Int) {
        queueEvent { renderer.createMesh(key, meshW, meshH) }
    }

    // 更新网格顶点（NDC 坐标数组），长度应为 (meshW+1)*(meshH+1)*2
    fun updateMeshVertices(key: String, vertsNdc: FloatArray) {
        queueEvent { renderer.updateMeshVertices(key, vertsNdc) }
        post { requestRender() }
    }

    // 便捷接口：上传位图并确保 mesh 已创建
    fun uploadBitmapWithMesh(key: String, bitmap: Bitmap, meshW: Int, meshH: Int) {
        queueEvent {
            renderer.ensureMesh(key, meshW, meshH)
            renderer.uploadTexture(key, bitmap)
        }
        post { requestRender() }
    }

    fun releaseGLResources() {
        queueEvent { renderer.release() }
    }

    fun setRenderModeContinuous() {
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun setRenderModeDirty() {
        renderMode = RENDERMODE_WHEN_DIRTY
    }
}
