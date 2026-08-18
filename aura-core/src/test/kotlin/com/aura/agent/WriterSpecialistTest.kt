package com.aura.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WriterSpecialistTest {
    @Test
    fun `writer specialist exposes creative project tools without a model override`() {
        val writer = Specialist.Writer
        assertEquals("writer", writer.name)
        assertTrue("creative_read_project" in writer.toolsAllowed)
        assertTrue("creative_add_world_item" in writer.toolsAllowed)
        assertEquals(null, writer.suggestedModel)
        assertTrue(writer in Specialist.ALL)
    }

    // The two routing tests that were here went with SpecialistRouter. They
    // asserted which specialist a phrase selected, and nothing selects one from
    // a phrase any more — the user picks an agent.
}