package com.musigatto.fosscore.library

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The setlists of the library: named, ordered lists of its scores. */
class SetlistRepository(context: Context) : ScoreGroups {
    private val dao = AppDatabase.get(context.applicationContext).setlistDao()

    override val ordered = true

    override fun groups(): Flow<List<GroupSummary>> = dao.observeSummaries()

    override fun name(id: Long): Flow<String?> = dao.observe(id).map { it?.name }

    override fun entries(id: Long): Flow<List<GroupEntry>> = dao.observeEntries(id)

    override suspend fun create(name: String): Long = dao.insert(Setlist(name = name.trim(), createdAt = System.currentTimeMillis()))

    override suspend fun rename(id: Long, name: String) = dao.rename(id, name.trim())

    override suspend fun delete(id: Long) = dao.delete(id)

    override suspend fun add(id: Long, sheetId: Long) {
        dao.append(id, sheetId)
    }

    // a setlist's entry is its place
    override suspend fun remove(id: Long, entryKey: Long) = dao.removeItem(entryKey)

    // two concerts may share a name
    override suspend fun nameTaken(name: String, id: Long?): Boolean = false

    /** The places [entryKeys] of a setlist, in this order (after a drag). */
    override suspend fun reorder(entryKeys: List<Long>) = dao.reorder(entryKeys)
}
