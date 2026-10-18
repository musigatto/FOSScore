package com.musigatto.fosscore.ui.viewer

import androidx.compose.ui.geometry.Offset
import com.musigatto.fosscore.library.Stamp
import com.musigatto.fosscore.library.Stroke
import kotlin.math.max
import kotlin.math.min

// one page saved for undo: stamps + strokes
data class PageEdit(val stamps: List<Stamp>, val strokes: List<Stroke>)

// smallest gap in px between points of a stroke; anything closer is dropped
const val MIN_SAMPLE_DIST_PX = 2f

// stroke width as a share of the page height (0.002..0.02)
const val MIN_STROKE_WIDTH = 0.002f
const val MAX_STROKE_WIDTH = 0.02f

// the user picks the thickness in screen px at zoom 1 (the height of the fitted page); the
// fraction is worked out when the stroke is created. ponytail: on small pages the real cap is
// MAX_STROKE_WIDTH (2% of the height), so the last px of the slider change nothing; raise that
// constant if it bothers you.
const val DEFAULT_PEN_PX = 8f
const val MIN_PEN_PX = 1f
const val MAX_PEN_PX = 20f

fun penWidthPxToFrac(px: Float, fit: RectPx): Float =
    (px / max(1f, fit.height)).coerceIn(MIN_STROKE_WIDTH, MAX_STROKE_WIDTH)

// eraser radius as a share of the page height (px = frac * fit.height)
const val ERASE_RADIUS_FRAC = 0.02f

// Writing without fully letting go: if the next stroke starts less than STROKE_MERGE_MS after
// the previous one, and close to where it ended, it joins onto it (one word = one stroke, even
// if the pen lifts). ponytail: the gap gets bridged with a straight line (it is a few ms wide).
// 400ms keeps words apart but still joins letters; drop to ~150ms to demand steady writing.
// There is a Log.d ("ink: merge=...") to see the real gap/dist on the device and tune it by hand.
const val STROKE_MERGE_MS = 400L
const val STROKE_MERGE_DIST_FRAC = 0.04f

// the stroke just written, so the next one can join it
class InkTail {
    var last: Stroke? = null
    var at: Long = 0L
}

// does the new stroke continue the last one? same style, short pause, close to its end
fun shouldMergeStroke(
    prevEndPx: Offset,
    newStartPx: Offset,
    fit: RectPx,
    gapMs: Long,
    sameStyle: Boolean
): Boolean = sameStyle && gapMs <= STROKE_MERGE_MS &&
    (newStartPx - prevEndPx).getDistance() <= STROKE_MERGE_DIST_FRAC * fit.height

// drops points that land closer than minDist to the one before
fun samplePoints(pts: List<Offset>, minDist: Float): List<Offset> {
    if (pts.size < 2) return pts
    val out = ArrayList<Offset>(pts.size)
    var last = pts.first()
    out.add(last)
    for (p in pts.drop(1)) {
        if ((p - last).getDistance() >= minDist) {
            out.add(p)
            last = p
        }
    }
    return out
}

fun encodePoints(points: List<Offset>): String =
    points.joinToString("|") { "${it.x};${it.y}" }

fun decodePoints(s: String): List<Offset> {
    val out = ArrayList<Offset>()
    for (raw in s.split('|')) {
        val parts = raw.split(';')
        if (parts.size == 2) {
            val x = parts[0].toFloatOrNull()
            val y = parts[1].toFloatOrNull()
            if (x != null && y != null) out.add(Offset(x, y))
        }
    }
    return out
}

fun distanceToSegment(p: Offset, a: Offset, b: Offset): Float {
    val abx = b.x - a.x
    val aby = b.y - a.y
    val len2 = abx * abx + aby * aby
    if (len2 == 0f) return (p - a).getDistance()
    var t = ((p.x - a.x) * abx + (p.y - a.y) * aby) / len2
    t = t.coerceIn(0f, 1f)
    return (p - Offset(a.x + abx * t, a.y + aby * t)).getDistance()
}

fun minDistanceToPolyline(p: Offset, poly: List<Offset>): Float {
    if (poly.isEmpty()) return Float.MAX_VALUE
    if (poly.size == 1) return (p - poly[0]).getDistance()
    var best = Float.MAX_VALUE
    for (i in 0 until poly.size - 1) {
        best = min(best, distanceToSegment(p, poly[i], poly[i + 1]))
    }
    return best
}

// removes from a stroke the points that fall within radius of the eraser trail and returns the
// surviving pieces (separated ends become separate strokes); empty = the whole thing went.
// ponytail: the eraser is always partial (you graze what you want gone). To remove a whole
// word: tap it (that selects it) and press 🗑. Upgrade if it ever matters: a "delete the whole
// stroke" mode as an eraser setting; nobody asked for it and 🗑 already covers it.
fun eraseStrokePoints(pts: List<Offset>, eraser: List<Offset>, radius: Float): List<List<Offset>> {
    if (eraser.isEmpty() || pts.isEmpty()) return listOf(pts)
    val keep = pts.map { minDistanceToPolyline(it, eraser) >= radius }
    if (keep.none { it }) return emptyList()
    val runs = mutableListOf<MutableList<Offset>>()
    var cur = mutableListOf<Offset>()
    // ponytail: a gap wider than 2*radius between survivors splits the pieces; it gets it wrong
    // if a stroke has naturally very spread-out points. Upgrade: split by distance to the trail.
    val splitGap = radius * 2f
    for (i in pts.indices) {
        if (!keep[i]) {
            if (cur.isNotEmpty()) { runs.add(cur); cur = mutableListOf() }
            continue
        }
        if (cur.isNotEmpty() && (pts[i] - cur.last()).getDistance() > splitGap) {
            runs.add(cur); cur = mutableListOf()
        }
        cur.add(pts[i])
    }
    if (cur.isNotEmpty()) runs.add(cur)
    return runs
}

// the box that wraps the points (px) — the selection box around a stroke
fun strokeBounds(pts: List<Offset>): RectPx {
    if (pts.isEmpty()) return RectPx(0f, 0f, 0f, 0f)
    var l = Float.MAX_VALUE
    var t = Float.MAX_VALUE
    var r = -Float.MAX_VALUE
    var b = -Float.MAX_VALUE
    for (p in pts) {
        if (p.x < l) l = p.x
        if (p.y < t) t = p.y
        if (p.x > r) r = p.x
        if (p.y > b) b = p.y
    }
    return RectPx(l, t, r - l, b - t)
}

fun scalePoints(pts: List<Offset>, center: Offset, factor: Float): List<Offset> =
    pts.map { Offset(center.x + (it.x - center.x) * factor, center.y + (it.y - center.y) * factor) }

// the stroke under the finger (tolerance = the handle or the thickness, whichever is bigger)
fun hitStroke(pos: Offset, strokes: List<Stroke>, fit: RectPx): Stroke? {
    if (fit.width <= 0f) return null
    return strokes.lastOrNull { st ->
        val tol = max(handleRadiusPx(fit), st.width * fit.height)
        minDistanceToPolyline(pos, decodePoints(st.points).map { normalizedToPx(it, fit) }) <= tol
    }
}