package com.aura.capabilities

import com.aura.providers.ProviderKeys
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every [CapabilityProvider] available, from two sources.
 *
 * **Declared** — the Hilt multibinding in [com.aura.capabilities.di.CapabilityModule].
 * Hand-written vendor adapters that encode things no generic client can infer:
 * Kling mints a JWT and polls a task id, WorldLabs polls a bespoke operation id,
 * ElevenLabs puts the voice in the URL path behind an `xi-api-key` header,
 * Stability posts multipart with the model in the path.
 *
 * **Discovered** — [DiscoveredCapabilityProviders], synthesized from the model
 * catalogs of whatever chat providers the user has configured. This is what lets
 * a newly connected token serve images or video without a code change; the
 * declared map can only ever contain vendors somebody wrote an adapter for, and
 * it is generated at compile time.
 *
 * **Declared wins.** A hand-written adapter exists precisely because the generic
 * path was not good enough for that vendor, so a discovered backend must never
 * displace one. It fills gaps.
 *
 * Providers the user has not configured (no API key) are filtered out
 * everywhere — [forKind] returns null when nothing for a kind is configured.
 */
@Singleton
class CapabilityRegistry @Inject constructor(
    private val providers: Map<String, @JvmSuppressWildcards CapabilityProvider>,
    private val providerKeys: ProviderKeys,
    private val discovered: DiscoveredCapabilityProviders? = null,
) {
    /**
     * Declared first, then discovered.
     *
     * The old KDoc claimed iteration order "follows Hilt's multibinding
     * insertion order so providers are tried in declaration order". It does not:
     * Dagger builds the map in binding-processing order, which nothing pins, so
     * with two configured backends for one kind it was unspecified which ran.
     * The declared/discovered split below IS a real guarantee; ordering *within*
     * the declared map still is not, and no caller should depend on it.
     */
    private fun allProviders(): List<CapabilityProvider> =
        providers.values.toList() + discovered?.current().orEmpty()

    fun all(): List<CapabilityProvider> = allProviders()

    fun byPrefix(prefix: String): CapabilityProvider? =
        providers[prefix] ?: discovered?.current()?.firstOrNull { it.prefix == prefix }

    /** The first configured provider for [kind], preferring a declared one. */
    fun forKind(kind: CapabilityKind): CapabilityProvider? =
        allProviders().firstOrNull { it.kind == kind && it.isConfigured() }

    fun configuredForKind(kind: CapabilityKind): List<CapabilityProvider> =
        allProviders().filter { it.kind == kind && it.isConfigured() }
}
