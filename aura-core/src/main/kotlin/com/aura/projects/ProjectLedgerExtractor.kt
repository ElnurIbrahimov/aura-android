package com.aura.projects

import android.util.Log
import com.aura.agent.Turn
import com.aura.curiosity.OpenQuestionEntity
import com.aura.curiosity.OpenQuestionDao
import com.aura.providers.ChatOptions
import com.aura.providers.CheapModelResolver
import com.aura.providers.ProviderMessage
import com.aura.providers.ProviderRegistry
import com.aura.providers.ResponseSchema
import com.aura.providers.StructuredJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads a conversation and records what it settled about a project.
 *
 * This is `SceneLedger` pointed at the user's real work instead of a manuscript,
 * and it inherits that class's discipline deliberately, including the parts that
 * look like over-caution:
 *
 * - **Never throws.** Every failure path returns [Outcome] and logs. It runs on
 *   a worker with nobody watching, so an exception here would surface as a
 *   `WorkerRunRecorder` failure with no useful attribution.
 * - **Refuses blank provenance.** A note whose `sourceConversationId` is empty
 *   cannot answer "why do you think that", and a ledger that cannot be
 *   questioned is worse than no ledger — it is confident.
 * - **Cheap model, low temperature.** [CheapModelResolver] rather than
 *   [com.aura.providers.ModelRoleRouter], which falls through to the
 *   conversation default and would quietly run every sweep on the flagship.
 *
 * **Marked unattended.** Nobody is waiting on this, it runs on a timer, and
 * `ProviderRegistry.chat` therefore refuses it once `BackgroundBudget` is spent
 * — which reads as a skip rather than a failure. `UnattendedCallersAreMarkedTest`
 * names this file; the flag failing open is why that list exists.
 */
@Singleton
class ProjectLedgerExtractor @Inject constructor(
    private val registry: ProviderRegistry,
    private val cheapModelResolver: CheapModelResolver,
    private val store: ProjectStore,
    private val openQuestionDao: OpenQuestionDao? = null,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /** What one pass did, so the worker can log something truthful. */
    data class Outcome(
        val notesWritten: Int = 0,
        val questionsWritten: Int = 0,
        val ran: Boolean = false,
        val reason: String = "",
    ) {
        companion object {
            fun skipped(reason: String) = Outcome(ran = false, reason = reason)
        }
    }

    /**
     * Extract one conversation's contribution to a project's ledger.
     *
     * @param turns the exchange to read. Already filtered by the caller to the
     *   turns newer than the last pass — this class does no watermarking, so it
     *   stays testable without a clock.
     */
    suspend fun extract(
        projectId: String,
        conversationId: String,
        turns: List<Turn>,
        baseModel: String,
    ): Outcome {
        if (conversationId.isBlank()) {
            // Provenance is the thing that makes unsupervised writing safe. See
            // the class KDoc, and `SceneLedger.record`'s identical refusal.
            Log.w(TAG, "refusing to extract with no conversation id; the notes could not be traced")
            return Outcome.skipped("no provenance")
        }
        val project = store.get(projectId)
            ?: return Outcome.skipped("project $projectId is gone").also {
                Log.w(TAG, "project $projectId no longer exists; nothing to record")
            }
        if (project.status != ProjectEntity.STATUS_ACTIVE) {
            // A paused or finished project should stop accruing state. Doing
            // this here rather than in the worker's query means it also holds
            // for a manual re-run.
            return Outcome.skipped("project is ${project.status}")
        }

        val transcript = transcript(turns)
        if (transcript.isBlank()) return Outcome.skipped("nothing said")

        val model = cheapModelResolver.resolve(baseModel, baseModel)
            ?: return Outcome.skipped("no model available").also {
                Log.w(TAG, "no model for the ledger; ${project.name} left unrecorded")
            }

        val known = runCatching { store.activeSubjects(projectId) }
            .onFailure { Log.w(TAG, "could not read known subjects: ${it.message}", it) }
            .getOrDefault(emptyList())

        val extraction = request(project, known, transcript, model)
            ?: return Outcome.skipped("extraction produced nothing")

        var notes = 0
        for (item in extraction.notes) {
            val kind = item.kind.trim().lowercase()
            if (kind !in ProjectNoteEntity.KINDS) continue
            val written = runCatching {
                store.recordNote(
                    projectId = projectId,
                    kind = kind,
                    subject = item.subject,
                    body = item.body,
                    sourceConversationId = conversationId,
                    sourceTurnAt = turns.lastOrNull()?.timestamp ?: System.currentTimeMillis(),
                )
            }.onFailure { Log.w(TAG, "could not record a $kind note: ${it.message}", it) }.getOrNull()
            if (written != null) notes++
        }

        val questions = writeQuestions(project, extraction.questions)

        return Outcome(notesWritten = notes, questionsWritten = questions, ran = true, reason = "ok")
    }

    /**
     * Questions go to `open_questions`, capped at one per pass.
     *
     * `OpenQuestionDao.current()` hands the UI a single question at a time —
     * "one open question at a time is the entire defence against an assistant
     * that plays twenty questions", as that DAO puts it. A sweep across eight
     * projects could otherwise queue eight questions ahead of curiosity's own,
     * and the cap keeps that promise from a table this class does not own.
     */
    private suspend fun writeQuestions(project: ProjectEntity, questions: List<String>): Int {
        val dao = openQuestionDao ?: return 0
        val text = questions.firstOrNull { it.isNotBlank() }?.trim() ?: return 0

        // Never re-ask, and never re-open something dismissed. The subject is the
        // project, so a dismissal here means "stop asking about this project".
        val claimed = runCatching { dao.claimedSubjects() }
            .onFailure { Log.w(TAG, "could not read claimed subjects: ${it.message}", it) }
            .getOrDefault(emptyList())
        if ("${OpenQuestionEntity.SUBJECT_PROJECT}/${project.id}" in claimed) return 0

        return runCatching {
            dao.insert(
                OpenQuestionEntity(
                    id = UUID.randomUUID().toString(),
                    kind = OpenQuestionEntity.KIND_GAP,
                    subjectKind = OpenQuestionEntity.SUBJECT_PROJECT,
                    subjectId = project.id,
                    question = text.take(MAX_QUESTION_CHARS),
                    // Only the user can say where their own project stands. A web
                    // search cannot answer "is the eval harness done".
                    answerable = OpenQuestionEntity.ANSWERABLE_USER,
                ),
            )
            1
        }.onFailure { Log.w(TAG, "could not write a project question: ${it.message}", it) }.getOrDefault(0)
    }

    /** Flatten the turns into something a model can read, newest last. */
    private fun transcript(turns: List<Turn>): String = buildString {
        for (turn in turns) {
            turn.user?.takeIf { it.isNotBlank() }?.let { appendLine("User: ${it.trim()}") }
            turn.assistant?.takeIf { it.isNotBlank() }?.let { appendLine("Aura: ${it.trim()}") }
        }
    }.trim().take(MAX_TRANSCRIPT_CHARS)

    private suspend fun request(
        project: ProjectEntity,
        knownSubjects: List<String>,
        transcript: String,
        model: String,
    ): LedgerExtraction? {
        val vocabulary = if (knownSubjects.isEmpty()) {
            "There are no recorded subjects yet."
        } else {
            "Subjects already in use — reuse the exact wording when a note is about one of " +
                "them: ${knownSubjects.sorted().joinToString(", ")}"
        }
        return StructuredJson.requestJson(
            registry = registry,
            modelId = model,
            messages = listOf(
                ProviderMessage(role = ProviderMessage.Role.system, content = SYSTEM_PROMPT),
                ProviderMessage(
                    role = ProviderMessage.Role.user,
                    content = "Project: ${project.name}\n" +
                        (project.description.takeIf { it.isNotBlank() }?.let { "About: $it\n" } ?: "") +
                        "$vocabulary\n\n---\n$transcript",
                ),
            ),
            // Unattended: nobody is waiting, so the daily background budget
            // governs this call. See the class KDoc.
            options = ChatOptions(temperature = 0.0, maxTokens = 600, attended = false),
            schema = EXTRACTION_SCHEMA,
            timeoutMs = EXTRACTION_TIMEOUT_MS,
            tag = TAG,
        ) { cleaned ->
            runCatching { json.decodeFromString(LedgerExtraction.serializer(), cleaned) }
                .onFailure { Log.w(TAG, "unparseable ledger extraction: ${it.message}", it) }
                .getOrNull()
        }
    }

    private companion object {
        const val TAG = "ProjectLedgerExtractor"

        /** A sweep reads a whole conversation, not one message. */
        const val EXTRACTION_TIMEOUT_MS = 20_000L
        const val MAX_TRANSCRIPT_CHARS = 12_000
        const val MAX_QUESTION_CHARS = 240

        /**
         * The prompt is almost entirely refusals, and deliberately so.
         *
         * The failure that would take months to notice is not a missed decision
         * — the next conversation offers another chance at that. It is an
         * invented one: a ledger that confidently reports a decision the user
         * never made, indistinguishable from a real one, sitting at the top of
         * "where is ARC-AGI-2" forever. So the instructions bias hard toward
         * recording nothing, and an empty result is named as correct twice.
         */
        val SYSTEM_PROMPT = """
            You keep the ledger for one of the user's projects. You are given a conversation
            and you record only what it settled.

            Return two things.

            notes: things that are now true about the project. Each has a kind, a subject and
            a body.
              kind is one of:
                decision - the user chose something, or changed a previous choice
                blocker  - something is stopping progress
                status   - where the work stands
              subject is one or two lowercase words naming what the note is ABOUT, not what it
                says: "payments", "training run", "eval harness", "hosting". A later note with
                the same subject replaces this one, so the subject is what makes the ledger
                converge. Reuse a subject already in use whenever the note is about that same
                thing.
              body is one sentence, in plain past or present tense, written so the user
                recognises their own decision.

            questions: at most one thing you would need the user to tell you to describe this
            project's state accurately. Only ask if the conversation left something genuinely
            unresolved.

            Record only what the USER stated about this project, in their own words, in this
            conversation. Specifically:
            - Do not record anything you suggested, proposed, or offered.
            - Do not record something the user was considering, weighing, or asked about.
              "Should I use X?" is not a decision. "I'm going with X" is.
            - Do not infer, summarise the conversation, or carry anything forward from what
              you already know about the project.
            - Do not record anything about a different project.

            Most conversations settle nothing. An empty notes list is the correct answer for a
            conversation where the user asked questions, thought out loud, or changed the
            subject. Returning nothing is always better than returning something plausible.
        """.trimIndent()

        val EXTRACTION_SCHEMA = ResponseSchema(
            name = "project_ledger_extraction",
            schema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("notes", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("kind", buildJsonObject {
                                    put("type", "string")
                                    put("enum", buildJsonArray {
                                        ProjectNoteEntity.KINDS.sorted().forEach { add(JsonPrimitive(it)) }
                                    })
                                })
                                put("subject", buildJsonObject { put("type", "string") })
                                put("body", buildJsonObject { put("type", "string") })
                            })
                            put("required", buildJsonArray {
                                add(JsonPrimitive("kind"))
                                add(JsonPrimitive("subject"))
                                add(JsonPrimitive("body"))
                            })
                        })
                    })
                    put("questions", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                    })
                })
                put("required", buildJsonArray { add(JsonPrimitive("notes")) })
            },
        )
    }
}

/** Typed rather than hand-parsed — see `StructuredJson`'s KDoc on why. */
@Serializable
internal data class LedgerExtraction(
    val notes: List<ExtractedNote> = emptyList(),
    val questions: List<String> = emptyList(),
)

@Serializable
internal data class ExtractedNote(
    val kind: String = "",
    val subject: String = "",
    val body: String = "",
)
