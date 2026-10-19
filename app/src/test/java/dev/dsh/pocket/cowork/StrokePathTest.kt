package dev.dsh.pocket.cowork

import dev.dsh.pocket.cowork.broker.StrokePath
import org.junit.Assert.*
import org.junit.Test

/** Timing of one phone_gesture stroke: the finger follows the polyline at constant speed, and every vertex reaches the
 * app as its own sample at the moment the finger passes it, so a drawn shape keeps its corners at any speed. */
class StrokePathTest {
    private fun path(vararg p: Int) = StrokePath(p)

    @Test fun lengthFollowsTheLine() {
        assertEquals(300.0, path(0, 0, 100, 0, 100, 200).length(), 1e-9)
    }
    @Test fun oneFrameCarriesEveryVertexItPasses() {
        // An L of 100 + 100 px; one frame from 0 to 75% passes the corner and ends 50 px up the second leg.
        val samples = path(0, 0, 100, 0, 100, 100).between(0.0, 0.75)
        assertEquals(2, samples.size)
        assertEquals(0.5, samples[0].fraction(), 1e-9); assertEquals(100f, samples[0].x()); assertEquals(0f, samples[0].y())
        assertEquals(0.75, samples[1].fraction(), 1e-9); assertEquals(100f, samples[1].x()); assertEquals(50f, samples[1].y(), 1e-4f)
    }
    @Test fun framesContinueWhereThePreviousOneEnded() {
        val p = path(0, 0, 10, 0, 20, 0, 30, 0, 40, 0)
        val first = p.between(0.0, 0.3); val second = p.between(0.3, 1.0)
        assertEquals(listOf(0.25, 0.3), first.map { it.fraction() })
        assertEquals(listOf(0.5, 0.75, 1.0), second.map { it.fraction() })
        assertEquals(40f, second.last().x())
    }
    @Test fun theLastFrameEndsExactlyOnTheLastPoint() {
        val samples = path(5, 5, 50, 80).between(0.4, 1.0)
        assertEquals(1, samples.size)
        assertEquals(50f, samples[0].x()); assertEquals(80f, samples[0].y())
    }
    @Test fun aDotHasNoMovement() {
        val dot = path(30, 40, 30, 40)
        assertEquals(0.0, dot.length(), 0.0)
        assertTrue(dot.between(0.0, 1.0).isEmpty())
        assertEquals(30f, dot.lastX()); assertEquals(40f, dot.lastY())
    }
    @Test fun repeatedPointsAreDropped() {
        val samples = path(0, 0, 0, 0, 10, 0, 10, 0, 20, 0).between(0.0, 1.0)
        assertEquals(listOf(0.5, 1.0), samples.map { it.fraction() })
    }
    @Test fun anEmptyFrameSendsNothing() {
        assertTrue(path(0, 0, 10, 0).between(0.5, 0.5).isEmpty())
    }
    @Test fun pointInterpolatesAlongTheLength() {
        val p = path(0, 0, 100, 0, 100, 300)
        val q = p.point(0.5) // 200 of 400 px: 100 px down the second leg
        assertEquals(100f, q.x()); assertEquals(100f, q.y(), 1e-4f)
    }
    @Test(expected = IllegalArgumentException::class) fun oddCoordinatesAreRejected() { path(1, 2, 3) }
    @Test fun fingersShareEveryVertexTime() {
        // A pinch: one finger has a corner halfway, the other moves straight; each sample places both fingers.
        // A step longer than the whole movement adds no grid samples: only the vertices and the frame end.
        val a = path(0, 0, 50, 0, 50, 50); val b = path(200, 0, 300, 0)
        val fractions = StrokePath.frame(arrayOf(a, b), 0.0, 0.75, 1.0)
        assertArrayEquals(doubleArrayOf(0.5, 0.75), fractions, 1e-9)
        assertEquals(250f, b.point(0.5).x(), 1e-4f)
        assertArrayEquals(doubleArrayOf(0.25, 0.5, 0.75, 1.0), StrokePath.frame(arrayOf(path(0, 0, 10, 0, 20, 0, 30, 0, 40, 0), b), 0.0, 1.0, 1.0), 1e-9)
    }
    @Test fun aStillFingerAddsNoSamples() {
        val pivot = path(360, 800); val turning = path(560, 800, 360, 1000)
        assertArrayEquals(doubleArrayOf(0.4), StrokePath.frame(arrayOf(pivot, turning), 0.0, 0.4, 1.0), 1e-9)
        assertEquals(360f, pivot.point(0.4).x()); assertEquals(800f, pivot.point(0.4).y())
        assertEquals("nothing moves: no grid either", 0, StrokePath.frame(arrayOf(pivot, path(1, 1)), 0.0, 1.0, 0.1).size)
    }
    @Test fun aStraightLineIsSampledOnASharedTimeGrid() {
        // An 80 ms line sampled every 8 ms (step 0.1); frames are far apart, the grid continues across them.
        val line = arrayOf(path(0, 0, 100, 0))
        assertArrayEquals(doubleArrayOf(0.1, 0.2, 0.3, 0.35), StrokePath.frame(line, 0.0, 0.35, 0.1), 1e-9)
        assertArrayEquals(doubleArrayOf(0.4, 0.5, 0.6, 0.7, 0.72), StrokePath.frame(line, 0.35, 0.72, 0.1), 1e-9)
        assertArrayEquals(doubleArrayOf(0.8, 0.9, 1.0), StrokePath.frame(line, 0.72, 1.0, 0.1), 1e-9)
        assertEquals(30f, line[0].point(0.3).x(), 1e-4f)
    }
    @Test fun gridSamplesAndVerticesInterleave() {
        val corner = arrayOf(path(0, 0, 50, 0, 50, 50))
        assertArrayEquals(doubleArrayOf(0.2, 0.4, 0.5, 0.6, 0.75), StrokePath.frame(corner, 0.0, 0.75, 0.2), 1e-9)
        // A pinch of two straight fingers: the grid places both fingers at every sample.
        val pinch = arrayOf(path(330, 770, 180, 620), path(390, 830, 540, 980))
        assertArrayEquals(doubleArrayOf(0.25, 0.5, 0.6), StrokePath.frame(pinch, 0.0, 0.6, 0.25), 1e-9)
        assertEquals(255f, pinch[0].point(0.5).x(), 1e-3f); assertEquals(465f, pinch[1].point(0.5).x(), 1e-3f)
    }
    @Test fun aGridPointOnTheFrameStartIsNotRepeated() {
        assertArrayEquals(doubleArrayOf(0.75, 1.0), StrokePath.frame(arrayOf(path(0, 0, 100, 0)), 0.5, 1.0, 0.25), 1e-9)
    }
    @Test fun theFullCheckIsSkippedOnlyRightAfterTheCallersOwn() {
        val now = 500_000L
        assertTrue("0 forces the check", StrokePath.checkDue(now, 0))
        assertFalse("the caller has just checked: the first DOWN does not repeat it", StrokePath.checkDue(now, now - 5))
        assertFalse(StrokePath.checkDue(now, now - StrokePath.CHECK_MS + 1))
        assertTrue("CHECK_MS later it is due again", StrokePath.checkDue(now, now - StrokePath.CHECK_MS))
        assertTrue("a stale check (from before the gap) is repeated", StrokePath.checkDue(now, now - 400))
        // elapsedRealtime is never behind uptime: a timestamp from that clock must not skip checks.
        assertTrue("a time ahead of now is another clock", StrokePath.checkDue(now, now + 1))
    }
    @Test fun beforeTheDownTheLiftIsCoveredWhenOneCheckCoversTheStroke() {
        val now = 500_000L
        assertFalse("a 20 ms tap right after the caller's check needs no other", StrokePath.checkDue(now, now - 5, now + 20, false))
        assertTrue("the caller's check would be too old at the lift: check before the DOWN", StrokePath.checkDue(now, now - 70, now + 20, false))
        assertFalse("a check now would not cover a long stroke: it checks while the finger is down", StrokePath.checkDue(now, now - 70, now + 300, false))
        assertTrue("CHECK_MS still forces it", StrokePath.checkDue(now, now - StrokePath.CHECK_MS, now + 300, false))
        assertTrue("0 still forces it", StrokePath.checkDue(now, 0, now + 20, false))
    }
    @Test fun whileDownTheLiftIsCoveredBeforeItsLastMove() {
        val now = 500_000L
        assertFalse("the lift is far: only the CHECK_MS rule", StrokePath.checkDue(now, now - 50, now + StrokePath.LIFT_LEAD_MS, true))
        assertTrue("the last check would be too old at the lift", StrokePath.checkDue(now, now - 50, now + StrokePath.LIFT_LEAD_MS - 1, true))
        assertTrue("a check that ends this far from the lift does not cover it: again", StrokePath.checkDue(now, now, now + StrokePath.LIFT_LEAD_MS - 1, true))
        assertFalse("one that ends here covers it", StrokePath.checkDue(now, now, now + StrokePath.CHECK_MS - StrokePath.LIFT_SLACK_MS - 1, true))
        assertTrue(StrokePath.checkDue(now, now - 20, now + StrokePath.LIFT_LATE_MS, true))
        assertFalse("nearer than LIFT_LATE_MS a check would end after the lift", StrokePath.checkDue(now, now - 20, now + StrokePath.LIFT_LATE_MS - 1, true))
        assertFalse("the lift is now: only the CHECK_MS rule", StrokePath.checkDue(now, now - StrokePath.CHECK_MS + 1, now, true))
        assertTrue(StrokePath.checkDue(now, now - StrokePath.CHECK_MS, now, true))
    }

    @Test fun twoNearbyTapsWouldBeADoubleTap() {
        // At 291 dpi (the phone measured in test-evidence/gesture-gap): touch slop 15 px, double-tap slop 182 px.
        val touch = StrokePath.px(StrokePath.TOUCH_SLOP_DP.toFloat(), 291); val slop = StrokePath.px(StrokePath.DOUBLE_TAP_SLOP_DP.toFloat(), 291)
        assertEquals(15, touch); assertEquals(182, slop)
        val dot = arrayOf(intArrayOf(144, 165))
        assertTrue("dots 60 px apart", StrokePath.doubleTap(dot, arrayOf(intArrayOf(204, 165)), touch, slop))
        assertTrue("the same point", StrokePath.doubleTap(dot, dot, touch, slop))
        assertTrue("181 px apart", StrokePath.doubleTap(dot, arrayOf(intArrayOf(144 + 181, 165)), touch, slop))
        assertFalse("182 px apart: outside the slop", StrokePath.doubleTap(dot, arrayOf(intArrayOf(144 + 182, 165)), touch, slop))
        assertTrue("a tap may wobble within the touch slop", StrokePath.doubleTap(arrayOf(intArrayOf(144, 165, 159, 165, 150, 170)), arrayOf(intArrayOf(204, 165)), touch, slop))
        assertFalse("a line that left the touch slop is no tap", StrokePath.doubleTap(arrayOf(intArrayOf(144, 165, 160, 165, 144, 165)), arrayOf(intArrayOf(204, 165)), touch, slop))
        assertFalse("a second finger cancels the tap", StrokePath.doubleTap(arrayOf(intArrayOf(144, 165), intArrayOf(300, 165)), arrayOf(intArrayOf(204, 165)), touch, slop))
        assertTrue("any next stroke goes down with its first finger", StrokePath.doubleTap(dot, arrayOf(intArrayOf(150, 170, 600, 900), intArrayOf(500, 500)), touch, slop))
    }
    /** ProfileWorkspace.stroke() for one moving finger without holds, as a timeline: [age] ms since the caller's check,
     *  each full check takes [check] ms (StrokePath.checkDue decides, repeated while due), the DOWN is injected in 3 ms,
     *  frames sleep 16 ms and the last one until the movement's end, each MOVE is injected in [move] ms. Returns the time
     *  from the last MOVE sample (the movement's end) to the UP, and the age of the last full check at the UP. */
    private fun timeline(durationMs: Int, age: Long, check: Long, move: Long): Pair<Long, Long> {
        var t = 500_000L; var checked = t - age
        fun strokeCheck(lift: Long, down: Boolean) { while (StrokePath.checkDue(t, checked, lift, down)) { t += check; checked = t } }
        strokeCheck(t + durationMs, false)
        t += 3
        val end = t + durationMs
        strokeCheck(end, true)
        do { t += minOf(16L, maxOf(0L, end - t)); strokeCheck(end, true); val last = t >= end; t += move } while (!last)
        strokeCheck(t, true)
        return Pair(t - end, t - checked)
    }
    @Test fun aFlingKeepsItsVelocity() {
        // Android takes a pointer that has not moved for 40 ms as stopped and drops the fling velocity at the UP.
        for (d in StrokePath.MIN_DURATION_MS..1000) for (age in 0L until StrokePath.CHECK_MS) {
            val (gap, last) = timeline(d, age, check = 90, move = 2)
            assertTrue("$d ms stroke, caller's check $age ms old: $gap ms from the last MOVE to the UP", gap < 40)
            assertTrue("$d ms stroke, caller's check $age ms old: last check $last ms before the UP", last < StrokePath.CHECK_MS)
        }
        // Slow checks and MOVEs that wait a whole frame can cost the fling, never the check before the lift.
        for (d in StrokePath.MIN_DURATION_MS..1000) for (age in 0L until StrokePath.CHECK_MS)
            assertTrue(timeline(d, age, check = 130, move = 17).second < StrokePath.CHECK_MS)
    }
}
