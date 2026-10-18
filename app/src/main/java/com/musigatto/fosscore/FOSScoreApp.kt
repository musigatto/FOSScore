package com.musigatto.fosscore

import android.app.Application
import com.musigatto.fosscore.library.LibraryRepository
import com.musigatto.fosscore.library.StampRepository
import com.musigatto.fosscore.library.StrokeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

// ponytail: hand-rolled DI, one DB + three repos; move to Hilt when the graph grows
class FOSScoreApp : Application() {
    val repository: LibraryRepository by lazy { LibraryRepository(this) }
    val stampRepository: StampRepository by lazy { StampRepository(this) }
    val strokeRepository: StrokeRepository by lazy { StrokeRepository(this) }

    // cleanup work that must outlive the UI (e.g. MuPdfDoc.close())
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
