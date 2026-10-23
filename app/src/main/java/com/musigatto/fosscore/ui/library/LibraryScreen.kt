package com.musigatto.fosscore.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.musigatto.fosscore.R
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.musigatto.fosscore.library.LibraryRepository
import com.musigatto.fosscore.library.Sheet
import com.musigatto.fosscore.score.SheetFormat
import kotlinx.coroutines.launch

/**
 * The library list. A tap on a row opens the score; a long press starts selecting rows, and the
 * header then acts on the selection (edit one, delete one or more) instead of importing.
 */
@OptIn(ExperimentalFoundationApi::class)
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
            if (chosen.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val cancelLabel = stringResource(R.string.action_cancel)
                    TextButton(
                        onClick = { selected = emptyList() },
                        modifier = Modifier.semantics { contentDescription = cancelLabel }
                    ) { Text("✕", style = MaterialTheme.typography.headlineSmall) }
                    Text(
                        pluralStringResource(R.plurals.library_selected, chosen.size, chosen.size),
                        style = MaterialTheme.typography.headlineSmall
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // editing several scores at once comes later (roadmap 3.6)
                    if (chosen.size == 1) {
                        TextButton(onClick = {
                            selected = emptyList()
                            onEdit(chosen.single())
                        }) { Text(stringResource(R.string.action_edit)) }
                        Spacer(Modifier.width(8.dp))
                    }
                    Button(onClick = { confirmDelete = true }) { Text(stringResource(R.string.action_delete)) }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val menuDescription = stringResource(R.string.menu_open)
                    TextButton(onClick = onOpenMenu, modifier = Modifier.semantics { contentDescription = menuDescription }) {
                        Text("☰", style = MaterialTheme.typography.headlineSmall)
                    }
                    Text(stringResource(R.string.library_title), style = MaterialTheme.typography.headlineSmall)
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
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (chosen.isNotEmpty()) {
                            Checkbox(checked = sheet.id in selected, onCheckedChange = { toggle(sheet) })
                            Spacer(Modifier.width(8.dp))
                        }
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
                    val doomed = chosen
                    selected = emptyList()
                    scope.launch { doomed.forEach { repository.delete(it) } }
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
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
