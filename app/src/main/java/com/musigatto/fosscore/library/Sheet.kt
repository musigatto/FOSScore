package com.musigatto.fosscore.library

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sheets")
data class Sheet(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileName: String,
    val path: String,
    val hash: String,
    val title: String,
    val composer: String,
    val genre: String,
    val musicalKey: String,
    val tags: String
)