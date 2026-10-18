package com.musigatto.fosscore.ui.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.ceil

/**
 * The maths behind the "1:1" zoom (see ZoomMathTest and plan 2026-09-30-zoom-render-1to1).
 *
 * The page is drawn WITHOUT a scaled layer: content the size of `fit*scale` placed at
 * `base + offset` (screen), and the vector overlay scaled as it is drawn. `base` is where the
 * page sits centred in the viewport at scale 1 (pageFit.left/top). Everything is in px.
 */
object ZoomMath {

    /**
     * Pinch anchor: multiplying the zoom by [k] keeps the content under [anchor] (screen) still.
     * It is the usual `k*offset + (1-k)*anchor`, with the centring base taken off the anchor
     * point (the content no longer starts at the viewport corner: the page is centred on `base`).
     */
    fun anchoredOffset(offset: Offset, k: Float, anchor: Offset, base: Offset): Offset =
        Offset(
            offset.x * k + (1f - k) * (anchor.x - base.x),
            offset.y * k + (1f - k) * (anchor.y - base.y)
        )

    /**
     * Pan range: the content ([content] in screen px at scale 1) enlarged by [scale] so it still
     * touches the viewport — one single, continuous range (no jump when it goes from fitting the
     * screen to overflowing it). At scale 1 (whole page in view) it stays centred.
     */
    fun clampOffset(offset: Offset, content: Size, viewport: Size, scale: Float, base: Offset): Offset =
        Offset(
            clampAxis(offset.x, content.width, viewport.width, scale, base.x),
            clampAxis(offset.y, content.height, viewport.height, scale, base.y)
        )

    private fun clampAxis(o: Float, content: Float, viewport: Float, scale: Float, base: Float): Float {
        // With no zoom the view is the whole page: always centred.
        if (scale <= 1f) return 0f
        val scaled = content * scale
        // ponytail: clamp with no gaps — if the content fits it is centred (no panning); if it
        // overflows, the edge sticks to the border (no half-empty viewport).
        return if (scaled <= viewport) {
            (viewport - scaled) / 2f - base
        } else {
            o.coerceIn(viewport - scaled - base, -base)
        }
    }

    /**
     * Re-draw step: the bitmap is drawn at the next 10% of the fit (1.0, 1.1, ...). During a
     * gesture the drawing requests land on the same steps and MuPdfDoc's cache reuses them; when
     * the gesture ends, the final draw matches the step already on screen.
     */
    fun renderBucket(scale: Float): Float {
        val bucket = ceil(scale * 10f) / 10f
        return if (bucket < 1f) 1f else bucket
    }
}