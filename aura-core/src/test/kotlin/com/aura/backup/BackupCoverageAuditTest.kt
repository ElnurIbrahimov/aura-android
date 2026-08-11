package com.aura.backup

import com.aura.agent.requireNonEmpty
import com.aura.agent.sourceDir
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Everything a restore can delete, a restore must be able to put back.
 *
 * This is the gate for a defect class that has now been found twice by hand and
 * never by a test. `restore()` wraps its write phase in a rollback: on failure
 * it calls `purgeAll()` and then re-writes the pre-restore snapshot. For nine
 * tables — evolution proposals, revisions and settings, every learned
 * strategy-bandit weight, and all five council tables — `purgeAll` cleared them
 * and the rollback wrote nothing back, because the functions that restore them
 * sat on the success path and took the incoming backup rather than the
 * snapshot. The safety net destroyed data the failure itself would have left
 * alone, and the UI reported "Restore failed", which reads as "nothing
 * happened".
 *
 * That was fixed by reading all ~900 lines of BackupManager and reasoning about
 * them. Nothing stops it regressing, and the same reasoning has to be redone
 * every time a table is added — which is the shape of every other gap this file
 * covers: consciousness state and tool policies were absent from the backup for
 * months, recorded in ENGINEERING_HISTORY §3 as known, and no test disagreed.
 *
 * Source-scanning rather than reflective, because the invariant is about which
 * DAOs a *function body* touches, and Kotlin erases that at runtime. The scan
 * fails loudly on an empty match set — a version of this test that resolved the
 * wrong path would otherwise pass having read nothing, which is the exact
 * defect ENGINEERING_HISTORY §2.6 records.
 */
class BackupCoverageAuditTest {

    private fun backupManagerSource(): String =
        File(sourceDir("src/main/kotlin/com/aura"), "backup/BackupManager.kt")
            .also { check(it.isFile) { "BackupManager.kt not found at ${it.absolutePath}" } }
            .readText()

    /** Body of a top-level `fun name(` in [source], by brace matching. */
    private fun functionBody(source: String, name: String): String {
        val header = Regex("""(?:private |internal |public )?suspend fun $name\s*\(""").find(source)
            ?: error("function '$name' not found in BackupManager.kt — this test is reading the wrong shape")
        var depth = 0
        var started = false
        for (i in header.range.first until source.length) {
            when (source[i]) {
                '{' -> { depth++; started = true }
                '}' -> {
                    depth--
                    if (started && depth == 0) return source.substring(header.range.first, i)
                }
            }
        }
        error("unbalanced braces reading '$name'")
    }

    /** DAO properties a body calls a clearing method on. */
    private val clearCall =
        Regex("""(\w+Dao)\??\.(deleteAll|deleteAllCustom|clear|purge)\w*\(""")

    /** DAO properties a body calls an inserting method on. */
    private val writeCall =
        Regex("""(\w+Dao)\??\.(insert|insertAll|upsert|upsertAll|restore|save)\w*\(""")

    private fun daosMatching(body: String, pattern: Regex): Set<String> =
        pattern.findAll(body).map { it.groupValues[1] }.toSet()

    /**
     * Tables `purgeAll` clears that no restore path writes.
     *
     * Empty is the only acceptable value. An entry here would mean a failed
     * restore permanently destroys that table.
     */
    @Test
    fun `every table the rollback purges is one a restore can write back`() {
        val src = backupManagerSource()

        val purged = daosMatching(functionBody(src, "purgeAll"), clearCall)
            .toList()
            .requireNonEmpty("DAOs cleared by purgeAll")
            .toSet()

        // Every write anywhere in the file except inside purgeAll itself.
        //
        // Deliberately not a list of function names. The first draft of this
        // test named the restore helpers explicitly and reported three
        // evolution tables as orphaned — because the helper is called
        // `restoreEvolutionRows` and the list guessed `restoreEvolutionRoom`.
        // A gate whose correctness depends on guessing private method names
        // will produce exactly that: a confident, wrong failure, which is worse
        // than no gate because someone will act on it.
        //
        // The structural half of the invariant is asserted separately below:
        // that both the success path and the rollback go through ONE writer, so
        // "written somewhere in this file" and "written on the rollback path"
        // cannot drift apart the way they had before schema v18.
        val purgeBody = functionBody(src, "purgeAll")
        val written = daosMatching(src.replace(purgeBody, ""), writeCall)
            .toList()
            .requireNonEmpty("DAOs written by a restore path")
            .toSet()

        val orphaned = (purged - written).sorted()
        assertTrue(
            orphaned.isEmpty(),
            "purgeAll clears ${orphaned.size} table(s) that no restore path writes back:\n" +
                orphaned.joinToString("\n") { "  - $it" } +
                "\nA failed restore calls purgeAll and then re-writes the pre-restore snapshot. " +
                "Anything cleared but not written is destroyed by the rollback — the safety net " +
                "becoming the data-loss vector, which is what happened to nine tables before " +
                "schema v18.",
        )
    }

    /**
     * The success path and the rollback must write through the same function.
     *
     * This is the structural half of the invariant above. Before schema v18 the
     * two paths were different code: the success path called `writeRows` plus
     * five `restoreX` helpers, and the rollback called `writeRows` alone — so
     * the nine tables only the helpers touched were purged and never written
     * back. Coverage checked over the whole file cannot see that split; this
     * can.
     */
    @Test
    fun `the rollback and the success path share one writer`() {
        val src = backupManagerSource()
        val restoreBody = functionBody(src, "restore")

        val writerCalls = Regex("""\bwriteEverything\s*\(""").findAll(restoreBody).count()
        assertTrue(
            writerCalls >= 2,
            "restore() calls a shared writer $writerCalls time(s); expected at least two — one for the " +
                "successful import and one for the rollback. If the rollback has its own write path again, " +
                "anything only the success path knows how to restore is destroyed by a failed import.",
        )

        // And the shared writer must actually write: without this, the check
        // above would pass over an empty shell that both paths call. It is a
        // delegator, so the DAOs are counted one level down through the private
        // helpers it calls — which is also what makes the count meaningful,
        // since those helpers are precisely the code that used to live only on
        // the success path.
        val reached = reachableDaoWrites(src, "writeEverything")
        assertTrue(
            reached.size >= 20,
            "the shared writer reaches only ${reached.size} DAO write(s) across itself and the helpers it " +
                "calls, which is too few for eleven databases. Either it has been hollowed out, or a helper " +
                "was moved back off the shared path — which is the arrangement that destroyed nine tables " +
                "before schema v18.",
        )
    }

    /** DAO writes in [entry] plus the private helpers it calls, one level down. */
    private fun reachableDaoWrites(src: String, entry: String): Set<String> {
        val body = functionBody(src, entry)
        val direct = daosMatching(body, writeCall)
        val calledHelpers = Regex("""\b(restore\w+|write\w+|insert\w+)\s*\(""")
            .findAll(body)
            .map { it.groupValues[1] }
            .filter { it != entry }
            .toSet()
        val indirect = calledHelpers
            .mapNotNull { name -> runCatching { functionBody(src, name) }.getOrNull() }
            .flatMap { daosMatching(it, writeCall) }
        return direct + indirect
    }

    /**
     * Every Room entity must have somewhere to live in a backup file.
     *
     * The three exclusions are stated rather than inferred, because "no backup
     * class" and "deliberately not backed up" are indistinguishable from the
     * outside — which is how consciousness state stayed out of the schema for
     * months while being listed as a known gap.
     */
    @Test
    fun `every persisted entity has a backup representation`() {
        val mainSrc = sourceDir("src/main/kotlin/com/aura")
        val kotlinFiles = mainSrc.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
            .requireNonEmpty("Kotlin sources")

        val entityToDatabase = LinkedHashMap<String, String>()
        val databaseDecl = Regex("""@Database\s*\(([^)]*?)\)\s*(?:@\w+(?:\([^)]*\))?\s*)*abstract class (\w+)""", RegexOption.DOT_MATCHES_ALL)
        val entityList = Regex("""entities\s*=\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
        for (file in kotlinFiles) {
            val text = file.readText()
            if ("@Database" !in text) continue
            for (db in databaseDecl.findAll(text)) {
                val entities = entityList.find(db.groupValues[1]) ?: continue
                for (e in Regex("""(\w+)::class""").findAll(entities.groupValues[1])) {
                    entityToDatabase[e.groupValues[1]] = db.groupValues[2]
                }
            }
        }
        val entities = entityToDatabase.keys.toList().requireNonEmpty("Room entities")

        val allSource = kotlinFiles.joinToString("\n") { it.readText() }

        val uncovered = entities
            .filterNot { it in DERIVED_OR_TRANSIENT }
            .filterNot { entity ->
                val base = entity.removeSuffix("Entity")
                Regex("""\bclass ${Regex.escape(base)}Backup\b""").containsMatchIn(allSource)
            }
            .sorted()

        assertTrue(
            uncovered.isEmpty(),
            "${uncovered.size} entity(ies) have no backup class and are not declared derived:\n" +
                uncovered.joinToString("\n") { "  - $it (${entityToDatabase[it]})" } +
                "\nAdd a ${'$'}{Name}Backup and wire it through snapshot() and the restore path, or add it " +
                "to DERIVED_OR_TRANSIENT with the reason it does not need one.",
        )

        // The exclusion list must not outlive what it excuses.
        val stale = DERIVED_OR_TRANSIENT.filterNot { it in entityToDatabase }
        assertTrue(
            stale.isEmpty(),
            "DERIVED_OR_TRANSIENT names entities that no longer exist: ${stale.joinToString(", ")}",
        )
    }

    private companion object {
        /**
         * Entities that correctly have no backup representation.
         *
         * - `MemoryFtsEntity` is the full-text index over `memories`. It is
         *   maintained by SQL triggers and rebuilt from the content table, so
         *   exporting it would store a derivable artefact and risk restoring an
         *   index inconsistent with the rows it indexes.
         * - `CreativeGenerationJobEntity` is in-flight generation work. A job
         *   restored onto another device refers to a request that is no longer
         *   running anywhere; it is documented as transient at its declaration
         *   and in AuraBackupSchema13.
         */
        private val DERIVED_OR_TRANSIENT = setOf(
            "MemoryFtsEntity",
            "CreativeGenerationJobEntity",
        )
    }
}
