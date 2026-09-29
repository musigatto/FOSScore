package com.musigatto.fosscore.ui.viewer

import androidx.compose.ui.geometry.Offset
import com.musigatto.fosscore.library.Stamp
import kotlin.math.max
import kotlin.math.min

data class RectPx(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float
) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height

    fun center(): Offset = Offset(left + width / 2f, top + height / 2f)

    fun contains(o: Offset): Boolean =
        o.x >= left && o.x <= right && o.y >= top && o.y <= bottom
}

// ponytail: tamaño del sello como fracción de alto de página (0.015..0.3); un slider de
// tamaños absolutos se añadiría cuando el usuario lo pida.
const val MIN_STAMP_SIZE = 0.015f
const val MAX_STAMP_SIZE = 0.3f
const val DEFAULT_STAMP_SIZE = 0.09f

fun fitRect(slotW: Float, slotH: Float, aspect: Float): RectPx {
    if (slotW <= 0f || slotH <= 0f || aspect <= 0f) return RectPx(0f, 0f, 0f, 0f)
    val width = min(slotW, slotH * aspect)
    val height = width / aspect
    return RectPx((slotW - width) / 2f, (slotH - height) / 2f, width, height)
}

fun normalizedToPx(n: Offset, fit: RectPx): Offset =
    Offset(fit.left + n.x * fit.width, fit.top + n.y * fit.height)

fun pxToNormalized(p: Offset, fit: RectPx): Offset =
    Offset((p.x - fit.left) / fit.width, (p.y - fit.top) / fit.height)

// x/y = centro normalizado del sello sobre el lienzo; el rect puede sobresalir en bordes
// (un sello medio fuera de página es legítimo), el centro siempre queda dentro tras clamp.
fun stampRect(stamp: Stamp, fit: RectPx): RectPx {
    val side = stamp.size.coerceIn(MIN_STAMP_SIZE, MAX_STAMP_SIZE) * fit.height
    val center = normalizedToPx(
        Offset(stamp.x.coerceIn(0f, 1f), stamp.y.coerceIn(0f, 1f)),
        fit
    )
    return RectPx(center.x - side / 2f, center.y - side / 2f, side, side)
}

fun hitStamp(pos: Offset, stamps: List<Stamp>, fit: RectPx): Stamp? =
    stamps.lastOrNull { stampRect(it, fit).contains(pos) }

// Tiradores del recuadro de selección: 4 quadratitos en las esquinas,jut FUERA del recuadro
// (como en cualquier editor) para que arrastrar dentro siga moviendo y no reescalando por error.
private const val HANDLE_RADIUS = 0.0045f // radio del quadratito, fracción del alto de página
private const val HANDLE_HIT_SLOP = 4f   // el toque es 4x el radio: se ve pequeño pero se agarra bien

fun handleRadiusPx(fit: RectPx): Float = HANDLE_RADIUS * fit.height

private fun corners(sel: RectPx): List<Offset> = listOf(
    Offset(sel.left, sel.top),
    Offset(sel.right, sel.top),
    Offset(sel.right, sel.bottom),
    Offset(sel.left, sel.bottom)
)

/** Centros de los 4 tiradores (px), empujados hacia fuera desde el centro del recuadro. */
fun handleCenters(sel: RectPx, fit: RectPx): List<Offset> {
    val r = handleRadiusPx(fit)
    val c = sel.center()
    return corners(sel).map { k ->
        val d = k - c
        val len = d.getDistance()
        if (len <= 1e-3f) k else Offset(k.x + d.x / len * r, k.y + d.y / len * r)
    }
}

/** Índice del tirador bajo [pos] (0..3), o null. */
fun hitHandle(pos: Offset, sel: RectPx, fit: RectPx): Int? {
    val tol = handleRadiusPx(fit) * HANDLE_HIT_SLOP
    return handleCenters(sel, fit).indexOfFirst { (it - pos).getDistance() <= tol }
        .takeIf { it >= 0 }
}

// factor de escala al arrastrar un tirador = (dist actual al centro) / (dist inicial al centro),
// acotado para que el tirador pegado al centro no dispare la escala a infinito.
const val MIN_SCALE_FACTOR = 0.1f
const val MAX_SCALE_FACTOR = 6f

fun scaleFactor(center: Offset, start: Offset, now: Offset): Float {
    val d0 = (start - center).getDistance()
    if (d0 <= 1e-3f) return 1f
    return ((now - center).getDistance() / d0).coerceIn(MIN_SCALE_FACTOR, MAX_SCALE_FACTOR)
}

// rectángulo de selección de un trazo: caja de sus puntos + margen de medio grosor
fun strokeSelectionRect(pointsPx: List<Offset>, widthPx: Float): RectPx {
    if (pointsPx.isEmpty()) return RectPx(0f, 0f, 0f, 0f)
    val b = strokeBounds(pointsPx)
    val pad = max(widthPx / 2f, 1f)
    return RectPx(b.left - pad, b.top - pad, b.width + pad * 2f, b.height + pad * 2f)
}