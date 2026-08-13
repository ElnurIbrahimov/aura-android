package com.aura.curiosity

import android.util.Log
import com.aura.agent.PromptFraming
import com.aura.data.UserPreferences
import com.aura.providers.ProviderMessage
import com.aura.providers.ProviderRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a subject into a sentence a person would not mind being asked.
 *
 * The one model call in this feature, on the background model, during the
 * nightly cycle — the same budget `IdleTimePreparationEngine` already spends.
 * It writes phrasing only: the subject and its context come from rows, and the
 * author is told not to add facts, because a question that presumes something
 * the user never said is worse than no question at all.
 *
 * It also decides who can answer. That distinction matters more than it looks:
 * "what is Causeway" may be answerable from the world, but anything about a
 * person never is — looking up a name on the internet is not curiosity about
 * someone, it is something else, and this is the point where that line gets
 * drawn rather than left to a later component.
 */
@Singleton
class QuestionAuthor @Inject constructor(
    private val providerRegistry: ProviderRegistry,
    private val userPreferences: UserPreferences,
) {

    data class Authored(
        val subject: QuestionScanner.Subject,
        val question: String,
        val answerable: String,
    )

    /**
     * Write one question per subject. Returns fewer than it was given when the
     * model omits or mangles lines — a dropped question is a subject that comes
     * back next cycle, which is the harmless failure.
     *
     * Returns empty with no background model configured, matching every other
     * background-LLM caller in the app.
     */
    suspend fun author(subjects: List<QuestionScanner.Subject>): List<Authored> {
        if (subjects.isEmpty()) return emptyList()
        val model = runCatching { userPreferences.backgroundModel.first() }
            .onFailure { Log.w(TAG, "background model read failed", it) }
            .getOrNull()
        if (model.isNullOrBlank()) return emptyList()

        val numbered = subjects.mapIndexed { index, subject ->
            "${index + 1}. [${subject.kind}] ${subject.context}"
        }.joinToString("\n")

        val response = runCatching {
            providerRegistry.chat(
                model,
                listOf(
                    ProviderMessage(role = ProviderMessage.Role.system, content = systemPrompt()),
                    ProviderMessage(role = ProviderMessage.Role.user, content = numbered),
                ),
                com.aura.providers.ChatOptions(attended = false),
            ).toList().joinToString("") { it.text ?: "" }
        }.onFailure { Log.w(TAG, "authoring call failed", it) }
            .getOrNull()
            ?: return emptyList()

        return parse(response, subjects)
    }

    /**
     * Read back `index|who|question` lines.
     *
     * Tolerant on purpose — a line that cannot be read is skipped rather than
     * guessed at, and an index that does not match a subject is dropped
     * entirely. Attaching a question to the wrong subject would make it
     * unanswerable and un-closable, and it would be asked forever.
     */
    internal fun parse(response: String, subjects: List<QuestionScanner.Subject>): List<Authored> {
        val out = mutableListOf<Authored>()
        val seen = mutableSetOf<Int>()
        for (line in response.lines()) {
            val parts = line.trim().split("|", limit = 3)
            if (parts.size < 3) continue
            val index = parts[0].trim().trimStart('#').toIntOrNull()?.minus(1) ?: continue
            if (index !in subjects.indices || !seen.add(index)) continue
            val question = parts[2].trim().trim('"').take(MAX_QUESTION_CHARS)
            if (question.length < MIN_QUESTION_CHARS || !question.contains('?')) continue
            val subject = subjects[index]
            val answerable = when {
                // A question about a person is never sent to a search engine,
                // whatever the model says.
                subject.subjectKind == OpenQuestionEntity.SUBJECT_KG_NODE &&
                    subject.context.contains("A person called") -> OpenQuestionEntity.ANSWERABLE_USER
                parts[1].trim().lowercase().startsWith("w") -> OpenQuestionEntity.ANSWERABLE_WORLD
                else -> OpenQuestionEntity.ANSWERABLE_USER
            }
            out += Authored(subject, question, answerable)
        }
        return out
    }

    private fun systemPrompt(): String = """
        You write the questions an assistant wants to ask the person it works for.
        ${PromptFraming.UNTRUSTED_DATA_DIRECTIVE}

        For each numbered item, write ONE short question, under 20 words, in the
        second person. Ask only about what the item says. Never invent a detail,
        a name, or a reason. If an item is too vague to ask about, skip it.

        Also say who can answer it:
          user  - only this person knows (anything about them, their life, or people they know)
          world - a general fact anyone could look up

        Reply with one line per item and nothing else:
        <number>|<user or world>|<question>
    """.trimIndent()

    private companion object {
        const val TAG = "QuestionAuthor"
        const val MIN_QUESTION_CHARS = 8
        const val MAX_QUESTION_CHARS = 160
    }
}
