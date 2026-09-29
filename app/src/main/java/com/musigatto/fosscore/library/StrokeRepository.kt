package com.musigatto.fosscore.library

import android.content.Context
import kotlinx.coroutines.flow.Flow

class StrokeRepository(context: Context) {
    private val dao = AppDatabase.get(context).strokeDao()

    fun observe(hash: String): Flow<List<Stroke>> = dao.observeBySheet(hash)

    suspend fun insert(stroke: Stroke): Long = dao.insert(stroke)

    suspend fun update(stroke: Stroke) = dao.update(stroke)

    suspend fun delete(stroke: Stroke) = dao.delete(stroke)

    suspend fun deleteAll(strokes: List<Stroke>) = dao.deleteAll(strokes)

    // la goma parte un trazo en varios: se reinsertan los tramos supervivientes
    suspend fun insertAll(strokes: List<Stroke>) = dao.insertAll(strokes)

    // restaura una página a un snapshot (undo): borra lo actual e inserta el snapshot
    suspend fun restorePage(hash: String, page: Int, strokes: List<Stroke>) {
        dao.deletePage(hash, page)
        dao.insertAll(strokes)
    }
}