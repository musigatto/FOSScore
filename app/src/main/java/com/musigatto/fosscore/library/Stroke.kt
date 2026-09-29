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
    // puntos normalizados (0..1) codificados como "x,y|x,y|…"
    val points: String,
    // ancho en fracción del alto de página
    val width: Float,
    // ARGB del color de tinta (null = color del tema)
    val color: Int? = null
)