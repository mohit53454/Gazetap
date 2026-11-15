package com.gazetap.app.gaze

/**
 * Turns face landmarks into a small, distance-invariant feature vector for gaze regression.
 *
 * Why iris-in-eye rather than raw iris pixel position: raw pixel positions change when the user
 * leans in or out, which the model would have to undo with head-size features. Dividing by the
 * eye's own width and height cancels that scaling, so the four features mean "iris offset inside
 * each eye socket" and stay stable across small head translations.
 *
 * Features (x, y down, MediaPipe normalised image coordinates):
 *   0: right iris x offset from eye centre, in eye widths
 *   1: right iris y offset from eye corner line, in eye heights
 *   2: left  iris x offset ...
 *   3: left  iris y offset ...
 */
object GazeFeatures {
    const val COUNT = 4

    /** MediaPipe Face Mesh indices: iris centres arrive only with refineLandmarks, indices 468+473. */
    const val RIGHT_EYE_OUTER = 33
    const val RIGHT_EYE_INNER = 133
    const val RIGHT_EYE_TOP = 159
    const val RIGHT_EYE_BOTTOM = 145
    const val RIGHT_IRIS_CENTER = 468

    const val LEFT_EYE_INNER = 362
    const val LEFT_EYE_OUTER = 263
    const val LEFT_EYE_TOP = 386
    const val LEFT_EYE_BOTTOM = 374
    const val LEFT_IRIS_CENTER = 473

    /** Iris landmarks mean we need at least 478 points; older 468-point output cannot be used. */
    const val MIN_LANDMARKS = 478

    /**
     * Smallest believable eye, in normalised image coordinates (~2 px wide at 480 px).
     *
     * It used to be 1e-5, which let a narrowed eye - head rolled 20 degrees, lids half shut - divide
     * the iris offset by almost nothing. That produces features like 17 eye-widths, and the on-device
     * readout `iris range(3.03, 17.53)` is exactly that: a few such frames poison every calibration
     * centroid they touch, and the fitted model lands hundreds of pixels off.
     */
    private const val MIN_EYE_SIZE = 4e-3f

    /** Largest believable iris offset, in eye widths: the iris cannot leave its own eye socket. */
    private const val MAX_OFFSET = 0.8

    /**
     * @param xs landmark x coordinates (normalised), indexed by MediaPipe landmark index
     * @param ys landmark y coordinates
     * @return 4 features, or null when the mesh lacks iris points or the eye is degenerate
     */
    fun extract(xs: FloatArray, ys: FloatArray): DoubleArray? {
        if (xs.size < MIN_LANDMARKS || ys.size < MIN_LANDMARKS) return null
        val right = eyeOffset(
            xs, ys,
            outerIdx = RIGHT_EYE_OUTER, innerIdx = RIGHT_EYE_INNER,
            topIdx = RIGHT_EYE_TOP, bottomIdx = RIGHT_EYE_BOTTOM,
            irisIdx = RIGHT_IRIS_CENTER
        ) ?: return null
        val left = eyeOffset(
            xs, ys,
            outerIdx = LEFT_EYE_OUTER, innerIdx = LEFT_EYE_INNER,
            topIdx = LEFT_EYE_TOP, bottomIdx = LEFT_EYE_BOTTOM,
            irisIdx = LEFT_IRIS_CENTER
        ) ?: return null
        return doubleArrayOf(right[0], right[1], left[0], left[1])
    }

    /**
     * Alternative feature vector built from MediaPipe's eyeLook* blendshapes instead of iris
     * geometry. Some devices return iris landmarks that ride along with the head and carry no gaze
     * signal; the blendshapes are a separate regression output and can still encode where the eye
     * points. Order matches the iris vector: right-x, right-y, left-x, left-y.
     */
    fun fromBlendshapes(scores: Map<String, Float>): DoubleArray? {
        fun v(name: String): Double {
            val f = scores[name] ?: return Double.NaN
            return f.toDouble()
        }
        val outLeft = v("eyeLookOutLeft")
        val inLeft = v("eyeLookInLeft")
        val upLeft = v("eyeLookUpLeft")
        val downLeft = v("eyeLookDownLeft")
        val outRight = v("eyeLookOutRight")
        val inRight = v("eyeLookInRight")
        val upRight = v("eyeLookUpRight")
        val downRight = v("eyeLookDownRight")
        // out - in grows when looking to that eye's outer side; up - down grows looking up
        return doubleArrayOf(
            outRight - inRight, upRight - downRight,
            outLeft - inLeft, upLeft - downLeft,
        )
    }

    /**
     * The vector the production pipeline calibrates on: six numbers instead of four.
     *
     * 0,1: mean iris offset of both eyes (precise, but shifts when the head turns)
     * 2,3: mean eyeLook blendshape of both eyes (independent estimate, saturates at the extremes)
     * 4,5: head yaw and pitch in degrees / 40 (explains the shift the iris suffers when you move)
     *
     * Averaging the two eyes is deliberate: they move together, so per-eye copies were two redundant
     * dimensions of pure noise, while head pose was a real source of error that was being ignored.
     */
    const val COMBINED_COUNT = 6

    /** Dims 0..3 get quadratic terms; head pose stays linear (see RidgePolynomialModel). */
    const val COMBINED_QUADRATIC_DIMS = 4

    fun combine(
        iris: DoubleArray?,
        look: DoubleArray?,
        yawDeg: Double,
        pitchDeg: Double,
    ): DoubleArray? {
        if (iris == null && look == null) return null
        val ix = if (iris != null) (iris[0] + iris[2]) / 2.0 else 0.0
        val iy = if (iris != null) (iris[1] + iris[3]) / 2.0 else 0.0
        val lh = if (look != null) (look[0] + look[2]) / 2.0 else 0.0
        val lv = if (look != null) (look[1] + look[3]) / 2.0 else 0.0
        return doubleArrayOf(ix, iy, lh, lv, yawDeg / 40.0, pitchDeg / 40.0)
    }

    private fun eyeOffset(
        xs: FloatArray,
        ys: FloatArray,
        outerIdx: Int,
        innerIdx: Int,
        topIdx: Int,
        bottomIdx: Int,
        irisIdx: Int,
    ): DoubleArray? {
        val centerX = (xs[outerIdx].toDouble() + xs[innerIdx]) / 2.0
        val centerY = (ys[outerIdx].toDouble() + ys[innerIdx]) / 2.0
        val width = kotlin.math.abs(xs[outerIdx].toDouble() - xs[innerIdx])
        val height = kotlin.math.abs(ys[topIdx].toDouble() - ys[bottomIdx])
        if (width < MIN_EYE_SIZE || height < MIN_EYE_SIZE) return null
        val dx = (xs[irisIdx].toDouble() - centerX) / width
        val dy = (ys[irisIdx].toDouble() - centerY) / height
        if (!dx.isFinite() || !dy.isFinite()) return null
        // A frame this far off is a misplaced iris landmark, not gaze: drop the frame instead of
        // letting it drag the point centroid and the reported range.
        if (kotlin.math.abs(dx) > MAX_OFFSET || kotlin.math.abs(dy) > MAX_OFFSET) return null
        return doubleArrayOf(dx, dy)
    }
}
