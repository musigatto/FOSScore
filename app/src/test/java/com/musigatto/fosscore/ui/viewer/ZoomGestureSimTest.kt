package com.musigatto.fosscore.ui.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

/**
 * Reproduce NUMÉRICAMENTE el bucle del pellizco de PdfViewerScreen (anclaje → pan → clamp) con
 * las funciones de producción (ZoomMath, fitRect) y mide la deriva del punto de página que está
 * bajo los dedos del centroid. Sin el fix de clampAxis (anclaje anulado cuando el contenido cabe
 * en un eje) la deriva era de 40-112 px medios; con el fix debe quedar ~0.
 */
class ZoomGestureSimTest {

    class Drift(val avg: Float, val max: Float)

    /**
     * Dos dedos que se abren [zoomTotal]× alrededor de un centroid fijo dentro de la página y que
     * además se desliza [drift] px/frame (dedos que se mueven al pellizcar).
     */
    private fun runPinch(
        viewport: Size,
        pageAspect: Float,
        frames: Int = 28,
        zoomTotal: Float = 2.5f,
        drift: Offset = Offset.Zero
    ): Drift {
        val fit = fitRect(viewport.width, viewport.height, pageAspect)
        val content = Size(fit.width, fit.height)
        val base = Offset(fit.left, fit.top)
        // centroid inicial dentro de la página (bajo el centro, algo a la derecha)
        val c0 = Offset(base.x + fit.width * 0.45f, base.y + fit.height * 0.55f)

        var scale = 1f
        var offset = Offset.Zero
        val f0 = Offset((c0.x - base.x) / scale, (c0.y - base.y) / scale)
        var anchorDist = 0f
        var prevC = c0
        var prevD = -1f
        var maxDrift = 0f
        var sumDrift = 0f

        for (t in 1 until frames) {
            val half = 60f * zoomTotal.pow(t.toFloat() / (frames - 1))
            val c = Offset(c0.x + drift.x * t, c0.y + drift.y * t)
            val delta = c - prevC
            val d = half * 2f

            // mismo orden y fórmulas que el gesto real
            if (prevD < 0f) {
                anchorDist = d
            } else {
                val ratio = d / anchorDist
                anchorDist = d
                if (abs(ratio - 1f) > 1e-4f) {
                    val target = (scale * ratio).coerceIn(1f, 2.5f)
                    if (target != scale) {
                        val k = target / scale
                        offset = ZoomMath.anchoredOffset(offset, k, c, base)
                        scale = target
                    }
                }
            }
            offset += delta
            offset = ZoomMath.clampOffset(offset, content, viewport, scale, base)
            prevC = c
            prevD = d

            val screen = Offset(base.x + offset.x + f0.x * scale, base.y + offset.y + f0.y * scale)
            val dr = (screen - c).getDistance()
            maxDrift = maxOf(maxDrift, dr)
            sumDrift += dr
        }
        return Drift(sumDrift / (frames - 1), maxDrift)
    }

    private fun assertAnchored(
        label: String,
        viewport: Size,
        pageAspect: Float,
        frames: Int = 28,
        drift: Offset = Offset.Zero
    ) {
        val r = runPinch(viewport, pageAspect, frames = frames, drift = drift)
        println("$label: drift medio ${r.avg}px, máx ${r.max}px")
        // El residuo del anclaje incremental es proporcional al viaje total del centroid
        // (~1-3 px para un pellizco real); el umbral 5 sigue cazando los 40-130 px del bug
        // de clamp (página congelada al centro / salto al desbordar).
        assertTrue("$label deriva ${r.avg}px (máx ${r.max}px)", r.avg < 5f)
    }

    @Test
    fun staticFingersHoldLandscapePageInLandscapeViewport() {
        assertAnchored("apaisada/en-apaisado", Size(1280f, 800f), 1.414f)
    }

    @Test
    fun staticFingersHoldPortraitPageInPortraitViewport() {
        assertAnchored("retrato/en-retrato", Size(1428f, 2143f), 0.7071f)
    }

    @Test
    fun slidingFingersHoldLandscapePageInPortraitViewport() {
        // caso típico de partitura: página apaisada en viewport retrato. Antes del fix la Y
        // quedaba congelada al centro hasta 2.12x y el punto bajo los dedos huía ~90px.
        assertAnchored("apaisada/en-retrato", Size(1428f, 2143f), 1.414f, frames = 24, drift = Offset(1f, 1.5f))
    }

    @Test
    fun slidingFingersHoldPortraitPageInLandscapeViewport() {
        assertAnchored("retrato/en-apaisado", Size(2143f, 1428f), 0.7071f, frames = 24, drift = Offset(1.5f, 1f))
    }
}