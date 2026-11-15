package com.gazetap.app.gaze

import kotlin.math.abs

/**
 * Ridge regression mapping gaze features -> screen coordinates on a degree-2 polynomial basis.
 *
 * Why this model: eye-in-socket geometry makes the mapping from iris position to screen position
 * visibly non-linear, so a quadratic basis fits far better than a linear one; ordinary least squares
 * on a small calibration set is unstable, so ridge shrinkage keeps the weights sane. Regularization
 * strength is chosen by leave-one-point-out CV because a separate validation split would throw away
 * too much of an already tiny calibration set.
 *
 * Why features are standardised (z-scored) before the polynomial expansion: the iris features live
 * in a narrow band around zero (typical |f| < 0.4) while screen coordinates are hundreds of pixels.
 * Without centring and scaling, A^T A is badly conditioned and a single lambda means wildly
 * different things per coefficient, which made the fit sensitive to where the user happened to look
 * during calibration. Standardisation is stored with the weights so prediction stays consistent.
 */
class RidgePolynomialModel(
    val featureCount: Int,
    var lambda: Double = 1.0,
    var weightsX: DoubleArray = DoubleArray(0),
    var weightsY: DoubleArray = DoubleArray(0),
    var featureMean: DoubleArray = DoubleArray(0),
    var featureStd: DoubleArray = DoubleArray(0),
    /** Which vector this model expects: [SOURCE_IRIS] or [SOURCE_EYE_LOOK]. */
    var featureSource: String = SOURCE_IRIS,
    /**
     * Number of leading features that get quadratic terms; 0 means all of them.
     * Head-pose features must stay linear: squaring a rotation angle adds curvature the eye model
     * does not have, and it costs coefficients the 25-point calibration cannot spare.
     */
    var quadraticDims: Int = 0,
) {
    val usesEyeLook: Boolean get() = featureSource == SOURCE_EYE_LOOK

    private val quad: Int
        get() = if (quadraticDims in 1..featureCount) quadraticDims else featureCount

    val basisSize: Int get() = 1 + featureCount + quad * (quad + 1) / 2

    /** z-score, falling back to a raw value when no statistics were stored (legacy models). */
    private fun standardised(features: DoubleArray): DoubleArray {
        val out = DoubleArray(featureCount)
        for (i in 0 until featureCount) {
            val mean = featureMean.getOrElse(i) { 0.0 }
            val std = featureStd.getOrElse(i) { 0.0 }
            out[i] = if (std > 1e-6) (features[i] - mean) / std else features[i] - mean
        }
        return out
    }

    fun expand(features: DoubleArray): DoubleArray {
        require(features.size == featureCount) { "expected $featureCount features, got ${features.size}" }
        val z = standardised(features)
        val out = DoubleArray(basisSize)
        var k = 0
        out[k++] = 1.0
        for (f in z) out[k++] = f
        for (i in 0 until quad) {
            for (j in i until quad) {
                out[k++] = z[i] * z[j]
            }
        }
        return out
    }

    fun predict(features: DoubleArray): DoubleArray {
        // Weights that do not match the basis (corrupt or half-written file) used to index past the
        // array and throw inside the frame pipeline; a NaN result is handled as "no position".
        if (!isFitted()) return doubleArrayOf(Double.NaN, Double.NaN)
        if (features.size != featureCount) return doubleArrayOf(Double.NaN, Double.NaN)
        val b = expand(features)
        var x = 0.0
        var y = 0.0
        for (i in b.indices) {
            x += b[i] * weightsX[i]
            y += b[i] * weightsY[i]
        }
        return doubleArrayOf(x, y)
    }

    fun isFitted(): Boolean = weightsX.size == basisSize && weightsY.size == basisSize

    fun toJson(): String {
        val sb = StringBuilder()
        sb.append("{\"type\":\"ridge_poly2\",")
        sb.append("\"featureCount\":").append(featureCount).append(',')
        sb.append("\"lambda\":").append(lambda).append(',')
        sb.append("\"featureMean\":[").append(featureMean.joinToString(",")).append("],")
        sb.append("\"featureSource\":\"").append(featureSource).append("\",")
        sb.append("\"quadraticDims\":").append(quadraticDims).append(',')
        sb.append("\"featureStd\":[").append(featureStd.joinToString(",")).append("],")
        sb.append("\"weightsX\":[").append(weightsX.joinToString(",")).append("],")
        sb.append("\"weightsY\":[").append(weightsY.joinToString(",")).append("]}")
        return sb.toString()
    }

    companion object {
        const val SOURCE_IRIS = "iris"
        const val SOURCE_EYE_LOOK = "eyeLook"

        /** Minimal JSON reader for the exact shape written by [toJson]. */
        fun fromJson(json: String): RidgePolynomialModel {
            fun numbersAfter(key: String): DoubleArray? {
                val keyIdx = json.indexOf("\"$key\"")
                if (keyIdx < 0) return null
                val open = json.indexOf('[', keyIdx)
                if (open < 0) return null
                val close = json.indexOf(']', open)
                if (close < 0) return null
                val body = json.substring(open + 1, close).trim()
                if (body.isEmpty()) return DoubleArray(0)
                return body.split(',').map { it.trim().toDouble() }.toDoubleArray()
            }
            fun stringAfter(key: String): String? {
                val keyIdx = json.indexOf("\"$key\"")
                if (keyIdx < 0) return null
                val firstQuote = json.indexOf('"', json.indexOf(':', keyIdx) + 1)
                if (firstQuote < 0) return null
                val secondQuote = json.indexOf('"', firstQuote + 1)
                if (secondQuote < 0) return null
                return json.substring(firstQuote + 1, secondQuote)
            }
            fun numberAfter(key: String): Double {
                val keyIdx = json.indexOf("\"$key\"")
                require(keyIdx >= 0) { "missing key $key" }
                val colon = json.indexOf(':', keyIdx)
                val end = json.indexOfAny(charArrayOf(',', '}'), colon + 1)
                return json.substring(colon + 1, if (end < 0) json.length else end).trim().toDouble()
            }
            return RidgePolynomialModel(
                featureCount = numberAfter("featureCount").toInt(),
                lambda = numberAfter("lambda"),
                weightsX = numbersAfter("weightsX") ?: DoubleArray(0),
                weightsY = numbersAfter("weightsY") ?: DoubleArray(0),
                featureMean = numbersAfter("featureMean") ?: DoubleArray(0),
                featureStd = numbersAfter("featureStd") ?: DoubleArray(0),
                featureSource = stringAfter("featureSource") ?: SOURCE_IRIS,
                quadraticDims = if (json.contains("\"quadraticDims\"")) numberAfter("quadraticDims").toInt() else 0,
            )
        }

        /** Per-dimension mean and standard deviation (never zero, so division is safe). */
        fun featureStats(features: List<DoubleArray>): Pair<DoubleArray, DoubleArray> {
            val dims = features.first().size
            val mean = DoubleArray(dims)
            val std = DoubleArray(dims)
            for (d in 0 until dims) {
                var sum = 0.0
                for (f in features) sum += f[d]
                mean[d] = sum / features.size
                var variance = 0.0
                for (f in features) {
                    val delta = f[d] - mean[d]
                    variance += delta * delta
                }
                std[d] = kotlin.math.sqrt(variance / features.size).coerceAtLeast(1e-3)
            }
            return mean to std
        }

        /** Fit both axes; features[i] corresponds to (screenX[i], screenY[i]). */
        fun fit(
            features: List<DoubleArray>,
            screenX: DoubleArray,
            screenY: DoubleArray,
            quadraticDims: Int = 0,
            lambdas: DoubleArray = doubleArrayOf(1e-4, 1e-3, 1e-2, 0.1, 1.0, 10.0, 100.0),
        ): RidgePolynomialModel {
            require(features.isNotEmpty()) { "need at least one calibration point" }
            require(features.size == screenX.size && features.size == screenY.size)
            val (mean, std) = featureStats(features)
            val model = RidgePolynomialModel(features[0].size, quadraticDims = quadraticDims)
            model.featureMean = mean
            model.featureStd = std
            val (bestLambda, _) = chooseLambda(features, screenX, screenY, quadraticDims, lambdas)
            model.lambda = bestLambda
            val design = features.map { model.expand(it) }.toTypedArray()
            model.weightsX = solveRidge(design, screenX, bestLambda)
            model.weightsY = solveRidge(design, screenY, bestLambda)
            return model
        }

        /** Leave-one-point-out CV over a lambda grid: returns (bestLambda, meanErrorPx). */
        fun chooseLambda(
            features: List<DoubleArray>,
            screenX: DoubleArray,
            screenY: DoubleArray,
            quadraticDims: Int = 0,
            lambdas: DoubleArray = doubleArrayOf(1e-4, 1e-3, 1e-2, 0.1, 1.0, 10.0, 100.0),
        ): Pair<Double, Double> {
            var bestLambda = lambdas.first()
            var bestErr = Double.MAX_VALUE
            for (lambda in lambdas) {
                val err = leaveOnePointOutError(features, screenX, screenY, lambda, quadraticDims)
                if (err < bestErr) {
                    bestErr = err
                    bestLambda = lambda
                }
            }
            return bestLambda to bestErr
        }

        fun leaveOnePointOutError(
            features: List<DoubleArray>,
            screenX: DoubleArray,
            screenY: DoubleArray,
            lambda: Double,
            quadraticDims: Int = 0,
        ): Double {
            val n = features.size
            if (n < 3) return Double.MAX_VALUE
            val (mean, std) = featureStats(features)
            val m = RidgePolynomialModel(features[0].size, quadraticDims = quadraticDims)
            m.featureMean = mean
            m.featureStd = std
            var total = 0.0
            for (held in 0 until n) {
                val trainIdx = (0 until n).filter { it != held }
                val design = trainIdx.map { m.expand(features[it]) }.toTypedArray()
                val wx = solveRidge(design, DoubleArray(trainIdx.size) { screenX[trainIdx[it]] }, lambda)
                val wy = solveRidge(design, DoubleArray(trainIdx.size) { screenY[trainIdx[it]] }, lambda)
                val b = m.expand(features[held])
                var px = 0.0
                var py = 0.0
                for (i in b.indices) { px += b[i] * wx[i]; py += b[i] * wy[i] }
                total += kotlin.math.hypot(px - screenX[held], py - screenY[held])
            }
            return total / n
        }

        /**
         * Solve (AᵀA + λI) w = Aᵀy by Gaussian elimination with partial pivoting.
         * The intercept is left unpenalized so the model can still shift freely.
         */
        fun solveRidge(design: Array<DoubleArray>, y: DoubleArray, lambda: Double): DoubleArray {
            val p = design[0].size
            val xtx = Array(p) { DoubleArray(p) }
            val xty = DoubleArray(p)
            for (r in design.indices) {
                val row = design[r]
                for (i in 0 until p) {
                    xty[i] += row[i] * y[r]
                    for (j in i until p) xtx[i][j] += row[i] * row[j]
                }
            }
            for (i in 0 until p) for (j in 0 until i) xtx[i][j] = xtx[j][i]
            for (i in 1 until p) xtx[i][i] += lambda
            return gaussianSolve(xtx, xty)
        }

        internal fun gaussianSolve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
            val n = b.size
            val m = Array(n) { i -> DoubleArray(n + 1) { j -> if (j < n) a[i][j] else b[i] } }
            for (col in 0 until n) {
                var pivot = col
                for (r in col + 1 until n) if (abs(m[r][col]) > abs(m[pivot][col])) pivot = r
                if (abs(m[pivot][col]) < 1e-12) continue
                if (pivot != col) { val t = m[pivot]; m[pivot] = m[col]; m[col] = t }
                for (r in col + 1 until n) {
                    val factor = m[r][col] / m[col][col]
                    if (factor == 0.0) continue
                    for (c in col..n) m[r][c] -= factor * m[col][c]
                }
            }
            val x = DoubleArray(n)
            for (i in n - 1 downTo 0) {
                if (abs(m[i][i]) < 1e-12) continue
                var sum = m[i][n]
                for (j in i + 1 until n) sum -= m[i][j] * x[j]
                x[i] = sum / m[i][i]
            }
            return x
        }
    }
}
