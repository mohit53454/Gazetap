package com.gazetap.app

import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.PI

data class HeadPose(val yaw: Float = 0f, val pitch: Float = 0f, val roll: Float = 0f)

object HeadPoseEstimator {
    /**
     * Best-effort extraction from 4x4 facial transformation matrix provided by MediaPipe.
     * Matrix layout may vary by version; this assumes row-major 16 floats in a column-major? 
     * We treat as standard 4x4 and compute Euler angles (degrees). Verify on device.
     */
    fun estimate(result: FaceLandmarkerResult): HeadPose {
        val mats = result.facialTransformationMatrixes()
        if (!mats.isPresent || mats.get().isEmpty()) return HeadPose()
        val m = mats.get()[0]
        if (m.size < 16) return HeadPose()
        // Assume row-major: r00 r01 r02 r03 r10 r11 r12 r13 r20 r21 r22 r23 r30 r31 r32 r33
        val r00 = m[0]; val r01 = m[1]; val r02 = m[2]
        val r10 = m[4]; val r11 = m[5]; val r12 = m[6]
        val r20 = m[8]; val r21 = m[9]; val r22 = m[10]

        val sy = kotlin.math.sqrt(r00 * r00 + r10 * r10)
        val singular = sy < 1e-6f
        val pitch: Float
        val yaw: Float
        val roll: Float
        if (!singular) {
            pitch = atan2(-r20, sy)
            yaw = atan2(r10, r00)
            roll = atan2(r21, r22)
        } else {
            pitch = atan2(-r20, sy)
            yaw = atan2(-r01, r11)
            roll = 0f
        }
        fun deg(rad: Float) = (rad * 180f / PI.toFloat())
        return HeadPose(yaw = deg(yaw), pitch = deg(pitch), roll = deg(roll))
    }
}
