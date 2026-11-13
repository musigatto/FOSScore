package com.musigatto.fosscore.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musigatto.fosscore.library.Credits
import com.musigatto.fosscore.library.LibraryRepository
import com.musigatto.fosscore.library.Sheet
import com.musigatto.fosscore.score.SheetFormat
import kotlinx.coroutines.launch

/**
 * A list as the app shows every list it can sort by name (the library, the setlists, later the
 * tags and the artists): sorted by [name], a narrow bar for each first letter, and on the right the
 * letters there are, one under the other at a fixed spacing, a tap jumping to the letter's bar.
 * [row] draws an item; a divider follows it. [key] is its stable id.
 */
@Composable
fun <T> IndexedList(
    items: List<T>,
    name: (T) -> String,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    row: @Composable (T) -> Unit,
) {
    val index = remember(items) { alphabetIndex(items) { name(it) } }
    val scope = rememberCoroutineScope()
    Row(modifier) {
        LazyColumn(Modifier.weight(1f).fillMaxHeight(), state = state) {
            items(index.rows, key = { r ->
                when (r) {
                    is IndexedRow.Letter -> "letter:" + r.letter
                    is IndexedRow.Item -> "item:" + key(r.item)
                }
            }) { r ->
                when (r) {
                    is IndexedRow.Letter -> LetterBar(r.letter)
                    is IndexedRow.Item -> {
                        row(r.item)
                        HorizontalDivider()
                    }
                }
            }
        }
        LetterIndex(index.letters) { letter ->
            index.rowOf(letter)?.let { at -> scope.launch { state.scrollToItem(at) } }
        }
    }
}

// the narrow bar over the items of one letter
@Composable
private fun LetterBar(letter: String) {
    Text(
        letter,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            // level with the titles of the rows under it
            .padding(horizontal = 16.dp, vertical = 2.dp),
    )
}

/**
 * The letters there are, from the top (level with the first letter's bar), one under the other at
 * a fixed spacing whatever their number or the orientation; it scrolls on its own when they do
 * not all fit.
 */
@Composable
private fun LetterIndex(letters: List<String>, onJump: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxHeight()
            .width(INDEX_WIDTH)
            .verticalScroll(rememberScrollState()),
    ) {
        letters.forEach { letter ->
            Box(
                Modifier
                    .height(INDEX_ROW)
                    .fillMaxWidth()
                    .clickable { onJump(letter) },
                contentAlignment = Alignment.Center,
            ) {
                Text(letter, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

// a letter of the index: high enough to tap without missing it, low enough to show many at once
private val INDEX_ROW = 32.dp
private val INDEX_WIDTH = 32.dp

/**
 * A score as every list shows it: its title with its kind (PDF, MusicXML), so two copies of a
 * piece can be told apart, and who it is by under it ("Composer · Performer") when known.
 */
@Composable
fun SheetText(sheet: Sheet, modifier: Modifier = Modifier, bold: Boolean = false) {
    val credits = LocalSheetCredits.current[sheet.id]?.line.orEmpty()
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                sheet.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (bold) FontWeight.Bold else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            FormatBadge(formatLabel(sheet.path))
        }
        if (credits.isNotBlank()) {
            Text(credits, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Who each score is by, by score id ([LibraryRepository.creditsBySheet]), provided once at the top
 * of the app: every list of scores shows it under the titles without asking for it.
 */
val LocalSheetCredits = compositionLocalOf<Map<Long, Credits>> { emptyMap() }

/**
 * The kind of score, as the copy the importer keeps says ("PDF", "MusicXML"). Later the same
 * place can show an attached MIDI or audio file, or the tags.
 */
internal fun formatLabel(path: String): String = if (SheetFormat.of(path).isMusicXml) "MusicXML" else "PDF"

@Composable
private fun FormatBadge(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp)
    )
}
