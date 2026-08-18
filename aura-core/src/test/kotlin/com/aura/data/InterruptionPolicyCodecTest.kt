package com.aura.data

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The round trip the shipped writer never had.
 *
 * The inline encoder rendered a literal `${'$'}{it.key}` — the Kotlin template
 * escape leaked into the output — so the stored string was one junk entry and
 * every explicit interruption choice fell back to EARNED on the next read.
 * This is the test that would have caught it: encode, decode, get back what
 * you put in.
 */
class InterruptionPolicyCodecTest {

    @Test
    fun `what is encoded is what decodes`() {
        val policies = mapOf("living_world" to "ALWAYS", "stuck_tasks" to "NEVER")
        val decoded = UserPreferences.decodePolicyMap(UserPreferences.encodePolicyMap(policies))
        assertEquals(policies, decoded)
    }

    @Test
    fun `the encoded form carries real keys, not template text`() {
        val encoded = UserPreferences.encodePolicyMap(mapOf("open_question" to "ALWAYS"))
        assertEquals("open_question=ALWAYS", encoded)
        assertTrue(!encoded.contains("{it."), "the template leak is back")
    }

    @Test
    fun `malformed entries are dropped, not guessed at`() {
        val decoded = UserPreferences.decodePolicyMap("good=NEVER,=orphanvalue,barekey,too=many=parts")
        assertEquals(mapOf("good" to "NEVER"), decoded)
    }
}
