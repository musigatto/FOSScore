package com.musigatto.fosscore.library

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class SetlistDao {
    @Query(
        "SELECT s.id AS id, s.name AS name, COUNT(i.id) AS size FROM setlists s " +
            "LEFT JOIN setlist_items i ON i.setlistId = s.id " +
            "GROUP BY s.id ORDER BY s.name COLLATE NOCASE ASC, s.id ASC"
    )
    abstract fun observeSummaries(): Flow<List<GroupSummary>>

    @Query("SELECT * FROM setlists WHERE id = :id")
    abstract fun observe(id: Long): Flow<Setlist?>

    @Query(
        "SELECT i.id AS entryKey, sh.* FROM setlist_items i " +
            "JOIN sheets sh ON sh.id = i.sheetId " +
            "WHERE i.setlistId = :setlistId ORDER BY i.position ASC, i.id ASC"
    )
    abstract fun observeEntries(setlistId: Long): Flow<List<GroupEntry>>

    @Insert
    abstract suspend fun insert(setlist: Setlist): Long

    @Query("UPDATE setlists SET name = :name WHERE id = :id")
    abstract suspend fun rename(id: Long, name: String)

    // its places go with it (ON DELETE CASCADE); the scores stay in the library
    @Query("DELETE FROM setlists WHERE id = :id")
    abstract suspend fun delete(id: Long)

    @Insert
    protected abstract suspend fun insertItem(item: SetlistItem): Long

    @Query("SELECT COALESCE(MAX(position) + 1, 0) FROM setlist_items WHERE setlistId = :setlistId")
    protected abstract suspend fun nextPosition(setlistId: Long): Int

    @Query("UPDATE setlist_items SET position = :position WHERE id = :itemId")
    protected abstract suspend fun setPosition(itemId: Long, position: Int)

    @Query("DELETE FROM setlist_items WHERE id = :itemId")
    abstract suspend fun removeItem(itemId: Long)

    /** Puts [sheetId] at the end of the setlist, even if it is already in it. */
    @Transaction
    open suspend fun append(setlistId: Long, sheetId: Long): Long =
        insertItem(SetlistItem(setlistId = setlistId, position = nextPosition(setlistId), sheetId = sheetId))

    /** The places [itemIds], in this order. */
    @Transaction
    open suspend fun reorder(itemIds: List<Long>) {
        itemIds.forEachIndexed { position, id -> setPosition(id, position) }
    }
}
