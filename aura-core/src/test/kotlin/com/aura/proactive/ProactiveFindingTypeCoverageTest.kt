package com.aura.proactive

import com.aura.agent.requireNonEmpty
import com.aura.agent.sourceDir
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * That a proactive check cannot invent a finding type nothing recognises.
 *
 * [SalienceFilter] suppresses a repeated finding by matching its type against
 * the types already recorded, and that match runs through
 * [ProactiveFindingType]. A ninth check added to [ProactiveAwarenessEngine]
 * with an unregistered type string would compile, run, and be silently
 * un-suppressable forever — the same class of failure the two namespaces
 * already produced once.
 *
 * Source-scanning rather than reflective because the type strings are literals
 * at the construction sites; there is no runtime registry to enumerate.
 */
class ProactiveFindingTypeCoverageTest {

    @Test
    fun `every finding type emitted by the awareness engine is registered`() {
        val source = sourceDir("src/main/kotlin/com/aura")
            .resolve("proactive/ProactiveAwarenessEngine.kt")
            .readText()

        val emitted = Regex("type\\s*=\\s*\"([a-z_]+)\"")
            .findAll(source)
            .map { it.groupValues[1] }
            .distinct()
            .toList()
            .requireNonEmpty("finding type literals in ProactiveAwarenessEngine.kt")

        val registered = ProactiveFindingType.entries.map { it.wire }.toSet()
        val unregistered = emitted.filterNot { it in registered }

        assertTrue(
            unregistered.isEmpty(),
            "ProactiveAwarenessEngine emits finding type(s) with no ProactiveFindingType entry: " +
                unregistered.joinToString(", ") +
                "\nAdd them to ProactiveFindingType, or SalienceFilter can never recognise a repeat of them.",
        )
    }
}
