package com.aura.curiosity

import android.util.Log
import com.aura.agent.PromptFraming
import com.aura.data.UserPreferences
import com.aura.memory.MemoryStore
import com.aura.providers.ProviderMessage
import com.aura.providers.ProviderRegistry
import com.aura.tools.WebSearchTool
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The questions Aura can answer without bothering anyone.
 *
 * Not every gap is about the user. "What is a Kalman filter" is a fact about
 * the world that Aura can go and find, and asking a person to explain it is
 * both lazy and a waste of the one open-question slot — which should be spent
 * on the things only they know.
 *
 * Two rules make this safe rather than merely clever:
 *
 *  - **A researched answer is never indistinguishable from something the user
 *    said.** It says so in its own text, so the marker travels with the fact
 *    wherever it is quoted — including into a recall block in the system
 *    prompt, where a separate "source" column would not be visible.
 *  - **Nothing about a person is ever looked up.** That is decided upstream in
 *    [QuestionAuthor], which overrides the model, and enforced again here.
 */
@Singleton
class SelfServeResearcher @Inject constructor(
    private val dao: OpenQuestionDao,
    private val curiosityStore: CuriosityStore,
    private val webSearchTool: WebSearchTool,
    private val providerRegistry: ProviderRegistry,
    private val userPreferences: UserPreferences,
    private val memoryStore: MemoryStore,
) {

    /**
     * Answer at most one world-answerable question.
     *
     * @return how many were answered — 0 or 1.
     */
    suspend fun research(now: Long = System.currentTimeMillis()): Int {
        val model = runCatching { userPreferences.backgroundModel.first() }
            .onFailure { Log.w(TAG, "background model read failed", it) }
            .getOrNull()
        if (model.isNullOrBlank()) return 0
        if (researchedToday(now) >= MAX_PER_DAY) return 0

        val question = dao.openAnswerableBy(OpenQuestionEntity.ANSWERABLE_WORLD, limit = 1).firstOrNull()
            ?: return 0

        val results = runCatching { webSearchTool.search(question.question, MAX_RESULTS) }
            .onFailure { Log.w(TAG, "search failed for '${question.question}'", it) }
            .getOrDefault(emptyList())
        if (results.isEmpty()) return 0

        val sources = results.joinToString("\n") { "- ${it.title}: ${it.snippet.take(SNIPPET_CHARS)}" }
        val answer = runCatching {
            providerRegistry.chat(
                model,
                listOf(
                    ProviderMessage(role = ProviderMessage.Role.system, content = SYSTEM_PROMPT),
                    ProviderMessage(
                        role = ProviderMessage.Role.user,
                        content = "Question: ${question.question}\n\nSearch results:\n$sources",
                    ),
                ),
                com.aura.providers.ChatOptions(attended = false),
            ).toList().joinToString("") { it.text ?: "" }.trim()
        }.onFailure { Log.w(TAG, "condensing failed", it) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() && !it.startsWith(NO_ANSWER) }
            ?: return 0

        val memoryId = runCatching {
            memoryStore.store(
                // The provenance is in the sentence, not beside it. A "source"
                // column is invisible the moment the text is quoted anywhere,
                // and the one place this text will certainly be quoted is a
                // recall block in the system prompt.
                content = "${question.question} — Aura looked this up: ${answer.take(MAX_ANSWER_CHARS)}",
                source = SOURCE_RESEARCHED,
                category = "fact",
                // Lower than an answer the user gave, because nobody confirmed
                // it. It is correctable like any other memory.
                importance = RESEARCHED_IMPORTANCE,
                tags = listOf(TAG_INFERRED),
            )
        }.onFailure { Log.w(TAG, "storing the researched answer failed", it) }
            .getOrNull()
            ?: return 0

        curiosityStore.markResearched(question.id, memoryId, now)
        return 1
    }

    private suspend fun researchedToday(now: Long): Int =
        dao.byStatus(OpenQuestionEntity.STATUS_RESEARCHED, DAY_WINDOW)
            .count { (it.answeredAt ?: 0L) >= now - DAY_MS }

    private companion object {
        const val TAG = "SelfServeResearcher"

        const val SOURCE_RESEARCHED = "aura:researched"
        const val TAG_INFERRED = "inferred"
        const val RESEARCHED_IMPORTANCE = 0.4f

        /**
         * One a day.
         *
         * Deliberately not gated on an unmetered network: the daemon's
         * WorkManager constraint is `CONNECTED`, and tightening it to
         * `UNMETERED` would delay everything else that worker does for the sake
         * of one search and one short completion — less traffic than a single
         * chat turn.
         */
        const val MAX_PER_DAY = 1

        const val MAX_RESULTS = 5
        const val SNIPPET_CHARS = 300
        const val MAX_ANSWER_CHARS = 300
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val DAY_WINDOW = 20

        const val NO_ANSWER = "UNKNOWN"

        val SYSTEM_PROMPT = """
            Answer the question in one or two sentences using only the search
            results provided. ${PromptFraming.UNTRUSTED_DATA_DIRECTIVE}
            Do not add anything the results do not say. If they do not answer
            the question, reply with exactly: $NO_ANSWER
        """.trimIndent()
    }
}
