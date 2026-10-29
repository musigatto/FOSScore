package com.musigatto.fosscore.ui.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.musigatto.fosscore.R
import com.musigatto.fosscore.musicxml.PageGeometry
import com.musigatto.fosscore.musicxml.Part
import com.musigatto.fosscore.musicxml.Playback
import kotlin.math.max

// Material's 24 dp paths (play_arrow, pause, skip_previous, repeat), drawn with ToolIcon
internal const val ICON_PLAY = "M8,5v14l11,-7z"
internal const val ICON_PAUSE = "M6,19h4V5H6v14zM14,5v14h4V5h-4z"
private const val ICON_TO_START = "M6,6h2v12H6zM9.5,12l8.5,6V6z"
// Material "tune": the playback options
internal const val ICON_OPTIONS = "M3,17v2h6v-2H3zM3,5v2h10V5H3zM13,21v-2h8v-2h-8v-2h-2v6h2zM7,9v2H3v2h4v2h2V9H7zM21,13v-2H11v2h10zM15,9h2V7h4V5h-4V3h-2v6z"
private const val ICON_LOOP =
    "M7,7h10v3l4,-4,-4,-4v3H5v6h2V7zM17,17H7v-3l-4,4,4,4v-3h12v-6h-2v4z"
// Material has no metronome: its case (the window cut out, wound the other way), the arm and its weight
internal const val ICON_METRONOME =
    "M9,2h6l5,20H4zM10.4,4L6.8,17h10.4L13.6,4z" +
        "M11.28,14.64L16.28,4.64L17.72,5.36L12.72,15.36zM13.3,8.4h3v2h-3z"

// the band's buttons: a little under M3's 48 dp, so the band stays one slim row
internal val BAND_BUTTON = 44.dp
// the band holds − ♩ = N + and the options button from this width (a tablet, a phone in landscape)
private val BAND_WITH_TEMPO_BUTTONS = 440.dp

// the playback bar: a line across the line of music being played, a little past its staves
private val BAR_COLOR = Color(0xCCE53935)
private const val BAR_WIDTH_SPACES = 0.4f
private const val BAR_OVERHANG_SPACES = 1.5f

/**
 * The playback band, in the bottom bar above the page dots: ⏮ (back to the start of the loop or
 * of the piece), ▶ / ⏸, the loop (on / off, the whole piece unless A or B is set), the A and B
 * markers (a tap sets one where the music is, a second tap clears it), the tempo with its − / +
 * (repeating while held), and the playback options. On a narrow screen − / + leave room, and a
 * tap on the tempo opens the options. It shows with the bars (centre tap); the music goes on when
 * they hide. The metronome button puts the metronome band in its place.
 */
@Composable
fun PlaybackBand(
    state: PlayState,
    loop: Loop,
    bpm: Int,
    minBpm: Int,
    maxBpm: Int,
    onPlayPause: () -> Unit,
    onRestart: () -> Unit,
    onLoop: () -> Unit,
    onA: () -> Unit,
    onB: () -> Unit,
    onTempo: (Int) -> Unit,
    onOptions: () -> Unit,
    onMetronome: () -> Unit,
    modifier: Modifier = Modifier,
) = BoxWithConstraints(modifier.fillMaxWidth()) {
    val wide = maxWidth >= BAND_WITH_TEMPO_BUTTONS
    // the held button reads the tempo it changes, not the one it saw when the press began
    val current by rememberUpdatedState(bpm)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BandButton(
            stringResource(if (loop.on) R.string.playback_to_loop_start else R.string.playback_to_start),
            active = false,
            onClick = onRestart,
        ) { ToolIcon(ICON_TO_START) }
        val playing = state == PlayState.PLAYING
        BandButton(
            stringResource(if (playing) R.string.playback_pause else R.string.playback_play),
            active = true,
            enabled = state != PlayState.PREPARING,
            onClick = onPlayPause,
        ) { ToolIcon(if (playing) ICON_PAUSE else ICON_PLAY) }
        Spacer(Modifier.width(8.dp))
        BandButton(
            stringResource(if (loop.on) R.string.loop_off else R.string.loop_on),
            active = loop.on,
            onClick = onLoop,
        ) { ToolIcon(ICON_LOOP) }
        BandButton(
            stringResource(if (loop.a != null) R.string.loop_clear_a else R.string.loop_set_a),
            active = loop.a != null,
            onClick = onA,
        ) { Text("A", style = MaterialTheme.typography.labelLarge) }
        BandButton(
            stringResource(if (loop.b != null) R.string.loop_clear_b else R.string.loop_set_b),
            active = loop.b != null,
            onClick = onB,
        ) { Text("B", style = MaterialTheme.typography.labelLarge) }
        Spacer(Modifier.width(8.dp))
        if (wide) {
            RepeatButton("−", stringResource(R.string.tempo_slower), enabled = bpm > minBpm, size = BAND_BUTTON) {
                onTempo((current - 1).coerceAtLeast(minBpm))
            }
            Text(stringResource(R.string.tempo_value, bpm), style = MaterialTheme.typography.labelLarge)
            RepeatButton("+", stringResource(R.string.tempo_faster), enabled = bpm < maxBpm, size = BAND_BUTTON) {
                onTempo((current + 1).coerceAtMost(maxBpm))
            }
            BandButton(stringResource(R.string.menu_playback), active = false, onClick = onOptions) { ToolIcon(ICON_OPTIONS) }
        } else {
            TextButton(onClick = onOptions) {
                Text(stringResource(R.string.tempo_value, bpm), style = MaterialTheme.typography.labelLarge)
            }
        }
        BandButton(stringResource(R.string.menu_metronome), active = false, onClick = onMetronome) { ToolIcon(ICON_METRONOME) }
    }
}

/** A band button: filled while what it stands for is on (the loop, a marker set, ▶). */
@Composable
internal fun BandButton(
    label: String,
    active: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val modifier = Modifier
        .size(BAND_BUTTON)
        .semantics { contentDescription = label }
    if (active) {
        FilledTonalIconButton(onClick = onClick, enabled = enabled, modifier = modifier) { content() }
    } else {
        IconButton(onClick = onClick, enabled = enabled, modifier = modifier) { content() }
    }
}

/**
 * The playback options panel (menu › Playback): the tempo (the same block as the metronome's),
 * the bars to turn the pages ahead of the music, and the parts heard, last and in their own scroll
 * box, as in the mockup. The parts shown are in Score layout.
 */
@Composable
fun PlaybackOptionsOverlay(
    bpm: Int,
    writtenBpm: Int,
    minBpm: Int,
    maxBpm: Int,
    onTempo: (Int) -> Unit,
    turnAhead: Int,
    onTurnAhead: (Int) -> Unit,
    parts: List<Part>,
    heard: Set<String>,
    onHeard: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PanelOverlay(stringResource(R.string.menu_playback), onDismiss, modifier) {
        SectionLabel(stringResource(R.string.tempo_section_label))
        TempoBlock(bpm, minBpm..maxBpm, writtenBpm, onTempo)
        SectionLabel(stringResource(R.string.metronome_page_turns))
        TurnAheadRow(turnAhead, onTurnAhead)
        // listed even when the score has one part (a PDF has no parts)
        if (parts.isNotEmpty()) {
            SectionLabel(stringResource(R.string.heard_section_label))
            Column(
                modifier = Modifier
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                parts.forEach { part ->
                    val isHeard = part.id in heard
                    // every part can be muted: the bar then follows the music without sound
                    OptionRow(part.name.ifBlank { part.id }, isHeard, Role.Checkbox) { onHeard(part.id, !isHeard) }
                }
            }
        }
    }
}

/**
 * Where the playback bar is on a page: [x], and the [top] and [bottom] lines of the line of music
 * it crosses, with its staff [space]. SVG units.
 */
class BarSpot(val x: Float, val top: Float, val bottom: Float, val space: Float, internal val lineEnd: Float = x)

/**
 * The way the playback bar goes over one page laid out as [geometry]: through the notes of each
 * onset of [playback] on the page, gliding from one to the next at the speed of the music, and
 * to the end of the line after the last notes of a line. An onset of hidden notes alone (the
 * line of lyrics kept for a part not shown) has nothing to point at: the bar glides past it.
 */
class BarPath(private val playback: Playback, private val geometry: PageGeometry) {
    /** The page's width in SVG units. */
    val width: Float get() = geometry.width

    private val onsets = playback.onsets
    // whether each onset is on this page (hidden notes included), and where its notes are drawn
    private val onPage = BooleanArray(onsets.size) { i -> onsets[i].notes.any { it in geometry.noteIds } }
    private val spots = Array(onsets.size) { i ->
        onsets[i].notes.mapNotNull { geometry.head(it) }.takeIf { it.isNotEmpty() }?.let { heads ->
            BarSpot(heads.minOf { it.x }, heads.first().top, heads.first().bottom, heads.first().space, heads.first().lineEnd)
        }
    }

    /** Where the bar is with the music at [quarters]; null when the music is not on this page. */
    fun at(quarters: Double): BarSpot? {
        var i = playback.onsetIndexAt(quarters)
        if (i < 0 || !onPage[i]) return null
        // back to the last notes drawn, past onsets of hidden notes
        while (i >= 0 && onPage[i] && spots[i] == null) i--
        if (i < 0 || !onPage[i]) return null
        val here = spots[i] ?: return null
        // on to the next notes drawn on this page, or to where the music leaves it
        var j = i + 1
        while (j < onsets.size && onPage[j] && spots[j] == null) j++
        val to = onsets.getOrNull(j)?.quarters ?: return here
        val there = if (onPage[j]) spots[j] else null
        val from = onsets[i].quarters
        // the next notes further on the same line, else the end of the line (a new line, a
        // repeat going back, another page)
        val toX = if (there != null && there.top == here.top && there.x >= here.x) there.x else max(here.x, here.lineEnd)
        val f = if (to > from) ((quarters - from) / (to - from)).toFloat().coerceIn(0f, 1f) else 0f
        return BarSpot(here.x + (toX - here.x) * f, here.top, here.bottom, here.space)
    }
}

/**
 * Draws the playback bar at [spot] on a page [width] SVG units wide, which covers [pageWidth] px
 * from ([left], [top]).
 */
fun DrawScope.drawPlaybackBar(spot: BarSpot, width: Float, left: Float, top: Float, pageWidth: Float) {
    val k = pageWidth / width
    val space = spot.space * k
    val x = left + spot.x * k
    val t = top + spot.top * k - BAR_OVERHANG_SPACES * space
    val b = top + spot.bottom * k + BAR_OVERHANG_SPACES * space
    val w = BAR_WIDTH_SPACES * space
    drawRect(BAR_COLOR, Offset(x - w / 2, t), Size(w, b - t))
}

/**
 * The playback bar over a bitmap drawn with ContentScale.Fit in a box of this canvas's size,
 * where [quarters] says the music is (read while drawing, so the bar moves without the screen
 * being composed again); [keep] leaves out the bar where the page is not shown.
 */
@Composable
fun FitPlaybackBar(
    path: BarPath,
    quarters: () -> Double?,
    bitmapWidth: Int,
    bitmapHeight: Int,
    modifier: Modifier,
    keep: (BarSpot) -> Boolean = { true },
) {
    Canvas(modifier) {
        val spot = quarters()?.let { path.at(it) }?.takeIf(keep) ?: return@Canvas
        val fit = minOf(size.width / bitmapWidth, size.height / bitmapHeight)
        val dx = (size.width - bitmapWidth * fit) / 2
        val dy = (size.height - bitmapHeight * fit) / 2
        drawPlaybackBar(spot, path.width, dx, dy, bitmapWidth * fit)
    }
}
