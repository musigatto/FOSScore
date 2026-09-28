package com.musigatto.fosscore

import android.app.Application
import com.musigatto.fosscore.library.AppDatabase
import com.musigatto.fosscore.library.LibraryRepository
import com.musigatto.fosscore.library.StampRepository

class FOSScoreApp : Application() {
    val repository: LibraryRepository by lazy { LibraryRepository(this) }
    val stampRepository: StampRepository by lazy { StampRepository(this) }
    // ponytail: DI manual con 1 BD + 2 repos; Hilt cuando el grafo crezca
    val database: AppDatabase by lazy { AppDatabase.get(this) }
}