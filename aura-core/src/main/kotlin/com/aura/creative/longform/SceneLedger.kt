package com.aura.creative.longform

import android.util.Log
import com.aura.creative.CanonFactDao
import com.aura.creative.CanonFactEntity
import com.aura.creative.ContinuityIssueDao
import com.aura.creative.CreativeArtifactStore
import com.aura.creative.CreativeProject
import com.aura.creative.CreativeProjectStore
import com.aura.creative.CreativeRevisionDao
import com.aura.creative.StoryBeat
import com.aura.providers.ChatOptions
import com.aura.providers.CheapModelResolver
import com.aura.providers.ModelRole
import com.aura.providers.ModelRoleRouter
import com.aura.providers.ProviderMessage
import com.aura.providers.ProviderRegistry
import com.aura.providers.ResponseSchema
import com.aura.providers.StructuredJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
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
 * What the manuscript remembers about itself.
 *
 * `SceneContextBuilder` documents an eight-section budget for a scene and
 * `LongformRunner` supplied six of them: `storySoFar` and `retrieved` were
 * defaulted parameters no production caller passed. Scene twelve of a novel saw
 * the outline titles and the last 2,000 characters of scene eleven, and had not
 * read scenes one through ten. This class is what fills them.
 *
 * **Deliberately has no `Context` and is not a Worker**, for the reason
 * [LongformRunner]'s KDoc gives: everything that decides something stays in a
 * plain class a JVM test can drive.
 *
 * The three jobs are one class rather than three because the act is one act —
 * extract, compare against what canon already holds, decide whether the
 * difference is a contradiction, write three places. Split across three injected
 * components, the orchestration reassembles inside the runner, which is what
 * `AgentRunExecutorWorker` did and why it has no test of its logic at all.
 */
@Singleton
class SceneLedger @Inject constructor(
    private val projectStore: CreativeProjectStore,
    private val artifactStore: CreativeArtifactStore,
    private val revisionDao: CreativeRevisionDao,
    private val canonFactDao: CanonFactDao,
    private val continuityIssueDao: ContinuityIssueDao,
    private val registry: ProviderRegistry,
    private val modelRoleRouter: ModelRoleRouter,
    private val cheapModelResolver: CheapModelResolver,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /**
     * Read one committed scene and record what it established.
     *
     * Called **after** the artifact commit and outside its `NonCancellable`
     * block, so a bookkeeping call can never endanger a scene that has already
     * been generated, streamed and paid for — the lesson `LongformRunner`'s
     * commit block records from a real device failure.
     *
     * The consequence is that a failure here leaves a committed scene with a
     * blank synopsis, which the runner's back-fill clears on a later slice.
     *
     * @return true when a synopsis was stored.
     */
    suspend fun record(
        project: CreativeProject,
        branchId: String,
        beatIndex: Int,
        artifactId: String,
        revisionId: String,
        sceneText: String,
        sceneModel: String,
    ): Boolean {
        val model = resolveModel(sceneModel) ?: run {
            Log.w(TAG, "no model available for the ledger; scene $beatIndex left unrecorded")
            return false
        }
        val extraction = extract(sceneText, model) ?: return false

        val synopsis = extraction.synopsis.trim().take(SYNOPSIS_CAP)
        if (synopsis.isBlank()) return false

        // Re-read rather than writing through the caller's snapshot. The commit
        // that marked this beat drafted has already landed, and the user can
        // edit the outline in the World tab while a run is in flight — the same
        // reason the runner re-reads the project on every pass.
        val current = runCatching { projectStore.get(project.id) }
            .onFailure { Log.w(TAG, "could not re-read the project: ${it.message}", it) }
            .getOrNull() ?: return false
        val beats = current.world.outline
        if (beatIndex !in beats.indices) return false

        val facts = extraction.facts
            .filter { it.subjectType in SUBJECT_TYPES && it.subjectId.isNotBlank() && it.predicate.isNotBlank() && it.value.isNotBlank() }
            .map { it.toEntity(project.id, branchId, revisionId) }

        if (facts.isNotEmpty()) {
            val written = runCatching { reconcile(project.id, branchId, artifactId, facts) }
                .onFailure { Log.w(TAG, "could not reconcile canon facts: ${it.message}", it) }
                .isSuccess
            // The synopsis is back-fill's only sentinel: a beat with one is never
            // revisited. Writing it after the facts failed would strand them, so the
            // beat stays blank and the next slice tries again.
            if (!written) return false
        }

        val updated = beats.toMutableList().also {
            it[beatIndex] = it[beatIndex].copy(synopsis = synopsis)
        }
        return runCatching { projectStore.updateWorld(project.id, current.world.copy(outline = updated)) }
            .onFailure { Log.w(TAG, "could not store the synopsis: ${it.message}", it) }
            .isSuccess
    }

    /**
     * Everything the book has established before the beat being drafted.
     *
     * **No model call.** Each synopsis was written once, by [record], when the
     * scene was fresh. Nothing re-summarises them. Rolling re-summarisation is
     * where a story-so-far drifts: every pass compounds the previous pass's
     * compression, and by scene twelve it is a summary of summaries with nothing
     * left to check it against.
     *
     * Budgeted here rather than by `section()`'s `.take()`, because `.take()`
     * truncates the tail — it would keep scene one and drop the scene just
     * written. Accumulates backwards from the current beat until the budget is
     * spent, then reverses, so the most recent scenes are the ones guaranteed to
     * survive. A book long enough to lose its early synopses still carries every
     * beat title in the OUTLINE section, which is what that section is for.
     */
    fun storySoFar(beats: List<StoryBeat>, beatIndex: Int): String {
        val kept = ArrayDeque<String>()
        var used = 0
        for (i in (beatIndex - 1) downTo 0) {
            val line = beats.getOrNull(i)?.synopsis?.trim().orEmpty()
            if (line.isEmpty()) continue
            val cost = line.length + 1
            if (used + cost > SceneContextBuilder.SUMMARY_CAP) break
            kept.addFirst(line)
            used += cost
        }
        return kept.joinToString("\n")
    }

    /**
     * The model the ledger runs on.
     *
     * **Not [ModelRoleRouter.resolve]**, which is the obvious call and the wrong
     * one: it falls through to the conversation default, so an unset Creative
     * Critic row would run every extraction on the user's flagship model with
     * nothing anywhere reporting it. [CheapModelResolver] exists for exactly
     * this failure. `sceneModel` is both the fallback and the exclusion — take
     * anything cheaper, but do the work rather than skipping it.
     */
    private suspend fun resolveModel(sceneModel: String): String? =
        modelRoleRouter.explicit(ModelRole.CREATIVE_CRITIC)?.takeIf(String::isNotBlank)
            ?: cheapModelResolver.resolve(sceneModel, sceneModel)

    private suspend fun extract(sceneText: String, model: String): SceneExtraction? =
        StructuredJson.requestJson(
            registry = registry,
            modelId = model,
            messages = listOf(
                ProviderMessage(role = ProviderMessage.Role.system, content = SYSTEM_PROMPT),
                ProviderMessage(role = ProviderMessage.Role.user, content = sceneText.take(MAX_SCENE_CHARS)),
            ),
            options = ChatOptions(temperature = 0.0, maxTokens = 700),
            schema = EXTRACTION_SCHEMA,
            timeoutMs = EXTRACTION_TIMEOUT_MS,
            tag = TAG,
        ) { cleaned ->
            runCatching { json.decodeFromString(SceneExtraction.serializer(), cleaned) }
                .onFailure { Log.w(TAG, "unparseable scene extraction: ${it.message}", it) }
                .getOrNull()
        }

    private fun ExtractedFact.toEntity(projectId: String, branchId: String, revisionId: String) =
        CanonFactEntity(
            // Deterministic, not random: a re-extraction of the same revision must
            // REPLACE its own rows rather than duplicate them. Built from the same
            // normalised values stored below, so the id and the row always agree.
            id = UUID.nameUUIDFromBytes(
                "$revisionId|$subjectType|${subjectId.trim()}|${predicate.trim().lowercase()}".toByteArray()
            ).toString(),
            projectId = projectId,
            branchId = branchId,
            subjectType = subjectType,
            subjectId = subjectId.trim(),
            predicate = predicate.trim().lowercase(),
            valueJson = JsonPrimitive(value.trim()).toString(),
            confidence = confidence.coerceIn(0f, 1f),
            sourceRevisionId = revisionId,
            status = "active",
        )

    /**
     * Write the new facts, and record where they disagree with what canon holds.
     *
     * Nothing here calls a model: a contradiction is a comparison of two triples.
     *
     * The new fact always wins and the old is superseded, so canon stays clean
     * and singular and a reader never has to decide which of two rows is
     * current. The disagreement is recorded *beside* canon rather than inside
     * it, which is the whole point — silently superseding absorbs every mistake
     * as though the character simply moved.
     */
    private suspend fun reconcile(
        projectId: String,
        branchId: String,
        artifactId: String,
        facts: List<CanonFactEntity>,
    ) {
        // New facts first, supersession second — deliberately not the other way
        // round, and deliberately not one transaction.
        //
        // `record` is called outside the caller's NonCancellable block on
        // purpose, so being interrupted here is an expected condition. Superseding
        // first would leave a fact retired with no replacement, and `forSubject`
        // filters to active — so the next pass would find nothing to compare
        // against, detect no conflict, and lose the continuity issue for good.
        // This order fails toward two active facts instead: visible, re-detected
        // next pass, and repaired. Absence cannot be recovered; duplication can.
        canonFactDao.upsertAll(facts)

        for (fact in facts) {
            if (fact.predicate !in SINGLE_VALUED) continue
            val existing = canonFactDao
                .forSubject(projectId, branchId, fact.subjectType, fact.subjectId)
                .filter { it.predicate == fact.predicate && it.status == "active" && it.id != fact.id }
            for (old in existing) {
                if (old.valueJson == fact.valueJson) continue
                continuityIssueDao.upsert(
                    com.aura.creative.ContinuityIssueEntity(
                        id = UUID.randomUUID().toString(),
                        projectId = projectId,
                        branchId = branchId,
                        artifactId = artifactId,
                        category = PREDICATE_CATEGORY[fact.predicate] ?: "identity",
                        severity = "warning",
                        message = "${fact.subjectId}: ${fact.predicate} was ${old.valueJson} " +
                            "and this scene says ${fact.valueJson}.",
                        evidenceFactIdsJson = json.encodeToString(
                            kotlinx.serialization.builtins.ListSerializer(String.serializer()),
                            listOf(old.id, fact.id),
                        ),
                        status = "open",
                    ),
                )
                canonFactDao.updateStatus(old.id, "superseded", System.currentTimeMillis())
            }
        }
    }

    companion object {
        private const val TAG = "SceneLedger"

        /**
         * A prompt asking for two sentences is a request; a cap is a guarantee.
         * `SceneContextBuilder.SUMMARY_CAP` is budgeted as thirty of these.
         */
        const val SYNOPSIS_CAP = 400

        /** Matches `CanonFactEntity.subjectType`'s documented values. */
        val SUBJECT_TYPES = setOf(
            "character", "location", "faction", "object", "rule", "timeline", "relationship",
        )

        /**
         * Predicates that cannot hold two values at once, and are therefore the
         * only ones where a different value is a contradiction rather than an
         * addition. Everything else — traits, allies, possessions, knowledge —
         * accumulates.
         *
         * Conservative on purpose. Pausing a run on a conflict was rejected
         * precisely because early false positives are likely, and a flag earns
         * trust by having its first ten be real. Tune this by reading real
         * flags, not by argument.
         */
        val SINGLE_VALUED = setOf("location", "age", "alive", "allegiance", "occupation", "rank")

        /**
         * `ContinuityIssueEntity.category` documents a taxonomy of continuity-error
         * *kinds* — identity, location, timeline, knowledge, relationship, rule,
         * visual, voice. [SINGLE_VALUED] holds predicates, which are finer-grained
         * and mostly outside that set, so writing one straight into the column
         * would put values there that its own KDoc says cannot appear.
         *
         * Nothing is lost by mapping: the issue's message names the predicate and
         * both values, and `evidenceFactIdsJson` points at the two facts that
         * carry it exactly.
         */
        private val PREDICATE_CATEGORY = mapOf(
            "location" to "location",
            "age" to "identity",
            "alive" to "identity",
            "occupation" to "identity",
            "rank" to "identity",
            "allegiance" to "relationship",
        )

        /** A scene is 1,200 words; this is generous and bounds a runaway one. */
        private const val MAX_SCENE_CHARS = 12_000

        /** Longer than the write gate's 8s: this reads a whole scene, not a message. */
        private const val EXTRACTION_TIMEOUT_MS = 20_000L

        private val SYSTEM_PROMPT = """
            You are the continuity clerk for a long-form creative project. You are given one
            scene that has just been written. Record what it established, for the writer of
            the next scene.

            Return two things.

            synopsis: two sentences. What changed in this scene, and what is now true that was
            not true before. Write it for someone who has not read the scene. No praise, no
            summary of the prose style, no commentary.

            facts: the concrete, checkable things this scene established. Each is a subject, a
            predicate and a value — for example a character's location, age, allegiance,
            occupation, rank, or whether they are alive; a relationship formed or broken; a
            rule of the world stated outright.

            subjectType must be one of: character, location, faction, object, rule, timeline,
            relationship.

            Record only what the scene states or plainly shows. Do not infer, do not carry
            facts forward from earlier scenes, and do not record atmosphere, mood, or
            interpretation. An empty facts list is a correct answer for a scene that only
            moves people around a room.
        """.trimIndent()

        private val EXTRACTION_SCHEMA = ResponseSchema(
            name = "scene_ledger_extraction",
            schema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("synopsis", buildJsonObject { put("type", "string") })
                    put("facts", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("subjectType", buildJsonObject {
                                    put("type", "string")
                                    put("enum", buildJsonArray { SUBJECT_TYPES.forEach { add(JsonPrimitive(it)) } })
                                })
                                put("subjectId", buildJsonObject { put("type", "string") })
                                put("predicate", buildJsonObject { put("type", "string") })
                                put("value", buildJsonObject { put("type", "string") })
                                put("confidence", buildJsonObject { put("type", "number") })
                            })
                            put("required", buildJsonArray {
                                add(JsonPrimitive("subjectType"))
                                add(JsonPrimitive("subjectId"))
                                add(JsonPrimitive("predicate"))
                                add(JsonPrimitive("value"))
                            })
                        })
                    })
                })
                put("required", buildJsonArray { add(JsonPrimitive("synopsis")) })
            },
        )
    }
}

/** Typed rather than hand-parsed — see `StructuredJson`'s KDoc on why. */
@Serializable
internal data class SceneExtraction(
    val synopsis: String = "",
    val facts: List<ExtractedFact> = emptyList(),
)

@Serializable
internal data class ExtractedFact(
    val subjectType: String = "",
    val subjectId: String = "",
    val predicate: String = "",
    val value: String = "",
    val confidence: Float = 1.0f,
)
