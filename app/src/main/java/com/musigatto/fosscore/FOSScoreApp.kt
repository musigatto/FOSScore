package com.musigatto.fosscore

import android.app.Application
import com.musigatto.fosscore.library.AppDatabase
import com.musigatto.fosscore.library.LibraryRepository
import com.musigatto.fosscore.library.StampRepository
import com.musigatto.fosscore.library.StrokeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class FOSScoreApp : Application() {
    val repository: LibraryRepository by lazy { LibraryRepository(this) }
    val stampRepository: StampRepository by lazy { StampRepository(this) }
    val strokeRepository: StrokeRepository by lazy { StrokeRepository(this) }
    // ponytail: DI manual con 1 BD + 3 repos; Hilt cuando el grafo crezca
    val database: AppDatabase by lazy { AppDatabase.get(this) }
    // tareas de limpieza que deben sobrevivir a la composición (p.ej. close() de PdfRenderer)
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}