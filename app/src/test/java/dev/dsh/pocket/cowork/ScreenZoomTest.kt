package dev.dsh.pocket.cowork

import dev.dsh.pocket.cowork.broker.ActionGate
import dev.dsh.pocket.cowork.broker.ScreenZoom
import dev.dsh.pocket.cowork.broker.ScreenZoom.MARGIN
import org.junit.Assert.*
import org.junit.Test

/** A zoomed phone_screenshot: every content pixel is a screen pixel enlarged scale times, and the rulers around it are
 *  labelled in screen pixels, so the model reads coordinates it can tap or draw at. */
class ScreenZoomTest {
    private val black = 0xFF000000.toInt(); private val white = -1
    private fun zoom(l: Int, t: Int, r: Int, b: Int, s: Int) = ScreenZoom(l, t, r, b, s, 720, 1600)
    private fun refused(l: Int, t: Int, r: Int, b: Int, s: Int) {
        try { zoom(l, t, r, b, s); fail("Expected refusal") } catch (e: ActionGate.Denied) { assertEquals("ARGUMENTS_INVALID", e.code) }
    }
    /** Distinct, never black: a tick or label drawn over the content would show. */
    private fun pixels(z: ScreenZoom) = IntArray((z.right - z.left) * (z.bottom - z.top)) { 0xFF000000.toInt() or (it * 2654435 and 0xFFFFFF) or 0x010101 }
    // The 5x7 digit font of the labels, drawn 2 px a dot, 12 px a digit.
    private val font = listOf(intArrayOf(14,17,19,21,25,17,14), intArrayOf(4,12,4,4,4,4,14), intArrayOf(14,17,1,2,4,8,31), intArrayOf(31,2,4,2,1,17,14),
        intArrayOf(2,6,10,18,31,2,2), intArrayOf(31,16,30,1,1,17,14), intArrayOf(6,8,16,30,17,17,14), intArrayOf(31,1,2,4,8,8,8),
        intArrayOf(14,17,17,14,17,17,14), intArrayOf(14,17,17,15,1,2,12))
    private fun label(out: IntArray, iw: Int, x: Int, y: Int, digits: Int) = (0 until digits).joinToString("") { c ->
        font.indexOfFirst { glyph -> (0 until 7).all { r -> (0 until 5).all { k ->
            (out[(y + r * 2) * iw + x + c * 12 + k * 2] == black) == ((glyph[r] shr (4 - k)) and 1 != 0) } } }.toString()
    }

    @Test fun imageIsTheRegionTimesScaleInsideTheBand() {
        val z = zoom(100, 200, 220, 320, 4)
        assertEquals(120 * 4 + 2 * MARGIN, z.imageWidth()); assertEquals(120 * 4 + 2 * MARGIN, z.imageHeight())
        assertEquals(1536, zoom(0, 0, 177, 8, 8).imageWidth())
    }
    @Test fun regionsOutsideTheScreenOrTooLargeAreRefused() {
        refused(-1, 0, 100, 100, 2); refused(0, 0, 721, 100, 1); refused(0, 1500, 100, 1601, 1)
        refused(10, 10, 17, 100, 2); refused(10, 10, 100, 17, 2); refused(100, 100, 50, 200, 2)
        refused(0, 0, 100, 100, 0); refused(0, 0, 100, 100, 9)
        refused(0, 0, 720, 100, 2); refused(0, 0, 100, 1600, 1); refused(0, 0, 178, 8, 8)
        zoom(0, 0, 720, 1416, 1); zoom(0, 0, 8, 8, 8)
    }
    @Test fun tickSpacingFollowsTheScale() {
        val expected = mapOf(1 to (50 to 10), 2 to (25 to 5), 3 to (20 to 5), 4 to (20 to 2), 5 to (10 to 2), 8 to (10 to 1))
        for ((s, steps) in expected) { val z = zoom(0, 0, 100, 100, s); assertEquals("scale $s", steps, z.major() to z.minor()) }
    }
    @Test fun everyContentPixelIsItsScreenPixelNearestNeighbour() {
        for (s in listOf(1, 3, 8)) {
            val z = zoom(103, 211, 160, 250, s); val src = pixels(z); val out = z.render(src); val iw = z.imageWidth(); val w = 57
            for (y in 0 until 39 * s) for (x in 0 until w * s)
                assertEquals("scale $s at $x,$y", src[y / s * w + x / s], out[(MARGIN + y) * iw + MARGIN + x])
        }
    }
    @Test fun ticksMarkWhereTheirScreenPixelStarts() {
        val z = zoom(103, 200, 220, 320, 4); val out = z.render(pixels(z)); val iw = z.imageWidth(); val frame = MARGIN - 1
        // Minor ticks every 2 px, major every 20: x=104 is the first tick, 120 the first long one.
        val tick = { x: Int -> MARGIN + (x - 103) * 4 }
        for (row in frame - 4 until frame) assertEquals(black, out[row * iw + tick(104)])
        assertEquals(white, out[(frame - 5) * iw + tick(104)])
        for (row in frame - 8 until frame) assertEquals(black, out[row * iw + tick(120)])
        assertEquals("no tick at x=103 or between ticks", white, out[(frame - 1) * iw + tick(103)]); assertEquals(white, out[(frame - 1) * iw + tick(104) + 1])
        assertEquals("the frame row", black, out[frame * iw + tick(150)])
    }
    @Test fun labelsAreScreenCoordinates() {
        val z = zoom(100, 200, 220, 320, 4); val out = z.render(pixels(z)); val iw = z.imageWidth()
        val frame = MARGIN - 1; val right = MARGIN + 120 * 4; val bottom = MARGIN + 120 * 4
        // Top and bottom labels are centred on their tick (3 digits are 34 px wide).
        assertEquals("100", label(out, iw, MARGIN - 17, frame - 8 - 3 - 14, 3))
        assertEquals("120", label(out, iw, MARGIN + 20 * 4 - 17, frame - 8 - 3 - 14, 3))
        assertEquals("220", label(out, iw, right - 17, bottom + 1 + 8 + 3, 3))
        // Left labels end before the tick, right labels start after it; both centred on the row.
        assertEquals("200", label(out, iw, frame - 8 - 3 - 34, MARGIN - 7, 3))
        assertEquals("300", label(out, iw, right + 1 + 8 + 3, MARGIN + 100 * 4 - 7, 3))
    }
    @Test fun fourDigitLabelsFitTheBand() {
        val z = zoom(600, 1550, 720, 1600, 2); val out = z.render(pixels(z)); val iw = z.imageWidth()
        assertEquals("1550", label(out, iw, MARGIN - 1 - 8 - 3 - 46, MARGIN - 7, 4))
        assertEquals("1600", label(out, iw, MARGIN + 120 * 2 + 1 + 8 + 3, MARGIN + 50 * 2 - 7, 4))
    }
    @Test(expected = IllegalArgumentException::class) fun pixelsMustMatchTheRegion() { zoom(0, 0, 10, 10, 2).render(IntArray(99)) }
}
