package com.musigatto.fosscore

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.musigatto.fosscore.library.ImportResult
import com.musigatto.fosscore.library.LibraryRepository
import com.musigatto.fosscore.library.Sheet
import com.musigatto.fosscore.ui.library.LibraryScreen
import com.musigatto.fosscore.ui.library.SheetDetailScreen
import com.musigatto.fosscore.ui.theme.FOSScoreTheme
import com.musigatto.fosscore.ui.viewer.PdfViewerScreen
import com.musigatto.fosscore.ui.viewer.Settings
import com.musigatto.fosscore.ui.viewer.ThemeMode
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            var themeMode by remember { mutableStateOf(Settings.themeMode(context)) }
            val toggleTheme = {
                themeMode = themeMode.next()
                Settings.setThemeMode(context, themeMode)
            }
            val repository = (context.applicationContext as FOSScoreApp).repository

            FOSScoreTheme(darkTheme = themeMode.darkTheme(isSystemInDarkTheme())) {
                FOSScoreNav(repository, themeMode, toggleTheme)
            }
        }
    }
}

private sealed interface Screen {
    data object Library : Screen
    data class Detail(val sheet: Sheet) : Screen
    data class Viewer(val sheet: Sheet) : Screen
}

// rotating the tablet recreates the Activity: without saving the screen, `screen` went back to
// Library and the open sheet was closed. The viewer already keeps the current page (rememberSaveable).
private val ScreenSaver = listSaver<Screen, Any>(
    save = { s ->
        when (s) {
            Screen.Library -> listOf("library")
            is Screen.Detail -> listOf("detail") + s.sheet.toSaveable()
            is Screen.Viewer -> listOf("viewer") + s.sheet.toSaveable()
        }
    },
    restore = { v ->
        when (v[0]) {
            "detail" -> Screen.Detail(sheetFromSaveable(v.drop(1)))
            "viewer" -> Screen.Viewer(sheetFromSaveable(v.drop(1)))
            else -> Screen.Library
        }
    }
)

internal fun Sheet.toSaveable(): List<Any> =
    listOf(id, fileName, path, hash, title, composer, genre, musicalKey, tags)

internal fun sheetFromSaveable(v: List<Any>) = Sheet(
    id = v[0] as Long,
    fileName = v[1] as String,
    path = v[2] as String,
    hash = v[3] as String,
    title = v[4] as String,
    composer = v[5] as String,
    genre = v[6] as String,
    musicalKey = v[7] as String,
    tags = v[8] as String
)

@Composable
private fun FOSScoreNav(
    repository: LibraryRepository,
    themeMode: ThemeMode,
    toggleTheme: () -> Unit
) {
    var screen by rememberSaveable(stateSaver = ScreenSaver) { mutableStateOf<Screen>(Screen.Library) }
    var messageRes by remember { mutableStateOf<Int?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                when (val result = repository.importSheet(uri)) {
                    is ImportResult.Ok -> screen = Screen.Detail(result.sheet)
                    is ImportResult.Duplicate -> messageRes = R.string.sheet_already_in_library
                    is ImportResult.Error -> messageRes = result.messageRes
                }
            }
        }
    }

    when (val s = screen) {
        is Screen.Detail -> SheetDetailScreen(
            repository = repository,
            sheet = s.sheet,
            onDone = { screen = Screen.Library }
        )

        is Screen.Viewer -> PdfViewerScreen(
            pdfUri = Uri.fromFile(File(s.sheet.path)),
            onBack = { screen = Screen.Library },
            themeMode = themeMode,
            onToggleTheme = toggleTheme,
            sheetHash = s.sheet.hash
        )

        is Screen.Library -> Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            Box(modifier = Modifier.padding(padding)) {
                LibraryScreen(
                    repository = repository,
                    onOpen = { screen = Screen.Viewer(it) },
                    onEdit = { screen = Screen.Detail(it) },
                    onImport = { picker.launch(arrayOf("application/pdf")) }
                )
                messageRes?.let { res ->
                    val msg = stringResource(res)
                    LaunchedEffect(msg) {
                        snackbar.showSnackbar(msg)
                        messageRes = null
                    }
                }
            }
        }
    }
}