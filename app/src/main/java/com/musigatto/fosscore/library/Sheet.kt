package com.musigatto.fosscore.library

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.musigatto.fosscore.score.SheetFormat

/**
 * One score in the library.
 *
 * [id] is the key for everything that hangs off a score: annotations, keys, people, tags and
 * layouts. [hash] stays next to it as the natural key (UNIQUE): it dedupes on import and tells
 * whether the same file came back, but it changes when the file changes (re-exported, optimised,
 * one page more), so nothing points at it.
 *
 * The text columns (composer, genre, musicalKey, tags) are the v4 values, still written and read
 * by the library screen. The tables below are filled on import: the text goes in v6, once the new
 * ones are checked. See docs/feature-roadmap.md §3.8.
 */
@Entity(tableName = "sheets", indices = [Index("hash", unique = true)])
data class Sheet(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileName: String,
    val path: String,
    val hash: String,
    val title: String,
    val composer: String,
    val genre: String,
    val musicalKey: String,
    val tags: String,
    /** What the file holds; told by its content at import, not by its extension. */
    val format: SheetFormat? = null,
    /** Beats per minute, from a MusicXML tempo or an attached MIDI. */
    val tempoBpm: Double? = null,
    /** Denominator the tempo counts: 4 = a quarter note. */
    val beatUnit: Int? = null,
    /** As written ("4/4", "6/8"), not as an image. */
    val timeSignature: String? = null,
    val durationS: Double? = null,
    /** Last time the score was opened, and how many times: the library sorts by these. */
    val openedAt: Long? = null,
    val openCount: Int? = null,
    /** When the score entered the library. */
    val addedAt: Long? = null,
)

/**
 * A key of the score, in the order the score changes key: [position] 0 is the key it starts in.
 * [key] is a language-neutral code (letter + accidental + mode, e.g. `G`, `Gm`, `Ab`, `Abm`), never
 * a text in one language: the user's notation (letters or note names) only matters when drawing.
 */
@Entity(
    tableName = "sheet_keys",
    primaryKeys = ["sheetId", "position"],
    foreignKeys = [ForeignKey(entity = Sheet::class, parentColumns = ["id"], childColumns = ["sheetId"], onDelete = ForeignKey.CASCADE)]
)
data class SheetKey(
    val sheetId: Long,
    val position: Int,
    val key: String,
)

/** Someone who worked on the score. [sortName] is how it sorts: "Beethoven, Ludwig van". */
@Entity(tableName = "people", indices = [Index("name", unique = true)])
data class Person(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sortName: String,
)

/** What [personId] did on [sheetId]: composer, lyricist, arranger, performer. */
@Entity(
    tableName = "sheet_people",
    primaryKeys = ["sheetId", "personId", "role"],
    foreignKeys = [
        ForeignKey(entity = Sheet::class, parentColumns = ["id"], childColumns = ["sheetId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Person::class, parentColumns = ["id"], childColumns = ["personId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("personId")]
)
data class SheetPerson(
    val sheetId: Long,
    val personId: Long,
    val role: String,
)

/** A genre is a tag too: one mechanism, and the genre filter is [kind]. */
enum class TagKind { GENRE, FREE }

@Entity(tableName = "tags", indices = [Index("name", unique = true)])
data class Tag(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val color: Int? = null,
    val kind: TagKind = TagKind.FREE,
)

@Entity(
    tableName = "sheet_tags",
    primaryKeys = ["sheetId", "tagId"],
    foreignKeys = [
        ForeignKey(entity = Sheet::class, parentColumns = ["id"], childColumns = ["sheetId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Tag::class, parentColumns = ["id"], childColumns = ["tagId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("tagId")]
)
data class SheetTag(
    val sheetId: Long,
    val tagId: Long,
)

/**
 * How a score is laid out in one orientation: a score is shown differently in portrait and in
 * landscape, so one row each (`portrait`, `landscape`). No named configurations yet: they need
 * their own screen (roadmap 3.4), and `name` is an ALTER TABLE away.
 *
 * Empty until the display options move here from the preferences: they are read and written
 * synchronously today, and the move is its own step.
 */
@Entity(
    tableName = "sheet_layouts",
    primaryKeys = ["sheetId", "orientation"],
    foreignKeys = [ForeignKey(entity = Sheet::class, parentColumns = ["id"], childColumns = ["sheetId"], onDelete = ForeignKey.CASCADE)]
)
data class SheetLayout(
    val sheetId: Long,
    val orientation: String,
    val zoom: Float = 1f,
    val twoUp: Boolean = false,
    /** The lyrics placement of the score, by name; null = as the file has it. */
    val lyrics: String? = null,
)
