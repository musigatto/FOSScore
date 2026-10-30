package com.musigatto.fosscore.ui.viewer

import androidx.compose.ui.geometry.Offset
import com.musigatto.fosscore.library.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
class InkGeometryTest {
    @Test
    fun encodeDecodeRoundtripPreservesPoints() {
        val pts = listOf(Offset(0.1f, 0.2f), Offset(0.3f, 1f), Offset(1f, 0f))
        assertEquals(pts, decodePoints(encodePoints(pts)))
    }

    @Test
    fun samplePointsDropsConsecutiveClosePoints() {
        val pts = listOf(Offset(0f, 0f), Offset(1f, 0f), Offset(1.5f, 0f), Offset(10f, 0f))
        val out = samplePoints(pts, 2f)
        // se mide contra el ÚLTIMO punto conservado: (1,0) y (1.5,0) están a <2 del (0,0),
        // así que solo entra el (10,0). La expectativa original (conservar el 1.5) contradecía
        // la implementación y fallaba siempre (definición en InkGeometry: minDist del anterior).
        assertEquals(listOf(Offset(0f, 0f), Offset(10f, 0f)), out)
    }

    @Test
    fun draggingAWordToTheEdgeKeepsItsBoxOnThePage() {
        // a word drawn near the right edge; a big drag to the right must stop at the border
        val word = listOf(Offset(0.70f, 0.40f), Offset(0.90f, 0.40f), Offset(0.90f, 0.60f))
        val d = deltaInside(word, Offset(0.5f, 0f))
        assertEquals(0.1f, d.x, 1e-4f)
        assertEquals(0f, d.y, 1e-4f)
        // and the word keeps its width (no single point pinned to the edge)
        val moved = word.map { it + d }
        assertEquals(0.20f, moved.maxOf { it.x } - moved.minOf { it.x }, 1e-4f)
    }

    @Test
    fun deltaInsideLeavesAFreeMoveAlone() {
        val word = listOf(Offset(0.3f, 0.3f), Offset(0.5f, 0.5f))
        assertEquals(Offset(0.1f, -0.1f), deltaInside(word, Offset(0.1f, -0.1f)))
    }

    @Test
    fun penWidthPxRoundTripsAndClampsToPageRange() {        val fit = RectPx(0f, 0f, 750f, 1000f)
        assertEquals(DEFAULT_PEN_PX, penWidthPxToFrac(DEFAULT_PEN_PX, fit) * fit.height, 1e-4f)
        // página baja: manda el tope de fracción (2% del alto)
        assertEquals(MAX_STROKE_WIDTH, penWidthPxToFrac(MAX_PEN_PX, RectPx(0f, 0f, 100f, 300f)), 1e-6f)
        // alto 0 (aún sin maquetar): el guard evita NaN/Inf y deja el valor en rango
        val degenerate = penWidthPxToFrac(MIN_PEN_PX, RectPx(0f, 0f, 0f, 0f))
        assertTrue(degenerate.isFinite() && degenerate in MIN_STROKE_WIDTH..MAX_STROKE_WIDTH)
    }

    @Test
    fun distanceToSegmentTakesProjectionOnMiddle() {
        assertEquals(1f, distanceToSegment(Offset(5f, 1f), Offset(0f, 0f), Offset(10f, 0f)), 1e-6f)
        assertEquals(5f, distanceToSegment(Offset(-5f, 0f), Offset(0f, 0f), Offset(10f, 0f)), 1e-6f)
    }

    @Test
    fun eraseMiddleOfStrokeSplitsIntoTwoRuns() {
        val pts = (0..10).map { Offset(it.toFloat(), 0f) }
        val runs = eraseStrokePoints(pts, listOf(Offset(4.5f, 0f), Offset(5.5f, 0f)), 1.2f)
        assertEquals(2, runs.size)
        assertEquals(listOf(4, 4), runs.map { it.size })
    }

    @Test
    fun eraserThatGrazeNothingLeavesTheStrokeAlone() {
        // a fast stroke stores its points far apart: 40 px here, well over 2*radius. A pass 500 px
        // away used to split it anyway, leaving a white gap between the two pieces.
        val pts = listOf(0f, 40f, 80f, 120f).map { Offset(it, 0f) }
        val far = listOf(Offset(0f, 500f), Offset(200f, 500f))
        assertEquals(listOf(pts), eraseStrokePoints(pts, far, 16f))
    }

    @Test
    fun eraseWholeStrokeReturnsEmpty() {
        val pts = (0..4).map { Offset(it.toFloat(), 0f) }
        assertEquals(emptyList<List<Offset>>(), eraseStrokePoints(pts, listOf(Offset(2f, 0f)), 10f))
    }

    @Test
    fun scalePointsGrowsAroundCenter() {
        val pts = listOf(Offset(0f, 0f), Offset(10f, 0f))
        assertEquals(
            listOf(Offset(-5f, 0f), Offset(15f, 0f)),
            scalePoints(pts, Offset(5f, 0f), 2f)
        )
    }

    @Test
    fun onePixelStaysOnePixelOnARealPage() {
        // the 1 px setting used to be clamped up to MIN_STROKE_WIDTH (0.002 of the page height), so
        // the stroke got fatter the moment it was committed, while the preview showed 1 px
        val fit = RectPx(0f, 0f, 900f, 1700f)
        assertEquals(1f, penWidthPxToFrac(1f, fit) * fit.height, 1e-3f)
        assertEquals(2f, penWidthPxToFrac(2f, fit) * fit.height, 1e-3f)
    }

    // letter at x..x+40, y..y+40 in px at a 1000 px fit (the entity stores normalized points)
    private fun letter(id: Long, x: Float, y: Float = 0f) = Stroke(
        id = id,
        sheetHash = "h",
        page = 0,
        points = encodePoints(
            listOf(Offset(x, y), Offset(x, y + 40f), Offset(x + 40f, y))
                .map { Offset(it.x / 1000f, it.y / 1000f) }
        ),
        width = 0.002f
    )

    @Test
    fun strokesOfAWordEndUpInOneGroup() {
        val fit = RectPx(0f, 0f, 1000f, 1000f)
        // "hola": 10 px between letters, "mundo" 110 px away, and a second line 160 px below
        val strokes = listOf(
            letter(1, 0f), letter(2, 50f), letter(3, 100f), letter(4, 150f),
            letter(5, 300f), letter(6, 350f), letter(7, 400f),
            letter(8, 0f, y = 200f)
        )
        val groups = groupInk(strokes, fit)
        assertEquals(3, groups.size)
        val hola = groups.first { g -> g.strokes.any { it.id == 1L } }
        assertEquals(listOf(1L, 2L, 3L, 4L), hola.strokes.map { it.id })
        assertTrue(groups.none { g -> g.strokes.any { it.id == 8L } && g.strokes.size > 1 })
    }

    @Test
    fun groupingFollowsTheWritingSize() {
        val fit = RectPx(0f, 0f, 1000f, 1000f)
        val strokes = listOf(letter(1, 0f), letter(2, 50f), letter(3, 300f))
        // same page, half the fit: the threshold comes from the strokes, so the groups do not change
        assertEquals(2, groupInk(strokes, fit).size)
        assertEquals(2, groupInk(strokes, RectPx(0f, 0f, 500f, 500f)).size)
    }

    @Test
    fun tapInsideTheHoleOfAnOStillFindsTheWord() {
        val fit = RectPx(0f, 0f, 1000f, 1000f)
        val groups = groupInk(listOf(letter(1, 0f), letter(2, 50f)), fit)
        val word = groupAt(Offset(70f, 20f), groups, padPx = 6f)
        assertEquals(listOf(1L, 2L), word?.strokes?.map { it.id })
        assertNull(groupAt(Offset(700f, 700f), groups, padPx = 6f))
    }
}
