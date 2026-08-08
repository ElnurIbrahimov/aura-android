package com.aura.capabilities

/**
 * Text-to-3D / world generation.
 *
 * The one [CapabilityKind] that had no interface. `WorldLabs3DProvider`
 * implemented [CapabilityProvider] directly and exposed a bespoke
 * `generateWorld`, so `world_3d_generate` had to downcast to the concrete
 * class:
 *
 *     capabilityRouter.resolve(World3DGeneration) as? WorldLabs3DProvider
 *
 * which silently rejected any other backend — including a correctly registered
 * one — and reported it as "No 3D world provider configured. Add a WorldLabs
 * API key." A second 3D vendor could have been bound in Hilt and still done
 * nothing.
 *
 * Declared like the other kinds so the tool can resolve by capability rather
 * than by class. Nothing generates 3D from a chat catalog today (a world model
 * is a service, not a `/v1/models` entry), but that is a reason for the
 * interface to exist, not to be absent.
 */
interface World3DProvider : CapabilityProvider {
    override val kind: CapabilityKind get() = CapabilityKind.World3DGeneration

    /** Generate a world/scene from [prompt]. */
    suspend fun generateWorld(prompt: String): WorldResult
}

/**
 * [worldUrl] is set when the provider returned a finished asset;
 * [operationId] identifies the job when it is still rendering.
 */
data class WorldResult(val worldUrl: String?, val operationId: String)
