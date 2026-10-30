package com.musigatto.fosscore.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musigatto.fosscore.R
import com.musigatto.fosscore.library.LibraryRepository
import com.musigatto.fosscore.library.Sheet
import com.musigatto.fosscore.score.SheetFormat
import kotlinx.coroutines.launch

// the right end of every row, kept for the check box of a selection, as wide and as high as a
// check box without a click of its own: the row is the same size with or without it
private val SELECT_BOX_ROOM = 24.dp
// the least height of a row's content, the check box with some room: a row with a title alone
// does not grow when the box shows
private val ROW_MIN_HEIGHT = 28.dp

/**
 * The library list. A tap on a row opens the score; a long press starts selecting rows, and the
 * header then acts on the selection (edit one, delete one or more) instead of importing.
 */
@Composable
fun LibraryScreen(
    repository: LibraryRepository,
    onOpen: (Sheet) -> Unit,
    onEdit: (Sheet) -> Unit,
    onImport: () -> Unit,
    /** ✕ is shown only when the library floats over a score, where it means "back to the page". */
    onClose: (() -> Unit)? = null,
    /** ☰ opens the side menu with what concerns the whole app (export / import of the library...). */
    onOpenMenu: () -> Unit = {}
) {
    val sheets by repository.sheets().collectAsState(initial = emptyList())
    var query by rememberSaveable { mutableStateOf("") }
    val visible = filterSheets(sheets, query)
    // ids of the rows ticked; empty = not selecting
    var selected by rememberSaveable { mutableStateOf(emptyList<Long>()) }
    val chosen = sheets.filter { it.id in selected }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var bulkEdit by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun toggle(sheet: Sheet) {
        selected = if (sheet.id in selected) selected - sheet.id else selected + sheet.id
    }

    if (chosen.isNotEmpty()) BackHandler { selected = emptyList() }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // the title takes the room the buttons leave, on one line: the buttons keep their
            // width and the header its height, so the list does not move when selecting starts
            if (chosen.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    val cancelLabel = stringResource(R.string.action_cancel)
                    TextButton(
                        onClick = { selected = emptyList() },
                        modifier = Modifier.semantics { contentDescription = cancelLabel }
                    ) { Text("✕", style = MaterialTheme.typography.headlineSmall) }
                    Text(
                        pluralStringResource(R.plurals.library_selected, chosen.size, chosen.size),
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        if (chosen.size == 1) {
                            selected = emptyList()
                            onEdit(chosen.single())
                        } else {
                            bulkEdit = true
                        }
                    }) { Text(stringResource(R.string.action_edit)) }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { confirmDelete = true }) { Text(stringResource(R.string.action_delete)) }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    val menuDescription = stringResource(R.string.menu_open)
                    TextButton(onClick = onOpenMenu, modifier = Modifier.semantics { contentDescription = menuDescription }) {
                        Text("☰", style = MaterialTheme.typography.headlineSmall)
                    }
                    Text(
                        stringResource(R.string.library_title),
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    onClose?.let { close ->
                        val closeLabel = stringResource(R.string.action_close)
                        TextButton(
                            onClick = close,
                            modifier = Modifier.semantics { contentDescription = closeLabel }
                        ) { Text("✕") }
                        Spacer(Modifier.width(8.dp))
                    }
                    Button(onClick = onImport) { Text(stringResource(R.string.action_import)) }
                }
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.search_label)) },
            // the keyboard waits for a tap: the field can get the focus by itself (after a pedal
            // or a keyboard key), and the keyboard would then hide the list
            keyboardOptions = KeyboardOptions(showKeyboardOnFocus = false),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        )

        if (sheets.isEmpty()) {
            Text(
                stringResource(R.string.empty_library),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyLarge
            )
        } else if (visible.isEmpty()) {
            Text(
                stringResource(R.string.no_results, query),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyLarge
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(visible, key = { it.id }) { sheet ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { if (chosen.isNotEmpty()) toggle(sheet) else onOpen(sheet) },
                                onLongClick = { toggle(sheet) }
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .heightIn(min = ROW_MIN_HEIGHT),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(sheet.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                                Spacer(Modifier.width(8.dp))
                                FormatBadge(formatLabel(sheet.path))
                            }
                            if (sheet.composer.isNotBlank()) {
                                Text(sheet.composer, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        // the check box shows in a room every row keeps, and a tap on the row ticks
                        // it: on a long press nothing moves, the row stays under the finger
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.size(SELECT_BOX_ROOM), contentAlignment = Alignment.Center) {
                            if (chosen.isNotEmpty()) {
                                Checkbox(checked = sheet.id in selected, onCheckedChange = null)
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    if (confirmDelete && chosen.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = {
                Text(
                    if (chosen.size == 1) stringResource(R.string.delete_sheet_title)
                    else stringResource(R.string.delete_sheets_title, chosen.size)
                )
            },
            text = {
                Text(
                    stringResource(if (chosen.size == 1) R.string.delete_sheet_text else R.string.delete_sheets_text)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    selected = emptyList()
                    scope.launch { chosen.forEach { repository.delete(it) } }
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    if (bulkEdit && chosen.isNotEmpty()) {
        BulkEditDialog(
            count = chosen.size,
            onDismiss = { bulkEdit = false },
            onApply = { composer, genre, musicalKey, tags ->
                bulkEdit = false
                selected = emptyList()
                scope.launch {
                    chosen.forEach { sheet ->
                        repository.save(
                            sheet.copy(
                                composer = composer.ifBlank { sheet.composer },
                                genre = genre.ifBlank { sheet.genre },
                                musicalKey = musicalKey.ifBlank { sheet.musicalKey },
                                tags = if (tags.isBlank()) sheet.tags else mergeTags(sheet.tags, tags)
                            )
                        )
                    }
                }
            }
        )
    }
}

/**
 * What several scores can share. The title stays out (it is each score's own), and an empty field
 * means "leave it as it is"; tags are added to the ones each score already has.
 */
@Composable
private fun BulkEditDialog(
    count: Int,
    onDismiss: () -> Unit,
    onApply: (composer: String, genre: String, musicalKey: String, tags: String) -> Unit
) {
    var composer by rememberSaveable { mutableStateOf("") }
    var genre by rememberSaveable { mutableStateOf("") }
    var musicalKey by rememberSaveable { mutableStateOf("") }
    var tags by rememberSaveable { mutableStateOf("") }
    var tagDraft by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bulk_edit_title, count)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(stringResource(R.string.bulk_edit_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = composer,
                    onValueChange = { composer = it },
                    label = { Text(stringResource(R.string.field_composer)) },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = genre,
                    onValueChange = { genre = it },
                    label = { Text(stringResource(R.string.field_genre)) },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = musicalKey,
                    onValueChange = { musicalKey = it },
                    label = { Text(stringResource(R.string.field_key)) },
                    modifier = Modifier.fillMaxWidth()
                )
                TagField(
                    tags = tags,
                    draft = tagDraft,
                    onTagsChange = { tags = it },
                    onDraftChange = { tagDraft = it },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(composer, genre, musicalKey, mergeTags(tags, tagDraft)) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

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
