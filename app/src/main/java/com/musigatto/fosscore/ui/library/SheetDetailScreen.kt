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
import androidx.compose.ui.unit.dp
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
        Text("Editar partitura", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
        OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Título") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = composer, onValueChange = { composer = it }, label = { Text("Compositor") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = genre, onValueChange = { genre = it }, label = { Text("Género") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = musicalKey, onValueChange = { musicalKey = it }, label = { Text("Tonalidad") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = tags, onValueChange = { tags = it }, label = { Text("Etiquetas") }, modifier = Modifier.fillMaxWidth())

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
            ) { Text("Guardar") }
            Spacer(Modifier.width(16.dp))
            OutlinedButton(onClick = { onDone() }) { Text("Volver") }
            Spacer(Modifier.width(16.dp))
            Button(onClick = { confirmDelete = true }) { Text("Eliminar") }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("¿Eliminar esta partitura?") },
            text = { Text("Se borrará de la biblioteca y del dispositivo.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { repository.delete(sheet); onDone() }
                }) { Text("Eliminar") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } }
        )
    }
}