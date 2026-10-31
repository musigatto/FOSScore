package com.musigatto.fosscore

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import com.musigatto.fosscore.score.ScorePrecompute
import com.musigatto.fosscore.score.SheetFormat
import com.musigatto.fosscore.ui.library.LibraryMenuDrawer
import com.musigatto.fosscore.ui.library.LibraryScreen
import com.musigatto.fosscore.ui.library.SheetDetailScreen
import com.musigatto.fosscore.ui.theme.FOSScoreTheme
import com.musigatto.fosscore.ui.viewer.AppLanguage
import com.musigatto.fosscore.ui.viewer.PdfViewerScreen
import com.musigatto.fosscore.ui.viewer.Settings
import com.musigatto.fosscore.ui.viewer.ThemeMode
import java.io.File
import java.util.Locale
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    // The app language is chosen in the app, so resources are resolved against it; the default
    // locale follows too, for dates and numbers. Picking another one recreates the activity.
    override fun attachBaseContext(newBase: Context) {
        val language = Settings.language(newBase)
        Locale.setDefault(language.tag?.let(Locale::forLanguageTag) ?: AppLanguage.deviceLocale())
        super.attachBaseContext(language.wrap(newBase))
    }

    // the scores are laid out ahead only while the user leaves the tablet alone
    override fun onUserInteraction() {
        super.onUserInteraction()
        ScorePrecompute.touched()
    }

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

// The library is no longer a destination but a layer: the score stays composed underneath, so opening
// the menu does not reload the PDF. One state object and one saver — same shape as before.
private data class NavState(
    val sheet: Sheet? = null,
    val libraryOpen: Boolean = false,
    val detail: Sheet? = null
)

// rotating the tablet recreates the Activity: without saving this, the open sheet was closed, and we
// fell back to the library. The viewer already keeps the current page (rememberSaveable).
private val NavSaver = listSaver(
    save = {
        listOf(
            it.sheet?.toSaveable() ?: emptyList<Any>(),
            it.libraryOpen,
            it.detail?.toSaveable() ?: emptyList<Any>()
        )
    },
    restore = { v ->
        fun sheetAt(i: Int) = (v[i] as List<*>)
            .takeIf { l -> l.isNotEmpty() }
            ?.let { l -> sheetFromSaveable(l.filterNotNull()) }
        NavState(
            sheet = sheetAt(0),
            libraryOpen = v[1] as Boolean,
            detail = sheetAt(2)
        )
    }
)

// The score's details travel with it across a rotation. A listSaver only carries non-null values,
// so a field the row does not have yet goes as "" or 0 and comes back as null: 0 is never a real
// tempo, duration or count, and no format or time signature is the empty string.
internal fun Sheet.toSaveable(): List<Any> = listOf(
    id, fileName, path, hash, title, composer, genre, musicalKey, tags,
    format?.name.orEmpty(), tempoBpm ?: 0.0, beatUnit ?: 0, timeSignature.orEmpty(),
    durationS ?: 0.0, openedAt ?: 0L, openCount ?: 0, addedAt ?: 0L,
)

internal fun sheetFromSaveable(v: List<Any>) = Sheet(
    id = v[0] as Long,
    fileName = v[1] as String,
    path = v[2] as String,
    hash = v[3] as String,
    title = v[4] as String,
    composer = v[5] as String,
    genre = v[6] as String,
    musicalKey = v[7] as String,
    tags = v[8] as String,
    format = (v[9] as String).takeIf { it.isNotEmpty() }?.let { SheetFormat.valueOf(it) },
    tempoBpm = (v[10] as Double).takeIf { it != 0.0 },
    beatUnit = (v[11] as Int).takeIf { it != 0 },
    timeSignature = (v[12] as String).takeIf { it.isNotEmpty() },
    durationS = (v[13] as Double).takeIf { it != 0.0 },
    openedAt = (v[14] as Long).takeIf { it != 0L },
    openCount = (v[15] as Int).takeIf { it != 0 },
    addedAt = (v[16] as Long).takeIf { it != 0L },
)

@Composable
private fun FOSScoreNav(
    repository: LibraryRepository,
    themeMode: ThemeMode,
    toggleTheme: () -> Unit
) {
    var nav by rememberSaveable(stateSaver = NavSaver) { mutableStateOf(NavState()) }
    var messageRes by remember { mutableStateOf<Int?>(null) }
    // already formatted texts (counts), from the library export / import
    var messageText by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // the library's side menu, kept here for the back key below
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                when (val result = repository.importSheet(uri)) {
                    is ImportResult.Ok -> nav = nav.copy(detail = result.sheet)
                    is ImportResult.Duplicate -> messageRes = R.string.sheet_already_in_library
                    is ImportResult.Error -> messageRes = result.messageRes
                }
            }
        }
    }

    val s = nav
    // The window background is always light (Theme.FOSScore extends Theme.Material.Light), and a
    // composable that paints nothing shows it: that is why the page matte was white even in dark
    // mode. Painting the app background here covers every screen.
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // the score stays composed under the library: opening the menu must not reload the PDF
        s.sheet?.let { sheet ->
            // Keyed by document. The viewer now stays composed while the library is open, so without
            // this key its annotation session (undo stack, selection, hidden ids) would leak from one
            // score into the next — and undo would then write one score's stamps over the other's.
            key(sheet.hash) {
                PdfViewerScreen(
                    pdfUri = Uri.fromFile(File(sheet.path)),
                    title = sheet.title,
                    composer = sheet.composer,
                    // back leaves the score, so the Android up-chain is score -> library -> exit
                    onBack = { nav = nav.copy(sheet = null, libraryOpen = false) },
                    // ♪ opens the library over the live page instead
                    onOpenLibrary = { nav = nav.copy(libraryOpen = true) },
                    themeMode = themeMode,
                    onToggleTheme = toggleTheme,
                    sheetId = sheet.id
                )
            }
        }

        // ponytail: the library covers the whole screen. The sliding/tiled sidebar and its animation
        // are their own step — this layering is what they will need.
        if (s.libraryOpen || s.sheet == null) {
            LibraryMenuDrawer(drawerState, onMessage = { messageText = it }) {
                Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
                    Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                        LibraryScreen(
                            repository = repository,
                            onOpen = { nav = nav.copy(sheet = it, libraryOpen = false) },
                            onEdit = { nav = nav.copy(detail = it) },
                            onImport = { picker.launch(SheetFormat.PICKER_TYPES) },
                            onClose = if (s.sheet != null) ({ nav = nav.copy(libraryOpen = false) }) else null,
                            onOpenMenu = { scope.launch { drawerState.open() } }
                        )
                        messageRes?.let { res ->
                            val msg = stringResource(res)
                            LaunchedEffect(msg) {
                                snackbar.showSnackbar(msg)
                                messageRes = null
                            }
                        }
                        messageText?.let { msg ->
                            LaunchedEffect(msg) {
                                snackbar.showSnackbar(msg)
                                messageText = null
                            }
                        }
                    }
                }
            }
        }

        // ponytail: editing metadata from here leaves nav.sheet stale, so the pill keeps showing the
        // old title until the score is reopened. Refresh nav.sheet from the DB when the library chrome
        // gets its own step.
        s.detail?.let { sheet ->
            Surface(modifier = Modifier.fillMaxSize()) {
                SheetDetailScreen(
                    repository = repository,
                    sheet = sheet,
                    onDone = { nav = nav.copy(detail = null) }
                )
            }
        }
        // registered after the viewer on purpose: BackHandler resolves last-registered-first, so with
        // the menu open back closes the menu instead of leaving the score
        if (s.libraryOpen && s.sheet != null) BackHandler { nav = nav.copy(libraryOpen = false) }

        // then: with the library's side menu open, back closes the menu, not the library or the app
        if (drawerState.isOpen) BackHandler { scope.launch { drawerState.close() } }

        // and last of all: with the metadata editor on top, back closes the editor (same as its
        // Volver), not the layer underneath it
        if (s.detail != null) BackHandler { nav = nav.copy(detail = null) }
    }
}