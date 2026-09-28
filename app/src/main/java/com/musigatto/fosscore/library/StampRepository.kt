package com.musigatto.fosscore.library

import android.content.Context
import kotlinx.coroutines.flow.Flow

class StampRepository(context: Context) {
    private val dao = AppDatabase.get(context).stampDao()

    fun observe(hash: String): Flow<List<Stamp>> = dao.observeBySheet(hash)

    suspend fun insert(stamp: Stamp): Long = dao.insert(stamp)

    suspend fun update(stamp: Stamp) = dao.update(stamp)

    suspend fun delete(stamp: Stamp) = dao.delete(stamp)
}