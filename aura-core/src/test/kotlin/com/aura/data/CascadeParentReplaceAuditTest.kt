package com.aura.data

import com.aura.agent.requireNonEmpty
import com.aura.agent.sourceDir
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * `@Insert(onConflict = REPLACE)` must never target a table that other tables
 * declare `ON DELETE CASCADE` against.
 *
 * `OnConflictStrategy.REPLACE` compiles to SQLite's `INSERT OR REPLACE`, which
 * is a **DELETE followed by an INSERT**, not an UPDATE. Room enables
 * `PRAGMA foreign_keys = ON`, so that implicit delete fires every CASCADE
 * action pointed at the row. Re-saving a parent therefore destroys all of its
 * children, and then re-inserts the parent so the table still looks intact.
 *
 * This is not a hypothetical. [com.aura.creative.CreativeProjectDao] carries a
 * post-mortem of it in its own KDoc: a long-form run drafted thirteen scenes
 * and finished with `creative_artifacts`, `creative_revisions`,
 * `creative_branches` and `creative_generation_jobs` all holding zero rows,
 * because marking each beat re-saved the project. That fix added targeted
 * column-named updates for `creative_projects` — and stopped there. The same
 * defect was still live on five other parents, including `creative_artifacts`
 * one level down, where writing a new revision deleted every revision of that
 * artifact including the one just written.
 *
 * ENGINEERING_HISTORY §4 names the pattern: a fix applied to N−1 of N places.
 * This test is the N-th place, permanently.
 *
 * **The fix is never "delete the annotation".** Pick by intent:
 * - genuine update-or-insert -> `@Upsert` (a real UPDATE; does not cascade)
 * - insert-only, conflict is a bug -> plain `@Insert` (ABORT; throws loudly)
 * - targeted field change -> `@Query("UPDATE … SET … WHERE id = :id")`
 *
 * Note that `@Upsert` conflicts on the primary key only. An entity carrying a
 * `unique = true` index can still collide there, and that collision throws
 * rather than cascading — which is the correct, loud outcome.
 */
class CascadeParentReplaceAuditTest {

    /** `entity = Foo::class, … onDelete = ForeignKey.CASCADE` — FQNs allowed. */
    private val foreignKeyBlock = Regex("""ForeignKey\(([^()]*)\)""")
    private val cascadeParent = Regex("""entity\s*=\s*([A-Za-z0-9_.]+)::class""")

    /**
     * `@Insert(onConflict = …REPLACE)` followed by its function, capturing the
     * first parameter's type. Tolerates `suspend`, `List<T>`, and FQNs.
     */
    private val replaceInsert = Regex(
        """@Insert\s*\(\s*onConflict\s*=\s*(?:androidx\.room\.)?OnConflictStrategy\.REPLACE\s*\)\s*""" +
            """(?:suspend\s+)?fun\s+(\w+)\s*\(\s*\w+\s*:\s*(?:(?:kotlin\.collections\.)?List<)?([A-Za-z0-9_.]+)""",
    )

    private fun kotlinSources(): List<File> =
        sourceDir("src/main/kotlin/com/aura")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
            .requireNonEmpty("Kotlin sources under src/main/kotlin/com/aura")

    /** Simple name of a possibly-qualified type reference. */
    private fun simpleName(ref: String) = ref.substringAfterLast('.')

    private fun cascadeParents(files: List<File>): Set<String> {
        val parents = mutableSetOf<String>()
        for (file in files) {
            val text = file.readText()
            if (!text.contains("ForeignKey")) continue
            for (block in foreignKeyBlock.findAll(text)) {
                val body = block.groupValues[1]
                if (!body.contains("ForeignKey.CASCADE")) continue
                val parent = cascadeParent.find(body)?.groupValues?.get(1) ?: continue
                parents += simpleName(parent)
            }
        }
        return parents
    }

    @Test
    fun `no REPLACE insert targets a table other tables cascade off`() {
        val files = kotlinSources()
        val parents = cascadeParents(files)
            .toList()
            .requireNonEmpty("entities declared as ON DELETE CASCADE parents")
            .toSet()

        val violations = mutableListOf<String>()
        for (file in files) {
            val text = file.readText()
            if (!text.contains("OnConflictStrategy.REPLACE")) continue
            for (match in replaceInsert.findAll(text)) {
                val (function, entityRef) = match.destructured
                val entity = simpleName(entityRef)
                if (entity in parents) {
                    violations += "${file.name}: $function($entity) — $entity is a CASCADE parent, " +
                        "so INSERT OR REPLACE deletes its children before re-inserting it"
                }
            }
        }

        assertTrue(
            violations.isEmpty(),
            "INSERT OR REPLACE on a CASCADE parent silently destroys child rows.\n" +
                "Use @Upsert (real UPDATE), plain @Insert (ABORT), or a targeted @Query UPDATE.\n" +
                violations.joinToString("\n") { "  - $it" },
        )
    }

    /**
     * The scan above proves nothing if either half matched nothing, and both
     * halves are regexes over hand-written source. Pin the two shapes this test
     * depends on against entities that exist today, so a formatting change that
     * silently breaks a pattern fails here rather than turning the real
     * assertion into a no-op.
     */
    @Test
    fun `the scan recognises the shapes it depends on`() {
        val files = kotlinSources()
        val parents = cascadeParents(files)

        // Both spellings appear in the tree: same-package short name (NodeEntity,
        // via kg/KgEntities.kt) and fully-qualified (com.aura.agent.AgentEntity,
        // via agent/state/AgentStateEntity.kt).
        assertTrue("NodeEntity" in parents, "expected NodeEntity among CASCADE parents, found: $parents")
        assertTrue("AgentEntity" in parents, "expected AgentEntity among CASCADE parents, found: $parents")

        val replaceTargets = files
            .filter { it.readText().contains("OnConflictStrategy.REPLACE") }
            .flatMap { file -> replaceInsert.findAll(file.readText()).map { simpleName(it.destructured.component2()) } }
            .toList()
            .requireNonEmpty("@Insert(REPLACE) declarations")

        // REPLACE is correct and common on tables nothing cascades off; the
        // audit only bans it on parents. If this ever comes back empty the
        // function-signature half of the regex has broken.
        assertTrue(
            replaceTargets.any { it !in parents },
            "expected some REPLACE inserts on non-parent tables; the function regex may have broken",
        )
    }
}
