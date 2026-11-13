package com.musigatto.fosscore.library

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import com.musigatto.fosscore.R
import com.musigatto.fosscore.musicxml.MusicXmlScore
import com.musigatto.fosscore.score.ScorePrecompute
import com.musigatto.fosscore.score.SheetFormat
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

sealed class ImportResult {
    data class Ok(val sheet: Sheet) : ImportResult()
    data object Duplicate : ImportResult()
    data class Error(val messageRes: Int) : ImportResult()
}

class LibraryRepository(context: Context) {
    private val appContext = context.applicationContext
    private val db = AppDatabase.get(appContext)
    private val dao = db.sheetDao()
    private val stampDao = db.stampDao()
    private val strokeDao = db.strokeDao()
    private val tagDao = db.tagDao()
    private val personDao = db.personDao()
    private val keyDao = db.keyDao()

    fun sheets(): Flow<List<Sheet>> = dao.observeAll()

    /** Every tag of the library, used or not: what the tag field offers. */
    fun tags(): Flow<List<Tag>> = tagDao.observeFree()

    /** The tags of each score, by score id, in name order: the search looks in them. */
    fun tagsBySheet(): Flow<Map<Long, List<String>>> =
        tagDao.observeSheetTags().map { rows -> rows.groupBy({ it.sheetId }, { it.name }) }

    suspend fun tagNamesOf(sheetId: Long): List<String> = tagDao.tagsOf(sheetId).map { it.name }

    /** [sheetId] gets exactly [names]: a tag already in the library is reused, a new one is created. */
    suspend fun setTags(sheetId: Long, names: List<String>) = db.withTransaction {
        val ids = tagIdsFor(names)
        tagDao.unlinkFree(sheetId)
        tagDao.link(ids.map { SheetTag(sheetId, it) })
    }

    /** [names] are added to each of [sheetIds], next to the tags it already has. */
    suspend fun addTags(sheetIds: List<Long>, names: List<String>) = db.withTransaction {
        val ids = tagIdsFor(names)
        tagDao.link(sheetIds.flatMap { sheetId -> ids.map { SheetTag(sheetId, it) } })
    }

    // one id per tag named, an existing spelling ("Rock" for "rock") before a new row
    private suspend fun tagIdsFor(names: List<String>): List<Long> {
        val known = tagDao.free()
        return distinctTagNames(names).map { name ->
            known.firstOrNull { sameTag(it.name, name) }?.id ?: tagDao.insert(Tag(name = name, kind = TagKind.FREE))
        }
    }

    /** Every person of the library, in either role: what the people fields offer. */
    fun people(): Flow<List<Person>> = personDao.observeAll()

    /** Who each score is by, by score id: shown under its title and searched. */
    fun creditsBySheet(): Flow<Map<Long, Credits>> = personDao.observeSheetPeople().map(Credits::bySheet)

    suspend fun peopleOf(sheetId: Long, role: PersonRole): List<String> = personDao.peopleOf(sheetId, role.name).map { it.name }

    /** [sheetId] gets exactly [names] in [role]: a person already in the library is reused, a new one is created. */
    suspend fun setPeople(sheetId: Long, role: PersonRole, names: List<String>) = db.withTransaction {
        val ids = personIdsFor(names)
        personDao.unlinkRole(sheetId, role.name)
        personDao.link(ids.map { SheetPerson(sheetId, it, role.name) })
    }

    /** [names] are added in [role] to each of [sheetIds], next to the people it already has. */
    suspend fun addPeople(sheetIds: List<Long>, role: PersonRole, names: List<String>) = db.withTransaction {
        val ids = personIdsFor(names)
        personDao.link(sheetIds.flatMap { sheetId -> ids.map { SheetPerson(sheetId, it, role.name) } })
    }

    // one id per person named, an existing spelling before a new row
    private suspend fun personIdsFor(names: List<String>): List<Long> {
        val known = personDao.all()
        return distinctTagNames(names.map(::tagName)).map { name ->
            known.firstOrNull { sameTag(it.name, name) }?.id ?: personDao.insert(Person(name = name, sortName = name))
        }
    }

    /** Every genre of the library, a default one shown in the app's language ([genreLabel]). */
    fun genres(): Flow<List<Tag>> = tagDao.observeGenres()

    /** The genres of each score, by score id. */
    fun genresBySheet(): Flow<Map<Long, List<Tag>>> = tagDao.observeSheetGenres().map { rows ->
        rows.groupBy({ it.sheetId }, { Tag(id = it.tagId, name = it.name, kind = TagKind.GENRE, code = it.code) })
    }

    suspend fun genresOf(sheetId: Long): List<Tag> = tagDao.genresOf(sheetId)

    /**
     * [sheetId] gets exactly the genres [names], as shown ([labels]: the default genres' names in
     * the app's language): a genre already in the library is reused, a new one is created.
     */
    suspend fun setGenres(sheetId: Long, names: List<String>, labels: Map<String, String>) = db.withTransaction {
        val ids = genreIdsFor(names, labels)
        tagDao.unlinkGenres(sheetId)
        tagDao.link(ids.map { SheetTag(sheetId, it) })
    }

    /** The genres [names] are added to each of [sheetIds], next to the ones it has. */
    suspend fun addGenres(sheetIds: List<Long>, names: List<String>, labels: Map<String, String>) = db.withTransaction {
        val ids = genreIdsFor(names, labels)
        tagDao.link(sheetIds.flatMap { sheetId -> ids.map { SheetTag(sheetId, it) } })
    }

    // a genre named as shown or as stored, before a new row
    private suspend fun genreIdsFor(names: List<String>, labels: Map<String, String>): List<Long> {
        val known = tagDao.genres()
        return distinctTagNames(names.map(::tagName)).map { name ->
            known.firstOrNull { sameTag(it.genreLabel(labels), name) || sameTag(it.name, name) }?.id
                ?: tagDao.insert(Tag(name = name, kind = TagKind.GENRE))
        }
    }

    /**
     * The default genres the library no longer has come back: a genre of the user's with a default
     * one's name (in [labels] or in English) becomes that default again. Returns how many came back.
     */
    suspend fun restoreDefaultGenres(labels: Map<String, String>): Int = db.withTransaction {
        val known = tagDao.genres()
        var restored = 0
        for (genre in Genre.entries) {
            if (known.any { it.code == genre.name }) continue
            val same = known.firstOrNull { it.code == null && (sameTag(it.name, labels[genre.name].orEmpty()) || sameTag(it.name, genre.english)) }
            if (same != null) tagDao.setCode(same.id, genre.name)
            else tagDao.insert(Tag(name = genre.english, kind = TagKind.GENRE, code = genre.name))
            restored++
        }
        restored
    }

    /** The keys of each score, by score id, in each score's order. */
    fun keysBySheet(): Flow<Map<Long, List<MusicalKey>>> = keyDao.observeAll().map { rows ->
        rows.groupBy({ it.sheetId }, { MusicalKey.ofCode(it.key) }).mapValues { (_, keys) -> keys.filterNotNull() }
    }

    suspend fun keysOf(sheetId: Long): List<MusicalKey> = keyDao.keysOf(sheetId).mapNotNull { MusicalKey.ofCode(it.key) }

    /** [sheetId] is in [keys], in this order (the first is the one it starts in). */
    suspend fun setKeys(sheetId: Long, keys: List<MusicalKey>) = db.withTransaction {
        keyDao.clear(sheetId)
        keyDao.insert(keys.distinct().mapIndexed { position, key -> SheetKey(sheetId, position, key.code) })
    }

    /** [keys] are added after the ones each of [sheetIds] has. */
    suspend fun addKeys(sheetIds: List<Long>, keys: List<MusicalKey>) = db.withTransaction {
        for (sheetId in sheetIds) {
            val had = keysOf(sheetId)
            setKeys(sheetId, had + keys.filter { it !in had })
        }
    }

    suspend fun byHash(hash: String): Sheet? = dao.byHash(hash)

    // the copy and the MD5 run on the IO dispatcher: called from the Main scope, a 20 MB PDF
    // froze the UI for as long as it took to read and hash it
    suspend fun importSheet(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        try {
            val fileName = appContext.contentResolver.query(uri, arrayOf("_display_name"), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
                ?: uri.lastPathSegment ?: "partitura.pdf"
            val dir = File(appContext.filesDir, "sheets").apply { mkdirs() }
            val name = UUID.randomUUID().toString()
            var destination = File(dir, "$name.pdf")
            val hash = try {
                val input = appContext.contentResolver.openInputStream(uri)
                    ?: return@withContext ImportResult.Error(R.string.err_open_file)
                SheetImporter.import(input, destination)
            } catch (_: IOException) {
                destination.delete()
                return@withContext ImportResult.Error(R.string.err_file_unreadable)
            }

            dao.byHash(hash)?.let {
                destination.delete()
                return@withContext ImportResult.Duplicate
            }

            // the copy is named after what it holds, which is how the viewer picks PDF or MusicXML
            val format = SheetFormat.detect(destination) ?: run {
                destination.delete()
                return@withContext ImportResult.Error(R.string.err_not_a_score)
            }
            var title = SheetImporter.titleFrom(fileName)
            var composer = ""
            if (format.isMusicXml) {
                val copy = destination
                destination = File(dir, "$name.${format.extension}").takeIf { copy.renameTo(it) } ?: run {
                    copy.delete()
                    return@withContext ImportResult.Error(R.string.err_file_unreadable)
                }
                // the details the score carries beat the file name
                runCatching { MusicXmlScore.parse(destination.readBytes()).info }.getOrNull()?.let { info ->
                    info.title?.takeIf { it.isNotBlank() }?.let { title = it.trim() }
                    info.composer?.takeIf { it.isNotBlank() }?.let { composer = it.trim() }
                }
            }

            val sheet = Sheet(
                fileName = fileName,
                path = destination.absolutePath,
                hash = hash,
                title = title,
                // the people are in their table (below), not in the text
                composer = "",
                genre = "",
                musicalKey = "",
                tags = "",
                format = format,
                addedAt = System.currentTimeMillis()
            )
            val saved = sheet.copy(id = dao.insert(sheet))
            // the composer a MusicXML file names, split as the old text was
            if (composer.isNotBlank()) setPeople(saved.id, PersonRole.COMPOSER, splitPeopleText(composer))
            // a MusicXML score is laid out and drawn ahead while the user looks at its details
            if (format.isMusicXml) ScorePrecompute.changed()
            ImportResult.Ok(saved)
        } catch (e: Exception) {
            Log.e("FOSScore-Library", "importar ${uri.lastPathSegment}: ${e.message}", e)
            ImportResult.Error(R.string.err_import_failed)
        }
    }

    suspend fun save(sheet: Sheet) = dao.update(sheet)

    suspend fun delete(sheet: Sheet) {
        // the annotations hang on the sheet's id; the foreign keys cascade, and the explicit
        // deletes keep this right even where they are not enforced
        stampDao.deleteForSheet(sheet.id)
        strokeDao.deleteForSheet(sheet.id)
        dao.delete(sheet)
        try {
            File(sheet.path).delete()
        } catch (_: Exception) {
            // ponytail: the row is already gone; a missing file must not block the delete
        }
    }
}