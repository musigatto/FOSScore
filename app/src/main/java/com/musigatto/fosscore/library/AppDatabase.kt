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
        Setlist::class, SetlistItem::class,
        Layer::class,
    ],
    version = AppDatabase.VERSION
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sheetDao(): SheetDao
    abstract fun stampDao(): StampDao
    abstract fun strokeDao(): StrokeDao
    abstract fun layerDao(): LayerDao
    abstract fun setlistDao(): SetlistDao
    abstract fun tagDao(): TagDao
    abstract fun personDao(): PersonDao
    abstract fun keyDao(): KeyDao

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
         * which survives a re-export. Nothing is dropped: the old free text columns stay until a
         * later version, once the new tags are proven.
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
                    arrayOf<Any>(sheetId, name)
                )
            }
            for ((sheetId, value) in rows) {
                val (genre, tags) = value
                if (genre.isNotEmpty()) add(sheetId, genre, TagKind.GENRE)
                tags.forEach { add(sheetId, it, TagKind.FREE) }
            }
        }

        /**
         * v6: shapes. The strokes table gains `kind`: 0 = ink (what every row was until now),
         * 1 = a shape's outline, 2 = a filled shape. Everything else stays put.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `strokes` ADD COLUMN `kind` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v7: a text stamp keeps the width it was wrapped at (a share of the page width). Old texts
         * have null and keep wrapping at the page width.
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `stamps` ADD COLUMN `textWidth` REAL")
            }
        }

        /**
         * v8: setlists, named and ordered lists of scores. A score may come back in the same
         * setlist, so a place has an id of its own. Nothing existing changes.
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `setlists` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `setlist_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`setlistId` INTEGER NOT NULL, `position` INTEGER NOT NULL, `sheetId` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`setlistId`) REFERENCES `setlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(`sheetId`) REFERENCES `sheets`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_setlist_items_setlistId_position` ON `setlist_items` (`setlistId`, `position`)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_setlist_items_sheetId` ON `setlist_items` (`sheetId`)")
            }
        }

        /**
         * v9: annotation layers. Adds the [layers] table and a [layer] column to stamps and strokes.
         * Existing marks are moved to one layer per sheet (created by the migration) so they keep
         * their grouping; the layer name is empty and the UI shows a translated default by position.
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `layers` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sheetId` INTEGER NOT NULL, `name` TEXT NOT NULL, `visible` INTEGER NOT NULL, FOREIGN KEY(`sheetId`) REFERENCES `sheets`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_layers_sheetId` ON `layers` (`sheetId`)")
                db.execSQL("ALTER TABLE `stamps` ADD COLUMN `layer` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `strokes` ADD COLUMN `layer` INTEGER NOT NULL DEFAULT 0")
                // One layer per sheet that has marks; old marks go to that layer. The name is empty:
                // the UI shows a translated default by position.
                db.execSQL("INSERT INTO `layers` (`sheetId`, `name`, `visible`) SELECT `sheetId`, '', 1 FROM (SELECT DISTINCT `sheetId` FROM `stamps` UNION SELECT DISTINCT `sheetId` FROM `strokes`)")
                db.execSQL("UPDATE `stamps` SET `layer` = (SELECT MIN(`id`) FROM `layers` WHERE `layers`.`sheetId` = `stamps`.`sheetId`)")
                db.execSQL("UPDATE `strokes` SET `layer` = (SELECT MIN(`id`) FROM `layers` WHERE `layers`.`sheetId` = `strokes`.`sheetId`)")
            }
        }

        /**
         * v10: the tags live in their table, no longer in `sheets.tags`. Since v5 the table was a
         * copy taken once, and the tag field went on writing the text: it is built again from the
         * text, which is what every score shows today. A tag written on several scores, or spelled
         * several ways ("Rock", "rock"), becomes one tag. The genre stays text for now: its rows,
         * copied at v5 and never read, go, and come back when the genre gets its own field.
         * The text column stays (dropping it rebuilds `sheets`), emptied.
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // the user's own words on each score
                db.execSQL("ALTER TABLE `sheets` ADD COLUMN `comment` TEXT NOT NULL DEFAULT ''")

                val sheets = mutableListOf<Pair<Long, String>>()
                db.query("SELECT `id`, `tags` FROM `sheets` ORDER BY `id`").use { c ->
                    while (c.moveToNext()) sheets += c.getLong(0) to c.getString(1)
                }
                db.execSQL("DELETE FROM `sheet_tags`")
                db.execSQL("DELETE FROM `tags`")
                // a name is unique within its kind (the genre "Pop" beside a tag "Pop"), and a
                // default genre knows which one it is, to be shown in the app's language
                db.execSQL("DROP INDEX IF EXISTS `index_tags_name`")
                db.execSQL("ALTER TABLE `tags` ADD COLUMN `code` TEXT")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_tags_kind_name` ON `tags` (`kind`, `name`)")
                val plan = NameBackfill.of(sheets, ::splitTagText)
                plan.names.forEachIndexed { i, name ->
                    db.execSQL(
                        "INSERT INTO `tags` (`id`, `name`, `kind`) VALUES (?, ?, ?)",
                        arrayOf<Any>(i + 1L, name, TagKind.FREE.name)
                    )
                }
                for ((sheetId, index) in plan.links) {
                    db.execSQL(
                        "INSERT INTO `sheet_tags` (`sheetId`, `tagId`) VALUES (?, ?)",
                        arrayOf<Any>(sheetId, index + 1L)
                    )
                }
                db.execSQL("UPDATE `sheets` SET `tags` = ''")

                // the composer text becomes people (empty until now), as authors/composers
                val composers = mutableListOf<Pair<Long, String>>()
                db.query("SELECT `id`, `composer` FROM `sheets` ORDER BY `id`").use { c ->
                    while (c.moveToNext()) composers += c.getLong(0) to c.getString(1)
                }
                db.execSQL("DELETE FROM `sheet_people`")
                db.execSQL("DELETE FROM `people`")
                val people = NameBackfill.of(composers, ::splitPeopleText)
                people.names.forEachIndexed { i, name ->
                    db.execSQL(
                        "INSERT INTO `people` (`id`, `name`, `sortName`) VALUES (?, ?, ?)",
                        arrayOf<Any>(i + 1L, name, name)
                    )
                }
                for ((sheetId, index) in people.links) {
                    db.execSQL(
                        "INSERT INTO `sheet_people` (`sheetId`, `personId`, `role`) VALUES (?, ?, ?)",
                        arrayOf<Any>(sheetId, index + 1L, PersonRole.COMPOSER.name)
                    )
                }
                db.execSQL("UPDATE `sheets` SET `composer` = ''")

                // the genre text becomes genres: the defaults, each text recognised linked to its
                // default, any other text a genre of its own
                val genres = mutableListOf<Pair<Long, String>>()
                db.query("SELECT `id`, `genre` FROM `sheets` ORDER BY `id`").use { c ->
                    while (c.moveToNext()) genres += c.getLong(0) to c.getString(1)
                }
                val genrePlan = GenreBackfill.of(genres)
                val firstGenre = plan.names.size + 1L
                genrePlan.rows.forEachIndexed { i, row ->
                    db.execSQL(
                        "INSERT INTO `tags` (`id`, `name`, `kind`, `code`) VALUES (?, ?, ?, ?)",
                        arrayOf(firstGenre + i, row.name, TagKind.GENRE.name, row.code)
                    )
                }
                for ((sheetId, index) in genrePlan.links) {
                    db.execSQL(
                        "INSERT INTO `sheet_tags` (`sheetId`, `tagId`) VALUES (?, ?)",
                        arrayOf<Any>(sheetId, firstGenre + index)
                    )
                }
                db.execSQL("UPDATE `sheets` SET `genre` = ''")

                // the key text becomes keys, in its order; a text naming no key goes to the
                // comment, so that nothing is lost
                val keyTexts = mutableListOf<Pair<Long, String>>()
                db.query("SELECT `id`, `musicalKey` FROM `sheets` ORDER BY `id`").use { c ->
                    while (c.moveToNext()) keyTexts += c.getLong(0) to c.getString(1)
                }
                db.execSQL("DELETE FROM `sheet_keys`")
                for ((sheetId, text) in keyTexts) {
                    val (keys, left) = splitKeyText(text)
                    keys.forEachIndexed { position, key ->
                        db.execSQL(
                            "INSERT INTO `sheet_keys` (`sheetId`, `position`, `key`) VALUES (?, ?, ?)",
                            arrayOf<Any>(sheetId, position, key.code)
                        )
                    }
                    if (left.isNotEmpty()) {
                        db.execSQL("UPDATE `sheets` SET `comment` = ? WHERE `id` = ?", arrayOf<Any>(KEY_LEFT_PREFIX + left, sheetId))
                    }
                }
                db.execSQL("UPDATE `sheets` SET `musicalKey` = ''")
            }
        }

        /** Before the part of an old key text that names no key, kept in the comment. */
        const val KEY_LEFT_PREFIX = "Key: "

        /** The default genres, in a library that has none of a code yet (a new one, or "restore"). */
        fun insertDefaultGenres(db: SupportSQLiteDatabase) {
            for (genre in Genre.entries) {
                db.execSQL(
                    "INSERT OR IGNORE INTO `tags` (`name`, `kind`, `code`) " +
                        "SELECT ?, ?, ? WHERE NOT EXISTS (SELECT 1 FROM `tags` WHERE `code` = ?)",
                    arrayOf<Any>(genre.english, TagKind.GENRE.name, genre.name, genre.name)
                )
            }
        }

        const val NAME = "fosscore.db"

        /**
         * Schema version. When it changes: add the migration to [MIGRATIONS] (or an automatic
         * migration), commit the new file in `app/schemas/`, and extend `MigrationTest`.
         */
        const val VERSION = 10

        /** Every migration, oldest first. A missing one stops the app instead of wiping the library. */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)

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
                    // a new library starts with the default genres
                    override fun onCreate(db: SupportSQLiteDatabase) = insertDefaultGenres(db)

                    // After the migrations: the imported sheets point to their PDFs on this device.
                    override fun onOpen(db: SupportSQLiteDatabase) = LibraryBackup.relinkIfNeeded(app, db)
                })
                .build()
                .also { instance = it }
        }
    }
}
