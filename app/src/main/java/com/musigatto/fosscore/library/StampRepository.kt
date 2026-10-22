package com.musigatto.fosscore.library

import android.content.Context
import kotlinx.coroutines.flow.Flow

class StampRepository(context: Context) {
    private val dao = AppDatabase.get(context).stampDao()

    fun observe(hash: String, page: Int): Flow<List<Stamp>> = dao.observePage(hash, page)

    suspend fun insert(stamp: Stamp): Long = dao.insert(stamp)

    suspend fun update(stamp: Stamp) = dao.update(stamp)

    suspend fun delete(stamp: Stamp) = dao.delete(stamp)

    suspend fun deleteAll(stamps: List<Stamp>) = dao.deleteAll(stamps)

    // puts a page back from an undo snapshot: drops what is there, inserts the snapshot
    suspend fun restorePage(hash: String, page: Int, stamps: List<Stamp>) {
        dao.deletePage(hash, page)
        dao.insertAll(stamps)
    }
}