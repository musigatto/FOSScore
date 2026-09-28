package com.musigatto.fosscore.library

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.Flow

sealed class ImportResult {
    data class Ok(val sheet: Sheet) : ImportResult()
    data class Duplicate(val existing: Sheet) : ImportResult()
    data class Error(val reason: String) : ImportResult()
}

class LibraryRepository(context: Context) {
    private val appContext = context.applicationContext
    private val dao = AppDatabase.get(context).sheetDao()
    private val stampDao = AppDatabase.get(context).stampDao()

    fun sheets(): Flow<List<Sheet>> = dao.observeAll()

    suspend fun importSheet(uri: Uri): ImportResult = try {
        val fileName = appContext.contentResolver.query(uri, arrayOf("_display_name"), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
            ?: uri.lastPathSegment ?: "partitura.pdf"
        val dir = File(appContext.filesDir, "sheets").apply { mkdirs() }
        val destination = File(dir, "${UUID.randomUUID()}.pdf")
        val hash = try {
            val input = appContext.contentResolver.openInputStream(uri)
            if (input == null) return ImportResult.Error("No se pudo abrir el archivo")
            SheetImporter.import(input, destination)
        } catch (e: IOException) {
            destination.delete()
            return ImportResult.Error("El archivo no se pudo leer")
        }

        dao.byHash(hash)?.let { existing ->
            destination.delete()
            return ImportResult.Duplicate(existing)
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
        ImportResult.Error(e.message ?: "Fallo al importar")
    }

    suspend fun save(sheet: Sheet) = dao.update(sheet)

    suspend fun delete(sheet: Sheet) {
        dao.delete(sheet)
        stampDao.deleteForSheet(sheet.hash)
        try {
            File(sheet.path).delete()
        } catch (_: Exception) {
            // ponytail: fila ya borrada; si el archivo no existe/falla, no bloquea el borrado
        }
    }
}