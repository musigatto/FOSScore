package com.musigatto.fosscore.ui.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.musigatto.fosscore.R
import com.musigatto.fosscore.sound.BankFile
import com.musigatto.fosscore.sound.SoundBank
import com.musigatto.fosscore.sound.SoundBankEntry
import com.musigatto.fosscore.ui.viewer.AppLanguage
import com.musigatto.fosscore.ui.viewer.ChromeStyle
import com.musigatto.fosscore.ui.viewer.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// the list of the banks found in a folder: tall enough for a few, short enough for a phone
private const val FOUND_HEIGHT_DP = 320

// the widest the preferences get on a tablet: lines longer than this are hard to read
private const val MAX_WIDTH_DP = 640

/** The sections of the preferences, in their order. Each one opens under its title. */
private enum class Section { LANGUAGE, MAIN, VIEWER, KEYBOARD, MIDI, LIBRARY }

/**
 * The app's preferences, over the screen it was opened from: what applies to every score. A list
 * of sections, each opened under its title (one at a time), so new ones join without changing the
 * others: the language, the score screen (menu or icon bar), the keyboard and the pedals, MIDI
 * (the sound banks), and the library (export and import).
 */
@Composable
fun PreferencesScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    // the section open, by name (kept when the screen turns, and when a new language recreates it)
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var chromeStyle by remember { mutableStateOf(Settings.chromeStyle(context)) }
    var mainStyle by remember { mutableStateOf(Settings.mainStyle(context)) }
    BackHandler(onBack = onDone)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        // on the left, as the other screens: centred, a wide screen left a blank strip on each side
        horizontalAlignment = Alignment.Start,
    ) {
        Column(modifier = Modifier.widthIn(max = MAX_WIDTH_DP.dp).fillMaxWidth()) {
            Text(stringResource(R.string.menu_settings), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 8.dp))
            Section.entries.forEach { section ->
                val isOpen = open == section.name
                SectionTitle(section.title(), section.summary(chromeStyle, mainStyle), isOpen) { open = if (isOpen) null else section.name }
                if (isOpen) {
                    Column(modifier = Modifier.padding(start = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        when (section) {
                            Section.LANGUAGE -> LanguageSection()
                            Section.MAIN -> StyleSection(mainStyle, { it.mainLabel() }) {
                                mainStyle = it
                                Settings.setMainStyle(context, it)
                            }
                            Section.VIEWER -> StyleSection(chromeStyle, { it.label() }) {
                                chromeStyle = it
                                Settings.setChromeStyle(context, it)
                            }
                            Section.KEYBOARD -> KeyboardSection()
                            Section.MIDI -> SoundBanks()
                            Section.LIBRARY -> {
                                LibraryBackupSection()
                                DefaultGenresRestore()
                            }
                        }
                    }
                }
                HorizontalDivider()
            }
            OutlinedButton(onClick = onDone, modifier = Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.action_back)) }
        }
    }
}

@Composable
private fun Section.title(): String = stringResource(
    when (this) {
        Section.LANGUAGE -> R.string.language_label
        Section.MAIN -> R.string.prefs_main
        Section.VIEWER -> R.string.prefs_viewer
        Section.KEYBOARD -> R.string.prefs_keyboard
        Section.MIDI -> R.string.prefs_midi
        Section.LIBRARY -> R.string.prefs_library
    }
)

@Composable
private fun Section.summary(chromeStyle: ChromeStyle, mainStyle: ChromeStyle): String = when (this) {
    Section.LANGUAGE -> Settings.language(LocalContext.current).displayName.ifEmpty { stringResource(R.string.language_system) }
    Section.MAIN -> mainStyle.mainLabel()
    Section.VIEWER -> chromeStyle.label()
    Section.KEYBOARD -> stringResource(R.string.prefs_keyboard_summary)
    Section.MIDI -> stringResource(R.string.prefs_midi_summary)
    Section.LIBRARY -> stringResource(R.string.prefs_library_summary)
}

/** The top of the score screen: the ☰ menu (as before) or the icon bar. A score on screen follows at once. */
@Composable
private fun StyleSection(current: ChromeStyle, label: @Composable (ChromeStyle) -> String, onPick: (ChromeStyle) -> Unit) {
    ChromeStyle.entries.forEach { style ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .selectable(selected = style == current, role = Role.RadioButton) { onPick(style) },
        ) {
            RadioButton(selected = style == current, onClick = null)
            Text(label(style), modifier = Modifier.padding(start = 12.dp))
        }
    }
}

// the main views' header: the views in the side menu, or an icon each
@Composable
private fun ChromeStyle.mainLabel(): String = stringResource(
    when (this) {
        ChromeStyle.MENU -> R.string.main_menu
        ChromeStyle.ICONS -> R.string.main_icons
    }
)

@Composable
private fun ChromeStyle.label(): String = stringResource(
    when (this) {
        ChromeStyle.MENU -> R.string.chrome_menu
        ChromeStyle.ICONS -> R.string.chrome_icons
    }
)

// a section's title, what it holds under it, and ▸ / ▾ for closed / open; a tap opens or closes it
@Composable
private fun SectionTitle(title: String, summary: String, open: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(if (open) "▾" else "▸", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
    }
}

/** The app's language: the device's, or one the app ships, each named in itself. The app restarts its screen in it. */
@Composable
private fun LanguageSection() {
    val context = LocalContext.current
    val current = Settings.language(context)
    AppLanguage.entries.forEach { language ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .selectable(selected = language == current, role = Role.RadioButton) {
                    if (language == current) return@selectable
                    Settings.setLanguage(context, language)
                    // every string is resolved again in the new language
                    (context as? Activity)?.recreate()
                },
        ) {
            RadioButton(selected = language == current, onClick = null)
            Text(language.displayName.ifEmpty { stringResource(R.string.language_system) }, modifier = Modifier.padding(start = 12.dp))
        }
    }
}

/**
 * Under a bank in SF3 not ready yet: how far its sounds are prepared, and that it can be chosen
 * only then. The preparation goes on by itself (when it is added, and each time the app starts).
 */
@Composable
private fun BankState(bank: SoundBankEntry, progress: Pair<Int, Int>?) {
    if (bank.ready) return
    val style = MaterialTheme.typography.bodySmall
    val text = progress?.let { (done, total) ->
        stringResource(R.string.sound_bank_preparing, if (total > 0) done * 100 / total else 0)
    } ?: stringResource(R.string.sound_bank_waiting)
    Text(text, style = style, color = MaterialTheme.colorScheme.primary)
}

/**
 * The sound banks: the default one picked with its radio button, the ones added deleted with
 * their button (the built-in one stays), and more added from a folder of the device. Android's
 * file picker filters by type, not by extension, and knows no type for .sf2 and .sf3: so the user
 * picks the folder, and the app lists its SF2 and SF3 files only. The folder is kept for next time.
 */
@Composable
private fun SoundBanks() {
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()
    var banks by remember { mutableStateOf(emptyList<SoundBankEntry>()) }
    var chosen by remember { mutableStateOf(Settings.soundBank(app)) }
    var adding by remember { mutableStateOf(false) }
    var notRead by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SoundBankEntry?>(null) }
    // the folder the banks are added from, and what it holds (null: its list is not shown)
    var folder by remember { mutableStateOf(Settings.bankFolder(app)?.let { Uri.parse(it) }) }
    var found by remember { mutableStateOf<List<BankFile>?>(null) }
    var folderName by remember { mutableStateOf("") }
    // the folder picker is explained before it opens: it shows every file, greyed out
    var explaining by remember { mutableStateOf(false) }

    suspend fun reload() {
        banks = withContext(Dispatchers.IO) { SoundBank.list(app) }
        // a default bank that is gone falls back to the built-in one
        if (banks.none { it.id == chosen }) chosen = SoundBank.BUILT_IN
    }
    // the banks being prepared, and their progress; the list is read again when one starts or ends
    val preparing by SoundBank.preparing.collectAsState()
    LaunchedEffect(preparing.keys) { reload() }
    // a preparation cut short goes on (it does when the app starts, too)
    LaunchedEffect(Unit) { SoundBank.prepareAll(app) }

    suspend fun browse(tree: Uri) {
        found = withContext(Dispatchers.IO) {
            folderName = SoundBank.folderName(app, tree)
            SoundBank.filesIn(app, tree)
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree == null) return@rememberLauncherForActivityResult
        // kept for next time, beyond this screen
        runCatching { app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        Settings.setBankFolder(app, tree.toString())
        folder = tree
        scope.launch { browse(tree) }
    }

    fun add(file: BankFile) {
        found = null
        adding = true
        notRead = false
        scope.launch {
            val added = withContext(Dispatchers.IO) { SoundBank.add(app, file.uri) }
            adding = false
            notRead = added == null
            // a bank in SF3 is prepared at once, in the background: its pieces then play at once
            added?.let { SoundBank.prepare(app, it.id) }
            reload()
        }
    }

    // the folder of last time when the app can still read it, else the folder picker
    fun startAdding() {
        notRead = false
        val tree = folder?.takeIf { f -> app.contentResolver.persistedUriPermissions.any { it.uri == f && it.isReadPermission } }
        if (tree != null) scope.launch { browse(tree) } else explaining = true
    }

    Text(stringResource(R.string.sound_banks_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(stringResource(R.string.sound_bank_default), style = MaterialTheme.typography.labelLarge)
    banks.forEach { bank ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                // a bank not ready yet cannot be chosen
                .selectable(selected = bank.id == chosen, enabled = bank.ready, role = Role.RadioButton) {
                    chosen = bank.id
                    Settings.setSoundBank(app, bank.id)
                },
        ) {
            RadioButton(selected = bank.id == chosen, onClick = null, enabled = bank.ready)
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(bank.name, style = MaterialTheme.typography.bodyLarge)
                val size = Formatter.formatShortFileSize(context, bank.size)
                Text(
                    if (bank.builtIn) "${stringResource(R.string.sound_bank_built_in)} · $size" else size,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                BankState(bank, preparing[bank.id])
            }
            if (!bank.builtIn) {
                TextButton(onClick = { deleting = bank }) { Text(stringResource(R.string.sound_bank_delete)) }
            }
        }
    }
    OutlinedButton(onClick = ::startAdding, enabled = !adding) {
        Text(stringResource(if (adding) R.string.sound_bank_adding else R.string.sound_bank_add))
    }
    if (notRead) {
        Text(stringResource(R.string.sound_bank_not_read), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }

    found?.let { files ->
        AlertDialog(
            onDismissRequest = { found = null },
            title = { Text(stringResource(R.string.sound_bank_found_title, folderName)) },
            text = {
                if (files.isEmpty()) {
                    Text(stringResource(R.string.sound_bank_none_found))
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = FOUND_HEIGHT_DP.dp)) {
                        items(files) { file ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 56.dp)
                                    .clickable(role = Role.Button) { add(file) }
                                    .padding(vertical = 8.dp),
                            ) {
                                Text(file.name, style = MaterialTheme.typography.bodyLarge)
                                Text(Formatter.formatShortFileSize(context, file.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    found = null
                    explaining = true
                }) { Text(stringResource(R.string.sound_bank_other_folder)) }
            },
            dismissButton = { TextButton(onClick = { found = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    if (explaining) {
        AlertDialog(
            onDismissRequest = { explaining = false },
            title = { Text(stringResource(R.string.sound_bank_folder_title)) },
            text = { Text(stringResource(R.string.sound_bank_folder_text)) },
            confirmButton = {
                TextButton(onClick = {
                    explaining = false
                    runCatching { folderPicker.launch(folder) }
                }) { Text(stringResource(R.string.sound_bank_folder_choose)) }
            },
            dismissButton = { TextButton(onClick = { explaining = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    deleting?.let { bank ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.sound_bank_delete_title, bank.name)) },
            text = { Text(stringResource(R.string.sound_bank_delete_text)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        withContext(Dispatchers.IO) { SoundBank.delete(app, bank.id) }
                        if (chosen == bank.id) Settings.setSoundBank(app, SoundBank.BUILT_IN)
                        reload()
                    }
                }) { Text(stringResource(R.string.sound_bank_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
