package com.gazetap.app.gaze

import kotlin.math.abs

/**
 * Detects a deliberate head turn (yaw = left/right, pitch = up/down) as a swipe command.
 *
 * This is independent of the calibrated gaze cursor, so a clear head turn works even when eye/gaze
 * tracking is noisy or when the cursor model is poorly calibrated. To avoid accidental swipes while
 * the user is just looking around, a swipe must start from a settled head pose, make a clear turn,
 * and hold that turned pose briefly.
 */
class HeadSwipeDetector(
    var yawThresholdDeg: Double = 14.0,
    var pitchThresholdDeg: Double = 12.0,
    var settleWindowMs: Double = 450.0,
    var settleMaxDeltaDeg: Double = 4.0,
    var maxTurnMs: Double = 500.0,
    var holdMs: Double = 120.0,
    var cooldownMs: Double = 900.0,
) {
    private data class Sample(val t: Double, val yaw: Double, val pitch: Double)

    private val history = ArrayDeque<Sample>()
    private var baselineYaw = Double.NaN
    private var baselinePitch = Double.NaN
    private var baselineAtMs = Double.NaN
    private var candidateDir: FlickDirection? = null
    private var candidateSinceMs = Double.NaN
    private var lastEmitMs = Double.NaN

    fun update(yawDeg: Double, pitchDeg: Double, tsMs: Double): FlickDirection? {
        if (!yawDeg.isFinite() || !pitchDeg.isFinite()) return null

        history.addLast(Sample(tsMs, yawDeg, pitchDeg))
        while (history.isNotEmpty() && tsMs - history.first().t > HISTORY_MS) history.removeFirst()

        // Maintain a "settled" baseline: head must stay within a small range for a while.
        if (baselineYaw.isNaN()) {
            baselineYaw = yawDeg
            baselinePitch = pitchDeg
            baselineAtMs = tsMs
        } else {
            val dyaw = abs(yawDeg - baselineYaw)
            val dpitch = abs(pitchDeg - baselinePitch)
            if (dyaw <= settleMaxDeltaDeg && dpitch <= settleMaxDeltaDeg) {
                // still settled around baseline; keep baseline fresh (slow recenter)
                if (tsMs - baselineAtMs > 120.0) {
                    baselineYaw = baselineYaw * 0.7 + yawDeg * 0.3
                    baselinePitch = baselinePitch * 0.7 + pitchDeg * 0.3
                    baselineAtMs = tsMs
                }
            } else if (candidateDir == null) {
                // moved away from baseline while not in a candidate: baseline will be re-established
                // only after we return close and settle again.
                baselineYaw = yawDeg
                baselinePitch = pitchDeg
                baselineAtMs = tsMs
            }
        }

        val coolingDown = !lastEmitMs.isNaN() && tsMs - lastEmitMs < cooldownMs
        if (coolingDown) {
            candidateDir = null
            return null
        }

        val settled = !baselineYaw.isNaN() && tsMs - baselineAtMs >= settleWindowMs
        val dy = yawDeg - baselineYaw
        val dp = pitchDeg - baselinePitch
        val ay = abs(dy)
        val ap = abs(dp)

        // Restored pitch (up/down) to v0.28.7 mapping which was working; yaw kept as v0.28.7.
        val dirNow = when {
            ay >= yawThresholdDeg && ay >= ap * 1.2 -> if (dy > 0) FlickDirection.RIGHT else FlickDirection.LEFT
            ap >= pitchThresholdDeg && ap >= ay * 1.2 -> if (dp > 0) FlickDirection.DOWN else FlickDirection.UP
            else -> null
        }

        if (dirNow == null) {
            candidateDir = null
            candidateSinceMs = Double.NaN
            // if we returned near baseline, baseline timer is handled above
            return null
        }

        if (!settled) {
            // need a settled baseline before a deliberate turn counts
            candidateDir = null
            candidateSinceMs = Double.NaN
            return null
        }

        if (candidateDir != dirNow) {
            candidateDir = dirNow
            candidateSinceMs = tsMs
            return null
        }

        // same direction held: require it to be reached reasonably quickly and held briefly
        if (tsMs - baselineAtMs > settleWindowMs + maxTurnMs + holdMs) {
            candidateDir = null
            return null
        }
        if (tsMs - candidateSinceMs >= holdMs) {
            lastEmitMs = tsMs
            candidateDir = null
            candidateSinceMs = Double.NaN
            // reset baseline so user must return/settle before next swipe
            baselineYaw = yawDeg
            baselinePitch = pitchDeg
            baselineAtMs = tsMs
            return dirNow
        }
        return null
    }

    fun reset() {
        history.clear()
        baselineYaw = Double.NaN
        baselinePitch = Double.NaN
        baselineAtMs = Double.NaN
        candidateDir = null
        candidateSinceMs = Double.NaN
        lastEmitMs = Double.NaN
    }

    private companion object {
        const val HISTORY_MS = 1500.0
    }
}
