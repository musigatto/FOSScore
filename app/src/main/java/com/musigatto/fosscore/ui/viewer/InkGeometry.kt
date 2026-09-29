package com.musigatto.fosscore.ui.viewer

import androidx.compose.ui.geometry.Offset
import com.musigatto.fosscore.library.Stamp
import com.musigatto.fosscore.library.Stroke
import kotlin.math.max
import kotlin.math.min

// snapshot de una página para undo: sellos + trazos
data class PageEdit(val stamps: List<Stamp>, val strokes: List<Stroke>)

// distancia mínima en px entre puntos de un trazo (downsample)
const val MIN_SAMPLE_DIST_PX = 2f

// ancho del trazo en fracción del alto de página (0.002..0.02; ~0.8% por defecto)
const val DEFAULT_STROKE_WIDTH = 0.008f
const val MIN_STROKE_WIDTH = 0.002f
const val MAX_STROKE_WIDTH = 0.02f

// radio de la goma como fracción del alto de página (px = frac * fit.height)
const val ERASE_RADIUS_FRAC = 0.02f

// Al escribir sin soltar del todo: si el siguiente trazo empieza antes de STROKE_MERGE_MS y
// cerca del final del anterior, se pega al mismo (una palabra = un trazo, aunque el stylus alza).
// ponytail: los puntos se unen con un segmento recto (el hueco son unos ms). 400ms separa
// palabras pero junta letras; bajar a ~150ms para exigir escritura continua. Hay un Log.d
// ("ink: merge=...") para ver gap/dist reales en el dispositivo y ajustar a mano.
const val STROKE_MERGE_MS = 400L
const val STROKE_MERGE_DIST_FRAC = 0.04f

// último trazo recién escrito, para poder pegarle el siguiente
class InkTail {
    var last: Stroke? = null
    var at: Long = 0L
}

// ¿el trazo nuevo continúa el anterior? mismo estilo, pausa breve y cerca de su final
fun shouldMergeStroke(
    prevEndPx: Offset,
    newStartPx: Offset,
    fit: RectPx,
    gapMs: Long,
    sameStyle: Boolean
): Boolean = sameStyle && gapMs <= STROKE_MERGE_MS &&
    (newStartPx - prevEndPx).getDistance() <= STROKE_MERGE_DIST_FRAC * fit.height

// descarta puntos consecutivos a menos de minDist del anterior
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

// quita de un trazo los puntos que caen a < radius del rastro de la goma y devuelve los
// tramos supervivientes (los extremos separados quedan como trazos distintos); vacío = borrado entero.
// ponytail: la goma siempre es parcial (rozas lo que quieres quitar). Para quitar una palabra
// entera: tocarla (la selecciona) y luego 🗑. Upgrade si hiciera falta: un modo "borra el trazo
// entero" como ajuste de la goma; no se pidió y el 🗑 ya lo cubre.
fun eraseStrokePoints(pts: List<Offset>, eraser: List<Offset>, radius: Float): List<List<Offset>> {
    if (eraser.isEmpty() || pts.isEmpty()) return listOf(pts)
    val keep = pts.map { minDistanceToPolyline(it, eraser) >= radius }
    if (keep.none { it }) return emptyList()
    val runs = mutableListOf<MutableList<Offset>>()
    var cur = mutableListOf<Offset>()
    // ponytail: un hueco > 2*radio entre supervivientes separa tramos; falla si un trazo tiene
    // muestras naturalmente muy espaciadas. Upgrade: decidir por cercanía al rastro de la goma.
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

// caja que envuelve los puntos (px) — el recuadro de selección de un trazo
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

// trazo bajo el dedo (tolerancia = el tirador o el grosor, lo que sea mayor)
fun hitStroke(pos: Offset, strokes: List<Stroke>, fit: RectPx): Stroke? {
    if (fit.width <= 0f) return null
    return strokes.lastOrNull { st ->
        val tol = max(handleRadiusPx(fit), st.width * fit.height)
        minDistanceToPolyline(pos, decodePoints(st.points).map { normalizedToPx(it, fit) }) <= tol
    }
}