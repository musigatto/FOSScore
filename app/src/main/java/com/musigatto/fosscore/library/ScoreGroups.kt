package com.musigatto.fosscore.library

import androidx.room.Embedded
import kotlinx.coroutines.flow.Flow

/**
 * The named groups of scores the app shows and edits the same way: the setlists, the tags, the
 * people, the genres. One screen serves them all (ScoreGroupsScreen); a person's scores show by role.
 */
enum class GroupKind { SETLIST, TAG, PERSON, GENRE }

/** A group as the list of groups shows it: its name and how many scores it holds. */
data class GroupSummary(
    val id: Long,
    val name: String,
    val size: Int,
)

/**
 * A score in a group. [entryKey] tells it from the group's other entries: a setlist's place (the
 * same score may be in a setlist twice), the score itself for a tag.
 */
data class GroupEntry(
    val entryKey: Long,
    @Embedded val sheet: Sheet,
)

/** What the groups screen reads and changes, whatever the kind of group. */
interface ScoreGroups {
    /**
     * True: the scores keep the order they were put in, and a score may come more than once (a
     * setlist). False: each score once, shown and played by title (a tag).
     */
    val ordered: Boolean

    fun groups(): Flow<List<GroupSummary>>

    fun name(id: Long): Flow<String?>

    /** The group's scores: in its order when [ordered], else in no particular order. */
    fun entries(id: Long): Flow<List<GroupEntry>>

    suspend fun create(name: String): Long

    suspend fun rename(id: Long, name: String)

    /** The group goes; its scores stay in the library. */
    suspend fun delete(id: Long)

    /** Puts [sheetId] in the group [id]: at the end of an ordered group, even again; once in the others. */
    suspend fun add(id: Long, sheetId: Long)

    /** Takes the entry [entryKey] out of the group [id]. */
    suspend fun remove(id: Long, entryKey: Long)

    /** Whether another group than [id] (null: a group still to create) already has [name]. */
    suspend fun nameTaken(name: String, id: Long?): Boolean

    /** The entries [entryKeys] of an ordered group, in this order. */
    suspend fun reorder(entryKeys: List<Long>)

    /** Whether two groups can become one ([merge]): two spellings of one person. */
    val canMerge: Boolean get() = false

    /**
     * The group [from] joins [into], which takes every score of both and the name [name]; [from]
     * goes. Does nothing where [canMerge] is false.
     */
    suspend fun merge(into: Long, from: Long, name: String) = Unit
}

/** Moves the entry at [from] to [to] in [entries], the ordered group as shown. */
suspend fun ScoreGroups.move(entries: List<GroupEntry>, from: Int, to: Int) {
    val keys = entries.map { it.entryKey }
    val order = keys.moved(from, to)
    if (order !== keys) reorder(order)
}
