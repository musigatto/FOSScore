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

// stroke width as a share of the page height. The floor only keeps a stroke from being zero: it used
// to be 0.002, which is 2 px on a 1000 px page and silently overrode the 1 px setting (the preview
// draws the true width, so a stroke got fatter the moment it was committed).
const val MIN_STROKE_WIDTH = 0.0002f
const val MAX_STROKE_WIDTH = 0.02f

// the user picks the thickness in screen px at zoom 1 (the height of the fitted page); the
// fraction is worked out when the stroke is created. ponytail: on small pages the real cap is
// MAX_STROKE_WIDTH (2% of the height), so the last px of the slider change nothing; raise that
// constant if it bothers you.
const val DEFAULT_PEN_PX = 4f
const val MIN_PEN_PX = 1f
const val MAX_PEN_PX = 20f

fun penWidthPxToFrac(px: Float, fit: RectPx): Float =
    (px / max(1f, fit.height)).coerceIn(MIN_STROKE_WIDTH, MAX_STROKE_WIDTH)

// eraser radius in px at fit (same space as the pen width): the panel sets it
const val MIN_ERASER_PX = 6f
const val MAX_ERASER_PX = 60f
const val DEFAULT_ERASER_PX = 16f

// A word: the strokes that were written as one unit. An InkGroup is what the selection moves and
// deletes; its identity comes from the strokes' groupId, decided when they were written.
data class InkGroup(val bounds: RectPx, val strokes: List<Stroke>)

// Same word if the boxes are close: dx leaves room for the gap between letters, dy is tighter so
// two lines of text written close together do not merge.
// ponytail: a heuristic, calibrated on normal handwriting. Two words written very close end up in
// one group (the selection box shows it before you press the bin, and undo covers it). Upgrade:
// split each line at its largest gap (ICPR gap clustering) or select with a lasso.
const val WORD_GAP_X_FRAC = 0.6f
const val WORD_GAP_Y_FRAC = 0.35f

// A stroke written this soon after the one before, and right next to it, is the next letter of the
// same word. Calibration: ~2 s is a slow letter, and two separate marks usually take longer.
// ponytail: one number for every hand; raise it if a slow writer's words keep splitting.
const val WORD_GAP_MS = 2_000L

/** The stroke's points in px at this fit. */
fun strokePointsPx(st: Stroke, fit: RectPx): List<Offset> =
    decodePoints(st.points).map { normalizedToPx(it, fit) }

/**
 * Limits a drag of a set of points so the WHOLE set stays on the page: the far edge stops at the
 * border. Clamping each point instead pinned them all to the border and flattened the word into a
 * line. Points are normalized (0..1) page coordinates.
 */
fun deltaInside(points: List<Offset>, d: Offset): Offset {
    if (points.isEmpty()) return d
    val loX = -points.minOf { it.x }
    val loY = -points.minOf { it.y }
    return Offset(
        d.x.coerceIn(loX, max(loX, 1f - points.maxOf { it.x })),
        d.y.coerceIn(loY, max(loY, 1f - points.maxOf { it.y })),
    )
}

// box a set of strokes covers, the same one a single stroke gets
private fun boxOf(stroke: Stroke, fit: RectPx): RectPx =
    strokeSelectionRect(strokePointsPx(stroke, fit), stroke.width * fit.height)

private fun boxesOf(strokes: List<Stroke>, fit: RectPx): List<RectPx> = strokes.map { boxOf(it, fit) }

private fun union(boxes: List<RectPx>) = RectPx(
    boxes.minOf { it.left },
    boxes.minOf { it.top },
    boxes.maxOf { it.right } - boxes.minOf { it.left },
    boxes.maxOf { it.bottom } - boxes.minOf { it.top }
)

private fun gapX(a: RectPx, b: RectPx) = max(0f, max(b.left - a.right, a.left - b.right))
private fun gapY(a: RectPx, b: RectPx) = max(0f, max(b.top - a.bottom, a.top - b.bottom))

/**
 * The word a stroke written at [now] joins: the group of [previous] (the last stroke of the page)
 * when it was written [WORD_GAP_MS] ago or less and the new stroke lands within the same gaps
 * [groupInk] uses, or [nextGroup] for a new word.
 *
 * Deciding this once, when the stroke is written, is what stops two words from merging when one is
 * dragged next to the other afterwards: the group is a value in the row, not a distance measured
 * again at every touch.
 *
 * Ink from before v5 has no group (`groupId == 0`) and is still grouped by distance, so it cannot
 * adopt a letter written next to it; that ink keeps the behaviour it had.
 */
fun groupForStroke(
    previous: Stroke?,
    writtenAt: Long,
    now: Long,
    nextGroup: Long,
    stroke: Stroke,
    fit: RectPx,
): Long {
    if (previous == null || previous.groupId == 0L) return nextGroup
    if (now - writtenAt > WORD_GAP_MS) return nextGroup
    val a = boxOf(previous, fit)
    val b = boxOf(stroke, fit)
    val h = max(a.height, b.height).coerceAtLeast(1f)
    val close = gapX(a, b) <= WORD_GAP_X_FRAC * h && gapY(a, b) <= WORD_GAP_Y_FRAC * h
    return if (close) previous.groupId else nextGroup
}

/**
 * Strokes of the page grouped into words. A stroke written since v5 carries the word it was
 * written as ([Stroke.groupId]) and only joins the strokes that share it. Ink from before has no
 * group and is grouped by the gap between its boxes (the bounding-box metric of the
 * handwriting-segmentation literature), union-find over the pairs, thresholded by the median stroke
 * height so it follows the size the user writes at.
 *
 * ponytail: O(n²) over the page's strokes; a page holds a few hundred, and the result is cached by
 * the caller. Upgrade only if a page with thousands of strokes ever lags.
 */
fun groupInk(strokes: List<Stroke>, fit: RectPx): List<InkGroup> {
    if (strokes.isEmpty() || fit.width <= 0f || fit.height <= 0f) return emptyList()
    val boxes = boxesOf(strokes, fit)
    val h = boxes.map { it.height }.sorted()[boxes.size / 2].coerceAtLeast(1f)
    val gx = WORD_GAP_X_FRAC * h
    val gy = WORD_GAP_Y_FRAC * h
    val parent = IntArray(strokes.size) { it }
    fun root(i: Int): Int {
        var r = i
        while (parent[r] != r) {
            parent[r] = parent[parent[r]]
            r = parent[r]
        }
        return r
    }
    for (i in strokes.indices) {
        for (j in i + 1 until strokes.size) {
            val gi = strokes[i].groupId
            val gj = strokes[j].groupId
            val together = if (gi != 0L || gj != 0L) {
                // a word is what was written as one: no distance joins two of them
                gi != 0L && gi == gj
            } else {
                gapX(boxes[i], boxes[j]) <= gx && gapY(boxes[i], boxes[j]) <= gy
            }
            if (together) {
                val a = root(i)
                val b = root(j)
                if (a != b) parent[a] = b
            }
        }
    }
    // one pass in page order, so the group list comes out stable
    val members = LinkedHashMap<Int, MutableList<Int>>()
    for (i in strokes.indices) members.getOrPut(root(i)) { mutableListOf() }.add(i)
    return members.values.map { idx ->
        InkGroup(union(idx.map { boxes[it] }), idx.map { strokes[it] })
    }
}

/**
 * The group under the finger. Inside a letter's hole or between two letters there is no stroke to
 * hit, but the word is still what the user meant: the smallest box wins, so a word sitting inside
 * another group's box still selects the word.
 */
fun groupAt(pos: Offset, groups: List<InkGroup>, padPx: Float): InkGroup? = groups
    .filter {
        pos.x >= it.bounds.left - padPx && pos.x <= it.bounds.right + padPx &&
            pos.y >= it.bounds.top - padPx && pos.y <= it.bounds.bottom + padPx
    }
    .minByOrNull { it.bounds.width * it.bounds.height }

/** Box that wraps a set of strokes (px), the same one a single stroke gets. Null if there is none. */
fun inkBounds(strokes: List<Stroke>, fit: RectPx): RectPx? =
    boxesOf(strokes, fit).takeIf { it.isNotEmpty() }?.let(::union)

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
// word: tap it (that selects it) and press the bin. Upgrade if it ever matters: a "delete the whole
// stroke" mode as an eraser setting; nobody asked for it and the bin already covers it.
fun eraseStrokePoints(pts: List<Offset>, eraser: List<Offset>, radius: Float): List<List<Offset>> {
    if (eraser.isEmpty() || pts.isEmpty()) return listOf(pts)
    val keep = pts.map { minDistanceToPolyline(it, eraser) >= radius }
    if (keep.none { it }) return emptyList()
    // Only the points the eraser removed break a stroke. Splitting on point spacing as well broke
    // strokes nobody touched: a fast stroke stores its points far apart, so a pass anywhere on the
    // page turned it into pieces with a visible white gap between the round caps.
    val runs = mutableListOf<MutableList<Offset>>()
    var cur = mutableListOf<Offset>()
    for (i in pts.indices) {
        if (!keep[i]) {
            if (cur.isNotEmpty()) { runs.add(cur); cur = mutableListOf() }
            continue
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
        minDistanceToPolyline(pos, strokePointsPx(st, fit)) <= tol
    }
}