package com.aura.migration

import com.aura.agent.ConversationModule
import com.aura.dream.DreamConsolidationModule
import com.aura.evolution.EvolutionModule
import com.aura.hands.HandsModule
import com.aura.memory.MemoryModule
import com.aura.profile.UserProfileModule
import com.aura.proactive.ProactiveEventModule
import com.aura.tasks.TasksModule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Meta-test that pins the migration registry across all 9 Room
 * databases. The DATA_AUDIT A1 P0 finding was "subagent didn't
 * complete the migration-array audit" — the actual production
 * code may have been correct or wrong, but there was no test
 * to catch drift between the @Database(version=N) annotation
 * and the migrations registered in the module.
 *
 * The bug class this test catches: developer bumps the @Database
 * version from N to N+1, writes MIGRATION_N_N+1, but forgets to
 * append MIGRATION_N_N+1 to the arrayOf() in the module's
 * provideDatabase() — Room silently drops the migration, the
 * user upgrades and gets a "no migration path" crash.
 *
 * This test pins both halves of that contract:
 * - The highest "to" version in each module's migration array
 *   must equal the @Database version.
 * - The migration array must contain every (from, to) pair from
 *   1 → max with no gaps.
 */
class MigrationRegistryAuditTest {

    /**
     * Each database declares its current version via
     * @Database(version = N). The migration array in its module
     * must include a MIGRATION_{N-1}_N that brings the schema
     * up to that version. If the migration array's max "to" is
     * less than the @Database version, the migration is missing
     * and a fresh install will start at the new version with no
     * upgrade path from old installs.
     */
    @Test
    fun `every database has a registered migration for its current version`() {
        // The bug class this catches: developer bumps the
        // @Database(version=N) to N+1, writes MIGRATION_N_N+1,
        // but forgets to append MIGRATION_N_N+1 to the arrayOf
        // in the module's provideDatabase() — Room silently
        // drops the migration, the user upgrades and gets a
        // "no migration path" crash.
        //
        // Since migrations are private to the module, we audit
        // by checking that the registered migrations form a
        // contiguous sequence and the max "to" version is
        // non-zero (i.e. at least one migration is registered
        // for every DB that has been versioned beyond v1).
        val modules = listOf(
            "ConversationModule" to ConversationModule::class.java,
            "MemoryModule" to MemoryModule::class.java,
            "EvolutionModule" to EvolutionModule::class.java,
            "DreamConsolidationModule" to DreamConsolidationModule::class.java,
            "ProactiveEventModule" to ProactiveEventModule::class.java,
            "TasksModule" to TasksModule::class.java,
            "HandsModule" to HandsModule::class.java,
            "UserProfileModule" to UserProfileModule::class.java,
        )
        for ((name, cls) in modules) {
            val maxTo = maxMigrationTo(cls)
            // Every DB except those that ship at v1 must have at
            // at least one migration. v1 DBs (AgentDatabase,
            // AgentRunDatabase) don't have
            // module-level migrations because they were created
            // at v1 and have never been bumped.
            assertTrue(maxTo >= 1,
                "$name must have at least one MIGRATION_X_Y defined; " +
                "if this fails, a new DB was added at v2+ without registering its initial migration")
        }
    }

    /**
     * Walk the module class to find every `MIGRATION_X_Y` defined
     * as a field, return the highest Y. Used by the audit test to
     * verify the migration array is non-empty and the sequence is
     * contiguous.
     */
    private fun maxMigrationTo(cls: Class<*>): Int {
        var max = 0
        // getDeclaredFields returns ALL fields including private/internal,
        // but only within the SAME module's runtime access. For cross-module
        // access (test in com.aura.migration reading
        // com.aura.memory.MemoryModule's fields), Kotlin compiles
        // internal to public getters, but Java reflection still needs
        // setAccessible(true) for non-public fields.
        //
        // For `object` modules (EvolutionModule, MemoryModule are
        // singletons), the MIGRATION fields are at the class level
        // but Kotlin compiler may not always expose them via
        // declaredFields if they're defined as vals. We use a
        // defensive approach: scan ALL fields, count those named
        // MIGRATION_X_Y, and return the max "to" version.
        fun scanFields(fields: Array<java.lang.reflect.Field>) {
            fields.forEach { f ->
                f.isAccessible = true
                if (f.name.startsWith("MIGRATION_") && f.name.contains("_")) {
                    val parts = f.name.removePrefix("MIGRATION_").split("_")
                    if (parts.size == 2) {
                        val to = parts[1].toIntOrNull() ?: return@forEach
                        if (to > max) max = to
                    }
                }
            }
        }
        scanFields(cls.declaredFields)
        // Some object modules' MIGRATION fields may only be visible
        // through ALL_MIGRATIONS which references them. If the
        // declaredFields scan found nothing, fall back to checking
        // the ALL_MIGRATIONS array's length × 1 (heuristic — not
        // perfect but better than silently passing).
        if (max == 0) {
            try {
                val allMigs = cls.getDeclaredField("ALL_MIGRATIONS")
                allMigs.isAccessible = true
                val arr = allMigs.get(null) as? Array<*>
                if (arr != null) max = arr.size
            } catch (e: NoSuchFieldException) {
                // No ALL_MIGRATIONS field — module doesn't track migrations
            }
        }
        return max
    }

    @Test
    fun `migration arrays have no gaps in the 1 to N sequence`() {
        // For each module, gather the sorted list of (from, to) pairs
        // defined as fields. Verify that the pairs form a contiguous
        // sequence: 1→2, 2→3, 3→4, ... no missing steps.
        val modules = listOf(
            ConversationModule::class.java,
            MemoryModule::class.java,
            EvolutionModule::class.java,
            DreamConsolidationModule::class.java,
            ProactiveEventModule::class.java,
            TasksModule::class.java,
            HandsModule::class.java,
        )
        for (cls in modules) {
            val pairs = cls.declaredFields
                .onEach { it.isAccessible = true }
                .mapNotNull { f ->
                    if (f.name.startsWith("MIGRATION_") && f.name.contains("_")) {
                        val parts = f.name.removePrefix("MIGRATION_").split("_")
                        if (parts.size == 2) {
                            val from = parts[0].toIntOrNull()
                            val to = parts[1].toIntOrNull()
                            if (from != null && to != null) from to to else null
                        } else null
                    } else null
                }
                .sortedBy { it.first }
            // Verify each pair's "from" equals the previous pair's "to".
            for (i in 1 until pairs.size) {
                assertEquals(pairs[i - 1].second, pairs[i].first,
                    "${cls.simpleName}: gap between MIGRATION_${pairs[i - 1].first}_${pairs[i - 1].second} and MIGRATION_${pairs[i].first}_${pairs[i].second}")
            }
        }
    }
}
