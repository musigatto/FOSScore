package com.musigatto.fosscore

import android.content.Context
import android.content.pm.ApplicationInfo
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.FrameMetrics
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.musigatto.fosscore.library.GroupKind
import com.musigatto.fosscore.library.GenreRepository
import com.musigatto.fosscore.library.ImportResult
import com.musigatto.fosscore.library.LibraryRepository
import com.musigatto.fosscore.library.PersonRepository
import com.musigatto.fosscore.library.PersonRole
import com.musigatto.fosscore.library.ScoreGroups
import com.musigatto.fosscore.library.SetlistRepository
import com.musigatto.fosscore.library.Sheet
import com.musigatto.fosscore.library.TagRepository
import com.musigatto.fosscore.library.neighbour
import com.musigatto.fosscore.score.ScorePrecompute
import com.musigatto.fosscore.score.SheetFormat
import com.musigatto.fosscore.ui.groups.ScoreGroupsScreen
import com.musigatto.fosscore.ui.groups.genreFacets
import com.musigatto.fosscore.ui.groups.personFacets
import com.musigatto.fosscore.ui.groups.setlistFacets
import com.musigatto.fosscore.ui.groups.tagFacets
import com.musigatto.fosscore.ui.groups.texts
import com.musigatto.fosscore.ui.library.LibraryMenuDrawer
import com.musigatto.fosscore.ui.library.LocalSheetCredits
import com.musigatto.fosscore.ui.library.rememberGenreLabels
import com.musigatto.fosscore.ui.library.alphabetical
import com.musigatto.fosscore.ui.library.MainNavigation
import com.musigatto.fosscore.ui.library.MenuView
import com.musigatto.fosscore.ui.library.LibraryScreen
import com.musigatto.fosscore.ui.library.SheetDetailScreen
import com.musigatto.fosscore.ui.settings.PreferencesScreen
import com.musigatto.fosscore.ui.theme.FOSScoreTheme
import com.musigatto.fosscore.ui.viewer.AppLanguage
import com.musigatto.fosscore.ui.viewer.InkPerfMetrics
import com.musigatto.fosscore.ui.viewer.PdfViewerScreen
import com.musigatto.fosscore.ui.viewer.SetlistPlace
import com.musigatto.fosscore.ui.viewer.Settings
import com.musigatto.fosscore.ui.viewer.StartPage
import com.musigatto.fosscore.ui.viewer.ThemeMode
import java.io.File
import java.util.Locale
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            // the display's rate sets the ink frame budget; the window's getDefaultDisplay() is
            // deprecated, DisplayManager works on every supported version
            val display = getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
            InkPerfMetrics.enable(display?.refreshRate ?: 0f)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // no removal needed: the listener hangs off the window, which dies with the activity
                window.addOnFrameMetricsAvailableListener(
                    { _, metrics, _ ->
                        InkPerfMetrics.frame(metrics.getMetric(FrameMetrics.TOTAL_DURATION))
                    },
                    Handler(Looper.getMainLooper()),
                )
            }
        }
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
internal data class NavState(
    val sheet: Sheet? = null,
    val libraryOpen: Boolean = false,
    val detail: Sheet? = null,
    // the app's preferences, over whatever screen opened them
    val preferences: Boolean = false,
    /** The groups shown over everything (the setlists, the tags), and which one (0: their list). */
    val groups: GroupKind? = null,
    val groupShown: Long = 0,
    /** Which of the group's facets shows: a person's scores as author/composer (0) or as performer (1). */
    val groupFacet: Int = 0,
    /**
     * The score open is this entry of this group (0: opened from the library): a setlist's place,
     * or the score itself in a tag.
     */
    val playKind: GroupKind = GroupKind.SETLIST,
    val playGroup: Long = 0,
    /** The facet the score was opened from: the next score is the next one of that list. */
    val playFacet: Int = 0,
    val playItem: Long = 0,
    /** The page the score opens on. */
    val start: StartPage = StartPage.REMEMBERED,
    /** The score opens with its top bar shown: the one before in the setlist was left with it. */
    val chromeShown: Boolean = false,
) {
    /** A view chosen in the side menu (or its icon bar); one not built yet changes nothing. */
    fun toView(view: MenuView) = when (view) {
        MenuView.LIBRARY -> toLibrary()
        MenuView.SETLISTS -> toGroups(GroupKind.SETLIST)
        MenuView.TAGS -> toGroups(GroupKind.TAG)
        MenuView.PEOPLE -> toGroups(GroupKind.PERSON)
        MenuView.GENRES -> toGroups(GroupKind.GENRE)
        MenuView.PRACTICE -> this
    }

    /**
     * Another entry of the group playing, turned to from the score open: [chromeShown] is
     * whether that score's top bar showed, and the next one opens the same way.
     */
    fun toPlace(sheet: Sheet, itemId: Long, start: StartPage, chromeShown: Boolean) =
        copy(sheet = sheet, playItem = itemId, start = start, chromeShown = chromeShown)

    /** The library, chosen in the side menu: the view it replaces closes. */
    fun toLibrary() = copy(libraryOpen = true, groups = null)

    /** The groups of [kind], chosen in the side menu or from a score: [shown] (0: their list). */
    fun toGroups(kind: GroupKind, shown: Long = 0, facet: Int = 0) =
        copy(groups = kind, groupShown = shown, groupFacet = facet, libraryOpen = false)

    /**
     * Back from the score: to where it was opened from. A score of a setlist or a tag goes back
     * to it, where the entry just left stays marked; any other score goes back to the library.
     */
    fun leaveScore() =
        if (playGroup != 0L) copy(sheet = null, libraryOpen = false, groups = playKind, groupShown = playGroup, groupFacet = playFacet)
        else copy(sheet = null, libraryOpen = false)
}

// rotating the tablet recreates the Activity: without saving this, the open sheet was closed, and we
// fell back to the library. The viewer already keeps the current page (rememberSaveable).
private val NavSaver = listSaver(
    save = {
        listOf(
            it.sheet?.toSaveable() ?: emptyList<Any>(),
            it.libraryOpen,
            it.detail?.toSaveable() ?: emptyList<Any>(),
            it.preferences,
            it.groups?.name.orEmpty(),
            it.groupShown,
            it.playGroup,
            it.playItem,
            it.start.name,
            it.playKind.name,
            it.groupFacet,
            it.playFacet,
        )
    },
    restore = { v ->
        fun sheetAt(i: Int) = (v[i] as List<*>)
            .takeIf { l -> l.isNotEmpty() }
            ?.let { l -> sheetFromSaveable(l.filterNotNull()) }
        NavState(
            sheet = sheetAt(0),
            libraryOpen = v[1] as Boolean,
            detail = sheetAt(2),
            preferences = v.getOrNull(3) as? Boolean ?: false,
            groups = (v.getOrNull(4) as? String)?.takeIf { it.isNotEmpty() }?.let { GroupKind.valueOf(it) },
            groupShown = v.getOrNull(5) as? Long ?: 0L,
            playGroup = v.getOrNull(6) as? Long ?: 0L,
            playItem = v.getOrNull(7) as? Long ?: 0L,
            start = (v.getOrNull(8) as? String)?.let { StartPage.valueOf(it) } ?: StartPage.REMEMBERED,
            playKind = (v.getOrNull(9) as? String)?.let { GroupKind.valueOf(it) } ?: GroupKind.SETLIST,
            groupFacet = v.getOrNull(10) as? Int ?: 0,
            playFacet = v.getOrNull(11) as? Int ?: 0,
        )
    }
)

// The score's details travel with it across a rotation. A listSaver only carries non-null values,
// so a field the row does not have yet goes as "" or 0 and comes back as null: 0 is never a real
// tempo, duration or count, and no format or time signature is the empty string.
internal fun Sheet.toSaveable(): List<Any> = listOf(
    id, fileName, path, hash, title, composer, genre, musicalKey, tags,
    format?.name.orEmpty(), tempoBpm ?: 0.0, beatUnit ?: 0, timeSignature.orEmpty(),
    durationS ?: 0.0, openedAt ?: 0L, openCount ?: 0, addedAt ?: 0L, comment,
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
    comment = v.getOrNull(17) as? String ?: "",
)

@Composable
private fun FOSScoreNav(
    repository: LibraryRepository,
    themeMode: ThemeMode,
    toggleTheme: () -> Unit
) {
    var nav by rememberSaveable(stateSaver = NavSaver) { mutableStateOf(NavState()) }
    var messageRes by remember { mutableStateOf<Int?>(null) }
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
    // the group the score open belongs to, in the order it plays (a setlist's, a tag's by title as
    // its list shows it): the next score and the one before
    val context = LocalContext.current
    val setlists = remember { SetlistRepository(context) }
    val tags = remember { TagRepository(context) }
    val people = remember { PersonRepository(context) }
    val composers = remember { PersonRepository(context, PersonRole.COMPOSER) }
    val performers = remember { PersonRepository(context, PersonRole.PERFORMER) }
    // the default genres in the app's language (the activity is recreated when it changes)
    val genreLabels = rememberGenreLabels()
    val genres = remember(genreLabels) { GenreRepository(context, genreLabels) }
    fun groupsOf(kind: GroupKind): ScoreGroups = when (kind) {
        GroupKind.SETLIST -> setlists
        GroupKind.TAG -> tags
        GroupKind.PERSON -> people
        GroupKind.GENRE -> genres
    }
    fun facetsOf(kind: GroupKind) = when (kind) {
        GroupKind.SETLIST -> setlistFacets(setlists)
        GroupKind.TAG -> tagFacets(tags)
        GroupKind.PERSON -> personFacets(composers, performers)
        GroupKind.GENRE -> genreFacets(genres)
    }
    // who each score is by, shown under its title in every list and in the viewer
    val credits by remember(repository) { repository.creditsBySheet() }.collectAsState(initial = emptyMap())
    val playing by remember(s.playKind, s.playGroup, s.playFacet) {
        // the scores of the facet the score was opened from (a person's as performer, say)
        val groups = facetsOf(s.playKind).getOrNull(s.playFacet)?.groups ?: groupsOf(s.playKind)
        if (s.playGroup == 0L) flowOf(emptyList())
        else groups.entries(s.playGroup).map { entries ->
            if (groups.ordered) entries else alphabetical(entries) { it.sheet.title }
        }
    }.collectAsState(initial = emptyList())
    val playingIds = playing.map { it.entryKey }
    val playingName by remember(s.playKind, s.playGroup) {
        if (s.playGroup == 0L) flowOf(null) else groupsOf(s.playKind).name(s.playGroup)
    }.collectAsState(initial = null)
    val place = playingIds.indexOf(s.playItem).takeIf { it >= 0 }?.let { at ->
        playingName?.let { name -> SetlistPlace(name, at + 1, playingIds.size) }
    }
    // the main views' header: ☰ and the name, or the views' icons (preferences, Main screen)
    var mainStyle by remember { mutableStateOf(Settings.mainStyle(context)) }
    DisposableEffect(Unit) {
        val stop = Settings.onMainStyleChange(context) { mainStyle = it }
        onDispose { stop() }
    }
    val mainNavigation: @Composable (MenuView) -> Unit = { view ->
        MainNavigation(
            current = view,
            style = mainStyle,
            onOpenMenu = { scope.launch { drawerState.open() } },
            onOpenView = { nav = nav.toView(it) },
            onOpenPreferences = { nav = nav.copy(preferences = true) },
        )
    }
    fun openPlace(itemId: Long?, start: StartPage): ((Boolean) -> Unit)? {
        val entry = playing.find { it.entryKey == itemId } ?: return null
        return { chromeShown -> nav = nav.toPlace(entry.sheet, entry.entryKey, start, chromeShown) }
    }
    // The window background is always light (Theme.FOSScore extends Theme.Material.Light), and a
    // composable that paints nothing shows it: that is why the page matte was white even in dark
    // mode. Painting the app background here covers every screen.
    CompositionLocalProvider(LocalSheetCredits provides credits) {
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // the score stays composed under the library: opening the menu must not reload the PDF
        s.sheet?.let { sheet ->
            // Keyed by document. The viewer now stays composed while the library is open, so without
            // this key its annotation session (undo stack, selection, hidden ids) would leak from one
            // score into the next — and undo would then write one score's stamps over the other's.
            // and by place: the same score twice in a row in a setlist opens again on its first page
            key(sheet.hash, s.playItem) {
                PdfViewerScreen(
                    pdfUri = Uri.fromFile(File(sheet.path)),
                    title = sheet.title,
                    composer = credits[sheet.id]?.line.orEmpty(),
                    // back leaves the score for where it came from: score -> setlist or library -> exit
                    onBack = { nav = nav.leaveScore() },
                    // ♪ opens the library over the live page instead
                    onOpenLibrary = { nav = nav.copy(libraryOpen = true) },
                    themeMode = themeMode,
                    onToggleTheme = toggleTheme,
                    onOpenPreferences = { nav = nav.copy(preferences = true) },
                    sheetId = sheet.id,
                    onOpenSetlist = { nav = nav.toGroups(s.playKind, s.playGroup, s.playFacet) },
                    onPastEnd = openPlace(neighbour(playingIds, s.playItem, 1), StartPage.FIRST),
                    onBeforeStart = openPlace(neighbour(playingIds, s.playItem, -1), StartPage.LAST),
                    start = s.start,
                    setlistPlace = place,
                    chromeShown = s.chromeShown,
                )
            }
        }

        // ponytail: the library covers the whole screen. The sliding/tiled sidebar and its animation
        // are their own step — this layering is what they will need.
        // The side menu goes from one view to another (library, setlists, tags), so it wraps them all.
        val libraryShown = s.libraryOpen || s.sheet == null
        if (libraryShown || s.groups != null) {
            LibraryMenuDrawer(
                drawerState,
                current = s.groups?.view() ?: MenuView.LIBRARY,
                onOpenPreferences = { nav = nav.copy(preferences = true) },
                onOpenView = { view -> nav = nav.toView(view) },
            ) {
                Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
                    Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                        if (libraryShown) LibraryScreen(
                            repository = repository,
                            onOpen = {
                                nav = nav.copy(sheet = it, libraryOpen = false, playGroup = 0, playItem = 0, start = StartPage.REMEMBERED, chromeShown = false)
                            },
                            onEdit = { nav = nav.copy(detail = it) },
                            onImport = { picker.launch(SheetFormat.PICKER_TYPES) },
                            onClose = if (s.sheet != null) ({ nav = nav.copy(libraryOpen = false) }) else null,
                            onOpenMenu = { scope.launch { drawerState.open() } },
                            navigation = { mainNavigation(MenuView.LIBRARY) },
                        )
                        // the setlists or the tags, over the library or the score. Their back is
                        // registered before the screens, so a group's own back (to the list) goes first
                        s.groups?.let { kind ->
                            // a screen of its own for each kind: one's state does not leak into the other
                            key(kind) {
                                BackHandler { nav = nav.copy(groups = null) }
                                Surface(modifier = Modifier.fillMaxSize()) {
                                    ScoreGroupsScreen(
                                        groups = groupsOf(kind),
                                        texts = kind.texts(),
                                        facets = facetsOf(kind),
                                        facet = s.groupFacet,
                                        library = repository,
                                        shownId = s.groupShown.takeIf { it != 0L },
                                        // marked only in its own group: a tag's entry is a score id, a
                                        // setlist's a place id
                                        playingKey = s.playItem.takeIf {
                                            it != 0L && s.playKind == kind && s.playGroup == s.groupShown &&
                                                s.playFacet == s.groupFacet
                                        },
                                        onShow = { nav = nav.copy(groupShown = it ?: 0L) },
                                        onFacet = { nav = nav.copy(groupFacet = it) },
                                        onPlay = { groupId, entry ->
                                            nav = nav.copy(
                                                sheet = entry.sheet, libraryOpen = false, groups = null,
                                                playKind = kind, playGroup = groupId, playFacet = s.groupFacet,
                                                playItem = entry.entryKey,
                                                start = StartPage.FIRST, chromeShown = false,
                                            )
                                        },
                                        onClose = { nav = nav.copy(groups = null) },
                                        navigation = { mainNavigation(kind.view()) },
                                    )
                                }
                            }
                        }
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
        // over everything, with its own back handling (its sub-menus first)
        if (s.preferences) {
            Surface(modifier = Modifier.fillMaxSize()) {
                PreferencesScreen(onDone = { nav = nav.copy(preferences = false) })
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
}
/** The view of the side menu showing the groups of [this] kind. */
private fun GroupKind.view(): MenuView = when (this) {
    GroupKind.SETLIST -> MenuView.SETLISTS
    GroupKind.TAG -> MenuView.TAGS
    GroupKind.PERSON -> MenuView.PEOPLE
    GroupKind.GENRE -> MenuView.GENRES
}
