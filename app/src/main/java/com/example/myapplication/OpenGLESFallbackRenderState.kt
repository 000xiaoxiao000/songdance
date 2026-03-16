package com.example.myapplication

data class OpenGLESFallbackRenderState(
    val headTiltDeg: Float = 0f,
    val bodyLeanDeg: Float = 0f,
    val headOffsetXUnits: Float = 0f,
    val headOffsetYUnits: Float = 0f,
    val faceOffsetXUnits: Float = 0f,
    val faceOffsetYUnits: Float = 0f,
    val breathScale: Float = 1f,
    val breathLiftUnits: Float = 0f,
    val mouthOpen: Float = 0f,
    val browLift: Float = 0f,
    val blushAlphaMultiplier: Float = 1f,
    val spritePulseScale: Float = 1f,
) {
    companion object {
        val Neutral = OpenGLESFallbackRenderState()
    }
}

