package com.musigatto.fosscore.library

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Sheet::class, Stamp::class, Stroke::class], version = 4)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sheetDao(): SheetDao
    abstract fun stampDao(): StampDao
    abstract fun strokeDao(): StrokeDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `stamps` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`sheetHash` TEXT NOT NULL, `page` INTEGER NOT NULL, `symbol` TEXT NOT NULL, " +
                        "`x` REAL NOT NULL, `y` REAL NOT NULL, `size` REAL NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_stamps_sheetHash_page` ON `stamps` (`sheetHash`, `page`)")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `stamps` ADD COLUMN `color` INTEGER")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `strokes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`sheetHash` TEXT NOT NULL, `page` INTEGER NOT NULL, `points` TEXT NOT NULL, " +
                        "`width` REAL NOT NULL, `color` INTEGER)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_strokes_sheetHash_page` ON `strokes` (`sheetHash`, `page`)")
            }
        }

        private var instance: AppDatabase? = null

        @Synchronized
        fun get(context: Context): AppDatabase =
            instance ?: Room.databaseBuilder(context, AppDatabase::class.java, "fosscore.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
                .also { instance = it }
    }
}