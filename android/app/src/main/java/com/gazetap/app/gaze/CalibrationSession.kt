package com.gazetap.app.gaze

import kotlin.math.sqrt

/**
 * Calibration state machine, fed with BOTH candidate feature vectors per frame.
 *
 * Why two sources: on the user's device the iris landmarks moved exactly as much as the nose (see
 * docs/PERFORMANCE.md), i.e. they carried head motion and no gaze. MediaPipe's eyeLook* blendshapes
 * are a separate regression head and still varied. Which one actually tracks the eye can only be
 * known at runtime, so calibration collects both and fits whichever varied more across the targets.
 *
 * Samples only count once the features are stable, so a saccade cannot pollute a point. A point whose
 * eyes did not move at all is flagged but still advances: the run must never show the same dot again,
 * because a repeated dot is indistinguishable from a calibration that restarted itself. A run whose
 * signal is genuinely absent stops after [maxConsecutiveRejections] flat points in a row, and the fit
 * stage refuses a model with no spread regardless.
 */
class CalibrationSession(
    val screenWidth: Double,
    val screenHeight: Double,
    val minSamplesPerPoint: Int = 30,
    val settleSamplesPerPoint: Int = 10,
    /** Max per-dimension standard deviation for a frame to count as "eye is holding still". */
    val maxFeatureStdDev: Double = 0.025,
    val stabilityWindow: Int = 5,
    /**
     * Floor of the movement test, per feature dimension.
     *
     * It used to be 0.02 compared as a Euclidean distance, which is larger than a whole 5x5 grid step
     * on a device whose entire iris range is ~0.03: legitimate points were discarded, the same dot
     * was shown two or three times, and a run aborted halfway. It now only has to separate "the eyes
     * moved" from "the signal is frozen".
     */
    val minCentroidDistance: Double = 0.002,
    /** Consecutive flat points before giving up: the eye signal is absent, not just small. */
    val maxConsecutiveRejections: Int = 8,
    pointsNormalized: List<Pair<Double, Double>> = DEFAULT_POINTS,
    /** Feature dims that get quadratic terms (see RidgePolynomialModel.quadraticDims). */
    val quadraticDims: Int = 0,
) {
    val targets: List<Pair<Double, Double>> = pointsNormalized.map {
        (it.first * screenWidth) to (it.second * screenHeight)
    }

    private val recent = ArrayDeque<DoubleArray>()
    private val collectedIris = mutableListOf<DoubleArray>()
    private val collectedLook = mutableListOf<DoubleArray>()
    private var framesOnPoint = 0
    private var pointIndex = 0

    private val fitIris = mutableListOf<DoubleArray>()
    private val fitLook = mutableListOf<DoubleArray>()
    private val fitTargets = mutableListOf<Pair<Double, Double>>()

    /** Feature source chosen by the last [fit]: [SOURCE_IRIS] or [SOURCE_EYE_LOOK]. */
    var chosenSource: String = RidgePolynomialModel.SOURCE_IRIS
        private set

    val totalPoints: Int get() = targets.size
    val currentPointIndex: Int get() = pointIndex
    val isComplete: Boolean get() = pointIndex >= targets.size
    val currentTarget: Pair<Double, Double>? get() = targets.getOrNull(pointIndex)

    /** Fraction of samples collected for the current target, 0..1. */
    val currentPointProgress: Double
        get() = (maxOf(collectedIris.size, collectedLook.size).toDouble() / minSamplesPerPoint)
            .coerceIn(0.0, 1.0)

    val sampleCount: Int get() = maxOf(collectedIris.size, collectedLook.size)

    /** True when the eye appears to be holding still, i.e. samples may be counted. */
    var isStable: Boolean = false
        private set

    /** False when the last completed point was thrown away because the eyes did not move. */
    var lastPointAccepted: Boolean = true
        private set

    /** Flat points in a row. Named for its old meaning; a flat point now advances the grid. */
    var consecutiveRejections: Int = 0
        private set

    /** Points whose eyes did not measurably move. Kept in the fit, but reported at the end. */
    var flatPoints: Int = 0
        private set

    /** True when too many points in a row showed no eye movement; the run should stop. */
    var isAborted: Boolean = false
        private set

    /** Iris-only convenience used by tests and by callers without blendshapes. */
    fun addSample(features: DoubleArray): Boolean = addSample(features, null)

    /**
     * Feed one frame. Either vector may be null (fast mode produces no blendshapes).
     * @return true when this sample completed the current calibration point
     */
    fun addSample(iris: DoubleArray?, look: DoubleArray?): Boolean {
        if (isComplete) return false
        val primary = iris ?: look ?: return false
        framesOnPoint++

        recent.addLast(primary)
        while (recent.size > stabilityWindow) recent.removeFirst()
        isStable = recent.size >= stabilityWindow && computeMaxStdDev() <= maxFeatureStdDev

        if (framesOnPoint <= settleSamplesPerPoint) return false
        if (!isStable) return false // saccade or jitter: wait, do not count

        if (iris != null) collectedIris.add(iris)
        if (look != null) collectedLook.add(look)
        if (maxOf(collectedIris.size, collectedLook.size) < minSamplesPerPoint) return false

        val centroidIris = if (collectedIris.isNotEmpty()) medianPerFeature(collectedIris) else null
        val centroidLook = if (collectedLook.isNotEmpty()) medianPerFeature(collectedLook) else null

        // Did the eyes move at all since the previous point? This has to catch a frozen signal only:
        // rejecting small-but-real movement is what made the dot repeat and the run abort.
        val threshold = movementThreshold(collectedIris)
        val movedIris = centroidIris != null &&
            (fitIris.lastOrNull()?.let { maxAbsDelta(centroidIris, it) > threshold } ?: true)
        val movedLook = centroidLook != null &&
            (fitLook.lastOrNull()?.let { maxAbsDelta(centroidLook, it) > threshold } ?: true)
        val moved = movedIris || movedLook
        lastPointAccepted = moved
        if (moved) {
            consecutiveRejections = 0
        } else {
            consecutiveRejections++
            flatPoints++
            if (consecutiveRejections >= maxConsecutiveRejections) isAborted = true
        }

        if (centroidIris != null) fitIris.add(centroidIris)
        if (centroidLook != null) fitLook.add(centroidLook)
        fitTargets.add(targets[pointIndex])
        collectedIris.clear()
        collectedLook.clear()
        framesOnPoint = 0
        recent.clear()
        isStable = false
        pointIndex++
        return true
    }

    /** Number of calibration points actually accepted so far. */
    fun fitPointCount(): Int = fitTargets.size

    /**
     * Fits the model on whichever feature source varied more across the accepted points.
     * Requires at least [MIN_FIT_POINTS] accepted points for the chosen source.
     */
    fun fit(): RidgePolynomialModel {
        val irisOk = fitIris.size >= MIN_FIT_POINTS && fitIris.size == fitTargets.size
        val lookOk = fitLook.size >= MIN_FIT_POINTS && fitLook.size == fitTargets.size
        require(irisOk || lookOk) { "need at least $MIN_FIT_POINTS completed calibration points" }

        val irisSpread = if (irisOk) spreadOf(fitIris) else 0.0
        val lookSpread = if (lookOk) spreadOf(fitLook) else 0.0
        val useLook = lookOk && (!irisOk || lookSpread > irisSpread)

        chosenSource = if (useLook) RidgePolynomialModel.SOURCE_EYE_LOOK else RidgePolynomialModel.SOURCE_IRIS
        val features = if (useLook) fitLook else fitIris
        val xs = DoubleArray(fitTargets.size) { fitTargets[it].first }
        val ys = DoubleArray(fitTargets.size) { fitTargets[it].second }
        val model = RidgePolynomialModel.fit(features, xs, ys, quadraticDims)
        model.featureSource = chosenSource
        return model
    }

    /** Mean Euclidean error of the fitted model on its own calibration points, in screen px. */
    fun residualPx(model: RidgePolynomialModel): Double {
        if (fitTargets.isEmpty()) return Double.NaN
        val features = if (model.featureSource == RidgePolynomialModel.SOURCE_EYE_LOOK) fitLook else fitIris
        if (features.size != fitTargets.size) return Double.NaN
        var total = 0.0
        for (i in features.indices) {
            val p = model.predict(features[i])
            total += kotlin.math.hypot(p[0] - fitTargets[i].first, p[1] - fitTargets[i].second)
        }
        return total / features.size
    }

    /**
     * Largest per-dimension range of the collected feature centroids across both sources. Near zero
     * means the eyes did not change while looking at different targets, so any model fitted from
     * them is meaningless.
     */
    fun featureSpread(): Double = maxOf(
        if (fitIris.size >= 2) spreadOf(fitIris) else 0.0,
        if (fitLook.size >= 2) spreadOf(fitLook) else 0.0,
    )

    /** Spread of one source only, for the on-device diagnostic line. */
    fun spreadOf(source: String): Double {
        val list = if (source == RidgePolynomialModel.SOURCE_EYE_LOOK) fitLook else fitIris
        return if (list.size >= 2) spreadOf(list) else 0.0
    }

    private fun spreadOf(points: List<DoubleArray>): Double {
        if (points.size < 2) return 0.0
        val dims = points[0].size
        var maxRange = 0.0
        for (d in 0 until dims) {
            val r = spreadOfDimension(points, d)
            if (r > maxRange) maxRange = r
        }
        return maxRange
    }

    private fun spreadOfDimension(points: List<DoubleArray>, d: Int): Double {
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        for (f in points) {
            if (d >= f.size) continue
            if (f[d] < lo) lo = f[d]
            if (f[d] > hi) hi = f[d]
        }
        return if (hi >= lo) hi - lo else 0.0
    }

    /**
     * How far the eyes must move for a point to count as movement.
     *
     * Half the standard error of the collected centroid: with 30 samples the centroid of a noisy
     * signal is far more precise than any single frame, so this adapts to how noisy this device
     * actually is instead of assuming a fixed step size.
     */
    private fun movementThreshold(samples: List<DoubleArray>): Double {
        if (samples.size < 2) return minCentroidDistance
        val sem = maxPerDimStdDev(samples) / sqrt(samples.size.toDouble())
        return maxOf(minCentroidDistance, 0.5 * sem)
    }

    private fun maxAbsDelta(a: DoubleArray, b: DoubleArray): Double {
        var worst = 0.0
        val dims = minOf(a.size, b.size)
        for (i in 0 until dims) {
            val d = kotlin.math.abs(a[i] - b[i])
            if (d > worst) worst = d
        }
        return worst
    }

    private fun distance(a: DoubleArray, b: DoubleArray): Double {
        var sum = 0.0
        val dims = minOf(a.size, b.size)
        for (i in 0 until dims) {
            val d = a[i] - b[i]
            sum += d * d
        }
        return sqrt(sum)
    }

    private fun computeMaxStdDev(): Double = maxPerDimStdDev(recent)

    private fun maxPerDimStdDev(samples: Collection<DoubleArray>): Double {
        if (samples.isEmpty()) return Double.MAX_VALUE
        val dims = samples.first().size
        var worst = 0.0
        for (d in 0 until dims) {
            var sum = 0.0
            for (f in samples) sum += f[d]
            val mean = sum / samples.size
            var variance = 0.0
            for (f in samples) {
                val delta = f[d] - mean
                variance += delta * delta
            }
            val std = sqrt(variance / samples.size)
            if (std > worst) worst = std
        }
        return worst
    }

    private fun medianPerFeature(samples: List<DoubleArray>): DoubleArray {
        val dims = samples[0].size
        val out = DoubleArray(dims)
        val column = DoubleArray(samples.size)
        for (d in 0 until dims) {
            for (i in samples.indices) column[i] = samples[i][d]
            column.sort()
            out[d] = if (column.size % 2 == 1) {
                column[column.size / 2]
            } else {
                (column[column.size / 2 - 1] + column[column.size / 2]) / 2.0
            }
        }
        return out
    }

    companion object {
        /** A degree-2 basis on 4 features has 15 coefficients; fewer points leave it underdetermined. */
        const val MIN_FIT_POINTS = 3

        /** Range below which a feature column is treated as constant. */
        const val MIN_USEFUL_SPREAD = 1e-3

        /**
         * 5x5 grid. Why not 9: nine points leave a 15-coefficient system underdetermined and the
         * fit collapses toward the mean, which is why the cursor used to sit in the middle.
         */
        val DEFAULT_POINTS: List<Pair<Double, Double>> = buildList {
            // 5x5 = 25 points for 15 coefficients (intercept + 6 linear + 10 quadratic), leaving
            // room for the ridge to fit instead of interpolate.
            val fractions = listOf(0.08, 0.29, 0.50, 0.71, 0.92)
            for (row in fractions) {
                for (col in fractions) {
                    add(col to row)
                }
            }
        }
    }
}
