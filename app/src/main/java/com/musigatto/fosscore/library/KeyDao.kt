package com.musigatto.fosscore.library

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** The keys of the scores, each score's in its order ([SheetKey.position] 0: the key it starts in). */
@Dao
interface KeyDao {
    @Query("SELECT * FROM sheet_keys ORDER BY sheetId, position")
    fun observeAll(): Flow<List<SheetKey>>

    @Query("SELECT * FROM sheet_keys WHERE sheetId = :sheetId ORDER BY position")
    suspend fun keysOf(sheetId: Long): List<SheetKey>

    @Query("DELETE FROM sheet_keys WHERE sheetId = :sheetId")
    suspend fun clear(sheetId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(keys: List<SheetKey>)
}
