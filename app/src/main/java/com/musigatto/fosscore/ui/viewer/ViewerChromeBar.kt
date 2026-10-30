package com.musigatto.fosscore.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.musigatto.fosscore.R
import com.musigatto.fosscore.metronome.BarPages
import com.musigatto.fosscore.metronome.MetronomeSettings
import com.musigatto.fosscore.metronome.Tick
import com.musigatto.fosscore.score.MusicXmlPages
import com.musigatto.fosscore.score.PageSource
import com.musigatto.fosscore.score.SheetFormat
import kotlin.math.roundToInt

/**
 * The annotation bar: the title bar's own row while annotating (spec §5: forScore replaces it).
 * ✕ leaves at the leading edge, ✓ at the trailing one, and the tools scroll between them when
 * they do not fit. Each tool's options drop from the tool itself, not from the bar's edge.
 * It only draws and reports taps; the viewer decides what each tool does.
 */
@Composable
internal fun AnnotationBar(
    activeSymbol: StampSymbol?,
    lastSymbol: StampSymbol,
    selectTool: Boolean,
    openPanel: ToolPanel?,
    drawingTool: Boolean,
    eraserTool: Boolean,
    invert: Boolean,
    activeColor: Int?,
    penWidthPx: Float,
    eraserPx: Float,
    placePx: Float,
    defaultStampPx: Float,
    pageTouched: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    canDelete: Boolean,
    onSelect: () -> Unit,
    onToolTap: (ToolPanel) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    onDragBar: (Float) -> Unit,
    onDragState: (Boolean) -> Unit,
    onStampSymbol: (StampSymbol) -> Unit,
    onStampSize: (Float) -> Unit,
    onSaveDefault: () -> Unit,
    onPenWidth: (Float) -> Unit,
    onEraserPx: (Float) -> Unit,
    onColor: (Int?) -> Unit,
    onHeight: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // where each tool sits on screen, so its panel hangs from the tool and not from the bar's edge
    val toolX = remember { mutableStateMapOf<ToolPanel, Float>() }
    var slotX by remember { mutableFloatStateOf(0f) }
    var slotWidth by remember { mutableIntStateOf(0) }
    var panelWidth by remember { mutableIntStateOf(0) }
    // the strip the bar paints over the status bar, mirrored below it: without it the tools sit
    // low against the page and the bar looks top-heavy
    val statusBarDp = with(LocalDensity.current) { WindowInsets.systemBars.getTop(this).toDp() }
    Column(
        modifier = modifier
            .fillMaxWidth()
            // landscape: the same side inset the title bar takes, so the tools do not run
            // under the navigation bar
            .windowInsetsPadding(
                WindowInsets.systemBars.only(WindowInsetsSides.Horizontal)
            )
    ) {
        // the tools are drawn glyphs now, with no text of their own: the names come from here
        val selectLabel = stringResource(R.string.tool_select)
        val penLabel = stringResource(R.string.tool_pen)
        val eraserLabel = stringResource(R.string.tool_eraser)
        val undoLabel = stringResource(R.string.action_undo)
        val redoLabel = stringResource(R.string.action_redo)
        val deleteLabel = stringResource(R.string.action_delete)
        val cancelLabel = stringResource(R.string.action_cancel)
        val doneLabel = stringResource(R.string.annotate_done)
        val moveLabel = stringResource(R.string.annotate_move_bar)
        // ✕ at the leading edge and ✓ at the trailing one (Apple HIG: Cancel leading, Done
        // trailing), outside the scroll so they never hide behind the tools. The tools keep the
        // rest of the width.
        //
        // This row is the top bar while annotating (the title bar steps aside), so it carries the
        // status bar inset, and it is measured with it: the page starts below the whole row.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { onHeight(it.height) }
                .background(MaterialTheme.colorScheme.surface)
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top))
                .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 4.dp + statusBarDp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // the grip: the bar can be dragged down out of the way (spec §5, forScore's ≡), and ✓
            // puts it back at the top. It only turns see-through while a finger is on it.
            Box(
                modifier = Modifier
                    // 48dp is the minimum touch target; the glyph stays small inside it
                    .size(48.dp)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { onDragState(true) },
                            onDragEnd = { onDragState(false) },
                            onDragCancel = { onDragState(false) },
                        ) { change, drag ->
                            change.consume()
                            onDragBar(drag.y)
                        }
                    }
                    .semantics { contentDescription = moveLabel },
                contentAlignment = Alignment.Center,
            ) { GripIcon() }
            // Cancel reads as a button, not as a ghost: it is one of the two ways out
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.semantics { contentDescription = cancelLabel },
            ) { ToolIcon(ICON_CLOSE) }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Select (issue #50): the neutral mode made visible. It is lit when no tool is
                // armed, and a tap on it puts any tool down.
                Button(
                    onClick = onSelect,
                    colors = if (selectTool) ButtonDefaults.buttonColors()
                    else ButtonDefaults.outlinedButtonColors(),
                    modifier = Modifier.semantics { contentDescription = selectLabel },
                ) { ToolIcon(ICON_SELECT) }
                Spacer(Modifier.width(8.dp))
                // stamps tool (spec §5): shows the last stamp used. Tap once to select it,
                // again to open its panel (symbols + size). It never touches the selection.
                Box(Modifier.onGloballyPositioned { toolX[ToolPanel.Stamps] = it.positionInWindow().x }) {
                    StampPaletteButton(
                        symbol = activeSymbol ?: lastSymbol,
                        selected = activeSymbol != null,
                        onClick = { onToolTap(ToolPanel.Stamps) }
                    )
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onToolTap(ToolPanel.Pen) },
                    colors = if (drawingTool) ButtonDefaults.buttonColors()
                    else ButtonDefaults.outlinedButtonColors(),
                    modifier = Modifier
                        .onGloballyPositioned { toolX[ToolPanel.Pen] = it.positionInWindow().x }
                        .semantics { contentDescription = penLabel },
                ) { ToolIcon(ICON_PENCIL) }
                Spacer(Modifier.width(4.dp))
                Button(
                    onClick = { onToolTap(ToolPanel.Eraser) },
                    colors = if (eraserTool) ButtonDefaults.buttonColors()
                    else ButtonDefaults.outlinedButtonColors(),
                    modifier = Modifier
                        .onGloballyPositioned { toolX[ToolPanel.Eraser] = it.positionInWindow().x }
                        .semantics { contentDescription = eraserLabel },
                ) { EraserIcon() }
                Spacer(Modifier.width(6.dp))
                // the tint for ink and stamps, in its own little panel (it used to be a whole
                // row of swatches eating the width of the bar)
                Box(Modifier.onGloballyPositioned { toolX[ToolPanel.Color] = it.positionInWindow().x }) {
                    ColorSwatchButton(
                        // null is the default colour: draw the ink it really gives
                        color = inkTint(activeColor, invert),
                        selected = openPanel == ToolPanel.Color,
                        onClick = { onToolTap(ToolPanel.Color) }
                    )
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onUndo,
                    enabled = canUndo,
                    modifier = Modifier.semantics { contentDescription = undoLabel },
                ) { ToolIcon(ICON_UNDO) }
                Spacer(Modifier.width(4.dp))
                Button(
                    onClick = onRedo,
                    enabled = canRedo,
                    modifier = Modifier.semantics { contentDescription = redoLabel },
                ) { ToolIcon(ICON_REDO) }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onDelete,
                    enabled = canDelete,
                    modifier = Modifier.semantics { contentDescription = deleteLabel },
                ) { ToolIcon(ICON_TRASH) }
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onDone,
                modifier = Modifier.semantics { contentDescription = doneLabel },
            ) { ToolIcon(ICON_CHECK) }
        }
        // one tool panel at a time drops from the bar (spec §5). They all step aside
        // while a finger is on the page, like forScore's panels. The panel hangs from its own
        // tool and stays inside the bar's width (colour, at the right, would overflow).
        val panel = openPanel
        if (panel != null && !pageTouched) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { slotX = it.positionInWindow().x }
                    .onSizeChanged { slotWidth = it.width }
            ) {
                Box(
                    modifier = Modifier
                        .offset {
                            val raw = ((toolX[panel] ?: slotX) - slotX).roundToInt()
                            IntOffset(raw.coerceIn(0, (slotWidth - panelWidth).coerceAtLeast(0)), 0)
                        }
                        .onSizeChanged { panelWidth = it.width }
                ) {
                    when (panel) {
                        ToolPanel.Stamps -> StampPalettePanel(
                            active = activeSymbol ?: lastSymbol,
                            sizePx = placePx,
                            onSymbol = onStampSymbol,
                            onSize = onStampSize,
                            onSaveDefault = onSaveDefault,
                            isDefault = placePx.roundToInt() == defaultStampPx.roundToInt(),
                        )

                        ToolPanel.Pen -> ToolSizePanel(
                            label = stringResource(R.string.thickness_label),
                            value = penWidthPx,
                            range = MIN_PEN_PX..MAX_PEN_PX,
                            onValue = onPenWidth,
                        )

                        ToolPanel.Eraser -> ToolSizePanel(
                            label = stringResource(R.string.size_label),
                            value = eraserPx,
                            range = MIN_ERASER_PX..MAX_ERASER_PX,
                            onValue = onEraserPx,
                        )

                        ToolPanel.Color -> ColorPanel(
                            active = activeColor,
                            invert = invert,
                            onPick = onColor
                        )
                    }
                }
            }
        }
    }
}

/**
 * Asked before ✕ throws away what was drawn since the pen was picked up (issue #50): the undo
 * history is not enough to get it back, so the loss is named before it happens.
 */@Composable
internal fun DiscardAnnotationDialog(onKeep: () -> Unit, onDiscard: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text(stringResource(R.string.annotate_discard_title)) },
        text = { Text(stringResource(R.string.annotate_discard_message)) },
        confirmButton = {
            TextButton(onClick = onDiscard) { Text(stringResource(R.string.annotate_discard)) }
        },
        dismissButton = {
            TextButton(onClick = onKeep) { Text(stringResource(R.string.annotate_keep_editing)) }
        },
    )
}

/**
 * The bottom of the reading chrome (spec §2): the metronome or playback band, when it shows, and
 * the page selector. Nothing else.
 */
@Composable
internal fun BottomChrome(
    metronomeVm: MetronomeViewModel,
    playbackVm: PlaybackViewModel,
    metronome: MetronomeSettings,
    metronomeOn: Boolean,
    metronomeTick: Tick?,
    metronomeShown: Boolean,
    pageSource: PageSource?,
    pageCount: Int,
    currentPage: Int,
    format: SheetFormat,
    playState: PlayState,
    loop: Loop,
    tempoFactor: Float,
    playbackFailed: Boolean,
    onStartStop: () -> Unit,
    onJump: (Int) -> Unit,
    onShowSurface: (ViewerSurface) -> Unit,
    onHeight: (Int) -> Unit,
    notesOnScreen: () -> Set<String>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { onHeight(it.height) }
            .background(MaterialTheme.colorScheme.surface)
            // edge-to-edge: the knob was sitting in the navigation gesture area
            .navigationBarsPadding()
    ) {
        if (metronomeShown) {
            // the bar heard, counted from the start of the piece as the score numbers
            // its bars; a PDF score knows them once the bars of its pages are counted
            val barText = metronomeVm.barHeard(metronomeTick)
                ?.takeIf { format.isMusicXml || BarPages(metronome.barCounts, pageCount).known }
                ?.let { stringResource(R.string.metronome_bar, it + 1) }
            val writtenBpm = (pageSource as? MusicXmlPages)?.info?.tempo ?: DEFAULT_BPM
            MetronomeBand(
                running = metronomeOn,
                tick = metronomeTick,
                settings = metronome,
                barText = barText,
                onStartStop = onStartStop,
                // a MusicXML score has one tempo, the one of playback
                onBpm = { bpm ->
                    if (format.isMusicXml) playbackVm.setTempo(factorOf(bpm, writtenBpm)) else metronomeVm.setBpm(bpm)
                },
                onTap = {
                    metronomeVm.tap()?.let { bpm ->
                        if (format.isMusicXml) playbackVm.setTempo(factorOf(bpm, writtenBpm))
                    }
                },
                onSettings = { onShowSurface(ViewerSurface.METRONOME) },
                onClose = { metronomeVm.show(false) },
                // a MusicXML score: the same button switches back to the playback band
                onPlayback = if (format.isMusicXml) ({ metronomeVm.show(false) }) else null,
            )
        } else if (format.isMusicXml) {
            val writtenBpm = (pageSource as? MusicXmlPages)?.info?.tempo ?: DEFAULT_BPM
            PlaybackBand(
                state = playState,
                loop = loop,
                bpm = bpmOf(tempoFactor, writtenBpm),
                minBpm = bpmOf(MIN_TEMPO_FACTOR, writtenBpm),
                maxBpm = bpmOf(MAX_TEMPO_FACTOR, writtenBpm),
                onPlayPause = {
                    metronomeVm.stop()
                    playbackVm.playPause(notesOnScreen())
                },
                onRestart = playbackVm::restart,
                onLoop = playbackVm::toggleLoop,
                onA = playbackVm::tapA,
                onB = playbackVm::tapB,
                onTempo = { playbackVm.setTempo(factorOf(it, writtenBpm)) },
                onOptions = { onShowSurface(ViewerSurface.PLAYBACK) },
                onMetronome = {
                    playbackVm.stop()
                    metronomeVm.show(true)
                },
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
            onJump = onJump,
            // air under the knob: flush with the edge it sat inside the system
            // navigation gesture area and was hard to grab
            modifier = Modifier.padding(
                start = 24.dp,
                end = 24.dp,
                // under a band, the dots need no air above them
                top = if (format.isMusicXml || metronomeShown) 0.dp else 10.dp,
                bottom = 10.dp,
            ),
        )
    }
}
