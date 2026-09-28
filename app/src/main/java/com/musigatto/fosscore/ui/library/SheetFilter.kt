package com.musigatto.fosscore.ui.library

import com.musigatto.fosscore.library.Sheet
import java.text.Normalizer

fun filterSheets(sheets: List<Sheet>, query: String): List<Sheet> {
    val tokens = normalize(query).split(' ').filter { it.isNotBlank() }
    if (tokens.isEmpty()) return sheets
    return sheets.filter { sheet ->
        val haystack = normalize(
            listOf(sheet.title, sheet.composer, sheet.genre, sheet.musicalKey, sheet.tags)
                .joinToString(" ")
        )
        tokens.all { haystack.contains(it) }
    }
}

private fun normalize(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase()