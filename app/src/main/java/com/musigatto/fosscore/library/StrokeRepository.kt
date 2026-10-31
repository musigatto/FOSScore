package com.musigatto.fosscore.library

import android.content.Context
import kotlinx.coroutines.flow.Flow

class StrokeRepository(context: Context) {
    private val dao = AppDatabase.get(context).strokeDao()

    fun observe(sheetId: Long, page: Int): Flow<List<Stroke>> = dao.observePage(sheetId, page)

    suspend fun insert(stroke: Stroke): Long = dao.insert(stroke)

    suspend fun update(stroke: Stroke) = dao.update(stroke)

    suspend fun delete(stroke: Stroke) = dao.delete(stroke)

    suspend fun deleteAll(strokes: List<Stroke>) = dao.deleteAll(strokes)

    // the eraser cuts one stroke into pieces: the survivors go back in one by one
    suspend fun insertAll(strokes: List<Stroke>) = dao.insertAll(strokes)

    suspend fun listBySheet(sheetId: Long): List<Stroke> = dao.listBySheet(sheetId)

    // puts a page back from an undo snapshot: drops what is there, inserts the snapshot
    suspend fun restorePage(sheetId: Long, page: Int, strokes: List<Stroke>) {
        dao.deletePage(sheetId, page)
        dao.insertAll(strokes)
    }
}
