package com.example.myapplication

/**
 * OpenGL ES 相关的运动平滑与插值工具。
 * 为避免与旧符号冲突，内部类型以 OpenGLES 前缀命名，并提供兼容层。
 */
class OpenGLESBezierInterpolator(
    private val x1: Float, private val y1: Float,
    private val x2: Float, private val y2: Float
) {
    // 计算贝塞尔插值结果，t 为 0..1（仅返回 y 轴映射）
    fun getInterpolation(t: Float): Float {
        val u = 1 - t
        val tt = t * t
        val uu = u * u
        val uuu = uu * u
        val ttt = tt * t
        return (uuu * 0f) + (3 * uu * t * y1) + (3 * u * tt * y2) + (ttt * 1f)
    }
}

object OpenGLESMotionSmoother {
    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    fun bezier(a: Float, b: Float, t: Float, interpolator: OpenGLESBezierInterpolator): Float {
        val factor = interpolator.getInterpolation(t)
        return lerp(a, b, factor)
    }
}

// 兼容层：保留原名，转发到新的实现，避免修改大量调用处。
typealias CubicBezierInterpolator = OpenGLESBezierInterpolator

object MotionSmoother {
    fun lerp(a: Float, b: Float, t: Float): Float = OpenGLESMotionSmoother.lerp(a, b, t)

    fun bezier(a: Float, b: Float, t: Float, interpolator: CubicBezierInterpolator): Float {
        // interpolator 已由 typealias 指向 OpenGLESBezierInterpolator
        return OpenGLESMotionSmoother.bezier(a, b, t, interpolator as OpenGLESBezierInterpolator)
    }
}

