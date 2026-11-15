package com.gazetap.app.gaze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GazeFeaturesTest {

    private fun blankMesh(): Pair<FloatArray, FloatArray> {
        val n = GazeFeatures.MIN_LANDMARKS
        return FloatArray(n) { 0.5f } to FloatArray(n) { 0.5f }
    }

    /** Puts both eyes on a known box and the irises at a chosen offset (in eye-width/height units). */
    private fun meshWithIris(rightDx: Double, rightDy: Double, leftDx: Double, leftDy: Double): Pair<FloatArray, FloatArray> {
        val (xs, ys) = blankMesh()
        // right eye: corners at x 0.4 / 0.6 (width 0.2), lids at y 0.45 / 0.55 (height 0.1)
        xs[GazeFeatures.RIGHT_EYE_OUTER] = 0.4f
        xs[GazeFeatures.RIGHT_EYE_INNER] = 0.6f
        ys[GazeFeatures.RIGHT_EYE_TOP] = 0.45f
        ys[GazeFeatures.RIGHT_EYE_BOTTOM] = 0.55f
        xs[GazeFeatures.RIGHT_IRIS_CENTER] = (0.5 + 0.2 * rightDx).toFloat()
        ys[GazeFeatures.RIGHT_IRIS_CENTER] = (0.5 + 0.1 * rightDy).toFloat()

        // left eye: corners at x 0.6 / 0.8 (width 0.2), same lids
        xs[GazeFeatures.LEFT_EYE_OUTER] = 0.8f
        xs[GazeFeatures.LEFT_EYE_INNER] = 0.6f
        ys[GazeFeatures.LEFT_EYE_TOP] = 0.45f
        ys[GazeFeatures.LEFT_EYE_BOTTOM] = 0.55f
        xs[GazeFeatures.LEFT_IRIS_CENTER] = (0.7 + 0.2 * leftDx).toFloat()
        ys[GazeFeatures.LEFT_IRIS_CENTER] = (0.5 + 0.1 * leftDy).toFloat()
        return xs to ys
    }

    @Test
    fun `centred irises give zero features`() {
        val (xs, ys) = meshWithIris(0.0, 0.0, 0.0, 0.0)
        val f = GazeFeatures.extract(xs, ys)!!
        assertEquals(4, f.size)
        for (v in f) assertEquals(0.0, v, 1e-6)
    }

    @Test
    fun `iris offsets are normalised by eye size`() {
        val (xs, ys) = meshWithIris(0.25, -0.5, -0.25, 0.5)
        val f = GazeFeatures.extract(xs, ys)!!
        assertEquals(0.25, f[0], 1e-5)
        assertEquals(-0.5, f[1], 1e-5)
        assertEquals(-0.25, f[2], 1e-5)
        assertEquals(0.5, f[3], 1e-5)
    }

    @Test
    fun `features are independent of face scale`() {
        // same relative iris position, eyes twice as wide and tall
        fun mesh(scale: Float): Pair<FloatArray, FloatArray> {
            val (xs, ys) = blankMesh()
            fun eye(outer: Int, inner: Int, top: Int, bottom: Int, iris: Int, cx: Float) {
                val halfW = 0.1f * scale
                val halfH = 0.05f * scale
                xs[outer] = cx - halfW
                xs[inner] = cx + halfW
                ys[top] = 0.5f - halfH
                ys[bottom] = 0.5f + halfH
                xs[iris] = cx + halfW * 0.5f   // quarter eye-width toward the inner corner
                ys[iris] = 0.5f
            }
            eye(
                GazeFeatures.RIGHT_EYE_OUTER, GazeFeatures.RIGHT_EYE_INNER,
                GazeFeatures.RIGHT_EYE_TOP, GazeFeatures.RIGHT_EYE_BOTTOM,
                GazeFeatures.RIGHT_IRIS_CENTER, 0.45f
            )
            eye(
                GazeFeatures.LEFT_EYE_OUTER, GazeFeatures.LEFT_EYE_INNER,
                GazeFeatures.LEFT_EYE_TOP, GazeFeatures.LEFT_EYE_BOTTOM,
                GazeFeatures.LEFT_IRIS_CENTER, 0.65f
            )
            return xs to ys
        }

        val smallMesh = mesh(0.5f)
        val bigMesh = mesh(1.0f)
        val small = GazeFeatures.extract(smallMesh.first, smallMesh.second)!!
        val big = GazeFeatures.extract(bigMesh.first, bigMesh.second)!!
        assertEquals(4, small.size)
        for (i in 0 until 4) {
            assertEquals("feature $i must not depend on face scale", small[i], big[i], 1e-5)
        }
    }

    @Test
    fun `mesh without iris points is rejected`() {
        val xs = FloatArray(468) { 0.5f }
        val ys = FloatArray(468) { 0.5f }
        assertNull(GazeFeatures.extract(xs, ys))
    }

    @Test
    fun `degenerate eye width is rejected`() {
        val (xs, ys) = blankMesh()
        xs[GazeFeatures.RIGHT_EYE_OUTER] = 0.5f
        xs[GazeFeatures.RIGHT_EYE_INNER] = 0.5f
        assertNull(GazeFeatures.extract(xs, ys))
    }
}

class CalibrationSessionTest {

    /** Synthetic user whose gaze maps linearly onto the screen. */
    private fun featuresFor(targetX: Double, targetY: Double, w: Double, h: Double): DoubleArray {
        val fx = (targetX / w - 0.5) * 0.5
        val fy = (targetY / h - 0.5) * 0.5
        return doubleArrayOf(fx, fy, fx, fy)
    }

    private fun runNinePointCalibration(w: Double = 1000.0, h: Double = 2000.0): CalibrationSession {
        val session = CalibrationSession(w, h)
        var guard = 0
        while (!session.isComplete && guard < 10_000) {
            val t = session.currentTarget!!
            repeat(3) { session.addSample(featuresFor(t.first, t.second, w, h)) }
            guard++
        }
        assertTrue("calibration should complete", session.isComplete)
        return session
    }

    @Test
    fun `collects all nine points then fits with a small residual`() {
        val w = 1000.0
        val h = 2000.0
        val session = runNinePointCalibration(w, h)
        val model = session.fit()
        val residual = session.residualPx(model)
        assertTrue("residual should be tens of px at most, got $residual", residual < 40.0)
        assertTrue(model.isFitted())
    }

    @Test
    fun `targets match the twenty five point layout`() {
        val session = CalibrationSession(1000.0, 1000.0)
        assertEquals(25, session.totalPoints)
        assertEquals(80.0, session.targets.first().first, 1e-6)
        assertEquals(920.0, session.targets.last().first, 1e-6)
        assertEquals(920.0, session.targets.last().second, 1e-6)
    }

    @Test
    fun `sixteen points fit the true mapping better than nine`() {
        val w = 1000.0
        val h = 2000.0

        fun residualFor(fractions: List<Double>): Double {
            val points = buildList { for (r in fractions) for (c in fractions) add(c to r) }
            val session = CalibrationSession(
                w, h, minSamplesPerPoint = 3, settleSamplesPerPoint = 1,
                stabilityWindow = 3, pointsNormalized = points
            )
            var guard = 0
            while (!session.isComplete && guard < 20000) {
                val t = session.currentTarget!!
                session.addSample(featuresFor(t.first, t.second, w, h))
                guard++
            }
            assertTrue(session.isComplete)
            val model = session.fit()
            return session.residualPx(model)
        }

        val nine = residualFor(listOf(0.1, 0.5, 0.9))
        val sixteen = residualFor(listOf(0.08, 0.29, 0.50, 0.71, 0.92))
        println("RESIDUAL nine=%.3f sixteen=%.3f".format(nine, sixteen))
        assertTrue(
            "25 points ($sixteen px) should beat 9 points ($nine px)",
            sixteen < nine
        )
    }

    @Test
    fun `settle frames are discarded so saccade samples do not pollute the point`() {
        val session = CalibrationSession(
            1000.0, 1000.0,
            minSamplesPerPoint = 5, settleSamplesPerPoint = 3, stabilityWindow = 5
        )
        val junk = doubleArrayOf(99.0, 99.0, 99.0, 99.0)
        repeat(3) { session.addSample(junk) }
        assertEquals("nothing collected during settle frames", 0, session.sampleCount)
        // 5 to fill the stability window + 5 accepted samples
        repeat(15) { session.addSample(doubleArrayOf(0.1, 0.2, 0.1, 0.2)) }
        assertEquals("advanced to the second point", 1, session.currentPointIndex)
    }

    @Test
    fun `moving eyes are not sampled until the gaze holds still`() {
        val session = CalibrationSession(
            1000.0, 1000.0,
            minSamplesPerPoint = 3, settleSamplesPerPoint = 1, stabilityWindow = 5
        )
        // a continuous saccade: alternating far-apart positions every frame
        repeat(40) { i ->
            val sign = if (i % 2 == 0) 1.0 else -1.0
            session.addSample(doubleArrayOf(sign * 0.4, sign * 0.4, sign * 0.4, sign * 0.4))
        }
        assertEquals("no samples may be collected while the gaze is moving", 0, session.sampleCount)
        assertEquals("still on the first point", 0, session.currentPointIndex)
    }

    @Test
    fun `a flat point advances instead of repeating, and a long flat run stops the calibration`() {
        val session = CalibrationSession(
            1000.0, 1000.0,
            minSamplesPerPoint = 3, settleSamplesPerPoint = 1, stabilityWindow = 3,
            minCentroidDistance = 0.02, maxConsecutiveRejections = 4
        )
        // The user never moves: the first point is accepted (no reference yet), every later point is
        // flagged flat. It must still advance - re-showing the dot is what made a whole run look like
        // it had restarted itself - while the run stops after a few of them.
        var guard = 0
        while (!session.isAborted && guard < 2000) {
            session.addSample(doubleArrayOf(0.01, 0.02, 0.01, 0.02))
            guard++
        }
        assertTrue("a run without eye movement must stop", session.isAborted)
        assertEquals("every flat point still advanced the grid", 1 + 4, session.currentPointIndex)
        assertEquals("each point was visited exactly once", 5, session.fitPointCount())
        assertEquals("flat points are counted for the report", 4, session.flatPoints)
        assertTrue("the last point is reported as flat", !session.lastPointAccepted)
    }

    @Test
    fun `a small but real eye step is not treated as flat`() {
        // The real-device case: one 5x5 step on an iris range of ~0.03 is ~0.007 per dimension, far
        // below the old 0.02 threshold, but it is a real move and must not be flagged.
        val session = CalibrationSession(
            1000.0, 1000.0,
            minSamplesPerPoint = 3, settleSamplesPerPoint = 1, stabilityWindow = 3
        )
        var step = 0.0
        var guard = 0
        while (session.currentPointIndex < 3 && guard < 500) {
            session.addSample(doubleArrayOf(step, step, step, step))
            if (session.currentPointIndex > 0) step += 0.007
            guard++
        }
        assertTrue("three points must complete", session.currentPointIndex >= 3)
        assertEquals("no point is flat at 0.007 per step", 0, session.flatPoints)
    }

    @Test
    fun `a point is accepted again once the eyes actually move`() {
        val session = CalibrationSession(
            1000.0, 1000.0,
            minSamplesPerPoint = 3, settleSamplesPerPoint = 1, stabilityWindow = 3,
            minCentroidDistance = 0.02
        )
        // point 1
        feed(session, doubleArrayOf(0.0, 0.0, 0.0, 0.0))
        assertEquals(1, session.currentPointIndex)
        // point 2: eyes moved -> accepted, rejections reset
        feed(session, doubleArrayOf(0.2, 0.1, 0.2, 0.1))
        assertEquals(2, session.currentPointIndex)
        assertEquals(0, session.consecutiveRejections)
        assertTrue(session.lastPointAccepted)
    }

    private fun feed(session: CalibrationSession, f: DoubleArray) {
        var guard = 0
        val start = session.currentPointIndex
        while (session.currentPointIndex == start && !session.isAborted && guard < 500) {
            session.addSample(f)
            guard++
        }
    }

    @Test
    fun `feature spread is large when the eyes moved between points`() {
        val session = CalibrationSession(
            1000.0, 1000.0,
            minSamplesPerPoint = 3, settleSamplesPerPoint = 1, stabilityWindow = 3
        )
        var guard = 0
        while (!session.isComplete && guard < 5000) {
            val t = session.currentTarget!!
            val idx = session.currentPointIndex
            val f = featuresFor(t.first, t.second, 1000.0, 1000.0)
            session.addSample(f)
            guard++
            if (idx < 0) break
        }
        assertTrue(session.isComplete)
        assertTrue(
            "spread must be well above the 0.03 rejection threshold (was ${session.featureSpread()})",
            session.featureSpread() > 0.05
        )
    }

    @Test
    fun `residual is reported in pixels for a well fitted mapping`() {
        val w = 1000.0
        val h = 2000.0
        val session = runNinePointCalibration(w, h)
        val model = session.fit()
        // predictions must land inside the screen area
        val p = model.predict(featuresFor(500.0, 1000.0, w, h))
        assertTrue("x in screen", abs(p[0] - 500.0) < 120.0)
        assertTrue("y in screen", abs(p[1] - 1000.0) < 200.0)
    }

    @Test
    fun `fits on the source that actually moved`() {
        // The real-device case: iris landmarks ride along with the head, eyeLook varies with gaze.
        val w = 1000.0
        val h = 1000.0
        val session = CalibrationSession(
            w, h, minSamplesPerPoint = 3, settleSamplesPerPoint = 1, stabilityWindow = 3
        )
        val frozenIris = doubleArrayOf(0.01, 0.02, 0.01, 0.02)
        var guard = 0
        while (!session.isComplete && guard < 5000) {
            val t = session.currentTarget!!
            val fx = (t.first / w - 0.5) * 0.4
            val fy = (t.second / h - 0.5) * 0.4
            session.addSample(frozenIris, doubleArrayOf(fx, fy, fx, fy))
            guard++
        }
        assertTrue("calibration completes on eyeLook alone", session.isComplete)
        val model = session.fit()
        assertEquals(RidgePolynomialModel.SOURCE_EYE_LOOK, model.featureSource)
        assertTrue(model.usesEyeLook)
        val residual = session.residualPx(model)
        assertTrue("eyeLook fit should be usable, got $residual px", residual < 60.0)
    }

    @Test
    fun `iris is used when no blendshapes are available`() {
        val model = runNinePointCalibration().fit()
        assertEquals(RidgePolynomialModel.SOURCE_IRIS, model.featureSource)
    }

    @Test
    fun `feature source survives a json round trip`() {
        val model = RidgePolynomialModel.fit(
            listOf(
                doubleArrayOf(0.0, 0.0, 0.0, 0.0),
                doubleArrayOf(0.1, 0.1, 0.1, 0.1),
                doubleArrayOf(0.2, -0.1, 0.2, -0.1),
                doubleArrayOf(-0.2, 0.05, -0.2, 0.05),
            ),
            doubleArrayOf(100.0, 400.0, 700.0, 250.0),
            doubleArrayOf(200.0, 800.0, 300.0, 600.0),
        )
        model.featureSource = RidgePolynomialModel.SOURCE_EYE_LOOK
        val restored = RidgePolynomialModel.fromJson(model.toJson())
        assertEquals(RidgePolynomialModel.SOURCE_EYE_LOOK, restored.featureSource)
        assertTrue(restored.usesEyeLook)
        assertTrue("weights survive", restored.isFitted())
    }

    @Test
    fun `combined vector averages the eyes and keeps head pose linear`() {
        val iris = doubleArrayOf(0.10, -0.20, 0.30, -0.40)
        val look = doubleArrayOf(0.50, 0.10, 0.70, 0.30)
        val v = GazeFeatures.combine(iris, look, 8.0, -4.0)!!
        assertEquals(GazeFeatures.COMBINED_COUNT, v.size)
        assertEquals(0.20, v[0], 1e-9)
        assertEquals(-0.30, v[1], 1e-9)
        assertEquals(0.60, v[2], 1e-9)
        assertEquals(0.20, v[3], 1e-9)
        assertEquals(0.20, v[4], 1e-9)
        assertEquals(-0.10, v[5], 1e-9)
        assertNull(GazeFeatures.combine(null, null, 0.0, 0.0))
    }

    @Test
    fun `hexa basis keeps head pose linear`() {
        val model = RidgePolynomialModel(GazeFeatures.COMBINED_COUNT, quadraticDims = 4)
        assertEquals(17, model.basisSize)
        assertEquals(17, model.expand(DoubleArray(6) { 0.1 }).size)
    }

    @Test
    fun `combined features fit a gaze plus head pose mapping`() {
        // Truth: screen position depends on gaze (quadratic) and head yaw (linear), which is the
        // situation where the old iris-only, pose-blind model produced 250-360 px residuals.
        val w = 1000.0
        val h = 2000.0
        val session = CalibrationSession(
            w, h, minSamplesPerPoint = 3, settleSamplesPerPoint = 1, stabilityWindow = 3,
            quadraticDims = GazeFeatures.COMBINED_QUADRATIC_DIMS,
        )
        var guard = 0
        while (!session.isComplete && guard < 20000) {
            val t = session.currentTarget!!
            val gx = (t.first / w - 0.5) * 0.5
            val gy = (t.second / h - 0.5) * 0.5
            val yaw = gx * 6.0
            val pitch = gy * 4.0
            val v = doubleArrayOf(gx, gy, gx * 0.8, gy * 0.8, yaw / 40.0, pitch / 40.0)
            session.addSample(v)
            guard++
        }
        assertTrue(session.isComplete)
        val model = session.fit()
        val residual = session.residualPx(model)
        assertTrue("combined fit residual should be small, got $residual px", residual < 60.0)
    }
}
