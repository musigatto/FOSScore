package com.musigatto.fosscore.ui.library

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
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
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(sheet.title, style = MaterialTheme.typography.titleMedium)
                            if (sheet.composer.isNotBlank()) {
                                Text(sheet.composer, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Button(onClick = { onEdit(sheet) }) { Text(stringResource(R.string.action_edit)) }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { onOpen(sheet) }) { Text(stringResource(R.string.action_open)) }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}