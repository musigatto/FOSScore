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
import androidx.compose.runtime.remember
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
import com.musigatto.fosscore.ui.viewer.LINE_MENU
import com.musigatto.fosscore.ui.viewer.LINE_CLOSE
import com.musigatto.fosscore.ui.viewer.LineIcon
import com.musigatto.fosscore.library.LibraryRepository
import com.musigatto.fosscore.library.MusicalKey
import com.musigatto.fosscore.library.genreLabel
import com.musigatto.fosscore.library.PersonRole
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
    onOpenMenu: () -> Unit = {},
    /** The left of the header, ☰ and the name or the views' icons (MainNavigation); ☰ alone when none. */
    navigation: (@Composable () -> Unit)? = null,
) {
    val sheets by repository.sheets().collectAsState(initial = emptyList())
    val tagsFlow = remember(repository) { repository.tagsBySheet() }
    val tagsBySheet by tagsFlow.collectAsState(initial = emptyMap())
    val knownFlow = remember(repository) { repository.tags() }
    val knownTags by knownFlow.collectAsState(initial = emptyList())
    val knownPeople by remember(repository) { repository.people() }.collectAsState(initial = emptyList())
    val knownGenres by remember(repository) { repository.genres() }.collectAsState(initial = emptyList())
    val genresBySheet by remember(repository) { repository.genresBySheet() }.collectAsState(initial = emptyMap())
    val keysBySheet by remember(repository) { repository.keysBySheet() }.collectAsState(initial = emptyMap())
    val genreLabels = rememberGenreLabels()
    val keyLabels = rememberKeyLabels()
    val credits = LocalSheetCredits.current
    var query by rememberSaveable { mutableStateOf("") }
    // the search finds a score by its tags, genres and keys as shown (in the app's language)
    val words = remember(tagsBySheet, genresBySheet, keysBySheet, genreLabels, keyLabels) {
        wordsBySheet(tagsBySheet, genresBySheet.mapValues { (_, g) -> g.map { it.genreLabel(genreLabels) } },
            keysBySheet.mapValues { (_, k) -> k.mapNotNull { keyLabels[it] } })
    }
    val visible = filterSheets(sheets, query, words, credits)
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
                    ) { LineIcon(LINE_CLOSE, size = 26.dp) }
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
            } else if (navigation != null) {
                Box(Modifier.weight(1f)) { navigation() }
                LibraryActions(onClose, onImport)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    val menuDescription = stringResource(R.string.menu_open)
                    TextButton(onClick = onOpenMenu, modifier = Modifier.semantics { contentDescription = menuDescription }) {
                        LineIcon(LINE_MENU, size = 26.dp)
                    }
                    Text(
                        stringResource(R.string.library_title),
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                LibraryActions(onClose, onImport)
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
            // sorted by title under a bar for each letter, the letters on the right
            IndexedList(visible, name = { it.title }, key = { it.id }, modifier = Modifier.fillMaxSize()) { sheet ->
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
                        SheetText(sheet, Modifier.weight(1f))
                        // the check box shows in a room every row keeps, and a tap on the row ticks
                        // it: on a long press nothing moves, the row stays under the finger
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.size(SELECT_BOX_ROOM), contentAlignment = Alignment.Center) {
                            if (chosen.isNotEmpty()) {
                                Checkbox(checked = sheet.id in selected, onCheckedChange = null)
                            }
                        }
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
            knownTags = knownTags.map { it.name },
            knownPeople = knownPeople.map { it.name },
            knownGenres = knownGenres.map { it.genreLabel(genreLabels) },
            knownKeys = MusicalKey.entries.mapNotNull { keyLabels[it] },
            onDismiss = { bulkEdit = false },
            onApply = { edit ->
                bulkEdit = false
                selected = emptyList()
                scope.launch {
                    val ids = chosen.map { it.id }
                    if (edit.genres.isNotEmpty()) repository.addGenres(ids, edit.genres, genreLabels)
                    if (edit.keys.isNotEmpty()) repository.addKeys(ids, edit.keys.mapNotNull { keyOf(it, keyLabels) })
                    if (edit.composers.isNotEmpty()) repository.addPeople(ids, PersonRole.COMPOSER, edit.composers)
                    if (edit.performers.isNotEmpty()) repository.addPeople(ids, PersonRole.PERFORMER, edit.performers)
                    if (edit.tags.isNotEmpty()) repository.addTags(ids, edit.tags)
                }
            }
        )
    }
}

// the right of the header: ✕ over a score, and Import
@Composable
private fun LibraryActions(onClose: (() -> Unit)?, onImport: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        onClose?.let { close ->
            val closeLabel = stringResource(R.string.action_close)
            TextButton(
                onClick = close,
                modifier = Modifier.semantics { contentDescription = closeLabel }
            ) { LineIcon(LINE_CLOSE) }
            Spacer(Modifier.width(8.dp))
        }
        Button(onClick = onImport) { Text(stringResource(R.string.action_import)) }
    }
}

/** What the bulk edit applies to the scores chosen. */
private class BulkEdit(
    val composers: List<String>,
    val performers: List<String>,
    val genres: List<String>,
    val keys: List<String>,
    val tags: List<String>,
)

/**
 * What several scores can share. The title and the comment stay out (they are each score's own),
 * and an empty field means "leave it as it is"; people, genres, keys and tags are added to the
 * ones each score already has.
 */
@Composable
private fun BulkEditDialog(
    count: Int,
    knownTags: List<String>,
    knownPeople: List<String>,
    knownGenres: List<String>,
    knownKeys: List<String>,
    onDismiss: () -> Unit,
    onApply: (BulkEdit) -> Unit
) {
    var composers by rememberSaveable(stateSaver = TagListSaver) { mutableStateOf<List<String>?>(emptyList()) }
    var performers by rememberSaveable(stateSaver = TagListSaver) { mutableStateOf<List<String>?>(emptyList()) }
    var genres by rememberSaveable(stateSaver = TagListSaver) { mutableStateOf<List<String>?>(emptyList()) }
    var keys by rememberSaveable(stateSaver = TagListSaver) { mutableStateOf<List<String>?>(emptyList()) }
    var tags by rememberSaveable(stateSaver = TagListSaver) { mutableStateOf<List<String>?>(emptyList()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bulk_edit_title, count)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(stringResource(R.string.bulk_edit_hint), style = MaterialTheme.typography.bodySmall)
                NamesField(
                    COMPOSER_NAMES,
                    tags = composers.orEmpty(),
                    known = knownPeople,
                    onTagsChange = { composers = it },
                    modifier = Modifier.fillMaxWidth()
                )
                NamesField(
                    PERFORMER_NAMES,
                    tags = performers.orEmpty(),
                    known = knownPeople,
                    onTagsChange = { performers = it },
                    modifier = Modifier.fillMaxWidth()
                )
                NamesField(
                    GENRE_NAMES,
                    tags = genres.orEmpty(),
                    known = knownGenres,
                    onTagsChange = { genres = it },
                    modifier = Modifier.fillMaxWidth()
                )
                NamesField(
                    KEY_NAMES,
                    tags = keys.orEmpty(),
                    known = knownKeys,
                    onTagsChange = { keys = it },
                    modifier = Modifier.fillMaxWidth(),
                    sorted = false,
                )
                NamesField(
                    TAG_NAMES,
                    tags = tags.orEmpty(),
                    known = knownTags,
                    onTagsChange = { tags = it },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(BulkEdit(composers.orEmpty(), performers.orEmpty(), genres.orEmpty(), keys.orEmpty(), tags.orEmpty())) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

