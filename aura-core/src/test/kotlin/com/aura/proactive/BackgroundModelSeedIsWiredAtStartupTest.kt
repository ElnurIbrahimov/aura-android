package com.aura.proactive

import com.aura.agent.sourceDir
import org.junit.Test
import kotlin.test.assertTrue

/**
 * The half of the seed that reaches an install that already exists.
 *
 * `OnboardingViewModel.complete()` only ever runs on a first launch, so it
 * cannot help any install created before the seed existed — which, on the day
 * this was written, is all of them. `ProactiveBootstrap.start()` is the startup
 * path that can, and the invariant is simply that the call is there.
 *
 * Source-scanning because the call is a `scope.launch` on process start: there
 * is no return value to observe and no seam to inject, and what would regress is
 * someone deleting the line, not the line behaving differently.
 * `BackgroundModelSeedIsWiredTest` in :app covers the onboarding half.
 */
class BackgroundModelSeedIsWiredAtStartupTest {

    @Test
    fun `startup seeds the background model`() {
        val source = sourceDir("src/main/kotlin/com/aura")
            .resolve("proactive/ProactiveBootstrap.kt")
            .also { check(it.isFile) { "ProactiveBootstrap.kt not found at ${it.absolutePath}" } }
            .readText()

        assertTrue(
            "seedBackgroundModelOnce()" in source,
            "nothing seeds the background model on an install that finished onboarding before the seed " +
                "existed. Those installs keep backgroundModel = null, and QuestionAuthor, " +
                "SelfServeResearcher, DaemonWorker, IdleTimePreparationEngine and MorningBriefBuilder " +
                "all return early and silently.",
        )
    }
}
