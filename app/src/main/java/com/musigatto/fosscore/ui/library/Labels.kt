package com.musigatto.fosscore.ui.library

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.musigatto.fosscore.R
import com.musigatto.fosscore.library.Genre
import com.musigatto.fosscore.library.MusicalKey

/** The default genres' names in the app's language, by code (`CLASSICAL` → "Classique"). */
fun genreLabels(resources: Resources): Map<String, String> = Genre.entries.associate { it.name to resources.getString(it.label) }

private val NOTES = intArrayOf(
    R.string.key_note_c, R.string.key_note_d, R.string.key_note_e, R.string.key_note_f,
    R.string.key_note_g, R.string.key_note_a, R.string.key_note_b,
)

/** The 42 keys' names in the app's language: "Si♭ majeur", "B♭ major", "Fa♯ menor". */
fun keyLabels(resources: Resources): Map<MusicalKey, String> = MusicalKey.entries.associateWith { key ->
    resources.getString(if (key.minor) R.string.key_minor else R.string.key_major, resources.getString(NOTES[key.note]) + key.sign)
}

// the activity's resources: in the app's language, and the activity is recreated when it changes
@Composable
fun rememberGenreLabels(): Map<String, String> {
    val context = LocalContext.current
    @Suppress("LocalContextResourcesRead")
    return remember(context) { genreLabels(context.resources) }
}

@Composable
fun rememberKeyLabels(): Map<MusicalKey, String> {
    val context = LocalContext.current
    @Suppress("LocalContextResourcesRead")
    return remember(context) { keyLabels(context.resources) }
}

/** The key a name shown in the field stands for ([labels]: the keys' names in the app's language). */
fun keyOf(shown: String, labels: Map<MusicalKey, String>): MusicalKey? = labels.entries.firstOrNull { it.value == shown }?.key
