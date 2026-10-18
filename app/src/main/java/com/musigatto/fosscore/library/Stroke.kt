package com.musigatto.fosscore.library

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "strokes",
    indices = [Index("sheetHash", "page")]
)
data class Stroke(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sheetHash: String,
    val page: Int,
    // points as 0..1, packed as "x,y|x,y|…"
    val points: String,
    // width as a share of the page height
    val width: Float,
    // ARGB ink color (null = theme color)
    val color: Int? = null
)