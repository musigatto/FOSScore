package com.musigatto.fosscore

import android.app.Application
import com.musigatto.fosscore.library.AppDatabase
import com.musigatto.fosscore.library.LibraryRepository

class FOSScoreApp : Application() {
    val repository: LibraryRepository by lazy { LibraryRepository(this) }
    // ponytail: DI manual con 1 BD + 1 repo; Hilt cuando el grafo crezca
    val database: AppDatabase by lazy { AppDatabase.get(this) }
}