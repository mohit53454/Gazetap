package com.gazetap.app.gaze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlickDetectorTest {

    /** Mirrors MainActivity's strict runtime tuning (scaled for a ~1220 px wide screen). */
    private fun detector() = FlickDetector(
        minDisplacementPx = 300.0,
        maxWindowMs = 220.0,
        minSpeedPxPerSec = 1600.0,
        cooldownMs = 800.0,
        rearmSpeedPxPerSec = 350.0,
        minAxisDominance = 2.0,
        minStraightness = 0.82,
        minMonotonicity = 0.92,
        settleWindowMs = 500.0,
        confirmTimeoutMs = 300.0,
        confirmFraction = 0.80,
        snapBackFraction = 0.35,
    )

    /** Feeds a continuously still gaze long enough to satisfy the sustained-settle requirement. */
    private fun settle(d: FlickDetector, x: Double, y: Double, atMs: Double): Double {
        var t = atMs
        repeat(18) {
            d.update(x, y, t)
            t += 33.0
        }
        return t
    }

    @Test
    fun `a held leftward flick is sent one frame after it is recognised`() {
        val d = detector()
        val t1 = settle(d, 500.0, 500.0, 0.0)
        assertNull("nothing is sent before the new position is held", d.update(180.0, 500.0, t1))
        val flick = d.update(180.0, 500.0, t1 + 33.0)
        assertNotNull("a 320 px held movement must fire", flick)
        assertEquals(FlickDirection.LEFT, flick!!.direction)
        assertTrue("peak speed is reported", flick.speedPxPerSec > 6000.0)
    }

    @Test
    fun `vertical flicks map to UP and DOWN with screen y growing downward`() {
        val up = detector()
        var t = settle(up, 500.0, 900.0, 0.0)
        assertNull(up.update(500.0, 560.0, t))
        assertEquals(FlickDirection.UP, up.update(500.0, 560.0, t + 33.0)?.direction)

        val down = detector()
        t = settle(down, 500.0, 400.0, 0.0)
        assertNull(down.update(500.0, 740.0, t))
        assertEquals(FlickDirection.DOWN, down.update(500.0, 740.0, t + 33.0)?.direction)
    }

    @Test
    fun `a vertical flick with slight lateral noise still fires`() {
        val d = detector()
        var t = settle(d, 500.0, 900.0, 0.0)
        assertNull(d.update(520.0, 560.0, t))
        val flick = d.update(520.0, 560.0, t + 33.0)
        assertNotNull("mostly vertical movement is still vertical", flick)
        assertEquals(FlickDirection.UP, flick!!.direction)
    }

    @Test
    fun `a one frame model spike snaps back and never swipes`() {
        val d = detector()
        val t = settle(d, 500.0, 500.0, 0.0)
        assertNull(d.update(180.0, 500.0, t)) // candidate only
        assertNull("the gaze returned, so this was noise", d.update(500.0, 500.0, t + 33.0))
        assertNull(d.update(500.0, 500.0, t + 66.0))
    }

    @Test
    fun `a partly returned move that never holds is dropped by the timeout`() {
        val d = detector()
        var t = settle(d, 500.0, 500.0, 0.0)
        assertNull(d.update(180.0, 500.0, t))
        // only ~150 px still holds: not enough to confirm, not enough to snap back
        t += 33.0
        repeat(10) {
            assertNull(d.update(350.0, 500.0, t))
            t += 33.0
        }
        // past the confirmation timeout the candidate is gone for good
        assertNull(d.update(500.0, 500.0, t))
        assertNull(d.update(500.0, 500.0, t + 33.0))
    }

    @Test
    fun `a gaze that never settles can never fire a flick`() {
        val d = detector()
        var t = 0.0
        var x = 500.0
        repeat(30) {
            assertNull("constant jitter must never arm the detector", d.update(x, 500.0, t))
            x = if (x > 600.0) 500.0 else 900.0
            t += 33.0
        }
    }

    @Test
    fun `fast diagonal move with no dominant axis is rejected`() {
        val d = detector()
        val t = settle(d, 500.0, 500.0, 0.0)
        assertNull(d.update(760.0, 740.0, t))
        assertNull(d.update(760.0, 740.0, t + 33.0))
    }

    @Test
    fun `a curved sweep is rejected even though it is large and fast`() {
        val d = detector()
        val t = settle(d, 500.0, 500.0, 0.0)
        d.update(600.0, 700.0, t)
        assertNull(d.update(820.0, 600.0, t + 33.0))
        assertNull("holding a curved path does not make it a flick", d.update(820.0, 600.0, t + 66.0))
    }

    @Test
    fun `looking right and back left is not a RIGHT flick`() {
        val d = detector()
        val t = settle(d, 400.0, 500.0, 0.0)
        d.update(720.0, 500.0, t)
        assertNull(d.update(450.0, 500.0, t + 33.0))
        assertNull(d.update(450.0, 500.0, t + 66.0))
    }

    @Test
    fun `small saccade below the displacement threshold is ignored`() {
        val d = detector()
        val t = settle(d, 500.0, 500.0, 0.0)
        assertNull(d.update(455.0, 500.0, t))
        assertNull(d.update(455.0, 500.0, t + 33.0))
    }

    @Test
    fun `minor hand wobble while holding the phone never swipes`() {
        val d = detector()
        var t = settle(d, 500.0, 505.0, 0.0)
        var x = 500.0
        repeat(5) {
            x += 24.0
            assertNull("minor movement must never fire a swipe", d.update(x, 505.0, t))
            t += 33.0
        }
    }

    @Test
    fun `slow drift is not a flick`() {
        val d = detector()
        var t = settle(d, 500.0, 500.0, 0.0)
        var x = 500.0
        repeat(24) {
            x -= 8.0
            assertNull("drift must never trigger", d.update(x, 500.0, t))
            t += 33.0
        }
    }

    @Test
    fun `cooldown blocks a second swipe from the same movement`() {
        val d = detector()
        val t = settle(d, 500.0, 500.0, 0.0)
        d.update(180.0, 500.0, t)
        assertNotNull(d.update(180.0, 500.0, t + 33.0))
        // keeps travelling fast in the same direction immediately after
        assertNull(d.update(0.0, 500.0, t + 66.0))
    }

    @Test
    fun `re-arms after the eye settles and the cooldown expires`() {
        val d = detector()
        var t = settle(d, 500.0, 500.0, 0.0)
        d.update(180.0, 500.0, t)
        val first = d.update(180.0, 500.0, t + 33.0)
        assertNotNull(first)

        // hold still long enough for cooldown (800 ms) and a fresh sustained quiet period
        t = settle(d, 180.0, 500.0, first!!.tsMs + 33.0)
        while (t - first.tsMs < 900.0) {
            d.update(180.0, 500.0, t)
            t += 33.0
        }
        assertNull(d.update(-140.0, 500.0, t))
        val second = d.update(-140.0, 500.0, t + 33.0)
        assertNotNull("a second deliberate flick must work", second)
        assertEquals(FlickDirection.LEFT, second!!.direction)
    }

    @Test
    fun `reset drops a candidate and the history`() {
        val d = detector()
        val t = settle(d, 500.0, 500.0, 0.0)
        d.update(180.0, 500.0, t)
        d.reset()
        assertNull("a candidate from before the reset cannot fire", d.update(180.0, 500.0, t + 33.0))
    }

    @Test
    fun `a fast move without a sustained quiet period before it never swipes`() {
        val d = detector()
        // start with a proper settle, then keep interrupting quiet periods so none lasts long enough
        var t = settle(d, 500.0, 500.0, 0.0)
        var x = 500.0
        repeat(30) {
            x += if (it % 4 == 0) 12.0 else -4.0
            d.update(x, 500.0, t)
            t += 33.0
            if (it % 10 == 9) {
                // brief faster nudge breaks continuous settle window
                d.update(x + 20.0, 500.0, t); t += 33.0
                d.update(x, 500.0, t); t += 33.0
            }
        }
        assertNull(d.update(x - 340.0, 500.0, t))
        assertNull(d.update(x - 340.0, 500.0, t + 33.0))
    }

    @Test
    fun `repeated noisy jumps with brief pauses do not produce random swipes`() {
        val d = detector()
        var t = 0.0
        val positions = listOf(500.0, 820.0, 500.0, 180.0, 500.0, 840.0, 500.0)
        for (p in positions) {
            // not long enough to count as continuously settled
            repeat(6) { i -> d.update(if (i % 2 == 0) 500.0 else 520.0, 500.0, t); t += 33.0 }
            assertNull(d.update(p, 500.0, t)); t += 33.0
            assertNull(d.update(p, 500.0, t)); t += 33.0
        }
    }

    @Test
    fun `initial movement before any settle period cannot fire`() {
        val d = detector()
        assertNull(d.update(500.0, 500.0, 0.0))
        // no sustained quiet period has been established yet
        assertNull(d.update(180.0, 500.0, 33.0))
        assertNull(d.update(180.0, 500.0, 66.0))
        assertNull(d.update(180.0, 500.0, 99.0))
    }

    @Test
    fun `small jitter interrupts settling and prevents accidental swipes`() {
        val d = detector()
        var t = 0.0
        // never continuously still for 500ms: periodic larger steps
        repeat(50) {
            val x = if (it % 6 == 0) 530.0 else 500.0
            d.update(x, 500.0, t)
            t += 33.0
        }
        assertNull(d.update(180.0, 500.0, t))
        assertNull(d.update(180.0, 500.0, t + 33.0))
    }

    @Test
    fun `non finite samples are ignored`() {
        val d = detector()
        assertNull(d.update(Double.NaN, 500.0, 0.0))
        assertNull(d.update(500.0, Double.POSITIVE_INFINITY, 33.0))
    }
}
