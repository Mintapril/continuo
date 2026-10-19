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
}
