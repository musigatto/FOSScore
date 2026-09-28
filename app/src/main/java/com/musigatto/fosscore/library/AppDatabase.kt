package com.musigatto.fosscore.library

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Sheet::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sheetDao(): SheetDao

    companion object {
        private var instance: AppDatabase? = null

        @Synchronized
        fun get(context: Context): AppDatabase =
            instance ?: Room.databaseBuilder(context, AppDatabase::class.java, "fosscore.db")
                .fallbackToDestructiveMigration(dropAllTables = true) // ponytail: schema v1; migraciones cuando existan
                .build()
                .also { instance = it }
    }
}