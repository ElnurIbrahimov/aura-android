package com.aura.taste

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.assertTrue

/**
 * Regression test for the TasteEngine aggregation bug where signals
 * were bucketed by attribute VALUE instead of KEY. Two signals with
 * different keys but the same value ("response_length":"concise" and
 * "verbosity":"concise") would collapse into one bucket "concise",
 * losing the dimension. After the fix, buckets are "key:value" pairs.
 */
class TasteEngineAggregationTest {

    @Test
    fun `signals with same value but different keys produce distinct buckets`() = runTest {
        val signalDao = mockk<PreferenceSignalDao>(relaxed = true)
        val styleProfileDao = mockk<StyleProfileDao>(relaxed = true)
        val routingDao = mockk<RoutingOutcomeDao>(relaxed = true)

        val signals = listOf(
            PreferenceSignalEntity(
                id = "1",
                signalType = "accept",
                category = "style",
                attributesJson = """{"response_length":"concise"}""",
                weight = 1.0f,
                createdAt = 1000L,
            ),
            PreferenceSignalEntity(
                id = "2",
                signalType = "accept",
                category = "style",
                attributesJson = """{"verbosity":"concise"}""",
                weight = 1.0f,
                createdAt = 2000L,
            ),
        )
        coEvery { signalDao.global(any()) } returns signals

        // After recomputeProfile calls upsert, capture the stored profile
        // and return it from global() so getTasteContext can read it.
        val profileSlot = kotlinx.coroutines.channels.Channel<StyleProfileEntity>(kotlinx.coroutines.channels.Channel.CONFLATED)
        coEvery { styleProfileDao.upsert(any()) } answers { profileSlot.trySend(firstArg()); }
        coEvery { styleProfileDao.global() } answers { profileSlot.tryReceive().getOrNull() }
        coEvery { styleProfileDao.forScopes(any()) } returns null

        val engine = TasteEngine(signalDao, styleProfileDao, routingDao)
        engine.recomputeProfile()

        val profile = engine.getTasteContext()
        assertTrue(
            "Profile should contain response_length: concise, got: $profile",
            profile.contains("response_length: concise"),
        )
        assertTrue(
            "Profile should contain verbosity: concise, got: $profile",
            profile.contains("verbosity: concise"),
        )
    }
}