package com.gazetap.app.gaze

import kotlin.math.hypot

/**
 * Turns the noisy per-frame gaze estimate into a pointer that behaves like a mouse.
 *
 * Three problems have to be solved at once, and each used to be "fixed" by breaking another:
 *  1. Frame-to-frame noise darts the dot around while the user is holding still.
 *  2. Any smoothing strong enough to hide (1) adds lag, so real eye movement feels late.
 *  3. Snapping to the raw value to remove that lag makes the dot jump between points.
 *
 * The fix is a quiet zone instead of heavy smoothing:
 *  - Inside [quietRadiusPx] the pointer does not move at all. Ordinary gaze jitter, hand tremor
 *    and breathing all stay inside it, so the dot is perfectly still at rest with no lag at all.
 *  - Outside it, the dead zone is subtracted from the step and the rest is passed through, so the
 *    pointer moves essentially 1:1 with the eye. There is no discontinuity at the boundary: a
 *    movement of `quietRadius + n` px produces exactly `n` px of motion.
 *
 * On top of that a low-pass ([OneEuroFilter2D]) removes what is left of single-frame noise; its
 * cutoff rises with speed, so it never fights a real movement.
 */
class GazeCursorSmoother(
    var quietRadiusPx: Double = 18.0,
    minCutoff: Double = 1.2,
    beta: Double = 0.8,
    dCutoff: Double = 1.0,
) {
    private val filter = OneEuroFilter2D(minCutoff, beta, dCutoff)
    private var anchorX = Double.NaN
    private var anchorY = Double.NaN

    /**
     * @param x steady (median filtered) gaze position in screen px
     * @param timestampSec monotonic time in seconds
     */
    fun filter(x: Double, y: Double, timestampSec: Double): DoubleArray {
        if (!x.isFinite() || !y.isFinite()) return lastValue()
        if (anchorX.isNaN()) {
            anchorX = x
            anchorY = y
            filter.reset()
            return doubleArrayOf(x, y)
        }

        val dx = x - anchorX
        val dy = y - anchorY
        val distance = hypot(dx, dy)

        val outX: Double
        val outY: Double
        if (distance <= quietRadiusPx) {
            // Holding still: keep the pointer exactly where it is.
            outX = anchorX
            outY = anchorY
        } else {
            // Real movement: pass everything beyond the quiet zone through, so speed and
            // direction match the eye instead of lagging behind a heavy average.
            val scale = (distance - quietRadiusPx) / distance
            outX = anchorX + dx * scale
            outY = anchorY + dy * scale
            anchorX = outX
            anchorY = outY
        }
        return filter.filter(outX, outY, timestampSec)
    }

    /** Last emitted position, or NaN before the first sample. */
    fun lastValue(): DoubleArray = doubleArrayOf(anchorX, anchorY)

    fun reset() {
        anchorX = Double.NaN
        anchorY = Double.NaN
        filter.reset()
    }
}
