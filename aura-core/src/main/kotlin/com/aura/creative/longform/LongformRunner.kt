package com.aura.creative.longform

import android.util.Log
import com.aura.agent.Brain
import com.aura.agent.BrainChunk
import com.aura.creative.CreativeArtifactStore
import com.aura.creative.CreativeProject
import com.aura.creative.CreativeProjectStore
import com.aura.creative.StoryBeat
import com.aura.providers.ChatOptions
import com.aura.providers.ModelRole
import com.aura.providers.ModelRoleRouter
import com.aura.providers.ProviderMessage
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Why a slice of work stopped. The worker decides what to do next from this. */
enum class LongformOutcome {
    /** Every beat is drafted. */
    COMPLETED,

    /** Work remains and the worker should re-enqueue itself. */
    PAUSED_FOR_TIME,

    /** The user asked to stop; whatever was written is committed. */
    CANCELLED,

    /** Something unrecoverable. The job row carries the reason. */
    FAILED,
}

/**
 * Drafts a long-form work one scene at a time.
 *
 * **Deliberately has no `Context` and is not a Worker.** `AgentRunExecutorWorker`
 * put its logic inside `doWork()` and consequently has no unit test of that
 * logic at all; this keeps the decisions — which beat is next, when to stop,
 * what to commit — in a plain class that a test can drive with a mocked [Brain].
 * [LongformRunWorker] is a shell around it.
 *
 * Durability comes from where state is written, not from holding it in memory:
 * each completed scene updates its beat's status in the project's `worldJson`
 * and writes the text as an artifact, so a run killed mid-scene resumes at that
 * beat and redrafts only it.
 */
@Singleton
class LongformRunner @Inject constructor(
    private val runStore: LongformRunStore,
    private val projectStore: CreativeProjectStore,
    private val artifactStore: CreativeArtifactStore,
    private val contextBuilder: SceneContextBuilder,
    private val brain: Brain,
    private val progressBus: LongformProgressBus,
    private val modelRoleRouter: ModelRoleRouter? = null,
) {

    /**
     * Draft scenes until the plan is done, [deadlineMs] passes, or [isStopped]
     * reports the worker has been cancelled.
     *
     * @param nowMs injected so the wall-clock guard is testable without waiting.
     */
    suspend fun runSlice(
        jobId: String,
        deadlineMs: Long,
        isStopped: () -> Boolean,
        nowMs: () -> Long = { System.currentTimeMillis() },
    ): LongformOutcome {
        val job = runStore.get(jobId) ?: return LongformOutcome.FAILED
        if (job.status in LongformStatus.TERMINAL) return LongformOutcome.COMPLETED
        if (job.status == LongformStatus.CANCELLING) {
            runStore.finish(jobId, LongformStatus.CANCELLED, emptyList())
            return LongformOutcome.CANCELLED
        }

        val model = resolveModel()
            ?: return LongformOutcome.FAILED.also {
                runStore.fail(jobId, "no_model", "Configure an LLM provider and choose a model before drafting.")
            }

        // Guards against drafting the same beat forever.
        //
        // The loop re-reads the project each pass and advances by finding the
        // first beat that is not yet "drafted". That works only if the commit
        // actually persisted — and the world update is best-effort, wrapped in
        // runCatching and logged. If it silently fails, the next pass finds the
        // same beat still "planned" and redrafts it, and the model is billed for
        // every repetition. An unbounded spend loop is a much worse failure than
        // a stalled run, so a beat that does not advance ends the slice.
        var lastDraftedIndex = -1
        var scenesThisSlice = 0

        while (true) {
            // Re-read the project every iteration. The user can edit the outline
            // in the World tab while a run is in flight, and a snapshot taken
            // once at the top would quietly draft against a plan that no longer
            // exists.
            val project = projectStore.get(job.projectId)
                ?: return LongformOutcome.FAILED.also {
                    runStore.fail(jobId, "no_project", "The project was deleted while drafting.")
                }
            val beats = project.world.outline

            if (beats.isEmpty()) {
                runStore.fail(jobId, "no_outline", "Plan an outline before drafting.")
                return LongformOutcome.FAILED
            }

            // Cancellation is checked from Room, not just from the worker's own
            // flag: markCancelling writes there first precisely so a worker that
            // is mid-scene or re-enqueuing still sees it.
            if (isStopped() || runStore.get(jobId)?.status == LongformStatus.CANCELLING) {
                progressBus.clear()
                runStore.finish(jobId, LongformStatus.CANCELLED, drafted(beats))
                return LongformOutcome.CANCELLED
            }

            val nextIndex = beats.indexOfFirst { it.status != STATUS_DRAFTED }
            if (nextIndex < 0) {
                progressBus.clear()
                runStore.finish(jobId, LongformStatus.SUCCEEDED, drafted(beats))
                return LongformOutcome.COMPLETED
            }

            // The beat did not move on despite a scene being committed, so the
            // commit is not reaching the database. Stop rather than pay for the
            // same scene again.
            if (nextIndex <= lastDraftedIndex) {
                progressBus.clear()
                runStore.fail(
                    jobId,
                    "no_progress",
                    "Beat ${nextIndex + 1} was drafted but did not persist; stopping to avoid redrafting it.",
                )
                return LongformOutcome.FAILED
            }

            if (nowMs() >= deadlineMs || scenesThisSlice >= MAX_SCENES_PER_SLICE) {
                progressBus.clear()
                runStore.markRunning(jobId, percent(beats))
                return LongformOutcome.PAUSED_FOR_TIME
            }

            runStore.markRunning(jobId, percent(beats))
            val committed = draftScene(jobId, project, beats, nextIndex, model, isStopped)
            scenesThisSlice++
            if (committed) {
                lastDraftedIndex = nextIndex
            } else {
                // The scene produced nothing usable, or could not be saved.
                // Never simply pause here: pausing re-runs the slice, which
                // regenerates the same beat and bills for it again. On device
                // this became an unbounded spend loop — a foreign-key failure in
                // the artifact insert meant no scene could ever commit, and the
                // run generated scene 1 over and over.
                //
                // A few attempts absorb a transient provider error; past that,
                // the failure is structural and the run stops with a reason.
                progressBus.clear()
                runStore.recordAttempt(jobId)
                val attempts = (runStore.get(jobId)?.attempts ?: 0)
                return if (attempts >= MAX_SCENE_ATTEMPTS) {
                    runStore.fail(
                        jobId,
                        "scene_failed",
                        "Scene ${nextIndex + 1} could not be written after $attempts attempts.",
                    )
                    LongformOutcome.FAILED
                } else {
                    LongformOutcome.PAUSED_FOR_TIME
                }
            }
        }
    }

    /**
     * Write one scene and commit it. Returns false when nothing usable came back.
     *
     * Commits happen in one order for a reason: artifact first, then the beat's
     * status. A crash between them leaves an orphaned scene artifact, which is
     * recoverable and visible. The reverse would mark a beat drafted with no
     * text behind it, and the run would skip past a hole in the manuscript.
     */
    private suspend fun draftScene(
        jobId: String,
        project: CreativeProject,
        beats: List<StoryBeat>,
        index: Int,
        model: String,
        isStopped: () -> Boolean,
    ): Boolean {
        val beat = beats[index]
        val previousTail = previousSceneTail(beats, index)
        val context = contextBuilder.build(
            project = project,
            beats = beats,
            beatIndex = index,
            previousSceneTail = previousTail,
        )

        progressBus.beginScene(jobId, index, beats.size, beat.title)
        val text = StringBuilder()
        val streamed = runCatching {
            brain.stream(
                model,
                listOf(
                    ProviderMessage(ProviderMessage.Role.system, context.systemPrompt),
                    ProviderMessage(ProviderMessage.Role.user, context.userPrompt),
                ),
                emptyList(),
                sceneOptions(),
            ).collect { chunk ->
                when (chunk) {
                    is BrainChunk.Text -> {
                        text.append(chunk.text)
                        progressBus.appendToScene(chunk.text)
                    }
                    is BrainChunk.Error -> throw IllegalStateException("${chunk.code}: ${chunk.message}")
                    else -> Unit
                }
            }
        }.onFailure { Log.w(TAG, "scene ${index + 1} failed: ${it.message}", it) }

        val body = text.toString().trim()
        // A cancelled or errored run still commits what it has, as long as there
        // is enough of it to be prose. The user asked to stop, not to throw away
        // a scene they have already paid for and watched appear — and a stream
        // that failed halfway still leaves usable text behind.
        if (body.length < MIN_SCENE_CHARS) {
            if (streamed.isFailure) Log.w(TAG, "scene ${index + 1} produced nothing usable")
            return false
        }

        // Committing is non-cancellable, and that is the whole point of it.
        //
        // WorkManager stops a worker by cancelling its coroutine. Without this,
        // a stop arriving after the model had already answered cancelled the
        // database write too — so a scene that was generated, streamed to the
        // screen and paid for was thrown away, and the beat stayed "planned" so
        // the next slice generated it again. Observed on device as
        // "could not persist scene 1: Job was cancelled".
        //
        // Same reasoning as BackupManager.restore's insert phase: once the
        // expensive irreversible part has happened, finishing the cheap durable
        // part is not optional.
        val artifactId = withContext(NonCancellable) {
            runCatching {
                val id = artifactStore.create(
                    projectId = project.id,
                    branchId = beatBranch(jobId),
                    kind = KIND_SCENE,
                    title = "${index + 1}. ${beat.title}",
                    initialContent = body,
                    authorKind = "generation",
                    modelId = model,
                    prompt = beat.summary.ifBlank { beat.title },
                ).id
                val updated = beats.toMutableList().also {
                    it[index] = beat.copy(status = STATUS_DRAFTED, artifactId = id)
                }
                projectStore.updateWorld(project.id, project.world.copy(outline = updated))
                id
            }.onFailure { Log.w(TAG, "could not persist scene ${index + 1}: ${it.message}", it) }.getOrNull()
        }
        progressBus.clear()
        return artifactId != null
    }

    /**
     * The tail of the previous scene, verbatim.
     *
     * Verbatim rather than summarised because this is what carries voice, the
     * position of everyone in the room, and any sentence the last scene left
     * hanging. A summary of the previous scene tells the model what happened; the
     * last few hundred words tell it how the prose sounds.
     */
    private suspend fun previousSceneTail(beats: List<StoryBeat>, index: Int): String {
        if (index == 0) return ""
        val previous = beats[index - 1].artifactId.takeIf { it.isNotBlank() } ?: return ""
        return runCatching { artifactStore.currentContent(previous) }
            .onFailure { Log.w(TAG, "could not read the previous scene: ${it.message}", it) }
            .getOrNull()
            .orEmpty()
            .takeLast(SceneContextBuilder.PREVIOUS_TAIL_CAP)
    }

    /**
     * Per-scene budgets, both stated explicitly.
     *
     * Leaving them null would take the resolver's whole-context budget for a
     * 1,200-word scene. Setting `maxTokens` without `thinkingBudget` would let
     * the user's global 32,000 apply to a scene call — `TokenBudgetPolicy` bounds
     * it to the requested output, but 8,192 tokens of thinking per scene across
     * twelve scenes is a cost nobody asked for. 2,048 is enough for the model to
     * plan a scene it already has an outline for.
     */
    private fun sceneOptions() = ChatOptions(
        temperature = SCENE_TEMPERATURE,
        maxTokens = SCENE_MAX_TOKENS,
        thinkingBudget = SCENE_THINKING_BUDGET,
    )

    private suspend fun resolveModel(): String? =
        modelRoleRouter?.explicit(ModelRole.CREATIVE_DRAFT)
            ?: modelRoleRouter?.resolve(ModelRole.CONVERSATION)

    private fun drafted(beats: List<StoryBeat>): List<String> =
        beats.filter { it.status == STATUS_DRAFTED }.map { it.artifactId }.filter { it.isNotBlank() }

    private fun percent(beats: List<StoryBeat>): Int {
        if (beats.isEmpty()) return 0
        return (beats.count { it.status == STATUS_DRAFTED } * 100) / beats.size
    }

    /** Scenes go on the run's own branch, recorded on the job row at creation. */
    private suspend fun beatBranch(jobId: String): String =
        runStore.get(jobId)?.branchId.orEmpty()

    private companion object {
        const val TAG = "LongformRunner"
        const val KIND_SCENE = "scene"
        const val STATUS_DRAFTED = "drafted"

        /** Below this a "scene" is an apology or a truncated fragment, not prose. */
        const val MIN_SCENE_CHARS = 200

        const val SCENE_TEMPERATURE = 0.85
        const val SCENE_MAX_TOKENS = 8_192
        const val SCENE_THINKING_BUDGET = 2_048

        /**
         * Second backstop on scenes per execution, independent of the clock.
         *
         * The deadline is the real limit; this bounds spend if a clock source
         * ever misbehaves, and keeps any single slice's cost knowable. Twenty
         * scenes is far more than seven minutes can produce.
         */
        const val MAX_SCENES_PER_SLICE = 20

        /**
         * How many times one scene may fail before the run stops.
         *
         * A couple of attempts absorb a transient provider error. Beyond that
         * the cause is structural, and retrying only pays the model again to
         * produce something that cannot be saved.
         */
        const val MAX_SCENE_ATTEMPTS = 3
    }
}
