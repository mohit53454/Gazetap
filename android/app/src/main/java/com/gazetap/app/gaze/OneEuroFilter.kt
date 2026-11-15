package com.gazetap.app.gaze

import kotlin.math.PI
import kotlin.math.abs

/**
 * One Euro filter (Casiez et al. 2012).
 *
 * Why: an adaptive low-pass filter. When the signal is slow (fixation) it smooths hard giving low
 * jitter; when it moves fast (saccade) it raises the cutoff giving low lag. A fixed low-pass cannot
 * do both — that trade-off is exactly what makes cursor control feel either jittery or laggy.
 */
class LowPassFilter(private val alpha: Double) {
    private var initialized = false
    private var y = 0.0

    fun filter(value: Double, alpha: Double = this.alpha): Double {
        if (!initialized) {
            initialized = true
            y = value
            return y
        }
        y = alpha * value + (1.0 - alpha) * y
        return y
    }

    fun lastValue(): Double = y
    fun reset() { initialized = false; y = 0.0 }
}

/**
 * @param minCutoff cutoff (Hz) at zero speed. Lower = steadier fixation, more lag overall.
 * @param beta speed coefficient (Hz per px/s). Higher = less lag on fast moves, more jitter.
 * @param dCutoff cutoff (Hz) for the derivative estimate.
 */
class OneEuroFilter(
    var minCutoff: Double = 1.0,
    var beta: Double = 0.5,
    var dCutoff: Double = 1.0,
) {
    private val xFilter = LowPassFilter(1.0)
    private val dxFilter = LowPassFilter(1.0)
    private var lastValue = 0.0
    private var lastTimestamp = Double.NaN

    private fun alpha(cutoff: Double, dt: Double): Double {
        val tau = 1.0 / (2.0 * PI * cutoff)
        return 1.0 / (1.0 + tau / dt)
    }

    fun filter(value: Double, timestampSec: Double): Double {
        if (lastTimestamp.isNaN() || timestampSec.isNaN() || timestampSec <= lastTimestamp) {
            lastTimestamp = timestampSec
            lastValue = value
            xFilter.filter(value, 1.0)
            dxFilter.filter(0.0, 1.0)
            return value
        }
        val dt = timestampSec - lastTimestamp
        if (dt <= 0.0) return lastValue

        val dx = (value - lastValue) / dt
        val edx = dxFilter.filter(dx, alpha(dCutoff, dt))
        val cutoff = minCutoff + beta * abs(edx)
        val out = xFilter.filter(value, alpha(cutoff, dt))

        lastValue = value
        lastTimestamp = timestampSec
        return out
    }

    fun reset() {
        lastTimestamp = Double.NaN
        lastValue = 0.0
        xFilter.reset()
        dxFilter.reset()
    }
}

/** Convenience 2D wrapper for cursor positions in pixels. */
class OneEuroFilter2D(
    minCutoff: Double = 0.9,
    beta: Double = 0.35,
    dCutoff: Double = 1.0,
) {
    private val fx = OneEuroFilter(minCutoff, beta, dCutoff)
    private val fy = OneEuroFilter(minCutoff, beta, dCutoff)

    fun filter(x: Double, y: Double, timestampSec: Double): DoubleArray =
        doubleArrayOf(fx.filter(x, timestampSec), fy.filter(y, timestampSec))

    fun setParams(minCutoff: Double, beta: Double) {
        fx.minCutoff = minCutoff; fy.minCutoff = minCutoff
        fx.beta = beta; fy.beta = beta
    }

    fun reset() { fx.reset(); fy.reset() }
}

/**
 * Saccade detector: true when recent speed exceeds the threshold, so the pipeline can reduce
 * smoothing for the jump instead of fighting it (fixation smoothing otherwise blurs saccades).
 */
class SaccadeDetector(
    var speedThresholdPxPerSec: Double = 900.0,
) {
    private var lastX = Double.NaN
    private var lastY = Double.NaN
    private var lastTsMs = Double.NaN

    fun update(x: Double, y: Double, tsMs: Double): Boolean {
        if (lastX.isNaN() || tsMs <= lastTsMs) {
            lastX = x; lastY = y; lastTsMs = tsMs
            return false
        }
        val dt = (tsMs - lastTsMs) / 1000.0
        val dist = kotlin.math.hypot(x - lastX, y - lastY)
        lastX = x; lastY = y; lastTsMs = tsMs
        if (dt <= 0.0) return false
        return dist / dt >= speedThresholdPxPerSec
    }

    fun reset() { lastX = Double.NaN; lastY = Double.NaN; lastTsMs = Double.NaN }
}
