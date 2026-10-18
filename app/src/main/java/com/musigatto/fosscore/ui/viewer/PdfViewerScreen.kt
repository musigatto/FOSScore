package com.musigatto.fosscore.ui.viewer

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.musigatto.fosscore.FOSScoreApp
import com.musigatto.fosscore.R
import com.musigatto.fosscore.library.Stamp
import com.musigatto.fosscore.library.Stroke
import com.musigatto.fosscore.pdf.ExportError
import com.musigatto.fosscore.pdf.InkStroke
import com.musigatto.fosscore.pdf.MuPdfAnnots
import com.musigatto.fosscore.pdf.MuPdfDoc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.core.graphics.createBitmap

// The render (memory) cap now lives in MuPdfDoc.MAX_RENDER_PIXELS: MuPDF draws at whatever
// resolution we ask for, so zoom looks sharp instead of slightly soft.
private const val LOG_TAG = "FOSScore-PDF"

// Touch noise with still fingers: ±1 px over ~400 px between fingers ≈ 0.25%. A 0.4% dead zone
// stops the zoom reacting to that noise (without it the content "breathes" and looks shaky).
private const val ZOOM_NOISE_RATIO = 0.004f

// Zoom the double tap jumps to (2.5x: readable without losing sight of the page).
private const val DOUBLE_TAP_ZOOM = 2.5f

// The double tap is animated (~1 frame would teleport to the corner): 250 ms, with the exact
// anchor on every frame.
private const val DOUBLE_TAP_ANIM_MS = 250

// Zoom cap. Reference viewers do NOT scale the page with a transform: they redraw it at the
// current zoom, so the GPU never rescales the texture. Our layer does scale it, which is why we
// have to cap it: with MAX_RENDER_PIXELS = 4M and a page that fits in ~1.6M px, the most MuPDF
// can hand over without rescaling is ~2.5x. Past that, the GPU stretch (nearest neighbor) shows.
// Only raise it if you also raise the pixel budget in MuPdfDoc.
private const val MAX_ZOOM = 2.5f

// Re-draw during a pinch: minimum gap between draws (150 ms) and a settling wait for the final
// draw (250 ms with no zoom change). Reference viewers also draw during the gesture; kept to a
// minimum, the bitmap follows the zoom and the GPU never rescales the texture.
private const val RENDER_DURING_ZOOM_MS = 150L
private const val RENDER_SETTLE_MS = 250L

// state of the "stretch a handle" gesture: which object scales from which corner
private sealed interface Resizing {
    val startPos: Offset

    data class OfStamp(
        val stamp: Stamp,
        override val startPos: Offset
    ) : Resizing

    data class OfStroke(
        val stroke: Stroke,
        val ptsPx: List<Offset>,
        val center: Offset,   // center of the box: the scale grows outwards from here
        override val startPos: Offset
    ) : Resizing
}

// inverts page colors (white bg -> black, ink -> white) for OLED dark mode; applied as a
// draw-time colorFilter so the render cache is untouched.
private val INVERT_MATRIX = floatArrayOf(
    -1f, 0f, 0f, 0f, 255f,
    0f, -1f, 0f, 0f, 255f,
    0f, 0f, -1f, 0f, 255f,
    0f, 0f, 0f, 1f, 0f
)
private val INVERT_FILTER = ColorFilter.colorMatrix(ColorMatrix(INVERT_MATRIX))

// the same negative as INVERT_MATRIX, for what is drawn with a color instead of a bitmap
internal fun Color.inverted(): Color = Color(1f - red, 1f - green, 1f - blue, alpha)

private fun centroid(changes: List<PointerInputChange>): Offset =
    changes.fold(Offset.Zero) { acc, c -> acc + c.position } / changes.size.toFloat()

@Composable
fun PdfViewerScreen(
    pdfUri: Uri,
    onBack: () -> Unit,
    themeMode: ThemeMode,
    onToggleTheme: () -> Unit,
    sheetHash: String? = null
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    var mupdfDoc by remember { mutableStateOf<MuPdfDoc?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    var currentPage by rememberSaveable(pdfUri) {
        mutableIntStateOf(Settings.lastPage(context, pdfUri.toString()))
    }
    var twoUp by rememberSaveable { mutableStateOf(isLandscape) }
    var halfEnabled by rememberSaveable { mutableStateOf(false) }
    var halfTurned by rememberSaveable { mutableStateOf(false) }
    var navVisible by rememberSaveable { mutableStateOf(true) }
    var error by remember { mutableStateOf<Int?>(null) }
    // double tap: fit <-> zoom. We keep the time and the point of the previous tap.
    var lastTapMs by remember { mutableLongStateOf(0L) }
    var lastTapPos by remember { mutableStateOf(Offset.Zero) }
    val doubleTapSlopPx = with(LocalDensity.current) { 40.dp.toPx() }
    // SnapshotStateMap: changing one bitmap only redraws whoever reads THAT key. With a plain
    // Map, changing a bitmap invalidated the Image + the ink Canvas + all the stamps at once
    // (that was the jolt felt when the sharpness changed mid-pinch).
    val bitmaps = remember { mutableStateMapOf<Int, Bitmap>() }
    // bitmap draw step: the bitmap is drawn at fit × renderScale (1:1 on screen, no GPU
    // rescaling). The gesture feeds it through ZoomMath.renderBucket.
    var renderScale by remember { mutableFloatStateOf(1f) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var renderGeneration by remember { mutableIntStateOf(0) }
    // goes up when ink is exported into the PDF: drops the cached bitmap and forces a redraw
    var annotVersion by remember { mutableIntStateOf(0) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var dimPct by remember { mutableFloatStateOf(Settings.dimPct(context)) }
    var showDim by rememberSaveable { mutableStateOf(false) }
    var invert by remember { mutableStateOf(Settings.invert(context)) }
    var keepScreenOn by remember { mutableStateOf(Settings.keepScreenOn(context)) }

    // Applies only while the viewer is on screen: the library lets the device sleep as usual.
    val view = LocalView.current
    DisposableEffect(view, keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    val stampsFlow = remember(sheetHash) {
        sheetHash?.let { (context.applicationContext as FOSScoreApp).stampRepository.observe(it) }
            ?: flowOf(emptyList())
    }
    val stamps by stampsFlow.collectAsState(initial = emptyList())
    val stampRepo = (context.applicationContext as FOSScoreApp).stampRepository

    val strokesFlow = remember(sheetHash) {
        sheetHash?.let { (context.applicationContext as FOSScoreApp).strokeRepository.observe(it) }
            ?: flowOf(emptyList())
    }
    val strokes by strokesFlow.collectAsState(initial = emptyList())
    val strokeRepo = (context.applicationContext as FOSScoreApp).strokeRepository

    var editing by rememberSaveable { mutableStateOf(false) }
    var activeSymbol by rememberSaveable { mutableStateOf<StampSymbol?>(null) }
    // selected stamp (for the box with handles) or selected stroke
    var selectedStrokeId by remember { mutableStateOf<Long?>(null) }
    // handle in use: (base object, grabbed corner) while scaling
    var resizing by remember { mutableStateOf<Resizing?>(null) }
    var activeColor by rememberSaveable { mutableStateOf<Int?>(null) }  // tint ARGB; null = theme
    var drawingTool by rememberSaveable { mutableStateOf(false) }      // pen mode: ink, not stamps
    var eraserTool by rememberSaveable { mutableStateOf(false) }      // eraser mode: removes stamps and strokes
    var penWidthPx by remember { mutableFloatStateOf(DEFAULT_PEN_PX) }  // ink thickness (px at zoom 1)
    var dragStamp by remember { mutableStateOf<Stamp?>(null) }
    var dragStroke by remember { mutableStateOf<Stroke?>(null) }   // preview of a stroke being moved/stretched
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var undoStack by remember { mutableStateOf<List<Pair<Int, PageEdit>>>(emptyList()) }
    var inkPreview by remember { mutableStateOf<List<Offset>>(emptyList()) }  // preview of the stroke being drawn (px)
    // so the next stroke can join the last one if the pen only lifts for a few ms
    val inkTail = remember { InkTail() }
    LaunchedEffect(currentPage, editing) { inkTail.last = null }
    // ink already lifted that the DB has not handed back yet: without this the stroke blinks ~20 ms
    var pendingInk by remember { mutableStateOf<List<Stroke>>(emptyList()) }
    LaunchedEffect(currentPage, editing) { pendingInk = emptyList() }
    // optimistic delete: the view hides what is gone at once and the DB confirms afterward.
    // ponytail: a Set of ids pruned by LaunchedEffect; enough, because deleting never brings ids back.
    var hiddenStampIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var hiddenStrokeIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val editScope = rememberCoroutineScope()
    // double-tap animation in flight: any new touch cancels it
    var zoomAnimJob by remember { mutableStateOf<Job?>(null) }

    val swipeThresholdPx = with(LocalDensity.current) { 80.dp.toPx() }

    // MuPDF is not thread-safe either (it shares a global Context): this serializes draw/close so
    // a canceled draw (not cancellable mid-page) cannot clash with the next one or with close().
    // ponytail: one mutex per document is enough; if there is ever more than one document at a
    // time, move it to FOSScoreApp. The bitmap cache lives in MuPdfDoc.
    val rendererLock = remember(pdfUri) { Mutex() }

    val branch = PageFlow(currentPage, halfTurned, halfEnabled, twoUp, isLandscape, pageCount)

    val canEdit = sheetHash != null && !branch.showTwoUp && !(branch.showHalf && halfTurned)

    // marks and stamps of the current page, shared between the overlay and the gestures.
    // The fit comes from MuPDF's size in points (not from the bitmap), so the UI can lay out from
    // the first frame and the overlay does not wait for a draw to land.
    val pageBox = mupdfDoc?.pageSize(currentPage)
    val pageStamps = if (sheetHash != null) {
        stamps.filter { it.page == currentPage && it.id !in hiddenStampIds }
    } else emptyList()
    val pageStrokes = if (sheetHash != null) {
        strokes.filter { it.page == currentPage && it.id !in hiddenStrokeIds }
    } else emptyList()
    val pageFit = if (pageBox != null && pageBox.height > 0f) {
        fitRect(
            viewport.width.toFloat(),
            viewport.height.toFloat(),
            pageBox.width / pageBox.height
        )
    } else RectPx(0f, 0f, 0f, 0f)
    // PURE fit rect (origin 0,0): content coordinates (the page itself, without pageFit's
    // centring margin). The whole overlay and the edit gesture live here.
    val hitFit = RectPx(0f, 0f, pageFit.width, pageFit.height)
    val currentEditing by rememberUpdatedState(editing)
    val currentCanEdit by rememberUpdatedState(canEdit)
    val currentActiveSymbol by rememberUpdatedState(activeSymbol)
    val currentPageStamps by rememberUpdatedState(pageStamps)
    val currentPageStrokes by rememberUpdatedState(pageStrokes)
    val currentPageFit by rememberUpdatedState(pageFit)
    val currentDrawingTool by rememberUpdatedState(drawingTool)
    val currentEraserTool by rememberUpdatedState(eraserTool)
    // where the page is centred in the viewport, and the pure fit rect (origin 0,0):
    // content work and zoom anchoring live in these coordinates
    val currentBase by rememberUpdatedState(Offset(pageFit.left, pageFit.top))
    val currentHitFit by rememberUpdatedState(RectPx(0f, 0f, pageFit.width, pageFit.height))
    // the reading modes (two-up, half page) scale the whole viewport; a single page scales its
    // content. The gesture takes this through rememberUpdatedState: it always reads the live value.
    val currentSpread by rememberUpdatedState(branch.showTwoUp || (branch.showHalf && halfTurned))

    val selectedStamp = pageStamps.firstOrNull { it.id == selectedId }
    val selectedStroke = pageStrokes.firstOrNull { it.id == selectedStrokeId }

    // selection box in px: the stamp's or the stroke's. While dragging or stretching we use the
    // live position (dragStamp/dragStroke) so the box follows the object.
    val selectionRect: RectPx? = when {
        dragStamp != null -> stampRect(dragStamp!!, hitFit)
        dragStroke != null -> strokeSelectionRect(
            decodePoints(dragStroke!!.points).map { normalizedToPx(it, hitFit) },
            dragStroke!!.width * hitFit.height
        )
        selectedStamp != null -> stampRect(selectedStamp, hitFit)
        selectedStroke != null -> strokeSelectionRect(
            decodePoints(selectedStroke.points).map { normalizedToPx(it, hitFit) },
            selectedStroke.width * hitFit.height
        )
        else -> null
    }
    val currentSelectionRect by rememberUpdatedState(selectionRect)
    val currentSelectedStamp by rememberUpdatedState(selectedStamp)
    val currentSelectedStroke by rememberUpdatedState(selectedStroke)

    // while a drag or a stretch is in progress we draw the object at its live position, not the box
    val showSelection = selectionRect != null && editing && canEdit &&
        !drawingTool && !eraserTool

    // stacks snapshots BEFORE every change, each with its page; 10 levels
    fun pushUndo(snapshot: PageEdit = PageEdit(pageStamps, pageStrokes), page: Int = currentPage) {
        undoStack = (undoStack + listOf(page to snapshot)).takeLast(10)
    }

    val applyNext: () -> Unit = {
        val f = branch.next()
        currentPage = f.page
        halfTurned = f.halfTurned
    }
    val applyPrev: () -> Unit = {
        val f = branch.prev()
        currentPage = f.page
        halfTurned = f.halfTurned
    }

    fun clampOffsets() {
        if (viewport == IntSize.Zero) return
        // Single page: the content is the page at its fit; the reading modes (two-up, half page)
        // scale the whole viewport. `currentSpread`/`currentPageFit` are states updated on every
        // composition, so the gesture — which captured this function when it started — always
        // reads live values.
        val vps = Size(viewport.width.toFloat(), viewport.height.toFloat())
        val (content, base) = if (currentSpread) {
            Size(viewport.width.toFloat(), viewport.height.toFloat()) to Offset.Zero
        } else {
            Size(currentPageFit.width, currentPageFit.height) to
                Offset(currentPageFit.left, currentPageFit.top)
        }
        val o = ZoomMath.clampOffset(Offset(offsetX, offsetY), content, vps, scale, base)
        offsetX = o.x
        offsetY = o.y
    }

    // anchor base: a single page scales its content (centred in pageFit); the reading modes scale
    // the whole viewport (origin 0). Same one the gesture uses.
    fun anchorBaseOf(): Offset = if (currentSpread) Offset.Zero else currentBase

    // Animated zoom to a point (double tap): the scale is interpolated geometrically and anchored
    // on every frame with the pinch formula, so it lands exactly where it should, and you see it
    // travel. Going back to fit interpolates straight to (0,0). Cancels the previous one if any.
    fun animateZoomTo(targetScale: Float, anchor: Offset) {
        zoomAnimJob?.cancel()
        zoomAnimJob = editScope.launch {
            val s0 = scale
            val o0 = Offset(offsetX, offsetY)
            val base = anchorBaseOf()
            if (s0 == targetScale) return@launch
            Animatable(0f).animateTo(1f, tween(DOUBLE_TAP_ANIM_MS, easing = FastOutSlowInEasing)) {
                val e = value
                if (targetScale <= 1f) {
                    scale = s0 + (1f - s0) * e
                    offsetX = o0.x * (1f - e)
                    offsetY = o0.y * (1f - e)
                } else {
                    val k = (targetScale / s0).pow(e)
                    val no = ZoomMath.anchoredOffset(o0, k, anchor, base)
                    scale = (s0 * k).coerceIn(1f, DOUBLE_TAP_ZOOM)
                    offsetX = no.x
                    offsetY = no.y
                }
                clampOffsets()
            }
        }
    }

    BackHandler(onBack = onBack)

    // keyboard / pedal page turns: the viewer root holds the focus, otherwise key events reach
    // nothing and only the on-screen buttons turn pages. Preview so a focused button or slider
    // does not swallow the key first.
    val keyFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { keyFocus.requestFocus() }

    LaunchedEffect(currentPage) {
        Settings.setLastPage(context, pdfUri.toString(), currentPage)
    }

    // the DB no longer has what we hid: clear the optimistic delete (and the drag preview)
    LaunchedEffect(stamps, strokes) {
        val liveStamps = stamps.map { it.id }.toSet()
        val liveStrokes = strokes.map { it.id }.toSet()
        if (hiddenStampIds.any { it !in liveStamps }) hiddenStampIds = hiddenStampIds intersect liveStamps
        if (hiddenStrokeIds.any { it !in liveStrokes }) hiddenStrokeIds = hiddenStrokeIds intersect liveStrokes
        dragStamp?.let { d ->
            if (stamps.any { it.id == d.id && it.x == d.x && it.y == d.y && it.size == d.size }) dragStamp = null
        }
        dragStroke?.let { d ->
            if (strokes.any { it.id == d.id && it.points == d.points && it.width == d.width }) dragStroke = null
        }
        // the DB has this ink already: drop it from the optimistic layer
        pendingInk = pendingInk.filter { p -> strokes.none { it.id == p.id && it.points == p.points } }
    }

    // MuPDF (prebuilt AAR, AGPL-3.0: see LICENSE). Opens and works out sizes in the background:
    // opening parses the PDF index and must not touch the main thread.
    LaunchedEffect(pdfUri) {
        try {
            // NonCancellable: if the user leaves while we are opening, the document is created
            // anyway, and we close it below. Cancelling here would leave the native doc hanging
            // (and its memory too).
            val doc = withContext(NonCancellable + Dispatchers.IO) { MuPdfDoc.open(context, pdfUri) }
            if (!isActive) {
                withContext(NonCancellable + Dispatchers.IO) { doc.close() }
                return@LaunchedEffect
            }
            pageCount = doc.pageCount
            if (pageCount > 0) currentPage = currentPage.coerceIn(0, pageCount - 1)
            mupdfDoc = doc
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.e(LOG_TAG, "no se pudo abrir el archivo: ${e.message}", e)
            error = R.string.err_open_file
        } catch (e: Exception) {
            Log.e(LOG_TAG, "no se pudo abrir: ${e::class.simpleName}: ${e.message}", e)
            error = R.string.err_invalid_pdf
        }
    }

    // zoom back to zero when the page or the reading mode changes (the draw step too: otherwise
    // paging would draw every page zoomed in)
    LaunchedEffect(currentPage, branch.showTwoUp, branch.showHalf, halfTurned) {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
        renderScale = 1f
    }

    // Redraw at the current zoom (steps of 10% of the fit, ZoomMath.renderBucket): like reference
    // viewers, the bitmap follows the zoom so the GPU never rescales it. During a pinch it only
    // goes UP a step, with a 150 ms minimum between draws; once it settles (no scale change for
    // 250 ms) the final step is tuned in, which can also go DOWN after zooming out.
    LaunchedEffect(mupdfDoc, viewport) {
        var lastUpgradeMs = 0L
        snapshotFlow { scale }.collectLatest { s ->
            val target = ZoomMath.renderBucket(s)
            val now = System.currentTimeMillis()
            if (target > renderScale && now - lastUpgradeMs >= RENDER_DURING_ZOOM_MS) {
                lastUpgradeMs = now
                renderScale = target
                return@collectLatest
            }
            delay(RENDER_SETTLE_MS.milliseconds)
            // if the scale kept changing while we waited, the next emission picks it up
            if (target >= ZoomMath.renderBucket(scale)) renderScale = target
        }
    }

    // viewport matters: on the first frame it is still IntSize.Zero, and with no size there is no draw
    LaunchedEffect(
        mupdfDoc, currentPage, branch.showTwoUp, branch.showHalf, halfTurned, renderScale, viewport,
        annotVersion
    ) {
        val r = mupdfDoc ?: return@LaunchedEffect
        val gen = ++renderGeneration
        val needed = when {
            branch.showTwoUp -> listOf(currentPage, currentPage + 1).filter { it < pageCount }
            branch.showHalf && halfTurned -> listOf(currentPage, currentPage + 1).filter { it < pageCount }
            else -> listOf(currentPage)
        }
        val isSpread = branch.showTwoUp || (branch.showHalf && halfTurned)
        // Single page: the draw slot is the fit × zoom step (the bitmap is shown 1:1 on screen, the
        // GPU rescales nothing). Reading modes (two-up, half page): the slot is the viewport and
        // the layer keeps scaling (they are for reading, not for zooming in).
        val spreadSlot =
            if (isSpread) Pair(if (branch.showTwoUp) viewport.width / 2 else viewport.width, viewport.height)
            else null
        // read Compose state BEFORE going into the draw dispatcher
        val rs = renderScale
        val vpW = viewport.width.toFloat()
        val vpH = viewport.height.toFloat()
        val fallback = needed.associateWith { bitmaps[it] }
        try {
            val session = rendererLock.withLock {
                withContext(Dispatchers.Default) {
                    // the real page size may not be loaded yet (pages past the ones loaded up
                    // front): it is resolved inside the lock with loadSize (a cheap read of the
                    // page dictionary), the same path the draw takes for its fit
                    val slot = if (isSpread) spreadSlot!! else {
                        val box = r.ensurePageSize(currentPage)
                        if (box == null || box.height <= 0f) return@withContext emptyMap()
                        val fit = fitRect(vpW, vpH, box.width / box.height)
                        Pair((fit.width * rs).toInt(), (fit.height * rs).toInt())
                    }
                    val out = mutableMapOf<Int, Bitmap>()
                    for (idx in needed) {
                        // if a draw fails we keep what is already on screen: faint beats a hole
                        out[idx] = r.render(idx, slot.first, slot.second) ?: fallback[idx] ?: continue
                    }
                    out
                }
            }
            // guard against an older, non-cancellable render landing after a newer one
            if (gen == renderGeneration) {
                for ((idx, bmp) in session) bitmaps[idx] = bmp
                // we do not pile up bitmaps of pages that are no longer on screen (16 MB each when sharp)
                bitmaps.keys.filterNot { it in needed }.forEach { bitmaps.remove(it) }
                // no bitmap for this page means nothing to show: say so instead of leaving the
                // spinner turning forever (it happened with a blank page)
                if (bitmaps[currentPage] == null) {
                    Log.e(LOG_TAG, "la página $currentPage no se pudo renderizar")
                    error = R.string.err_render_page
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(LOG_TAG, "render failed: ${e::class.simpleName}: ${e.message}", e)
            if (gen == renderGeneration) error = R.string.err_render_page
        }
    }

    DisposableEffect(mupdfDoc) {
        val current = mupdfDoc
        onDispose {
            // never block the main thread waiting for a draw in flight (ANR): close() goes to
            // applicationScope; the mutex's FIFO queue makes sure it runs after the draw.
            val scope = (context.applicationContext as FOSScoreApp).applicationScope
            scope.launch {
                rendererLock.withLock { current?.close() }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewport = it }
            .onPreviewKeyEvent { e ->
                val turn = pageTurnFor(e.key, e.isShiftPressed) ?: return@onPreviewKeyEvent false
                // a held key repeats: turn one page per press, not a burst
                if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) {
                    if (turn == PageTurn.NEXT) applyNext() else applyPrev()
                }
                true
            }
            .focusRequester(keyFocus)
            .focusable()
    ) {
        if (error != null) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(stringResource(error!!), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onBack) { Text(stringResource(R.string.action_back)) }
            }
        } else if (bitmaps.isNotEmpty()) {
            // Reading modes (two-up, half page): the layer scales the whole viewport as before
            // (they are not meant for zooming into detail: offscreen drawing sweeps away the
            // stretch at no real cost in sharpness). Single page (else branch): NO scaling layer —
            // the bitmap is drawn at the current zoom and shown 1:1; the overlay is redrawn as
            // vector (see plan 2026-09-30-zoom-render-1to1).
            val spreadMod = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                    translationY = offsetY
                    compositingStrategy = CompositingStrategy.Offscreen
                }
            val gestureMod = Modifier.pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (down.isConsumed) return@awaitEachGesture
                        zoomAnimJob?.cancel()    // any new touch cancels the double-tap animation
                        // screen -> fit coordinates (0..fitW x 0..fitH): the content no longer
                        // lives in a scaled layer, so the gesture translates the finger point here
                        fun fitPoint(p: Offset): Offset {
                            val b = currentBase
                            return Offset(
                                (p.x - b.x - offsetX) / scale,
                                (p.y - b.y - offsetY) / scale
                            )
                        }
                        val downF = fitPoint(down.position)
                        // anchor base: a single page scales its content (centred in
                        // pageFit.left/top); the reading modes scale the whole viewport (origin 0)
                        val anchorBase = if (currentSpread) Offset.Zero else currentBase
                        var ids = setOf(down.id)
                        var prevCount = 1
                        var prevCentroid = down.position
                        var anchorDist = 0f
                        var totalPan = Offset.Zero
                        var isTransform = false
                        // With 2+ fingers only the pinch branch moves the page, so the two branches
                        // never fight.
                        var scaling: Boolean
                        var movingStamp: Stamp? = null
                        var movingOrigin: Stamp? = null
                        var moveTrackingPos = downF
                        var movingStroke: Stroke? = null
                        var movingStrokeBase: Stroke? = null
                        var moveStrokeLastPos = downF
                        var inkActive = false
                        var inkPts = mutableListOf<Offset>()
                        var eraserActive = false
                        var eraserPath = mutableListOf<Offset>()
                        var scaleF = 1f   // accumulated handle factor (always from startPos)
                        if (currentEditing && currentCanEdit && !currentDrawingTool && !currentEraserTool) {
                            // 1) a handle on the selection box wins over everything else: entering
                            //    by the corner stretches, entering inside moves.
                            val sel = currentSelectionRect
                            val corner = if (sel != null) hitHandle(downF, sel, currentHitFit) else null
                            if (corner != null) {
                                resizing = when (val s = currentSelectedStamp) {
                                    null -> currentSelectedStroke?.let { st ->
                                        val ptsPx = decodePoints(st.points)
                                            .map { normalizedToPx(it, currentHitFit) }
                                        Resizing.OfStroke(
                                            stroke = st,
                                            ptsPx = ptsPx,
                                            center = strokeBounds(ptsPx).center(),
                                            startPos = downF
                                        )
                                    }
                                    else -> Resizing.OfStamp(stamp = s, startPos = downF)
                                }
                            }
                            if (resizing == null) {
                                val hitS = hitStamp(downF, currentPageStamps, currentHitFit)
                                val hitT = if (hitS == null) {
                                    hitStroke(downF, currentPageStrokes, currentHitFit)
                                } else null
                                if (hitS != null) {
                                    movingStamp = hitS
                                    movingOrigin = hitS
                                    selectedId = hitS.id
                                    selectedStrokeId = null
                                    activeSymbol = runCatching { StampSymbol.valueOf(hitS.symbol) }.getOrNull()
                                    dragStamp = hitS
                                } else if (hitT != null) {
                                    movingStroke = hitT
                                    movingStrokeBase = hitT
                                    moveStrokeLastPos = downF
                                    selectedStrokeId = hitT.id
                                    selectedId = null
                                    dragStroke = hitT
                                }
                            }
                        }
                        if (currentEditing && currentCanEdit && currentEraserTool) {
                            eraserActive = true
                            eraserPath.add(downF)
                            inkPreview = listOf(downF)
                        }
                        if (currentEditing && currentCanEdit && currentDrawingTool) {
                            inkActive = true
                            inkPts.add(downF)
                            inkPreview = listOf(downF)
                        }

                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) {
                                if (resizing != null) {
                                    val rz = resizing
                                    resizing = null
                                    when (rz) {
                                        is Resizing.OfStamp -> {
                                            val base = rz.stamp
                                            val newSize = (base.size * scaleF)
                                                .coerceIn(MIN_STAMP_SIZE, MAX_STAMP_SIZE)
                                            if (abs(newSize - base.size) > 1e-4f) {
                                                pushUndo(PageEdit(currentPageStamps, currentPageStrokes))
                                                editScope.launch {
                                                    stampRepo.update(base.copy(size = newSize))
                                                }
                                            }
                                        }
                                        is Resizing.OfStroke -> {
                                            val base = rz.stroke
                                            val pts = scalePoints(rz.ptsPx, rz.center, scaleF)
                                            val newW = (base.width * scaleF)
                                                .coerceIn(MIN_STROKE_WIDTH, MAX_STROKE_WIDTH)
                                            if (abs(newW - base.width) > 1e-5f ||
                                                pts != rz.ptsPx
                                            ) {
                                                pushUndo(PageEdit(currentPageStamps, currentPageStrokes))
                                                editScope.launch {
                                                    strokeRepo.update(
                                                        base.copy(
                                                            points = encodePoints(
                                                                pts.map { pxToNormalized(it, currentHitFit) }
                                                            ),
                                                            width = newW
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                        null -> {}  // resizing is a captured var: it can be null
                                    }
                                } else if (inkActive) {
                                    // ink capture lives in fit coordinates (F): the sampling
                                    // threshold is a screen distance, so it scales with the zoom
                                    val pts = samplePoints(inkPts, MIN_SAMPLE_DIST_PX / scale)
                                    inkPreview = emptyList()
                                    if (pts.size >= 2 && sheetHash != null && currentPageFit.width > 0f) {
                                        pushUndo(PageEdit(currentPageStamps, currentPageStrokes))
                                        val norm = pts.map { pxToNormalized(it, currentHitFit) }
                                        val fit = currentHitFit
                                        val page = currentPage
                                        val width = penWidthPxToFrac(penWidthPx, currentHitFit)
                                        val color = activeColor
                                        val startPx = pts.first()   // compared in px, not normalized
                                        editScope.launch {
                                            val now = android.os.SystemClock.uptimeMillis()
                                            val prev = inkTail.last
                                            val prevPts = prev?.points?.let { decodePoints(it) }
                                                ?.map { normalizedToPx(it, fit) } ?: emptyList()
                                            val canMerge = prev != null && prevPts.isNotEmpty() &&
                                                shouldMergeStroke(
                                                    prevPts.last(),
                                                    startPx,
                                                    fit,
                                                    now - inkTail.at,
                                                    prev.width == width && prev.color == color
                                                )
                                            Log.d(LOG_TAG, "ink: merge=$canMerge gap=${now - inkTail.at}ms prev=${prev != null} dist=${if (prevPts.isNotEmpty()) (startPx - prevPts.last()).getDistance() else -1f}px limit=${STROKE_MERGE_DIST_FRAC * fit.height}px")
                                            if (canMerge) {
                                                val merged = prev.copy(
                                                    points = encodePoints(decodePoints(prev.points) + norm)
                                                )
                                                strokeRepo.update(merged)
                                                inkTail.last = merged
                                                pendingInk = pendingInk.filter { it.id != merged.id } + merged
                                            } else {
                                                val s = Stroke(
                                                    sheetHash = sheetHash,
                                                    page = page,
                                                    points = encodePoints(norm),
                                                    width = width,
                                                    color = color
                                                )
                                                val stored = s.copy(id = strokeRepo.insert(s))
                                                inkTail.last = stored
                                                pendingInk = pendingInk + stored
                                            }
                                            inkTail.at = now
                                        }
                                    }
                                } else if (eraserActive) {
                                    val path = eraserPath
                                    inkPreview = emptyList()
                                    if (path.isNotEmpty() && sheetHash != null && currentPageFit.width > 0f) {
                                        pushUndo(PageEdit(currentPageStamps, currentPageStrokes))
                                        val radiusPx = ERASE_RADIUS_FRAC * currentHitFit.height
                                        // the eraser always deletes what it grazes: to remove a whole
                                        // word, tap it (that selects it) and press 🗑
                                        val goneStamps = currentPageStamps.filter { st ->
                                            val r = stampRect(st, currentHitFit)
                                            path.any { r.contains(it) }
                                        }
                                        val goneStrokes = mutableListOf<Stroke>()
                                        val newRuns = mutableListOf<Stroke>()
                                        for (st in currentPageStrokes) {
                                            val ptsPx = decodePoints(st.points).map { normalizedToPx(it, currentHitFit) }
                                            val runs = eraseStrokePoints(ptsPx, path, radiusPx)
                                            if (runs.size == 1 && runs[0].size == ptsPx.size) {
                                                // untouched
                                            } else {
                                                goneStrokes.add(st)
                                                runs.forEach { r ->
                                                    newRuns.add(
                                                        st.copy(
                                                            id = 0,
                                                            points = encodePoints(r.map { pxToNormalized(it, currentHitFit) })
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                        selectedId = null
                                        selectedStrokeId = null
                                        inkTail.last = null   // the previous stroke is gone
                                        // make it look deleted NOW: the DB lags behind (~20 ms)
                                        hiddenStampIds = hiddenStampIds + goneStamps.map { it.id }
                                        hiddenStrokeIds = hiddenStrokeIds + goneStrokes.map { it.id }
                                        editScope.launch {
                                            if (goneStamps.isNotEmpty()) stampRepo.deleteAll(goneStamps)
                                            if (goneStrokes.isNotEmpty()) {
                                                strokeRepo.deleteAll(goneStrokes)
                                                strokeRepo.insertAll(newRuns)
                                            }
                                        }
                                    }
                                } else if (movingStroke != null) {
                                    val done = movingStroke
                                    val base = movingStrokeBase
                                    // keep the final position: clearing it here would make the
                                    // overlay draw the DB's old position for a few ms until the flow
                                    // emits again (a glitch).
                                    dragStroke = done
                                    if (base != null) {
                                        if (done.points != base.points) {
                                            pushUndo(PageEdit(currentPageStamps, currentPageStrokes))
                                            editScope.launch { strokeRepo.update(done) }
                                        }
                                    }
                                } else if (movingStamp != null) {
                                    val done = movingStamp
                                    val origin = movingOrigin
                                    // keep the final position: clearing it here would make the
                                    // overlay draw the DB's old position for a few ms until the flow
                                    // emits again (a glitch).
                                    dragStamp = done
                                    if (origin != null && (done.x != origin.x || done.y != origin.y)) {
                                        pushUndo(PageEdit(currentPageStamps, currentPageStrokes))
                                    }
                                    editScope.launch { stampRepo.update(done) }
                                } else if (!isTransform) {
                                    // double tap: toggles fit <-> 2.5x on the point touched. It is the
                                    // shortcut back to the whole page (and into a detail) without
                                    // fighting with the buttons on the bar.
                                    val nowMs = System.currentTimeMillis()
                                    val isDoubleTap = nowMs - lastTapMs < 320L &&
                                        (down.position - lastTapPos).getDistance() < doubleTapSlopPx
                                    if (isDoubleTap && !currentEditing) {
                                        if (scale > 1.05f) {
                                            animateZoomTo(1f, down.position)
                                        } else {
                                            animateZoomTo(DOUBLE_TAP_ZOOM, down.position)
                                        }
                                        lastTapMs = 0L
                                    } else {
                                        lastTapMs = nowMs
                                        lastTapPos = down.position
                                    }
                                    if (isDoubleTap) {
                                        // already handled above: neither places a stamp nor turns the page
                                    } else if (currentEditing && currentCanEdit) {
                                        // tap while editing: places the active stamp on the point touched
                                        val sym = currentActiveSymbol
                                        if (sym == null) {
                                            // no tool: just deselect
                                            selectedId = null
                                            selectedStrokeId = null
                                            dragStamp = null
                                            dragStroke = null
                                        }
                                        if (sym != null && sheetHash != null && scale <= 1f) {
                                            val pos = pxToNormalized(downF, currentHitFit)
                                            pushUndo(PageEdit(currentPageStamps, currentPageStrokes))
                                            editScope.launch {
                                                val id = stampRepo.insert(
                                                    Stamp(
                                                        sheetHash = sheetHash,
                                                        page = currentPage,
                                                        symbol = sym.name,
                                                        x = pos.x.coerceIn(0f, 1f),
                                                        y = pos.y.coerceIn(0f, 1f),
                                                        size = DEFAULT_STAMP_SIZE,
                                                        color = activeColor
                                                    )
                                                )
                                                selectedId = id
                                            }
                                        }
                                    } else {
                                        val isSwipe = scale <= 1f &&
                                            totalPan.getDistance() > swipeThresholdPx &&
                                            abs(totalPan.x) > abs(totalPan.y)
                                        if (isSwipe) {
                                            if (totalPan.x < 0f) applyNext() else applyPrev()
                                        } else {
                                            // tap zones: left third prev, right third next, center toggles the bars
                                            val width = viewport.width
                                            when {
                                                width > 0 && down.position.x > width * 2f / 3f -> applyNext()
                                                width > 0 && down.position.x < width / 3f -> applyPrev()
                                                else -> navVisible = !navVisible
                                            }
                                        }
                                    }
                                }
                                break
                            }

                            if (resizing != null) {
                                // stretching by a handle: one pointer, scaling around the center.
                                // the factor is worked out from the starting position, never accumulated
                                val tracking = pressed.firstOrNull { it.id == down.id } ?: pressed.first()
                                val rz = resizing!!
                                val center = when (rz) {
                                    is Resizing.OfStamp -> stampRect(rz.stamp, currentHitFit).center()
                                    is Resizing.OfStroke -> rz.center
                                }
                                val f = scaleFactor(center, rz.startPos, fitPoint(tracking.position))
                                scaleF = f
                                when (rz) {
                                    is Resizing.OfStamp -> {
                                        dragStamp = rz.stamp.copy(
                                            size = (rz.stamp.size * f).coerceIn(MIN_STAMP_SIZE, MAX_STAMP_SIZE)
                                        )
                                    }
                                    is Resizing.OfStroke -> {
                                        val pts = scalePoints(rz.ptsPx, center, f)
                                        dragStroke = rz.stroke.copy(
                                            points = encodePoints(pts.map { pxToNormalized(it, currentHitFit) }),
                                            width = (rz.stroke.width * f).coerceIn(MIN_STROKE_WIDTH, MAX_STROKE_WIDTH)
                                        )
                                    }
                                }
                                prevCentroid = tracking.position
                                event.changes.forEach { change -> if (change.positionChanged()) change.consume() }
                                continue
                            }

                            if (movingStroke != null) {
                                val tracking = pressed.firstOrNull { it.id == down.id } ?: pressed.first()
                                val m = pxToNormalized(fitPoint(tracking.position), currentHitFit) -
                                    pxToNormalized(moveStrokeLastPos, currentHitFit)
                                moveStrokeLastPos = fitPoint(tracking.position)
                                val ns = movingStroke.copy(
                                    points = encodePoints(
                                        decodePoints(movingStroke.points).map {
                                            Offset((it.x + m.x).coerceIn(0f, 1f), (it.y + m.y).coerceIn(0f, 1f))
                                        }
                                    )
                                )
                                movingStroke = ns
                                dragStroke = ns
                                prevCentroid = tracking.position
                                event.changes.forEach { change -> if (change.positionChanged()) change.consume() }
                                continue
                            }

                            if (movingStamp != null) {
                                // move with one finger (the initial pointer, no jump when a second
                                // finger arrives); no pinch to resize — size is changed with the slider
                                val tracking = pressed.firstOrNull { it.id == down.id } ?: pressed.first()
                                val m = pxToNormalized(fitPoint(tracking.position), currentHitFit) -
                                    pxToNormalized(moveTrackingPos, currentHitFit)
                                moveTrackingPos = fitPoint(tracking.position)
                                val ns = movingStamp.copy(
                                    x = (movingStamp.x + m.x).coerceIn(0f, 1f),
                                    y = (movingStamp.y + m.y).coerceIn(0f, 1f)
                                )
                                movingStamp = ns
                                dragStamp = ns
                                prevCentroid = centroid(pressed)
                                event.changes.forEach { change -> if (change.positionChanged()) change.consume() }
                                continue
                            }

                            if (inkActive) {
                                if (pressed.size > 1) {
                                    // a second finger arrived: drop the ink and let the pinch zoom
                                    inkActive = false
                                    inkPts = mutableListOf()
                                    inkPreview = emptyList()
                                } else {
                                    val tracking = pressed.firstOrNull { it.id == down.id } ?: pressed.first()
                                    val last = inkPts.last()
                                    // points captured in fit coordinates: the screen threshold scales
                                    // with the zoom
                                    if ((fitPoint(tracking.position) - last).getDistance() >=
                                        MIN_SAMPLE_DIST_PX / scale
                                    ) {
                                        inkPts.add(fitPoint(tracking.position))
                                        inkPreview = inkPts.toList()
                                    }
                                    prevCentroid = tracking.position
                                    event.changes.forEach { change -> if (change.positionChanged()) change.consume() }
                                    continue
                                }
                            }

                            if (eraserActive) {
                                if (pressed.size > 1) {
                                    eraserActive = false
                                    eraserPath = mutableListOf()
                                    inkPreview = emptyList()
                                } else {
                                    val tracking = pressed.firstOrNull { it.id == down.id } ?: pressed.first()
                                    val last = eraserPath.last()
                                    if ((fitPoint(tracking.position) - last).getDistance() >=
                                        MIN_SAMPLE_DIST_PX / scale
                                    ) {
                                        eraserPath.add(fitPoint(tracking.position))
                                        inkPreview = eraserPath.toList()
                                    }
                                    prevCentroid = tracking.position
                                    event.changes.forEach { change -> if (change.positionChanged()) change.consume() }
                                    continue
                                }
                            }

                            val nids = pressed.map { it.id }.toSet()
                            // Re-anchor if the SET of fingers changes OR their NUMBER. Comparing the
                            // set alone let 1 -> 2 -> 1 through: back on one finger, nids was ids
                            // again, no re-anchor happened and prevCentroid stayed at the midpoint of
                            // the two, so delta was half the separation and the page teleported
                            // ±215 px when a finger left the pinch.
                            if (nids != ids || nids.size != prevCount) {
                                prevCount = nids.size
                                ids = nids
                                anchorDist = 0f      // re-anchor the ratio reference
                                prevCentroid = centroid(pressed)
                            }

                            val c = centroid(pressed)
                            val delta = c - prevCentroid
                            totalPan += delta
                            // While a pinch is on, one-finger panning does NOT run and the anchor is
                            // taken again. That is what stops the two branches fighting over the
                            // offset.
                            scaling = pressed.size > 1

                            if (scaling) {
                                isTransform = true
                                val d = (pressed[0].position - pressed[1].position).getDistance()
                                // ANCHOR: the live centroid (midpoint of the fingers). No follow
                                // (no offset += delta): following added the midpoint's travel to the
                                // offset and dragged the page ~30 px on asymmetric pinches. Anchoring
                                // without follow keeps the content under the midpoint still; each
                                // finger stays on its content when the pinch is symmetric (see
                                // ZoomGestureSimTest symmetricPinchKeepsFingersPinned).
                                val pinchAnchor = c
                                if (anchorDist == 0f) {
                                    // first frame of the pinch applies no zoom (the reference is
                                    // taken against this distance)
                                    anchorDist = d
                                } else if (d > 0f) {
                                    // INCREMENTAL: how much the distance changed in THIS frame.
                                    val ratio = d / anchorDist
                                    anchorDist = d   // the next frame measures against this distance
                                    // dead zone against touch noise (±1 px, still fingers)
                                    if (abs(ratio - 1f) > ZOOM_NOISE_RATIO) {
                                        val target = (scale * ratio).coerceIn(1f, MAX_ZOOM)
                                        if (target != scale) {
                                            // Anchor on the live midpoint: the content under the
                                            // centroid stays still. No centroid follow.
                                            val k = target / scale
                                            val no = ZoomMath.anchoredOffset(
                                                Offset(offsetX, offsetY), k, pinchAnchor, anchorBase
                                            )
                                            offsetX = no.x
                                            offsetY = no.y
                                            scale = target
                                        }
                                    }
                                }
                            } else if (scale > 1f && delta.getDistance() > 0f) {
                                // One finger and NO pinch in progress: pan. While there are 2+ fingers
                                // this branch does not run, so the two branches never fight over the
                                // offset.
                                isTransform = true
                                offsetX += delta.x
                                offsetY += delta.y
                            }
                            clampOffsets()
                            prevCentroid = c
                            event.changes.forEach { change -> if (change.positionChanged()) change.consume() }
                        }
                    }
                }

            when {
                branch.showTwoUp -> {
                    Row(modifier = spreadMod.then(gestureMod)) {
                        bitmaps[currentPage]?.let { bmp ->
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                colorFilter = if (invert) INVERT_FILTER else null,
                                modifier = Modifier.weight(1f).fillMaxHeight()
                            )
                        }
                        bitmaps[currentPage + 1]?.let { bmp ->
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                colorFilter = if (invert) INVERT_FILTER else null,
                                modifier = Modifier.weight(1f).fillMaxHeight()
                            )
                        }
                    }
                }
                branch.showHalf && halfTurned && bitmaps[currentPage] != null && bitmaps[currentPage + 1] != null -> {
                    // ponytail: compose both halves into a single bitmap the same size as one page,
                    // so it scales identically to the full page and the bottom (reading) half stays put.
                    // Assumes consecutive pages share the same dimensions.
                    val cur = bitmaps[currentPage]!!
                    val next = bitmaps[currentPage + 1]!!
                    val w = cur.width
                    val h = cur.height
                    val sepColor = MaterialTheme.colorScheme.primary.toArgb()
                    // remember keyed on the BITMAPS (not on the map): with a SnapshotStateMap,
                    // reading the keys above already registers the dependency, so this only
                    // recomposes if they change
                    val combined = remember(cur, next, w, h) {
                        val out = createBitmap(w, h)
                        val canvas = android.graphics.Canvas(out)
                        val paint = android.graphics.Paint().apply { isFilterBitmap = true }
                        canvas.drawBitmap(next, Rect(0, 0, next.width, next.height / 2), Rect(0, 0, w, h / 2), paint)
                        canvas.drawBitmap(cur, Rect(0, cur.height / 2, cur.width, cur.height), Rect(0, h / 2, w, h), paint)
                        paint.color = sepColor
                        paint.strokeWidth = (h / 200f).coerceAtLeast(2f)
                        canvas.drawLine(0f, (h / 2).toFloat(), w.toFloat(), (h / 2).toFloat(), paint)
                        out
                    }
                    Image(
                        bitmap = combined.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        colorFilter = if (invert) INVERT_FILTER else null,
                        modifier = spreadMod.then(gestureMod)
                    )
                }
                else -> {
                    bitmaps[currentPage]?.let { bmp ->
                        // Single page: the bitmap is drawn at the current zoom (fit × scale, the
                        // same size it takes on screen) and shown 1:1 — the GPU rescales nothing.
                        // The overlay (ink/stamps/selection) is drawn in fit coordinates and the
                        // zoom is applied inside the drawing with translate+scale: vector, redrawn
                        // every frame, sharp at any zoom.
                        val density = LocalDensity.current
                        val k = scale
                        val posX = pageFit.left + offsetX
                        val posY = pageFit.top + offsetY
                        // clipToBounds: without it the zoomed page (a 3000x3922 box) is drawn
                        // outside the viewport and COVERS the app bars (seen on the test device:
                        // zooming in made the mode bar and the page bar disappear).
                        Box(modifier = Modifier.fillMaxSize().clipToBounds().then(gestureMod)) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = stringResource(
                                    R.string.desc_page, currentPage + 1, pageCount
                                ),
                                contentScale = ContentScale.FillBounds,
                                colorFilter = if (invert) INVERT_FILTER else null,
                                modifier = Modifier
                                    // ponytail: a Placeable clamps the reported size to the
                                    // constraints and centres overflowing content (apparent vs real
                                    // offset: see PROBE img). We report the parent size (the
                                    // viewport, bounded by construction: both Box are fillMaxSize)
                                    // and the real content (fit*k) is placed at (0,0): the corner
                                    // ends up at posX/posY as the model says, and clipToBounds trims.
                                    .layout { m, c ->
                                        val p = m.measure(
                                            Constraints.fixed(
                                                (pageFit.width * k).roundToInt(),
                                                (pageFit.height * k).roundToInt()
                                            )
                                        )
                                        layout(c.maxWidth, c.maxHeight) { p.place(0, 0) }
                                    }
                                    // The POSITION goes through graphicsLayer (a DRAW transform),
                                    // not Modifier.offset (which goes through LAYOUT). With offset the
                                    // page landed somewhere other than what the model says: the
                                    // content slipped out from under the fingers while the ink and
                                    // the stamps (Canvas + withTransform, a draw transform) stayed
                                    // where they should and looked like they slid off the music.
                                    .graphicsLayer {
                                        translationX = posX
                                        translationY = posY
                                    }
                            )
                            if (canEdit) {
                                // ink/stamps default to black (not the theme's bluish primary). With
                                // "Neg" they are inverted like the page: otherwise black ink
                                // disappears on the now-black background.
                                val themeColor = Color.Black
                                fun inkColor(c: Color) = if (invert) c.inverted() else c
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    withTransform({
                                        translate(posX, posY)
                                        scale(k, k, pivot = Offset.Zero)
                                    }) {
                                        fun drawStrokePath(pts: List<Offset>, color: Color, widthPx: Float) {
                                            if (pts.size < 2) return
                                            val path = Path()
                                            path.moveTo(pts[0].x, pts[0].y)
                                            for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
                                            drawPath(
                                                path,
                                                color = color,
                                                style = DrawStroke(
                                                    width = max(1f, widthPx),
                                                    cap = StrokeCap.Round,
                                                    join = StrokeJoin.Round
                                                )
                                            )
                                        }
                                        // pendingInk goes first: if the flow still brings the older
                                        // version (after a merge), the one being written wins
                                        for ((id, _, _, points, width, color) in (pendingInk + pageStrokes).distinctBy { it.id }) {
                                            // the one being moved/stretched is drawn only at its live
                                            // position (otherwise two copies = a ghost)
                                            if (dragStroke?.id == id) continue
                                            drawStrokePath(
                                                decodePoints(points).map { normalizedToPx(it, hitFit) },
                                                inkColor(stampColor(color, themeColor)),
                                                width * hitFit.height
                                            )
                                        }
                                        // selected stroke being moved/stretched live
                                        dragStroke?.let { ds ->
                                            drawStrokePath(
                                                decodePoints(ds.points).map { normalizedToPx(it, hitFit) },
                                                inkColor(stampColor(ds.color, themeColor)),
                                                ds.width * hitFit.height
                                            )
                                        }
                                        if (inkPreview.size >= 2) {
                                            drawStrokePath(
                                                inkPreview,
                                                if (eraserTool) Color.Red.copy(alpha = 0.4f)
                                                else inkColor(stampColor(activeColor, themeColor)),
                                                if (eraserTool) ERASE_RADIUS_FRAC * hitFit.height * 2f
                                                else penWidthPx
                                            )
                                        }
                                    }
                                }
                                // Stamps: the SAME transform as the page and the ink (translate+scale
                                // from the origin) but on the DRAW path. They used to go through the
                                // LAYOUT path (Modifier.offset + Modifier.size), two mechanisms for
                                // the same transform: that is why stamps and music drifted apart when
                                // zooming. size() is also the modifier that broke the page before
                                // (requiredSize was needed there).
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            // required: the default origin is the CENTRE and the
                                            // scale would apply from there, moving everything
                                            transformOrigin = TransformOrigin(0f, 0f)
                                            translationX = posX
                                            translationY = posY
                                            scaleX = k
                                            scaleY = k
                                            // no compositingStrategy: that way the scale is a canvas
                                            // transform and the glyphs are redrawn sharp. With Offscreen
                                            // it rasterizes to a bitmap and scales that (blurry)
                                        }
                                ) {
                                    for (stamp in pageStamps) {
                                        val shown = dragStamp?.takeIf { it.id == stamp.id } ?: stamp
                                        val sym = runCatching { StampSymbol.valueOf(shown.symbol) }.getOrNull()
                                            ?: continue
                                        val r = stampRect(shown, hitFit)
                                        StampView(
                                            symbol = sym,
                                            // in fit units: the layer applies the scale, not the
                                            // stamp size (otherwise it is applied twice)
                                            sizePx = r.width,
                                            color = inkColor(stampColor(shown.color, themeColor)),
                                            modifier = Modifier
                                                .offset { IntOffset(r.left.roundToInt(), r.top.roundToInt()) }
                                                .requiredSize(
                                                    with(density) { r.width.toDp() },
                                                    with(density) { r.height.toDp() }
                                                )
                                        )
                                    }
                                }
                                // selection box ON TOP of everything: otherwise the handles end up
                                // hidden by the very stamp being edited
                                if (showSelection) {
                                    val hr = handleRadiusPx(hitFit)
                                    val frame = Color(0xFF00A0FF)
                                    Canvas(modifier = Modifier.fillMaxSize()) {
                                        withTransform({
                                            translate(posX, posY)
                                            scale(k, k, pivot = Offset.Zero)
                                        }) {
                                            drawRect(
                                                color = frame,
                                                topLeft = Offset(selectionRect.left, selectionRect.top),
                                                size = Size(selectionRect.width, selectionRect.height),
                                                style = DrawStroke(width = max(1.5f, hr * 0.3f))
                                            )
                                            handleCenters(selectionRect, hitFit).forEach { hc ->
                                                drawRect(
                                                    color = Color.White,
                                                    topLeft = Offset(hc.x - hr, hc.y - hr),
                                                    size = Size(hr * 2f, hr * 2f)
                                                )
                                                drawRect(
                                                    color = frame,
                                                    topLeft = Offset(hc.x - hr, hc.y - hr),
                                                    size = Size(hr * 2f, hr * 2f),
                                                    style = DrawStroke(width = max(1.5f, hr * 0.3f))
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } ?: CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
            }
        } else {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }

        if (dimPct > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = dimPct / 100f))
            )
        }

        if (navVisible) {
            val surface = MaterialTheme.colorScheme.surface

            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(surface)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.mode_label), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        halfEnabled = !halfEnabled
                        halfTurned = false
                    },
                    enabled = !editing,
                    colors = if (halfEnabled) ButtonDefaults.buttonColors()
                    else ButtonDefaults.outlinedButtonColors()
                ) { Text("½") }
                if (isLandscape) {
                    Spacer(Modifier.width(4.dp))
                    Button(
                        onClick = { twoUp = false },
                        enabled = !editing,
                        colors = if (!twoUp) ButtonDefaults.buttonColors()
                        else ButtonDefaults.outlinedButtonColors()
                    ) { Text("1") }
                    Spacer(Modifier.width(4.dp))
                    Button(
                        onClick = { twoUp = true },
                        enabled = !editing,
                        colors = if (twoUp) ButtonDefaults.buttonColors()
                        else ButtonDefaults.outlinedButtonColors()
                    ) { Text("2") }
                }
                Spacer(Modifier.width(16.dp))
                Button(onClick = onToggleTheme) { Text(themeMode.label()) }
                Spacer(Modifier.width(4.dp))
                Button(
                    onClick = {
                        invert = !invert
                        Settings.setInvert(context, invert)
                    },
                    colors = if (invert) ButtonDefaults.buttonColors()
                    else ButtonDefaults.outlinedButtonColors()
                ) { Text(stringResource(R.string.neg_label)) }
                Spacer(Modifier.width(4.dp))
                Button(
                    onClick = {
                        editing = !editing
                        if (editing) {
                            twoUp = false
                            halfEnabled = false
                            halfTurned = false
                        } else {
                            undoStack = emptyList()
                            selectedId = null
                            selectedStrokeId = null
                            dragStamp = null
                            dragStroke = null
                            drawingTool = false
                            eraserTool = false
                            inkPreview = emptyList()
                            hiddenStampIds = emptySet()
                            hiddenStrokeIds = emptySet()
                        }
                    },
                    enabled = canEdit,
                    colors = if (editing) ButtonDefaults.buttonColors()
                    else ButtonDefaults.outlinedButtonColors()
                ) { Text(stringResource(R.string.action_edit)) }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(surface)
            ) {
                if (showDim) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.dim_label), style = MaterialTheme.typography.bodyMedium)
                        Slider(
                            value = dimPct,
                            onValueChange = { dimPct = it },
                            onValueChangeFinished = { Settings.setDimPct(context, dimPct) },
                            valueRange = 0f..80f,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 12.dp)
                        )
                        Text("${dimPct.toInt()}%", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (editing && canEdit) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StampSymbol.entries.forEach { sym ->
                            StampPaletteButton(
                                symbol = sym,
                                selected = sym == activeSymbol,
                                onClick = {
                                    activeSymbol = sym
                                    drawingTool = false
                                    eraserTool = false
                                }
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // transparent swatch = no tint (theme color)
                        ColorSwatchButton(
                            color = Color.Transparent,
                            selected = activeColor == null,
                            onClick = {
                                activeColor = null
                                if (selectedStamp?.color != null) {
                                    pushUndo()
                                    editScope.launch { stampRepo.update(selectedStamp.copy(color = null)) }
                                }
                            }
                        )
                        Spacer(Modifier.width(6.dp))
                        SWATCH_COLORS.forEach { c ->
                            ColorSwatchButton(
                                color = c,
                                selected = activeColor == c.toArgb(),
                                onClick = {
                                    activeColor = c.toArgb()
                                    if (selectedStamp != null && selectedStamp.color != c.toArgb()) {
                                        pushUndo()
                                        editScope.launch { stampRepo.update(selectedStamp.copy(color = c.toArgb())) }
                                    }
                                }
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                    }
                }
                if (editing && canEdit) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                drawingTool = !drawingTool
                                if (drawingTool) {
                                    activeSymbol = null
                                    eraserTool = false
                                    selectedId = null
                                }
                            },
                            colors = if (drawingTool) ButtonDefaults.buttonColors()
                            else ButtonDefaults.outlinedButtonColors()
                        ) { Text("✏️") }
                        Spacer(Modifier.width(4.dp))
                        Button(
                            onClick = {
                                eraserTool = !eraserTool
                                if (eraserTool) {
                                    activeSymbol = null
                                    drawingTool = false
                                    selectedId = null
                                    selectedStrokeId = null
                                    inkPreview = emptyList()
                                }
                            },
                            colors = if (eraserTool) ButtonDefaults.buttonColors()
                            else ButtonDefaults.outlinedButtonColors()
                        ) { Text("🩹") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val (page, edit) = undoStack.lastOrNull() ?: return@Button
                                undoStack = undoStack.dropLast(1)
                                selectedId = null
                                selectedStrokeId = null
                                dragStamp = null
                                dragStroke = null
                                inkTail.last = null
                                editScope.launch {
                                    stampRepo.restorePage(sheetHash, page, edit.stamps)
                                    strokeRepo.restorePage(sheetHash, page, edit.strokes)
                                }
                            },
                            enabled = undoStack.isNotEmpty()
                        ) { Text("↶") }
                        Spacer(Modifier.width(8.dp))
                        // Saves the ink into the PDF itself (/Annots ink): it stops being a sidecar
                        // and works in any viewer. Ink only; stamps stay in the DB.
                        var exportingPdf by remember { mutableStateOf(false) }
                        Button(
                            onClick = {
                                if (exportingPdf) return@Button
                                exportingPdf = true
                                editScope.launch {
                                    try {
                                        val app = context.applicationContext as FOSScoreApp
                                        val sheet = withContext(Dispatchers.IO) {
                                            app.repository.byHash(sheetHash)
                                        } ?: throw ExportError.NoLocalFile()
                                        val all = withContext(Dispatchers.IO) {
                                            strokeRepo.listBySheet(sheetHash)
                                        }
                                        if (all.isEmpty()) throw ExportError.NoInk()
                                        val pages = all.groupBy({ it.page }) { s ->
                                            InkStroke(decodePoints(s.points), s.width, s.color)
                                        }
                                        val written = withContext(Dispatchers.IO) {
                                            rendererLock.withLock {
                                                MuPdfAnnots.exportInk(sheet.path, pages) { idx ->
                                                    mupdfDoc?.ensurePageSize(idx)
                                                }
                                            }
                                        }
                                        if (written.isEmpty()) throw ExportError.NotEditable()
                                        withContext(Dispatchers.IO) {
                                            strokeRepo.deleteAll(all.filter { it.page in written })
                                        }
                                        rendererLock.withLock {
                                            for (p in written) {
                                                mupdfDoc?.forgetPage(p)
                                                bitmaps.remove(p)
                                            }
                                        }
                                        // undo keeps DB snapshots: once the ink has moved into the PDF they are worthless
                                        undoStack = emptyList()
                                        selectedStrokeId = null
                                        dragStroke = null
                                        annotVersion++
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        Log.e(LOG_TAG, "exportar tinta: ${e.message}", e)
                                        error = (e as? ExportError)?.res ?: R.string.err_export_failed
                                    } finally {
                                        exportingPdf = false
                                    }
                                }
                            },
                            enabled = !exportingPdf && currentCanEdit
                        ) { Text(if (exportingPdf) "…" else "💾PDF") }
                        Spacer(Modifier.width(8.dp))
                        if (drawingTool) {
                            Text(stringResource(R.string.thickness_label), style = MaterialTheme.typography.bodyMedium)
                            Slider(
                                value = penWidthPx,
                                onValueChange = { penWidthPx = it },
                                valueRange = MIN_PEN_PX..MAX_PEN_PX,
                                steps = (MAX_PEN_PX - MIN_PEN_PX).toInt() - 1,
                                modifier = Modifier
                                    .width(140.dp)
                                    .padding(horizontal = 12.dp)
                            )
                            Text("${penWidthPx.roundToInt()} px", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.width(8.dp))
                        }
                        if (selectedStamp != null) {
                            var sliderUndoPushed by remember { mutableStateOf(false) }
                            Text(stringResource(R.string.size_label), style = MaterialTheme.typography.bodyMedium)
                            Slider(
                                value = selectedStamp.size.coerceIn(MIN_STAMP_SIZE, MAX_STAMP_SIZE),
                                onValueChange = { v ->
                                    val current = pageStamps.firstOrNull { it.id == selectedId }
                                    if (current != null && v != current.size) {
                                        if (!sliderUndoPushed) {
                                            pushUndo()
                                            sliderUndoPushed = true
                                        }
                                        editScope.launch { stampRepo.update(current.copy(size = v)) }
                                    }
                                },
                                onValueChangeFinished = { sliderUndoPushed = false },
                                valueRange = MIN_STAMP_SIZE..MAX_STAMP_SIZE,
                                modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
                            )
                            Text("${(selectedStamp.size * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.width(8.dp))
                        }
                        Button(
                            onClick = {
                                if (selectedStamp == null && selectedStroke == null) return@Button
                                pushUndo()
                                selectedId = null
                                selectedStrokeId = null
                                inkTail.last = null
                                hiddenStampIds = hiddenStampIds + listOfNotNull(selectedStamp?.id)
                                hiddenStrokeIds = hiddenStrokeIds + listOfNotNull(selectedStroke?.id)
                                editScope.launch {
                                    selectedStamp?.let { stampRepo.delete(it) }
                                    selectedStroke?.let { strokeRepo.delete(it) }
                                }
                            },
                            enabled = selectedStamp != null || selectedStroke != null
                        ) { Text("🗑") }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Button(
                        onClick = applyPrev,
                        enabled = branch.canPrev
                    ) { Text("◀") }
                    Spacer(Modifier.width(16.dp))
                    Text(
                        branch.label,
                        modifier = Modifier.align(Alignment.CenterVertically),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(Modifier.width(16.dp))
                    Button(onClick = onBack) { Text("✕") }
                    Spacer(Modifier.width(16.dp))
                    Button(
                        onClick = { showDim = !showDim },
                        colors = if (showDim) ButtonDefaults.buttonColors()
                        else ButtonDefaults.outlinedButtonColors()
                    ) { Text("◐") }
                    Spacer(Modifier.width(16.dp))
                    // ponytail: provisional home until there is a settings screen, together with
                    // the language picker and the future page-turn key choice
                    Button(
                        onClick = {
                            keepScreenOn = !keepScreenOn
                            Settings.setKeepScreenOn(context, keepScreenOn)
                        },
                        colors = if (keepScreenOn) ButtonDefaults.buttonColors()
                        else ButtonDefaults.outlinedButtonColors()
                    ) { Text("☀") }
                    Spacer(Modifier.width(16.dp))
                    Button(
                        onClick = applyNext,
                        enabled = branch.canNext
                    ) { Text("▶") }
                }
            }
        }

    }
}