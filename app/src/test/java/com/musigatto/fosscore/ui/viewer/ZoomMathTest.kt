package com.musigatto.fosscore.ui.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Matemática del zoom "1:1": anclaje con base de centrado, rango de paneo en coordenadas de
 * contenido y escalón de re-render. Estas funciones puras las consume el gesto del visor.
 */
class ZoomMathTest {

    // El anclaje debe dejar quieto el punto de la partitura que está bajo el dedo:
    // el mismo punto de contenido (en unidades de encaje) bajo la pantalla `anchor`
    // antes y después de multiplicar el zoom por k.
    @Test
    fun anchoredOffsetKeepsFitPointUnderAnchorFixed() {
        val base = Offset(40f, 60f)
        val offset = Offset(-10f, 20f)
        val anchor = Offset(500f, 300f)
        val s = 2.0f
        val k = 1.25f

        // punto de contenido bajo el dedo antes del zoom
        val pBefore = Offset(
            (anchor.x - base.x - offset.x) / s,
            (anchor.y - base.y - offset.y) / s
        )

        val offset2 = ZoomMath.anchoredOffset(offset, k, anchor, base)
        val s2 = s * k

        // el mismo punto de contenido debe seguir bajo el dedo tras el zoom
        val pAfter = Offset(
            (anchor.x - base.x - offset2.x) / s2,
            (anchor.y - base.y - offset2.y) / s2
        )
        assertEquals(pBefore.x, pAfter.x, 1e-2f)
        assertEquals(pBefore.y, pAfter.y, 1e-2f)
        // y el anclaje con base nula es la fórmula clásica k*offset + (1-k)*anchor
        val noBase = ZoomMath.anchoredOffset(offset, k, anchor, Offset.Zero)
        assertEquals(k * offset.x + (1f - k) * anchor.x, noBase.x, 1e-3f)
        assertEquals(k * offset.y + (1f - k) * anchor.y, noBase.y, 1e-3f)
    }

    // con zoom, la página puede deslizarse en un rango ÚNICO y continuo (siempre toca el viewport):
    // el anclaje del pellizco se respeta en todos los escalados — no hay salto al cruzar de
    // "cabe" a "desborda" (antes la página huía hacia la esquina en el tramo donde cabía).
    // Sin zoom (scale == 1) la vista es página completa: siempre centrada.
    @Test
    fun clampOffsetKeepsAnchorSlideAcrossAllScalesAndCentersAtScaleOne() {
        val viewport = Size(1000f, 700f)
        val content = Size(400f, 560f)   // encaje <= viewport
        val base = Offset((viewport.width - content.width) / 2f, (viewport.height - content.height) / 2f)
        val s = 1.2f   // 400*1.2=480 <= 1000 y 560*1.2=672 <= 700: ambos ejes caben

        // un offset dentro del rango no se re-centra (la página se desliza tras el anclaje)
        val kept = ZoomMath.clampOffset(Offset(50f, 50f), content, viewport, s, base)
        assertEquals(50f, kept.x, 1e-3f)
        assertEquals(50f, kept.y, 1e-3f)

        // fuera del rango: la página se acota para seguir tocando el viewport
        val tooFar = ZoomMath.clampOffset(Offset(-5000f, -5000f), content, viewport, s, base)
        assertEquals(-480f - 300f, tooFar.x, 1e-3f)      // -780
        assertEquals(-672f - 70f, tooFar.y, 1e-3f)       // -742

        // scale == 1: vista de página completa, centrada
        val atOne = ZoomMath.clampOffset(Offset(50f, 50f), content, viewport, 1f, base)
        assertEquals(0f, atOne.x, 1e-3f)
        assertEquals(0f, atOne.y, 1e-3f)
    }

    // contenido más grande que el viewport: el paneo se acota para que la página nunca se
    // despegue del todo (sigue tocando el viewport por una arista en el extremo del rango)
    @Test
    fun clampOffsetClampsPanWhenContentOverflows() {
        val viewport = Size(600f, 800f)
        val content = Size(900f, 1200f)
        val base = Offset((600f - 900f) / 2f, (800f - 1200f) / 2f)   // (-150, -200)
        val s = 2f   // contenido eficaz 1800x2400
        val minX = -content.width * s - base.x                       // -1650
        val maxX = viewport.width - base.x                           // 750
        val minY = -content.height * s - base.y                      // -2200
        val maxY = viewport.height - base.y                          // 1000

        val tooLeft = ZoomMath.clampOffset(Offset(-5000f, -5000f), content, viewport, s, base)
        assertEquals(minX, tooLeft.x, 1e-3f)
        assertEquals(minY, tooLeft.y, 1e-3f)

        val tooRight = ZoomMath.clampOffset(Offset(5000f, 5000f), content, viewport, s, base)
        assertEquals(maxX, tooRight.x, 1e-3f)
        assertEquals(maxY, tooRight.y, 1e-3f)

        // un offset dentro del rango no se toca
        val mid = Offset((minX + maxX) / 2f, (minY + maxY) / 2f)
        val kept = ZoomMath.clampOffset(mid, content, viewport, s, base)
        assertEquals(mid.x, kept.x, 1e-3f)
        assertEquals(mid.y, kept.y, 1e-3f)
    }

    @Test
    fun renderBucketRoundsUpToTenthsAndNeverBelowOne() {
        assertEquals(1.0f, ZoomMath.renderBucket(1.0f), 1e-4f)
        assertEquals(1.1f, ZoomMath.renderBucket(1.04f), 1e-4f)
        assertEquals(2.0f, ZoomMath.renderBucket(1.999f), 1e-4f)
        assertEquals(2.5f, ZoomMath.renderBucket(2.499f), 1e-4f)
        assertEquals(1.0f, ZoomMath.renderBucket(0.9f), 1e-4f)
        assertEquals(1.0f, ZoomMath.renderBucket(0.0f), 1e-4f)
    }
}