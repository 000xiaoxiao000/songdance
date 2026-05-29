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
            val progress = i.toFloat() / frameCount
            val animatedKeypoints = animateKeypoints(basePose, progress, danceStyle)
            sequence.add(AnimatedPose(animatedKeypoints, progress))
        }
        
        return sequence
    }
    
    private fun animateKeypoints(
        basePose: PoseDetector.Pose,
        progress: Float,
        danceStyle: DanceStyle
    ): List<PointF> {
        return when (danceStyle) {
            DanceStyle.POWER -> animatePower(basePose, progress)
            DanceStyle.CHILL -> animateChill(basePose, progress)
            DanceStyle.GROOVE -> animateGroove(basePose, progress)
        }
    }
    
    private fun animatePower(pose: PoseDetector.Pose, progress: Float): List<PointF> {
        val animatedPoints = mutableListOf<PointF>()
        val cycle = progress * 4 * PI.toFloat()
        
        pose.keypoints.forEachIndexed { index, keypoint ->
            val basePoint = keypoint.position
            
            val animated = when (index) {
                5, 6 -> {
                    val armSwing = sin(cycle) * 30f
                    PointF(basePoint.x + armSwing, basePoint.y)
                }
                7, 8 -> {
                    val elbowBend = sin(cycle + PI.toFloat() / 2) * 40f
                    PointF(basePoint.x + elbowBend, basePoint.y)
                }
                9, 10 -> {
                    val wristFlick = sin(cycle + PI.toFloat()) * 50f
                    PointF(basePoint.x + wristFlick, basePoint.y - abs(sin(cycle)) * 20f)
                }
                11, 12 -> {
                    val hipSway = sin(cycle * 0.5f) * 15f
                    PointF(basePoint.x + hipSway, basePoint.y)
                }
                13, 14 -> {
                    val kneeBend = abs(sin(cycle)) * 25f
                    PointF(basePoint.x, basePoint.y + kneeBend)
                }
                15, 16 -> {
                    val footTap = abs(sin(cycle * 2)) * 15f
                    PointF(basePoint.x, basePoint.y + footTap)
                }
                else -> basePoint
            }
            
            animatedPoints.add(animated)
        }
        
        return animatedPoints
    }
    
    private fun animateChill(pose: PoseDetector.Pose, progress: Float): List<PointF> {
        val animatedPoints = mutableListOf<PointF>()
        val cycle = progress * 2 * PI.toFloat()
        
        pose.keypoints.forEachIndexed { index, keypoint ->
            val basePoint = keypoint.position
            
            val animated = when (index) {
                5, 6 -> {
                    val armWave = sin(cycle) * 20f
                    val armLift = cos(cycle) * 15f
                    PointF(basePoint.x + armWave, basePoint.y - armLift)
                }
                7, 8 -> {
                    val elbowFlow = sin(cycle + PI.toFloat() / 3) * 25f
                    PointF(basePoint.x + elbowFlow, basePoint.y)
                }
                9, 10 -> {
                    val wristCircle = sin(cycle + PI.toFloat() / 2) * 30f
                    val wristHeight = cos(cycle + PI.toFloat() / 2) * 20f
                    PointF(basePoint.x + wristCircle, basePoint.y + wristHeight)
                }
                11, 12 -> {
                    val hipRoll = sin(cycle * 0.5f) * 12f
                    PointF(basePoint.x + hipRoll, basePoint.y)
                }
                else -> basePoint
            }
            
            animatedPoints.add(animated)
        }
        
        return animatedPoints
    }
    
    private fun animateGroove(pose: PoseDetector.Pose, progress: Float): List<PointF> {
        val animatedPoints = mutableListOf<PointF>()
        val beat = (progress * 8).toInt()
        val beatProgress = (progress * 8) - beat
        val isOnBeat = beatProgress < 0.3f
        
        pose.keypoints.forEachIndexed { index, keypoint ->
            val basePoint = keypoint.position
            
            val animated = if (isOnBeat) {
                val intensity = sin(beatProgress * PI.toFloat() / 0.3f)
                
                when (index) {
                    5, 6 -> {
                        val armPop = intensity * 35f
                        PointF(basePoint.x + armPop * if (index == 5) -1 else 1, basePoint.y - armPop * 0.5f)
                    }
                    9, 10 -> {
                        val handPop = intensity * 45f
                        PointF(basePoint.x + handPop * if (index == 9) -1 else 1, basePoint.y)
                    }
                    11, 12 -> {
                        val hipDrop = intensity * 20f
                        PointF(basePoint.x, basePoint.y + hipDrop)
                    }
                    13, 14 -> {
                        val kneeBounce = intensity * 30f
                        PointF(basePoint.x, basePoint.y + kneeBounce)
                    }
                    else -> basePoint
                }
            } else {
                basePoint
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
