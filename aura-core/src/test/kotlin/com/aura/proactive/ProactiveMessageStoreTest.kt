package com.aura.proactive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProactiveMessageStoreTest {

    private lateinit var store: ProactiveMessageStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        store = ProactiveMessageStore(context)
    }

    @After
    fun tearDown() = runTest {
        store.clear()
    }

    @Test
    fun `setMessage then consume returns the message`() = runTest {
        store.setMessage("Thinking about you!")
        val msg = store.consumeMessage()
        assertEquals("Thinking about you!", msg)
    }

    @Test
    fun `consume clears the message after reading`() = runTest {
        store.setMessage("Hello there")
        store.consumeMessage()
        val second = store.consumeMessage()
        assertNull(second)
    }

    @Test
    fun `consume returns null when no message set`() = runTest {
        val msg = store.consumeMessage()
        assertNull(msg)
    }

    @Test
    fun `setMessage overwrites previous message`() = runTest {
        store.setMessage("First")
        store.setMessage("Second")
        val msg = store.consumeMessage()
        assertEquals("Second", msg)
    }

    @Test
    fun `clear removes any stored message`() = runTest {
        store.setMessage("To be cleared")
        store.clear()
        val msg = store.consumeMessage()
        assertNull(msg)
    }
}