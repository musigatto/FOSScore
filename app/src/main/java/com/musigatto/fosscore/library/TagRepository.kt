package com.musigatto.fosscore.library

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

/** The free tags as groups of scores: each score once, by title. */
class TagRepository(context: Context) : ScoreGroups {
    private val db = AppDatabase.get(context.applicationContext)
    private val dao = db.tagDao()

    override val ordered = false

    override fun groups(): Flow<List<GroupSummary>> = dao.observeSummaries()

    override fun name(id: Long): Flow<String?> = dao.observeName(id)

    override fun entries(id: Long): Flow<List<GroupEntry>> = dao.observeEntries(id)

    override suspend fun create(name: String): Long = dao.insert(Tag(name = tagName(name), kind = TagKind.FREE))

    override suspend fun rename(id: Long, name: String) = dao.rename(id, tagName(name))

    // its links go first: the foreign key would take them, but not where it is not enforced
    override suspend fun delete(id: Long) = db.withTransaction {
        dao.unlinkAll(id)
        dao.delete(id)
    }

    override suspend fun add(id: Long, sheetId: Long) = dao.link(listOf(SheetTag(sheetId = sheetId, tagId = id)))

    // a tag's entry is the score itself
    override suspend fun remove(id: Long, entryKey: Long) = dao.unlink(sheetId = entryKey, tagId = id)

    // one tag per name, whatever its spelling: "rock" cannot be made beside "Rock"; a genre "Rock"
    // is another kind, and may be
    override suspend fun nameTaken(name: String, id: Long?): Boolean =
        dao.free().any { it.id != id && sameTag(it.name, name) }

    override suspend fun reorder(entryKeys: List<Long>) = Unit
}
