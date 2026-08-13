package com.aura.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The single highest-leverage preference in the app, and the one whose absence
 * was completely silent.
 *
 * `backgroundModel` had no default and only two writers — Settings and a
 * restored backup — so an install that never opened Settings → AI & Models kept
 * it null forever. Five subsystems hard-return on exactly that
 * (`QuestionAuthor`, `SelfServeResearcher`, `DaemonWorker`,
 * `IdleTimePreparationEngine`, `MorningBriefBuilder`), each quietly, each
 * looking identical to a feature that had nothing to say. `BackgroundHealth`
 * ships a switch list naming this one first, which made it legible without
 * making it work.
 *
 * What is pinned is the *once*: seeding on every launch would undo a deliberate
 * clear, and never seeding leaves the installs that need it exactly where they
 * were.
 *
 * **One test method on purpose.** `Context.auraPrefs` is an AndroidX
 * `preferencesDataStore` delegate, which caches its instance per name for the
 * life of the process — the same property [UserPreferences]' own KDoc records as
 * having hidden a real bug once. Robolectric hands each method a fresh temp
 * directory but not a fresh DataStore, so writes leak between methods and split
 * cases fail on whatever ran before them. The invariant here is a sequence
 * anyway; asserting it as one is honest rather than a workaround.
 */
@RunWith(RobolectricTestRunner::class)
class BackgroundModelSeedTest {

    @Test
    fun `the background model is seeded once, and only when nothing else has spoken`() = runBlocking {
        val prefs = UserPreferences(ApplicationProvider.getApplicationContext<Context>())

        // 1. Onboarding's Skip path: the seed runs before any model is chosen.
        //    It must not burn the one chance — the startup backfill still has to
        //    be able to do the work on a later launch.
        assertNull(prefs.seedBackgroundModelOnce(), "it claimed to seed with no chat model to seed from")
        assertNull(prefs.backgroundModel.first())

        // 2. A model gets chosen. The next seed takes it.
        prefs.setDefaultModel("groq:llama-3.3-70b")
        assertEquals(
            "groq:llama-3.3-70b",
            prefs.seedBackgroundModelOnce(),
            "onboarding finished with a chat model and background work still had none",
        )
        assertEquals("groq:llama-3.3-70b", prefs.backgroundModel.first())

        // 3. Every launch after that is a no-op. This is the startup backfill
        //    running on an install that has already been seeded.
        assertNull(prefs.seedBackgroundModelOnce())
        assertNull(prefs.seedBackgroundModelOnce())
        assertEquals("groq:llama-3.3-70b", prefs.backgroundModel.first())

        // 4. Clearing it is something a person can mean, and it has to stick.
        //    This is why the flag exists rather than inferring "not seeded yet"
        //    from the field being empty.
        prefs.setBackgroundModel(null)
        assertNull(prefs.seedBackgroundModelOnce())
        assertNull(prefs.backgroundModel.first(), "a deliberate clear was undone on the next launch")

        // 5. And a value the user picked themselves is never replaced. Re-seeding
        //    from scratch to prove the branch: an already-set background model
        //    ends the seed without a write, whatever the chat model is.
        prefs.setBackgroundModel("ollama:qwen3:8b")
        prefs.setDefaultModel("anthropic:claude-opus-5")
        assertNull(prefs.seedBackgroundModelOnce())
        assertEquals("ollama:qwen3:8b", prefs.backgroundModel.first())
    }
}
