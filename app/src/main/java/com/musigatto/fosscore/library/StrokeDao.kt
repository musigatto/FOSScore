package com.musigatto.fosscore.library

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface StrokeDao {
    // one page only: the viewer draws one page, and re-reading the whole score on every stroke
    // made the cost of a stroke grow with the score (index: sheetId, page)
    @Query("SELECT * FROM strokes WHERE sheetId = :sheetId AND page = :page ORDER BY id")
    fun observePage(sheetId: Long, page: Int): Flow<List<Stroke>>

    @Query("SELECT * FROM strokes WHERE sheetId = :sheetId ORDER BY page, id")
    suspend fun listBySheet(sheetId: Long): List<Stroke>

    @Insert
    suspend fun insert(stroke: Stroke): Long

    @Insert
    suspend fun insertAll(strokes: List<Stroke>)

    @Update
    suspend fun update(stroke: Stroke)

    @Delete
    suspend fun delete(stroke: Stroke)

    @Delete
    suspend fun deleteAll(strokes: List<Stroke>)

    @Query("DELETE FROM strokes WHERE sheetId = :sheetId")
    suspend fun deleteForSheet(sheetId: Long)

    @Query("DELETE FROM strokes WHERE sheetId = :sheetId AND page = :page")
    suspend fun deletePage(sheetId: Long, page: Int)
}
