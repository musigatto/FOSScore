package com.musigatto.fosscore.library

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

/**
 * The people as groups of scores. With a [role], a person's scores in that role only (as
 * author/composer, as performer): what adding and taking a score out act on. Without, a person's
 * scores in any role, each once: the people screen's list. Both read the same people: a singer who
 * writes their songs is one person.
 */
class PersonRepository(context: Context, private val role: PersonRole? = null) : ScoreGroups {
    private val db = AppDatabase.get(context.applicationContext)
    private val dao = db.personDao()

    override val ordered = false

    override fun groups(): Flow<List<GroupSummary>> = dao.observeSummaries()

    override fun name(id: Long): Flow<String?> = dao.observeName(id)

    override fun entries(id: Long): Flow<List<GroupEntry>> =
        role?.let { dao.observeEntries(id, it.name) } ?: dao.observeAllEntries(id)

    override suspend fun create(name: String): Long = tagName(name).let { dao.insert(Person(name = it, sortName = it)) }

    override suspend fun rename(id: Long, name: String) = dao.rename(id, tagName(name))

    // the person goes from every score, in every role; their links go first, the foreign key
    // would take them but not where it is not enforced
    override suspend fun delete(id: Long) = db.withTransaction {
        dao.unlinkAll(id)
        dao.delete(id)
    }

    // in no role, a score cannot be added: the screen adds in the role it shows
    override suspend fun add(id: Long, sheetId: Long) {
        role?.let { dao.link(listOf(SheetPerson(sheetId = sheetId, personId = id, role = it.name))) }
    }

    // a person's entry is the score itself; in a role, their other role on it stays
    override suspend fun remove(id: Long, entryKey: Long) {
        role?.let { dao.unlink(sheetId = entryKey, personId = id, role = it.name) }
            ?: dao.unlinkEveryRole(sheetId = entryKey, personId = id)
    }

    // one person per name, whatever its spelling
    override suspend fun nameTaken(name: String, id: Long?): Boolean =
        dao.all().any { it.id != id && sameTag(it.name, name) }

    override suspend fun reorder(entryKeys: List<Long>) = Unit

    override val canMerge = true

    // [from]'s scores go to [into] in their roles (a score both were on in one role, once); [from]
    // goes before [into] takes the name, which may be [from]'s (the names are unique)
    override suspend fun merge(into: Long, from: Long, name: String) {
        if (into == from) return
        db.withTransaction {
            dao.relink(from = from, into = into)
            dao.unlinkAll(from)
            dao.delete(from)
            dao.rename(into, tagName(name))
        }
    }
}
