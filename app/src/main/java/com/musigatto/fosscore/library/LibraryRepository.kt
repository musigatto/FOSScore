package com.musigatto.fosscore.library

import android.content.Context
import android.net.Uri
import android.util.Log
import com.musigatto.fosscore.R
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
            val destination = File(dir, "${UUID.randomUUID()}.pdf")
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

            val sheet = Sheet(
                fileName = fileName,
                path = destination.absolutePath,
                hash = hash,
                title = SheetImporter.titleFrom(fileName),
                composer = "",
                genre = "",
                musicalKey = "",
                tags = ""
            )
            ImportResult.Ok(sheet.copy(id = dao.insert(sheet)))
        } catch (e: Exception) {
            Log.e("FOSScore-Library", "importar ${uri.lastPathSegment}: ${e.message}", e)
            ImportResult.Error(R.string.err_import_failed)
        }
    }

    suspend fun save(sheet: Sheet) = dao.update(sheet)

    suspend fun delete(sheet: Sheet) {
        dao.delete(sheet)
        stampDao.deleteForSheet(sheet.hash)
        strokeDao.deleteForSheet(sheet.hash)
        try {
            File(sheet.path).delete()
        } catch (_: Exception) {
            // ponytail: the row is already gone; a missing file must not block the delete
        }
    }
}