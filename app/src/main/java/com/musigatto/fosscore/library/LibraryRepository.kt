package com.musigatto.fosscore.library

import android.content.Context
import android.net.Uri
import android.util.Log
import com.musigatto.fosscore.R
import com.musigatto.fosscore.musicxml.MusicXmlScore
import com.musigatto.fosscore.score.ScorePrecompute
import com.musigatto.fosscore.score.SheetFormat
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
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

    fun sheets(): Flow<List<Sheet>> = dao.observeAll()

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
                composer = composer,
                genre = "",
                musicalKey = "",
                tags = "",
                format = format,
                addedAt = System.currentTimeMillis()
            )
            val saved = sheet.copy(id = dao.insert(sheet))
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