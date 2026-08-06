package com.aura.evolution

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import io.mockk.mockk
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class EvolutionCoordinatorTest {
    private lateinit var db: EvolutionDatabase
    private lateinit var coordinator: EvolutionCoordinator

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, EvolutionDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val detectors = EvolutionCandidateDetectors(db.evidenceDao(), db.candidateDao())
        val metrics = EvolutionMetricsRecorder(db.settingsDao())
        val patchAuthor = mockk<EvolutionPatchAuthor>(relaxed = true)
        val proposalStore = mockk<EvolutionProposalStore>(relaxed = true)
        val candidateDao = db.candidateDao()
        val settingsDao = db.settingsDao()
        coordinator = EvolutionCoordinator(
            detectors, metrics, patchAuthor, proposalStore, candidateDao, settingsDao, EvolutionSafetyGuard(),
        )
    }

    @After
    fun teardown() { db.close() }

    @Test
    fun `runAll returns zero candidates when no evidence`() = runBlocking {
        val result = coordinator.runAll()
        assertEquals(0, result.candidateCount)
        assertTrue(result.durationMs >= 0)
    }
}
