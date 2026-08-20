package com.aura.memory.onnx

import com.aura.agent.requireNonEmpty
import com.aura.agent.sourceDir
import org.junit.Test
import kotlin.test.assertTrue

/**
 * Guards the two ways the 137 MB model could be built and never actually used.
 *
 * The first is the one this codebase has a documented history of: a subsystem
 * that compiles, is tested, and nothing ever calls. `LivingWorldWiringTest`
 * exists for the same reason and names the precedents — a canon fact store
 * nothing writes to, a voice stack nothing starts. Every piece of the embedder
 * shipped in that state: `EmbeddingModelStore.ensureDownloaded` had no caller,
 * so the model never arrived, so `RoutedEmbedder` always chose the hash and
 * every measurement behind it stayed theoretical.
 *
 * The second is subtler and worse, because it makes recall *worse* rather than
 * merely no better. The moment the model lands, `Embedder.isCurrent` starts
 * excluding every `local-hash-v2` vector from cosine scoring — so between
 * "downloaded" and "corpus rebuilt" the semantic signal is not weak, it is
 * absent, and `RetrievalConfig.SEMANTIC` has meanwhile opened `vectorPoolSize`
 * to 25 on the strength of vectors that are being ignored. The download and the
 * rebuild are one operation and the code has to keep them that way.
 */
class EmbeddingModelWiringTest {

    private fun source(pkg: String, name: String): String =
        sourceDir("src/main/kotlin/com/aura/$pkg")
            .listFiles { f -> f.name == name }
            ?.toList()
            .orEmpty()
            .requireNonEmpty(name)
            .first()
            .readText()

    @Test
    fun `something actually triggers the download`() {
        val bootstrap = source("proactive", "ProactiveBootstrap.kt")

        // `enqueue`, not the class name. The off-branch calls `cancel` on the
        // same class, so asserting the name alone stays green in a build where
        // the only thing wired is the teardown — which was true of the first
        // draft of this test, caught by mutating the call site.
        assertTrue(
            bootstrap.contains("EmbeddingModelWorker.enqueue"),
            "nothing enqueues the download from app start, so `ensureDownloaded` has no caller, " +
                "the model never arrives, and RoutedEmbedder answers with the hash sketch forever",
        )
    }

    @Test
    fun `the download is gated on the preference the user controls`() {
        val bootstrap = source("proactive", "ProactiveBootstrap.kt")

        assertTrue(
            bootstrap.contains("smarterMemoryEnabled"),
            "the download is not gated on its preference, so it would start unasked on next " +
                "launch — 137 MB is not a thing to help yourself to, and the Settings toggle " +
                "would do nothing",
        )
    }

    @Test
    fun `the rebuild is chained to the download rather than left to chance`() {
        val worker = source("memory/onnx", "EmbeddingModelWorker.kt")

        assertTrue(
            worker.contains("ReembedWorker.enqueue"),
            "the model can land without anything repairing the existing vectors, which leaves " +
                "recall running on BM25 alone with the semantic settings already applied",
        )
    }

    @Test
    fun `turning it off removes the model rather than stranding it`() {
        val bootstrap = source("proactive", "ProactiveBootstrap.kt")

        // Otherwise "off" means the download stops but a complete model on disk
        // keeps being used, and the toggle reads as a lie. delete() puts the
        // hash back and countNeedingReembed goes non-zero, which is the same
        // repair path in the other direction.
        assertTrue(
            bootstrap.contains("modelStore.delete()") || bootstrap.contains("embeddingModelStore.delete()"),
            "switching the toggle off leaves the model in place and in use, so it does not " +
                "switch anything off",
        )
    }
}
