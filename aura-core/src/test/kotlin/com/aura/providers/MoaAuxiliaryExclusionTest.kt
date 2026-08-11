package com.aura.providers

import com.aura.agent.requireNonEmpty
import com.aura.agent.sourceDir
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * No background path may run on the MoA prefix.
 *
 * `MoaProvider` cancels the previous run when a new one starts. In every other
 * provider that shape is a defect — a background call on a shared singleton
 * killing the user's foreground stream — and an audit flagged it here as the
 * same thing. It is not, and the reason is an invariant rather than a property
 * of MoaProvider: nothing but the user's own chat selection ever reaches this
 * provider, so two concurrent runs mean the user sent a second message, and
 * cancelling the first saves a fan-out of N reference models plus an aggregator.
 *
 * That invariant is currently held by four separate decisions in four files —
 * `CheapModelResolver` excluding the prefix from auxiliary choices, the agentic
 * loop and the compactor each substituting a non-MoA model when the
 * conversation model is MoA, and `ProviderContextWindows` mapping it to null.
 * Four independent places, each one line, each easy to drop while refactoring
 * something else. The day one of them goes, the cancellation upstairs silently
 * becomes the clobber bug, and the test that would notice is
 * `MoaProviderTest`, which asserts the cancellation is CORRECT.
 *
 * So this pins the precondition rather than the behaviour. If it fails, the
 * question is not "why is this test failing" but "does MoaProvider still get to
 * cancel the previous run" — and the answer is probably no.
 */
class MoaAuxiliaryExclusionTest {

    private fun mainSources(): List<File> =
        sourceDir("src/main/kotlin/com/aura")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
            .requireNonEmpty("Kotlin sources")

    /**
     * The four exclusions, each identified by a fragment of the code that
     * performs it rather than by a comment, so rewording a comment does not
     * silently drop the guard.
     */
    private val guards = listOf(
        Triple(
            "CheapModelResolver.kt",
            "MOA_PREFIX",
            "cheap auxiliary model resolution must skip the MoA prefix — it fans out, so it is " +
                "the most expensive possible choice for a background call",
        ),
        Triple(
            "MemoryAugmentedAgenticLoop.kt",
            "it.providerPrefix != \"moa\"",
            "the write gate must substitute a non-MoA model when the conversation model is MoA, " +
                "or a memorable turn starts a second fan-out concurrent with the user's",
        ),
        Triple(
            "ConversationCompactor.kt",
            "moa:",
            "compaction must substitute a non-MoA model, or summarising a long conversation " +
                "starts a fan-out while the user is mid-turn",
        ),
        Triple(
            "ProviderContextWindows.kt",
            "\"moa\" -> null",
            "MoA has no context window of its own; reporting one would let a caller size a " +
                "request against the wrong model",
        ),
    )

    @Test
    fun `every auxiliary path still excludes the MoA prefix`() {
        val sources = mainSources().associateBy { it.name }
        val broken = mutableListOf<String>()

        for ((fileName, fragment, why) in guards) {
            val file = sources[fileName]
            if (file == null) {
                broken += "$fileName no longer exists — the guard it held may have moved: $why"
                continue
            }
            if (fragment !in file.readText()) {
                broken += "$fileName no longer contains `$fragment` — $why"
            }
        }

        assertTrue(
            broken.isEmpty(),
            "A background path may now reach the MoA provider:\n" +
                broken.joinToString("\n") { "  - $it" } +
                "\nMoaProvider cancels the previous run when a new one starts, which is correct ONLY " +
                "while the user's own chat selection is the only thing that reaches it. If that is no " +
                "longer true, that cancellation is the stream-clobber bug fixed in every other " +
                "provider, and MoaProvider plus MoaProviderTest have to change together.",
        )
    }

    /**
     * The cancellation this protects must still be there. Without this, the
     * test above would keep passing after someone removed the very behaviour
     * whose precondition it exists to guard — a gate outliving its subject.
     */
    @Test
    fun `MoaProvider still cancels the previous run`() {
        val moa = mainSources().single { it.name == "MoaProvider.kt" }.readText()
        assertTrue(
            "activeJob?.cancel()" in moa,
            "MoaProvider no longer cancels the previous run. If that was deliberate, this test and " +
                "MoaAuxiliaryExclusionTest's premise are both obsolete and should be deleted together.",
        )
    }
}
