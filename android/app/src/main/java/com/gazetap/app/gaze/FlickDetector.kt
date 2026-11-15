package com.gazetap.app.gaze

import kotlin.math.abs
import kotlin.math.hypot

enum class FlickDirection { LEFT, RIGHT, UP, DOWN }

/**
 * Turns a deliberate eye flick into a discrete direction command - and refuses everything else.
 *
 * The hard problem: at ~15 analysed frames per second a single noisy model prediction that jumps
 * 300 px looks **exactly** like a fast saccade. Geometry alone cannot tell them apart, which is why
 * swipes kept firing while the user was just looking around. Three pieces of temporal evidence fix it:
 *
 *  1. **Arming.** A flick may only start from a gaze that has been continuously settled for
 *     [settleWindowMs]: every recent frame-to-frame step must stay below [rearmSpeedPxPerSec]. A gaze
 *     that is jumping or drifting around never has such a quiet period, so those jumps can never start
 *     a flick.
 *  2. **Deliberate movement.** The movement must be large, fast, straight and one-directional.
 *  3. **Holding.** A candidate is not sent immediately. The new position has to *stay* displaced for
 *     at least one more frame, within [confirmTimeoutMs]. A noise spike snaps back and is discarded;
 *     a real flick ends with the eye holding the new direction.
 *
 * Only then is the swipe dispatched, roughly one frame later.
 */
class FlickDetector(
    var minDisplacementPx: Double = 300.0,
    var maxWindowMs: Double = 200.0,
    var minSpeedPxPerSec: Double = 1600.0,
    var cooldownMs: Double = 700.0,
    var rearmSpeedPxPerSec: Double = 350.0,
    var minAxisDominance: Double = 2.0,
    /** Straight-line distance / travelled path length; 1.0 is perfectly straight. */
    var minStraightness: Double = 0.80,
    /** Fraction of dominant-axis movement that must point the reported way. */
    var minMonotonicity: Double = 0.90,
    /** How long the eye must have been continuously still for a flick to be allowed to start. */
    var settleWindowMs: Double = 500.0,
    /** How long the new position may take to confirm before the candidate is dropped. */
    var confirmTimeoutMs: Double = 300.0,
    /** Fraction of [minDisplacementPx] that must still hold at confirmation time. */
    var confirmFraction: Double = 0.80,
    /** Below this fraction of [minDisplacementPx] the movement is considered to have snapped back. */
    var snapBackFraction: Double = 0.40,
) {
    private data class Sample(val t: Double, val x: Double, val y: Double)

    /** A movement that passed every geometric test and is waiting to be held. */
    private data class Pending(
        val direction: FlickDirection,
        val dx: Double,
        val dy: Double,
        val speedPxPerSec: Double,
        val atMs: Double,
        val horizontal: Boolean,
        val sign: Double,
        val originX: Double,
        val originY: Double,
    )

    private val history = ArrayDeque<Sample>()
    private var pending: Pending? = null

    private var lastEmitMs = Double.NaN
    /** Start of the current continuously-settled period (frame-to-frame speed below threshold). */
    private var quietSinceMs = Double.NaN

    data class Flick(
        val direction: FlickDirection,
        val dx: Double,
        val dy: Double,
        /** Peak speed of the movement inside the window, px/s. */
        val speedPxPerSec: Double,
        val tsMs: Double,
    )

    /**
     * Feed the steadied gaze (dead-zone + filtered cursor position) in screen px.
     * Returns a flick only on the frame that confirms it.
     */
    fun update(x: Double, y: Double, tsMs: Double): Flick? {
        if (!x.isFinite() || !y.isFinite()) return null

        history.addLast(Sample(tsMs, x, y))
        while (history.isNotEmpty() && tsMs - history.first().t > HISTORY_MS) history.removeFirst()

        val window = history.filter { tsMs - it.t <= maxWindowMs }
        if (window.size < 2) return null
        val reference = window.first()
        if (tsMs - reference.t < MIN_SPAN_MS) return null

        val last = window[window.size - 1]
        val previous = window[window.size - 2]
        val lastSpan = (last.t - previous.t).coerceAtLeast(1.0)
        val recentSpeed = hypot(last.x - previous.x, last.y - previous.y) / (lastSpan / 1000.0)
        // Was the gaze continuously settled before this sample's step? Check before updating.
        val continuouslySettled = !quietSinceMs.isNaN() && tsMs - quietSinceMs >= settleWindowMs
        if (recentSpeed >= rearmSpeedPxPerSec) {
            quietSinceMs = Double.NaN
        } else if (quietSinceMs.isNaN()) {
            // Begin a quiet period at the start of this slow step.
            quietSinceMs = previous.t
        }

        // ---- 1. Is a candidate being held? ----
        val candidate = pending
        if (candidate != null) {
            if (tsMs - candidate.atMs > confirmTimeoutMs) {
                pending = null
                return null
            }
            val heldX = x - candidate.originX
            val heldY = y - candidate.originY
            val along = if (candidate.horizontal) heldX * candidate.sign else heldY * candidate.sign
            val heldDistance = hypot(heldX, heldY)
            if (along < snapBackFraction * minDisplacementPx) {
                // snapped back: this was noise
                pending = null
                return null
            }
            if (heldDistance >= confirmFraction * minDisplacementPx &&
                along >= confirmFraction * minDisplacementPx
            ) {
                pending = null
                lastEmitMs = tsMs
                // Require a fresh continuous-settle period before another flick can start.
                quietSinceMs = Double.NaN
                return Flick(
                    candidate.direction,
                    candidate.dx,
                    candidate.dy,
                    candidate.speedPxPerSec,
                    tsMs,
                )
            }
            return null
        }

        // ---- 2. May a new flick start? ----
        val coolingDown = !lastEmitMs.isNaN() && tsMs - lastEmitMs < cooldownMs
        if (coolingDown) return null
        // Must have been continuously still before this movement: constant jitter/drift can never arm.
        if (!continuouslySettled) return null
        // After an emit, require the current quiet period to have started after that emit.
        if (!lastEmitMs.isNaN() && (quietSinceMs.isNaN() || quietSinceMs < lastEmitMs)) return null

        val dx = x - reference.x
        val dy = y - reference.y
        val distance = hypot(dx, dy)

        var peakSpeed = 0.0
        for (i in window.indices) {
            for (j in i + 1 until window.size) {
                val span = window[j].t - window[i].t
                if (span < MIN_SPAN_MS) continue
                val d = hypot(window[j].x - window[i].x, window[j].y - window[i].y)
                val s = d / (span / 1000.0)
                if (s > peakSpeed) peakSpeed = s
            }
        }
        if (distance < minDisplacementPx || peakSpeed < minSpeedPxPerSec) return null

        val horizontal = abs(dx) >= abs(dy)
        val dominance = if (horizontal) {
            abs(dx) / abs(dy).coerceAtLeast(1e-6)
        } else {
            abs(dy) / abs(dx).coerceAtLeast(1e-6)
        }
        if (dominance < minAxisDominance) return null

        var pathLength = 0.0
        for (i in 1 until window.size) {
            pathLength += hypot(
                window[i].x - window[i - 1].x,
                window[i].y - window[i - 1].y,
            )
        }
        val straightness = if (pathLength > 0.0) distance / pathLength else 0.0
        if (straightness < minStraightness) return null

        val sign = if (horizontal) {
            if (dx < 0) -1.0 else 1.0
        } else {
            if (dy < 0) -1.0 else 1.0
        }
        var along = 0.0
        var alongAbs = 0.0
        for (i in 1 until window.size) {
            val step = if (horizontal) {
                window[i].x - window[i - 1].x
            } else {
                window[i].y - window[i - 1].y
            }
            alongAbs += abs(step)
            if (step * sign > 0) along += abs(step)
        }
        val monotonicity = if (alongAbs > 0.0) along / alongAbs else 0.0
        if (monotonicity < minMonotonicity) return null

        val direction = if (horizontal) {
            if (dx < 0) FlickDirection.LEFT else FlickDirection.RIGHT
        } else {
            // screen y grows downwards, so a look up is a negative dy
            if (dy < 0) FlickDirection.UP else FlickDirection.DOWN
        }
        pending = Pending(
            direction = direction,
            dx = dx,
            dy = dy,
            speedPxPerSec = peakSpeed,
            atMs = tsMs,
            horizontal = horizontal,
            sign = sign,
            originX = reference.x,
            originY = reference.y,
        )
        return null
    }

    fun reset() {
        history.clear()
        pending = null
        lastEmitMs = Double.NaN
        quietSinceMs = Double.NaN
    }

    private companion object {
        const val HISTORY_MS = 800.0
        const val MIN_SPAN_MS = 20.0
    }
}
