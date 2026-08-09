package com.aura.agent

import java.io.File
import org.junit.Test
import kotlin.test.assertTrue

/**
 * Architectural guard: there is exactly one way to pick a cheap auxiliary model.
 *
 * ENGINEERING_HISTORY §2.5 records replacing a by-name-length ranking with
 * `CheapModelHeuristic` "at all three sites: the agentic loop, `resolveCheapModel`,
 * and `ConversationCompactor`". There were six. `DebateRoundUseCase` still ranked
 * by `minByOrNull { it.length }` — the exact anti-pattern, still choosing
 * `gpt-4o` over `gpt-4o-mini` — while `DreamConsolidator` and
 * `ParallelResearchTool` took whichever model a provider happened to list first,
 * and both returned bare model ids that `ProviderRegistry.parse` rejects.
 *
 * A claim in a document came back false once. This makes it enforceable, which
 * is the only version of that claim worth having.
 *
 * Scans run over comment-stripped source. The first version of this test did
 * not, and failed on the KDoc of the very fix it was guarding — a rule that
 * punishes writing down what went wrong is a rule nobody keeps.
 */
class CheapModelResolutionScanTest {

    private val coreSource = sourceDir("src/main/kotlin/com/aura")

    private fun kotlinFiles(): List<File> = coreSource.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .toList()
        .requireNonEmpty("Kotlin source files")

    /**
     * Crude but sufficient: strip block and line comments so a scan matches code
     * rather than prose. Not a Kotlin lexer — it will also blank a `//` inside a
     * string literal, which for these patterns is harmless.
     */
    private fun File.code(): String = readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//[^\n]*"""), "")

    /**
     * The ranking bug itself. `gpt-4o` is 6 characters and `gpt-4o-mini` is 11,
     * so shortest-name picks the expensive model — the suffix that marks a model
     * as small is also what lengthens its name.
     */
    @Test
    fun `no file ranks models by name length`() {
        val offenders = kotlinFiles()
            .filter { file ->
                val code = file.code()
                Regex("""(minByOrNull|sortedBy)\s*\{\s*\w+\s*->?\s*\w*\.?length\s*\}""").containsMatchIn(code)
            }
            .map { it.name }

        assertTrue(
            offenders.isEmpty(),
            "Ranking models by name length picks the expensive one — use CheapModelResolver. Offenders: $offenders",
        )
    }

    /**
     * Every cheap-model decision goes through `CheapModelResolver`, so the
     * user's Fast-model preference is honoured everywhere and the ranking cannot
     * drift apart again. The resolver is the one place allowed to call the
     * heuristic directly.
     */
    @Test
    fun `only CheapModelResolver calls the heuristic directly`() {
        val offenders = kotlinFiles()
            .filter { it.name != "CheapModelResolver.kt" && it.name != "CheapModelHeuristic.kt" }
            .filter { it.code().contains("CheapModelHeuristic.pick") }
            .map { it.name }

        assertTrue(
            offenders.isEmpty(),
            "Call CheapModelResolver.resolve() rather than the heuristic directly. Offenders: $offenders",
        )
    }

    /**
     * A local `resolveCheapModel` is fine as a thin adapter — several callers
     * need to shape the fallback differently — but it must delegate rather than
     * grow its own selection logic, which is exactly how six of these appeared.
     */
    @Test
    fun `every resolveCheapModel delegates to the shared resolver`() {
        val declaring = kotlinFiles().filter { it.code().contains("fun resolveCheapModel") }
        assertTrue(
            declaring.isNotEmpty(),
            "no resolveCheapModel found at all — this scan has drifted from the code it guards",
        )

        val offenders = declaring
            .filter { !it.code().contains("cheapModelResolver") }
            .map { it.name }

        assertTrue(
            offenders.isEmpty(),
            "resolveCheapModel must delegate to CheapModelResolver, not reimplement it. Offenders: $offenders",
        )
    }
}
