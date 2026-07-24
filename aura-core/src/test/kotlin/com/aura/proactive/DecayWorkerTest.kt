package com.aura.proactive

import android.content.Context
import androidx.work.WorkerParameters
import com.aura.data.UserPreferences
import com.aura.memory.MemoryStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals

class DecayWorkerTest {

    @Test
    fun `doWork calls runDecayPass and returns success`() = runBlocking {
        val mockContext = mockk<Context>(relaxed = true)
        val mockParams = mockk<WorkerParameters>(relaxed = true)
        val mockMemoryStore = mockk<MemoryStore>(relaxed = true)
        val mockPrefs = mockk<UserPreferences>(relaxed = true)
        every { mockPrefs.decayEnabled } returns flowOf(true)

        val worker = DecayWorker(mockContext, mockParams, mockMemoryStore, mockPrefs)

        val result = worker.doWork()

        coVerify(exactly = 1) { mockMemoryStore.runDecayPass() }
        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
    }

    @Test
    fun `doWork skips decay when disabled`() = runBlocking {
        val mockContext = mockk<Context>(relaxed = true)
        val mockParams = mockk<WorkerParameters>(relaxed = true)
        val mockMemoryStore = mockk<MemoryStore>(relaxed = true)
        val mockPrefs = mockk<UserPreferences>(relaxed = true)
        every { mockPrefs.decayEnabled } returns flowOf(false)

        val worker = DecayWorker(mockContext, mockParams, mockMemoryStore, mockPrefs)

        val result = worker.doWork()

        coVerify(exactly = 0) { mockMemoryStore.runDecayPass() }
        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
    }
}
