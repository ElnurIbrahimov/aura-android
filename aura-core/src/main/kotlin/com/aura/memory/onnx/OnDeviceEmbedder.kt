package com.aura.memory.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import com.aura.memory.EmbedKind
import com.aura.memory.Embedder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.LongBuffer

/**
 * Embeddings computed on the phone, by nomic-embed-text-v1.5 through ONNX Runtime.
 *
 * Replaces `LocalEmbedder`'s hash sketch as the thing that runs when there is no network.
 * The sketch was never a semantic model — it is SHA-256 over character n-grams, so two
 * sentences meaning the same thing in different words score no better than two unrelated
 * ones. Everything downstream was calibrated around that: `vectorPoolSize` is 0 because
 * hash vectors are noise in candidate selection, and `minRelevance` is 0.15 because that
 * is three sigma of a 384-dim hash's noise floor. Both are wrong for a real model, in
 * opposite directions, and both must move with it.
 *
 * Chosen by measurement rather than reputation. Against the retrieval eval corpus, at a
 * 0.50 floor with the vector pool open, this scored +0.210 nDCG@10 over the hash and was
 * the only model tried that could still return nothing for a question the corpus does not
 * answer. `gte-small` scores identically at every floor between 0.35 and 0.70, because its
 * similarities sit above that whole range — it cannot express "I don't know" at all.
 *
 * The model is 137 MB and is NOT bundled: GitHub rejects files over 100 MB, so it arrives
 * at runtime. Until it does, [com.aura.memory.LocalEmbedder] still answers, its vectors are
 * still tagged `local-hash-v2`, `Embedder.isCurrent` still excludes them from scoring, and
 * `MemoryStore.rebuildEmbeddings` repairs them once this is available. That machinery
 * already existed for a model that arrives later; this is the model.
 */
class OnDeviceEmbedder(
    private val modelFile: File,
    private val tokenizer: WordPieceTokenizer,
) : Embedder {

    private val loadLock = Mutex()
    @Volatile private var session: OrtSession? = null
    @Volatile private var outputName: String? = null

    override fun modelId(): String = MODEL_ID

    override fun dimension(): Int = DIMENSION

    /** True once the model file is present and can be loaded. */
    fun isAvailable(): Boolean = modelFile.isFile && modelFile.length() > MIN_MODEL_BYTES

    override suspend fun embed(text: String): FloatArray = embed(text, EmbedKind.DOCUMENT)

    /**
     * Embed [text] for the given [kind].
     *
     * nomic is an asymmetric model: it wants `search_query: ` in front of a question and
     * `search_document: ` in front of a stored passage, and the two produce measurably
     * different vectors for identical text. The eval that chose this model used those
     * prefixes, so using one prefix for both here would ship something other than what was
     * measured.
     */
    suspend fun embed(text: String, kind: EmbedKind): FloatArray = withContext(Dispatchers.Default) {
        val ort = ensureSession()
        val ids = tokenizer.encode(kind.prefix + text).map { it.toLong() }
        val length = ids.size
        // token_type_ids is all zeros: a single segment. The model still requires it.
        val shape = longArrayOf(1, length.toLong())
        val env = OrtEnvironment.getEnvironment()

        OnnxTensor.createTensor(env, LongBuffer.wrap(ids.toLongArray()), shape).use { inputIds ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(length) { 1L }), shape).use { mask ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(length)), shape).use { types ->
                    val inputs = mapOf(
                        "input_ids" to inputIds,
                        "attention_mask" to mask,
                        "token_type_ids" to types,
                    ).filterKeys { it in ort.inputNames }
                    ort.run(inputs).use { result ->
                        val name = outputName ?: ort.outputNames.first().also { outputName = it }
                        @Suppress("UNCHECKED_CAST")
                        val hidden = result.get(name).get().value as Array<Array<FloatArray>>
                        normalize(meanPool(hidden[0]))
                    }
                }
            }
        }
    }

    /**
     * Mean over the sequence, which is what `1_Pooling/config.json` specifies
     * (`pooling_mode_mean_tokens`).
     *
     * No attention-mask weighting is needed because nothing is padded — one sequence per
     * call, tokenized to its own length. Batching would change that, and would have to
     * weight by the mask rather than divide by the padded length.
     */
    private fun meanPool(tokens: Array<FloatArray>): FloatArray {
        val out = FloatArray(DIMENSION)
        if (tokens.isEmpty()) return out
        for (token in tokens) {
            for (i in out.indices) out[i] += token[i]
        }
        val n = tokens.size.toFloat()
        for (i in out.indices) out[i] /= n
        return out
    }

    /**
     * L2 normalise, so cosine similarity is a dot product.
     *
     * Every consumer — `cosineSimilarity`, the relevance floor, RRF's vector rank —
     * assumes unit vectors. An unnormalised vector would make the 0.50 floor meaningless
     * because the scale would vary with sentence length.
     */
    private fun normalize(vec: FloatArray): FloatArray {
        var sum = 0.0
        for (v in vec) sum += v.toDouble() * v
        val norm = kotlin.math.sqrt(sum).toFloat()
        if (norm <= 0f) return vec
        for (i in vec.indices) vec[i] /= norm
        return vec
    }

    private suspend fun ensureSession(): OrtSession {
        session?.let { return it }
        return loadLock.withLock {
            session?.let { return it }
            check(isAvailable()) { "the embedding model is not on disk at ${modelFile.path}" }
            val opts = OrtSession.SessionOptions().apply {
                // One thread per big core. The default spawns as many as there are cores,
                // which on a phone means contending with whatever is drawing the screen.
                setIntraOpNumThreads(THREADS)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val created = OrtEnvironment.getEnvironment().createSession(modelFile.path, opts)
            Log.i(TAG, "loaded ${modelFile.length() / 1_000_000} MB model; outputs=${created.outputNames}")
            session = created
            created
        }
    }

    /** Release the native session. Safe to call more than once. */
    fun close() {
        runCatching { session?.close() }
            .onFailure { Log.w(TAG, "closing the session failed: ${it.message}", it) }
        session = null
    }

    companion object {
        /**
         * Includes the quantisation, because it is part of what produced the vector.
         *
         * `Embedder.isCurrent` compares this against the tag stored on every row, so an
         * install that switches from int8 to fp16 must treat its old vectors as stale
         * rather than silently mixing two spaces.
         */
        const val MODEL_ID = "nomic-embed-text-v1.5-int8"
        const val DIMENSION = 768

        /** Sanity bound: a truncated download is a file, and would fail deep inside ORT. */
        const val MIN_MODEL_BYTES = 100L * 1024 * 1024

        const val THREADS = 4
        private const val TAG = "OnDeviceEmbedder"
    }
}
