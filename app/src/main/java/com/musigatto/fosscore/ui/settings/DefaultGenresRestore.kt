package com.musigatto.fosscore.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.musigatto.fosscore.FOSScoreApp
import com.musigatto.fosscore.R
import com.musigatto.fosscore.ui.library.rememberGenreLabels
import kotlinx.coroutines.launch

/**
 * Brings back the default genres the user deleted: nothing else changes (their own genres stay),
 * so it asks for no confirmation. Says how many came back.
 */
@Composable
internal fun DefaultGenresRestore() {
    val context = LocalContext.current
    val repository = (context.applicationContext as FOSScoreApp).repository
    val labels = rememberGenreLabels()
    val scope = rememberCoroutineScope()
    var restored by remember { mutableStateOf<Int?>(null) }

    Text(stringResource(R.string.genres_restore_summary), style = MaterialTheme.typography.bodyMedium)
    OutlinedButton(onClick = { scope.launch { restored = repository.restoreDefaultGenres(labels) } }) {
        Text(stringResource(R.string.genres_restore))
    }
    restored?.let { count ->
        Text(
            if (count == 0) stringResource(R.string.genres_restore_none) else stringResource(R.string.genres_restore_done, count),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
