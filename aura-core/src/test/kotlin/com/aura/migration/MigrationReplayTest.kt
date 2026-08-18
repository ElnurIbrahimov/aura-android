package com.aura.migration

import android.content.Context
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.aura.agent.AgentDatabase
import com.aura.agent.ConversationModule
import com.aura.agent.requireNonEmpty
import com.aura.agent.sourceDir
import com.aura.dream.DreamConsolidationModule
import com.aura.hands.HandsModule
import com.aura.memory.MemoryModule
import com.aura.proactive.ProactiveEventModule
import com.aura.tasks.TasksModule
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertTrue

/**
 * Replay every registered migration against the committed schema exports, on
 * the JVM.
 *
 * Thirty-eight migrations guard every piece of data in this app, and until now
 * nothing verified any of them. The tests that would have — Room's
 * `MigrationTestHelper` suites — are instrumented, instrumented tests need a
 * device, and CI has none, so they had never been run. That is not a
 * hypothetical gap: `MIGRATION_1_2` for `EvolutionDatabase` added two of the
 * four columns `2.json` declares, which meant any database still at v1 migrated
 * into a table Room rejects on open — `IllegalStateException: Migration didn't
 * properly handle: evolution_settings`, at startup, every launch, unrecoverable
 * without clearing app data. It was found by hand.
 *
 * This needs no device because it needs no Room. A schema export already
 * contains the `createSql` for every table it declares, so a version can be
 * built directly from `N.json`, the migration run against it, and the result
 * compared to `N+1.json` — which is exactly what the instrumented helper does,
 * minus the emulator.
 *
 * Two distinct defects fail this test, and the message says which:
 *
 * - **A migration does not produce what the next version declares.** The
 *   EvolutionDatabase defect above. This is the one that reaches users.
 * - **A schema export is not a faithful record of its version.** These files
 *   are supposed to be immutable history, but a build regenerates whichever
 *   version is current, so a bulk regeneration silently rewrites old ones with
 *   today's entity set. `MemoryDatabase/7.json` declares 24 tables while
 *   `MIGRATION_6_7` creates one; it and `8.json` were committed together, after
 *   `11.json`. Those exports describe no version that ever existed. Nothing at
 *   runtime reads them — Room validates only the final schema on open — so this
 *   half is a test-integrity problem rather than a user-facing one, but it also
 *   means the migration chain has no trustworthy baseline to be checked against.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MigrationReplayTest {

    /**
     * A database, the directory its exports live in, and the migrations
     * registered for it.
     *
     * Deliberately written out by hand rather than discovered by reflection.
     * The reflective sibling in this package walks `declaredFields` looking for
     * names starting with `MIGRATION_`, finds nothing for the modules that
     * declare theirs at file scope, and passes over an empty list — the failure
     * mode this whole file exists to stop. Naming them makes the compiler the
     * gate: rename or delete a migration and this stops compiling.
     */
    private data class DatabaseUnderTest(val schemaDir: String, val migrations: List<Migration>)

    /**
     * The one hop whose baseline cannot be trusted, and why.
     *
     * `MemoryDatabase/6.json` declares 6 tables. `7.json` declares 24 — the
     * whole creative, world-model and taste surface — while `MIGRATION_6_7`
     * creates exactly one table and four columns. The exports are not the
     * cause of a bug so much as the loss of a record: `7.json` and `8.json`
     * were committed in the same commit on 2026-07-26, *after* `11.json` on
     * 07-16, so they are bulk regenerations of whatever the entity set was on
     * the day someone ran a build, not descriptions of versions that existed.
     *
     * 7 through 11 are all mutually consistent because they are all the same
     * snapshot, and every migration uses `CREATE TABLE IF NOT EXISTS`, so the
     * hops *between* them replay clean. Only the 6->7 boundary, where the real
     * history is still visible on one side, shows the discontinuity.
     *
     * This is deliberately not silenced with a blanket skip. Recovering the
     * true v7..v11 exports means checking out the commit where each version was
     * current and re-running KSP — real archaeology, and it buys nothing at
     * runtime, because Room validates only the final schema when it opens a
     * database. What it would buy is a trustworthy baseline for the earliest
     * hops of the chain. Until someone does that, this entry is the honest
     * record of what is not covered.
     *
     * To remove: regenerate `MemoryDatabase/7.json` from the commit where
     * `@Database(version = 7)` was current, then delete this line and watch the
     * test go green on its own.
     */
    private val untrustedBaselines = setOf("com.aura.memory.MemoryDatabase 6->7")

    private fun databases(): List<DatabaseUnderTest> = listOf(
        DatabaseUnderTest(
            "com.aura.memory.MemoryDatabase",
            listOf(
                MemoryModule.MIGRATION_1_2, MemoryModule.MIGRATION_2_3, MemoryModule.MIGRATION_3_4,
                MemoryModule.MIGRATION_4_5, MemoryModule.MIGRATION_5_6, MemoryModule.MIGRATION_6_7,
                MemoryModule.MIGRATION_7_8, MemoryModule.MIGRATION_8_9, MemoryModule.MIGRATION_9_10,
                MemoryModule.MIGRATION_10_11, MemoryModule.MIGRATION_11_12, MemoryModule.MIGRATION_12_13,
                MemoryModule.MIGRATION_13_14, MemoryModule.MIGRATION_14_15, MemoryModule.MIGRATION_15_16,
                MemoryModule.MIGRATION_16_17,
                MemoryModule.MIGRATION_17_18,
                MemoryModule.MIGRATION_18_19,
                MemoryModule.MIGRATION_19_20,
                MemoryModule.MIGRATION_20_21,
                MemoryModule.MIGRATION_21_22,
                MemoryModule.MIGRATION_22_23,
                MemoryModule.MIGRATION_23_24,
                MemoryModule.MIGRATION_24_25,
                MemoryModule.MIGRATION_25_26,
                MemoryModule.MIGRATION_26_27,
                MemoryModule.MIGRATION_27_28, MemoryModule.MIGRATION_28_29,
            ),
        ),
        DatabaseUnderTest(
            "com.aura.agent.ConversationDatabase",
            listOf(
                ConversationModule.MIGRATION_1_2, ConversationModule.MIGRATION_2_3,
                ConversationModule.MIGRATION_3_4, ConversationModule.MIGRATION_4_5,
                ConversationModule.MIGRATION_5_6,
            ),
        ),
        DatabaseUnderTest(
            "com.aura.evolution.EvolutionDatabase",
            com.aura.evolution.EvolutionModule.ALL_MIGRATIONS.toList(),
        ),
        DatabaseUnderTest("com.aura.agent.AgentDatabase", AgentDatabase.ALL_MIGRATIONS.toList()),
        DatabaseUnderTest(
            "com.aura.proactive.ProactiveEventDatabase",
            listOf(
                ProactiveEventModule.MIGRATION_1_2, ProactiveEventModule.MIGRATION_2_3,
                ProactiveEventModule.MIGRATION_3_4, ProactiveEventModule.MIGRATION_4_5,
                ProactiveEventModule.MIGRATION_5_6, ProactiveEventModule.MIGRATION_6_7,
            ),
        ),
        DatabaseUnderTest(
            "com.aura.tasks.TaskDatabase",
            listOf(
                TasksModule.MIGRATION_1_2, TasksModule.MIGRATION_2_3,
                TasksModule.MIGRATION_3_4, TasksModule.MIGRATION_4_5,
                TasksModule.MIGRATION_5_6,
            ),
        ),
        DatabaseUnderTest("com.aura.hands.HandDatabase", listOf(HandsModule.MIGRATION_1_2)),
        DatabaseUnderTest(
            "com.aura.dream.DreamConsolidationDatabase",
            listOf(DreamConsolidationModule.MIGRATION_1_2, DreamConsolidationModule.MIGRATION_2_3),
        ),
    )

    // ------------------------------------------------------------------ schema

    private class TableSchema(val name: String, val columns: Map<String, Boolean>)

    private fun schemaRoot(): File = File(sourceDir("schemas").absolutePath)

    private fun exportFile(dir: String, version: Int) = File(schemaRoot(), "$dir/$version.json")

    /** The tables a schema export declares, with each column's NOT NULL flag. */
    private fun declaredTables(file: File): Map<String, TableSchema> {
        val db = JSONObject(file.readText()).getJSONObject("database")
        val entities = db.getJSONArray("entities")
        val out = LinkedHashMap<String, TableSchema>()
        for (i in 0 until entities.length()) {
            val e = entities.getJSONObject(i)
            val table = e.getString("tableName")
            val fields = e.getJSONArray("fields")
            val cols = LinkedHashMap<String, Boolean>()
            for (j in 0 until fields.length()) {
                val f = fields.getJSONObject(j)
                cols[f.getString("columnName")] = f.optBoolean("notNull", false)
            }
            out[table] = TableSchema(table, cols)
        }
        return out
    }

    /** Build the schema a version declares, so a migration can be run against it. */
    private fun materialise(db: SupportSQLiteDatabase, file: File) {
        val database = JSONObject(file.readText()).getJSONObject("database")
        val entities = database.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val e = entities.getJSONObject(i)
            val table = e.getString("tableName")
            // Room stores the table name as a placeholder so the same SQL can
            // build the real table and its temp copy during a destructive
            // migration.
            db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
            val indices = e.optJSONArray("indices") ?: continue
            for (j in 0 until indices.length()) {
                db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
        }
        val views = database.optJSONArray("views")
        if (views != null) {
            for (i in 0 until views.length()) {
                db.execSQL(views.getJSONObject(i).getString("createSql"))
            }
        }
    }

    /** What the database actually contains after a migration has run. */
    private fun actualColumns(db: SupportSQLiteDatabase, table: String): Map<String, Boolean>? {
        db.query("SELECT name FROM sqlite_master WHERE type IN ('table','view') AND name = ?", arrayOf(table))
            .use { if (!it.moveToFirst()) return null }
        val cols = LinkedHashMap<String, Boolean>()
        db.query("PRAGMA table_info(`$table`)").use { c ->
            val nameIdx = c.getColumnIndexOrThrow("name")
            val notNullIdx = c.getColumnIndexOrThrow("notnull")
            while (c.moveToNext()) cols[c.getString(nameIdx)] = c.getInt(notNullIdx) == 1
        }
        return cols
    }

    private fun openBlank(): SupportSQLiteDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val callback = object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) = Unit
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null) // in-memory
            .callback(callback)
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
    }

    // ------------------------------------------------------------------- tests

    @Test
    fun `every migration produces the schema its target version declares`() {
        val problems = mutableListOf<String>()
        var hops = 0

        val allowlistHit = mutableSetOf<String>()

        for (target in databases()) {
            for (migration in target.migrations.sortedBy { it.startVersion }) {
                val hop = "${target.schemaDir} ${migration.startVersion}->${migration.endVersion}"
                if (hop in untrustedBaselines) {
                    allowlistHit += hop
                    continue
                }
                val from = exportFile(target.schemaDir, migration.startVersion)
                val to = exportFile(target.schemaDir, migration.endVersion)
                if (!from.isFile || !to.isFile) {
                    problems += "${target.schemaDir}: MIGRATION_${migration.startVersion}_" +
                        "${migration.endVersion} has no committed schema export on " +
                        (if (!from.isFile) "the ${migration.startVersion} side" else "the ${migration.endVersion} side") +
                        " — a migration with no baseline cannot be verified by anything"
                    continue
                }
                hops++

                val db = openBlank()
                try {
                    materialise(db, from)
                    val migrationError = runCatching { migration.migrate(db) }.exceptionOrNull()
                    if (migrationError != null) {
                        problems += "${target.schemaDir} ${migration.startVersion}->${migration.endVersion}: " +
                            "migrate() threw ${migrationError::class.simpleName}: ${migrationError.message}"
                        continue
                    }

                    for ((table, expected) in declaredTables(to)) {
                        // FTS content tables and their shadow tables are created
                        // by triggers and helpers rather than by the migration
                        // body; PRAGMA reports the virtual table without its
                        // backing columns, so comparing them is not meaningful.
                        if (table.endsWith("_fts")) continue

                        val actual = actualColumns(db, table)
                        if (actual == null) {
                            problems += "${target.schemaDir} ${migration.startVersion}->${migration.endVersion}: " +
                                "table '$table' is declared by ${migration.endVersion}.json but the migration " +
                                "does not create it"
                            continue
                        }
                        val missing = expected.columns.keys - actual.keys
                        if (missing.isNotEmpty()) {
                            problems += "${target.schemaDir} ${migration.startVersion}->${migration.endVersion}: " +
                                "table '$table' is missing ${missing.size} column(s) the target version " +
                                "declares: ${missing.sorted().joinToString(", ")}"
                        }
                        val nullabilityDrift = expected.columns
                            .filterKeys { it in actual }
                            .filter { (col, notNull) -> actual[col] != notNull }
                            .keys
                        if (nullabilityDrift.isNotEmpty()) {
                            problems += "${target.schemaDir} ${migration.startVersion}->${migration.endVersion}: " +
                                "table '$table' has NOT NULL drift on: ${nullabilityDrift.sorted().joinToString(", ")}"
                        }
                    }
                } finally {
                    db.close()
                }
            }
        }

        assertTrue(hops > 0, "no migration hop was replayed — the schema exports were not found, so this proves nothing")
        // An allowlist that outlives the thing it excuses is how a gate rots
        // into a rubber stamp. If an entry stops matching a real hop, it is
        // stale and must go.
        assertTrue(
            allowlistHit == untrustedBaselines,
            "untrustedBaselines names hop(s) that no longer exist: " +
                (untrustedBaselines - allowlistHit).joinToString(", ") +
                " — remove them rather than leaving coverage silently excused",
        )
        assertTrue(
            problems.isEmpty(),
            "Replaying the registered migrations against the committed schema exports found " +
                "${problems.size} problem(s) across $hops hop(s).\n" +
                "Either a migration does not produce what the next version declares — the defect that " +
                "crashed the app on launch for v1 evolution databases — or a schema export is not a " +
                "faithful record of its version, which leaves the chain with no trustworthy baseline.\n" +
                problems.joinToString("\n") { "  - $it" },
        )
    }

    /**
     * The registry above is hand-written, so it can drift from what the modules
     * actually register. This pins the two halves against each other: every
     * version between 1 and the highest export must be reachable.
     */
    @Test
    fun `the registry covers every exported version with no gaps`() {
        val checked = databases().requireNonEmpty("databases under replay test")
        for (target in checked) {
            val dir = File(schemaRoot(), target.schemaDir)
            val versions = dir.listFiles { f: File -> f.extension == "json" }
                .orEmpty()
                .mapNotNull { it.nameWithoutExtension.toIntOrNull() }
                .sorted()
            assertTrue(versions.isNotEmpty(), "${target.schemaDir}: no schema exports found")
            val highest = versions.max()
            val hops = target.migrations.map { it.startVersion to it.endVersion }.toSet()
            val missing = (1 until highest).filterNot { (it to it + 1) in hops }
            assertTrue(
                missing.isEmpty(),
                "${target.schemaDir}: exports go up to $highest but no migration is registered here for " +
                    missing.joinToString(", ") { "$it->${it + 1}" },
            )
        }
    }
}
