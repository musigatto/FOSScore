package com.musigatto.fosscore.ui.viewer

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelectionGeometryTest {
    private val fit = RectPx(0f, 0f, 1000f, 1000f)
    private val sel = RectPx(400f, 400f, 200f, 200f)  // centro (500,500)

    @Test
    fun handlesSitOutsideTheCorners() {
        val cs = handleCenters(sel, fit)
        assertEquals(4, cs.size)
        // los tiradores caen FUERA del recuadro, para que arrastrar dentro siga moviendo
        assertEquals(true, cs[0].x < sel.left && cs[0].y < sel.top)
        assertEquals(true, cs[1].x > sel.right && cs[1].y < sel.top)
        assertEquals(true, cs[2].x > sel.right && cs[2].y > sel.bottom)
        assertEquals(true, cs[3].x < sel.left && cs[3].y > sel.bottom)
    }

    @Test
    fun hitHandleFindsTheCornerUnderTheFinger() {
        handleCenters(sel, fit).forEachIndexed { i, c ->
            assertEquals(i, hitHandle(c, sel, fit))
        }
        // dentro del recuadro pero lejos de las esquinas = mover, no escalar
        assertNull(hitHandle(Offset(500f, 500f), sel, fit))
    }

    @Test
    fun hitHandleIgnoresPointsFarOutside() {
        assertNull(hitHandle(Offset(50f, 50f), sel, fit))
    }

    @Test
    fun scaleFactorTracksThePullFromTheCenter() {
        val center = Offset(500f, 500f)
        val start = Offset(600f, 500f)
        assertEquals(2f, scaleFactor(center, start, Offset(700f, 500f)), 1e-4f)
        assertEquals(0.5f, scaleFactor(center, start, Offset(550f, 500f)), 1e-4f)
    }

    @Test
    fun scaleFactorIsClampedNearTheCenter() {
        // el tirador pegado al centro no dispara la escala, y colapsarlo no da 0
        assertEquals(1f, scaleFactor(Offset(500f, 500f), Offset(500f, 500f), Offset(520f, 500f)), 1e-4f)
        assertEquals(MIN_SCALE_FACTOR, scaleFactor(Offset(0f, 0f), Offset(10f, 0f), Offset(0f, 0f)), 1e-4f)
    }

    @Test
    fun strokeSelectionRectAddsHalfWidthPadding() {
        val r = strokeSelectionRect(listOf(Offset(100f, 100f), Offset(200f, 300f)), 20f)
        assertEquals(90f, r.left, 1e-3f)
        assertEquals(90f, r.top, 1e-3f)
        assertEquals(120f, r.width, 1e-3f)
        assertEquals(220f, r.height, 1e-3f)
    }

    @Test
    fun rectCenterIsTheMidpoint() {
        assertEquals(Offset(500f, 500f), sel.center())
    }
}