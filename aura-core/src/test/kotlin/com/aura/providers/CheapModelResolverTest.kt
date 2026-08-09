package com.aura.providers

import com.aura.data.UserPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CheapModelResolverTest {

    private val userPreferences = mockk<UserPreferences>(relaxed = true)
    private val providerRegistry = mockk<ProviderRegistry>()

    private fun provider(prefix: String, models: List<String>): Provider =
        mockk<Provider>(relaxed = true).also {
            every { it.prefix } returns prefix
            coEvery { it.listModels() } returns models
            coEvery { it.listModelsWithContext() } returns models.map { m -> ModelInfo(m) }
        }

    private fun resolver(
        fastRole: String? = null,
        providers: List<Provider> = emptyList(),
    ): CheapModelResolver {
        every { userPreferences.forRole(ModelRole.FAST) } returns flowOf(fastRole)
        every { userPreferences.defaultModel } returns flowOf("anthropic:claude-opus-4")
        coEvery { providerRegistry.configured() } returns providers
        coEvery { providerRegistry.all() } returns providers
        return CheapModelResolver(
            modelRoleRouter = ModelRoleRouter(userPreferences, providerRegistry),
            providerRegistry = providerRegistry,
            modelContextCache = null,
        )
    }

    @Test
    fun `an explicit Fast model wins outright`() = runTest {
        val r = resolver(fastRole = "groq:llama-3-8b", providers = listOf(provider("openai", listOf("gpt-4o-mini"))))
        assertEquals("groq:llama-3-8b", r.resolve())
    }

    /**
     * The single most important behaviour here. `ModelRoleRouter.resolve` falls
     * back to the conversation default, so resolving the Fast role through it
     * would run every 50-token rerank on the user's flagship — precisely the
     * waste `CheapModelHeuristic` exists to prevent. With no Fast model set, the
     * heuristic must decide, not the chat setting.
     */
    @Test
    fun `with no Fast model set it does not fall back to the conversation default`() = runTest {
        val r = resolver(fastRole = null, providers = listOf(provider("openai", listOf("gpt-4o", "gpt-4o-mini"))))
        val picked = r.resolve()
        assertEquals("openai:gpt-4o-mini", picked)
        assert(picked != "anthropic:claude-opus-4") { "must not inherit the conversation default" }
    }

    /**
     * The ranking bug in one assertion: `gpt-4o` is shorter than `gpt-4o-mini`,
     * so by-name-length picks the expensive model.
     */
    @Test
    fun `it ranks by cheapness, not by name length`() = runTest {
        val r = resolver(providers = listOf(provider("openai", listOf("gpt-4o", "gpt-4o-mini"))))
        assertEquals("openai:gpt-4o-mini", r.resolve())
    }

    @Test
    fun `it searches across every configured provider`() = runTest {
        val r = resolver(
            providers = listOf(
                provider("openai", listOf("gpt-4o")),
                provider("groq", listOf("llama-3-8b-instant")),
            ),
        )
        assertEquals("groq:llama-3-8b-instant", r.resolve())
    }

    /** MoA is a virtual provider that fans out to three models — never a cheap choice. */
    @Test
    fun `the MoA virtual provider is never chosen`() = runTest {
        val r = resolver(providers = listOf(provider("moa", listOf("council")), provider("openai", listOf("gpt-4o"))))
        assertEquals("openai:gpt-4o", r.resolve())
    }

    @Test
    fun `the excluded model is not returned`() = runTest {
        val r = resolver(providers = listOf(provider("openai", listOf("gpt-4o-mini"))))
        assertEquals("fallback:model", r.resolve(fallback = "fallback:model", exclude = "openai:gpt-4o-mini"))
    }

    @Test
    fun `the fallback is returned when nothing is configured`() = runTest {
        val r = resolver(providers = emptyList())
        assertEquals("caller:model", r.resolve(fallback = "caller:model"))
    }

    /**
     * Null rather than a bare model name or the literal "default" — both of
     * which `ProviderRegistry.parse` rejects, and both of which callers were
     * previously producing and passing straight into a model call.
     */
    @Test
    fun `nothing configured and no fallback yields null`() = runTest {
        val r = resolver(providers = emptyList())
        assertNull(r.resolve())
    }

    /** Every returned id must be fully qualified, or `ProviderRegistry.parse` throws. */
    @Test
    fun `the resolved id is always provider-qualified`() = runTest {
        val r = resolver(providers = listOf(provider("openai", listOf("gpt-4o-mini"))))
        val picked = r.resolve()!!
        val parts = picked.split(":", limit = 2)
        assertEquals(2, parts.size, "'$picked' is not provider:model")
        assert(parts[0].isNotBlank() && parts[1].isNotBlank()) { "'$picked' has a blank half" }
    }

    @Test
    fun `a failing catalog falls back rather than throwing`() = runTest {
        val broken = mockk<Provider>(relaxed = true).also {
            every { it.prefix } returns "broken"
            coEvery { it.listModels() } throws java.io.IOException("down")
            coEvery { it.listModelsWithContext() } throws java.io.IOException("down")
        }
        val r = resolver(providers = listOf(broken))
        assertEquals("caller:model", r.resolve(fallback = "caller:model"))
    }
}
