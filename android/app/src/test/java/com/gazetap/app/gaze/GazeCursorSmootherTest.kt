package com.gazetap.app.gaze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GazeCursorSmootherTest {

    private fun smoother() = GazeCursorSmoother(quietRadiusPx = 18.0, minCutoff = 1.2, beta = 0.8)

    @Test
    fun `jitter inside the quiet zone never moves the pointer`() {
        val s = smoother()
        var t = 0.0
        val first = s.filter(500.0, 500.0, t)
        assertEquals(500.0, first[0], 1e-9)
        assertEquals(500.0, first[1], 1e-9)

        repeat(30) { i ->
            t += 0.066
            val noise = if (i % 2 == 0) 12.0 else -12.0
            val p = s.filter(500.0 + noise, 500.0 + noise * 0.5, t)
            assertEquals("x must hold still, frame $i", 500.0, p[0], 1e-6)
            assertEquals("y must hold still, frame $i", 500.0, p[1], 1e-6)
        }
    }

    @Test
    fun `a real movement is followed within one quiet zone and stays there`() {
        val s = smoother()
        s.filter(500.0, 500.0, 0.0)
        var t = 0.066
        var p = s.filter(800.0, 500.0, t)
        repeat(8) {
            t += 0.066
            p = s.filter(800.0, 500.0, t)
        }
        assertTrue(
            "the pointer must end at the target minus at most the quiet zone, was ${p[0]}",
            abs(p[0] - 800.0) <= 19.0,
        )
        assertTrue("and it must move most of the way at once, was ${p[0]}", p[0] > 760.0)
    }

    @Test
    fun `motion beyond the quiet zone is continuous, not a jump at the boundary`() {
        val s = smoother()
        s.filter(0.0, 0.0, 0.0)
        val justInside = s.filter(17.0, 0.0, 0.066)
        assertEquals("17 px is still inside the quiet zone", 0.0, justInside[0], 1e-6)

        val justOutside = s.filter(21.0, 0.0, 0.132)
        // Dead zone removed (3 px of motion) then the low-pass softens the very first moving
        // frame; what matters is that motion starts tiny instead of jumping the whole 21 px.
        assertEquals("21 px must move by about 21 - 18 px", 3.0, justOutside[0], 1.0)
        assertTrue("and never by the full 21 px", justOutside[0] < 18.0)
    }

    @Test
    fun `vertical movement is not lost`() {
        val s = smoother()
        s.filter(400.0, 300.0, 0.0)
        var t = 0.0
        var p = s.filter(400.0, 600.0, 0.066)
        repeat(8) { t += 0.066; p = s.filter(400.0, 600.0, t + 0.066) }
        assertTrue("y must follow, was ${p[1]}", abs(p[1] - 600.0) <= 19.0)
        assertEquals("x must not drift", 400.0, p[0], 1e-6)
    }

    @Test
    fun `non finite samples cannot move the pointer`() {
        val s = smoother()
        s.filter(500.0, 500.0, 0.0)
        val p = s.filter(Double.NaN, Double.POSITIVE_INFINITY, 0.066)
        assertEquals(500.0, p[0], 1e-9)
        assertEquals(500.0, p[1], 1e-9)
    }

    @Test
    fun `reset makes the next sample the new anchor`() {
        val s = smoother()
        s.filter(100.0, 100.0, 0.0)
        s.reset()
        val p = s.filter(900.0, 700.0, 1.0)
        assertEquals(900.0, p[0], 1e-9)
        assertEquals(700.0, p[1], 1e-9)
    }
}
