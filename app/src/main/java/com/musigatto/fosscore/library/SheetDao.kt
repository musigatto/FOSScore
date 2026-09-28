package com.musigatto.fosscore.library

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SheetDao {
    @Insert
    suspend fun insert(sheet: Sheet): Long

    @Update
    suspend fun update(sheet: Sheet)

    @Delete
    suspend fun delete(sheet: Sheet)

    @Query("SELECT * FROM sheets WHERE hash = :hash LIMIT 1")
    suspend fun byHash(hash: String): Sheet?

    @Query("SELECT * FROM sheets ORDER BY title ASC, id ASC")
    fun observeAll(): Flow<List<Sheet>>
}