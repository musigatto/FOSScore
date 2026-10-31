package com.musigatto.fosscore.library

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface StampDao {
    // one page only: see StrokeDao.observePage
    @Query("SELECT * FROM stamps WHERE sheetId = :sheetId AND page = :page ORDER BY id")
    fun observePage(sheetId: Long, page: Int): Flow<List<Stamp>>

    @Insert
    suspend fun insert(stamp: Stamp): Long

    @Insert
    suspend fun insertAll(stamps: List<Stamp>)

    @Update
    suspend fun update(stamp: Stamp)

    @Delete
    suspend fun delete(stamp: Stamp)

    @Delete
    suspend fun deleteAll(stamps: List<Stamp>)

    @Query("DELETE FROM stamps WHERE sheetId = :sheetId")
    suspend fun deleteForSheet(sheetId: Long)

    @Query("DELETE FROM stamps WHERE sheetId = :sheetId AND page = :page")
    suspend fun deletePage(sheetId: Long, page: Int)
}
