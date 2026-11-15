package com.gazetap.app.gaze

import kotlin.math.hypot

/**
 * I-DT (dispersion-threshold) fixation detection.
 *
 * Why I-DT: it needs only position and time — no velocity model to calibrate — and it maps directly
 * onto dwell clicking ("is the gaze inside a small window for T ms?"). Samples in a short sliding
 * window are checked against their own centroid, which tolerates a slow drift but rejects a saccade.
 */
class IdtFixationDetector(
    var dispersionThresholdPx: Double = 30.0,
    var windowMs: Double = 200.0,
) {
    private data class Sample(val x: Double, val y: Double, val tsMs: Double)

    private val samples = ArrayDeque<Sample>()

    data class State(
        val isFixating: Boolean,
        val centroidX: Double,
        val centroidY: Double,
        val durationMs: Double,
    )

    fun update(x: Double, y: Double, tsMs: Double): State {
        samples.addLast(Sample(x, y, tsMs))
        while (samples.isNotEmpty() && tsMs - samples.first().tsMs > windowMs) {
            samples.removeFirst()
        }
        if (samples.isEmpty()) return State(false, x, y, 0.0)

        val cx = samples.sumOf { it.x } / samples.size
        val cy = samples.sumOf { it.y } / samples.size
        val maxDispersion = samples.maxOf { hypot(it.x - cx, it.y - cy) }
        val isFixating = samples.size >= 2 && maxDispersion <= dispersionThresholdPx
        return State(isFixating, cx, cy, tsMs - samples.first().tsMs)
    }

    fun reset() = samples.clear()
}

/**
 * Dwell click with a shrink-in-place ring.
 *
 * Why a ring: users need to know that looking somewhere is *doing something*; a shrinking ring shows
 * the remaining dwell time without requiring a second target.
 *
 * Firing latches: after a click the state stays latched until the gaze leaves [releaseRadiusPx].
 * Without that latch a user who keeps looking would fire a new click every dwellMs, which produces
 * accidental repeated taps (and wrecks click precision) on any real screen.
 */
class DwellClicker(
    /** Long enough to be intentional: a short dwell fired while the user was still settling. */
    var dwellMs: Double = 1100.0,
    var releaseRadiusPx: Double = 45.0,
) {
    private var anchorX = Double.NaN
    private var anchorY = Double.NaN
    private var startMs = Double.NaN
    private var fired = false

    data class Progress(
        val active: Boolean,
        val progress: Double, // 0..1
        val x: Double,
        val y: Double,
        val clicked: Boolean,
    )

    fun update(x: Double, y: Double, tsMs: Double): Progress {
        if (anchorX.isNaN()) {
            anchorX = x; anchorY = y; startMs = tsMs; fired = false
            return Progress(true, 0.0, x, y, false)
        }

        val moved = hypot(x - anchorX, y - anchorY)

        // Latched after a click: ignore everything until the user looks away.
        if (fired) {
            if (moved > releaseRadiusPx) {
                anchorX = x; anchorY = y; startMs = tsMs; fired = false
                return Progress(true, 0.0, x, y, false)
            }
            return Progress(false, 0.0, anchorX, anchorY, false)
        }

        if (moved > releaseRadiusPx) {
            anchorX = x; anchorY = y; startMs = tsMs
            return Progress(true, 0.0, x, y, false)
        }

        val progress = ((tsMs - startMs) / dwellMs).coerceIn(0.0, 1.0)
        if (progress >= 1.0) {
            fired = true
            return Progress(false, 1.0, anchorX, anchorY, true)
        }
        return Progress(true, progress, anchorX, anchorY, false)
    }

    fun reset() {
        anchorX = Double.NaN; anchorY = Double.NaN; startMs = Double.NaN; fired = false
    }
}
