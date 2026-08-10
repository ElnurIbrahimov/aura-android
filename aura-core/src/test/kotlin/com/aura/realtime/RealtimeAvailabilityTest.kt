package com.aura.realtime

import com.aura.providers.ProviderKeys
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What happens when the model the user is chatting with cannot hold a live call.
 *
 * Two of seventeen providers support realtime, so this is the common case, not
 * the edge one — and the honest handling of it is most of the product decision.
 */
class RealtimeAvailabilityTest {

    private fun availability(hasKey: Boolean): RealtimeAvailability {
        val keys: ProviderKeys = mockk {
            coEvery { keyForAwaiting("openai") } returns if (hasKey) "k" else null
            every { isConfigured("openai") } returns hasKey
        }
        return RealtimeAvailability(
            provider = OpenAiRealtimeProvider(keys, OkHttpClient()),
            providerKeys = keys,
        )
    }

    @Test
    fun `a realtime model is ready as-is`() {
        val result = runBlocking { availability(hasKey = true).forChatModel("openai:gpt-realtime") }
        assertTrue(result is RealtimeAvailability.Availability.Ready)
        assertEquals("openai:gpt-realtime", (result as RealtimeAvailability.Availability.Ready).model)
    }

    @Test
    fun `a non-realtime model reports that a call would SWITCH models`() {
        // The product wart, surfaced rather than hidden. A user chatting with
        // Claude who starts a call is silently moved to a different model with
        // a different personality and different memory. Making that invisible
        // is a trust bug, so the type forces the UI to say it.
        val result = runBlocking { availability(hasKey = true).forChatModel("anthropic:claude-sonnet-4.6") }
        assertTrue(result is RealtimeAvailability.Availability.WouldSwitchModel)
        val switch = result as RealtimeAvailability.Availability.WouldSwitchModel
        assertEquals("anthropic:claude-sonnet-4.6", switch.from)
        assertTrue("realtime" in switch.to)
    }

    @Test
    fun `no key means unavailable with a reason the user can act on`() {
        // Disabled-with-a-reason rather than hidden: a disabled affordance
        // teaches that the capability exists and what it needs; a hidden one
        // teaches nothing and the feature is never discovered.
        val result = runBlocking { availability(hasKey = false).forChatModel("openai:gpt-realtime") }
        assertTrue(result is RealtimeAvailability.Availability.Unavailable)
        val reason = (result as RealtimeAvailability.Availability.Unavailable).reason
        assertTrue("OpenAI key" in reason, reason)
        assertTrue("Settings" in reason, "the reason should say where to fix it: $reason")
    }

    @Test
    fun `a bare model name without a prefix still resolves`() {
        val result = runBlocking { availability(hasKey = true).forChatModel("gpt-4o-realtime-preview") }
        assertTrue(result is RealtimeAvailability.Availability.Ready)
    }
}
