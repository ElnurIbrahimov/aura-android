package com.aura.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `UserPreferences` — 958 lines, 59 preference flows, and no test of any kind.
 *
 * It is referenced by 41 test files and **mocked in 23 of them**, which is the
 * problem: a mocked preference returns whatever the test says it returns, so
 * nothing anywhere asserted what the real defaults are. Six subsystems gate on
 * these values, and `ENGINEERING_HISTORY` records finding "a preference six
 * subsystems gate on that nothing ever set" — a defect invisible to every
 * mock-based test by construction.
 *
 * This runs against a real DataStore under Robolectric.
 *
 * The defaults asserted here are the ones where being wrong costs something
 * real: money, privacy, or data. They are not a style choice, and a change to
 * any of them should be a deliberate edit to this file rather than a silent
 * flip in a 958-line class.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class UserPreferencesDefaultsTest {

    private fun prefs(): UserPreferences =
        UserPreferences(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun `everything that spends money or collects data is off by default`() = runBlocking {
        val p = prefs()

        // Off because it authors patches with an LLM and writes evidence rows on
        // every recall. `EvolutionWorker` never schedules while this is false —
        // and until recently `EvolutionHooks` wrote regardless, which is the
        // shape of bug this default exists to prevent.
        assertFalse("evolutionEnabled", p.evolutionEnabled.first())

        // Off because it is the one background switch that *collects something
        // new* rather than reading what Aura already has. Everything else in the
        // proactive stack works over existing data.
        assertFalse("placeLogEnabled", p.placeLogEnabled.first())

        // Off because a backup needs a folder and a passphrase the user has not
        // chosen yet. `BackupWorker` skips with a stated reason rather than
        // failing, which only reads correctly if the default is off.
        assertFalse("autoBackupEnabled", p.autoBackupEnabled.first())

        // Off because turning it on downloads 137 MB. Every other flag in this
        // method spends money or privacy; this one spends storage and somebody's
        // data plan, and it is the only preference here whose cost is paid
        // before the feature does anything at all. On-by-default would mean an
        // app update silently pulling a nine-figure byte count on next launch.
        assertFalse("smarterMemoryEnabled", p.smarterMemoryEnabled.first())
    }

    @Test
    fun `decay is on by default and is the exception`() = runBlocking {
        // The one background default that is *on*, because FadeMem is the
        // product behaviour rather than an opt-in feature — a memory store that
        // never fades is the thing task salience and the morning brief are
        // written against. Pinned because it is the odd one out and would look
        // like an oversight to anyone normalising these.
        assertTrue("decayEnabled", prefs().decayEnabled.first())
    }

    @Test
    fun `app lock is off by default`() = runBlocking {
        // Off, and that is correct for a single-user sideloaded build: a lock
        // the user did not ask for on an app with no remote account is friction
        // without a threat. What matters is that when it *is* on it covers every
        // door — see AppLockCoversEveryDoorTest.
        assertFalse(prefs().appLockEnabled.first())
    }

    @Test
    fun `a set is readable back through the flow, both ways`() = runBlocking {
        val p = prefs()

        p.setEvolutionEnabled(true)
        // The bug class this catches is the one the v1 cut shipped: the user
        // types a value in Settings, DataStore saves it, and the consumer never
        // sees it because it reads a different key or a baked-in constant.
        assertTrue("set(true) must be visible", p.evolutionEnabled.first())

        // Restored deliberately, not as politeness. The DataStore file is real
        // and outlives the test method, so leaving this true made the defaults
        // test above fail depending on JUnit's method order — it read a value
        // this test had written and reported it as the shipped default.
        // Asserting the way back is the isolation *and* the other half of the
        // round trip.
        p.setEvolutionEnabled(false)
        assertFalse("set(false) must be visible", p.evolutionEnabled.first())
    }

    @Test
    fun `a string preference round-trips exactly`() = runBlocking {
        val p = prefs()
        p.setDefaultModel("ollama:gemma4:31b-cloud")
        assertEquals("ollama:gemma4:31b-cloud", p.defaultModel.first())
    }

    @Test
    fun `an unset nullable preference is null rather than empty`() = runBlocking {
        // `agentId` has no `?:` fallback, unlike its neighbours. Null and "" are
        // different answers — one means "no agent chosen", the other is a scope
        // string that matches nothing — and the distinction is only visible on a
        // real DataStore.
        assertEquals(null, prefs().agentId.first())
    }
}
