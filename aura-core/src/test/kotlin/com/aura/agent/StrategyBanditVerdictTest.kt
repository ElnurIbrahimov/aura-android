package com.aura.agent

import com.aura.provenance.ConversationProvenance
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the bandit is allowed to call a success.
 *
 * `ChatSendController` passed `success = !runFailed`, where `runFailed` means the provider
 * errored or the step limit was hit. That is "did the machinery complete", not "was the
 * answer any good" — so a confidently wrong reply and a perfect one incremented α
 * identically, and a turn the user thumbed *down* was recorded as a success.
 *
 * This is a Beta-Bernoulli bandit sampled with Thompson selection. Feeding it a signal that
 * is true for nearly every turn drives every arm to Beta(large, ~0), every sample to ~1.0,
 * and the choice of reasoning strategy to noise. The failure is quiet: the bandit keeps
 * working, keeps picking, and picks meaninglessly.
 *
 * The real verdict exists — `RetrievalLabelStore.TurnSignal` already records THUMBS_UP,
 * THUMBS_DOWN and REGENERATED — but it arrives minutes later, long after the run finished
 * and the outcome was already written. So the fix is not a swapped argument: the run has to
 * leave the outcome *pending*, keyed by the turn, and a verdict resolves it later. A turn
 * that never gets one is never counted, which is the point — silence is not approval.
 */
class StrategyBanditVerdictTest {

    private val store = mockk<StrategyBanditStore>(relaxed = true)
    private fun bandit() = StrategyBandit(store)

    private val turn = ConversationProvenance("c1", 1000L)
    private val category = ProblemCategory.ANALYSIS
    private val strategy = ReasoningStrategy.MULTI_STEP_REFLECT

    @Test
    fun `a pending run records nothing until a verdict arrives`() = runTest {
        // The whole point. Under the old behaviour this turn was already a success.
        bandit().notePending(turn, category, strategy)

        coVerify(exactly = 0) { store.recordOutcome(any(), any(), any()) }
    }

    @Test
    fun `a thumbs up resolves the pending run as a success`() = runTest {
        val b = bandit()
        b.notePending(turn, category, strategy)

        assertTrue(b.resolvePending(turn, success = true))

        coVerify(exactly = 1) { store.recordOutcome(category, strategy, true) }
    }

    @Test
    fun `a thumbs down resolves it as a failure`() = runTest {
        val b = bandit()
        b.notePending(turn, category, strategy)

        assertTrue(b.resolvePending(turn, success = false))

        coVerify(exactly = 1) { store.recordOutcome(category, strategy, false) }
    }

    @Test
    fun `a verdict for a turn nobody noted changes nothing`() = runTest {
        // Reactions can arrive for a turn from a previous process, or from a run this
        // bandit never chose a strategy for. Attributing those to whatever arm is at hand
        // is worse than dropping them.
        assertFalse(bandit().resolvePending(ConversationProvenance("c1", 999L), success = true))

        coVerify(exactly = 0) { store.recordOutcome(any(), any(), any()) }
    }

    @Test
    fun `a turn is counted once, however many times the user changes their mind`() = runTest {
        // Reactions are a toggle. Up, then down, then up again must not add three
        // observations for one answer.
        val b = bandit()
        b.notePending(turn, category, strategy)

        assertTrue(b.resolvePending(turn, success = true))
        assertFalse(b.resolvePending(turn, success = false))
        assertFalse(b.resolvePending(turn, success = true))

        coVerify(exactly = 1) { store.recordOutcome(any(), any(), any()) }
    }

    @Test
    fun `pending turns do not accumulate without bound`() = runTest {
        // Most turns never get a verdict, so this map only ever grows. Bounded, oldest
        // first: an unresolved turn from a thousand turns ago is not going to be judged.
        val b = bandit()
        repeat(StrategyBandit.MAX_PENDING + 10) { i ->
            b.notePending(ConversationProvenance("c1", i.toLong() + 1), category, strategy)
        }

        assertFalse(
            b.resolvePending(ConversationProvenance("c1", 1L), success = true),
            "the oldest pending turn should have been evicted",
        )
        assertTrue(
            b.resolvePending(
                ConversationProvenance("c1", (StrategyBandit.MAX_PENDING + 10).toLong()),
                success = true,
            ),
            "the newest pending turn must still be resolvable",
        )
    }

    @Test
    fun `a failed run is still recorded immediately`() = runTest {
        // A run that errored or hit the step limit is a real failure, observed at the
        // moment it happens. Only the *success* half was meaningless, so only the success
        // half waits for a verdict.
        coEvery { store.recordOutcome(any(), any(), any()) } returns Unit

        bandit().recordOutcome(category, strategy, success = false)

        coVerify(exactly = 1) { store.recordOutcome(category, strategy, false) }
    }
}
