package com.musigatto.fosscore.library

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "stamps",
    indices = [Index("sheetHash", "page")]
)
data class Stamp(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sheetHash: String,
    val page: Int,
    val symbol: String,
    val x: Float,
    val y: Float,
    val size: Float
)