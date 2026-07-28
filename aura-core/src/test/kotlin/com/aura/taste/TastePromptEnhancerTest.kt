package com.aura.taste

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TastePromptEnhancerTest {

    private val enhancer = TastePromptEnhancer()

    @Test
    fun `empty taste context returns prompt unchanged`() {
        val result = enhancer.enhance("You are Aura.", "")
        assertEquals("You are Aura.", result)
    }

    @Test
    fun `tone preference converts to explicit instruction`() {
        val taste = "\n\n# User taste preferences (learned from signals):\n- general: prefers tone: concise"
        val result = enhancer.enhance("You are Aura.", taste)
        assertTrue(result.contains("Be concise."))
    }

    @Test
    fun `style preference converts to explicit instruction`() {
        val taste = "- general: prefers style: direct"
        val result = enhancer.enhance("You are Aura.", taste)
        assertTrue(result.contains("Use direct style."))
    }

    @Test
    fun `multiple preferences generate multiple instructions`() {
        val taste = "- general: prefers tone: concise, style: direct, length: short"
        val result = enhancer.enhance("You are Aura.", taste)
        assertTrue(result.contains("Be concise."))
        assertTrue(result.contains("Use direct style."))
        assertTrue(result.contains("Keep responses short."))
    }

    @Test
    fun `unknown preference key generates generic instruction`() {
        val taste = "- general: prefers humor: dry"
        val result = enhancer.enhance("You are Aura.", taste)
        assertTrue(result.contains("Prefer humor: dry."))
    }

    @Test
    fun `no preferences in context returns prompt unchanged`() {
        val taste = "Some random text without preferences"
        val result = enhancer.enhance("You are Aura.", taste)
        assertEquals("You are Aura.", result)
    }
}
