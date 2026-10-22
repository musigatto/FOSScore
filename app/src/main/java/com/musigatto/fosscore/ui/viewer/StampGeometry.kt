package com.musigatto.fosscore.ui.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.musigatto.fosscore.library.Stamp
import kotlin.math.max
import kotlin.math.min

// side of the loupe that opens on a long press while a stamp is armed (screen dp, radius)
const val LOUPE_RADIUS_DP = 58f
const val LOUPE_MARGIN_DP = 8f
const val LOUPE_LIFT = 1.9f   // its center sits this many radii above the finger

/**
 * Center of the loupe for a finger at [finger] (screen px): above it, never closer than [margin] to
 * the viewport edge, so it stays readable when you annotate the top of the page.
 */
fun loupeCenter(
    finger: Offset,
    radius: Float,
    margin: Float,
    viewport: Size,
): Offset {
    val min = radius + margin
    val x = finger.x.coerceIn(min, max(min, viewport.width - min))
    val y = (finger.y - radius * LOUPE_LIFT).coerceAtLeast(min)
    return Offset(x, y)
}

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
        o.x in left..right && o.y >= top && o.y <= bottom
}

// The user picks the stamp side in screen px at zoom 1 (the height of the fitted page), same as the
// pen width; the fraction is worked out when it is created. The slider stops at 100 px: past that
// every step is a big jump and the block covers half the page. Stamps already placed keep their
// stored size (MAX_STAMP_SIZE is unchanged); only the size of new ones is capped.
const val MIN_STAMP_SIZE = 0.004f
const val MAX_STAMP_SIZE = 0.3f

const val DEFAULT_STAMP_PX = 30f
const val MIN_STAMP_PX = 8f
const val MAX_STAMP_PX = 100f

fun stampSizePxToFrac(px: Float, fit: RectPx): Float =
    (px / max(1f, fit.height)).coerceIn(MIN_STAMP_SIZE, MAX_STAMP_SIZE)

fun stampSizeFracToPx(frac: Float, fit: RectPx): Float = frac * fit.height

/**
 * Stamps the eraser trail takes with it. The eraser is a circle of [radiusPx] around the trail, so a
 * stamp counts when the trail passes within that radius of its box — not only when a sample lands
 * inside it, which made grazing one feel like it was ignored.
 */
fun stampsTouched(
    stamps: List<Stamp>,
    fit: RectPx,
    path: List<Offset>,
    radiusPx: Float,
): List<Stamp> {
    if (path.isEmpty()) return emptyList()
    // The trail is a line, not a row of dots: walk it in small steps so a fast drag cannot jump
    // over a stamp between two samples. ponytail: 4 px steps; they only fail for a radius under
    // 4 px, which the eraser panel does not allow (MIN_ERASER_PX is 6).
    val points = ArrayList<Offset>()
    for (i in 0 until path.size - 1) {
        val a = path[i]
        val b = path[i + 1]
        val steps = max(1, ((b - a).getDistance() / 4f).toInt())
        for (s in 0 until steps) points.add(a + (b - a) * (s / steps.toFloat()))
    }
    points.add(path.last())
    return stamps.filter { st ->
        val r = stampRect(st, fit)
        points.any { p ->
            p.x >= r.left - radiusPx && p.x <= r.right + radiusPx &&
                p.y >= r.top - radiusPx && p.y <= r.bottom + radiusPx
        }
    }
}

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

// x/y = the stamp's center, as a share of the page; the box may stick out past the edges
// (a stamp half off the page is fine), but the center is always kept inside.
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

// Selection box handles: 4 little squares on the corners, just OUTSIDE the box (as in any
// editor) so dragging inside still moves it instead of resizing it by mistake. Strokes use them;
// stamps are sized with the slider, so they get no handles.
private const val HANDLE_RADIUS = 0.0045f // little-square radius, as a share of the page height
private const val HANDLE_HIT_SLOP = 4f   // the touch area is 4x the radius: looks small, grabs well

fun handleRadiusPx(fit: RectPx): Float = HANDLE_RADIUS * fit.height

/** Centres of the 4 handles (px), pushed outwards from the center of the box. */
fun handleCenters(sel: RectPx, fit: RectPx): List<Offset> {
    val r = handleRadiusPx(fit)
    val c = sel.center()
    return listOf(
        Offset(sel.left, sel.top),
        Offset(sel.right, sel.top),
        Offset(sel.right, sel.bottom),
        Offset(sel.left, sel.bottom)
    ).map { k ->
        val d = k - c
        val len = d.getDistance()
        if (len <= 1e-3f) k else Offset(k.x + d.x / len * r, k.y + d.y / len * r)
    }
}

/** Index of the handle under [pos] (0..3), or null. */
fun hitHandle(pos: Offset, sel: RectPx, fit: RectPx): Int? {
    val tol = handleRadiusPx(fit) * HANDLE_HIT_SLOP
    return handleCenters(sel, fit).indexOfFirst { (it - pos).getDistance() <= tol }
        .takeIf { it >= 0 }
}

// scale factor while dragging a handle = (distance to the center now) / (distance when it
// started), capped so a handle sitting in the center cannot blow the scale up to infinity.
const val MIN_SCALE_FACTOR = 0.1f
const val MAX_SCALE_FACTOR = 6f

fun scaleFactor(center: Offset, start: Offset, now: Offset): Float {
    val d0 = (start - center).getDistance()
    if (d0 <= 1e-3f) return 1f
    return ((now - center).getDistance() / d0).coerceIn(MIN_SCALE_FACTOR, MAX_SCALE_FACTOR)
}

// selection box for a stroke: the box around its points, plus half the thickness of margin
fun strokeSelectionRect(pointsPx: List<Offset>, widthPx: Float): RectPx {
    if (pointsPx.isEmpty()) return RectPx(0f, 0f, 0f, 0f)
    val b = strokeBounds(pointsPx)
    val pad = max(widthPx / 2f, 1f)
    return RectPx(b.left - pad, b.top - pad, b.width + pad * 2f, b.height + pad * 2f)
}