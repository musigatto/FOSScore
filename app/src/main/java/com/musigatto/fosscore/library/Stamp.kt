package com.musigatto.fosscore.library

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A symbol (or, later, a typed text) written on a page. It hangs on the sheet's [sheetId], and on
 * [page] of the file: a moved or re-exported copy of the file keeps its marks as long as it is
 * replaced on the same sheet.
 */
@Entity(
    tableName = "stamps",
    foreignKeys = [ForeignKey(entity = Sheet::class, parentColumns = ["id"], childColumns = ["sheetId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sheetId", "page")]
)
data class Stamp(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sheetId: Long,
    val page: Int,
    val symbol: String,
    val x: Float,
    val y: Float,
    val size: Float,
    // ARGB tint color (null = theme color), applied when the overlay is drawn
    val color: Int? = null,
    // typed text (#35): symbol = "TEXT" and this is what it says. Null = a symbol.
    val text: String? = null,
    val font: String? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    // on a MusicXML score, the measure (its number in the file) and the staff the mark hangs on;
    // null = x/y are a place on the page, as on a PDF
    val measure: Int? = null,
    val staff: Int? = null,
)
