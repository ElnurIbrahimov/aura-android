package com.aura.memory

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WriteGateTest {

    @Test
    fun `person category for family references`() {
        val gate = WriteGate()
        val d = gate.evaluate("Call mom tomorrow at 5", "user")
        assertEquals("person", d.category)
    }

    @Test
    fun `task category for reminders`() {
        val gate = WriteGate()
        val d = gate.evaluate("remind me to take the trash out at 8pm", "user")
        assertEquals("task", d.category)
    }

    @Test
    fun `idea category for brainstorming`() {
        val gate = WriteGate()
        val d = gate.evaluate("what if we added dark mode to the app", "user")
        assertEquals("idea", d.category)
    }

    // ---- what this gate is for ------------------------------------------

    private val gate = WriteGate()

    /**
     * The four memories found on a real install, which is what prompted all of
     * this. Every one of them passed the old gate, because it returned
     * shouldStore = true for anything four characters or longer.
     */
    @Test
    fun `the greetings that actually got stored are rejected`() {
        listOf("Hey you", "Hello", "Hey how are you", "Heyara").forEach {
            assertFalse(gate.evaluate(it, "user").shouldStore, "\"$it\" was stored as a memory")
        }
    }

    @Test
    fun `a pleasantry is matched whole, not by contains`() {
        // "thanks" inside a sentence is politeness attached to a fact, and the
        // fact is the part worth keeping.
        assertFalse(gate.evaluate("thanks!", "user").shouldStore)
        assertTrue(gate.evaluate("thanks — my flight is on Thursday", "user").shouldStore)
    }

    @Test
    fun `punctuation and emoji do not smuggle a greeting past`() {
        listOf("hey!", "hey 😊", "Hey, Aura!", "hi there", "good morning").forEach {
            assertFalse(gate.evaluate(it, "user").shouldStore, "\"$it\" was stored as a memory")
        }
    }

    @Test
    fun `a question is not a memory`() {
        listOf(
            "what's the weather today?",
            "can you search for the latest ARC results",
            "summarise this document for me",
        ).forEach {
            assertFalse(gate.evaluate(it, "user").shouldStore, "\"$it\" was stored as a memory")
        }
    }

    @Test
    fun `things worth recalling weeks later still get through`() {
        listOf(
            "I prefer terse answers with no preamble",
            "my colleague Rəşad handles the deployment side",
            "remind me to renew the domain before June",
            "I'm building an ARC-AGI-2 solver on a 7B model",
            "remember that the staging box has no GPU",
        ).forEach {
            assertTrue(gate.evaluate(it, "user").shouldStore, "\"$it\" was dropped")
        }
    }

    // ---- the hard/soft distinction --------------------------------------

    /**
     * The reason a weak rejection is not in [WriteGate.HARD_REJECT].
     *
     * "The ARC deadline moved to April" carries no first-person marker and no
     * keyword this class knows, so the heuristic cannot justify storing it — but
     * it is worth remembering, and a model would say so. Putting
     * `nothing_durable` in the hard set would stop it ever being asked.
     */
    @Test
    fun `a weak rejection stays open to a second opinion`() {
        // No first-person marker and no keyword this class knows — but plainly
        // worth remembering, and a model would say so.
        val d = gate.evaluate("The GPU budget was cut to 40k", "user")

        assertFalse(d.shouldStore)
        assertEquals(WriteGate.REASON_NOTHING_DURABLE, d.reason)
        assertFalse(d.reason in WriteGate.HARD_REJECT, "a model will never be asked about this")
    }

    @Test
    fun `obvious noise is settled here and costs no model call`() {
        listOf("hello", "okay", "thanks", "good morning").forEach {
            val d = gate.evaluate(it, "user")
            assertEquals(WriteGate.REASON_PLEASANTRY, d.reason, "for \"$it\"")
            assertTrue(d.reason in WriteGate.HARD_REJECT, "\"$it\" would cost a model call")
        }
        // Shorter greetings are caught by the length floor first. Different
        // reason, same hard rejection — which is all the caller acts on.
        assertEquals(WriteGate.REASON_TOO_SHORT, gate.evaluate("hey", "user").reason)
        assertTrue(gate.evaluate("hey", "user").reason in WriteGate.HARD_REJECT)
        assertTrue(gate.evaluate("", "user").reason in WriteGate.HARD_REJECT)
        assertTrue(gate.evaluate("anything", "system").reason in WriteGate.HARD_REJECT)
    }

    /**
     * A marker has to start a word.
     *
     * "im " appears inside "trim ", "aim " and "claim ", so a plain `contains`
     * filed "the aim is to cut latency" as a first-person claim about the user.
     */
    @Test
    fun `a marker buried inside another word does not match`() {
        assertFalse(gate.evaluate("the aim is to cut latency in half", "user").shouldStore)
        assertFalse(gate.evaluate("trim the whitespace before hashing", "user").shouldStore)
        // The real thing still does.
        assertTrue(gate.evaluate("I'm cutting latency in half", "user").shouldStore)
    }
}
