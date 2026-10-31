package com.musigatto.fosscore.ui.viewer

import android.content.res.Configuration
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.musigatto.fosscore.FOSScoreApp
import com.musigatto.fosscore.R
import com.musigatto.fosscore.library.Stamp
import com.musigatto.fosscore.library.Stroke
import com.musigatto.fosscore.metronome.BarPages
import com.musigatto.fosscore.metronome.Meter
import com.musigatto.fosscore.metronome.MetronomeMode
import com.musigatto.fosscore.musicxml.LayoutSpec
import com.musigatto.fosscore.musicxml.PageGeometry
import com.musigatto.fosscore.musicxml.Playback
import com.musigatto.fosscore.score.MusicXmlPages
import com.musigatto.fosscore.score.MusicXmlScreen
import com.musigatto.fosscore.score.PageSource
import com.musigatto.fosscore.score.SheetFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

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
// how long the sizes of a MusicXML page must stay still before it is laid out
private const val LAYOUT_SETTLE_MS = 120L
// The top-left corner where the ☰ button sits when the bars are shown, as wide as the button with
// its margin: a tap there opens the menu even with the bars hidden. Far from the left third's edge,
// so page turns keep their zone.
private val MENU_CORNER = 112.dp

// Zoom the double tap jumps to (2.5x: readable without losing sight of the page).
internal const val DOUBLE_TAP_ZOOM = 2.5f

// The double tap is animated (~1 frame would teleport to the corner): 250 ms, with the exact
// anchor on every frame.
private const val DOUBLE_TAP_ANIM_MS = 250

// Re-draw during a pinch and its settling wait live with the drawing, in PageRender.kt.

// in half page, the cut of the next turn for [page] as laid out in [layout]: the turn makes
// [split], the dotted line shows it at [mark] (a fraction of the page height)
internal data class HalfCut(val page: Int, val layout: Int, val split: HalfSplit, val mark: Float)

// options panel hanging from the annotation bar (spec §5): tapping the selected tool again opens it
internal enum class ToolPanel { Stamps, Pen, Eraser, Color }

// A stroke prepared for drawing: the Path is built in fit coordinates once per change, so a
// redraw (next pen point, pinch, pan) only re-issues it instead of decoding the points again.
internal class InkDraw(val id: Long, val path: Path, val color: Color, val width: Float)

private fun inkDrawsOf(strokes: List<Stroke>, fit: RectPx, invert: Boolean): List<InkDraw> =
    strokes.map { st ->
        InkDraw(
            id = st.id,
            path = inkPath(strokePointsPx(st, fit)),
            color = inkTint(st.color, invert),
            width = st.width * fit.height
        )
    }

@Composable
fun PdfViewerScreen(
    pdfUri: Uri,
    title: String,
    composer: String,
    onBack: () -> Unit,
    onOpenLibrary: () -> Unit = onBack,
    themeMode: ThemeMode,
    onToggleTheme: () -> Unit,
    sheetId: Long? = null
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
    val aheadNotes by playbackVm.ahead.collectAsState()

    // the metronome: its band, its clicks and the page turns it drives (MetronomeViewModel)
    val metronomeVm: MetronomeViewModel = viewModel(
        key = "metronome:$pdfUri",
        factory = MetronomeViewModel.factory(context, pdfUri.toString()),
    )
    val metronome by metronomeVm.settings.collectAsState()
    val metronomeOn by metronomeVm.running.collectAsState()
    val metronomeShown by metronomeVm.shown.collectAsState()
    val metronomeTick by metronomeVm.tick.collectAsState()
    val meterFromScore by metronomeVm.meterFromScore.collectAsState()

    // the page edits: undo/redo, the ink and stamps waiting for the DB, the selection
    // (AnnotationViewModel). One per score, like the menu and the playback.
    val editor: AnnotationViewModel = viewModel(
        key = "annotation:$pdfUri",
        factory = AnnotationViewModel.factory(context, sheetId),
    )
    // how the pages show (two-up, half page, dim, invert, keep the screen on): one ViewModel for
    // every score, reading and writing the settings in one place
    val viewer: ViewerViewModel = viewModel(
        key = "viewer:$isLandscape",
        factory = ViewerViewModel.factory(context, isLandscape),
    )
    // a MusicXML score's bars and notes, for the page turns of the metronome
    var metronomeMusic by remember { mutableStateOf<Playback?>(null) }
    SideEffect { playbackVm.turnAhead = metronome.turnAhead }

    var pageSource by remember { mutableStateOf<PageSource?>(null) }
    // a MusicXML score has no pages of its own: they are laid out for this screen (MusicXmlPages)
    val format = remember(pdfUri) { SheetFormat.of(pdfUri.path.orEmpty()) }
    // bumped when a MusicXML score is laid out again: its pages changed under the same numbers
    var layoutVersion by remember { mutableIntStateOf(0) }
    var currentPage by rememberSaveable(pdfUri) {
        mutableIntStateOf(Settings.lastPage(context, pdfUri.toString()))
    }
    // the same for every score, half page for each orientation (ViewerViewModel)
    val display by viewer.display.collectAsState()
    val twoUp = display.twoUp
    val halfEnabled = display.halfPage
    var halfTurned by rememberSaveable { mutableStateOf(false) }
    // the chrome starts hidden: the app is the page (spec §0)
    var navVisible by rememberSaveable { mutableStateOf(false) }
    // the paper: the document, its bitmaps, the cut of a half page and the lock MuPDF needs
    val paper = rememberPagePaper(pdfUri)
    // the paper that is open: how many pages it has, and why it is not there if it is not.
    // Plain reads: the paper holds them as Compose state, so a change recomposes the screen.
    val pageCount = paper.pageCount
    val error = paper.error
    // zoom and pan: read in the gesture's pointer loop and in the canvas' draw lambdas, never
    // in composition (a per-frame read here would recompose the screen on every pinch frame)
    val zoom = rememberZoomState()
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val dimPct = display.dimPct
    val invert = display.invert
    val keepScreenOn = display.keepScreenOn

    // Applies only while the viewer is on screen: the library lets the device sleep as usual.
    val view = LocalView.current
    DisposableEffect(view, keepScreenOn, playState) {
        // and always while the music plays (roadmap 4.7): nobody touches the screen then
        view.keepScreenOn = keepScreenOn || playState == PlayState.PLAYING
        onDispose { view.keepScreenOn = false }
    }

    // one page's rows: the viewer draws one page, and re-reading the whole score on every stroke
    // made each stroke cost grow with the score
    val stampsFlow = remember(sheetId, currentPage) {
        sheetId?.let {
            (context.applicationContext as FOSScoreApp).stampRepository.observe(it, currentPage)
        } ?: flowOf(emptyList())
    }
    val stamps by stampsFlow.collectAsState(initial = emptyList())

    val strokesFlow = remember(sheetId, currentPage) {
        sheetId?.let {
            (context.applicationContext as FOSScoreApp).strokeRepository.observe(it, currentPage)
        } ?: flowOf(emptyList())
    }
    val strokes by strokesFlow.collectAsState(initial = emptyList())

    // height of the annotation bar, measured: while annotating it is the top bar, and the page keeps
    // clear of it. Hoisted out of the chrome block because the bar is removed while the loupe is up
    // (and the height must survive that).
    var annBarPx by remember { mutableIntStateOf(0) }
    // the height of the bottom chrome (a band, the page dots), kept while it is hidden
    var bottomChromePx by remember { mutableIntStateOf(0) }
    var editing by rememberSaveable { mutableStateOf(false) }
    // ✕ asks before throwing away the session's marks, and only when there are some (issue #50)
    var confirmDiscard by remember { mutableStateOf(false) }
    // the page tag that flashes while annotating: the bar has no room for a page label, so the
    // number shows for a moment after a turn and goes away on its own
    var pageFlash by remember { mutableStateOf("") }
    // how far down the annotation bar was dragged out of the way (px). ✓ and ✕ put it back.
    var barDragY by remember { mutableFloatStateOf(0f) }
    // true only while a finger is on the grip: the bar is see-through while it moves, opaque
    // once it is parked
    var barDragging by remember { mutableStateOf(false) }
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
    // Only a long press opens the loupe (a tap still stamps straight away): hold, then drag.
    LaunchedEffect(loupeAnchor) {
        val anchor = loupeAnchor ?: return@LaunchedEffect
        delay(longPressMs.milliseconds)
        if (loupeAnchor == anchor) loupeAt = anchor
    }
    // selected stamp (for the box with handles) or the selected word of ink
    var activeColor by rememberSaveable { mutableStateOf<Int?>(null) }  // tint ARGB; null = theme
    var drawingTool by rememberSaveable { mutableStateOf(false) }      // pen mode: ink, not stamps
    var eraserTool by rememberSaveable { mutableStateOf(false) }      // eraser mode: removes stamps and strokes
    var penWidthPx by remember { mutableFloatStateOf(DEFAULT_PEN_PX) }  // ink thickness (px at zoom 1)
    var inkPreview by remember { mutableStateOf<List<Offset>>(emptyList()) }  // preview of the stroke being drawn (px)
    val editScope = rememberCoroutineScope()
    // double-tap animation in flight: any new touch cancels it
    var zoomAnimJob by remember { mutableStateOf<Job?>(null) }

    val branch = PageFlow(currentPage, halfTurned, halfEnabled, twoUp, isLandscape, pageCount)
    // two pages on screen at once: the reading modes scale the whole viewport, a single page
    // scales its own content instead
    val spread = branch.showTwoUp || (branch.showHalf && halfTurned)

    // "Página 5 de 9" / "Páginas 3-4 de 9". Empty while the PDF still has no pages, so the pill
    // never says "Página 1 de 0".
    val pageLabel = visiblePageRange(
        currentPage,
        pageCount,
        spread,
    ).let { r ->
        when {
            r.isEmpty() -> ""
            r.first == r.last -> stringResource(R.string.desc_page, r.first + 1, pageCount)
            else -> stringResource(R.string.desc_page_range, r.first + 1, r.last + 1, pageCount)
        }
    }

    // while annotating the page number is not in the bar: it flashes when the page changes and
    // fades by itself. Keyed on the label so it also fires on entering annotation mode.
    LaunchedEffect(pageLabel, editing) {
        if (!editing || pageLabel.isEmpty()) {
            pageFlash = ""
            return@LaunchedEffect
        }
        pageFlash = pageLabel
        delay(1400.milliseconds)
        pageFlash = ""
    }

    // the second page of a spread. The viewer is reading, not editing, there, but its ink and its
    // stamps are drawn too: half of a two-up (or the top of a turned half page) would come out bare.
    val companion = if (spread && currentPage + 1 < pageCount) currentPage + 1 else null
    val companionStampsFlow = remember(sheetId, companion) {
        if (sheetId != null && companion != null) {
            (context.applicationContext as FOSScoreApp).stampRepository.observe(sheetId, companion)
        } else flowOf(emptyList())
    }
    val companionStamps by companionStampsFlow.collectAsState(initial = emptyList())
    val companionStrokesFlow = remember(sheetId, companion) {
        if (sheetId != null && companion != null) {
            (context.applicationContext as FOSScoreApp).strokeRepository.observe(sheetId, companion)
        } else flowOf(emptyList())
    }
    val companionStrokes by companionStrokesFlow.collectAsState(initial = emptyList())

    // ponytail: no annotations on MusicXML yet. They are stored per page, and a MusicXML page
    // changes with the screen and the music size; they need to be tied to measures first.
    val canEdit = sheetId != null && !format.isMusicXml && !branch.showTwoUp &&
        !(branch.showHalf && halfTurned)

    // marks and stamps of the current page, shared between the overlay and the gestures.
    // The fit comes from MuPDF's size in points (not from the bitmap), so the UI can lay out from
    // the first frame and the overlay does not wait for a draw to land.
    val pageBox = pageSource?.pageSize(currentPage)
    // the DAO already returns only this page's rows; this drops the ones deleted a second ago
    val pageStamps = remember(stamps, editor.hiddenStampIds) { stamps.filter { it.id !in editor.hiddenStampIds } }
    val pageStrokes = remember(strokes, editor.hiddenStrokeIds) { strokes.filter { it.id !in editor.hiddenStrokeIds } }
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
    // (editor.pendingInk). Drawing, hit-testing and grouping must all see the same set, or a letter
    // written a second ago is not selectable and the word's box comes out short.
    val inkStrokes = (editor.pendingInk + pageStrokes).distinctBy { it.id }
    // the same ink, ready to draw: decode + Path once per change, not once per frame
    val inkDraws = remember(inkStrokes, hitFit, invert) { inkDrawsOf(inkStrokes, hitFit, invert) }
    // the next page's ink, same fit: its path only has to land on the paper that page takes, and
    // PageOverlay stretches it there
    val companionInkDraws = remember(companionStrokes, hitFit, invert) {
        inkDrawsOf(companionStrokes, hitFit, invert)
    }
    // the zoom frame reads these through rememberUpdatedState: the gesture that calls it captured
    // it when it started, and must see the live page
    val currentPageFit by rememberUpdatedState(pageFit)
    // the reading modes (two-up, half page) scale the whole viewport; a single page scales its
    // content.
    val currentSpread by rememberUpdatedState(spread)

    val selectedStamp = pageStamps.firstOrNull { it.id == editor.selectedId }
    val selectedStrokes = inkStrokes.filter { it.id in editor.selectedStrokeIds }

    // selection box in px: the stamp's or the ink's. While dragging or stretching we use the
    // live position (editor.dragStamp/editor.dragStrokes) so the box follows the object.
    val selectionRect: RectPx? = editor.dragStamp?.let { stampRect(it, hitFit) }
        ?: editor.dragStrokes?.let { inkBounds(it, hitFit) }
        ?: selectedStamp?.let { stampRect(it, hitFit) }
        ?: inkBounds(selectedStrokes, hitFit)

    // while a drag or a stretch is in progress we draw the object at its live position, not the box
    val showSelection = selectionRect != null && editing && canEdit &&
        !drawingTool && !eraserTool
    // corner handles belong to an INK selection: a stamp's size is the palette slider, and forScore
    // moves a placed stamp with the selection tool instead of resizing it by its corners (spec §5)
    val strokeSelection = selectedStamp == null && (selectedStrokes.isNotEmpty() || editor.dragStrokes != null)

    // what Annotate (menu) and Done (pill) do. Leaving edit mode must not leave anything armed or
    // selected behind.
    fun toggleEditing() {
        // the gate lives here, so it also covers the entry paths that are coming (Apple Pencil, long
        // press). Two-up or a turned half page is no reason to refuse: annotating goes back to one
        // page, below.
        if (!editing && (sheetId == null || format.isMusicXml)) return
        editing = !editing
        if (editing) {
            viewer.singlePage()
            halfTurned = false
            // a fresh session: ✕ only throws away what is drawn from now on (issue #50)
            editor.beginSession()
            // the bar always starts at the top
            barDragY = 0f
        } else {
            // back to the pages as the user set them
            viewer.restoreReadingMode()
            editor.clearHistory()
            // ✓ keeps everything: the session's start is no longer needed
            editor.clearSession()
            barDragY = 0f
            editor.clearSelection()
            drawingTool = false
            eraserTool = false
            // leaving edit mode must not leave a stamp tool armed for the next time we come back in
            activeSymbol = null
            openPanel = null
            inkPreview = emptyList()
            editor.clearHidden()
        }
    }

    // S Pen hover: bringing the stylus close arms the pen, so annotating needs no trip to the bar
    // (Samsung S6 Lite reports hover for its pen). It only acts when coming from reading mode: once
    // you are annotating it must not fight the tool you picked (eraser, stamps...).
    var stylusNear by remember { mutableStateOf(false) }
    // Only the first approach arms the pen: after leaving edit mode, hovering again must not drag
    // the tools back over a page the user meant to read (or tap the chrome on).
    // ponytail: one auto-arm per visit to the score; the pill still enters edit mode by hand.
    var hoverArmed by remember { mutableStateOf(false) }
    LaunchedEffect(stylusNear) {
        if (!stylusNear || editing || hoverArmed) return@LaunchedEffect
        if (!canEdit) return@LaunchedEffect
        hoverArmed = true
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

    // The frame the zoom is clamped against: a single page scales its own content (centred on
    // pageFit), the reading modes scale the whole viewport. It reads currentPageFit/currentSpread
    // and viewport live, so the gesture and the double-tap animation get the frame on screen now.
    fun zoomFrameNow(): ZoomFrame {
        val vps = Size(viewport.width.toFloat(), viewport.height.toFloat())
        val fit = currentPageFit
        return if (currentSpread) {
            ZoomFrame(vps, vps, Offset.Zero)
        } else {
            ZoomFrame(Size(fit.width, fit.height), vps, Offset(fit.left, fit.top))
        }
    }

    // Animated zoom to a point (double tap): the scale is interpolated geometrically and anchored
    // on every frame with the pinch formula, so it lands exactly where it should, and you see it
    // travel. Going back to fit interpolates straight to (0,0). Cancels the previous one if any.
    fun animateZoomTo(targetScale: Float, anchor: Offset) {
        zoomAnimJob?.cancel()
        zoomAnimJob = editScope.launch {
            val frame = zoomFrameNow()
            val s0 = zoom.transform.scale
            val o0 = zoom.transform.offset
            if (s0 == targetScale) return@launch
            Animatable(0f).animateTo(1f, tween(DOUBLE_TAP_ANIM_MS, easing = FastOutSlowInEasing)) {
                val e = value
                zoom.set(
                    frame,
                    if (targetScale <= 1f) {
                        ZoomTransform(s0 + (1f - s0) * e, o0 * (1f - e))
                    } else {
                        val k = (targetScale / s0).pow(e)
                        ZoomTransform(
                            (s0 * k).coerceIn(1f, DOUBLE_TAP_ZOOM),
                            ZoomMath.anchoredOffset(o0, k, anchor, frame.base),
                        )
                    },
                )
            }
        }
    }

    // What the annotation bar asks for (spec §5): the bar draws, these decide. One entry point per
    // tool, so the "tap turns it on, tap again opens its options, tap again turns it off" cycle
    // lives in one place instead of three copies inside the buttons.
    val onToolTap: (ToolPanel) -> Unit = { tool ->
        when (tool) {
            ToolPanel.Stamps -> {
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
            ToolPanel.Pen -> {
                val wasOn = drawingTool
                val (on, panel) = nextToolTap(wasOn, openPanel == ToolPanel.Pen)
                drawingTool = on
                if (!wasOn && on) {
                    activeSymbol = null
                    eraserTool = false
                }
                openPanel = if (panel) ToolPanel.Pen else null
            }
            ToolPanel.Eraser -> {
                val wasOn = eraserTool
                val (on, panel) = nextToolTap(wasOn, openPanel == ToolPanel.Eraser)
                eraserTool = on
                if (!wasOn && on) {
                    activeSymbol = null
                    drawingTool = false
                    editor.selectedId = null
                    editor.selectedStrokeIds = emptySet()
                }
                inkPreview = emptyList()
                openPanel = if (panel) ToolPanel.Eraser else null
            }
            ToolPanel.Color -> openPanel = if (openPanel == ToolPanel.Color) null else ToolPanel.Color
        }
    }

    // Select (issue #50): the neutral mode made visible. It only puts the tools down; the
    // selection itself is left alone, as forScore's selection tool does.
    val onSelect: () -> Unit = {
        activeSymbol = null
        drawingTool = false
        eraserTool = false
        openPanel = null
        inkPreview = emptyList()
    }

    // ✕ leaves without what was drawn in this session, and asks first when there is something to
    // lose; ✓ is the plain way out and keeps everything (issue #50)
    val onCancelEditing: () -> Unit = {
        if (editor.hasSessionChanges) confirmDiscard = true else toggleEditing()
    }

    val onUndo: () -> Unit = { editor.undo(currentPage, PageEdit(pageStamps, pageStrokes)) }

    val onRedo: () -> Unit = { editor.redo(currentPage, PageEdit(pageStamps, pageStrokes)) }

    val onDeleteSelection: () -> Unit = {
        editor.deleteSelection(currentPage, selectedStamp, selectedStrokes, PageEdit(pageStamps, pageStrokes))
    }

    BackHandler(onBack = onBack)

    // keyboard / pedal page turns: the viewer root holds the focus, otherwise key events reach
    // nothing and only the on-screen buttons turn pages. Preview so a focused button or slider
    // does not swallow the key first.
    val keyFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { keyFocus.requestFocus() }

    // the page reached and when, on every turn and when the score closes: back to it if the
    // score opens again a short while later (Settings.lastPage)
    LaunchedEffect(currentPage) {
        Settings.setLastPage(context, pdfUri.toString(), currentPage)
    }
    DisposableEffect(pdfUri) {
        onDispose { Settings.setLastPage(context, pdfUri.toString(), currentPage) }
    }

    // the DB no longer has what we hid: clear the optimistic delete (and the drag preview)
    LaunchedEffect(stamps, strokes) { editor.settle(stamps, strokes) }

    // a fresh page (or leaving edit mode) must not keep the ink the DB has not handed back
    LaunchedEffect(currentPage, editing) { editor.pendingInk = emptyList() }

    // MuPDF (prebuilt AAR, AGPL-3.0: see LICENSE). Opens and works out sizes in the background:
    // opening parses the PDF index and must not touch the main thread.
    LaunchedEffect(pdfUri) {
        val doc = paper.open(context, format) ?: return@LaunchedEffect
        if (paper.pageCount > 0) currentPage = currentPage.coerceIn(0, paper.pageCount - 1)
        if (doc is MusicXmlPages) menu.onScoreRead(doc.parts)
        pageSource = doc
    }

    // MusicXML: break the music into pages of this screen's size, and again when the screen turns
    // or two-up starts (two pages of half the width). The reading position is kept as a fraction
    // of the score: the page breaks move, not the music.
    val densityDpi = configuration.densityDpi
    val twoUpSlots = isLandscape && twoUp && !halfEnabled
    // A MusicXML page fills the viewport, while the system bars, and the title bar and page
    // selector when the centre tap shows them, are drawn over it (a PDF has a paper margin there).
    // The music keeps their room free: the sidebars on both sides, the top and bottom chrome as
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
        // the system bars settle over the first frames, restarting this effect each time: a
        // layout for sizes about to change took seconds on a slow tablet, and could not be cut
        // short, so the right one waited behind it
        delay(LAYOUT_SETTLE_MS.milliseconds)
        val usableW = (viewport.width - 2 * barsX).coerceAtLeast(1)
        val screen = MusicXmlScreen(
            viewportWidth = viewport.width,
            viewportHeight = viewport.height,
            pageWidth = if (twoUpSlots) usableW / 2 else usableW,
            baseScale = LayoutSpec.defaultScale(viewport.width, viewport.height, densityDpi),
            // a very short window keeps at least half its height for the music
            marginTop = chromeTop.coerceAtMost(viewport.height / 4),
            marginBottom = chromeBottom.coerceAtMost(viewport.height / 4),
            twoUp = twoUpSlots,
        )
        val spec = screen.spec(scoreLayout, parts)
        // the other scores get laid out and drawn ahead for this screen too
        withContext(Dispatchers.IO) { MusicXmlPages.rememberScreen(context, screen) }
        try {
            val changed = paper.layoutPages(pages, spec)
            if (!changed) return@LaunchedEffect
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(LOG_TAG, "MusicXML layout failed: ${e::class.simpleName}: ${e.message}", e)
            paper.error = R.string.err_open_file
            return@LaunchedEffect
        }
        val after = pages.pageCount
        if (after > 0) {
            currentPage = (if (pageCount > 0) currentPage * after / pageCount else currentPage)
                .coerceIn(0, after - 1)
        }
        halfTurned = false
        paper.pagesRelaidOut(after)
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

    // a page's height in its SVG units, from the page box (null before the first page is read)
    val pageHeight = pageBox?.let { b -> { geo: PageGeometry -> geo.width * b.height / b.width } }

    // Where a turned half page cuts: between two systems, in the blank bands of the pages drawn
    // (HalfSplit), worked out by the paper and read once per picture
    fun halfSplit(): HalfSplit = paper.halfSplit(currentPage, layoutVersion)

    // Whether the notehead [id] is above [fraction] of its page's height (a hidden note counts as at the top).
    fun above(geo: PageGeometry, id: String, fraction: Float) =
        pageHeight == null || (geo.head(id)?.y ?: 0f) < pageHeight(geo) * fraction

    // The notes on screen, for ▶ (it starts where the eyes are) and for the follow below. A turned
    // half page shows the top of the next page over the bottom of this one.
    fun notesOnScreen(notes: Collection<String>? = null): Set<String> {
        val pages = pageSource as? MusicXmlPages ?: return emptySet()
        fun onPage(index: Int, keep: (PageGeometry, String) -> Boolean = { _, _ -> true }): List<String> {
            val geo = pages.geometry(index) ?: return emptyList()
            return (notes ?: geo.noteIds).filter { it in geo.noteIds && keep(geo, it) }
        }
        return when {
            branch.showTwoUp -> onPage(currentPage) + onPage(currentPage + 1)
            branch.showHalf && halfTurned -> {
                val split = halfSplit()
                onPage(currentPage + 1) { g, id -> above(g, id, split.upper) } +
                    onPage(currentPage) { g, id -> !above(g, id, split.lower) }
            }
            else -> onPage(currentPage)
        }.toSet()
    }

    val toFirstPage by playbackVm.toFirstPage.collectAsState()
    LaunchedEffect(toFirstPage) {
        if (toFirstPage == 0) return@LaunchedEffect
        currentPage = 0
        halfTurned = false
    }

    // Follow the music of a MusicXML score: [notes] are heard, [ahead] are where the reader looks
    // (the bars to turn ahead). The pages turn as PageFlow.follow says. A page turned by hand
    // during playback lets the reader look ahead: the follow waits until the music is on screen
    // again (as in the MusicXML prototype).
    suspend fun followMusic(notes: List<String>, ahead: List<String>, byHand: Boolean = true) {
        val pages = pageSource as? MusicXmlPages ?: return
        if (notes.isEmpty()) return
        val onScreen = notesOnScreen(notes).isNotEmpty()
        if (byHand && onScreen) playbackVm.musicOnScreen()
        if (byHand && playbackVm.followPaused) return
        val target = pages.pageOf(notes.first())
        if (target < 0) return
        val looked = ahead.ifEmpty { notes }
        val aheadPage = pages.pageOf(looked.first()).takeIf { it >= 0 } ?: target
        // below the top of the next page that a turned half page shows
        val upper = halfSplit().upper
        val aheadInLowerPart = pages.geometry(aheadPage)?.let { geo ->
            val heads = looked.filter { geo.head(it) != null }
            heads.isNotEmpty() && heads.none { above(geo, it, upper) }
        } ?: false
        val f = branch.follow(target, aheadInLowerPart, onScreen, aheadPage)
        currentPage = f.page
        halfTurned = f.halfTurned
    }

    LaunchedEffect(cursorNotes) { followMusic(cursorNotes, aheadNotes) }

    // a MusicXML score: the metronome keeps the tempo of playback and the score's time signature
    val scoreInfo = (pageSource as? MusicXmlPages)?.info
    LaunchedEffect(tempoFactor, scoreInfo) {
        if (scoreInfo != null) metronomeVm.followScore(bpmOf(tempoFactor, scoreInfo.tempo ?: DEFAULT_BPM), Meter.parse(scoreInfo.time))
    }

    // ▶ / ⏸ of the metronome: it counts the bars from the page on screen, the music stops
    fun startStopMetronome() {
        if (metronomeOn) {
            metronomeVm.stop()
            return
        }
        playbackVm.stop()
        if (format.isMusicXml) {
            editScope.launch {
                val music = playbackVm.music()
                metronomeMusic = music
                val onScreen = notesOnScreen()
                val from = music?.onsets?.firstOrNull { o -> o.notes.any { it in onScreen } }?.quarters ?: 0.0
                metronomeVm.start(music?.barAt(from) ?: 0)
            }
        } else {
            metronomeVm.start(BarPages(metronome.barCounts, pageCount).startBar(currentPage, branch.showHalf && halfTurned))
        }
    }

    // The pages turn with the metronome: a MusicXML score knows its bars, a PDF score has the
    // bars of each page counted by the user (BarPages). Only forward: a page turned back by hand
    // stays until the metronome gets past it. pageCount: a score laid out again changes the bars.
    LaunchedEffect(metronomeTick, pageCount) {
        val bar = metronomeVm.barHeard(metronomeTick) ?: return@LaunchedEffect
        if (!metronome.autoTurn) return@LaunchedEffect
        val ahead = bar + metronome.turnAhead
        if (format.isMusicXml) {
            val music = metronomeMusic ?: return@LaunchedEffect
            val notes = music.notesOfBar(bar) ?: return@LaunchedEffect
            followMusic(notes, music.notesOfBar(ahead) ?: notes, byHand = false)
            return@LaunchedEffect
        }
        val pages = BarPages(metronome.barCounts, pageCount)
        if (!pages.known) return@LaunchedEffect
        val target = pages.pageOf(bar)
        val aheadPage = pages.pageOf(ahead)
        val onScreen = target == currentPage || (spread && target == currentPage + 1)
        val f = branch.follow(target, pages.inLowerHalf(ahead), onScreen, aheadPage)
        if (f.page > currentPage || (f.page == currentPage && f.halfTurned && !halfTurned)) {
            currentPage = f.page
            halfTurned = f.halfTurned
        }
    }

    // The paper's draws: the zoom step, the page on screen, the neighbours drawn ahead and the cut
    // of a half page (PageRender.kt)
    PageRenderEffects(
        paper = paper,
        doc = pageSource,
        page = currentPage,
        viewport = viewport,
        layoutVersion = layoutVersion,
        showTwoUp = branch.showTwoUp,
        showHalf = branch.showHalf,
        halfTurned = halfTurned,
        spread = spread,
        zoom = zoom,
    )
    // the document is freed off the main thread, after whatever draw is in flight
    DisposableEffect(pageSource) {
        val current = pageSource
        onDispose {
            current?.let { paper.close(it, (context.applicationContext as FOSScoreApp).applicationScope) }
        }
    }

    // Room the top chrome takes from the page while annotating, and nothing at all while reading:
    // the page is laid out next to the bars instead of under them (#19). While annotating there is
    // one bar only (the annotation bar, which carries the status bar inset in its own height).
    val chromeInset = with(LocalDensity.current) {
        (if (editing && canEdit) annBarPx else 0).toDp()
    }
    // With the metronome band, the bars stay on screen: a PDF page fits between the bar at the top
    // and the bottom chrome, which hid its first and last staves. (A MusicXML page is laid out
    // with that room already.)
    val bandShown = metronomeShown && !format.isMusicXml && !editing
    val bandInset = with(LocalDensity.current) { (if (bandShown) bottomChromePx else 0).toDp() }
    val bandTopInset = with(LocalDensity.current) { (if (bandShown) chromeTop else 0).toDp() }
    // a different page area is a different clamp: entering edit mode must not leave a zoomed page
    // parked outside its bounds
    LaunchedEffect(viewport) { zoom.set(zoomFrameNow(), zoom.transform) }

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
                .padding(top = chromeInset + bandTopInset, bottom = bandInset)
                .onSizeChanged { viewport = it }
        ) {
            if (error != null) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(stringResource(error), style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onBack) { Text(stringResource(R.string.action_back)) }
                }
            } else if (paper.bitmaps.isNotEmpty()) {
                // Reading modes (two-up, half page): the layer scales the whole viewport as before
                // (they are not meant for zooming into detail: offscreen drawing sweeps away the
                // stretch at no real cost in sharpness). Single page (else branch): NO scaling layer —
                // the bitmap is drawn at the current zoom and shown 1:1; the overlay is redrawn as
                // vector (see plan 2026-09-30-zoom-render-1to1).
                val spreadMod = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // read in the layout phase, not in composition: no recomposition per frame
                        val t = zoom.transform
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = t.scale
                        scaleY = t.scale
                        translationX = t.offset.x
                        translationY = t.offset.y
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                val gestureMod = Modifier.pageGestures(
                    editing = editing,
                    canEdit = canEdit,
                    page = currentPage,
                    sheetId = sheetId,
                    pageFit = pageFit,
                    stamps = pageStamps,
                    strokes = pageStrokes,
                    inkStrokes = inkStrokes,
                    selectionRect = selectionRect,
                    selectedStamp = selectedStamp,
                    selectedStrokes = selectedStrokes,
                    stampArmed = activeSymbol != null,
                    drawingTool = drawingTool,
                    eraserTool = eraserTool,
                    penWidthPx = penWidthPx,
                    eraserPx = eraserPx,
                    activeColor = activeColor,
                    loupeAt = loupeAt,
                    zoom = zoom,
                    zoomFrame = zoomFrameNow(),
                    editor = editor,
                    onCancelZoom = { zoomAnimJob?.cancel() },
                    onDoubleTapZoom = { target, anchor -> animateZoomTo(target, anchor) },
                    onTurn = { turn ->
                        if (turn == PageTurn.NEXT) currentApplyNext() else currentApplyPrev()
                    },
                    onTapZone = { p ->
                        val width = viewport.width
                        val corner = with(density) { MENU_CORNER.toPx() } +
                            bars.getLeft(density, LayoutDirection.Ltr)
                        when {
                            p.x < corner && p.y < chromeTop -> menu.show(ViewerSurface.MENU)
                            width > 0 && p.x > width * 2f / 3f -> currentApplyNext()
                            width > 0 && p.x < width / 3f -> currentApplyPrev()
                            else -> navVisible = !navVisible
                        }
                    },
                    onPageTouched = { pageTouched = it },
                    onLoupeAnchor = { loupeAnchor = it },
                    onLoupe = { loupeAt = it },
                    onInkPreview = { inkPreview = it },
                    // a touch on the matte has nowhere to write: the tools stand down and the
                    // selection goes, the same state three taps on the tool pill leave behind
                    onToolsDown = {
                        inkPreview = emptyList()
                        activeSymbol = null
                        drawingTool = false
                        eraserTool = false
                        openPanel = null
                        editor.clearSelection()
                    },
                    onPlaceStamp = { pos ->
                        val sym = activeSymbol
                        if (sym != null && sheetId != null) {
                            // One stamp per arming: stand the tool down as soon as it is used,
                            // so the next tap outside the new stamp deselects instead of
                            // stamping again.
                            activeSymbol = null
                            openPanel = null
                            // the whole square lands on the page, never half of it off the edge
                            val size = stampSizePxToFrac(placePx, hitFit)
                            val centre = stampCenterInside(pos.x, pos.y, size, hitFit)
                            editor.placeStamp(
                                currentPage,
                                Stamp(
                                    sheetId = sheetId,
                                    page = currentPage,
                                    symbol = sym.name,
                                    x = centre.x,
                                    y = centre.y,
                                    size = size,
                                    color = activeColor
                                ),
                                PageEdit(pageStamps, pageStrokes),
                            )
                        }
                    },
                )

                PageCanvas(
                    bitmaps = paper.bitmaps,
                    currentPage = currentPage,
                    pageCount = pageCount,
                    lastShown = paper.lastShown,
                    branch = branch,
                    halfTurned = halfTurned,
                    halfSplit = { halfSplit() },
                    above = { geo, id, fraction -> above(geo, id, fraction) },
                    pageFit = pageFit,
                    hitFit = hitFit,
                    scale = { zoom.transform.scale },
                    offsetX = { zoom.transform.offset.x },
                    offsetY = { zoom.transform.offset.y },
                    invert = invert,
                    pageSource = pageSource,
                    cursorNotes = cursorNotes,
                    halfCut = paper.halfCut,
                    layoutVersion = layoutVersion,
                    canEdit = canEdit,
                    inkDraws = inkDraws,
                    dragStrokes = editor.dragStrokes,
                    inkPreview = inkPreview,
                    eraserTool = eraserTool,
                    activeColor = activeColor,
                    eraserPx = eraserPx,
                    penWidthPx = penWidthPx,
                    pageStamps = pageStamps,
                    dragStamp = editor.dragStamp,
                    selectionRect = selectionRect,
                    strokeSelection = strokeSelection,
                    showSelection = showSelection,
                    loupeAt = loupeAt,
                    placePx = placePx,
                    nextInkDraws = companionInkDraws,
                    nextStamps = companionStamps,
                    // in the reading modes the whole viewport is scaled; a single page scales its
                    // own content, so the zoom layer is not used
                    modifier = if (spread) spreadMod.then(gestureMod) else gestureMod,
                )
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

        // the visual metronome: the border of the page flashes on the beats
        if (metronomeOn && metronome.mode != MetronomeMode.SOUND) BeatFlash(metronomeTick)

        // the loupe goes last: with a finger aiming a stamp the bars step aside, so it has the whole
        // page to read (it is drawn under them: they would cover the circle at the top).
        if ((navVisible || editing) && loupeAt == null) {

            // while annotating there is ONE bar: the annotation bar takes the top of the screen
            // and the title bar steps aside (spec §5: forScore replaces it). ✕ / ✓ are its ends.
            if (!(editing && canEdit)) {
                // ponytail: no ⌄ (score actions), no ⌕ (global search), no long-press-to-annotate yet.
                ViewerTitleBar(
                    title = title,
                    composer = composer,
                    pageLabel = pageLabel,
                    editing = editing,
                    onOpenMenu = { menu.show(ViewerSurface.MENU) },
                    onToggleEditing = { toggleEditing() },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        // edge-to-edge: the pills were slipping under the status bar, and in landscape
                        // under the side navigation bar.
                        .windowInsetsPadding(
                            WindowInsets.systemBars.only(
                                WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                            )
                        ),
                )
            }

            if (editing && canEdit) {
                AnnotationBar(
                    activeSymbol = activeSymbol,
                    lastSymbol = lastSymbol,
                    selectTool = activeSymbol == null && !drawingTool && !eraserTool,
                    openPanel = openPanel,
                    drawingTool = drawingTool,
                    eraserTool = eraserTool,
                    invert = invert,
                    activeColor = activeColor,
                    penWidthPx = penWidthPx,
                    eraserPx = eraserPx,
                    placePx = placePx,
                    defaultStampPx = defaultStampPx,
                    pageTouched = pageTouched,
                    canUndo = editor.canUndo,
                    canRedo = editor.canRedo,
                    canDelete = selectedStamp != null || selectedStrokes.isNotEmpty(),
                    onSelect = onSelect,
                    onToolTap = onToolTap,
                    onUndo = onUndo,
                    onRedo = onRedo,
                    onDelete = onDeleteSelection,
                    onCancel = onCancelEditing,
                    onDone = { toggleEditing() },
                    // dragging the grip moves the bar over the page; the page keeps its layout
                    onDragBar = { dy ->
                        barDragY = (barDragY + dy).coerceIn(0f, viewport.height * 0.6f)
                    },
                    onDragState = { dragging -> barDragging = dragging },
                    onStampSymbol = { activeSymbol = it; lastSymbol = it },
                    onStampSize = { placePx = it },
                    onSaveDefault = {
                        Settings.setStampPx(context, placePx)
                        defaultStampPx = placePx
                    },
                    onPenWidth = { penWidthPx = it },
                    onEraserPx = { eraserPx = it },
                    onColor = { argb ->
                        activeColor = argb
                        // the tint also recolours the selected stamp: forScore's
                        // selection tool changes colour, it does not resize
                        val current = selectedStamp
                        if (current != null) {
                            editor.recolorStamp(
                                currentPage, current, argb, PageEdit(pageStamps, pageStrokes)
                            )
                        }
                    },
                    onHeight = { annBarPx = it },
                    // the top of the screen: while annotating this bar IS the title bar, and the
                    // grip moves it down out of the way, where it turns see-through so the page
                    // underneath stays readable
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset { IntOffset(0, barDragY.roundToInt()) }
                        .alpha(if (barDragging) 0.75f else 1f),
                )

                // The page tag (spec §5: the bar stays lean). It is a live region, so TalkBack
                // announces the new page as a status message (WCAG 4.1.3) and the tag is never
                // the only way to know where you are. Empty and transparent the rest of the time:
                // it changes in place instead of appearing, which is what screen readers hear.
                Surface(
                    color = Color.Black.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp)
                        .alpha(if (pageFlash.isEmpty()) 0f else 1f),
                ) {
                    Text(
                        pageFlash,
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }

            if (!editing) {
                BottomChrome(
                    metronomeVm = metronomeVm,
                    playbackVm = playbackVm,
                    metronome = metronome,
                    metronomeOn = metronomeOn,
                    metronomeTick = metronomeTick,
                    metronomeShown = metronomeShown,
                    pageSource = pageSource,
                    pageCount = pageCount,
                    currentPage = currentPage,
                    format = format,
                    playState = playState,
                    loop = loop,
                    tempoFactor = tempoFactor,
                    playbackFailed = playbackFailed,
                    onStartStop = { startStopMetronome() },
                    onJump = {
                        playbackVm.pageTurnedByHand()
                        currentPage = it
                    },
                    onShowSurface = { menu.show(it) },
                    onHeight = { bottomChromePx = it },
                    notesOnScreen = { notesOnScreen() },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }

        // the menu and its panels, over everything: one at a time (ViewerMenuViewModel), the panels
        // under the bar at the top while it shows
        val panelTop = with(density) { if (navVisible) (bars.getTop(density) + TITLE_BAR_ROOM.roundToPx()).toDp() else 0.dp }
        ViewerOverlays(
            surface = surface,
            menu = menu,
            format = format,
            onOpenLibrary = onOpenLibrary,
            editing = editing,
            onToggleEditing = { toggleEditing() },
            onShowChrome = { navVisible = true },
            metronomeVm = metronomeVm,
            playbackVm = playbackVm,
            metronome = metronome,
            pageSource = pageSource,
            pageCount = pageCount,
            currentPage = currentPage,
            onJumpPage = {
                currentPage = it.coerceIn(0, pageCount - 1)
                halfTurned = false
            },
            isLandscape = isLandscape,
            meterFromScore = meterFromScore,
            parts = parts,
            heardPicked = heardPicked,
            shownIds = shownIds,
            scoreLayout = scoreLayout,
            themeMode = themeMode,
            onToggleTheme = onToggleTheme,
            invert = invert,
            onSetInvert = viewer::setInvert,
            keepScreenOn = keepScreenOn,
            onSetKeepScreenOn = viewer::setKeepScreenOn,
            dimPct = dimPct,
            onSetDimPct = viewer::setDimPct,
            onCommitDim = viewer::commitDimPct,
            twoUp = twoUp,
            onSetTwoUp = viewer::setTwoUp,
            halfEnabled = halfEnabled,
            onSetHalf = {
                viewer.setHalfPage(it)
                halfTurned = false
            },
            tempoFactor = tempoFactor,
            panelTop = panelTop,
        )

        // ✕ with something drawn: say what would be lost before losing it (issue #50)
        if (confirmDiscard) {
            DiscardAnnotationDialog(
                onKeep = { confirmDiscard = false },
                onDiscard = {
                    confirmDiscard = false
                    editor.cancelSession()
                    toggleEditing()
                },
            )
        }
    }
}
