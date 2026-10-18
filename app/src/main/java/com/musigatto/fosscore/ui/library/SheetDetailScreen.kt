package com.musigatto.fosscore.ui.library

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.musigatto.fosscore.R
import com.musigatto.fosscore.library.LibraryRepository
import com.musigatto.fosscore.library.Sheet
import kotlinx.coroutines.launch

@Composable
fun SheetDetailScreen(
    repository: LibraryRepository,
    sheet: Sheet,
    onDone: () -> Unit
) {
    var title by rememberSaveable(sheet.id) { mutableStateOf(sheet.title) }
    var composer by rememberSaveable(sheet.id) { mutableStateOf(sheet.composer) }
    var genre by rememberSaveable(sheet.id) { mutableStateOf(sheet.genre) }
    var musicalKey by rememberSaveable(sheet.id) { mutableStateOf(sheet.musicalKey) }
    var tags by rememberSaveable(sheet.id) { mutableStateOf(sheet.tags) }
    var confirmDelete by rememberSaveable(sheet.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.edit_sheet_title), style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
        OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text(stringResource(R.string.field_title)) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = composer, onValueChange = { composer = it }, label = { Text(stringResource(R.string.field_composer)) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = genre, onValueChange = { genre = it }, label = { Text(stringResource(R.string.field_genre)) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = musicalKey, onValueChange = { musicalKey = it }, label = { Text(stringResource(R.string.field_key)) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = tags, onValueChange = { tags = it }, label = { Text(stringResource(R.string.field_tags)) }, modifier = Modifier.fillMaxWidth())

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    scope.launch {
                        repository.save(
                            sheet.copy(title = title, composer = composer, genre = genre, musicalKey = musicalKey, tags = tags)
                        )
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
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}