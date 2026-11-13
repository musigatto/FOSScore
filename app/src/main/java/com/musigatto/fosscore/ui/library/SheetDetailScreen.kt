package com.musigatto.fosscore.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.musigatto.fosscore.R
import com.musigatto.fosscore.library.LibraryRepository
import com.musigatto.fosscore.library.MusicalKey
import com.musigatto.fosscore.library.genreLabel
import com.musigatto.fosscore.library.PersonRole
import com.musigatto.fosscore.library.Sheet
import kotlinx.coroutines.launch

@Composable
fun SheetDetailScreen(
    repository: LibraryRepository,
    sheet: Sheet,
    onDone: () -> Unit
) {
    var title by rememberSaveable(sheet.id) { mutableStateOf(sheet.title) }
    var comment by rememberSaveable(sheet.id) { mutableStateOf(sheet.comment) }
    // null until read from their tables (once: a rotation keeps the edits)
    var composers by rememberSaveable(sheet.id, stateSaver = TagListSaver) { mutableStateOf<List<String>?>(null) }
    var performers by rememberSaveable(sheet.id, stateSaver = TagListSaver) { mutableStateOf<List<String>?>(null) }
    var tags by rememberSaveable(sheet.id, stateSaver = TagListSaver) { mutableStateOf<List<String>?>(null) }
    // genres and keys as shown (in the app's language)
    var genres by rememberSaveable(sheet.id, stateSaver = TagListSaver) { mutableStateOf<List<String>?>(null) }
    var keys by rememberSaveable(sheet.id, stateSaver = TagListSaver) { mutableStateOf<List<String>?>(null) }
    var confirmDelete by rememberSaveable(sheet.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val knownTags by remember(repository) { repository.tags() }.collectAsState(initial = emptyList())
    val knownPeople by remember(repository) { repository.people() }.collectAsState(initial = emptyList())
    val knownGenres by remember(repository) { repository.genres() }.collectAsState(initial = emptyList())
    val genreLabels = rememberGenreLabels()
    val keyLabels = rememberKeyLabels()

    LaunchedEffect(sheet.id) {
        if (composers == null) composers = repository.peopleOf(sheet.id, PersonRole.COMPOSER)
        if (performers == null) performers = repository.peopleOf(sheet.id, PersonRole.PERFORMER)
        if (tags == null) tags = repository.tagNamesOf(sheet.id)
        if (genres == null) genres = repository.genresOf(sheet.id).map { it.genreLabel(genreLabels) }
        if (keys == null) keys = repository.keysOf(sheet.id).mapNotNull { keyLabels[it] }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // drawn over the whole window: clear of the status bar, as the preferences
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.edit_sheet_title), style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
        OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text(stringResource(R.string.field_title)) }, modifier = Modifier.fillMaxWidth())
        // one list of people for both fields: a singer who writes their songs is picked in both
        NamesField(
            COMPOSER_NAMES,
            tags = composers.orEmpty(),
            known = knownPeople.map { it.name },
            onTagsChange = { composers = it },
            modifier = Modifier.fillMaxWidth()
        )
        NamesField(
            PERFORMER_NAMES,
            tags = performers.orEmpty(),
            known = knownPeople.map { it.name },
            onTagsChange = { performers = it },
            modifier = Modifier.fillMaxWidth()
        )
        NamesField(
            GENRE_NAMES,
            tags = genres.orEmpty(),
            known = knownGenres.map { it.genreLabel(genreLabels) },
            onTagsChange = { genres = it },
            modifier = Modifier.fillMaxWidth()
        )
        // the closed list of keys, in the order the score goes through them
        NamesField(
            KEY_NAMES,
            tags = keys.orEmpty(),
            known = MusicalKey.entries.mapNotNull { keyLabels[it] },
            onTagsChange = { keys = it },
            modifier = Modifier.fillMaxWidth(),
            sorted = false,
        )
        NamesField(
            TAG_NAMES,
            tags = tags.orEmpty(),
            known = knownTags.map { it.name },
            onTagsChange = { tags = it },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = comment,
            onValueChange = { comment = it },
            label = { Text(stringResource(R.string.field_comment)) },
            minLines = 3,
            modifier = Modifier.fillMaxWidth()
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    scope.launch {
                        repository.save(
                            sheet.copy(
                                title = title,
                                comment = comment,
                            )
                        )
                        // not read yet: the score keeps what it has
                        composers?.let { repository.setPeople(sheet.id, PersonRole.COMPOSER, it) }
                        performers?.let { repository.setPeople(sheet.id, PersonRole.PERFORMER, it) }
                        tags?.let { repository.setTags(sheet.id, it) }
                        genres?.let { repository.setGenres(sheet.id, it, genreLabels) }
                        keys?.let { shown -> repository.setKeys(sheet.id, shown.mapNotNull { keyOf(it, keyLabels) }) }
                        onDone()
                    }
                }
            ) { Text(stringResource(R.string.action_save)) }
            Spacer(Modifier.width(16.dp))
            OutlinedButton(onClick = { onDone() }) { Text(stringResource(R.string.action_back)) }
            Spacer(Modifier.width(16.dp))
            Button(onClick = { confirmDelete = true }) { Text(stringResource(R.string.action_delete)) }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_sheet_title)) },
            text = { Text(stringResource(R.string.delete_sheet_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { repository.delete(sheet); onDone() }
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
