package com.example.myapplication

import android.graphics.PointF
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

/**
 * 关键点动画引擎
 * 基于检测到的姿态关键点生成流畅的唱跳动作序列
 */
class KeypointAnimator {
    
    companion object {
        private const val TAG = "KeypointAnimator"
    }
    
    data class AnimatedPose(
        val keypoints: List<PointF>,
        val timestamp: Float
    )
    
    fun generateDanceSequence(
        basePose: PoseDetector.Pose,
        frameCount: Int,
        danceStyle: DanceStyle
    ): List<AnimatedPose> {
        val sequence = mutableListOf<AnimatedPose>()
        
        for (i in 0 until frameCount) {
            val progress = if (frameCount <= 1) 0f else i.toFloat() / (frameCount - 1)
            val animatedKeypoints = animateKeypoints(basePose, progress, danceStyle)
            sequence.add(AnimatedPose(animatedKeypoints, progress))
        }
        
        return smoothSequence(sequence, windowSize = 5)
    }
    
    private fun animateKeypoints(
        basePose: PoseDetector.Pose,
        progress: Float,
        danceStyle: DanceStyle
    ): List<PointF> {
        val imageScale = calculateImageScale(basePose)
        
        return when (danceStyle) {
            DanceStyle.POWER -> animatePower(basePose, progress, imageScale)
            DanceStyle.CHILL -> animateChill(basePose, progress, imageScale)
            DanceStyle.GROOVE -> animateGroove(basePose, progress, imageScale)
        }
    }
    
    private fun calculateImageScale(pose: PoseDetector.Pose): Float {
        val leftShoulder = pose.keypoints.getOrNull(5)?.position
        val rightShoulder = pose.keypoints.getOrNull(6)?.position
        
        if (leftShoulder != null && rightShoulder != null) {
            val shoulderWidth = kotlin.math.abs(rightShoulder.x - leftShoulder.x)
            return (shoulderWidth / 120f).coerceIn(0.5f, 1.4f)
        }
        
        return 1.0f
    }
    
    private fun animatePower(pose: PoseDetector.Pose, progress: Float, scale: Float): List<PointF> {
        val animatedPoints = mutableListOf<PointF>()
        val cycle = progress * 4 * PI.toFloat()
        val amplification = 0.9f
        
        pose.keypoints.forEachIndexed { index, keypoint ->
            val basePoint = keypoint.position
            
            val animated = when (index) {
                5, 6 -> {
                    val armSwing = sin(cycle) * 80f * scale * amplification
                    val armLift = abs(cos(cycle)) * 60f * scale * amplification
                    PointF(basePoint.x + armSwing, basePoint.y - armLift)
                }
                7, 8 -> {
                    val elbowBend = sin(cycle + PI.toFloat() / 2) * 100f * scale * amplification
                    val elbowLift = abs(cos(cycle + PI.toFloat() / 2)) * 50f * scale * amplification
                    PointF(basePoint.x + elbowBend, basePoint.y - elbowLift)
                }
                9, 10 -> {
                    val wristFlick = sin(cycle + PI.toFloat()) * 120f * scale * amplification
                    val wristHeight = abs(sin(cycle)) * 80f * scale * amplification
                    PointF(basePoint.x + wristFlick, basePoint.y - wristHeight)
                }
                11, 12 -> {
                    val hipSway = sin(cycle * 0.5f) * 50f * scale * amplification
                    val hipBounce = abs(sin(cycle * 2)) * 40f * scale * amplification
                    PointF(basePoint.x + hipSway, basePoint.y + hipBounce)
                }
                13, 14 -> {
                    val kneeBend = abs(sin(cycle)) * 80f * scale * amplification
                    PointF(basePoint.x, basePoint.y + kneeBend)
                }
                15, 16 -> {
                    val footTap = abs(sin(cycle * 2)) * 60f * scale * amplification
                    val footSlide = cos(cycle * 2) * 30f * scale * amplification
                    PointF(basePoint.x + footSlide, basePoint.y + footTap)
                }
                0 -> {
                    val headBob = sin(cycle * 2) * 30f * scale * amplification
                    PointF(basePoint.x, basePoint.y + headBob)
                }
                else -> basePoint
            }
            
            animatedPoints.add(animated)
        }
        
        return animatedPoints
    }
    
    private fun animateChill(pose: PoseDetector.Pose, progress: Float, scale: Float): List<PointF> {
        val animatedPoints = mutableListOf<PointF>()
        val cycle = progress * 2 * PI.toFloat()
        val amplification = 0.7f
        
        pose.keypoints.forEachIndexed { index, keypoint ->
            val basePoint = keypoint.position
            
            val animated = when (index) {
                5, 6 -> {
                    val armWave = sin(cycle) * 70f * scale * amplification
                    val armLift = cos(cycle) * 60f * scale * amplification
                    PointF(basePoint.x + armWave, basePoint.y - armLift)
                }
                7, 8 -> {
                    val elbowFlow = sin(cycle + PI.toFloat() / 3) * 90f * scale * amplification
                    val elbowHeight = cos(cycle + PI.toFloat() / 3) * 40f * scale * amplification
                    PointF(basePoint.x + elbowFlow, basePoint.y - elbowHeight)
                }
                9, 10 -> {
                    val wristCircle = sin(cycle + PI.toFloat() / 2) * 100f * scale * amplification
                    val wristHeight = cos(cycle + PI.toFloat() / 2) * 70f * scale * amplification
                    PointF(basePoint.x + wristCircle, basePoint.y - wristHeight)
                }
                11, 12 -> {
                    val hipRoll = sin(cycle * 0.5f) * 45f * scale * amplification
                    val hipSway = cos(cycle * 0.5f) * 30f * scale * amplification
                    PointF(basePoint.x + hipRoll, basePoint.y + hipSway)
                }
                13, 14 -> {
                    val kneeSway = sin(cycle * 0.5f) * 35f * scale * amplification
                    PointF(basePoint.x + kneeSway, basePoint.y)
                }
                0 -> {
                    val headSway = sin(cycle) * 25f * scale * amplification
                    PointF(basePoint.x + headSway, basePoint.y)
                }
                else -> basePoint
            }
            
            animatedPoints.add(animated)
        }
        
        return animatedPoints
    }
    
    private fun animateGroove(pose: PoseDetector.Pose, progress: Float, scale: Float): List<PointF> {
        val animatedPoints = mutableListOf<PointF>()
        val beat = (progress * 8).toInt()
        val beatProgress = (progress * 8) - beat
        val isOnBeat = beatProgress < 0.25f
        val amplification = 0.9f
        
        pose.keypoints.forEachIndexed { index, keypoint ->
            val basePoint = keypoint.position
            
            val animated = if (isOnBeat) {
                val intensity = sin(beatProgress * PI.toFloat() / 0.25f)
                
                when (index) {
                    5, 6 -> {
                        val armPop = intensity * 120f * scale * amplification
                        val armLift = intensity * 80f * scale * amplification
                        PointF(
                            basePoint.x + armPop * if (index == 5) -1 else 1, 
                            basePoint.y - armLift
                        )
                    }
                    7, 8 -> {
                        val elbowPop = intensity * 100f * scale * amplification
                        val elbowLift = intensity * 60f * scale * amplification
                        PointF(
                            basePoint.x + elbowPop * if (index == 7) -1 else 1,
                            basePoint.y - elbowLift
                        )
                    }
                    9, 10 -> {
                        val handPop = intensity * 140f * scale * amplification
                        val handLift = intensity * 90f * scale * amplification
                        PointF(
                            basePoint.x + handPop * if (index == 9) -1 else 1, 
                            basePoint.y - handLift
                        )
                    }
                    11, 12 -> {
                        val hipDrop = intensity * 70f * scale * amplification
                        val hipSway = intensity * 50f * scale * amplification
                        PointF(
                            basePoint.x + hipSway * if (index == 11) -1 else 1,
                            basePoint.y + hipDrop
                        )
                    }
                    13, 14 -> {
                        val kneeBounce = intensity * 90f * scale * amplification
                        PointF(basePoint.x, basePoint.y + kneeBounce)
                    }
                    15, 16 -> {
                        val footPop = intensity * 70f * scale * amplification
                        val footSlide = intensity * 40f * scale * amplification
                        PointF(
                            basePoint.x + footSlide * if (index == 15) -1 else 1,
                            basePoint.y + footPop
                        )
                    }
                    0 -> {
                        val headBob = intensity * 40f * scale * amplification
                        PointF(basePoint.x, basePoint.y + headBob)
                    }
                    else -> basePoint
                }
            } else {
                val relaxProgress = (beatProgress - 0.25f) / 0.75f
                val relaxIntensity = 1f - relaxProgress
                
                when (index) {
                    5, 6 -> {
                        val armReturn = relaxIntensity * 30f * scale * amplification
                        PointF(
                            basePoint.x + armReturn * if (index == 5) -1 else 1,
                            basePoint.y - armReturn * 0.5f
                        )
                    }
                    11, 12 -> {
                        val hipReturn = relaxIntensity * 20f * scale * amplification
                        PointF(basePoint.x, basePoint.y + hipReturn)
                    }
                    else -> basePoint
                }
            }
            
            animatedPoints.add(animated)
        }
        
        return animatedPoints
    }
    
    private fun abs(value: Float): Float = if (value < 0) -value else value
    
    fun interpolateKeypoints(
        start: List<PointF>,
        end: List<PointF>,
        progress: Float
    ): List<PointF> {
        require(start.size == end.size) { "Keypoint lists must have the same size" }
        
        return start.zip(end) { startPoint, endPoint ->
            PointF(
                startPoint.x + (endPoint.x - startPoint.x) * progress,
                startPoint.y + (endPoint.y - startPoint.y) * progress
            )
        }
    }
    
    fun smoothSequence(sequence: List<AnimatedPose>, windowSize: Int = 3): List<AnimatedPose> {
        if (sequence.size < windowSize) return sequence
        
        val smoothed = mutableListOf<AnimatedPose>()
        val halfWindow = windowSize / 2
        
        for (i in sequence.indices) {
            val start = maxOf(0, i - halfWindow)
            val end = minOf(sequence.size - 1, i + halfWindow)
            val window = sequence.subList(start, end + 1)
            
            val smoothedKeypoints = mutableListOf<PointF>()
            for (keypointIndex in window[0].keypoints.indices) {
                var sumX = 0f
                var sumY = 0f
                
                window.forEach { pose ->
                    sumX += pose.keypoints[keypointIndex].x
                    sumY += pose.keypoints[keypointIndex].y
                }
                
                smoothedKeypoints.add(
                    PointF(sumX / window.size, sumY / window.size)
                )
            }
            
            smoothed.add(AnimatedPose(smoothedKeypoints, sequence[i].timestamp))
        }
        
        return smoothed
    }
}
