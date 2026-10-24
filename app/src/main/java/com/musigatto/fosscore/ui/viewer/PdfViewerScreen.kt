package com.musigatto.fosscore.ui.viewer

import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.lifecycle.viewmodel.compose.viewModel
import com.musigatto.fosscore.FOSScoreApp
import com.musigatto.fosscore.R
import com.musigatto.fosscore.library.Stamp
import com.musigatto.fosscore.library.Stroke
import com.musigatto.fosscore.musicxml.LayoutSpec
import com.musigatto.fosscore.musicxml.PageGeometry
import com.musigatto.fosscore.pdf.MuPdfDoc
import com.musigatto.fosscore.score.MusicXmlPages
import com.musigatto.fosscore.score.PageSource
import com.musigatto.fosscore.score.SheetFormat
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
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import android.graphics.Rect as AndroidRect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke

// The render (memory) cap now lives in MuPdfDoc.MAX_RENDER_PIXELS: MuPDF draws at whatever
// resolution we ask for, so zoom looks sharp instead of slightly soft.
private const val LOG_TAG = "FOSScore-PDF"

// Room the reading chrome takes over a MusicXML page, below the status bar and above the
// navigation bar: the title bar (8 dp of air, a 48 dp pill, 8 dp) and the page selector (48 dp
// track with 10 dp above and below). Keep in step with ViewerTitleBar and PageSelector.
private val TITLE_BAR_ROOM = 64.dp
private val SELECTOR_ROOM = 68.dp
// and, for MusicXML, the playback band (44 dp buttons) right above the page dots, which then
// lose their 10 dp of air on top
private val PLAYBACK_BAND_ROOM = 44.dp
private val SELECTOR_AIR = 10.dp
// The top-left corner where the ☰ button sits when the bars are shown, as wide as the button with
// its margin: a tap there opens the menu even with the bars hidden. Far from the left third's edge,
// so page turns keep their zone.
private val MENU_CORNER = 112.dp

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

// state of the "stretch" gesture: which strokes (a whole word) scale from which corner
private data class Resizing(
    val strokes: List<Stroke>,
    val ptsPx: List<List<Offset>>,
    val center: Offset,   // center of the box: the scale grows outwards from here
    val startPos: Offset
)

// options panel hanging from the annotation bar (spec §5): tapping the selected tool again opens it
private enum class ToolPanel { Stamps, Pen, Eraser, Color }

// A stroke prepared for drawing: the Path is built in fit coordinates once per change, so a
// redraw (next pen point, pinch, pan) only re-issues it instead of decoding the points again.
private class InkDraw(val id: Long, val path: Path, val color: Color, val width: Float)

private fun inkPath(pts: List<Offset>): Path {
    val path = Path()
    if (pts.size < 2) return path
    path.moveTo(pts[0].x, pts[0].y)
    for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
    return path
}

private fun DrawScope.drawInkPath(pts: List<Offset>, color: Color, widthPx: Float) {
    if (pts.size < 2) return
    drawPath(
        inkPath(pts),
        color = color,
        style = DrawStroke(width = max(1f, widthPx), cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
}

// ink and stamps default to black (not the theme's bluish primary); with "Neg" they are inverted
// like the page, or black ink would disappear on the now-black background
private fun inkTint(color: Int?, invert: Boolean): Color {
    val c = stampColor(color, Color.Black)
    return if (invert) c.inverted() else c
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
    title: String,
    composer: String,
    onBack: () -> Unit,
    onOpenLibrary: () -> Unit = onBack,
    themeMode: ThemeMode,
    onToggleTheme: () -> Unit,
    sheetHash: String? = null
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // the menu, its panels and the score's layout settings: one ViewModel per score
    val menu: ViewerMenuViewModel = viewModel(
        key = "viewer-menu:$pdfUri",
        factory = ViewerMenuViewModel.factory(context, pdfUri.toString()),
    )
    val surface by menu.surface.collectAsState()
    val scoreLayout by menu.layout.collectAsState()
    val parts by menu.parts.collectAsState()

    // MusicXML playback: the MIDI player, the cursor and the A–B loop (PlaybackViewModel)
    val playbackVm: PlaybackViewModel = viewModel(
        key = "playback:$pdfUri",
        factory = PlaybackViewModel.factory(context, pdfUri.toString()),
    )
    val playState by playbackVm.state.collectAsState()
    val loop by playbackVm.loop.collectAsState()
    val cursorNotes by playbackVm.cursor.collectAsState()
    val tempoFactor by playbackVm.tempo.collectAsState()
    val heardPicked by playbackVm.heard.collectAsState()
    val playbackFailed by playbackVm.failed.collectAsState()

    var pageSource by remember { mutableStateOf<PageSource?>(null) }
    // a MusicXML score has no pages of its own: they are laid out for this screen (MusicXmlPages)
    val format = remember(pdfUri) { SheetFormat.of(pdfUri.path.orEmpty()) }
    // bumped when a MusicXML score is laid out again: its pages changed under the same numbers
    var layoutVersion by remember { mutableIntStateOf(0) }
    var pageCount by remember { mutableIntStateOf(0) }
    var currentPage by rememberSaveable(pdfUri) {
        mutableIntStateOf(Settings.lastPage(context, pdfUri.toString()))
    }
    // the same for every score, half page for each orientation (Settings)
    var twoUp by remember { mutableStateOf(Settings.twoUp(context)) }
    var halfEnabled by remember(isLandscape) { mutableStateOf(Settings.halfPage(context, isLandscape)) }
    var halfTurned by rememberSaveable { mutableStateOf(false) }
    // the chrome starts hidden: the app is the page (spec §0)
    var navVisible by rememberSaveable { mutableStateOf(false) }
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
    // last page that was actually drawn: while the next one renders (~300 ms, plus the display
    // list parse) the old page stays on screen instead of a spinner flash. Cleared when the doc
    // changes so a new score never shows the previous one's paper.
    var lastShown by remember(pdfUri) { mutableStateOf<Bitmap?>(null) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var dimPct by remember { mutableFloatStateOf(Settings.dimPct(context)) }
    var invert by remember { mutableStateOf(Settings.invert(context)) }
    var keepScreenOn by remember { mutableStateOf(Settings.keepScreenOn(context)) }

    // Applies only while the viewer is on screen: the library lets the device sleep as usual.
    val view = LocalView.current
    DisposableEffect(view, keepScreenOn, playState) {
        // and always while the music plays (roadmap 4.7): nobody touches the screen then
        view.keepScreenOn = keepScreenOn || playState == PlayState.PLAYING
        onDispose { view.keepScreenOn = false }
    }

    // one page's rows: the viewer draws one page, and re-reading the whole score on every stroke
    // made each stroke cost grow with the score
    val stampsFlow = remember(sheetHash, currentPage) {
        sheetHash?.let {
            (context.applicationContext as FOSScoreApp).stampRepository.observe(it, currentPage)
        } ?: flowOf(emptyList())
    }
    val stamps by stampsFlow.collectAsState(initial = emptyList())
    val stampRepo = (context.applicationContext as FOSScoreApp).stampRepository

    val strokesFlow = remember(sheetHash, currentPage) {
        sheetHash?.let {
            (context.applicationContext as FOSScoreApp).strokeRepository.observe(it, currentPage)
        } ?: flowOf(emptyList())
    }
    val strokes by strokesFlow.collectAsState(initial = emptyList())
    val strokeRepo = (context.applicationContext as FOSScoreApp).strokeRepository

    // height of the title bar, measured: the annotation bar hangs from it. Hoisted out of the chrome
    // block because the bar is removed while the loupe is up (and the height must survive that).
    var titleBarHeight by remember { mutableIntStateOf(0) }
    var annBarPx by remember { mutableIntStateOf(0) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var activeSymbol by rememberSaveable { mutableStateOf<StampSymbol?>(null) }
    var lastSymbol by rememberSaveable { mutableStateOf(StampSymbol.FLAT) }  // glyph on the tool
    var openPanel by rememberSaveable { mutableStateOf<ToolPanel?>(null) }  // options of a tool
    var placePx by rememberSaveable { mutableFloatStateOf(Settings.stampPx(context)) }  // size of the next stamp
    // last size saved as default (persisted): the panel greys its save button while they match
    var defaultStampPx by remember { mutableFloatStateOf(Settings.stampPx(context)) }
    var eraserPx by rememberSaveable { mutableFloatStateOf(DEFAULT_ERASER_PX) }  // eraser radius (px at zoom 1)
    // a finger on the page hides the open panel so it does not cover what you are placing (spec §5)
    var pageTouched by remember { mutableStateOf(false) }
    // Loupe to aim a stamp (screen px). `loupeAnchor` is the finger while it rests; after the
    // long-press timeout it turns into `loupeAt`, which then follows the finger. Both null = off.
    var loupeAnchor by remember { mutableStateOf<Offset?>(null) }
    var loupeAt by remember { mutableStateOf<Offset?>(null) }
    val longPressMs = LocalViewConfiguration.current.longPressTimeoutMillis
    val touchSlopPx = LocalViewConfiguration.current.touchSlop
    // Only a long press opens the loupe (a tap still stamps straight away): hold, then drag.
    LaunchedEffect(loupeAnchor) {
        val anchor = loupeAnchor ?: return@LaunchedEffect
        delay(longPressMs.milliseconds)
        if (loupeAnchor == anchor) loupeAt = anchor
    }
    // selected stamp (for the box with handles) or the selected word of ink
    var selectedStrokeIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    // handle in use: (base object, grabbed corner) while scaling
    var resizing by remember { mutableStateOf<Resizing?>(null) }
    var activeColor by rememberSaveable { mutableStateOf<Int?>(null) }  // tint ARGB; null = theme
    var drawingTool by rememberSaveable { mutableStateOf(false) }      // pen mode: ink, not stamps
    var eraserTool by rememberSaveable { mutableStateOf(false) }      // eraser mode: removes stamps and strokes
    var penWidthPx by remember { mutableFloatStateOf(DEFAULT_PEN_PX) }  // ink thickness (px at zoom 1)
    var dragStamp by remember { mutableStateOf<Stamp?>(null) }
    var dragStrokes by remember { mutableStateOf<List<Stroke>?>(null) }   // preview of the ink being moved/stretched
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var undoStack by remember { mutableStateOf<List<Pair<Int, PageEdit>>>(emptyList()) }
    var redoStack by remember { mutableStateOf<List<Pair<Int, PageEdit>>>(emptyList()) }
    var inkPreview by remember { mutableStateOf<List<Offset>>(emptyList()) }  // preview of the stroke being drawn (px)
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

    // "Página 5 de 9" / "Páginas 3-4 de 9". Empty while the PDF still has no pages, so the pill
    // never says "Página 1 de 0".
    val pageLabel = visiblePageRange(
        currentPage,
        pageCount,
        branch.showTwoUp || (branch.showHalf && halfTurned),
    ).let { r ->
        when {
            r.isEmpty() -> ""
            r.first == r.last -> stringResource(R.string.desc_page, r.first + 1, pageCount)
            else -> stringResource(R.string.desc_page_range, r.first + 1, r.last + 1, pageCount)
        }
    }

    // ponytail: no annotations on MusicXML yet. They are stored per page, and a MusicXML page
    // changes with the screen and the music size; they need to be tied to measures first.
    val canEdit = sheetHash != null && !format.isMusicXml && !branch.showTwoUp &&
        !(branch.showHalf && halfTurned)

    // marks and stamps of the current page, shared between the overlay and the gestures.
    // The fit comes from MuPDF's size in points (not from the bitmap), so the UI can lay out from
    // the first frame and the overlay does not wait for a draw to land.
    val pageBox = pageSource?.pageSize(currentPage)
    // the DAO already returns only this page's rows; this drops the ones deleted a second ago
    val pageStamps = remember(stamps, hiddenStampIds) { stamps.filter { it.id !in hiddenStampIds } }
    val pageStrokes = remember(strokes, hiddenStrokeIds) { strokes.filter { it.id !in hiddenStrokeIds } }
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
    // the ink that is on screen: what the DB returned plus what it has not handed back yet
    // (pendingInk). Drawing, hit-testing and grouping must all see the same set, or a letter
    // written a second ago is not selectable and the word's box comes out short.
    val inkStrokes = (pendingInk + pageStrokes).distinctBy { it.id }
    // the same ink, ready to draw: decode + Path once per change, not once per frame
    val inkDraws = remember(inkStrokes, hitFit, invert) {
        inkStrokes.map { st ->
            InkDraw(
                id = st.id,
                path = inkPath(strokePointsPx(st, hitFit)),
                color = inkTint(st.color, invert),
                width = st.width * hitFit.height
            )
        }
    }
    val currentEditing by rememberUpdatedState(editing)
    val currentCanEdit by rememberUpdatedState(canEdit)
    val currentActiveSymbol by rememberUpdatedState(activeSymbol)
    val currentPageStamps by rememberUpdatedState(pageStamps)
    val currentPageStrokes by rememberUpdatedState(pageStrokes)
    val currentPageFit by rememberUpdatedState(pageFit)
    val currentDrawingTool by rememberUpdatedState(drawingTool)
    val currentEraserTool by rememberUpdatedState(eraserTool)
    val currentEraserPx by rememberUpdatedState(eraserPx)
    // where the page is centred in the viewport, and the pure fit rect (origin 0,0):
    // content work and zoom anchoring live in these coordinates
    val currentBase by rememberUpdatedState(Offset(pageFit.left, pageFit.top))
    val currentHitFit by rememberUpdatedState(RectPx(0f, 0f, pageFit.width, pageFit.height))
    // the reading modes (two-up, half page) scale the whole viewport; a single page scales its
    // content. The gesture takes this through rememberUpdatedState: it always reads the live value.
    val currentSpread by rememberUpdatedState(branch.showTwoUp || (branch.showHalf && halfTurned))

    val selectedStamp = pageStamps.firstOrNull { it.id == selectedId }
    val selectedStrokes = inkStrokes.filter { it.id in selectedStrokeIds }

    // selection box in px: the stamp's or the ink's. While dragging or stretching we use the
    // live position (dragStamp/dragStrokes) so the box follows the object.
    val selectionRect: RectPx? = when {
        dragStamp != null -> stampRect(dragStamp!!, hitFit)
        dragStrokes != null -> inkBounds(dragStrokes!!, hitFit)
        selectedStamp != null -> stampRect(selectedStamp, hitFit)
        else -> inkBounds(selectedStrokes, hitFit)
    }
    val currentSelectionRect by rememberUpdatedState(selectionRect)
    val currentSelectedStamp by rememberUpdatedState(selectedStamp)
    val currentSelectedStrokes by rememberUpdatedState(selectedStrokes)
    val currentInkStrokes by rememberUpdatedState(inkStrokes)

    // while a drag or a stretch is in progress we draw the object at its live position, not the box
    val showSelection = selectionRect != null && editing && canEdit &&
        !drawingTool && !eraserTool
    // corner handles belong to an INK selection: a stamp's size is the palette slider, and forScore
    // moves a placed stamp with the selection tool instead of resizing it by its corners (spec §5)
    val strokeSelection = selectedStamp == null && (selectedStrokes.isNotEmpty() || dragStrokes != null)

    // stacks snapshots BEFORE every change, each with its page; 10 levels. A new change throws the
    // redo branch away, like any editor.
    fun pushUndo(snapshot: PageEdit = PageEdit(pageStamps, pageStrokes), page: Int = currentPage) {
        undoStack = (undoStack + listOf(page to snapshot)).takeLast(10)
        redoStack = emptyList()
    }

    // what Annotate (menu) and Done (pill) do. Leaving edit mode must not leave anything armed or
    // selected behind.
    fun toggleEditing() {
        // the gate lives here, so it also covers the entry paths that are coming (Apple Pencil, long
        // press). Two-up or a turned half page is no reason to refuse: annotating goes back to one
        // page, below.
        if (!editing && (sheetHash == null || format.isMusicXml)) return
        editing = !editing
        if (editing) {
            twoUp = false
            halfEnabled = false
            halfTurned = false
        } else {
            // back to the pages as the user set them
            twoUp = Settings.twoUp(context)
            halfEnabled = Settings.halfPage(context, isLandscape)
            undoStack = emptyList()
            redoStack = emptyList()
            selectedId = null
            selectedStrokeIds = emptySet()
            dragStamp = null
            dragStrokes = null
            drawingTool = false
            eraserTool = false
            // leaving edit mode must not leave a stamp tool armed for the next time we come back in
            activeSymbol = null
            openPanel = null
            inkPreview = emptyList()
            hiddenStampIds = emptySet()
            hiddenStrokeIds = emptySet()
        }
    }

    // S Pen hover: bringing the stylus close arms the pen, so annotating needs no trip to the bar
    // (Samsung S6 Lite reports hover for its pen). It only acts when coming from reading mode: once
    // you are annotating it must not fight the tool you picked (eraser, stamps...).
    var stylusNear by remember { mutableStateOf(false) }
    LaunchedEffect(stylusNear) {
        if (!stylusNear || editing) return@LaunchedEffect
        if (!canEdit) return@LaunchedEffect
        toggleEditing()
        drawingTool = true
        activeSymbol = null
        eraserTool = false
        openPanel = null
    }
    val stylusHoverMod = Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.none { it.type == PointerType.Stylus }) continue
                when (event.type) {
                    // Exit is the reset: while the pen stays near, `stylusNear` does not change, so
                    // the effect cannot fire twice (leaving edit mode with the pen in hand is safe)
                    PointerEventType.Enter, PointerEventType.Move ->
                        if (event.changes.any { !it.pressed }) stylusNear = true
                    PointerEventType.Exit -> stylusNear = false
                }
            }
        }
    }

    val applyNext: () -> Unit = {
        playbackVm.pageTurnedByHand()
        val f = branch.next()
        currentPage = f.page
        halfTurned = f.halfTurned
    }
    val applyPrev: () -> Unit = {
        playbackVm.pageTurnedByHand()
        val f = branch.prev()
        currentPage = f.page
        halfTurned = f.halfTurned
    }
    // The gesture's pointerInput(Unit) never restarts, so it must read the live turns: a captured
    // lambda keeps the `branch` it closed over and stops advancing past the first page.
    val currentApplyNext by rememberUpdatedState(applyNext)
    val currentApplyPrev by rememberUpdatedState(applyPrev)

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
        dragStrokes?.let { list ->
            // all or nothing: dropping strokes one by one shrank the box to the rows that had not
            // landed yet, which looked like the box jumping from the word to a single stroke
            val settled = list.all { d ->
                strokes.any { s -> s.id == d.id && s.points == d.points && s.width == d.width }
            }
            if (settled) dragStrokes = null
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
            val doc: PageSource = withContext(NonCancellable + Dispatchers.IO) {
                if (format.isMusicXml) MusicXmlPages.open(context, File(pdfUri.path.orEmpty()))
                else MuPdfDoc.open(context, pdfUri)
            }
            if (!isActive) {
                withContext(NonCancellable + Dispatchers.IO) { doc.close() }
                return@LaunchedEffect
            }
            pageCount = doc.pageCount
            if (pageCount > 0) currentPage = currentPage.coerceIn(0, pageCount - 1)
            if (doc is MusicXmlPages) menu.onScoreRead(doc.parts)
            pageSource = doc
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.e(LOG_TAG, "no se pudo abrir el archivo: ${e.message}", e)
            error = R.string.err_open_file
        } catch (e: Exception) {
            Log.e(LOG_TAG, "no se pudo abrir: ${e::class.simpleName}: ${e.message}", e)
            error = if (format.isMusicXml) R.string.err_open_file else R.string.err_invalid_pdf
        }
    }

    // MusicXML: break the music into pages of this screen's size, and again when the screen turns
    // or two-up starts (two pages of half the width). The reading position is kept as a fraction
    // of the score: the page breaks move, not the music.
    val densityDpi = configuration.densityDpi
    val twoUpSlots = isLandscape && twoUp && !halfEnabled
    // A MusicXML page fills the viewport, while the system bars, and the title bar and page
    // selector when the centre tap shows them, are drawn over it (a PDF has a paper margin there).
    // The music keeps their room free: the side bars on both sides, the top and bottom chrome as
    // page margins. Fixed, so showing the bars never lays the music out again.
    val density = LocalDensity.current
    val bars = WindowInsets.safeDrawing
    val barsX = max(bars.getLeft(density, LayoutDirection.Ltr), bars.getRight(density, LayoutDirection.Ltr))
    val chromeTop = bars.getTop(density) + with(density) { TITLE_BAR_ROOM.roundToPx() }
    val chromeBottom = bars.getBottom(density) +
        with(density) { (SELECTOR_ROOM - SELECTOR_AIR + PLAYBACK_BAND_ROOM).roundToPx() }
    LaunchedEffect(pageSource, viewport, twoUpSlots, scoreLayout, parts, barsX, chromeTop, chromeBottom) {
        val pages = pageSource as? MusicXmlPages ?: return@LaunchedEffect
        if (viewport == IntSize.Zero) return@LaunchedEffect
        val usableW = (viewport.width - 2 * barsX).coerceAtLeast(1)
        val spec = LayoutSpec(
            width = if (twoUpSlots) usableW / 2 else usableW,
            height = viewport.height,
            // the music size follows the whole screen, so two-up does not shrink the notes
            scale = (LayoutSpec.defaultScale(viewport.width, viewport.height, densityDpi) * scoreLayout.musicSize / 100)
                .coerceIn(LayoutSpec.MIN_SCALE, LayoutSpec.MAX_SCALE),
            // a very short window keeps at least half its height for the music
            marginTop = chromeTop.coerceAtMost(viewport.height / 4),
            marginBottom = chromeBottom.coerceAtMost(viewport.height / 4),
            semitones = scoreLayout.semitones,
            parts = scoreLayout.layoutParts(parts),
            lyrics = scoreLayout.lyrics,
            hiddenLyrics = scoreLayout.keepHiddenLyrics,
        )
        val before = pageCount
        try {
            val changed = rendererLock.withLock {
                withContext(Dispatchers.Default) { pages.layout(spec) }
            }
            if (!changed) return@LaunchedEffect
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(LOG_TAG, "MusicXML layout failed: ${e::class.simpleName}: ${e.message}", e)
            error = R.string.err_open_file
            return@LaunchedEffect
        }
        val after = pages.pageCount
        if (after > 0) {
            currentPage = (if (before > 0) currentPage * after / before else currentPage)
                .coerceIn(0, after - 1)
        }
        halfTurned = false
        pageCount = after
        bitmaps.clear()
        layoutVersion++
    }

    // Playback plays what is loaded: a new transposition, new parts or new lyrics reload the score
    // with new note ids, and stop the music. A new size or a turned screen keeps it playing.
    val shownIds = scoreLayout.partsShown(parts)
    LaunchedEffect(pageSource, scoreLayout.semitones, shownIds, scoreLayout.lyrics, scoreLayout.keepHiddenLyrics) {
        playbackVm.attach(
            pageSource as? MusicXmlPages,
            shownIds,
            listOf(scoreLayout.semitones, shownIds, scoreLayout.lyrics, scoreLayout.keepHiddenLyrics),
        )
    }

    // The notes on screen, for ▶ (it starts where the eyes are) and for the follow below. A turned
    // half page shows the top half of the next page over the bottom half of this one.
    fun notesOnScreen(notes: Collection<String>? = null): Set<String> {
        val pages = pageSource as? MusicXmlPages ?: return emptySet()
        fun onPage(index: Int, keep: (PageGeometry, String) -> Boolean = { _, _ -> true }): List<String> {
            val geo = pages.geometry(index) ?: return emptyList()
            return (notes ?: geo.noteIds).filter { it in geo.noteIds && keep(geo, it) }
        }
        val pageHeight = pageBox?.let { b -> { geo: PageGeometry -> geo.width * b.height / b.width } }
        fun upper(geo: PageGeometry, id: String) =
            pageHeight == null || (geo.head(id)?.y ?: 0f) < pageHeight(geo) / 2
        return when {
            branch.showTwoUp -> onPage(currentPage) + onPage(currentPage + 1)
            branch.showHalf && halfTurned ->
                onPage(currentPage + 1) { g, id -> upper(g, id) } + onPage(currentPage) { g, id -> !upper(g, id) }
            else -> onPage(currentPage)
        }.toSet()
    }

    val toFirstPage by playbackVm.toFirstPage.collectAsState()
    LaunchedEffect(toFirstPage) {
        if (toFirstPage == 0) return@LaunchedEffect
        currentPage = 0
        halfTurned = false
    }

    // Follow the music: when the notes being played leave the screen, turn to their page. A page
    // turned by hand during playback lets the reader look ahead: the follow waits until the music
    // is on screen again (as in the MusicXML prototype).
    LaunchedEffect(cursorNotes) {
        val pages = pageSource as? MusicXmlPages ?: return@LaunchedEffect
        if (cursorNotes.isEmpty()) return@LaunchedEffect
        if (notesOnScreen(cursorNotes).isNotEmpty()) {
            playbackVm.musicOnScreen()
            return@LaunchedEffect
        }
        if (playbackVm.followPaused) return@LaunchedEffect
        val target = pages.pageOf(cursorNotes.first())
        if (target < 0) return@LaunchedEffect
        when {
            // half page: the top of the next page shows first, under the end of this one
            branch.showHalf && !halfTurned && target == currentPage + 1 -> halfTurned = true
            branch.showTwoUp && target == currentPage + 1 -> Unit
            else -> {
                currentPage = target
                halfTurned = false
            }
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
    LaunchedEffect(pageSource, viewport) {
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
        pageSource, currentPage, branch.showTwoUp, branch.showHalf, halfTurned, renderScale, viewport,
        layoutVersion,
    ) {
        val r = pageSource ?: return@LaunchedEffect
        // a MusicXML score has no pages until it is laid out (the effect above)
        if (r is MusicXmlPages && r.pageCount == 0) return@LaunchedEffect
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
                        ZoomMath.renderSlot(fit, rs)
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
                // keep the page on screen while the next one renders (see lastShown)
                bitmaps[currentPage]?.let { lastShown = it }
                // we do not pile up bitmaps of pages that are no longer on screen (16 MB each when sharp)
                bitmaps.keys.filterNot { it in needed }.forEach { bitmaps.remove(it) }
                // no bitmap for this page means nothing to show: say so instead of leaving the
                // spinner turning forever (it happened with a blank page). Only for a render made
                // for this page: a MusicXML score laid out again (two pages -> one) changes the
                // page numbers while an older render finishes, and the newer one is on its way.
                if (currentPage in needed && bitmaps[currentPage] == null) {
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

    // Prefetch both neighbours: a draw costs ~100-300 ms (measured on device), and drawing them
    // ahead of time makes the turn a cache hit in MuPdfDoc (same page, same width key).
    // Only at 1x: a turn resets the zoom, so a draw at a zoomed slot would never be reused.
    // The delay gives a quick turn the lock: turning before it fires cancels this effect.
    LaunchedEffect(
        pageSource, currentPage, viewport, renderScale, branch.showTwoUp, branch.showHalf, layoutVersion,
    ) {
        val r = pageSource ?: return@LaunchedEffect
        if (branch.showTwoUp || (branch.showHalf && halfTurned)) return@LaunchedEffect
        if (renderScale != 1f || viewport == IntSize.Zero) return@LaunchedEffect
        delay(RENDER_SETTLE_MS.milliseconds)
        val vpW = viewport.width.toFloat()
        val vpH = viewport.height.toFloat()
        rendererLock.withLock {
            withContext(Dispatchers.Default) {
                for (page in listOf(currentPage + 1, currentPage - 1)) {
                    if (page !in 0 until pageCount) continue
                    // a turn supersedes this prefetch: drop the rest instead of holding the lock
                    if (!isActive) return@withContext
                    val box = r.ensurePageSize(page) ?: continue
                    if (box.height <= 0f) continue
                    val fit = fitRect(vpW, vpH, box.width / box.height)
                    val slot = ZoomMath.renderSlot(fit, 1f)
                    r.render(page, slot.first, slot.second)
                }
            }
        }
    }

    DisposableEffect(pageSource) {
        val current = pageSource
        onDispose {
            // never block the main thread waiting for a draw in flight (ANR): close() goes to
            // applicationScope; the mutex's FIFO queue makes sure it runs after the draw.
            val scope = (context.applicationContext as FOSScoreApp).applicationScope
            scope.launch {
                rendererLock.withLock { current?.close() }
            }
        }
    }

    // Room the top chrome takes from the page while annotating, and nothing at all while reading:
    // the page is laid out next to the bars instead of under them (#19).
    val chromeInset = with(LocalDensity.current) {
        (if (editing && canEdit) titleBarHeight + annBarPx else 0).toDp()
    }
    // a different page area is a different clamp: entering edit mode must not leave a zoomed page
    // parked outside its bounds
    LaunchedEffect(viewport) { clampOffsets() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // the matte around the page: the theme background, and black when "Neg" is on (the page
            // turns black and the bands were left white)
            .background(if (invert) Color.Black else MaterialTheme.colorScheme.background)
            .then(stylusHoverMod)
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
        // The page lives in its own box: in edit mode it starts below the chrome instead of under
        // it (#19), and `viewport` is that box, so fit, zoom, tap zones and the loupe all follow.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = chromeInset)
                .onSizeChanged { viewport = it }
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
                            pageTouched = true   // the stamps palette steps aside while you touch the page
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
                            var movingStrokes: List<Stroke>? = null
                            var movingStrokesBase: List<Stroke>? = null
                            var moveStrokeLastPos = downF
                            var inkActive = false
                            var inkPts = mutableListOf<Offset>()
                            var eraserActive = false
                            var eraserPath = mutableListOf<Offset>()
                            var scaleF = 1f   // accumulated handle factor (always from startPos)
                            if (currentEditing && currentCanEdit && !currentDrawingTool && !currentEraserTool) {
                                // 1) a handle on a STROKE's selection box wins over everything else:
                                //    entering by the corner stretches, entering inside moves. Stamps
                                //    have no handles (a corner drag sized them by accident); their size
                                //    is the slider.
                                val sel = currentSelectionRect
                                val corner = if (sel != null && currentSelectedStamp == null) {
                                    hitHandle(downF, sel, currentHitFit)
                                } else null
                                if (corner != null) {
                                    val word = currentSelectedStrokes
                                    val box = inkBounds(word, currentHitFit)
                                    if (word.isNotEmpty() && box != null) {
                                        resizing = Resizing(
                                            strokes = word,
                                            ptsPx = word.map { st ->
                                                strokePointsPx(st, currentHitFit)
                                            },
                                            center = box.center(),
                                            startPos = downF
                                        )
                                    }
                                }
                                if (resizing == null) {
                                    val hitS = hitStamp(downF, currentPageStamps, currentHitFit)
                                    // A tap selects the WORD, not the single stroke. The grouping is
                                    // only needed here, on the tap: doing it on every change made it
                                    // O(n²) over the page after every stroke.
                                    val groups = if (hitS != null) {
                                        emptyList()
                                    } else groupInk(currentInkStrokes, currentHitFit)
                                    // If no stroke is hit, the word's box still catches it: the hole
                                    // of an 'o' and the gap between two letters have no ink to aim at
                                    val group = if (hitS != null) null else {
                                        val st = hitStroke(downF, currentInkStrokes, currentHitFit)
                                        st?.let { hit ->
                                            groups.firstOrNull { g -> g.strokes.any { it.id == hit.id } }
                                        } ?: groupAt(downF, groups, handleRadiusPx(currentHitFit))
                                    }
                                    if (hitS != null) {
                                        movingStamp = hitS
                                        movingOrigin = hitS
                                        selectedId = hitS.id
                                        selectedStrokeIds = emptySet()
                                        // Selecting a stamp must NOT arm its symbol, or the next tap
                                        // outside it silently stamps a second copy.
                                        dragStamp = hitS
                                    } else if (group != null) {
                                        movingStrokes = group.strokes
                                        movingStrokesBase = group.strokes
                                        moveStrokeLastPos = downF
                                        selectedStrokeIds = group.strokes.map { it.id }.toSet()
                                        selectedId = null
                                        // no preview yet: the box comes from the ids, which are
                                        // already the whole word. The preview is armed on the first
                                        // real movement (see the move branch).
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
                            // stamp armed on an empty spot: a long press starts the loupe, so the finger
                            // does not hide the exact place the stamp lands
                            var lastPlacePos = down.position
                            if (currentEditing && currentCanEdit && currentActiveSymbol != null &&
                                movingStamp == null && movingStrokes == null
                            ) {
                                loupeAnchor = down.position
                            }

                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty()) {
                                    pageTouched = false
                                    // did the loupe decide this placement? (cleared just below)
                                    val aimed = loupeAt != null
                                    loupeAnchor = null
                                    loupeAt = null
                                    // local copy: a delegated var captured by this lambda gets no smart
                                    // cast, so the null check needs a stable local
                                    val rz = resizing
                                    if (rz != null) {
                                        resizing = null
                                        val pts = rz.ptsPx.map { scalePoints(it, rz.center, scaleF) }
                                        val changed = pts != rz.ptsPx
                                        if (changed) {
                                            pushUndo(PageEdit(currentPageStamps, currentPageStrokes))
                                            editScope.launch {
                                                rz.strokes.forEachIndexed { i, base ->
                                                    strokeRepo.update(
                                                        base.copy(
                                                            points = encodePoints(
                                                                pts[i].map {
                                                                    pxToNormalized(it, currentHitFit)
                                                                }
                                                            ),
                                                            width = (base.width * scaleF)
                                                                .coerceIn(MIN_STROKE_WIDTH, MAX_STROKE_WIDTH)
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                    } else if (inkActive) {
                                        // ink capture lives in fit coordinates (F): the sampling
                                        // threshold is a screen distance, so it scales with the zoom
                                        val pts = samplePoints(inkPts, MIN_SAMPLE_DIST_PX / scale)
                                        inkPreview = emptyList()
                                        if (pts.size >= 2 && sheetHash != null && currentPageFit.width > 0f) {
                                            pushUndo(PageEdit(currentPageStamps, currentPageStrokes))
                                            val s = Stroke(
                                                sheetHash = sheetHash,
                                                page = currentPage,
                                                points = encodePoints(
                                                    pts.map { pxToNormalized(it, currentHitFit) }
                                                ),
                                                width = penWidthPxToFrac(penWidthPx, currentHitFit),
                                                color = activeColor
                                            )
                                            editScope.launch {
                                                val stored = s.copy(id = strokeRepo.insert(s))
                                                pendingInk = pendingInk + stored
                                            }
                                        }
                                    } else if (eraserActive) {
                                        val path = eraserPath
                                        inkPreview = emptyList()
                                        if (path.isNotEmpty() && sheetHash != null && currentPageFit.width > 0f) {
                                            pushUndo(PageEdit(currentPageStamps, currentPageStrokes))
                                            val radiusPx = currentEraserPx
                                            // the eraser always deletes what it grazes: to remove a whole
                                            // word, tap it (that selects it) and press the bin
                                            val goneStamps = stampsTouched(
                                                currentPageStamps, currentHitFit, path, radiusPx
                                            )
                                            val goneStrokes = mutableListOf<Stroke>()
                                            val newRuns = mutableListOf<Stroke>()
                                            for (st in currentPageStrokes) {
                                                val ptsPx = strokePointsPx(st, currentHitFit)
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
                                            selectedStrokeIds = emptySet()
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
                                    } else if (movingStrokes != null) {
                                        val done = movingStrokes
                                        val base = movingStrokesBase
                                        val moved = base != null &&
                                            done.map { it.points } != base.map { it.points }
                                        if (moved) {
                                            // keep the final position until the DB confirms it, or
                                            // the overlay would draw the old one for a few ms
                                            dragStrokes = done
                                            pushUndo(PageEdit(currentPageStamps, currentPageStrokes))
                                            editScope.launch { done.forEach { strokeRepo.update(it) } }
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
                                                selectedStrokeIds = emptySet()
                                                dragStamp = null
                                                dragStrokes = null
                                            }
                                            if (sym != null && sheetHash != null &&
                                                (aimed || totalPan.getDistance() <= touchSlopPx)
                                            ) {
                                                // a drag is a pan and must not drop a stamp; the loupe is
                                                // the way to aim a placement that is not a plain tap
                                                val pos = pxToNormalized(fitPoint(lastPlacePos), currentHitFit)
                                                // One stamp per arming: stand the tool down as soon as it
                                                // is used, so the next tap outside the new stamp deselects
                                                // instead of stamping again.
                                                activeSymbol = null
                                                openPanel = null
                                                pushUndo(PageEdit(currentPageStamps, currentPageStrokes))
                                                editScope.launch {
                                                    val id = stampRepo.insert(
                                                        Stamp(
                                                            sheetHash = sheetHash,
                                                            page = currentPage,
                                                            symbol = sym.name,
                                                            x = pos.x.coerceIn(0f, 1f),
                                                            y = pos.y.coerceIn(0f, 1f),
                                                            size = stampSizePxToFrac(placePx, currentHitFit),
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
                                                if (totalPan.x < 0f) currentApplyNext() else currentApplyPrev()
                                            } else {
                                                // tap zones: the top-left corner opens the menu, left third prev,
                                                // right third next, center toggles the bars
                                                val width = viewport.width
                                                val corner = bars.getLeft(density, LayoutDirection.Ltr) + MENU_CORNER.toPx()
                                                when {
                                                    down.position.x < corner && down.position.y < chromeTop ->
                                                        menu.show(ViewerSurface.MENU)
                                                    width > 0 && down.position.x > width * 2f / 3f -> currentApplyNext()
                                                    width > 0 && down.position.x < width / 3f -> currentApplyPrev()
                                                    else -> navVisible = !navVisible
                                                }
                                            }
                                        }
                                    }
                                    break
                                }

                                if (loupeAt != null) {
                                    if (pressed.size > 1) {
                                        // a second finger is a pinch, not an aimed placement
                                        loupeAnchor = null
                                        loupeAt = null
                                    } else {
                                        // the loupe owns the finger: it follows it and the page must not pan
                                        val tracking = pressed.firstOrNull { it.id == down.id } ?: pressed.first()
                                        lastPlacePos = tracking.position
                                        loupeAt = tracking.position
                                        event.changes.forEach { change -> if (change.positionChanged()) change.consume() }
                                        prevCentroid = tracking.position
                                        continue
                                    }
                                }

                                if (resizing != null) {
                                    // stretching the selected word by a handle: one pointer, scaling
                                    // around the box center. the factor is worked out from the
                                    // starting position, never accumulated
                                    val tracking = pressed.firstOrNull { it.id == down.id } ?: pressed.first()
                                    val rz = resizing!!
                                    val f = scaleFactor(rz.center, rz.startPos, fitPoint(tracking.position))
                                    scaleF = f
                                    dragStrokes = rz.strokes.mapIndexed { i, st ->
                                        st.copy(
                                            points = encodePoints(
                                                scalePoints(rz.ptsPx[i], rz.center, f)
                                                    .map { pxToNormalized(it, currentHitFit) }
                                            ),
                                            width = (st.width * f)
                                                .coerceIn(MIN_STROKE_WIDTH, MAX_STROKE_WIDTH)
                                        )
                                    }
                                    prevCentroid = tracking.position
                                    event.changes.forEach { change -> if (change.positionChanged()) change.consume() }
                                    continue
                                }

                                if (movingStrokes != null) {
                                    val tracking = pressed.firstOrNull { it.id == down.id } ?: pressed.first()
                                    val m = pxToNormalized(fitPoint(tracking.position), currentHitFit) -
                                        pxToNormalized(moveStrokeLastPos, currentHitFit)
                                    moveStrokeLastPos = fitPoint(tracking.position)
                                    val ns = movingStrokes.map { st ->
                                        st.copy(
                                            points = encodePoints(
                                                decodePoints(st.points).map {
                                                    Offset(
                                                        (it.x + m.x).coerceIn(0f, 1f),
                                                        (it.y + m.y).coerceIn(0f, 1f)
                                                    )
                                                }
                                            )
                                        )
                                    }
                                    movingStrokes = ns
                                    dragStrokes = ns
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
                                            // stamps are only removed on release, like strokes: the
                                            // trail may graze one on the way to another
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
                                // moving before the long press fired is a pan, not an aimed placement
                                if (loupeAt == null && totalPan.getDistance() > touchSlopPx) {
                                    loupeAnchor = null
                                }
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
                            for (index in listOf(currentPage, currentPage + 1)) {
                                val bmp = bitmaps[index] ?: continue
                                Box(Modifier.weight(1f).fillMaxHeight()) {
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = null,
                                        contentScale = ContentScale.Fit,
                                        colorFilter = if (invert) INVERT_FILTER else null,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    val geo = (pageSource as? MusicXmlPages)?.geometry(index)
                                    if (geo != null && cursorNotes.isNotEmpty()) {
                                        FitPlaybackCursor(geo, cursorNotes, bmp.width, bmp.height, Modifier.fillMaxSize())
                                    }
                                }
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
                            canvas.drawBitmap(next, AndroidRect(0, 0, next.width, next.height / 2), AndroidRect(0, 0, w, h / 2), paint)
                            canvas.drawBitmap(cur, AndroidRect(0, cur.height / 2, cur.width, cur.height), AndroidRect(0, h / 2, w, h), paint)
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
                        (bitmaps[currentPage] ?: lastShown)?.let { bmp ->
                            // Single page: the bitmap is drawn at the current zoom (fit × scale, the
                            // same size it takes on screen) and shown 1:1 — the GPU rescales nothing.
                            // The overlay (ink/stamps/selection) is drawn in fit coordinates and the
                            // zoom is applied inside the drawing with translate+scale: vector, redrawn
                            // every frame, sharp at any zoom.
                            // scale/offset are read ONLY in draw/measure lambdas below: reading them
                            // here recomposed the whole screen on every pinch or pan frame.
                            val density = LocalDensity.current
                            // clipToBounds: without it the zoomed page (a 3000x3922 box) is drawn
                            // outside the viewport and COVERS the app bars (seen on the test device:
                            // zooming in made the mode bar and the page bar disappear).
                            Box(modifier = Modifier.fillMaxSize().clipToBounds()
                                // Loupe: on a long press with a stamp armed (see the gesture) the whole
                                // scene is drawn again, magnified, inside a circle above the finger.
                                // drawContent() twice is the trick: the magnified view is exactly what
                                // is on screen (paper, ink, stamps, selection), not a second renderer
                                // that only knows about the bitmap.
                                .drawWithContent {
                                    drawContent()
                                    val at = loupeAt ?: return@drawWithContent
                                    val margin = LOUPE_MARGIN_DP.dp.toPx()
                                    val radius = LOUPE_RADIUS_DP.dp.toPx()
                                    val center = loupeCenter(at, radius, margin, size)
                                    // the footprint the stamp will take (screen px * zoom)
                                    val k = scale
                                    clipPath(Path().apply { addOval(Rect(center = center, radius = radius)) }) {
                                        withTransform({
                                            translate(center.x, center.y)
                                            scale(2f, 2f, pivot = Offset.Zero)
                                            translate(-at.x, -at.y)
                                        }) {
                                            this@drawWithContent.drawContent()
                                            drawRect(
                                                color = Color(0xFF00A0FF),
                                                topLeft = Offset(at.x - placePx * k / 2f, at.y - placePx * k / 2f),
                                                size = Size(placePx * k, placePx * k),
                                                style = DrawStroke(width = 2f)
                                            )
                                        }
                                    }
                                    // two rings: they have to read on white paper and in dark mode
                                    drawCircle(
                                        color = Color.Black.copy(alpha = 0.45f),
                                        radius = radius + 1.dp.toPx(),
                                        center = center,
                                        style = DrawStroke(width = 2.dp.toPx())
                                    )
                                    drawCircle(
                                        color = Color.White,
                                        radius = radius,
                                        center = center,
                                        style = DrawStroke(width = 2.dp.toPx())
                                    )
                                }
                                .then(gestureMod)) {
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = stringResource(
                                            R.string.desc_page, currentPage + 1, pageCount
                                        ),
                                        contentScale = ContentScale.FillBounds,
                                        colorFilter = if (invert) INVERT_FILTER else null,
                                        // The page is laid out at fit size and the zoom goes through
                                        // graphicsLayer (a DRAW transform): reading scale/offset inside
                                        // that lambda keeps a pinch from recomposing or re-measuring
                                        // anything. At rest scale == the render step, so the factor is
                                        // exactly 1 and the bitmap stays 1:1 (no resampling). POSITION
                                        // has to be a draw transform, not Modifier.offset (LAYOUT): with
                                        // offset the page slid out from under the ink and the stamps.
                                        modifier = Modifier
                                            .size(
                                                with(density) { pageFit.width.toDp() },
                                                with(density) { pageFit.height.toDp() }
                                            )
                                            .graphicsLayer {
                                                transformOrigin = TransformOrigin(0f, 0f)
                                                scaleX = scale
                                                scaleY = scale
                                                translationX = pageFit.left + offsetX
                                                translationY = pageFit.top + offsetY
                                            }
                                    )
                                // the playback cursor, at the page's place and zoom
                                val cursorGeo = (pageSource as? MusicXmlPages)?.geometry(currentPage)
                                if (cursorGeo != null && cursorNotes.isNotEmpty() && bitmaps[currentPage] != null) {
                                    Canvas(modifier = Modifier.fillMaxSize()) {
                                        drawPlaybackCursor(
                                            cursorGeo, cursorNotes, pageFit.left + offsetX, pageFit.top + offsetY,
                                            pageFit.width * scale
                                        )
                                    }
                                }
                                // the overlay belongs to currentPage: while lastShown is still the
                                // previous page's paper, its ink would land on the wrong music
                                if (canEdit && bitmaps[currentPage] != null) {
                                    // committed ink: paths already built (inkDraws), so this canvas
                                    // only re-issues them when something redraws the page
                                    Canvas(modifier = Modifier.fillMaxSize()) {
                                        withTransform({
                                            translate(pageFit.left + offsetX, pageFit.top + offsetY)
                                            scale(scale, scale, pivot = Offset.Zero)
                                        }) {
                                            for (d in inkDraws) {
                                                // the ink being moved/stretched is drawn only at its
                                                // live position (otherwise two copies = a ghost)
                                                if (dragStrokes?.any { it.id == d.id } == true) continue
                                                drawPath(
                                                    d.path,
                                                    d.color,
                                                    style = DrawStroke(
                                                        width = max(1f, d.width),
                                                        cap = StrokeCap.Round,
                                                        join = StrokeJoin.Round
                                                    )
                                                )
                                            }
                                        }
                                    }
                                    // live ink (the stroke being written, the eraser trail, the word
                                    // being moved) in its own canvas: a pen point only redraws this one
                                    Canvas(modifier = Modifier.fillMaxSize()) {
                                        withTransform({
                                            translate(pageFit.left + offsetX, pageFit.top + offsetY)
                                            scale(scale, scale, pivot = Offset.Zero)
                                        }) {
                                            dragStrokes?.forEach { ds ->
                                                drawInkPath(
                                                    strokePointsPx(ds, hitFit),
                                                    inkTint(ds.color, invert),
                                                    ds.width * hitFit.height
                                                )
                                            }
                                            if (inkPreview.size >= 2) {
                                                drawInkPath(
                                                    inkPreview,
                                                    if (eraserTool) Color.Red.copy(alpha = 0.4f)
                                                    else inkTint(activeColor, invert),
                                                    if (eraserTool) eraserPx * 2f
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
                                                translationX = pageFit.left + offsetX
                                                translationY = pageFit.top + offsetY
                                                scaleX = scale
                                                scaleY = scale
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
                                                color = inkTint(shown.color, invert),
                                                modifier = Modifier
                                                    .offset { IntOffset(r.left.roundToInt(), r.top.roundToInt()) }
                                                    .requiredSize(
                                                        with(density) { r.width.toDp() },
                                                        with(density) { r.height.toDp() }
                                                    )
                                            )
                                        }
                                    }
                                    // selection box ON TOP of everything: otherwise it ends up hidden by
                                    // the very stamp being edited. Stamps get the frame only, no corner
                                    // handles: their size is the slider (a corner drag resized them by
                                    // accident); strokes still stretch from the corners.
                                    if (showSelection) {
                                        val hr = handleRadiusPx(hitFit)
                                        val frame = Color(0xFF00A0FF)
                                        Canvas(modifier = Modifier.fillMaxSize()) {
                                            withTransform({
                                                translate(pageFit.left + offsetX, pageFit.top + offsetY)
                                                scale(scale, scale, pivot = Offset.Zero)
                                            }) {
                                                drawRect(
                                                    color = frame,
                                                    topLeft = Offset(selectionRect.left, selectionRect.top),
                                                    size = Size(selectionRect.width, selectionRect.height),
                                                    style = DrawStroke(width = max(1.5f, hr * 0.3f))
                                                )
                                                if (strokeSelection) {
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
                            }
                        } ?: CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                }
            } else {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }

        if (dimPct > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = dimPct / 100f))
            )
        }

        // the loupe goes last: with a finger aiming a stamp the bars step aside, so it has the whole
        // page to read (it is drawn under them: they would cover the circle at the top).
        if ((navVisible || editing) && loupeAt == null) {
            val surface = MaterialTheme.colorScheme.surface

            // ponytail: no ⌄ (score actions), no ⌕ (global search), no long-press-to-annotate yet.
            ViewerTitleBar(
                title = title,
                composer = composer,
                pageLabel = pageLabel,
                editing = editing,
                onOpenMenu = { menu.show(ViewerSurface.MENU) },
                onStopEditing = { toggleEditing() },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    // edge-to-edge: the pills were slipping under the status bar, and in landscape
                    // under the side navigation bar. The measured height includes these insets, so
                    // the annotation bar below still lands right.
                    .onSizeChanged { titleBarHeight = it.height }
                    .windowInsetsPadding(
                        WindowInsets.systemBars.only(
                            WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                        )
                    ),
            )

            // The annotation bar hangs from the measured title bar. forScore *replaces* the title
            // bar with it (spec §5) and that needs its row 1 (✕ / ✓ / ‹ ›), which is not built:
            // until then they stack, and the stamps palette drops from the bar (spec §5).
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset { IntOffset(0, titleBarHeight) }
                    .fillMaxWidth()
                    // landscape: the same side inset the title bar takes, so the tools do not run
                    // under the navigation bar
                    .windowInsetsPadding(
                        WindowInsets.systemBars.only(WindowInsetsSides.Horizontal)
                    )
            ) {
                // the tools are drawn glyphs now, with no text of their own: the names come from here
                val penLabel = stringResource(R.string.tool_pen)
                val eraserLabel = stringResource(R.string.tool_eraser)
                val undoLabel = stringResource(R.string.action_undo)
                val redoLabel = stringResource(R.string.action_redo)
                val deleteLabel = stringResource(R.string.action_delete)
                // one row of tools; the stamps tool comes first (spec §5 row 2). Each tool opens its
                // own options panel on a second tap, so no size slider sits in the bar all the time.
                if (editing && canEdit) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(surface)
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .horizontalScroll(rememberScrollState())
                            // the tool row, not the whole column: an open panel must not move the
                            // page down with it
                            .onSizeChanged { annBarPx = it.height },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // stamps tool (spec §5): shows the last stamp used. Tap once to select it,
                        // again to open its panel (symbols + size). It never touches the selection.
                        StampPaletteButton(
                            symbol = activeSymbol ?: lastSymbol,
                            selected = activeSymbol != null,
                            onClick = {
                                val wasOn = activeSymbol != null
                                val (on, panel) = nextToolTap(wasOn, openPanel == ToolPanel.Stamps)
                                if (!wasOn && on) {
                                    activeSymbol = lastSymbol
                                    drawingTool = false
                                    eraserTool = false
                                } else if (!on) {
                                    activeSymbol = null   // neutral mode: select and move objects
                                }
                                openPanel = if (panel) ToolPanel.Stamps else null
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val wasOn = drawingTool
                                val (on, panel) = nextToolTap(wasOn, openPanel == ToolPanel.Pen)
                                drawingTool = on
                                if (!wasOn && on) {
                                    activeSymbol = null
                                    eraserTool = false
                                }
                                openPanel = if (panel) ToolPanel.Pen else null
                            },
                            colors = if (drawingTool) ButtonDefaults.buttonColors()
                            else ButtonDefaults.outlinedButtonColors(),
                            modifier = Modifier.semantics {
                                contentDescription = penLabel
                            },
                        ) { ToolIcon(ICON_PENCIL) }
                        Spacer(Modifier.width(4.dp))
                        Button(
                            onClick = {
                                val wasOn = eraserTool
                                val (on, panel) = nextToolTap(wasOn, openPanel == ToolPanel.Eraser)
                                eraserTool = on
                                if (!wasOn && on) {
                                    activeSymbol = null
                                    drawingTool = false
                                    selectedId = null
                                    selectedStrokeIds = emptySet()
                                }
                                inkPreview = emptyList()
                                openPanel = if (panel) ToolPanel.Eraser else null
                            },
                            colors = if (eraserTool) ButtonDefaults.buttonColors()
                            else ButtonDefaults.outlinedButtonColors(),
                            modifier = Modifier.semantics {
                                contentDescription = eraserLabel
                            },
                        ) { EraserIcon() }
                        Spacer(Modifier.width(6.dp))
                        // the tint for ink and stamps, in its own little panel (it used to be a whole
                        // row of swatches eating the width of the bar)
                        ColorSwatchButton(
                            color = activeColor?.let { Color(it) } ?: Color.Transparent,
                            selected = openPanel == ToolPanel.Color,
                            onClick = {
                                openPanel = if (openPanel == ToolPanel.Color) null else ToolPanel.Color
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val (page, edit) = undoStack.lastOrNull() ?: return@Button
                                undoStack = undoStack.dropLast(1)
                                // what redo puts back is the state we are leaving behind
                                if (page == currentPage) {
                                    redoStack = (redoStack + (page to
                                        PageEdit(currentPageStamps, currentPageStrokes))).takeLast(10)
                                }
                                selectedId = null
                                selectedStrokeIds = emptySet()
                                dragStamp = null
                                dragStrokes = null
                                // the "looks deleted now" cache is stale after a restore: the
                                // snapshots go back with their original ids
                                hiddenStampIds = emptySet()
                                hiddenStrokeIds = emptySet()
                                editScope.launch {
                                    stampRepo.restorePage(sheetHash, page, edit.stamps)
                                    strokeRepo.restorePage(sheetHash, page, edit.strokes)
                                }
                            },
                            enabled = undoStack.isNotEmpty(),
                            modifier = Modifier.semantics { contentDescription = undoLabel },
                        ) { ToolIcon(ICON_UNDO) }
                        Spacer(Modifier.width(4.dp))
                        Button(
                            onClick = {
                                val (page, edit) = redoStack.lastOrNull() ?: return@Button
                                redoStack = redoStack.dropLast(1)
                                if (page == currentPage) {
                                    undoStack = (undoStack + (page to
                                        PageEdit(currentPageStamps, currentPageStrokes))).takeLast(10)
                                }
                                selectedId = null
                                selectedStrokeIds = emptySet()
                                dragStamp = null
                                dragStrokes = null
                                hiddenStampIds = emptySet()
                                hiddenStrokeIds = emptySet()
                                editScope.launch {
                                    stampRepo.restorePage(sheetHash, page, edit.stamps)
                                    strokeRepo.restorePage(sheetHash, page, edit.strokes)
                                }
                            },
                            enabled = redoStack.isNotEmpty(),
                            modifier = Modifier.semantics { contentDescription = redoLabel },
                        ) { ToolIcon(ICON_REDO) }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (selectedStamp == null && selectedStrokes.isEmpty()) return@Button
                                pushUndo()
                                selectedId = null
                                selectedStrokeIds = emptySet()
                                hiddenStampIds = hiddenStampIds + listOfNotNull(selectedStamp?.id)
                                hiddenStrokeIds = hiddenStrokeIds + selectedStrokes.map { it.id }
                                editScope.launch {
                                    selectedStamp?.let { stampRepo.delete(it) }
                                    if (selectedStrokes.isNotEmpty()) strokeRepo.deleteAll(selectedStrokes)
                                }
                            },
                            enabled = selectedStamp != null || selectedStrokes.isNotEmpty(),
                            modifier = Modifier.semantics { contentDescription = deleteLabel },
                        ) { ToolIcon(ICON_TRASH) }
                    }
                    // one tool panel at a time drops from the bar (spec §5). They all step aside
                    // while a finger is on the page, like forScore's panels.
                    val panel = openPanel
                    if (panel != null && !pageTouched) {
                        when (panel) {
                            ToolPanel.Stamps -> StampPalettePanel(
                                active = activeSymbol ?: lastSymbol,
                                sizePx = placePx,
                                onSymbol = {
                                    activeSymbol = it
                                    lastSymbol = it
                                },
                                onSize = { placePx = it },
                                onSaveDefault = {
                                    Settings.setStampPx(context, placePx)
                                    defaultStampPx = placePx
                                },
                                isDefault = placePx.roundToInt() == defaultStampPx.roundToInt(),
                            )

                            ToolPanel.Pen -> ToolSizePanel(
                                label = stringResource(R.string.thickness_label),
                                value = penWidthPx,
                                range = MIN_PEN_PX..MAX_PEN_PX,
                                onValue = { penWidthPx = it },
                            )

                            ToolPanel.Eraser -> ToolSizePanel(
                                label = stringResource(R.string.size_label),
                                value = eraserPx,
                                range = MIN_ERASER_PX..MAX_ERASER_PX,
                                onValue = { eraserPx = it },
                            )

                            ToolPanel.Color -> ColorPanel(
                                active = activeColor,
                                onPick = { argb ->
                                    activeColor = argb
                                    // the tint also recolours the selected stamp: forScore's
                                    // selection tool changes colour, it does not resize
                                    val current = selectedStamp
                                    if (current != null && current.color != argb) {
                                        pushUndo()
                                        editScope.launch { stampRepo.update(current.copy(color = argb)) }
                                    }
                                }
                            )
                        }
                    }
                }
            }

            // spec §2: the bottom of the reading chrome is the page selector, nothing else
            if (!editing) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(surface)
                        // edge-to-edge: the knob was sitting in the navigation gesture area
                        .navigationBarsPadding()
                ) {
                    if (format.isMusicXml) {
                        val writtenBpm = (pageSource as? MusicXmlPages)?.info?.tempo ?: DEFAULT_BPM
                        PlaybackBand(
                            state = playState,
                            loop = loop,
                            bpm = bpmOf(tempoFactor, writtenBpm),
                            onPlayPause = { playbackVm.playPause(notesOnScreen()) },
                            onRestart = playbackVm::restart,
                            onLoop = playbackVm::toggleLoop,
                            onA = playbackVm::tapA,
                            onB = playbackVm::tapB,
                            onOptions = { menu.show(ViewerSurface.PLAYBACK) },
                        )
                        if (playbackFailed) {
                            Text(
                                stringResource(R.string.playback_failed),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.align(Alignment.CenterHorizontally),
                            )
                        }
                    }
                    PageSelector(
                        pageCount = pageCount,
                        currentPage = currentPage,
                        onJump = {
                            playbackVm.pageTurnedByHand()
                            currentPage = it
                        },
                        // air under the knob: flush with the edge it sat inside the system
                        // navigation gesture area and was hard to grab
                        modifier = Modifier.padding(
                            start = 24.dp,
                            end = 24.dp,
                            top = if (format.isMusicXml) 0.dp else 10.dp,
                            bottom = 10.dp,
                        ),
                    )
                }
            }
        }

        // the menu and its panels, over everything: one at a time (ViewerMenuViewModel)
        when (surface) {
            ViewerSurface.NONE -> Unit
            ViewerSurface.MENU -> ViewerMenuPanel(
                isMusicXml = format.isMusicXml,
                onSelect = { item ->
                    menu.dismiss()
                    when (item) {
                        ViewerMenuItem.LIBRARY -> onOpenLibrary()
                        ViewerMenuItem.DISPLAY -> menu.show(ViewerSurface.DISPLAY)
                        ViewerMenuItem.LAYOUT -> menu.show(ViewerSurface.LAYOUT)
                        ViewerMenuItem.PLAYBACK -> menu.show(ViewerSurface.PLAYBACK)
                        ViewerMenuItem.ANNOTATE -> if (!editing) toggleEditing()
                        // greyed out in the menu: never chosen
                        else -> Unit
                    }
                },
                onDismiss = menu::dismiss,
            )
            ViewerSurface.DISPLAY -> DisplayOptionsOverlay(
                halfEnabled = halfEnabled,
                onSetHalf = {
                    halfEnabled = it
                    halfTurned = false
                    Settings.setHalfPage(context, isLandscape, it)
                },
                twoUp = twoUp,
                onSetTwoUp = {
                    twoUp = it
                    Settings.setTwoUp(context, it)
                },
                twoUpVisible = isLandscape,
                themeLabel = themeMode.label(),
                onToggleTheme = onToggleTheme,
                invert = invert,
                onToggleInvert = {
                    invert = !invert
                    Settings.setInvert(context, invert)
                },
                keepScreenOn = keepScreenOn,
                onToggleKeepScreenOn = {
                    keepScreenOn = !keepScreenOn
                    Settings.setKeepScreenOn(context, keepScreenOn)
                },
                dimPct = dimPct,
                onDimChange = { dimPct = it },
                onDimChangeFinished = { Settings.setDimPct(context, dimPct) },
                onDismiss = menu::dismiss,
            )
            ViewerSurface.PLAYBACK -> {
                val writtenBpm = (pageSource as? MusicXmlPages)?.info?.tempo ?: DEFAULT_BPM
                PlaybackOptionsOverlay(
                    bpm = bpmOf(tempoFactor, writtenBpm),
                    writtenBpm = bpmOf(1f, writtenBpm),
                    minBpm = bpmOf(MIN_TEMPO_FACTOR, writtenBpm),
                    maxBpm = bpmOf(MAX_TEMPO_FACTOR, writtenBpm),
                    onTempo = { playbackVm.setTempo(factorOf(it, writtenBpm)) },
                    parts = parts,
                    heard = heardParts(heardPicked, shownIds, parts),
                    onHeard = { id, on -> playbackVm.setHeard(id, on, parts) },
                    onDismiss = menu::dismiss,
                )
            }
            ViewerSurface.LAYOUT -> ScoreLayoutOverlay(
                layout = scoreLayout,
                parts = parts,
                onMusicSize = menu::setMusicSize,
                onTranspose = menu::transpose,
                onLyrics = menu::setLyrics,
                onKeepHiddenLyrics = menu::setKeepHiddenLyrics,
                onPartShown = menu::setPartShown,
                onDismiss = menu::dismiss,
            )
        }
    }
}