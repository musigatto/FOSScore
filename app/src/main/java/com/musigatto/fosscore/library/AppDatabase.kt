package com.musigatto.fosscore.library

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        Sheet::class, Stamp::class, Stroke::class,
        SheetKey::class, Person::class, SheetPerson::class,
        Tag::class, SheetTag::class, SheetLayout::class,
    ],
    version = AppDatabase.VERSION
)
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

        /**
         * v5 (issue #40, docs/feature-roadmap.md §3.8).
         *
         * The sheet's `id` becomes the key of everything that hangs off a score: the annotations
         * stop following the file hash, which changes when the file changes, and follow the sheet,
         * which survives a re-export. Nothing is dropped: the old free text columns stay until v6,
         * once the new tables are checked.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // --- the score's own details ---
                db.execSQL("ALTER TABLE `sheets` ADD COLUMN `format` TEXT")
                db.execSQL("ALTER TABLE `sheets` ADD COLUMN `tempoBpm` REAL")
                db.execSQL("ALTER TABLE `sheets` ADD COLUMN `beatUnit` INTEGER")
                db.execSQL("ALTER TABLE `sheets` ADD COLUMN `timeSignature` TEXT")
                db.execSQL("ALTER TABLE `sheets` ADD COLUMN `durationS` REAL")
                db.execSQL("ALTER TABLE `sheets` ADD COLUMN `openedAt` INTEGER")
                db.execSQL("ALTER TABLE `sheets` ADD COLUMN `openCount` INTEGER")
                db.execSQL("ALTER TABLE `sheets` ADD COLUMN `addedAt` INTEGER")
                // until now the extension said it; nothing else to go by for the rows already here
                db.execSQL(
                    "UPDATE `sheets` SET `format` = CASE" +
                        " WHEN lower(`path`) LIKE '%.mxl' THEN 'MXL'" +
                        " WHEN lower(`path`) LIKE '%.musicxml' OR lower(`path`) LIKE '%.xml' THEN 'MUSICXML'" +
                        " ELSE 'PDF' END"
                )
                // the hash was deduped on import, so a repeat is a bug; keeping the oldest row is
                // what lets the unique index below exist at all. Its annotations follow the row.
                db.execSQL("DELETE FROM `sheets` WHERE `id` NOT IN (SELECT MIN(`id`) FROM `sheets` GROUP BY `hash`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sheets_hash` ON `sheets` (`hash`)")

                // --- annotations: sheetId instead of the hash, plus the new columns ---
                // rebuilt rather than ALTERed: a NOT NULL foreign key cannot be added in place,
                // and the JOIN drops rows whose sheet is already gone (nothing shows them)
                db.execSQL(
                    "CREATE TABLE `stamps_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sheetId` INTEGER NOT NULL, " +
                        "`page` INTEGER NOT NULL, `symbol` TEXT NOT NULL, `x` REAL NOT NULL, `y` REAL NOT NULL, " +
                        "`size` REAL NOT NULL, `color` INTEGER, `text` TEXT, `font` TEXT, " +
                        "`bold` INTEGER NOT NULL, `italic` INTEGER NOT NULL, `measure` INTEGER, `staff` INTEGER, " +
                        "FOREIGN KEY(`sheetId`) REFERENCES `sheets`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "INSERT INTO `stamps_new` (`id`, `sheetId`, `page`, `symbol`, `x`, `y`, `size`, `color`, " +
                        "`text`, `font`, `bold`, `italic`, `measure`, `staff`) " +
                        "SELECT s.`id`, sh.`id`, s.`page`, s.`symbol`, s.`x`, s.`y`, s.`size`, s.`color`, " +
                        "NULL, NULL, 0, 0, NULL, NULL FROM `stamps` s JOIN `sheets` sh ON sh.`hash` = s.`sheetHash`"
                )
                db.execSQL("DROP TABLE `stamps`")
                db.execSQL("ALTER TABLE `stamps_new` RENAME TO `stamps`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_stamps_sheetId_page` ON `stamps` (`sheetId`, `page`)")

                db.execSQL(
                    "CREATE TABLE `strokes_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sheetId` INTEGER NOT NULL, " +
                        "`page` INTEGER NOT NULL, `points` TEXT NOT NULL, `width` REAL NOT NULL, `color` INTEGER, " +
                        "`measure` INTEGER, `staff` INTEGER, `groupId` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`sheetId`) REFERENCES `sheets`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "INSERT INTO `strokes_new` (`id`, `sheetId`, `page`, `points`, `width`, `color`, " +
                        "`measure`, `staff`, `groupId`) " +
                        "SELECT s.`id`, sh.`id`, s.`page`, s.`points`, s.`width`, s.`color`, NULL, NULL, 0 " +
                        "FROM `strokes` s JOIN `sheets` sh ON sh.`hash` = s.`sheetHash`"
                )
                db.execSQL("DROP TABLE `strokes`")
                db.execSQL("ALTER TABLE `strokes_new` RENAME TO `strokes`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_strokes_sheetId_page` ON `strokes` (`sheetId`, `page`)")

                // --- keys, people, tags: empty until the screen that fills them ---
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sheet_keys` (`sheetId` INTEGER NOT NULL, " +
                        "`position` INTEGER NOT NULL, `key` TEXT NOT NULL, PRIMARY KEY(`sheetId`, `position`), " +
                        "FOREIGN KEY(`sheetId`) REFERENCES `sheets`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `people` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `sortName` TEXT NOT NULL)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_people_name` ON `people` (`name`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sheet_people` (`sheetId` INTEGER NOT NULL, " +
                        "`personId` INTEGER NOT NULL, `role` TEXT NOT NULL, " +
                        "PRIMARY KEY(`sheetId`, `personId`, `role`), " +
                        "FOREIGN KEY(`sheetId`) REFERENCES `sheets`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(`personId`) REFERENCES `people`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sheet_people_personId` ON `sheet_people` (`personId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tags` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `color` INTEGER, `kind` TEXT NOT NULL)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_tags_name` ON `tags` (`name`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sheet_tags` (`sheetId` INTEGER NOT NULL, " +
                        "`tagId` INTEGER NOT NULL, PRIMARY KEY(`sheetId`, `tagId`), " +
                        "FOREIGN KEY(`sheetId`) REFERENCES `sheets`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(`tagId`) REFERENCES `tags`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sheet_tags_tagId` ON `sheet_tags` (`tagId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sheet_layouts` (`sheetId` INTEGER NOT NULL, " +
                        "`orientation` TEXT NOT NULL, `zoom` REAL NOT NULL, `twoUp` INTEGER NOT NULL, " +
                        "`lyrics` TEXT, PRIMARY KEY(`sheetId`, `orientation`), " +
                        "FOREIGN KEY(`sheetId`) REFERENCES `sheets`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )

                // the tags and the genre become rows; the text columns stay for v6
                backfillTags(db)
            }
        }

        /**
         * `sheets.tags` ("duo, practice") and `sheets.genre` ("Folk") become tags. The genre goes
         * first: a name that is a genre somewhere is a genre everywhere, so a free tag of the same
         * name does not create a second row.
         */
        private fun backfillTags(db: SupportSQLiteDatabase) {
            val rows = mutableListOf<Pair<Long, Pair<String, List<String>>>>()
            db.query("SELECT `id`, `genre`, `tags` FROM `sheets`").use { c ->
                while (c.moveToNext()) {
                    val genre = c.getString(1).trim()
                    val tags = c.getString(2).split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
                    rows += c.getLong(0) to (genre to tags)
                }
            }
            fun add(sheetId: Long, name: String, kind: TagKind) {
                db.execSQL(
                    "INSERT OR IGNORE INTO `tags` (`name`, `kind`) VALUES (?, ?)",
                    arrayOf(name, kind.name)
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO `sheet_tags` (`sheetId`, `tagId`) SELECT ?, `id` FROM `tags` WHERE `name` = ?",
                    arrayOf(sheetId, name)
                )
            }
            for ((sheetId, value) in rows) {
                val (genre, tags) = value
                if (genre.isNotEmpty()) add(sheetId, genre, TagKind.GENRE)
                tags.forEach { add(sheetId, it, TagKind.FREE) }
            }
        }

        const val NAME = "fosscore.db"

        /**
         * Schema version. When it changes: add the migration to [MIGRATIONS] (or an automatic
         * migration), commit the new file in `app/schemas/`, and extend `MigrationTest`.
         */
        const val VERSION = 5

        /** Every migration, oldest first. A missing one stops the app instead of wiping the library. */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)

        private var instance: AppDatabase? = null

        @Synchronized
        fun get(context: Context): AppDatabase = instance ?: run {
            val app = context.applicationContext
            // A library import chosen before the restart replaces the database now, while closed.
            LibraryBackup.applyPending(app, NAME, VERSION)
            // A copy of the database before any migration, and a newer database (after going back
            // to an older app version) moved aside instead of deleted.
            DatabaseSafety.beforeOpen(app, NAME, VERSION)
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .addCallback(object : Callback() {
                    // After the migrations: the imported sheets point to their PDFs on this device.
                    override fun onOpen(db: SupportSQLiteDatabase) = LibraryBackup.relinkIfNeeded(app, db)
                })
                .build()
                .also { instance = it }
        }
    }
}
