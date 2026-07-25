package com.aura.taste

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TasteEngineContextTest {

    private val signalDao = mockk<PreferenceSignalDao>(relaxed = true)
    private val profileDao = mockk<StyleProfileDao>(relaxed = true)
    private val routingDao = mockk<RoutingOutcomeDao>(relaxed = true)
    private val engine = TasteEngine(signalDao, profileDao, routingDao)

    @Test
    fun `getTasteContext renders key value buckets as key value`() = runTest {
        coEvery { signalDao.global(500) } returns listOf(
            PreferenceSignalEntity(
                id = "s1",
                projectId = "",
                signalType = "rating",
                category = "writing",
                attributesJson = """{"tone":"concise","style":"formal"}""",
                weight = 1.0f,
            ),
        )
        coEvery { profileDao.global() } returns null
        coEvery { profileDao.forScopes(any()) } returns null

        engine.recomputeProfile("")

        val slot = io.mockk.slot<StyleProfileEntity>()
        coVerify { profileDao.upsert(capture(slot)) }
        // The profile DAO in this test is relaxed, so the upsert above
        // returns Unit; but the captured entity is what we expect.
        coEvery { profileDao.global() } returns slot.captured
        coEvery { profileDao.forScopes(any()) } returns slot.captured

        val ctx = engine.getTasteContext()
        assertTrue(ctx.contains("tone: concise"))
        assertTrue(ctx.contains("style: formal"))
        assertTrue(ctx.contains("writing: prefers"))
    }

    @Test
    fun `negative only signals do not flip sign`() = runTest {
        coEvery { signalDao.global(500) } returns List(3) {
            PreferenceSignalEntity(
                id = "s$it",
                projectId = "",
                signalType = "edit",
                category = "writing",
                attributesJson = """{"tone":"verbose"}""",
                weight = -0.5f,
            )
        }
        coEvery { profileDao.global() } returns null

        engine.recomputeProfile("")

        val slot = io.mockk.slot<StyleProfileEntity>()
        coVerify { profileDao.upsert(capture(slot)) }
        val attrs = Json.decodeFromString<Map<String, Map<String, Float>>>(slot.captured.attributesJson)
        val writing = attrs["writing"] ?: emptyMap()
        assertTrue("Expected negative weight but got ${writing["tone:verbose"]}", (writing["tone:verbose"] ?: 0f) < 0f)
    }
}
