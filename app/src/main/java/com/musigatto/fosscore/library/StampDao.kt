package com.musigatto.fosscore.library

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface StampDao {
    @Query("SELECT * FROM stamps WHERE sheetHash = :hash ORDER BY page, id")
    fun observeBySheet(hash: String): Flow<List<Stamp>>

    @Insert
    suspend fun insert(stamp: Stamp): Long

    @Update
    suspend fun update(stamp: Stamp)

    @Delete
    suspend fun delete(stamp: Stamp)

    @Query("DELETE FROM stamps WHERE sheetHash = :hash")
    suspend fun deleteForSheet(hash: String)
}