package com.musigatto.fosscore.ui.library

import java.text.Collator
import java.text.Normalizer

/**
 * The letter [name] is filed under: its first letter, without accent ("Élise" → "E"), skipping
 * what comes before it ("'Round Midnight" → "R"). "#" when it starts with a digit or has no letter.
 */
fun indexLetter(name: String): String {
    val first = name.firstOrNull { it.isLetterOrDigit() } ?: return OTHERS
    val base = Normalizer.normalize(first.toString(), Normalizer.Form.NFD).first()
    return if (base.isLetter()) base.uppercaseChar().toString() else OTHERS
}

/** The group of what starts with no letter: first, before A. */
const val OTHERS = "#"

/** A row of a list grouped by letter: the narrow bar of a letter, or an item under it. */
sealed interface IndexedRow<out T> {
    data class Letter(val letter: String) : IndexedRow<Nothing>
    data class Item<T>(val item: T) : IndexedRow<T>
}

/**
 * A list sorted by name and grouped by letter, as the library, the genres or the artists show it:
 * [rows] to show, [letters] for the index on the side (only the letters that have items).
 */
class AlphabetIndex<T>(val rows: List<IndexedRow<T>>, val letters: List<String>) {
    private val rowOfLetter = rows.withIndex()
        .mapNotNull { (at, row) -> (row as? IndexedRow.Letter)?.let { it.letter to at } }
        .toMap()

    /** The row of [letter]'s bar, where the index jumps to. */
    fun rowOf(letter: String): Int? = rowOfLetter[letter]
}

fun <T> alphabetIndex(items: List<T>, collator: Collator = nameCollator(), name: (T) -> String): AlphabetIndex<T> {
    val byLetter = items.groupBy { indexLetter(name(it)) }
    val letters = byLetter.keys.sortedWith { a, b ->
        when {
            a == b -> 0
            a == OTHERS -> -1
            b == OTHERS -> 1
            else -> collator.compare(a, b)
        }
    }
    val rows = letters.flatMap { letter ->
        listOf(IndexedRow.Letter(letter)) +
            byLetter.getValue(letter).sortedWith { a, b -> collator.compare(name(a), name(b)) }.map { IndexedRow.Item(it) }
    }
    return AlphabetIndex(rows, letters)
}

/** Names sorted as a reader expects: case is ignored, and "écho" sorts next to "Echo", not after "Z". */
fun nameCollator(): Collator = Collator.getInstance().apply { strength = Collator.SECONDARY }


/**
 * [items] in the order an IndexedList shows them: "#" first, then letter by letter, by name in
 * each. A tag's scores play in this order, so the next score is the one under it on screen.
 */
fun <T> alphabetical(items: List<T>, collator: Collator = nameCollator(), name: (T) -> String): List<T> =
    alphabetIndex(items, collator, name).rows.mapNotNull { (it as? IndexedRow.Item<T>)?.item }
