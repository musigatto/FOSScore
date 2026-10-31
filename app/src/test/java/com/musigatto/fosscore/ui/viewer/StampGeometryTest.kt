package com.musigatto.fosscore.ui.viewer

import androidx.compose.ui.geometry.Offset
import com.musigatto.fosscore.library.Stamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StampGeometryTest {
    private val fitOf = { w: Float, h: Float, a: Float -> fitRect(w, h, a) }
    private fun stamp(x: Float = 0.5f, y: Float = 0.5f, size: Float = 0.1f) =
        Stamp(sheetId = 1, page = 0, symbol = "F", x = x, y = y, size = size)

    @Test
    fun landscapePageInPortraitSlotFitsByWidth() {
        val r = fitOf(800f, 1000f, 1.5f)
        assertEquals(0f, r.left, 1e-3f)
        assertEquals(233.3333f, r.top, 1e-3f)
        assertEquals(800f, r.width, 1e-3f)
        assertEquals(533.3333f, r.height, 1e-3f)
    }

    @Test
    fun portraitPageInLandscapeSlotFitsByHeight() {
        val r = fitOf(1000f, 600f, 0.7f)
        assertEquals(290f, r.left, 1e-3f)
        assertEquals(0f, r.top, 1e-3f)
        assertEquals(420f, r.width, 1e-3f)
        assertEquals(600f, r.height, 1e-3f)
    }

    @Test
    fun exactMatchFillsSlot() {
        val r = fitOf(600f, 800f, 0.75f)
        assertEquals(0f, r.left, 1e-3f)
        assertEquals(0f, r.top, 1e-3f)
        assertEquals(600f, r.width, 1e-3f)
        assertEquals(800f, r.height, 1e-3f)
    }

    @Test
    fun zeroSlotReturnsEmptyAndNoHit() {
        val r = fitOf(0f, 800f, 1.5f)
        assertEquals(RectPx(0f, 0f, 0f, 0f), r)
        assertNull(hitStamp(Offset(1f, 1f), listOf(stamp(0f, 0f, 0.1f)), r))
    }

    @Test
    fun normalizedToPxRoundtrips() {
        val fit = fitOf(800f, 1000f, 1.5f)
        val px = normalizedToPx(Offset(0.25f, 0.75f), fit)
        val back = pxToNormalized(px, fit)
        assertEquals(0.25f, back.x, 1e-3f)
        assertEquals(0.75f, back.y, 1e-3f)
    }

    @Test
    fun hitDetectsInsideAndMissesOutside() {
        val fit = fitOf(1000f, 800f, 1.25f)
        assertTrue(hitStamp(Offset(500f, 400f), listOf(stamp(0.5f, 0.5f, 0.1f)), fit) != null)
        assertNull(hitStamp(Offset(950f, 100f), listOf(stamp(0.5f, 0.5f, 0.1f)), fit))
    }

    @Test
    fun outOfRangeStampClampedInside() {
        val fit = fitOf(800f, 1000f, 1.5f)
        val r = stampRect(stamp(-0.2f, 1.3f, 0.9f), fit)
        // x/y son el CENTRO normalizado: tras clamp, el centro queda dentro del fit
        assertTrue(r.left + r.width / 2f >= fit.left && r.left + r.width / 2f <= fit.left + fit.width)
        assertTrue(r.top + r.height / 2f >= fit.top && r.top + r.height / 2f <= fit.top + fit.height)
    }

    @Test
    fun stampDraggedToTheEdgeKeepsItsWholeBox() {
        val fit = fitOf(800f, 1000f, 1.5f)
        val size = 0.1f
        // a drag past the corner: the centre is pulled back until the square fits
        val c = stampCenterInside(0.99f, 0.01f, size, fit)
        val r = stampRect(stamp(c.x, c.y, size), fit)
        assertTrue(r.left >= fit.left - 1e-3f)
        assertTrue(r.top >= fit.top - 1e-3f)
        assertTrue(r.left + r.width <= fit.left + fit.width + 1e-3f)
        assertTrue(r.top + r.height <= fit.top + fit.height + 1e-3f)
    }

    @Test
    fun stampLargerThanThePageStaysCenteredInsteadOfCrashing() {
        // size is capped by MAX_STAMP_SIZE, but a page can be narrower than that: the clamp must
        // not build a reversed range (coerceIn would throw)
        val fit = fitOf(200f, 1000f, 0.2f)
        val c = stampCenterInside(0.99f, 0.99f, MAX_STAMP_SIZE, fit)
        assertTrue(c.x.isFinite() && c.y.isFinite())
        assertEquals(0.5f, c.x, 1e-3f)
    }

    @Test
    fun stampPxRoundTripsAndClampsToPageRange() {        val fit = fitOf(750f, 1000f, 0.75f)
        val frac = stampSizePxToFrac(DEFAULT_STAMP_PX, fit)
        assertEquals(DEFAULT_STAMP_PX, stampSizeFracToPx(frac, fit), 1e-3f)
        // what draws and what measures share one fraction: the slider cannot lie
        assertEquals(frac, stampRect(stamp(0.5f, 0.5f, frac), fit).width / fit.height, 1e-6f)
        // height 0 (not laid out yet): the guard avoids NaN/Inf and keeps the value in range
        val degenerate = stampSizePxToFrac(DEFAULT_STAMP_PX, RectPx(0f, 0f, 0f, 0f))
        assertTrue(degenerate.isFinite() && degenerate in MIN_STAMP_SIZE..MAX_STAMP_SIZE)
    }
}