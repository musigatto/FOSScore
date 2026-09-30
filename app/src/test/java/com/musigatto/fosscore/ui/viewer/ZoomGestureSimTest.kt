package com.musigatto.fosscore.ui.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

/**
 * Reproduce NUMÉRICAMENTE el bucle del pellizco de PdfViewerScreen (anclaje → clamp) con las
 * funciones de producción (ZoomMath, fitRect) y mide el residuo del anclaje: cuánto se ha movido en
 * pantalla el punto de página que estaba bajo el dedo con el que se apuntaba.
 *
 * El foco es FIJO (el primer dedo, no el punto medio de los dos) y no sigue el deslizamiento, así
 * que el residuo debe ser ~0 aunque el otro dedo se mueva. Antes el ancla era el centroide vivo más
 * un pan de 2 dedos: el punto apuntado se escapaba con el viaje de los dedos. El clamp puede forzar
 * el offset en los bordes: por eso el umbral es 5 px y no 0.
 */
class ZoomGestureSimTest {

    class Drift(val avg: Float, val max: Float)

    /**
     * Dos dedos que se abren [zoomTotal]×: el primero (el que apunta) se queda en [fingers.x] y el
     * segundo se abre hacia [fingers.y] y además se desliza [drift] px/frame, como en un pellizco
     * real sobre un compás.
     */
    private fun runPinch(
        viewport: Size,
        pageAspect: Float,
        fingers: Pair<Offset, Offset>,
        frames: Int = 28,
        zoomTotal: Float = 2.5f,
        drift: Offset = Offset.Zero
    ): Drift {
        val fit = fitRect(viewport.width, viewport.height, pageAspect)
        val content = Size(fit.width, fit.height)
        val base = Offset(fit.left, fit.top)
        val (aim, open) = fingers
        val aim0 = aim
        val open0 = open

        var scale = 1f
        var offset = Offset.Zero
        // foco: el dedo que apunta, fijo durante todo el gesto
        val anchor = aim0
        val f0 = Offset((aim0.x - base.x) / scale, (aim0.y - base.y) / scale)
        var anchorDist = 0f
        var prevD = -1f
        var maxDrift = 0f
        var sumDrift = 0f

        for (t in 1 until frames) {
            val f = t.toFloat() / (frames - 1)
            // el dedo que abre se aleja y se desliza; el que apunta se queda quieto
            val openNow = Offset(
                open0.x + (open0.x - aim0.x) * (zoomTotal - 1f) * f + drift.x * t,
                open0.y + (open0.y - aim0.y) * (zoomTotal - 1f) * f + drift.y * t
            )
            val d = (openNow - aim0).getDistance()

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
                        // ancla al foco fijo (primer dedo), NO al punto medio ni al centroide vivo
                        offset = ZoomMath.anchoredOffset(offset, k, anchor, base)
                        scale = target
                    }
                }
            }
            // sin pan de 2 dedos: el foco no sigue el deslizamiento
            offset = ZoomMath.clampOffset(offset, content, viewport, scale, base)
            prevD = d

            val screen = Offset(base.x + offset.x + f0.x * scale, base.y + offset.y + f0.y * scale)
            val dr = (screen - anchor).getDistance()
            maxDrift = maxOf(maxDrift, dr)
            sumDrift += dr
        }
        return Drift(sumDrift / (frames - 1), maxDrift)
    }

    private fun assertPinned(
        label: String,
        viewport: Size,
        pageAspect: Float,
        fingers: Pair<Offset, Offset>,
        frames: Int = 28,
        drift: Offset = Offset.Zero
    ) {
        val r = runPinch(viewport, pageAspect, fingers, frames = frames, drift = drift)
        println("$label: deriva del foco medio ${r.avg}px, máx ${r.max}px")
        assertTrue("$label deriva ${r.avg}px (máx ${r.max}px)", r.avg < 5f)
    }

    // partitura apaisada en el S6 Lite (1200x2000, encaje 1200x1569 @ (0,216)): el usuario apunta
    // con un dedo y abre con el otro
    private val vp = Size(1200f, 2000f)
    private val partitura = 0.7648f

    @Test
    fun aimedTopLeftStaysPinned() {
        // un dedo en (150,300) (arriba izquierda) y el otro abriendo hacia (700,700)
        assertPinned("apunta-arriba-izq", vp, partitura, Offset(150f, 300f) to Offset(700f, 700f))
    }

    @Test
    fun aimedBottomRightStaysPinned() {
        assertPinned("apunta-abajo-der", vp, partitura, Offset(1050f, 1600f) to Offset(500f, 1100f))
    }

    @Test
    fun aimedTopRightStaysPinned() {
        assertPinned("apunta-arriba-der", vp, partitura, Offset(1050f, 300f) to Offset(400f, 900f))
    }

    @Test
    fun aimedBottomLeftStaysPinned() {
        assertPinned("apunta-abajo-izq", vp, partitura, Offset(150f, 1600f) to Offset(800f, 900f))
    }

    /**
     * Los dos dedos juntos (caso simétrico): el foco es el del primer dedo, así que el otro extremo
     * se abre. Es el cambio de comportamiento pedido: el punto medio dejaría el anclaje a la mitad
     * del hueco entre dedos.
     */
    @Test
    fun bothFingersTogetherStayPinnedOnTheFirstOne() {
        assertPinned("dos-dedos-juntos", vp, partitura, Offset(500f, 900f) to Offset(560f, 905f), frames = 24)
    }

    @Test
    fun slidingSecondFingerKeepsFocus() {
        // el segundo dedo se desliza mucho: el compás apuntado no debe moverse
        assertPinned("segundo-dedo-se-desliza", vp, partitura, Offset(150f, 300f) to Offset(700f, 700f), frames = 24, drift = Offset(4f, 6f))
    }

    @Test
    fun landscapePageInPortraitViewport() {
        assertPinned("apaisada/en-retrato", Size(1428f, 2143f), 1.414f, Offset(300f, 400f) to Offset(900f, 1000f), frames = 24)
    }

    @Test
    fun portraitPageInLandscapeViewport() {
        assertPinned("retrato/en-apaisado", Size(2143f, 1428f), 0.7071f, Offset(500f, 300f) to Offset(1300f, 900f), frames = 24)
    }
}
