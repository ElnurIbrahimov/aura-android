package com.aura.creative

import android.util.Log
import com.aura.data.UserPreferences
import com.aura.providers.ChatOptions
import com.aura.providers.ModelRole
import com.aura.providers.ProviderMessage
import com.aura.providers.ProviderRegistry
import com.aura.providers.ResponseSchema
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scores a manuscript scene by scene, and returns data rather than prose.
 *
 * This used to return `Flow<String>`. It declared [TensionReport] and
 * [SceneScore] next to itself and **never constructed either** — the scores went
 * out as text, landed in a text box, and were gone when the screen closed. So
 * the one question a writer actually has, *did the rewrite help*, could not be
 * asked: there was nothing to compare a draft against.
 *
 * Now the pass produces a [TensionReport] that [CreativeAnalysisStore] keys to
 * the revision it read, which is what makes
 * [CreativeAnalysisStore.diffAgainstParent] possible.
 *
 * **Structured output with a prose fallback, not one or the other.**
 * `ChatOptions.responseSchema` is honoured by most providers and by definition
 * not by `custom` (a user-supplied URL) or `moa` (fans out to whatever the
 * aggregator is) — and its own KDoc says callers must keep a lenient parse
 * either way. So the schema is requested, and [parseProse] catches the models
 * that answer in the format the prompt asks for instead.
 */
@Singleton
class TensionAnalyzer @Inject constructor(
    private val providerRegistry: ProviderRegistry,
    private val userPreferences: UserPreferences,
    private val brain: com.aura.agent.Brain,
    private val modelRoleRouter: com.aura.providers.ModelRoleRouter? = null,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Read [manuscript] and score every scene in it.
     *
     * Long manuscripts are split on scene boundaries and the parts merged, so a
     * novel returns one report rather than four unrelated ones.
     */
    suspend fun analyze(manuscript: String): TensionReport {
        require(manuscript.length > MIN_CHARS) { "Need at least $MIN_CHARS characters to analyze." }
        val model = resolveModel()

        val parts = splitManuscript(manuscript).mapIndexed { index, chunk ->
            analyseChunk(model, chunk, index, splitManuscript(manuscript).size)
        }
        return merge(parts)
    }

    /**
     * The report as the text the UI shows.
     *
     * Rendered from the data rather than being the source of it — which is the
     * whole inversion here. The prose is now a view of the scores; it used to be
     * the only place they existed.
     */
    fun render(report: TensionReport): String = buildString {
        report.scenes.forEach { s ->
            appendLine("${s.label}: ${s.tension}/10${if (s.note.isNotBlank()) " — ${s.note}" else ""}")
        }
        if (report.diagnosis.isNotBlank()) {
            appendLine()
            appendLine("PACING DIAGNOSIS")
            appendLine(report.diagnosis)
        }
        if (report.recommendations.isNotEmpty()) {
            appendLine()
            appendLine("RECOMMENDATIONS")
            report.recommendations.forEachIndexed { i, r -> appendLine("${i + 1}. $r") }
        }
    }.trim()

    private suspend fun analyseChunk(model: String, chunk: String, index: Int, total: Int): TensionReport {
        val userPrompt = if (total > 1) {
            "MANUSCRIPT PART ${index + 1} of $total:\n\n$chunk"
        } else {
            "MANUSCRIPT:\n\n$chunk"
        }
        val raw = StringBuilder()
        brain.stream(
            model,
            listOf(
                ProviderMessage(ProviderMessage.Role.system, SYSTEM_PROMPT),
                ProviderMessage(ProviderMessage.Role.user, userPrompt),
            ),
            emptyList(),
            ChatOptions(
                temperature = 0.3,
                maxTokens = 6_000,
                thinkingBudget = 16_384,
                responseSchema = SCHEMA,
            ),
        ).collect { c ->
            when (c) {
                is com.aura.agent.BrainChunk.Text -> raw.append(c.text.orEmpty())
                is com.aura.agent.BrainChunk.Error -> throw IllegalStateException(c.message)
                else -> Unit
            }
        }
        val text = raw.toString().trim()
        return parseJson(text) ?: parseProse(text)
    }

    /** The happy path: the provider honoured the schema. */
    private fun parseJson(text: String): TensionReport? = runCatching {
        // Models that wrap JSON in a fence still answer the schema; unwrapping is
        // cheaper than failing over to the prose parser for a formatting habit.
        val body = text.substringAfter("```json", text).substringBefore("```").trim()
            .ifBlank { text }
        json.decodeFromString(TensionReport.serializer(), body).takeIf { it.scenes.isNotEmpty() }
    }.getOrNull()

    /**
     * The fallback: the format the prompt asks for, in prose.
     *
     * `SCENE 4: 6/10 — the reversal lands` and its common variants. Deliberately
     * forgiving about the separator and the label, because this only runs for
     * the providers that could not be constrained in the first place — being
     * strict here would mean returning nothing for exactly the models most
     * likely to answer in text.
     */
    internal fun parseProse(text: String): TensionReport {
        val scenes = SCENE_LINE.findAll(text).map { m ->
            SceneScore(
                label = m.groupValues[1].trim(),
                tension = m.groupValues[2].toIntOrNull()?.coerceIn(1, 10) ?: 0,
                note = m.groupValues[3].trim().removePrefix("—").removePrefix("-").trim(),
            )
        }.filter { it.tension > 0 }.toList()

        val diagnosis = text.substringAfter("PACING DIAGNOSIS", "")
            .substringBefore("RECOMMENDATIONS")
            .trim()
            .removePrefix(":").trim()

        val recommendations = text.substringAfter("RECOMMENDATIONS", "")
            .lineSequence()
            .map { it.trim().removePrefix("-").trim() }
            .mapNotNull { NUMBERED.find(it)?.groupValues?.get(1)?.trim() }
            .filter { it.isNotBlank() }
            .toList()

        return TensionReport(scenes, diagnosis, recommendations)
    }

    /**
     * Parts back into one report.
     *
     * Scene labels are kept as the model gave them. Renumbering across parts
     * would make part 2's "Scene 1" into "Scene 6" and silently break the
     * label-matching that [CreativeAnalysisStore.diffAgainstParent] relies on —
     * the same scene would get a different name in a manuscript that grew past a
     * chunk boundary, and every scene after it would read as rewritten.
     */
    private fun merge(parts: List<TensionReport>): TensionReport = when (parts.size) {
        0 -> TensionReport()
        1 -> parts.single()
        else -> TensionReport(
            scenes = parts.flatMap { it.scenes },
            diagnosis = parts.mapNotNull { it.diagnosis.takeIf(String::isNotBlank) }.joinToString("\n\n"),
            recommendations = parts.flatMap { it.recommendations },
        )
    }

    private fun splitManuscript(text: String): List<String> {
        if (text.length <= MAX_CHUNK) return listOf(text)
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val end = minOf(start + MAX_CHUNK, text.length)
            val breakPoint = if (end < text.length) {
                val window = text.substring(end - LOOKBACK, end)
                maxOf(window.lastIndexOf("***"), window.lastIndexOf("\n# "), window.lastIndexOf("\n\n"))
            } else {
                -1
            }
            val actualEnd = if (breakPoint > 0) end - LOOKBACK + breakPoint + 3 else end
            chunks.add(text.substring(start, actualEnd.coerceAtMost(text.length)))
            start = actualEnd
        }
        return chunks
    }

    /**
     * The critic model, then the conversation default.
     *
     * This used to read `defaultModel` and then walk every configured provider
     * taking the first model it found — ignoring `ModelRoleRouter` entirely, so
     * the Creative Critic row in Settings routed `CreativeEngine` and
     * `LongformRunner` but not the analysis passes sitting beside them.
     */
    private suspend fun resolveModel(): String {
        modelRoleRouter?.explicit(ModelRole.CREATIVE_CRITIC)?.takeIf { it.isNotBlank() }?.let { return it }
        userPreferences.defaultModel.first()?.takeIf(String::isNotBlank)?.let { return it }
        for (provider in providerRegistry.configured()) {
            val model = runCatching { provider.listModels().firstOrNull() }
                .onFailure { Log.w(TAG, "listing models failed", it) }
                .getOrNull()
            if (!model.isNullOrBlank()) return "${provider.prefix}:$model"
        }
        throw IllegalStateException("Configure an LLM provider before using tension analysis.")
    }

    private companion object {
        const val TAG = "TensionAnalyzer"
        const val MIN_CHARS = 500
        const val MAX_CHUNK = 15_000
        const val LOOKBACK = 500

        /** `SCENE 4: 6/10 — note`, and the variants models actually produce. */
        val SCENE_LINE = Regex(
            """(?im)^\s*((?:scene|chapter|beat)\s*[\w.]+)\s*[:\-–]\s*(\d{1,2})\s*/\s*10\s*(.*)$""",
        )
        val NUMBERED = Regex("""^\d+[.)]\s*(.+)$""")

        val SCHEMA = ResponseSchema(
            name = "tension_report",
            schema = buildJsonObject {
                put("type", "object")
                put("additionalProperties", false)
                put(
                    "properties",
                    buildJsonObject {
                        put(
                            "scenes",
                            buildJsonObject {
                                put("type", "array")
                                put(
                                    "items",
                                    buildJsonObject {
                                        put("type", "object")
                                        put("additionalProperties", false)
                                        put(
                                            "properties",
                                            buildJsonObject {
                                                put("label", buildJsonObject { put("type", "string") })
                                                put(
                                                    "tension",
                                                    buildJsonObject {
                                                        put("type", "integer")
                                                        put("minimum", 1)
                                                        put("maximum", 10)
                                                    },
                                                )
                                                put("note", buildJsonObject { put("type", "string") })
                                            },
                                        )
                                        put(
                                            "required",
                                            buildJsonArray {
                                                add(kotlinx.serialization.json.JsonPrimitive("label"))
                                                add(kotlinx.serialization.json.JsonPrimitive("tension"))
                                                add(kotlinx.serialization.json.JsonPrimitive("note"))
                                            },
                                        )
                                    },
                                )
                            },
                        )
                        put("diagnosis", buildJsonObject { put("type", "string") })
                        put(
                            "recommendations",
                            buildJsonObject {
                                put("type", "array")
                                put("items", buildJsonObject { put("type", "string") })
                            },
                        )
                    },
                )
                put(
                    "required",
                    buildJsonArray {
                        add(kotlinx.serialization.json.JsonPrimitive("scenes"))
                        add(kotlinx.serialization.json.JsonPrimitive("diagnosis"))
                        add(kotlinx.serialization.json.JsonPrimitive("recommendations"))
                    },
                )
            },
        )

        val SYSTEM_PROMPT = """
            You are a developmental editor specialising in pacing and tension.

            Score every scene from 1 (calm, low stakes) to 10 (peak crisis), judging:
            STAKES — what the character stands to lose here.
            CONFLICT — is there active opposition, internal or external.
            REVERSAL — does the situation change mid-scene.
            INFORMATION — does the reader learn something that raises the stakes.
            EMOTIONAL INTENSITY — how much the character feels.

            Label each scene the way the manuscript does ("Scene 4", "Chapter 2",
            or its title). Labels are how one draft is compared against the next,
            so keep them stable for the same scene and do not renumber.

            Then give a pacing diagnosis — where it drags, where it rushes, which
            scenes are flat — and 3-5 specific, actionable recommendations.

            If you cannot answer as JSON, use exactly this line format instead:
            SCENE 1: 7/10 — one-line note
            followed by PACING DIAGNOSIS: and RECOMMENDATIONS: sections.
        """.trimIndent()
    }
}
