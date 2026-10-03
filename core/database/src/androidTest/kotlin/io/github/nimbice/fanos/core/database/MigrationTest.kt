package io.github.nimbice.fanos.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Every migration, from the first schema to the current one, against the exported schemas: a library made by any
 * version of the app must come through whole. Runs on a device or emulator (connectedDebugAndroidTest).
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val file = instrumentation.targetContext.getDatabasePath("migration-test")

    @get:Rule
    val helper =
        MigrationTestHelper(
            instrumentation = instrumentation,
            file = file,
            driver = BundledSQLiteDriver(),
            databaseClass = AppDatabase::class,
        )

    /** A database at [version] on a clean file: the helper builds on whatever file is there, so each start wipes it. */
    private fun fresh(version: Int): SQLiteConnection {
        file.parentFile?.mkdirs()
        for (suffix in listOf("", "-journal", "-wal", "-shm")) File(file.path + suffix).delete()
        return helper.createDatabase(version)
    }

    @Test
    fun migrateFromFirstToCurrent() {
        fresh(version = 1).use { connection ->
            // A novel and a chapter as the first version stored them, to come through every change since.
            connection.execSQL(
                "INSERT INTO novels (id, source_id, url_key, url, title, genres, status, in_library, created_at) VALUES (1, 'royalroad', 'fiction/1', 'https://www.royalroad.com/fiction/1', 'The Glass Orchard', '', 'Ongoing', 1, 1700000000000)",
            )
            connection.execSQL(
                "INSERT INTO chapters (id, novel_id, url_key, url, title, source_index, added_at) VALUES (1, 1, 'fiction/1/chapter/1', 'https://www.royalroad.com/fiction/1/chapter/1', 'Chapter 1', 0, 1700000000000)",
            )
        }
        helper.runMigrationsAndValidate(version = AppDatabase.VERSION, migrations = MIGRATIONS.toList()).use { connection ->
            connection.prepare("SELECT title FROM novels WHERE id = 1").use { statement ->
                check(statement.step()) { "The novel was lost on the way" }
                check(statement.getText(0) == "The Glass Orchard")
            }
            connection.prepare("SELECT COUNT(*) FROM chapters WHERE novel_id = 1").use { statement ->
                check(statement.step() && statement.getLong(0) == 1L) { "The chapter was lost on the way" }
            }
        }
    }

    @Test
    fun eachStepOnItsOwn() {
        for (migration in MIGRATIONS) {
            fresh(version = migration.startVersion).close()
            helper.runMigrationsAndValidate(version = migration.endVersion, migrations = listOf(migration)).close()
        }
    }
}
