package com.musigatto.fosscore.library

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** One stroke of ink. See [Stamp] for [sheetId], [measure] and [staff]. */
@Entity(
    tableName = "strokes",
    foreignKeys = [ForeignKey(entity = Sheet::class, parentColumns = ["id"], childColumns = ["sheetId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sheetId", "page")]
)
data class Stroke(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sheetId: Long,
    val page: Int,
    // points as 0..1, packed as "x,y|x,y|…"
    val points: String,
    // width as a share of the page height
    val width: Float,
    // ARGB ink color (null = theme color)
    val color: Int? = null,
    // on a MusicXML score, the measure and staff this ink hangs on; null = a place on the page
    val measure: Int? = null,
    val staff: Int? = null,
    // the word it was written as: the strokes of one word share an id and move together.
    // 0 = ink from before this column, grouped by distance instead (groupInk)
    val groupId: Long = 0,
)
