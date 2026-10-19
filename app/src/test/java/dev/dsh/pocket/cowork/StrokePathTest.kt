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
}
