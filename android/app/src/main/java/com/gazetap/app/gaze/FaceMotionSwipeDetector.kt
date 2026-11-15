package com.gazetap.app.gaze

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Joystick-like swipe detection from whole-face movement around a neutral center.
 * Core logic kept same as the well-working v0.28.11 behavior:
 * - neutral center tracked while still near center
 * - move away from center past trigger => swipe once (with early commit for fast/clear intent)
 * - return near center to re-arm (no swipe on return), with fast hysteresis re-arm for rapid sequences
 * - auto-recenter recovery if stuck away from center
 *
 * Improvements: adaptive smoothing (1€-filter style) for smooth but low-lag tracking,
 * intent-based early commit for real-time responsiveness, and hysteresis re-arm so
 * quick consecutive swipes (e.g. left-right-left) work without fully pausing at center,
 * while rejecting slow drift/random motion.
 */
class FaceMotionSwipeDetector(
    /** Full distance from center required to trigger a normal swipe. */
    var triggerPx: Double = 115.0,
    /** Distance for a fast/clear intentional flick (early commit). */
    var earlyTriggerPx: Double = 72.0,
    /** Distance for a very fast intentional flick (earliest commit, for real-time games). */
    var veryEarlyTriggerPx: Double = 52.0,
    /** Outward speed (px/s) considered a fast intentional flick for early commit. */
    var fastOutwardSpeedPxPerSec: Double = 360.0,
    /** Outward speed (px/s) considered a very fast intentional flick for earliest commit. */
    var veryFastOutwardSpeedPxPerSec: Double = 620.0,
    /** Distance from center considered "in center zone" for neutral tracking. */
    var returnPx: Double = 85.0,
    /** Inner hysteresis distance: after firing, must come back within this to re-arm (enables rapid reversals). */
    var rearmPx: Double = 42.0,
    /** Max speed (px/s) to treat as still for (re)centering. */
    var settleSpeedPxPerSec: Double = 210.0,
    /** How long it must be still/near-center before (re)centering. */
    var settleMs: Double = 50.0,
    /** Cooldown after emitting a swipe. */
    var cooldownMs: Double = 100.0,
    /** Dominant axis must be this many times the other axis. */
    var minAxisDominance: Double = 1.3,
    /** Slightly stricter dominance for very early commits. */
    var minEarlyAxisDominance: Double = 1.45,
    /** If disarmed and settled away from center this long, recenter here (recover from lost/stale center). */
    var lostCenterMs: Double = 320.0,
) {
    // --- 1€ filter (adaptive low-pass) for smooth + responsive input ---
    private var minCutoff = 1.25
    private var beta = 0.014
    private var dCutoff = 1.0
    private var xFilt = LowPass()
    private var yFilt = LowPass()
    private var dxFilt = LowPass()
    private var dyFilt = LowPass()

    private var centerX = Double.NaN
    private var centerY = Double.NaN
    private var lastX = Double.NaN
    private var lastY = Double.NaN
    private var lastT = Double.NaN
    private var stableSince = Double.NaN
    private var lastEmitMs = Double.NaN
    private var armed = true
    private var farSettledSince = Double.NaN
    private var peakOutwardSpeed = 0.0
    private var peakSpeedAway = 0.0

    // Debug snapshot
    @Volatile var debugDist: Double = 0.0
        private set
    @Volatile var debugDx: Double = 0.0
        private set
    @Volatile var debugDy: Double = 0.0
        private set
    @Volatile var debugSpeed: Double = 0.0
        private set
    @Volatile var debugArmed: Boolean = true
        private set
    @Volatile var debugNearCenter: Boolean = true
        private set
    @Volatile var debugSettled: Boolean = false
        private set
    @Volatile var debugHasCenter: Boolean = false
        private set

    private class LowPass {
        var y: Double = Double.NaN
        fun filter(x: Double, alpha: Double): Double {
            y = if (y.isNaN()) x else alpha * x + (1.0 - alpha) * y
            return y
        }
        fun hasLast(): Boolean = y.isFinite()
        fun last(): Double = y
        fun reset() { y = Double.NaN }
    }

    private fun alpha(cutoff: Double, dt: Double): Double {
        val tau = 1.0 / (2.0 * Math.PI * cutoff)
        return 1.0 / (1.0 + tau / dt)
    }

    private fun filterPos(x: Double, y: Double, dtSec: Double): Pair<Double, Double> {
        val dx = if (xFilt.hasLast()) (x - xFilt.last()) / dtSec else 0.0
        val dy = if (yFilt.hasLast()) (y - yFilt.last()) / dtSec else 0.0
        val edx = dxFilt.filter(dx, alpha(dCutoff, dtSec))
        val edy = dyFilt.filter(dy, alpha(dCutoff, dtSec))
        val speedMag = hypot(edx, edy)
        val cutoff = minCutoff + beta * speedMag
        val a = alpha(cutoff, dtSec)
        return Pair(xFilt.filter(x, a), yFilt.filter(y, a))
    }

    fun update(x: Double, y: Double, tsMs: Double): FlickDirection? {
        if (!x.isFinite() || !y.isFinite()) return null

        if (lastT.isNaN()) {
            val fx = xFilt.filter(x, 1.0)
            val fy = yFilt.filter(y, 1.0)
            dxFilt.filter(0.0, 1.0); dyFilt.filter(0.0, 1.0)
            lastX = fx; lastY = fy; lastT = tsMs
            centerX = fx; centerY = fy
            stableSince = tsMs
            debugHasCenter = true
            debugArmed = true; debugNearCenter = true; debugSettled = false
            return null
        }

        val dtMs = (tsMs - lastT).coerceAtLeast(1.0)
        val dtSec = dtMs / 1000.0
        val (fx, fy) = filterPos(x, y, dtSec)
        val vx = (fx - lastX) / dtSec
        val vy = (fy - lastY) / dtSec
        val speed = hypot(vx, vy)
        lastX = fx; lastY = fy; lastT = tsMs

        debugSpeed = speed
        if (!centerX.isFinite() || !centerY.isFinite()) {
            centerX = fx; centerY = fy
            stableSince = tsMs
            armed = true
            peakSpeedAway = 0.0
            peakOutwardSpeed = 0.0
            debugHasCenter = true
            debugDist = 0.0; debugDx = 0.0; debugDy = 0.0
            debugArmed = armed; debugNearCenter = true; debugSettled = false
            return null
        }

        val dx = fx - centerX
        val dy = fy - centerY
        val dist = hypot(dx, dy)
        val inCenterZone = dist <= returnPx
        val inRearmZone = dist <= rearmPx
        debugHasCenter = true
        debugDx = dx; debugDy = dy; debugDist = dist
        debugNearCenter = inCenterZone

        if (speed <= settleSpeedPxPerSec) {
            if (stableSince.isNaN()) stableSince = tsMs
        } else {
            stableSince = Double.NaN
        }
        val settled = !stableSince.isNaN() && (tsMs - stableSince >= settleMs)
        debugSettled = settled

        // Track neutral while in center zone to keep center stable/responsive.
        if (inCenterZone) {
            if (settled || speed <= settleSpeedPxPerSec) {
                centerX = centerX * 0.6 + fx * 0.4
                centerY = centerY * 0.6 + fy * 0.4
            }
            farSettledSince = Double.NaN
        } else if (settled) {
            if (farSettledSince.isNaN()) farSettledSince = tsMs
            if (!armed && tsMs - farSettledSince >= lostCenterMs) {
                centerX = fx; centerY = fy
                armed = true
                farSettledSince = Double.NaN
                peakSpeedAway = 0.0
                peakOutwardSpeed = 0.0
            } else if (armed && tsMs - farSettledSince >= lostCenterMs) {
                // drifted neutral while idle: gently recenter
                centerX = fx; centerY = fy
                peakSpeedAway = 0.0
                peakOutwardSpeed = 0.0
            }
        } else {
            farSettledSince = Double.NaN
        }

        // Hysteresis re-arm: after a swipe, re-arm quickly once back inside inner zone (supports rapid reversals).
        if (!armed && inRearmZone) {
            armed = true
            peakSpeedAway = 0.0
            peakOutwardSpeed = 0.0
        }

        // Track intent away from center. Accumulate during the outward stroke (including as you leave
        // the re-arm zone) so early commit stays responsive; clear only when resting at center.
        val outward = if (dist > 1e-6) (vx * dx + vy * dy) / dist else 0.0
        if (armed) {
            if (outward > 0.0) {
                peakOutwardSpeed = max(peakOutwardSpeed, outward)
                peakSpeedAway = max(peakSpeedAway, speed)
            }
            if (inCenterZone && settled && outward <= 30.0 && speed <= settleSpeedPxPerSec) {
                // resting at neutral: clear stroke intent
                peakSpeedAway = 0.0
                peakOutwardSpeed = 0.0
            }
        } else {
            // while disarmed (awaiting re-arm), do not accumulate a new stroke
            peakSpeedAway = 0.0
            peakOutwardSpeed = 0.0
        }

        debugArmed = armed

        if (!lastEmitMs.isNaN() && tsMs - lastEmitMs < cooldownMs) return null
        if (!armed) return null

        val adx = abs(dx)
        val ady = abs(dy)
        val horizontal = adx >= ady
        val dominance = if (horizontal) adx / ady.coerceAtLeast(1e-6) else ady / adx.coerceAtLeast(1e-6)

        // Early commit for real-time responsiveness: a fast, clear outward motion in one direction
        // fires before the full head movement completes.
        val fastIntent = peakOutwardSpeed >= fastOutwardSpeedPxPerSec || outward >= fastOutwardSpeedPxPerSec
        val veryFastIntent = peakOutwardSpeed >= veryFastOutwardSpeedPxPerSec || outward >= veryFastOutwardSpeedPxPerSec
        val requiredDist = when {
            veryFastIntent -> veryEarlyTriggerPx
            fastIntent -> earlyTriggerPx
            else -> triggerPx
        }
        val requiredDominance = if (veryFastIntent) minEarlyAxisDominance else minAxisDominance

        if (dist < requiredDist) return null
        if (dominance < requiredDominance) return null

        // Reject slow drift unless it's a clearly fast outward intent.
        if (!fastIntent && peakSpeedAway < 120.0 && speed < 100.0 && outward < 90.0) return null
        // Even for fast intent, require moving away (not a rebound/jitter) and minimal clarity.
        if (outward < -40.0) return null
        // For early commits, ensure the motion is actively moving outward (ballistic flick), unless very fast.
        if (dist < triggerPx && outward < 70.0 && !veryFastIntent) return null

        val dir = if (horizontal) {
            if (dx < 0) FlickDirection.LEFT else FlickDirection.RIGHT
        } else {
            if (dy < 0) FlickDirection.UP else FlickDirection.DOWN
        }

        lastEmitMs = tsMs
        armed = false
        farSettledSince = Double.NaN
        stableSince = Double.NaN
        peakSpeedAway = 0.0
        peakOutwardSpeed = 0.0
        return dir
    }

    fun reset() {
        xFilt.reset(); yFilt.reset(); dxFilt.reset(); dyFilt.reset()
        centerX = Double.NaN
        centerY = Double.NaN
        lastX = Double.NaN
        lastY = Double.NaN
        lastT = Double.NaN
        stableSince = Double.NaN
        lastEmitMs = Double.NaN
        armed = true
        farSettledSince = Double.NaN
        peakSpeedAway = 0.0
        peakOutwardSpeed = 0.0
        debugDist = 0.0; debugDx = 0.0; debugDy = 0.0; debugSpeed = 0.0
        debugArmed = true; debugNearCenter = true; debugSettled = false; debugHasCenter = false
    }
}
