package com.aura.testing

import org.junit.Test
import kotlin.test.assertTrue

/**
 * A seed with no caller is the bug it was written to fix.
 *
 * `UserPreferences.seedBackgroundModelOnce()` exists because `backgroundModel`
 * had no default and five subsystems hard-return when it is blank. Its own unit
 * test in :aura-core proves the *once* semantics and nothing about whether
 * anything invokes it — which is precisely the shape of the two defects this
 * pass fixed alongside it (`WorkerRunRecorder.prune()` had a unit test, a KDoc
 * naming its caller, and no caller; `OpportunityEntity.suggestedActionJson` went
 * unread for months). A behavioural test cannot cover this: both call sites are
 * a coroutine launched during startup or navigation, and the invariant is that
 * the call is *present*, which is a fact about the source.
 *
 * Two callers, not one. Onboarding reaches a new install; the bootstrap backfill
 * reaches the install that already exists. Dropping either leaves half the
 * installs dark, and the halves are not interchangeable. This covers the
 * onboarding half; `BackgroundModelSeedIsWiredAtStartupTest` in :aura-core
 * covers the other, each scanning its own module's sources.
 */
class BackgroundModelSeedIsWiredTest {

    private val seedCall = "seedBackgroundModelOnce()"

    @Test
    fun `onboarding seeds the background model when it completes`() {
        val source = sourceDir("src/main/kotlin/com/aura")
            .resolve("ui/screens/onboarding/OnboardingRoute.kt")
            .also { check(it.isFile) { "OnboardingRoute.kt not found at ${it.absolutePath}" } }
            .readText()

        assertTrue(
            seedCall in source,
            "onboarding finishes without giving background work a model. A fresh install then has " +
                "backgroundModel = null, and QuestionAuthor, SelfServeResearcher, DaemonWorker, " +
                "IdleTimePreparationEngine and MorningBriefBuilder all return early and silently.",
        )

        // In complete(), not in one of the two entry points. skip() and finish()
        // both delegate there; seeding in only one of them would leave the Skip
        // path unseeded, and Skip is the path a first-time user is most likely
        // to take.
        val complete = source.substringAfter("private fun complete(", "")
        assertTrue(
            seedCall in complete.substringBefore("\n    }"),
            "the seed is in the file but not on the path skip() and finish() share",
        )
    }

}
