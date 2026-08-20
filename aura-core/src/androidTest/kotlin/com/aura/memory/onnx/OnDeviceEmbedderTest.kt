package com.aura.memory.onnx

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aura.memory.EmbedKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.system.measureTimeMillis

/**
 * Does the model actually run on the phone, and is it fast enough to be worth it?
 *
 * Everything up to here was decided on a laptop against precomputed vectors. Two things
 * could not be: whether ONNX Runtime loads a 137 MB int8 graph on this device at all, and
 * what a single embedding costs. That cost is paid on every memory written and every recall
 * issued, so a number in the hundreds of milliseconds changes the design rather than
 * confirming it.
 *
 * The model is not bundled — GitHub rejects files over 100 MB — so this reads it from the
 * app's own external files directory, where a developer or the app's downloader put it. It SKIPS when
 * the file is absent rather than failing, because a CI emulator has no reason to hold a
 * 137 MB model; that is the one case where skipping is honest, and the assumption message
 * names the file that is missing.
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceEmbedderTest {

    /**
     * App-private external storage, not /sdcard/Download.
     *
     * The first attempt read from Download and every test failed with ORT_FAIL, system
     * error 13 — EACCES. Scoped storage means an app cannot read shared directories without
     * a runtime grant it has no business asking for. This is also where the production
     * downloader belongs, so the test exercises the real location rather than a convenient
     * one.
     */
    private fun modelFile(): File {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        return File(ctx.getExternalFilesDir(null), "nomic_int8.onnx")
    }

    private fun embedder(): OnDeviceEmbedder {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val vocab = ctx.assets.open("nomic_vocab.txt").bufferedReader().readLines()
        return OnDeviceEmbedder(modelFile(), WordPieceTokenizer(vocab))
    }

    private fun requireModel(e: OnDeviceEmbedder) =
        assumeTrue("no model at ${modelFile().path} — push it with adb to run this", e.isAvailable())

    @Test
    fun theModelLoadsAndReturnsAUnitVectorOfTheRightSize() = runBlocking {
        val e = embedder()
        requireModel(e)

        val vec = e.embed("the database migration ran overnight", EmbedKind.DOCUMENT)

        assertEquals("wrong dimensionality for nomic-embed-text-v1.5", 768, vec.size)
        val norm = sqrt(vec.fold(0.0) { acc, v -> acc + v.toDouble() * v })
        assertTrue(
            "vector is not unit length ($norm) — every consumer assumes cosine is a dot product",
            abs(norm - 1.0) < 1e-3,
        )
        e.close()
    }

    @Test
    fun meaningIsCloserThanVocabulary() = runBlocking {
        // The entire reason for replacing the hash. The target shares almost no words with
        // the query and means the same thing; the decoy shares the query's words and means
        // something else. A hash sketch ranks the decoy first — that is what the eval corpus
        // measured. On the device it has to come out the other way round.
        val e = embedder()
        requireModel(e)

        val query = e.embed("how did we decide to handle the database change", EmbedKind.QUERY)
        val target = e.embed(
            "agreed to roll the schema forward in place rather than dual-write during the cutover",
            EmbedKind.DOCUMENT,
        )
        val decoy = e.embed(
            "the database file on the phone is called aura-memory and lives under databases",
            EmbedKind.DOCUMENT,
        )

        val toTarget = query.indices.sumOf { (query[it] * target[it]).toDouble() }
        val toDecoy = query.indices.sumOf { (query[it] * decoy[it]).toDouble() }
        println("SEMANTIC CHECK: paraphrase=$toTarget decoy=$toDecoy")
        assertTrue(
            "the paraphrase ($toTarget) should beat the lexical decoy ($toDecoy)",
            toTarget > toDecoy,
        )
        e.close()
    }

    @Test
    fun theQueryAndDocumentPrefixesProduceDifferentVectors() = runBlocking {
        // nomic is asymmetric. If these came out identical the prefixes would not be
        // reaching the model, and this would ship something other than what the eval
        // measured.
        val e = embedder()
        requireModel(e)

        val asQuery = e.embed("schema migration", EmbedKind.QUERY)
        val asDoc = e.embed("schema migration", EmbedKind.DOCUMENT)

        val cos = asQuery.indices.sumOf { (asQuery[it] * asDoc[it]).toDouble() }
        assertTrue("prefixes had no effect (cos=$cos)", cos < 0.999)
        e.close()
    }

    @Test
    fun oneEmbeddingIsFastEnoughToSitOnTheRecallPath() = runBlocking {
        val e = embedder()
        requireModel(e)
        // Load is separate from inference and far slower; measuring them together would
        // report the first call's cost as though every call paid it.
        val loadMs = measureTimeMillis { e.embed("warm up", EmbedKind.DOCUMENT) }

        val runs = 10
        val totalMs = measureTimeMillis {
            repeat(runs) { i -> e.embed("memory number $i about a schema migration", EmbedKind.DOCUMENT) }
        }
        val perCall = totalMs / runs

        println("EMBED TIMING: first call incl. load ${loadMs}ms, then ${perCall}ms per embed over $runs")
        assertTrue(
            "an embedding costs ${perCall}ms — that is paid on every memory write and every recall",
            perCall < 1_000,
        )
        e.close()
    }
}
