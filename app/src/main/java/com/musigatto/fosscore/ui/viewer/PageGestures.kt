package com.musigatto.fosscore.ui.viewer

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import com.musigatto.fosscore.library.Stamp
import com.musigatto.fosscore.library.Stroke
import kotlin.math.abs

// Zoom cap. Reference viewers do NOT scale the page with a transform: they redraw it at the
// current zoom, so the GPU never rescales the texture. Our layer does scale it, which is why we
// have to cap it: with MAX_RENDER_PIXELS = 4M and a page that fits in ~1.6M px, the most MuPDF
// can hand over without rescaling is ~2.5x. Past that, the GPU stretch (nearest neighbor) shows.
// Only raise it if you also raise the pixel budget in MuPdfDoc.
private const val MAX_ZOOM = 2.5f

// Touch noise with still fingers: ±1 px over ~400 px between fingers ≈ 0.25%. A 0.4% dead zone
// stops the zoom reacting to that noise (without it the content "breathes" and looks shaky).
private const val ZOOM_NOISE_RATIO = 0.004f

// state of the "stretch" gesture: which strokes (a whole word) scale from which corner
private data class Resizing(
    val strokes: List<Stroke>,
    val ptsPx: List<List<Offset>>,
    val center: Offset,   // center of the box: the scale grows outwards from here
    val startPos: Offset
)

private fun centroid(changes: List<PointerInputChange>): Offset =
    changes.fold(Offset.Zero) { acc, c -> acc + c.position } / changes.size.toFloat()

/**
 * Everything a finger does on the page: tap zones, page turns, pinch and pan, and the whole
 * annotation gesture (select, move, stretch, draw, erase, place a stamp).
 *
 * It reads the page state it hit-tests against, and writes the zoom/pan it produces straight to
 * [zoom] (which clamps), plus the loupe, the ink trail and the tools standing down. Annotations
 * go to [editor], which is where they are written.
 */
@Composable
internal fun Modifier.pageGestures(
    editing: Boolean,
    canEdit: Boolean,
    page: Int,
    sheetHash: String?,
    pageFit: RectPx,
    stamps: List<Stamp>,
    strokes: List<Stroke>,
    inkStrokes: List<Stroke>,
    selectionRect: RectPx?,
    selectedStamp: Stamp?,
    selectedStrokes: List<Stroke>,
    stampArmed: Boolean,
    drawingTool: Boolean,
    eraserTool: Boolean,
    penWidthPx: Float,
    eraserPx: Float,
    activeColor: Int?,
    loupeAt: Offset?,
    zoom: ZoomState,
    zoomFrame: ZoomFrame,
    editor: AnnotationViewModel,
    onCancelZoom: () -> Unit,
    onDoubleTapZoom: (Float, Offset) -> Unit,
    onTurn: (PageTurn) -> Unit,
    onTapZone: (Offset) -> Unit,
    onPageTouched: (Boolean) -> Unit,
    onLoupeAnchor: (Offset?) -> Unit,
    onLoupe: (Offset?) -> Unit,
    onInkPreview: (List<Offset>) -> Unit,
    onToolsDown: () -> Unit,
    onPlaceStamp: (Offset) -> Unit,
): Modifier {
    // The gesture's pointerInput(Unit) never restarts, so everything it reads from outside its own
    // loop goes through rememberUpdatedState: a captured value would keep the first composition's
    // copy (a captured page turn lambda stops advancing past the first page).
    val currentEditing by rememberUpdatedState(editing)
    val currentCanEdit by rememberUpdatedState(canEdit)
    val currentPageStamps by rememberUpdatedState(stamps)
    val currentPageStrokes by rememberUpdatedState(strokes)
    val currentInkStrokes by rememberUpdatedState(inkStrokes)
    // the page (and the score) the edit belongs to. Not a local copy: this one rests on the page the
    // score opened at, so ink written on page 6 was stored under page 8 and showed up there after
    // pressing Done.
    val currentPage by rememberUpdatedState(page)
    val currentHash by rememberUpdatedState(sheetHash)
    val currentPageFit by rememberUpdatedState(pageFit)
    // where the page is centred in the viewport, and the pure fit rect (origin 0,0):
    // content work and zoom anchoring live in these coordinates
    val currentBase by rememberUpdatedState(Offset(pageFit.left, pageFit.top))
    val currentHitFit by rememberUpdatedState(RectPx(0f, 0f, pageFit.width, pageFit.height))
    // the frame the zoom is clamped against (the reading modes scale the whole viewport, a
    // single page scales its content)
    val currentFrame by rememberUpdatedState(zoomFrame)
    val currentSelectionRect by rememberUpdatedState(selectionRect)
    val currentSelectedStamp by rememberUpdatedState(selectedStamp)
    val currentSelectedStrokes by rememberUpdatedState(selectedStrokes)
    val currentStampArmed by rememberUpdatedState(stampArmed)
    val currentLoupeAt by rememberUpdatedState(loupeAt)
    val currentDrawingTool by rememberUpdatedState(drawingTool)
    val currentEraserTool by rememberUpdatedState(eraserTool)
    val currentEraserPx by rememberUpdatedState(eraserPx)
    val currentPenWidth by rememberUpdatedState(penWidthPx)
    val currentActiveColor by rememberUpdatedState(activeColor)

    // touch noise, the double-tap window and the swipe that turns a page: they belong to the
    // gesture, so they are read here
    val touchSlopPx = LocalViewConfiguration.current.touchSlop
    val doubleTapSlopPx = with(LocalDensity.current) { 40.dp.toPx() }
    val swipeThresholdPx = with(LocalDensity.current) { 80.dp.toPx() }
    // double tap: fit <-> zoom. We keep the time and the point of the previous tap.
    var lastTapMs by remember { mutableLongStateOf(0L) }
    var lastTapPos by remember { mutableStateOf(Offset.Zero) }

    return this.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (down.isConsumed) return@awaitEachGesture
            onPageTouched(true)   // the stamps palette steps aside while you touch the page
            onCancelZoom()    // any new touch cancels the double-tap animation
            // screen -> fit coordinates (0..fitW x 0..fitH): the content no longer
            // lives in a scaled layer, so the gesture translates the finger point here.
            // zoom.transform is read live: whatever resets it mid-touch (a turn, a mode
            // change) is already in effect on the next event, instead of being overwritten
            // by a copy this touch took at down.
            fun fitPoint(p: Offset): Offset {
                val t = zoom.transform
                val b = currentBase
                return Offset(
                    (p.x - b.x - t.offset.x) / t.scale,
                    (p.y - b.y - t.offset.y) / t.scale
                )
            }
            // the page is the canvas: a stroke that runs onto the matte stops at the
            // edge instead of being stored (and drawn) outside the sheet
            fun pagePoint(p: Offset): Offset {
                val f = fitPoint(p)
                return Offset(
                    f.x.coerceIn(0f, currentHitFit.width),
                    f.y.coerceIn(0f, currentHitFit.height)
                )
            }
            val downF = fitPoint(down.position)
            // the matte around the page sheet: a touch there is not a place to
            // write, so on release the tools stand down instead of stamping or
            // drawing at the page edge (see the tap branch below)
            val onMatte = downF.x < 0f || downF.x > currentHitFit.width ||
                downF.y < 0f || downF.y > currentHitFit.height
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
            // handle in use: (base object, grabbed corner) while scaling
            var resizing: Resizing? = null
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
                        editor.selectedId = hitS.id
                        editor.selectedStrokeIds = emptySet()
                        // Selecting a stamp must NOT arm its symbol, or the next tap
                        // outside it silently stamps a second copy.
                        editor.dragStamp = hitS
                    } else if (group != null) {
                        movingStrokes = group.strokes
                        movingStrokesBase = group.strokes
                        moveStrokeLastPos = downF
                        editor.selectedStrokeIds = group.strokes.map { it.id }.toSet()
                        editor.selectedId = null
                        // no preview yet: the box comes from the ids, which are
                        // already the whole word. The preview is armed on the first
                        // real movement (see the move branch).
                    }
                }
            }
            if (currentEditing && currentCanEdit && currentEraserTool && !onMatte) {
                eraserActive = true
                eraserPath.add(downF)
                onInkPreview(listOf(downF))
            }
            // a stroke that starts on the matte is no stroke at all: nothing is drawn, and the
            // release branch stands the pen down (it reads as a tap on the matte)
            if (currentEditing && currentCanEdit && currentDrawingTool && !onMatte) {
                inkActive = true
                inkPts.add(downF)
                onInkPreview(listOf(downF))
            }
            // stamp armed on an empty spot: a long press starts the loupe, so the finger
            // does not hide the exact place the stamp lands
            var lastPlacePos = down.position
            if (currentEditing && currentCanEdit && currentStampArmed &&
                movingStamp == null && movingStrokes == null
            ) {
                onLoupeAnchor(down.position)
            }

            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.isEmpty()) {
                        onPageTouched(false)
                        // did the loupe decide this placement? (cleared just below)
                        val aimed = currentLoupeAt != null
                        onLoupeAnchor(null)
                        onLoupe(null)
                        // local copy: a delegated var captured by this lambda gets no smart
                        // cast, so the null check needs a stable local
                        val rz = resizing
                        // a touch on the matte around the page has nowhere to write: the
                        // tool stands down and the selection goes, the same state three
                        // taps on the tool pill leave behind. A drag or a pinch there is
                        // a pan of the page, not a tap, and leaves the tools alone.
                        val matteTap = onMatte && !isTransform &&
                            totalPan.getDistance() <= touchSlopPx
                        if (matteTap && currentEditing && currentCanEdit) {
                            onToolsDown()
                        } else if (rz != null) {
                            val pts = rz.ptsPx.map { scalePoints(it, rz.center, scaleF) }
                            val changed = pts != rz.ptsPx
                            if (changed) {
                                editor.updateStrokes(
                                    currentPage,
                                    rz.strokes.mapIndexed { i, base ->
                                        base.copy(
                                            points = encodePoints(
                                                pts[i].map {
                                                    pxToNormalized(it, currentHitFit)
                                                }
                                            ),
                                            width = (base.width * scaleF)
                                                .coerceIn(MIN_STROKE_WIDTH, MAX_STROKE_WIDTH)
                                        )
                                    },
                                    PageEdit(currentPageStamps, currentPageStrokes),
                                )
                            }
                        } else if (inkActive) {
                            // ink capture lives in fit coordinates (F): the sampling
                            // threshold is a screen distance, so it scales with the zoom
                            val pts = samplePoints(inkPts, MIN_SAMPLE_DIST_PX / zoom.transform.scale)
                            onInkPreview(emptyList())
                            // local copy: a delegated var cannot be smart cast to String
                            val hash = currentHash
                            if (pts.size >= 2 && !onMatte && hash != null && currentPageFit.width > 0f) {
                                val s = Stroke(
                                    sheetHash = hash,
                                    page = currentPage,
                                    points = encodePoints(
                                        pts.map { pxToNormalized(it, currentHitFit) }
                                    ),
                                    width = penWidthPxToFrac(currentPenWidth, currentHitFit),
                                    color = currentActiveColor
                                )
                                editor.addInk(currentPage, s, PageEdit(currentPageStamps, currentPageStrokes))
                            }
                        } else if (eraserActive) {
                            val path = eraserPath
                            onInkPreview(emptyList())
                            if (path.isNotEmpty() && currentHash != null && currentPageFit.width > 0f) {
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
                                editor.erase(
                                    currentPage,
                                    goneStamps,
                                    goneStrokes,
                                    newRuns,
                                    PageEdit(currentPageStamps, currentPageStrokes),
                                )
                            }
                        } else if (movingStrokes != null) {
                            val done = movingStrokes
                            val base = movingStrokesBase
                            val moved = base != null &&
                                done.map { it.points } != base.map { it.points }
                            if (moved) {
                                // keep the final position until the DB confirms it, or
                                // the overlay would draw the old one for a few ms
                                editor.dragStrokes = done
                                editor.updateStrokes(
                                    currentPage, done, PageEdit(currentPageStamps, currentPageStrokes)
                                )
                            }
                        } else if (movingStamp != null) {
                            val done = movingStamp
                            val origin = movingOrigin
                            // keep the final position: clearing it here would make the
                            // overlay draw the DB's old position for a few ms until the flow
                            // emits again (a glitch).
                            editor.dragStamp = done
                            editor.updateStamp(
                                currentPage, done, origin, PageEdit(currentPageStamps, currentPageStrokes)
                            )
                        } else if (!isTransform) {
                            // double tap: toggles fit <-> 2.5x on the point touched. It is the
                            // shortcut back to the whole page (and into a detail) without
                            // fighting with the buttons on the bar.
                            val nowMs = System.currentTimeMillis()
                            val isDoubleTap = nowMs - lastTapMs < 320L &&
                                (down.position - lastTapPos).getDistance() < doubleTapSlopPx
                            if (isDoubleTap && !currentEditing) {
                                if (zoom.transform.scale > 1.05f) {
                                    onDoubleTapZoom(1f, down.position)
                                } else {
                                    onDoubleTapZoom(DOUBLE_TAP_ZOOM, down.position)
                                }
                                lastTapMs = 0L
                            } else {
                                lastTapMs = nowMs
                                lastTapPos = down.position
                            }
                            if (isDoubleTap) {
                                // already handled above: neither places a stamp nor turns the page
                            } else if (currentEditing && currentCanEdit) {
                                // Select mode: a horizontal swipe turns the page, the same one
                                // that works while reading. forScore turns from row 1 (‹ ›); this
                                // is our own addition, so it is only active with no tool armed and
                                // only at fit, where a one-finger drag is not a pan.
                                val isSwipe = !currentStampArmed &&
                                    zoom.transform.scale <= 1f &&
                                    totalPan.getDistance() > swipeThresholdPx &&
                                    abs(totalPan.x) > abs(totalPan.y)
                                // tap while editing: places the active stamp on the point touched
                                if (isSwipe) {
                                    if (totalPan.x < 0f) onTurn(PageTurn.NEXT) else onTurn(PageTurn.PREV)
                                } else if (!currentStampArmed) {
                                    // no tool: just deselect
                                    editor.clearSelection()
                                } else if (currentHash != null &&
                                    (aimed || totalPan.getDistance() <= touchSlopPx)
                                ) {
                                    // a drag is a pan and must not drop a stamp; the loupe is
                                    // the way to aim a placement that is not a plain tap. One
                                    // stamp per arming: the viewer stands the tool down as soon
                                    // as it is used, so the next tap outside the new stamp
                                    // deselects instead of stamping again.
                                    onPlaceStamp(pxToNormalized(fitPoint(lastPlacePos), currentHitFit))
                                }
                            } else {
                                val isSwipe = zoom.transform.scale <= 1f &&
                                    totalPan.getDistance() > swipeThresholdPx &&
                                    abs(totalPan.x) > abs(totalPan.y)
                                if (isSwipe) {
                                    if (totalPan.x < 0f) onTurn(PageTurn.NEXT) else onTurn(PageTurn.PREV)
                                } else {
                                    // tap zones: the top-left corner opens the menu, left third prev,
                                    // right third next, center toggles the bars
                                    onTapZone(down.position)
                                }
                            }
                        }
                        break
                    }
    
                    if (currentLoupeAt != null) {
                        if (pressed.size > 1) {
                            // a second finger is a pinch, not an aimed placement
                            onLoupeAnchor(null)
                            onLoupe(null)
                        } else {
                            // the loupe owns the finger: it follows it and the page must not pan
                            val tracking = pressed.firstOrNull { it.id == down.id } ?: pressed.first()
                            lastPlacePos = tracking.position
                            onLoupe(tracking.position)
                            event.changes.forEach { change -> if (change.positionChanged()) change.consume() }
                            prevCentroid = tracking.position
                            continue
                        }
                    }
    
                    resizing?.let { rz ->
                        // stretching the selected word by a handle: one pointer, scaling
                        // around the box center. the factor is worked out from the
                        // starting position, never accumulated
                        val tracking = pressed.firstOrNull { it.id == down.id } ?: pressed.first()
                        val f0 = scaleFactor(rz.center, rz.startPos, fitPoint(tracking.position))
                        // growing stops when the far corner reaches the sheet: a stretch near the
                        // border must not push the word (or hide part of it) outside the page
                        var fMax = Float.MAX_VALUE
                        rz.ptsPx.flatten().forEach { p ->
                            val dx = p.x - rz.center.x
                            val dy = p.y - rz.center.y
                            if (dx > 0f) fMax = minOf(fMax, (currentHitFit.width - rz.center.x) / dx)
                            else if (dx < 0f) fMax = minOf(fMax, rz.center.x / -dx)
                            if (dy > 0f) fMax = minOf(fMax, (currentHitFit.height - rz.center.y) / dy)
                            else if (dy < 0f) fMax = minOf(fMax, rz.center.y / -dy)
                        }
                        val f = f0.coerceAtMost(fMax).coerceAtLeast(0f)
                        scaleF = f
                        editor.dragStrokes = rz.strokes.mapIndexed { i, st ->
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
                        // the word moves as a block: the delta is limited to the box it occupies,
                        // so the far edge stops at the sheet instead of every point being pinned
                        // to it (that flattened the word into a line against the border)
                        val d = deltaInside(movingStrokes.flatMap { decodePoints(it.points) }, m)
                        val ns = movingStrokes.map { st ->
                            st.copy(
                                points = encodePoints(decodePoints(st.points).map { it + d })
                            )
                        }
                        movingStrokes = ns
                        editor.dragStrokes = ns
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
                        // the whole stamp stays on the page, not just its centre
                        val c = stampCenterInside(
                            movingStamp.x + m.x, movingStamp.y + m.y, movingStamp.size, currentHitFit
                        )
                        val ns = movingStamp.copy(x = c.x, y = c.y)
                        movingStamp = ns
                        editor.dragStamp = ns
                        prevCentroid = centroid(pressed)
                        event.changes.forEach { change -> if (change.positionChanged()) change.consume() }
                        continue
                    }
    
                    if (inkActive) {
                        if (pressed.size > 1) {
                            // a second finger arrived: drop the ink and let the pinch zoom
                            inkActive = false
                            inkPts = mutableListOf()
                            onInkPreview(emptyList())
                        } else {
                            val tracking = pressed.firstOrNull { it.id == down.id } ?: pressed.first()
                            val last = inkPts.last()
                            val p = pagePoint(tracking.position)
                            // points captured in fit coordinates: the screen threshold scales
                            // with the zoom
                            if ((p - last).getDistance() >= MIN_SAMPLE_DIST_PX / zoom.transform.scale) {
                                inkPts.add(p)
                                onInkPreview(inkPts.toList())
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
                            onInkPreview(emptyList())
                        } else {
                            val tracking = pressed.firstOrNull { it.id == down.id } ?: pressed.first()
                            val last = eraserPath.last()
                            val p = pagePoint(tracking.position)
                            if ((p - last).getDistance() >= MIN_SAMPLE_DIST_PX / zoom.transform.scale) {
                                eraserPath.add(p)
                                onInkPreview(eraserPath.toList())
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
                    if (currentLoupeAt == null && totalPan.getDistance() > touchSlopPx) {
                        onLoupeAnchor(null)
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
                        // INCREMENTAL: how much the distance changed in THIS frame. The reference
                        // is always taken, even on a frame that does not zoom, or the next frame
                        // would be measured against a distance two events old.
                        val previous = anchorDist
                        if (d > 0f) anchorDist = d
                        val s = zoom.transform.scale
                        val target = ZoomMath.nextScale(s, previous, d, ZOOM_NOISE_RATIO, MAX_ZOOM)
                        if (target != s) zoom.pinch(currentFrame, target / s, pinchAnchor)
                    } else if (zoom.transform.scale > 1f && delta.getDistance() > 0f) {
                        // One finger and NO pinch in progress: pan. While there are 2+ fingers
                        // this branch does not run, so the two branches never fight over the
                        // offset.
                        isTransform = true
                        zoom.pan(currentFrame, delta)
                    }
                    prevCentroid = c
                    event.changes.forEach { change -> if (change.positionChanged()) change.consume() }
                }
            } finally {
                // a gesture a parent takes over (or that is cancelled) never reaches its
                // release branch: put the half state back, or the loupe, the ghost stroke and
                // the "page is touched" flag stay on screen until the next touch
                onPageTouched(false)
                onLoupeAnchor(null)
                onLoupe(null)
                onInkPreview(emptyList())
            }
        }
    }
}
