package com.musigatto.fosscore.ui.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musigatto.fosscore.R
import kotlin.math.roundToInt

// M3's touch target (IconButton's box): the ⊞ slot is mirrored on the right so the centered title
// stays centered in the pill.
private val OPTIONS_SLOT = 48.dp

/**
 * Title bar of the viewer: three floating pills (left · center · right) drawn over the page. No state
 * of its own: everything comes in as a parameter and goes out as a lambda.
 *
 * The row deliberately has NO clickable full-width background: it would eat the page taps in the gaps
 * between pills, which are the navigation (spec §3).
 *
 * ponytail: forScore *replaces* this bar with the annotation one (spec §5); here they coexist, the bar
 * on top and PdfViewerScreen's annotation panel below. The 2-row bar is its own step.
 */
@Composable
fun ViewerTitleBar(
    title: String,
    composer: String,
    pageLabel: String,
    editing: Boolean,
    onOpenLibrary: () -> Unit,
    onToggleEdit: () -> Unit,
    onShowOptions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // rule 7 of the spec: the extra room depends on the width, not on the device. On a phone the
        // pills keep only their glyphs, or the centre zone is left with no room for the title (#19).
        val compact = maxWidth < 480.dp
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // left pill: "where am I". forScore stacks Scores · Bookmarks · Setlists here; today only
            // the library exists, so the pill has one entry and grows when the others do.
            Pill {
                PillEntry(
                    "♪",
                    stringResource(R.string.library_title),
                    compact = compact,
                    onClick = onOpenLibrary,
                )
            }
            Spacer(Modifier.width(8.dp))
            // center pill: "what am I reading". It takes the leftover width and the darker tone, so it
            // reads as the main zone.
            Pill(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // always drawn — disabled while annotating (page modes are frozen then) — so the title
                    // keeps its left inset and never runs under the pill's rounded corner
                    val optionsLabel = stringResource(R.string.display_options_title)
                    IconButton(
                        onClick = onShowOptions,
                        enabled = !editing,
                        // the accent goes in the component's slot, not on the Text: otherwise the disabled
                        // state (annotating) loses M3's greyed-out color
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary,
                        ),
                        modifier = Modifier
                            .size(OPTIONS_SLOT)
                            .semantics { contentDescription = optionsLabel },
                    ) {
                        Text("⊞", style = MaterialTheme.typography.titleLarge)
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            listOf(title, composer).filter(String::isNotBlank).joinToString(" · "),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (pageLabel.isNotEmpty()) {
                            Text(pageLabel, maxLines = 1, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    // the ⊞'s box, mirrored: without it the weighted title is centerd on what is LEFT of
                    // the glyph and reads off-center by half a slot. On a phone the slot is not worth
                    // the room it takes from the title, so only the wide layout mirrors it.
                    if (!compact) Spacer(Modifier.size(OPTIONS_SLOT))
                }
            }
            Spacer(Modifier.width(8.dp))
            // right pill: "make something happen". Editing tints the whole pill with the accent container:
            // that is the M3 "active" tone, and it reads from across the room.
            Pill(
                color = if (editing) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface,
            ) {
                PillEntry(
                    "✎",
                    stringResource(R.string.action_edit),
                    compact = compact,
                    selected = editing,
                    onClick = onToggleEdit,
                )
            }
        }
    }
}

/**
 * Display overlay, as a checklist: one row per option, the control in a fixed left slot, so the rows
 * never change width with their label. Closes on tap outside or with back.
 *
 * ponytail: it does not carry spec §7's modal ✕/✓ chrome — it is an overlay, not an inspector, and it
 * holds nothing that can be discarded. If it ever holds unapplied changes, that chrome becomes
 * mandatory.
 */
@Composable
fun DisplayOptionsOverlay(
    halfEnabled: Boolean,
    onSetHalf: (Boolean) -> Unit,
    twoUp: Boolean,
    onSetTwoUp: (Boolean) -> Unit,
    /** Two-up only means anything in landscape (`PageFlow.showTwoUp`); in portrait the row is not drawn. */
    twoUpVisible: Boolean,
    themeLabel: String,
    onToggleTheme: () -> Unit,
    invert: Boolean,
    onToggleInvert: () -> Unit,
    keepScreenOn: Boolean,
    onToggleKeepScreenOn: () -> Unit,
    dimPct: Float,
    onDimChange: (Float) -> Unit,
    onDimChangeFinished: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onDismiss)
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            // swallow taps on the panel: without this they reach the scrim behind and dismiss
            modifier = Modifier
                .widthIn(max = 420.dp)
                .padding(16.dp)
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // the page modes are exclusive, and what is ticked is what the viewer is showing:
                // half wins over two-up (PageFlow.showTwoUp requires !halfEnabled)
                val twoUpChecked = twoUpVisible && twoUp && !halfEnabled
                SectionLabel(stringResource(R.string.page_section_label))
                OptionRow(stringResource(R.string.half_page_label), halfEnabled, Role.RadioButton) {
                    onSetHalf(true)
                }
                OptionRow(stringResource(R.string.one_page_label), !halfEnabled && !twoUpChecked, Role.RadioButton) {
                    onSetHalf(false)
                    onSetTwoUp(false)
                }
                if (twoUpVisible) {
                    OptionRow(stringResource(R.string.two_pages_label), twoUpChecked, Role.RadioButton) {
                        onSetHalf(false)
                        onSetTwoUp(true)
                    }
                }
                SectionLabel(stringResource(R.string.screen_section_label))
                OptionRow(stringResource(R.string.neg_label), invert, Role.Checkbox, onToggleInvert)
                OptionRow(stringResource(R.string.keep_screen_on_label), keepScreenOn, Role.Checkbox, onToggleKeepScreenOn)
                ValueRow(stringResource(R.string.theme_row_label), themeLabel, onToggleTheme)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.dim_label), style = MaterialTheme.typography.bodyLarge)
                    Slider(
                        value = dimPct,
                        onValueChange = onDimChange,
                        onValueChangeFinished = onDimChangeFinished,
                        valueRange = 0f..80f,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp),
                    )
                    Text("${dimPct.toInt()}%", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

/** Group header of the checklist. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/**
 * One option row: the control sits in a fixed left slot, so a longer label only grows to the right
 * (and may wrap) — the row never changes width under the finger.
 */
@Composable
private fun OptionRow(label: String, checked: Boolean, role: Role, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = checked, role = role, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (role == Role.RadioButton) RadioButton(selected = checked, onClick = null)
        else Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Cycles on tap. The value sits on the right, so the row's width does not follow the label either. */
@Composable
private fun ValueRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text("$value ▸", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
    }
}

/** Rounded container for a stack of entries. [color] is the pill's own tone. */
@Composable
private fun Pill(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = color,
        shadowElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            content = content,
        )
    }
}

/**
 * Glyph + label entry. The glyph takes the accent, the label the plain content color, so the pill
 * reads as a Material component instead of a gray slab; [selected] switches both to the accent
 * container's content color. The label is what a screen reader announces.
 */
@Composable
private fun PillEntry(
    icon: String,
    label: String,
    compact: Boolean = false,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val glyphColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.primary
    val labelColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurface
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        // without its label the glyph has no name of its own: keep the label spoken
        modifier = if (compact) Modifier.semantics { contentDescription = label } else Modifier,
    ) {
        Text(icon, maxLines = 1, style = MaterialTheme.typography.labelLarge, color = glyphColor)
        if (!compact) {
            Spacer(Modifier.width(6.dp))
            Text(label, maxLines = 1, style = MaterialTheme.typography.labelLarge, color = labelColor)
        }
    }
}

/**
 * Tap cycle of a tool button (spec §5: tap a tool to select it, tap the selected one again for its
 * options): off -> on -> options -> off. Returns (tool on, panel open).
 */
fun nextToolTap(active: Boolean, panelOpen: Boolean): Pair<Boolean, Boolean> = when {
    !active -> true to false
    panelOpen -> false to false
    else -> true to true
}

/**
 * Compact block that drops from the annotation bar: the shape every tool panel shares (stamps, pen,
 * eraser, colour). It floats over the page instead of painting a band across it (spec §5), and the
 * fixed 160 dp keeps all of them the same width so the bar never reshuffles.
 */
@Composable
fun ToolBlock(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        // ponytail: fixed alpha; make it a setting if it hides the music you are working on
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .padding(start = 12.dp, bottom = 8.dp)
            .width(160.dp)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content
        )
    }
}

/** Tool panel with one labelled slider: pen thickness, eraser size. */
@Composable
fun ToolSizePanel(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValue: (Float) -> Unit,
) {
    ToolBlock {
        Text("$label · ${value.roundToInt()} px", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = value,
            onValueChange = onValue,
            valueRange = range,
            steps = (range.endInclusive - range.start).toInt() - 1,
        )
    }
}

/**
 * Colour selector in its own panel (spec §5): the tint for ink and stamps, and for the selected
 * stamp. Transparent = the theme colour.
 */
@Composable
fun ColorPanel(active: Int?, onPick: (Int?) -> Unit) {
    ToolBlock {
        (listOf<Int?>(null) + SWATCH_COLORS.map { it.toArgb() }).chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { argb ->
                    ColorSwatchButton(
                        color = if (argb == null) Color.Transparent else Color(argb),
                        selected = active == argb,
                        onClick = { onPick(argb) }
                    )
                }
            }
        }
    }
}
