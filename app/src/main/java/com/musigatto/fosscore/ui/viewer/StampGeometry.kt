package com.musigatto.fosscore.ui.viewer

import androidx.compose.ui.geometry.Offset
import com.musigatto.fosscore.library.Stamp
import kotlin.math.min

data class RectPx(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float
) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height

    fun contains(o: Offset): Boolean =
        o.x >= left && o.x <= right && o.y >= top && o.y <= bottom
}

// ponytail: tamaño del sello como fracción de alto de página (0.05..0.3); un slider de
// tamaños absolutos se añadiría cuando el usuario lo pida.
const val MIN_STAMP_SIZE = 0.05f
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