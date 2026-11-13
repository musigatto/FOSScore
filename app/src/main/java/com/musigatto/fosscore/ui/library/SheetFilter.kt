package com.musigatto.fosscore.ui.library

import com.musigatto.fosscore.library.Credits
import com.musigatto.fosscore.library.Sheet
import com.musigatto.fosscore.library.normalize

/**
 * The scores whose details hold every word of [query]: title and comment, and by score id the
 * [words] its tables give it (tags, genres and keys, as shown) and the people it is by ([credits]).
 */
fun filterSheets(
    sheets: List<Sheet>,
    query: String,
    words: Map<Long, List<String>> = emptyMap(),
    credits: Map<Long, Credits> = emptyMap(),
): List<Sheet> {
    val tokens = normalize(query).split(' ').filter { it.isNotBlank() }
    if (tokens.isEmpty()) return sheets
    return sheets.filter { sheet ->
        val people = credits[sheet.id]?.let { it.composers + it.performers }.orEmpty()
        val haystack = normalize(
            (listOf(sheet.title, sheet.comment) + words[sheet.id].orEmpty() + people).joinToString(" ")
        )
        tokens.all { haystack.contains(it) }
    }
}

/** The words of each score, by id, from several tables ([parts]: tags, genres, keys…). */
fun wordsBySheet(vararg parts: Map<Long, List<String>>): Map<Long, List<String>> =
    parts.flatMap { it.entries }.groupBy({ it.key }, { it.value }).mapValues { (_, lists) -> lists.flatten() }
