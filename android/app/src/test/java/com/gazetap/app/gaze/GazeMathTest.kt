package com.gazetap.app.gaze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.random.Random

class OneEuroFilterTest {

    private fun rms(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        return sqrt(values.sumOf { it * it } / values.size)
    }

    @Test
    fun `reduces jitter on a static signal`() {
        val raw = mutableListOf<Double>()
        val filtered = mutableListOf<Double>()
        val f = OneEuroFilter(minCutoff = 0.8, beta = 0.0, dCutoff = 1.0)
        val rnd = Random(7)
        val truth = 500.0
        var t = 0.0
        repeat(300) {
            t += 1.0 / 30.0
            val noisy = truth + rnd.nextDouble(-4.0, 4.0)
            raw.add(noisy)
            filtered.add(f.filter(noisy, t) - truth)
        }
        val rawJitter = rms(raw.drop(30).map { it - truth })
        val filtJitter = rms(filtered.drop(30))
        assertTrue("filtered jitter ($filtJitter) should be well below raw ($rawJitter)", filtJitter < rawJitter * 0.7)
    }

    @Test
    fun `tracks a fast saccade with bounded lag`() {
        val f = OneEuroFilter(minCutoff = 0.9, beta = 0.6)
        var t = 0.0
        repeat(60) { t += 1.0 / 30.0; f.filter(100.0, t) }
        var reached = 0.0
        var lagFrames = -1
        repeat(30) { i ->
            t += 1.0 / 30.0
            reached = f.filter(500.0, t)
            if (lagFrames < 0 && kotlin.math.abs(reached - 500.0) < 25.0) lagFrames = i + 1
        }
        assertEquals("should converge near the new target", 500.0, reached, 15.0)
        assertTrue("should get within 25 px quickly (got $lagFrames frames)", lagFrames in 1..10)
    }

    @Test
    fun `2d filter keeps axes independent`() {
        val f = OneEuroFilter2D()
        var t = 0.0
        var last = doubleArrayOf(0.0, 0.0)
        repeat(50) {
            t += 1.0 / 30.0
            last = f.filter(300.0, 700.0, t)
        }
        assertEquals(300.0, last[0], 5.0)
        assertEquals(700.0, last[1], 5.0)
    }

    @Test
    fun `saccade detector fires on a jump and not on a fixation`() {
        val d = SaccadeDetector(speedThresholdPxPerSec = 900.0)
        assertTrue(!d.update(100.0, 100.0, 0.0))
        var saccades = 0
        var t = 0.0
        repeat(10) { t += 33.0; if (d.update(101.0, 100.5, t)) saccades++ }
        assertEquals("tiny drift is not a saccade", 0, saccades)
        t += 33.0
        assertTrue("400 px in 33 ms is a saccade", d.update(500.0, 100.0, t))
    }
}

class FixationDwellTest {

    @Test
    fun `idt detects a fixation from a jittery still point`() {
        val det = IdtFixationDetector(dispersionThresholdPx = 30.0, windowMs = 200.0)
        val rnd = Random(3)
        var t = 0.0
        var last = IdtFixationDetector.State(false, 0.0, 0.0, 0.0)
        repeat(40) {
            t += 33.0
            last = det.update(600.0 + rnd.nextDouble(-5.0, 5.0), 400.0 + rnd.nextDouble(-5.0, 5.0), t)
        }
        assertTrue("should be fixating", last.isFixating)
        assertEquals(600.0, last.centroidX, 6.0)
        assertEquals(400.0, last.centroidY, 6.0)
    }

    @Test
    fun `idt rejects a saccade`() {
        val det = IdtFixationDetector(dispersionThresholdPx = 30.0, windowMs = 200.0)
        var t = 0.0
        det.update(100.0, 100.0, t)
        t += 16.0; det.update(300.0, 300.0, t)
        t += 16.0
        val s = det.update(600.0, 500.0, t)
        assertTrue("large spread should not be a fixation", !s.isFixating)
    }

    @Test
    fun `dwell fires once after dwellMs and does not repeat while holding`() {
        val clicker = DwellClicker(dwellMs = 800.0, releaseRadiusPx = 45.0)
        var clicked = 0
        var t = 0.0
        // hold for 3 s: must click exactly once, then latch
        repeat(90) {
            t += 33.0
            if (clicker.update(500.0, 500.0, t).clicked) clicked++
        }
        assertEquals("holding must produce exactly one click, not one per dwell period", 1, clicked)
    }

    @Test
    fun `dwell restarts after the gaze leaves the release radius`() {
        val clicker = DwellClicker(dwellMs = 300.0, releaseRadiusPx = 45.0)
        var clicked = 0
        var t = 0.0
        repeat(15) { t += 33.0; if (clicker.update(500.0, 500.0, t).clicked) clicked++ }
        assertEquals("first click on first fixation", 1, clicked)

        // look away: latch releases
        t += 33.0
        val away = clicker.update(800.0, 800.0, t)
        assertTrue("looking away must not click", !away.clicked)

        repeat(15) { t += 33.0; if (clicker.update(800.0, 800.0, t).clicked) clicked++ }
        assertEquals("second click on second fixation", 2, clicked)
    }

    @Test
    fun `dwell progress increases while active and before firing`() {
        val clicker = DwellClicker(dwellMs = 500.0, releaseRadiusPx = 60.0)
        var t = 0.0
        var prev = -1.0
        repeat(12) {
            t += 33.0
            val p = clicker.update(100.0, 100.0, t)
            if (p.active && !p.clicked) {
                assertTrue("progress must not decrease while active (${p.progress} after $prev)", p.progress >= prev - 1e-9)
                prev = p.progress
            }
        }
        assertTrue("progress should have advanced", prev > 0.4)
    }
}

class RidgePolynomialModelTest {

    /** Synthetic ground truth: screen position from two features, mildly quadratic. */
    private fun truth(f1: Double, f2: Double): Pair<Double, Double> {
        val x = 40.0 + 900.0 * f1 + 180.0 * f2 - 60.0 * f1 * f1
        val y = 80.0 + 700.0 * f2 + 120.0 * f1 * f1 + 50.0 * f1 * f2
        return x to y
    }

    private fun grid(n: Int = 3): Triple<List<DoubleArray>, DoubleArray, DoubleArray> {
        val feats = mutableListOf<DoubleArray>()
        val xs = mutableListOf<Double>()
        val ys = mutableListOf<Double>()
        val vals = (0 until n).map { 0.1 + 0.8 * it / (n - 1).toDouble() }
        for (a in vals) for (b in vals) {
            val f = doubleArrayOf(a, b)
            val (x, y) = truth(a, b)
            feats.add(f); xs.add(x); ys.add(y)
        }
        return Triple(feats, xs.toDoubleArray(), ys.toDoubleArray())
    }

    @Test
    fun `dense calibration fits the quadratic mapping to near-zero error`() {
        // 7x7 = 49 points is enough to identify 6 poly-2 coefficients on 2 features.
        val (f, xs, ys) = grid(n = 7)
        val model = RidgePolynomialModel.fit(f, xs, ys)
        assertTrue(model.isFitted())
        var maxErr = 0.0
        for (i in f.indices) {
            val p = model.predict(f[i])
            maxErr = maxOf(maxErr, hypot(p[0] - xs[i], p[1] - ys[i]))
        }
        assertTrue("dense fit should be near exact, max err=$maxErr px", maxErr < 2.0)
    }

    @Test
    fun `nine point calibration residual stays within a few degrees`() {
        // 9 points cannot identify 36 coefficients; honest expectation is a residual, not a perfect fit.
        val (f, xs, ys) = grid(n = 3)
        val model = RidgePolynomialModel.fit(f, xs, ys)
        var maxErr = 0.0
        for (i in f.indices) {
            val p = model.predict(f[i])
            maxErr = maxOf(maxErr, hypot(p[0] - xs[i], p[1] - ys[i]))
        }
        assertTrue("9-point residual should stay far below target size, got $maxErr px", maxErr < 80.0)

        val p = model.predict(doubleArrayOf(0.31, 0.72))
        val (tx, ty) = truth(0.31, 0.72)
        assertTrue("interpolation on unseen point should be plausible (${hypot(p[0] - tx, p[1] - ty)} px)",
            hypot(p[0] - tx, p[1] - ty) < 120.0)
    }

    @Test
    fun `loocv picks a lambda from the grid and reports an error`() {
        val (f, xs, ys) = grid()
        val (lambda, err) = RidgePolynomialModel.chooseLambda(
            f, xs, ys, lambdas = doubleArrayOf(0.001, 0.1, 1.0, 10.0, 100.0)
        )
        assertTrue("lambda from grid", lambda in setOf(0.001, 0.1, 1.0, 10.0, 100.0))
        assertTrue("LOOCV error finite and non-negative (got $err)", err.isFinite() && err >= 0.0)
    }

    @Test
    fun `survives collinear duplicated points thanks to regularization`() {
        val feats = mutableListOf<DoubleArray>()
        repeat(9) { feats.add(doubleArrayOf(0.4, 0.6)) }
        val xs = DoubleArray(9) { 500.0 }
        val ys = DoubleArray(9) { 400.0 }
        val model = RidgePolynomialModel.fit(feats, xs, ys, lambdas = doubleArrayOf(1.0))
        val p = model.predict(doubleArrayOf(0.4, 0.6))
        assertTrue("prediction stays finite", p[0].isFinite() && p[1].isFinite())
        assertEquals(500.0, p[0], 60.0)
    }

    @Test
    fun `json round trip preserves predictions`() {
        val (f, xs, ys) = grid()
        val model = RidgePolynomialModel.fit(f, xs, ys)
        val restored = RidgePolynomialModel.fromJson(model.toJson())
        val a = model.predict(f[4])
        val b = restored.predict(f[4])
        assertEquals(a[0], b[0], 1e-6)
        assertEquals(a[1], b[1], 1e-6)
        assertEquals(model.lambda, restored.lambda, 1e-9)
        assertEquals(model.featureCount, restored.featureCount)
    }

    @Test
    fun `gaussian solve handles a small system`() {
        val a = arrayOf(doubleArrayOf(2.0, 1.0), doubleArrayOf(1.0, 3.0))
        val b = doubleArrayOf(5.0, 10.0)
        val x = RidgePolynomialModel.gaussianSolve(a, b)
        assertEquals(1.0, x[0], 1e-9)
        assertEquals(3.0, x[1], 1e-9)
    }
}
