package com.aura.memory.onnx

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the embedding model lives, and how it gets there.
 *
 * It is not in the APK and cannot be: 137 MB, and GitHub rejects any file over 100 MB, so
 * committing it is impossible regardless of what we would prefer the app to weigh. It
 * arrives once, over the network, into app-private storage.
 *
 * That suits the existing design rather than fighting it. Every vector is tagged with the
 * model that produced it, `Embedder.isCurrent` excludes vectors from any other model, and
 * `MemoryStore.rebuildEmbeddings` repairs them in resumable pages. Until the download
 * finishes the hash sketch still answers, its vectors are still tagged `local-hash-v2`,
 * and they are still repaired afterwards. The machinery was written for a model that
 * arrives later; this is that model.
 *
 * `filesDir`, not `cacheDir` — Android reclaims cacheDir under storage pressure, and
 * re-downloading 137 MB because the phone got low on space is not a cache miss anyone
 * wants. The same reasoning as the generated-media fix.
 */
@Singleton
class EmbeddingModelStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient,
) {

    val modelFile: File
        get() = File(File(context.filesDir, DIR).apply { mkdirs() }, FILE_NAME)

    /** True when a complete model is on disk and ready to load. */
    fun isReady(): Boolean = modelFile.isFile && modelFile.length() >= MIN_BYTES

    /**
     * Fetch the model if it is not already here.
     *
     * Downloads to a `.part` sibling and renames only once the bytes are all down, so an
     * interrupted download can never be mistaken for a complete model — a truncated ONNX
     * file loads far enough to fail deep inside the runtime with nothing useful to say.
     * Same shape as the backup writer, and for the same reason.
     *
     * @return true when a usable model is on disk afterwards.
     */
    suspend fun ensureDownloaded(onProgress: ((Long, Long) -> Unit)? = null): Boolean =
        withContext(Dispatchers.IO) {
            if (isReady()) return@withContext true

            val target = modelFile
            val part = File(target.parentFile, "${target.name}$PART_SUFFIX")
            runCatching { part.delete() }

            runCatching {
                val response = httpClient.newCall(Request.Builder().url(URL).build()).execute()
                response.use { res ->
                    check(res.isSuccessful) { "download failed: HTTP ${res.code}" }
                    val body = res.body ?: error("download returned no body")
                    val total = body.contentLength()
                    var written = 0L
                    body.byteStream().use { input ->
                        part.outputStream().buffered().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                                written += read
                                onProgress?.invoke(written, total)
                            }
                        }
                    }
                    check(written >= MIN_BYTES) { "download was truncated at $written bytes" }
                }
                // Only now is it a model. Before the rename it is an incomplete file with a
                // name nothing looks for.
                check(part.renameTo(target)) { "could not move the model into place" }
                Log.i(TAG, "embedding model ready: ${target.length() / 1_000_000} MB")
                true
            }.onFailure {
                runCatching { part.delete() }
                Log.w(TAG, "embedding model download failed: ${it.message}", it)
            }.getOrDefault(false)
        }

    /** Remove the model. The hash embedder takes over and vectors are repaired on return. */
    fun delete() {
        runCatching { modelFile.delete() }
            .onFailure { Log.w(TAG, "could not delete the model: ${it.message}", it) }
    }

    companion object {
        /**
         * The int8 export, 137 MB.
         *
         * int8 rather than fp16 (274 MB) or full (547 MB) because the eval measured the
         * quantised weights and found them worth +0.210 nDCG@10 — there is no evidence a
         * larger file buys anything here, and it would be four times the download.
         */
        const val URL =
            "https://huggingface.co/nomic-ai/nomic-embed-text-v1.5/resolve/main/onnx/model_int8.onnx"

        const val DIR = "embedding_model"
        const val FILE_NAME = "nomic_int8.onnx"
        const val PART_SUFFIX = ".part"

        /** A complete int8 export is ~137 MB; anything far under it is a failed download. */
        const val MIN_BYTES = 100L * 1024 * 1024

        private const val TAG = "EmbeddingModelStore"
    }
}
