package com.gazetap.app.gaze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GazeModelStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun fittedModel(): RidgePolynomialModel {
        val feats = mutableListOf<DoubleArray>()
        val xs = mutableListOf<Double>()
        val ys = mutableListOf<Double>()
        for (row in 0..2) for (col in 0..2) {
            val f = doubleArrayOf(0.1 + 0.2 * col, 0.1 + 0.2 * row)
            feats.add(f)
            xs.add(100.0 + 400.0 * f[0])
            ys.add(200.0 + 600.0 * f[1])
        }
        return RidgePolynomialModel.fit(feats, xs.toDoubleArray(), ys.toDoubleArray())
    }

    @Test
    fun `saves model with its analysis resolution`() {
        val store = GazeModelStore(temp.newFile("gaze_model.json"))
        val model = fittedModel()
        store.save(model, imageWidth = 320, imageHeight = 240)

        val stored = store.load()
        assertNotNull(stored)
        assertEquals(320, stored!!.imageWidth)
        assertEquals(240, stored.imageHeight)

        val before = model.predict(doubleArrayOf(0.3, 0.4))
        val after = stored.model.predict(doubleArrayOf(0.3, 0.4))
        assertEquals(before[0], after[0], 1e-6)
        assertEquals(before[1], after[1], 1e-6)
    }

    @Test
    fun `resolution mismatch is detected so a stale model is not used`() {
        val store = GazeModelStore(temp.newFile("gaze_model.json"))
        store.save(fittedModel(), 640, 480)
        val stored = store.load()!!

        assertTrue(store.matchesResolution(stored, 640, 480))
        assertFalse("320x240 must not reuse a 640x480 calibration", store.matchesResolution(stored, 320, 240))
        assertFalse(store.matchesResolution(stored, 640, 240))
    }

    @Test
    fun `legacy model without a version is refused so the cursor cannot point sideways`() {
        val file = temp.newFile("gaze_model.json")
        file.writeText(fittedModel().toJson())
        val store = GazeModelStore(file)
        assertNull(store.load())
        assertTrue("the reason must be reportable", store.lastLoadError!!.contains("older feature basis"))
    }

    @Test
    fun `a six dimensional production model survives a restart`() {
        // The vector the app actually calibrates on: 6 dims, quadratic on the first 4.
        val feats = mutableListOf<DoubleArray>()
        val xs = mutableListOf<Double>()
        val ys = mutableListOf<Double>()
        for (row in 0..4) for (col in 0..4) {
            val gx = -0.5 + 0.25 * col
            val gy = -0.5 + 0.25 * row
            feats.add(doubleArrayOf(gx, gy, gx * 0.8, gy * 0.8, 0.0, 0.0))
            xs.add(640.0 * (gx + 0.5))
            ys.add(480.0 * (gy + 0.5))
        }
        val model = RidgePolynomialModel.fit(
            feats, xs.toDoubleArray(), ys.toDoubleArray(), quadraticDims = 4
        )
        val store = GazeModelStore(temp.newFile("gaze_model.json"))
        store.save(model, imageWidth = 640, imageHeight = 480)

        val reloaded = GazeModelStore(store.let { temp.root.resolve("gaze_model.json") }).load()
        assertNotNull("a saved calibration must come back after a restart", reloaded)
        assertEquals(6, reloaded!!.model.featureCount)
        assertEquals(4, reloaded.model.quadraticDims)
        assertTrue(reloaded.model.isFitted())
        assertEquals(640, reloaded.imageWidth)
        val p = reloaded.model.predict(feats[12])
        assertEquals(xs[12], p[0], 1e-6)
        assertEquals(ys[12], p[1], 1e-6)
    }

    @Test
    fun `a missing main file falls back to the backup copy`() {
        val store = GazeModelStore(temp.newFile("gaze_model.json"))
        store.save(fittedModel(), 640, 480)
        // Second save keeps the first as a backup; delete the main file as a kill mid-write would.
        store.save(fittedModel(), 640, 480)
        temp.root.resolve("gaze_model.json").delete()
        val recovered = GazeModelStore(temp.root.resolve("gaze_model.json")).load()
        assertNotNull("the backup must be used when the main file is gone", recovered)
        assertTrue(recovered!!.model.isFitted())
    }

    @Test
    fun `missing file loads as null`() {
        val store = GazeModelStore(temp.newFile("gaze_model.json"))
        assertNull(store.load())
    }

    @Test
    fun `delete removes both files`() {
        val store = GazeModelStore(temp.newFile("gaze_model.json"))
        store.save(fittedModel(), 320, 240)
        store.delete()
        assertFalse(store.exists())
        assertNull(store.load())
    }

    @Test
    fun `corrupt json loads as null instead of throwing`() {
        val file = temp.newFile("gaze_model.json")
        file.writeText("{not json at all")
        val store = GazeModelStore(file)
        assertNull(store.load())
    }
}
