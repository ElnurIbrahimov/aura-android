package com.aura.architecture

import com.aura.capabilities.CapabilityKind
import com.aura.capabilities.CapabilityProvider
import com.aura.capabilities.CapabilityRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural invariants of the capability layer.
 *
 * Two tests were removed from this class because they asserted nothing:
 *
 * - `tool_names_are_unique_when_registered` carried the comment *"Register two
 *   tools with the same name — second should overwrite"* and registered **one**,
 *   then asserted the registry held one. It would have passed against a registry
 *   with no de-duplication at all, which is the only thing it claimed to check.
 * - `every_tool_has_a_known_risk_level` mapped `ToolRisk.entries` to their own
 *   names and asserted those names were present. No `Tool` was ever constructed.
 *   `ToolRiskOrdinalAuditTest` already pins the real invariant — the declaration
 *   *order*, which four `>=` comparisons depend on — and says of this one that
 *   it "passes under every permutation".
 *
 * A private `registry()` helper went with them, whose own comment conceded it
 * used "an empty registry to verify the assertions compile and pass trivially".
 * Nothing called it.
 *
 * What remains constructs real objects and can fail.
 */
class BeyondSotaBaselineTest {

    @Test
    fun capability_kinds_are_comprehensive() {
        val expectedKinds = setOf(
            "TextToSpeech",
            "ImageGeneration",
            "VideoGeneration",
            "World3DGeneration",
            "WebSearch",
            "Transcription",
        )
        val actualKinds = CapabilityKind.entries.map { it.name }.toSet()
        for (expected in expectedKinds) {
            assertTrue(
                "CapabilityKind.$expected is missing from the enum. Actual: $actualKinds",
                expected in actualKinds,
            )
        }
    }

    @Test
    fun capability_registry_returns_null_for_unconfigured_kind() {
        // A registry with no providers should return null for any kind
        val emptyRegistry = CapabilityRegistry(
            providers = emptyMap(),
            providerKeys = mockk(relaxed = true),
        )
        for (kind in CapabilityKind.entries) {
            assertNull(
                "forKind($kind) should return null when no providers are configured",
                emptyRegistry.forKind(kind),
            )
        }
    }

    @Test
    fun capability_registry_filters_unconfigured_providers() {
        // A provider that returns false from isConfigured() should not be returned
        val unconfiguredProvider = mockk<CapabilityProvider>()
        every { unconfiguredProvider.kind } returns CapabilityKind.WebSearch
        every { unconfiguredProvider.isConfigured() } returns false
        every { unconfiguredProvider.prefix } returns "test"
        every { unconfiguredProvider.displayName } returns "Test"

        val registry = CapabilityRegistry(
            providers = mapOf("test" to unconfiguredProvider),
            providerKeys = mockk(relaxed = true),
        )
        assertNull(
            "forKind should return null when the only provider is not configured",
            registry.forKind(CapabilityKind.WebSearch),
        )
    }
}
