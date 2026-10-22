package com.musigatto.fosscore.ui.library

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerState
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.musigatto.fosscore.R
import com.musigatto.fosscore.library.AppDatabase
import com.musigatto.fosscore.library.BackupCheck
import com.musigatto.fosscore.library.BackupManifest
import com.musigatto.fosscore.library.CheckItem
import com.musigatto.fosscore.library.CheckStatus
import com.musigatto.fosscore.library.LibraryBackup
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The library's side menu, opened by the ☰ button of the library header or by swiping from the
 * left edge. It holds what concerns the whole app: today the export and import of the library,
 * later the settings and "About". [onMessage] shows a short result to the user.
 */
@Composable
fun LibraryMenuDrawer(drawerState: DrawerState, onMessage: (String) -> Unit, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var inspection by remember { mutableStateOf<LibraryBackup.Inspection?>(null) }
    val backup = remember { LibraryBackup(app) }

    val failed = stringResource(R.string.backup_failed)

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    app.contentResolver.openOutputStream(uri)!!.use { backup.export(AppDatabase.get(app), it) }
                }
            }
            busy = false
            onMessage(result.fold({ app.resources.getQuantityString(R.plurals.backup_exported, it.sheets, it.sheets) }, { failed }))
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { app.contentResolver.openInputStream(uri)!!.use { backup.inspect(it) } }
            }
            busy = false
            result.fold({ inspection = it }, { onMessage(failed) })
        }
    }

    // Closes the drawer, then runs [action] unless an export or import is already running.
    fun fromMenu(action: () -> Unit) {
        scope.launch { drawerState.close() }
        if (!busy) action()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp)
                )
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.backup_export)) },
                    selected = false,
                    onClick = {
                        fromMenu {
                            val day = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
                            // No file picker on some stripped-down devices.
                            runCatching { exportLauncher.launch("fosscore-library-$day.zip") }.onFailure { onMessage(failed) }
                        }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.backup_import)) },
                    selected = false,
                    onClick = {
                        fromMenu {
                            // Some file managers report .zip files as generic binary data.
                            runCatching {
                                importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                            }.onFailure { onMessage(failed) }
                        }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
        },
        content = content
    )

    inspection?.let { current ->
        ImportCheckDialog(
            inspection = current,
            onCancel = {
                inspection = null
                scope.launch(Dispatchers.IO) { backup.discard() }
            },
            onImport = {
                inspection = null
                scope.launch {
                    val staged = withContext(Dispatchers.IO) { runCatching { backup.stage(current) }.isSuccess }
                    if (staged) restart(context as? Activity) else onMessage(failed)
                }
            }
        )
    }
}

@Composable
private fun ImportCheckDialog(inspection: LibraryBackup.Inspection, onCancel: () -> Unit, onImport: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.backup_import_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (check in inspection.checks) {
                    Row {
                        Text(symbol(check.status), color = color(check.status), modifier = Modifier.width(28.dp))
                        Text(describe(check, inspection.manifest), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (inspection.canImport) {
                    Text(stringResource(R.string.backup_import_replaces), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            if (inspection.canImport) {
                TextButton(onClick = onImport) { Text(stringResource(R.string.backup_import_confirm)) }
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.backup_cancel)) } }
    )
}

private fun symbol(status: CheckStatus) = when (status) {
    CheckStatus.OK -> "✓"
    CheckStatus.WARN -> "⚠"
    CheckStatus.ERROR -> "✗"
}

// Same meaning as AndroidAPS's import: green, amber, red.
private fun color(status: CheckStatus) = when (status) {
    CheckStatus.OK -> Color(0xFF2E7D32)
    CheckStatus.WARN -> Color(0xFFEF8F00)
    CheckStatus.ERROR -> Color(0xFFC62828)
}

@Composable
private fun describe(check: BackupCheck, manifest: BackupManifest?): String {
    val m = manifest
    return when (check.item) {
        CheckItem.FORMAT ->
            if (check.status == CheckStatus.OK) stringResource(R.string.backup_check_format_ok)
            else stringResource(R.string.backup_check_format_error)
        CheckItem.DATABASE -> when (check.status) {
            CheckStatus.OK -> stringResource(R.string.backup_check_db_ok, m!!.dbVersion)
            CheckStatus.WARN -> stringResource(R.string.backup_check_db_older, m!!.dbVersion, AppDatabase.VERSION)
            CheckStatus.ERROR -> stringResource(R.string.backup_check_db_newer, m!!.dbVersion, AppDatabase.VERSION)
        }
        CheckItem.CONTENT ->
            if (check.status == CheckStatus.OK) stringResource(R.string.backup_check_content_ok, m!!.sheets, m.stamps, m.strokes)
            else stringResource(R.string.backup_check_content_error)
        CheckItem.AGE -> {
            val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(m!!.createdAt))
            if (check.status == CheckStatus.OK) stringResource(R.string.backup_check_age_ok, date)
            else stringResource(R.string.backup_check_age_old, date)
        }
        CheckItem.DEVICE ->
            if (check.status == CheckStatus.OK) stringResource(R.string.backup_check_device_ok)
            else stringResource(R.string.backup_check_device_other, m!!.device)
        CheckItem.FILES ->
            if (check.status == CheckStatus.OK) stringResource(R.string.backup_check_files_ok)
            else stringResource(R.string.backup_check_files_missing, m!!.missingFiles)
    }
}

/**
 * Restarts the app so the staged import is applied before the database opens. The library must
 * not be used in between: the process ends right away.
 */
private fun restart(activity: Activity?) {
    if (activity != null) {
        activity.packageManager.getLaunchIntentForPackage(activity.packageName)?.let {
            activity.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
        activity.finishAffinity()
    }
    Runtime.getRuntime().exit(0)
}
