package com.aura.config

import com.aura.agent.requireNonEmpty
import com.aura.agent.sourceDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * That a config field cannot be declared, persisted, and never read.
 *
 * This repo has now found the same defect four times: `ToolPolicy.allowedScopes`
 * and `PolicyResult.ScopeDenied` declared and never evaluated, so a user's app
 * restriction did nothing and said nothing; `RetrievalConfig.rerankMode` never
 * consulted, so OFF still reranked; `RetrievalTrace` a data class nobody
 * constructed; and `RetrievalTrace.rewrittenQuery` still unfilled after the
 * commit that fixed the other three. Two of those were introduced by the very
 * sweep that was documenting the pattern.
 *
 * It is a specific and recurring failure: a setting that exists, persists,
 * round-trips through backup, appears in a settings screen — and decides
 * nothing. Nothing fails. Nothing logs. The user believes a control is in force.
 *
 * The check is narrow on purpose. It covers the *configuration* types, where a
 * dead field is a lie to the user rather than merely unused code, and it looks
 * only for a total absence of reads. Anything subtler belongs in review.
 */
class DeadConfigFieldTest {

    /**
     * Types whose fields are load-bearing settings.
     *
     * Add a type here when it becomes something a user or an eval configures.
     */
    private val configTypes = listOf(
        "RetrievalConfig.kt" to "com/aura/memory",
        "ToolPolicy.kt" to "com/aura/agent/policy",
    )

    /**
     * Known-dead fields, each with why it is not simply wired.
     *
     * A map rather than a set so an entry cannot be added without stating a
     * reason — "we will use it later" is how every one of these got in, and it
     * is not a reason. Both entries below are pre-existing and both are
     * genuinely unenforceable at the policy gate as the types stand; they are
     * recorded in ENGINEERING_HISTORY §3 rather than quietly tolerated.
     */
    private val knownDead = mapOf(
        // Cost is not known before a tool runs. `PolicyResult.CostExceeded`
        // exists for it and is equally unreachable. Enforcing it needs a
        // per-tool cost estimate, which does not exist anywhere in the app.
        "costCeiling" to "no pre-execution cost estimate exists to compare against",
        // Approvals live in `ToolContext.approvedRemoteCostTools`, a Set<String>
        // with no timestamps, so there is nothing to expire. Enforcing it means
        // changing that set to carry grant times.
        "approvalExpiryMs" to "approvals are stored without timestamps, so expiry has nothing to measure",
    )

    @Test
    fun `every declared config field is read somewhere`() {
        // `sourceDir` rather than a hand-rolled path list: it resolves from
        // either the module dir or the repo root and errors when neither works,
        // because a source scan that finds nothing passes vacuously — the
        // defect §2.6 records finding in four separate tests.
        val roots = listOfNotNull(
            sourceDir("src/main/kotlin"),
            runCatching { File(sourceDir("src/main/kotlin").parentFile.parentFile.parentFile, "app/src/main/kotlin") }
                .getOrNull()?.takeIf { it.isDirectory },
        )
        val allSources = roots
            .flatMap { it.walkTopDown().filter { f -> f.extension == "kt" }.toList() }
            .requireNonEmpty("Kotlin sources")
        assertTrue(allSources.size > 100, "only ${allSources.size} sources found; the scan is not seeing the tree")

        val bodies = allSources.associateWith { stripComments(it.readText()) }

        val dead = mutableListOf<String>()
        for ((fileName, pkgPath) in configTypes) {
            val declFile = allSources.singleOrNull { it.name == fileName && it.path.replace('\\', '/').contains(pkgPath) }
            assertTrue(declFile != null, "config type $fileName not found under $pkgPath — did it move?")

            val declText = bodies.getValue(declFile)
            val fields = Regex("""^\s*val\s+(\w+)\s*:""", RegexOption.MULTILINE)
                .findAll(declText).map { it.groupValues[1] }.toList()
            assertTrue(fields.isNotEmpty(), "no fields parsed out of $fileName; the regex has drifted")

            for (field in fields) {
                if (field in knownDead) continue
                val word = Regex("""\b${Regex.escape(field)}\b""")
                val readsElsewhere = bodies.any { (f, body) -> f != declFile && word.containsMatchIn(body) }
                // Two occurrences in the declaring file means declaration plus
                // one use; one means declaration only.
                val ownUses = word.findAll(declText).count() - 1
                if (!readsElsewhere && ownUses <= 0) {
                    dead += "$fileName: $field"
                }
            }
        }

        assertTrue(
            dead.isEmpty(),
            "Config fields that are declared and never read:\n  ${dead.joinToString("\n  ")}\n\n" +
                "A setting that decides nothing is worse than no setting: it persists, it shows up " +
                "in the UI, and the user believes it is in force. Either wire it or delete it. " +
                "If it genuinely cannot be wired, add it to `knownDead` with the reason " +
                    "and record it in ENGINEERING_HISTORY §3.",
        )
    }

    /**
     * Strip comments before scanning.
     *
     * Learned the hard way three times in this repo: a comment explaining why a
     * field is unused contains the field name, so the scan finds a "use" in the
     * prose that documents its absence.
     */
    private fun stripComments(code: String): String = code
        .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
        .replace(Regex("""//[^\n]*"""), " ")
}
