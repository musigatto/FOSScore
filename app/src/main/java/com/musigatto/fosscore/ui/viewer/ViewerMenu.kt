package com.musigatto.fosscore.ui.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.musigatto.fosscore.R
import com.musigatto.fosscore.musicxml.LyricsPlacement
import com.musigatto.fosscore.musicxml.Part

// a glyph, not an icon font: the rest of the viewer chrome does the same (♪, ⊞, ✎), and these
// all exist in the fonts of Android 6; none for the metronome, drawn as on its band button
private val ViewerMenuItem.glyph: String?
    get() = when (this) {
        ViewerMenuItem.LIBRARY -> "♪"
        ViewerMenuItem.SETLIST -> "≡"
        ViewerMenuItem.BOOKMARKS -> "⚑"
        ViewerMenuItem.DISPLAY -> "⊞"
        ViewerMenuItem.LAYOUT -> "♫"
        ViewerMenuItem.METRONOME -> null
        // a tuning fork
        ViewerMenuItem.TUNER -> "Ψ"
        // keys
        ViewerMenuItem.PIANO -> "▥"
        ViewerMenuItem.PLAYBACK -> "▶"
        ViewerMenuItem.LINKS -> "↪"
        ViewerMenuItem.ANNOTATE -> "✎"
        ViewerMenuItem.PAGES -> "▤"
        ViewerMenuItem.EXPORT -> "↓"
        ViewerMenuItem.SHARE -> "↗"
        ViewerMenuItem.SETTINGS -> "⚙"
    }

@Composable
private fun ViewerMenuItem.label(): String = stringResource(
    when (this) {
        ViewerMenuItem.LIBRARY -> R.string.library_title
        ViewerMenuItem.SETLIST -> R.string.menu_setlist
        ViewerMenuItem.BOOKMARKS -> R.string.menu_bookmarks
        ViewerMenuItem.DISPLAY -> R.string.menu_display
        ViewerMenuItem.LAYOUT -> R.string.menu_layout
        ViewerMenuItem.METRONOME -> R.string.menu_metronome
        ViewerMenuItem.TUNER -> R.string.menu_tuner
        ViewerMenuItem.PIANO -> R.string.menu_piano
        ViewerMenuItem.PLAYBACK -> R.string.menu_playback
        ViewerMenuItem.LINKS -> R.string.menu_links
        ViewerMenuItem.ANNOTATE -> R.string.menu_annotate
        ViewerMenuItem.PAGES -> R.string.menu_pages
        ViewerMenuItem.EXPORT -> R.string.menu_export
        ViewerMenuItem.SHARE -> R.string.menu_share
        ViewerMenuItem.SETTINGS -> R.string.menu_settings
    }
)

@Composable
private fun MenuItemState.hint(): String? = when (this) {
    MenuItemState.READY -> null
    MenuItemState.SOON -> stringResource(R.string.menu_hint_soon)
    MenuItemState.MUSICXML_ONLY -> stringResource(R.string.menu_hint_musicxml_only)
    MenuItemState.NOT_ON_MUSICXML -> stringResource(R.string.menu_hint_not_on_musicxml)
}

/**
 * The viewer menu: one vertical list under the ☰ button, every entry always in the same place.
 * What does not work yet is greyed out with the reason under its name, so the menu shows where the
 * app is going and never moves when an entry arrives. Closes on tap outside, with back, or when an
 * entry is chosen.
 */
@Composable
fun ViewerMenuPanel(
    isMusicXml: Boolean,
    onSelect: (ViewerMenuItem) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onDismiss)
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.25f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            shadowElevation = 8.dp,
            // drawn over the ☰ pill, where the finger already is, but clear of the system bars;
            // taps on it must not reach the scrim
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .widthIn(max = 320.dp)
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
            ) {
                var group = ViewerMenuItem.entries.first().group
                ViewerMenuItem.entries.forEach { item ->
                    if (item.group != group) {
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        group = item.group
                    }
                    val state = menuItemState(item, isMusicXml)
                    MenuRow(item.glyph, item.label(), state.hint(), state == MenuItemState.READY) {
                        onSelect(item)
                    }
                }
            }
        }
    }
}

/**
 * Glyph (or, without one, the metronome icon), name, and under the name why the entry is greyed
 * out. 48 dp high at least: a touch target.
 */
@Composable
private fun MenuRow(glyph: String?, label: String, hint: String?, enabled: Boolean, onClick: () -> Unit) {
    val content = if (enabled) MaterialTheme.colorScheme.onSurface
    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val glyphColor = if (enabled) MaterialTheme.colorScheme.primary else content
        if (glyph != null) {
            Text(
                glyph,
                style = MaterialTheme.typography.titleMedium,
                color = glyphColor,
                modifier = Modifier.width(32.dp),
            )
        } else {
            Box(Modifier.width(32.dp)) {
                CompositionLocalProvider(LocalContentColor provides glyphColor) { ToolIcon(ICON_METRONOME) }
            }
        }
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = content)
            if (hint != null) {
                Text(hint, style = MaterialTheme.typography.bodySmall, color = content)
            }
        }
    }
}

/**
 * The MusicXML layout panel: what changes how the music is laid out on the page, saved per score.
 * Every change lays the music out again (about a second for a transposition on a slow tablet).
 * The parts come last, in their own scroll box: an orchestral score can have dozens.
 */
@Composable
fun ScoreLayoutOverlay(
    layout: ScoreLayout,
    parts: List<Part>,
    onMusicSize: (Int) -> Unit,
    onTranspose: (Int) -> Unit,
    onLyrics: (LyricsPlacement) -> Unit,
    onKeepHiddenLyrics: (Boolean) -> Unit,
    onPartShown: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PanelOverlay(stringResource(R.string.menu_layout), onDismiss, modifier) {
        SectionLabel(stringResource(R.string.engraving_section_label))
        NumberFieldRow(
            label = stringResource(R.string.note_size_label),
            value = layout.musicSize,
            range = MIN_MUSIC_SIZE..MAX_MUSIC_SIZE,
            step = MUSIC_SIZE_STEP,
            lessLabel = stringResource(R.string.note_size_smaller),
            moreLabel = stringResource(R.string.note_size_larger),
            rangeText = stringResource(R.string.note_size_range, MIN_MUSIC_SIZE, MAX_MUSIC_SIZE),
            onValue = onMusicSize,
            suffix = "%",
        )

        SectionLabel(stringResource(R.string.transposition_section_label))
        StepperRow(
            label = stringResource(R.string.transpose_label),
            value = if (layout.semitones == 0) stringResource(R.string.transpose_none)
            else stringResource(R.string.transpose_value, layout.semitones),
            lessLabel = stringResource(R.string.transpose_down),
            moreLabel = stringResource(R.string.transpose_up),
            canLess = layout.semitones > -MAX_TRANSPOSE,
            canMore = layout.semitones < MAX_TRANSPOSE,
            onLess = { onTranspose(layout.semitones - 1) },
            onMore = { onTranspose(layout.semitones + 1) },
        )

        SectionLabel(stringResource(R.string.lyrics_section_label))
        LyricsPlacement.entries.forEach { placement ->
            OptionRow(placement.label(), layout.lyrics == placement, Role.RadioButton) { onLyrics(placement) }
        }
        OptionRow(stringResource(R.string.lyrics_of_hidden_parts), layout.keepHiddenLyrics, Role.Checkbox) {
            onKeepHiddenLyrics(!layout.keepHiddenLyrics)
        }

        // listed even when the score has one part, its box greyed out: the user sees what the
        // score is made of (a PDF has no parts)
        if (parts.isNotEmpty()) {
            SectionLabel(stringResource(R.string.parts_section_label))
            val shown = layout.partsShown(parts)
            Column(
                modifier = Modifier
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                parts.forEach { part ->
                    val isShown = part.id in shown
                    // the last part shown cannot be hidden: the page would be blank
                    OptionRow(
                        part.name.ifBlank { part.id },
                        isShown,
                        Role.Checkbox,
                        enabled = !isShown || shown.size > 1,
                    ) { onPartShown(part.id, !isShown) }
                }
            }
        }
    }
}

@Composable
private fun LyricsPlacement.label(): String = stringResource(
    when (this) {
        LyricsPlacement.FILE -> R.string.lyrics_file
        LyricsPlacement.ABOVE -> R.string.lyrics_above
        LyricsPlacement.BELOW -> R.string.lyrics_below
        LyricsPlacement.BOTTOM -> R.string.lyrics_bottom
    }
)
