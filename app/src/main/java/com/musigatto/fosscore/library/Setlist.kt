package com.musigatto.fosscore.library

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A named, ordered list of scores: a concert, the pieces to work on this week. */
@Entity(tableName = "setlists")
data class Setlist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
)

/**
 * One place in a setlist. A score can be in the same setlist more than once (a piece played
 * twice in a concert), so a place has an [id] of its own instead of the pair (setlist, sheet).
 * [position] orders the places; the numbers need not follow each other.
 */
@Entity(
    tableName = "setlist_items",
    foreignKeys = [
        ForeignKey(entity = Setlist::class, parentColumns = ["id"], childColumns = ["setlistId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Sheet::class, parentColumns = ["id"], childColumns = ["sheetId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("setlistId", "position"), Index("sheetId")]
)
data class SetlistItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val setlistId: Long,
    val position: Int,
    val sheetId: Long,
)
