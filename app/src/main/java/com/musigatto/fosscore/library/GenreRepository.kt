package com.musigatto.fosscore.library

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** A genre's name as shown: a default genre's in the app's language ([labels], by code), else as written. */
fun Tag.genreLabel(labels: Map<String, String>): String = code?.let { labels[it] } ?: name

/**
 * The genres as groups of scores, each score once, by title. [labels] are the default genres'
 * names in the app's language, by code (the activity's resources; it is recreated on a language
 * change).
 */
class GenreRepository(context: Context, private val labels: Map<String, String>) : ScoreGroups {
    private val db = AppDatabase.get(context.applicationContext)
    private val dao = db.tagDao()

    override val ordered = false

    override fun groups(): Flow<List<GroupSummary>> = dao.observeGenreCounts().map { rows ->
        rows.map { GroupSummary(it.id, it.code?.let { code -> labels[code] } ?: it.name, it.size) }
    }

    override fun name(id: Long): Flow<String?> = dao.observe(id).map { it?.genreLabel(labels) }

    override fun entries(id: Long): Flow<List<GroupEntry>> = dao.observeEntries(id)

    override suspend fun create(name: String): Long = dao.insert(Tag(name = tagName(name), kind = TagKind.GENRE))

    override suspend fun rename(id: Long, name: String) = dao.renameGenre(id, tagName(name))

    override suspend fun delete(id: Long) = db.withTransaction {
        dao.unlinkAll(id)
        dao.delete(id)
    }

    override suspend fun add(id: Long, sheetId: Long) = dao.link(listOf(SheetTag(sheetId = sheetId, tagId = id)))

    override suspend fun remove(id: Long, entryKey: Long) = dao.unlink(sheetId = entryKey, tagId = id)

    // taken as shown (a default in this language) or as stored (its English name, the column
    // being unique within the genres)
    override suspend fun nameTaken(name: String, id: Long?): Boolean =
        dao.genres().any { it.id != id && (sameTag(it.genreLabel(labels), name) || sameTag(it.name, name)) }

    override suspend fun reorder(entryKeys: List<Long>) = Unit
}
