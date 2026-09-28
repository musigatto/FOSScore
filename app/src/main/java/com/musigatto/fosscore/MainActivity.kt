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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.musigatto.fosscore.library.ImportResult
import com.musigatto.fosscore.library.LibraryRepository
import com.musigatto.fosscore.library.Sheet
import com.musigatto.fosscore.ui.library.LibraryScreen
import com.musigatto.fosscore.ui.library.SheetDetailScreen
import com.musigatto.fosscore.ui.theme.FOSScoreTheme
import com.musigatto.fosscore.ui.viewer.PdfViewerScreen
import com.musigatto.fosscore.ui.viewer.Settings
import com.musigatto.fosscore.ui.viewer.ThemeMode
import java.io.File
import kotlinx.coroutines.launch

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

@Composable
private fun FOSScoreNav(
    repository: LibraryRepository,
    themeMode: ThemeMode,
    toggleTheme: () -> Unit
) {
    var screen by remember { mutableStateOf<Screen>(Screen.Library) }
    var message by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                when (val result = repository.importSheet(uri)) {
                    is ImportResult.Ok -> screen = Screen.Detail(result.sheet)
                    is ImportResult.Duplicate -> message = "Esa partitura ya está en la biblioteca"
                    is ImportResult.Error -> message = result.reason
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
                message?.let { msg ->
                    LaunchedEffect(msg) {
                        snackbar.showSnackbar(msg)
                        message = null
                    }
                }
            }
        }
    }
}