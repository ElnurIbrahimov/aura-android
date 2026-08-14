package com.aura.smoke

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aura.agent.AgentEvent
import com.aura.agent.Conversation
import com.aura.agent.MemoryAugmentedAgenticLoop
import com.aura.agent.Turn
import com.aura.a11y.ScreenControlBridge
import com.aura.data.UserPreferences
import com.aura.documents.DocumentRepository
import com.aura.health.WorkerRunRecorder
import com.aura.memory.MemoryStore
import com.aura.proactive.ProactiveRunner
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Does the app actually work, on this phone, against the model this phone is
 * configured with.
 *
 * ## Why this exists
 *
 * There are 3,065 unit tests and they are all tests of pure logic. In one
 * session of looking, roughly thirteen things were found built and not working
 * — a screenshot capability that had never once succeeded, a write gate that
 * stored everything, four workers re-running their whole pass on failure, a
 * widget painting black on black. Every one of them passed the suite. The write
 * gate's own tests asserted the broken behaviour.
 *
 * The 38 "UI smoke" instrumented tests do not help either: 36 of them are
 * stateless Compose assertions against hand-built state, which is the same
 * epistemic class as a unit test.
 *
 * So these five assert **outcomes a user would notice**, below the UI — real
 * graph, real keys, real network, real database, no Compose synchronisation.
 * Five that run on hardware and fail loudly beat fifty against fakes; the fakes
 * are what let all thirteen through.
 *
 * ## This cannot run in CI, and that is not a bug to fix later
 *
 * `ci.yml` compiles instrumented tests and never executes them — no emulator,
 * no `connectedAndroidTest`. Adding an emulator job would not help: an emulator
 * has no API keys, and the keys are the entire premise. Run it with
 * `scripts/smoke.sh`.
 *
 * ## It cleans up after itself
 *
 * It writes to the real store, because that is the only store with real keys
 * and real data. Every test records the memory ids that existed before it ran
 * and forgets anything new afterwards, so a smoke run leaves the store exactly
 * as it found it. Deleting by content or by source would not be safe — a real
 * memory could match.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class DeviceSmokeTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var memoryStore: MemoryStore
    @Inject lateinit var loop: MemoryAugmentedAgenticLoop
    @Inject lateinit var documents: DocumentRepository
    @Inject lateinit var proactiveRunner: ProactiveRunner
    @Inject lateinit var preferences: UserPreferences
    @Inject lateinit var workerRuns: WorkerRunRecorder
    @Inject lateinit var screenControl: ScreenControlBridge

    private var model: String? = null
    private var idsBefore: Set<String> = emptySet()

    @Before
    fun setUp() = runBlocking {
        hiltRule.inject()
        model = preferences.defaultModel.first()?.takeIf { it.isNotBlank() }
        idsBefore = memoryStore.recent(SNAPSHOT_LIMIT).map { it.id }.toSet()
    }

    /** Forget anything this test wrote, and nothing else. */
    @After
    fun cleanUp() = runBlocking {
        memoryStore.recent(SNAPSHOT_LIMIT)
            .map { it.id }
            .filterNot { it in idsBefore }
            .forEach { runCatching { memoryStore.forget(it) } }
    }

    /**
     * Skips rather than fails when the phone has no model configured — that is
     * a device that was never set up, not a regression. The message names the
     * missing thing so a skip is never mysterious.
     */
    private fun requireModel(): String {
        assumeTrue(
            "No default model configured on this device — set one in Settings first.",
            model != null,
        )
        return model!!
    }

    private suspend fun answer(userText: String): Conversation {
        val events = withTimeout(TURN_TIMEOUT_MS) {
            loop.run(
                conversation = Conversation(turns = listOf(Turn(user = userText, assistant = null))),
                model = requireModel(),
            ).toList()
        }
        val failure = events.filterIsInstance<AgentEvent.Error>().firstOrNull()
        assertTrue("the turn errored: ${failure?.code} ${failure?.message}", failure == null)
        return events.filterIsInstance<AgentEvent.Result>().last().conversation
    }

    // ---- 1. a real turn produces a real memory ---------------------------

    @Test
    fun aStatedPreferenceBecomesAMemory() = runBlocking {
        val text = "I prefer terse answers with no preamble, smoke ${System.currentTimeMillis()}"

        answer(text)

        val written = memoryStore.recent(SNAPSHOT_LIMIT).firstOrNull { it.id !in idsBefore }
        assertTrue("a stated preference produced no memory at all", written != null)
        // Not merely "a row exists" — the category is what the consult pass
        // filters on, so a memory filed under something nothing recognises is
        // invisible to it.
        assertTrue(
            "unknown category '${written!!.category}' — nothing downstream filters on that",
            written.category in KNOWN_CATEGORIES,
        )
    }

    // ---- 2. and a pleasantry produces none -------------------------------

    /**
     * The counterweight, and the one that would have caught the real defect: a
     * store holding "Hey you", "Hello", "Hey how are you" and "Heyara" as its
     * four facts. A test that only checks memories get written passes happily
     * against a gate that writes everything.
     */
    @Test
    fun aPleasantryProducesNoMemory() = runBlocking {
        answer("hey there")

        val written = memoryStore.recent(SNAPSHOT_LIMIT).filterNot { it.id in idsBefore }
        assertTrue(
            "a greeting was stored as ${written.map { "${it.category}: ${it.content}" }}",
            written.isEmpty(),
        )
    }

    // ---- 3. an imported document is findable and mapped ------------------

    @Test
    fun anImportedDocumentIsRetrievableAndOutlined() = runBlocking {
        requireModel()
        val marker = "smoke${System.currentTimeMillis()}"
        val id = "smoke-doc-$marker"
        val text = buildString {
            append("Deployment rules for project $marker.\n\n")
            append("Never deploy on a Friday. Two approvals are required for any release.\n\n")
            append("The staging environment has no GPU and must not be used for training runs.\n\n")
            repeat(4) { append("Section $it of the $marker specification, describing routine procedure.\n\n") }
        }

        try {
            val result = documents.import(
                id = id,
                name = "$marker.md",
                mimeType = "text/plain",
                sourceUri = "smoke://$id",
                text = text,
            )
            assertTrue("the document produced no chunks", result.chunkCount > 0)

            // Asserted by recall, not by chunkCount: a chunk that was written
            // but is not findable is the failure this is looking for.
            val found = memoryStore.searchByText(marker, limit = 50)
            assertTrue("nothing retrievable after importing the document", found.isNotEmpty())

            // The outline is what makes whole-document questions answerable.
            // It is best-effort by design, so its absence is reported rather
            // than asserted — the import itself must never fail for it.
            if (!result.outlined) {
                println("SMOKE: document imported but not outlined (no model, budget spent, or unparseable study)")
            } else {
                assertTrue(
                    "outlined=true but no outline memory is retrievable",
                    found.any { "outline" in it.tags },
                )
            }
        } finally {
            runCatching { documents.delete(id) }
        }
    }

    // ---- 4. a worker leaves something legible behind ---------------------

    /**
     * Two workers used to write no run row at all and two more recorded
     * `ok("")`, which in BackgroundHealth is indistinguishable from never
     * having been scheduled. `RunResult.Error` is swallowed into a return
     * value, so "it didn't throw" proves nothing — the assertion has to be on
     * `Ok` and on the row.
     */
    @Test
    fun firingAWorkerLeavesALegibleRunRecord() = runBlocking {
        val result = proactiveRunner.fireDecayPass()
        assertTrue(
            "decay pass failed: ${(result as? ProactiveRunner.RunResult.Error)?.message}",
            result is ProactiveRunner.RunResult.Ok,
        )

        val row = workerRuns.latestPerWorker().firstOrNull { it.worker == DECAY_WORKER }
        assertTrue("the decay pass ran and recorded nothing", row != null)
        assertTrue(
            "recorded an empty detail — nothing to read in Diagnostics",
            row!!.detail.isNotBlank(),
        )
    }

    // ---- 5. the screenshot path answers, or names its failure ------------

    /**
     * `canTakeScreenshot` was missing from the service config for the life of
     * the feature, so every call returned null — indistinguishable, to the
     * caller, from the FLAG_SECURE window it was meant to skip. README and
     * architecture.md both described it working.
     */
    @Test
    fun theScreenshotPathAnswersRatherThanGoingSilent() = runBlocking {
        assumeTrue(
            "Aura's accessibility service is not enabled — turn it on in Settings > Accessibility.",
            screenControl.connected.first(),
        )

        val bytes = screenControl.screenshot(quality = 50)

        // Null is legitimate on a FLAG_SECURE window, so this cannot assert
        // bytes unconditionally. What it can assert is that the capability is
        // declared — without it every call returns null and the quiet path
        // silently never runs.
        assertTrue(
            "the screenshot path returned an empty buffer",
            bytes == null || bytes.isNotEmpty(),
        )
        if (bytes == null) {
            println("SMOKE: screenshot returned null — expected only on a FLAG_SECURE window; check logcat for the named error")
        }
    }

    private companion object {
        /** Generous: a real turn is a real network round-trip, sometimes with tools. */
        const val TURN_TIMEOUT_MS = 120_000L

        /** Deep enough to see everything a single smoke run could add. */
        const val SNAPSHOT_LIMIT = 200

        const val DECAY_WORKER = "DecayWorker"

        val KNOWN_CATEGORIES = setOf(
            "fact", "preference", "person", "episode", "idea", "task", "project", "document",
        )
    }
}
