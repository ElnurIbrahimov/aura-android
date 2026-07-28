package com.aura.profile

import io.mockk.coEvery
import com.aura.providers.ProviderRegistry
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LlmProfileExtractorTest {

    private val registry = mockk<ProviderRegistry>(relaxed = true)
    private val extractor = LlmProfileExtractor(registry)

    @Test
    fun `extract returns structured facts from valid JSON`() = runBlocking {
        val jsonResponse = """{"name":"Elnur","traits":["Uses Vim","Allergic to peanuts"],"facts":["Wife's name is Sarah"]}"""
        coEvery { registry.chat(any(), any(), any()) } returns flowOf(
            com.aura.providers.ProviderChunk(text = jsonResponse),
        )
        val result = extractor.extract("I use Vim and I'm allergic to peanuts. My wife's name is Sarah.", "test-model")
        assertNotNull(result)
        assertEquals("Elnur", result!!.name)
        assertEquals(2, result.traits.size)
        assertEquals("Uses Vim", result.traits[0])
        assertEquals(1, result.facts.size)
    }

    @Test
    fun `extract handles markdown-fenced JSON`() = runBlocking {
        val jsonResponse = """```json
{"name":"","traits":["Night shift worker"],"facts":[]}
```"""
        coEvery { registry.chat(any(), any(), any()) } returns flowOf(
            com.aura.providers.ProviderChunk(text = jsonResponse),
        )
        val result = extractor.extract("I work night shifts at the hospital", "test-model")
        assertNotNull(result)
        assertEquals(1, result!!.traits.size)
        assertEquals("Night shift worker", result.traits[0])
    }

    @Test
    fun `extract returns null on empty response`() = runBlocking {
        coEvery { registry.chat(any(), any(), any()) } returns flowOf(
            com.aura.providers.ProviderChunk(text = ""),
        )
        val result = extractor.extract("hello world test", "test-model")
        assertNull(result)
    }

    @Test
    fun `extract returns null on unparseable JSON`() = runBlocking {
        coEvery { registry.chat(any(), any(), any()) } returns flowOf(
            com.aura.providers.ProviderChunk(text = "I cannot extract facts from this"),
        )
        val result = extractor.extract("hello", "test-model")
        assertNull(result)
    }

    @Test
    fun `extract returns null on exception`() = runBlocking {
        coEvery { registry.chat(any(), any(), any()) } throws RuntimeException("network error")
        val result = extractor.extract("I use Vim", "test-model")
        assertNull(result)
    }

    @Test
    fun `extract returns null for short messages`() = runBlocking {
        val result = extractor.extract("hi", "test-model")
        assertNull(result)
    }

    @Test
    fun `extract returns null when all fields are empty`() = runBlocking {
        val jsonResponse = """{"name":"","traits":[],"facts":[]}"""
        coEvery { registry.chat(any(), any(), any()) } returns flowOf(
            com.aura.providers.ProviderChunk(text = jsonResponse),
        )
        val result = extractor.extract("tell me a joke", "test-model")
        assertNull(result)
    }
}
