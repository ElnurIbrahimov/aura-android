package com.aura.tools

import com.aura.agent.ToolRisk
import com.aura.data.UserPreferences
import com.aura.providers.ProviderKeys
import com.aura.providers.ProviderRegistry
import io.mockk.mockk
import okhttp3.OkHttpClient
import org.junit.Test
import kotlin.test.assertEquals

class RemoteCostToolRiskTest {
    private val httpClient = mockk<OkHttpClient>()
    private val providerKeys = mockk<ProviderKeys>()

    @Test
    fun `metered tools require remote cost approval`() {
        val tools = listOf(
            ImageGenTool(httpClient, providerKeys, io.mockk.mockk<com.aura.data.UserPreferences>(relaxed = true).also { io.mockk.every { it.imageModel } returns kotlinx.coroutines.flow.flowOf("dall-e-3") }).tool,
            TranscriptionTool(httpClient, providerKeys).tool,
            BraveSearchTool(httpClient, providerKeys).tool,
            TavilySearchTool(httpClient, providerKeys).tool,
            FirecrawlFetchTool(httpClient, providerKeys).tool,
            DeepResearchTool(
                httpClient,
                providerKeys,
                mockk<ProviderRegistry>(),
                mockk<UserPreferences>(),
            ).tool,
        )

        assertEquals(
            setOf("image_gen", "transcribe", "brave_search", "tavily_search", "fetch_url", "deep_research"),
            tools.map { it.name }.toSet(),
        )
        // image_gen, transcribe, fetch_url, deep_research are REMOTE_COST (paid API per call)
        // brave_search and tavily_search are READ_ONLY (intentionally changed — the user
        // configured an API key, search is a basic expectation, not a high-cost operation)
        assertEquals(ToolRisk.REMOTE_COST, tools.first { it.name == "image_gen" }.risk)
        assertEquals(ToolRisk.REMOTE_COST, tools.first { it.name == "transcribe" }.risk)
        assertEquals(ToolRisk.REMOTE_COST, tools.first { it.name == "fetch_url" }.risk)
        assertEquals(ToolRisk.REMOTE_COST, tools.first { it.name == "deep_research" }.risk)
        assertEquals(ToolRisk.READ_ONLY, tools.first { it.name == "brave_search" }.risk)
        assertEquals(ToolRisk.READ_ONLY, tools.first { it.name == "tavily_search" }.risk)
    }
}