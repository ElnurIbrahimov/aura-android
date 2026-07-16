package com.aura.tools

import com.aura.agent.ToolRegistry
import com.aura.agent.ToolRisk
import com.aura.capabilities.CapabilityRegistry
import com.aura.capabilities.CapabilityRouter
import com.aura.providers.ProviderKeys
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sanity checks for the capability-backed tools' registry wiring and
 * surface contracts. Does not require Hilt; passes a mocked [CapabilityRouter].
 */
class ToolsModuleSanityTest {
    @Test
    fun `legacy and capability tools are both registrable`() {
        val router = CapabilityRouter(
            CapabilityRegistry(emptyMap(), mockk(relaxed = true)),
            mockk(relaxed = true),
        )
        val registry = ToolRegistry()
        registry.register(ImageGenCapabilityTool(router).tool)
        registry.register(WebSearchCapabilityTool(router, okhttp3.OkHttpClient()).tool)
        registry.register(WebSearchTool(okhttp3.OkHttpClient()).tool)

        val names = registry.all().map { it.name }.toSet()
        assertTrue("image_generate" in names)
        assertTrue("web_search_capability" in names)
        assertTrue("web_search" in names)
    }

    @Test
    fun `capability tools report correct risks`() {
        val router = CapabilityRouter(
            CapabilityRegistry(emptyMap(), mockk(relaxed = true)),
            mockk(relaxed = true),
        )
        val imageGen = ImageGenCapabilityTool(router).tool
        val webSearch = WebSearchCapabilityTool(router, okhttp3.OkHttpClient()).tool

        assertEquals(ToolRisk.REMOTE_COST, imageGen.risk)
        assertEquals(ToolRisk.READ_ONLY, webSearch.risk)
    }

    @Test
    fun `capability tools expose parameters`() {
        val router = CapabilityRouter(
            CapabilityRegistry(emptyMap(), mockk(relaxed = true)),
            mockk(relaxed = true),
        )
        val imageGen = ImageGenCapabilityTool(router).tool
        val webSearch = WebSearchCapabilityTool(router, okhttp3.OkHttpClient()).tool

        assertNotNull(imageGen.parameters.properties["prompt"])
        assertNotNull(webSearch.parameters.properties["query"])
    }
}
