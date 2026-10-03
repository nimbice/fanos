package io.github.nimbice.fanos.core.database

import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import io.github.nimbice.fanos.core.database.entity.SectionEntity

/** Every schema change, oldest first. Libraries are never dropped to get past a version. */
internal val MIGRATIONS = arrayOf(Migration1To2, Migration2To3, Migration3To4, Migration4To5, Migration5To6, Migration6To7, Migration7To8, Migration8To9, Migration9To10, Migration10To11, Migration11To12, Migration12To13, Migration13To14, Migration14To15, Migration15To16)

/**
 * 2: novels keep a library position, the order the reader arranges the library in. The library
 * starts in the order it showed until then: most recently read or added first.
 */
internal object Migration1To2 : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE novels ADD COLUMN library_position INTEGER NOT NULL DEFAULT 0")
        connection.execSQL(
            """UPDATE novels SET library_position = (
                 SELECT ranked.position FROM (
                   SELECT id, ROW_NUMBER() OVER (ORDER BY COALESCE(last_read_at, added_at, 0) DESC, title) - 1 AS position
                   FROM novels WHERE in_library = 1
                 ) AS ranked
                 WHERE ranked.id = novels.id
               )
               WHERE in_library = 1""",
        )
    }
}

/** 3: the download queue, chapters waiting to be saved for offline reading. */
internal object Migration2To3 : Migration(2, 3) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """CREATE TABLE IF NOT EXISTS `download_queue` (`chapter_id` INTEGER NOT NULL, `novel_id` INTEGER NOT NULL,
               `queued_at` INTEGER NOT NULL, `failure` TEXT, PRIMARY KEY(`chapter_id`),
               FOREIGN KEY(`chapter_id`) REFERENCES `chapters`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE ,
               FOREIGN KEY(`novel_id`) REFERENCES `novels`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )""",
        )
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_download_queue_novel_id` ON `download_queue` (`novel_id`)")
    }
}

/** 4: the reader's own title and cover for a novel, shown instead of the site's. */
internal object Migration3To4 : Migration(3, 4) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE novels ADD COLUMN custom_title TEXT")
        connection.execSQL("ALTER TABLE novels ADD COLUMN custom_cover_url TEXT")
    }
}

/** 5: chapters the site lists as ones the reader can't read now, marked with a lock. */
internal object Migration4To5 : Migration(4, 5) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE chapters ADD COLUMN locked INTEGER NOT NULL DEFAULT 0")
    }
}

/** 6: a novel's own reading settings, kept instead of the defaults. Every novel starts on the defaults. */
internal object Migration5To6 : Migration(5, 6) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE novels ADD COLUMN reader_settings TEXT")
    }
}

/**
 * 7: sections of the library, a novel in any number of them. Every library novel starts in none, which puts it in
 * the default section, made here.
 */
internal object Migration6To7 : Migration(6, 7) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `sections` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `position` INTEGER NOT NULL)",
        )
        connection.execSQL(
            """CREATE TABLE IF NOT EXISTS `novel_sections` (`novel_id` INTEGER NOT NULL, `section_id` INTEGER NOT NULL,
               PRIMARY KEY(`novel_id`, `section_id`),
               FOREIGN KEY(`novel_id`) REFERENCES `novels`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE ,
               FOREIGN KEY(`section_id`) REFERENCES `sections`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )""",
        )
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_novel_sections_section_id` ON `novel_sections` (`section_id`)")
        connection.execSQL(DEFAULT_SECTION_SQL)
    }
}

/**
 * 8, the same tables: a novel whose every chapter a refresh marked removed gets them back. A site that listed no
 * chapters at all emptied the novel that way (Novel Updates, once it took a licensed novel's links down); such a list
 * now removes nothing, and this undoes what earlier ones did. Read marks and saved chapters were kept all along.
 */
internal object Migration7To8 : Migration(7, 8) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """UPDATE chapters SET removed_at = NULL
               WHERE novel_id IN (SELECT novel_id FROM chapters GROUP BY novel_id HAVING COUNT(removed_at) = COUNT(*))""",
        )
    }
}

/**
 * 9: a chapter's translation group, and the group a novel's reader follows (Novel Updates lists several groups' chapters
 * side by side); and what the app last told a site's reading lists of each novel it put there.
 */
internal object Migration8To9 : Migration(8, 9) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE chapters ADD COLUMN group_name TEXT")
        connection.execSQL("ALTER TABLE novels ADD COLUMN chapter_group TEXT")
        connection.execSQL(
            """CREATE TABLE IF NOT EXISTS `reading_list_entries` (`source_id` TEXT NOT NULL, `novel_url` TEXT NOT NULL,
               `list_id` TEXT NOT NULL, `bookmark_url` TEXT, `synced_at` INTEGER NOT NULL, PRIMARY KEY(`source_id`, `novel_url`))""",
        )
    }
}

/**
 * Whether the reader picked the group a novel follows: a site whose groups are versions of the same chapters has the
 * app follow a default one until they do. Groups followed until now were all picked.
 */
internal object Migration9To10 : Migration(9, 10) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE novels ADD COLUMN chapter_group_chosen INTEGER NOT NULL DEFAULT 0")
        connection.execSQL("UPDATE novels SET chapter_group_chosen = 1 WHERE chapter_group IS NOT NULL")
    }
}

/** A novel's own chapter order, newest first or oldest; none yet, so every novel follows the app's until it's set. */
internal object Migration10To11 : Migration(10, 11) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE novels ADD COLUMN chapters_newest_first INTEGER")
    }
}

/** 16: a highlight may run on into later paragraphs, to its end block; the highlights there already end in their own. */
internal object Migration15To16 : Migration(15, 16) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE highlights ADD COLUMN end_block INTEGER NOT NULL DEFAULT -1")
        connection.execSQL("UPDATE highlights SET end_block = block")
    }
}

/** 15: highlights, text the reader marked in chapters, with notes. */
internal object Migration14To15 : Migration(14, 15) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """CREATE TABLE IF NOT EXISTS `highlights` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `novel_id` INTEGER NOT NULL,
               `chapter_id` INTEGER NOT NULL, `block` INTEGER NOT NULL, `start` INTEGER NOT NULL, `end` INTEGER NOT NULL, `text` TEXT NOT NULL,
               `color` INTEGER NOT NULL, `note` TEXT, `created_at` INTEGER NOT NULL,
               FOREIGN KEY(`novel_id`) REFERENCES `novels`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE ,
               FOREIGN KEY(`chapter_id`) REFERENCES `chapters`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )""",
        )
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_highlights_novel_id` ON `highlights` (`novel_id`)")
        connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_highlights_chapter_id_block_start` ON `highlights` (`chapter_id`, `block`, `start`)")
    }
}

/** 14: word replacements, in a novel's text or all novels'. */
internal object Migration13To14 : Migration(13, 14) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """CREATE TABLE IF NOT EXISTS `replacements` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `novel_id` INTEGER, `find` TEXT NOT NULL,
               `replace` TEXT NOT NULL, `whole_word` INTEGER NOT NULL, `match_case` INTEGER NOT NULL, `created_at` INTEGER NOT NULL,
               FOREIGN KEY(`novel_id`) REFERENCES `novels`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )""",
        )
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_replacements_novel_id` ON `replacements` (`novel_id`)")
    }
}

/** 13: reading time, by day and novel, for the reading stats. */
internal object Migration12To13 : Migration(12, 13) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """CREATE TABLE IF NOT EXISTS `reading_time` (`day` INTEGER NOT NULL, `novel_id` INTEGER NOT NULL, `seconds` INTEGER NOT NULL,
               PRIMARY KEY(`day`, `novel_id`), FOREIGN KEY(`novel_id`) REFERENCES `novels`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )""",
        )
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_reading_time_novel_id` ON `reading_time` (`novel_id`)")
    }
}

/** 12: bookmarks, places the reader marked in chapters. */
internal object Migration11To12 : Migration(11, 12) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """CREATE TABLE IF NOT EXISTS `bookmarks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `novel_id` INTEGER NOT NULL,
               `chapter_id` INTEGER NOT NULL, `block` INTEGER NOT NULL, `snippet` TEXT NOT NULL, `created_at` INTEGER NOT NULL,
               FOREIGN KEY(`novel_id`) REFERENCES `novels`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE ,
               FOREIGN KEY(`chapter_id`) REFERENCES `chapters`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )""",
        )
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_bookmarks_novel_id` ON `bookmarks` (`novel_id`)")
        connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_bookmarks_chapter_id_block` ON `bookmarks` (`chapter_id`, `block`)")
    }
}

/**
 * A new database starts with the default section, the one a library novel is in until the reader puts it in
 * others (migrating databases get it from [Migration6To7]).
 */
internal object DefaultSection : RoomDatabase.Callback() {
    override fun onCreate(connection: SQLiteConnection) {
        connection.execSQL(DEFAULT_SECTION_SQL)
    }
}

private const val DEFAULT_SECTION_SQL =
    "INSERT INTO sections (id, name, position) VALUES (${SectionEntity.DEFAULT_ID}, '${SectionEntity.DEFAULT_NAME}', 0)"
