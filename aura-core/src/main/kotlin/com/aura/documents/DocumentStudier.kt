package com.aura.documents

import android.util.Log
import com.aura.providers.ChatOptions
import com.aura.providers.ProviderMessage
import com.aura.providers.ProviderMessage.Role
import com.aura.providers.ProviderRegistry
import com.aura.providers.ResponseSchema
import com.aura.providers.StructuredJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads an imported document once and writes down what it is, in order.
 *
 * ## The gap this fills
 *
 * A document already arrives as chunks, and each chunk is stored as a memory,
 * so answering from one is retrieval rather than one-shot reading. That is the
 * right design and it has a specific blind spot: retrieval returns the handful
 * of passages nearest the question, so anything needing the shape of the
 * *whole* document is unanswerable. "What are all the constraints?" cannot be
 * answered from five chunks. Neither can "does section four contradict section
 * nine" — the two sections are individually retrievable and never retrieved
 * together, because neither is about the other.
 *
 * So this builds the map. An outline is small enough to carry whole, where
 * ninety thousand characters of source is not, and it sits alongside ordinary
 * retrieval rather than replacing it: the model gets the map and the few square
 * metres of territory the question is actually about.
 *
 * ## Why not decompose further
 *
 * The tempting version is to reason over each chunk separately and combine the
 * results. The measurements are against it: multi-agent decomposition scores
 * *worse* than a single pass on MMLU (40% → 28%) and collapses
 * prospective-memory F1 from 79 to 39. Splitting a problem up is not free, and
 * "study it in pieces" fails in exactly the way this succeeds only if the
 * pieces are then reassembled into something the model can hold at once.
 *
 * This is an extraction pass, not a reasoning one. It asks only what each part
 * is about and what rules the text states — questions a cheap model answers
 * reliably about text in front of it — and does all the reasoning later, once,
 * against the assembled outline.
 */
@Singleton
class DocumentStudier @Inject constructor(
    private val providerRegistry: ProviderRegistry,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /**
     * @param parts one line per chunk, in document order.
     * @param constraints rules the document states, deduplicated across batches.
     */
    data class Outline(
        val parts: List<String>,
        val constraints: List<String>,
    ) {
        val isEmpty: Boolean get() = parts.isEmpty() && constraints.isEmpty()
    }

    /**
     * Study [chunks] in batches.
     *
     * Returns null when nothing usable came back — the caller keeps the plain
     * chunk memories it already wrote, which is the behaviour that existed
     * before this class and is never worse than it.
     */
    suspend fun study(
        documentName: String,
        chunks: List<String>,
        model: String,
    ): Outline? {
        if (chunks.isEmpty()) return null

        val parts = mutableListOf<String>()
        val constraints = mutableListOf<String>()

        // Batched because a 15,000-word document is roughly fifty chunks, and
        // fifty calls to describe one file is not a study pass, it is a bill.
        // Batching by character budget rather than by count keeps a document of
        // short chunks from paying the same fifty calls anyway.
        var index = 0
        for (batch in batches(chunks)) {
            val numbered = batch.mapIndexed { i, c ->
                "--- part ${index + i + 1} ---\n${c.take(MAX_CHUNK_CHARS)}"
            }.joinToString("\n\n")

            val result = StructuredJson.requestJson(
                registry = providerRegistry,
                modelId = model,
                messages = listOf(
                    ProviderMessage(role = Role.system, content = SYSTEM_PROMPT),
                    ProviderMessage(
                        role = Role.user,
                        content = "Document: $documentName\n\n$numbered",
                    ),
                ),
                // attended = false: this runs unattended after an import, so it
                // has to be bounded by the daily background budget like every
                // other unprompted call. A user importing a large file must not
                // be able to spend the day's ceiling by accident.
                options = ChatOptions(temperature = 0.0, maxTokens = 700, attended = false),
                schema = STUDY_SCHEMA,
                timeoutMs = BATCH_TIMEOUT_MS,
                tag = TAG,
            ) { cleaned ->
                runCatching { json.decodeFromString(StudyBatch.serializer(), cleaned) }
                    .onFailure { Log.w(TAG, "unparseable study batch: ${it.message}", it) }
                    .getOrNull()
            }

            if (result != null) {
                // One line per part, in document order, whatever the model
                // returned. A batch that answers for three of its five parts
                // must not silently shift the numbering of everything after it.
                for (i in batch.indices) {
                    val about = result.parts.firstOrNull { it.n == index + i + 1 }?.about?.flatten()
                    parts += about?.ifBlank { null } ?: "(part ${index + i + 1})"
                }
                constraints += result.constraints.map { it.flatten() }.filter { it.isNotBlank() }
            } else {
                for (i in batch.indices) parts += "(part ${index + i + 1})"
            }
            index += batch.size
        }

        val outline = Outline(
            parts = parts,
            // Deduplicated case-insensitively: a rule restated in three
            // sections is one rule, and the outline exists to be read.
            constraints = constraints.distinctBy { it.lowercase() }.take(MAX_CONSTRAINTS),
        )

        // Every part unresolved means every batch failed. That is not an
        // outline, it is a list of apologies.
        if (outline.parts.all { it.startsWith("(part ") } && outline.constraints.isEmpty()) return null
        return outline.takeUnless { it.isEmpty }
    }

    /** The outline as one storable, readable block. */
    fun render(documentName: String, outline: Outline): String = buildString {
        append("Outline of ").append(documentName)
        append(" (").append(outline.parts.size).append(" parts)\n")
        outline.parts.forEachIndexed { i, p -> append(i + 1).append(". ").append(p).append('\n') }
        if (outline.constraints.isNotEmpty()) {
            append("\nRules and constraints it states:\n")
            outline.constraints.forEach { append("- ").append(it).append('\n') }
        }
    }.trim()

    /** Group chunks so each call carries a bounded amount of text. */
    private fun batches(chunks: List<String>): List<List<String>> {
        val out = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        var size = 0
        for (c in chunks.take(MAX_CHUNKS)) {
            val len = minOf(c.length, MAX_CHUNK_CHARS)
            if (current.isNotEmpty() && size + len > BATCH_CHARS) {
                out += current
                current = mutableListOf()
                size = 0
            }
            current += c
            size += len
        }
        if (current.isNotEmpty()) out += current
        return out
    }

    private fun String.flatten(): String =
        replace(Regex("\\s+"), " ").trim().take(MAX_LINE_CHARS)

    companion object {
        private const val TAG = "DocumentStudier"

        /** Characters of source per call. */
        internal const val BATCH_CHARS = 12_000

        /** A single oversized chunk cannot blow the batch budget on its own. */
        internal const val MAX_CHUNK_CHARS = 2_400

        /**
         * A ceiling on how much of a very large document is studied.
         *
         * 300 chunks is roughly a 90,000-word document. Past that the outline
         * itself stops fitting in a prompt, which is the problem this exists to
         * solve, so studying further would spend money to make the answer
         * worse.
         */
        internal const val MAX_CHUNKS = 300

        internal const val MAX_CONSTRAINTS = 40
        internal const val MAX_LINE_CHARS = 160

        /** Generous: this is unattended and batched, so a slow call costs nothing visible. */
        internal const val BATCH_TIMEOUT_MS = 45_000L

        private val SYSTEM_PROMPT = """
            You are indexing a document so it can be navigated later. You are not summarising it and not evaluating it.

            You will be given numbered parts of one document. For each part, write one short line saying what that part is about — the subject, not a summary of the prose.

            Separately, list any explicit rules, constraints, requirements or prohibitions the text states. Quote them closely. Do not infer rules that are not written down, and do not include ordinary factual statements.

            Use the exact part numbers you were given. If a part is boilerplate or has no discernible subject, still return a line for it saying so.
        """.trimIndent()

        private val STUDY_SCHEMA = ResponseSchema(
            name = "index_document_parts",
            schema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("parts", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("n", buildJsonObject { put("type", "integer") })
                                put("about", buildJsonObject { put("type", "string") })
                            })
                            put("required", buildJsonArray { add(JsonPrimitive("n")); add(JsonPrimitive("about")) })
                        })
                    })
                    put("constraints", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                    })
                })
                put("required", buildJsonArray { add(JsonPrimitive("parts")); add(JsonPrimitive("constraints")) })
            },
        )
    }
}

@Serializable
private data class StudyBatch(
    val parts: List<StudyPart> = emptyList(),
    val constraints: List<String> = emptyList(),
)

@Serializable
private data class StudyPart(val n: Int = 0, val about: String = "")
