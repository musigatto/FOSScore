package com.musigatto.fosscore.ui.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.ceil

/**
 * Matemática del zoom "1:1" (ver ZoomMathTest y plan 2026-09-30-zoom-render-1to1).
 *
 * La página se dibuja SIN capa con escala: contenido de tamaño `fit*scale` colocado en
 * `base + offset` (pantalla), overlay vectorial escalado en el propio dibujo. `base` es el
 * centrado de la página en el viewport a escala 1 (pageFit.left/top). Todo en px.
 */
object ZoomMath {

    /**
     * Anclaje del pellizco: al multiplicar el zoom por [k], el punto contenido bajo [anchor]
     * (pantalla) queda quieto. Es la misma fórmula clásica `k*offset + (1-k)*anchor` con la
     * base de centrado restada del punto de anclaje (el origen del contenido ya no es el del
     * viewport: la página está centrada en `base`).
     */
    fun anchoredOffset(offset: Offset, k: Float, anchor: Offset, base: Offset): Offset =
        Offset(
            offset.x * k + (1f - k) * (anchor.x - base.x),
            offset.y * k + (1f - k) * (anchor.y - base.y)
        )

    /**
     * Rango del paneo: el contenido (de tamaño [content] en pantalla a escala 1) ampliado por
     * [scale] no puede despegarse del viewport. Si cabe, se bloquea centrado; si desborda, se
     * acota el offset entre las dos posiciones en las que una arista del contenido toca el
     * borde opuesto del viewport.
     */
    fun clampOffset(offset: Offset, content: Size, viewport: Size, scale: Float, base: Offset): Offset =
        Offset(
            clampAxis(offset.x, content.width, viewport.width, scale, base.x),
            clampAxis(offset.y, content.height, viewport.height, scale, base.y)
        )

    private fun clampAxis(o: Float, content: Float, viewport: Float, scale: Float, base: Float): Float {
        if (content * scale <= viewport) return content * (1f - scale) / 2f
        val min = viewport - content * scale - base
        val max = -base
        return o.coerceIn(min, max)
    }

    /**
     * Escalón de re-render: el bitmap se renderiza al siguiente 10% del encaje (1.0, 1.1, ...).
     * Dentro de un gesto las peticiones de render se caen en los mismos escalones y la caché de
     * MuPdfDoc las reaprovecha; al terminar, el render final coincide con el escalón visual.
     */
    fun renderBucket(scale: Float): Float {
        val bucket = ceil(scale * 10f) / 10f
        return if (bucket < 1f) 1f else bucket
    }
}