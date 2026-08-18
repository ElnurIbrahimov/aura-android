package com.aura.evolution

import java.util.UUID
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Append-only evidence recorder. Callers pass lightweight summaries;
 * the recorder never mutates the source artifact. All evidence is
 * redacted — no full secrets, no user PII beyond what the source
 * artifact already stores under its own security model.
 */
@Singleton
class EvolutionEvidenceRecorder @Inject constructor(
    private val dao: EvolutionEvidenceDao,
) {
    suspend fun record(
        domain: EvolutionDomain,
        kind: kotlin.String,
        sourceEntityId: kotlin.String,
        runId: kotlin.String? = null,
        conversationId: kotlin.String? = null,
        turnTimestamp: kotlin.Long? = null,
        summary: kotlin.String = "",
        payload: Map<kotlin.String, kotlin.String> = emptyMap(),
        beforeCiphertext: kotlin.String? = null,
        afterCiphertext: kotlin.String? = null,
    ) {
        dao.upsert(
            EvolutionEvidenceEntity(
                id = UUID.randomUUID().toString(),
                domain = domain.name,
                kind = kind,
                sourceEntityId = sourceEntityId,
                runId = runId,
                conversationId = conversationId,
                turnTimestamp = turnTimestamp,
                summary = summary,
                payloadJson = Json.encodeToString(MapSerializer(String.serializer(), String.serializer()), payload),
                beforeCiphertext = beforeCiphertext,
                afterCiphertext = afterCiphertext,
            ),
        )
    }

    /**
     * Drop evidence past the retention window.
     *
     * `EvolutionEvidenceDao.deleteOlderThan` shipped with the table and had no
     * production caller, so `evolution_evidence` — five indices per row, written
     * once per stored memory and once per *recalled* memory — grew without any
     * bound at all. ENGINEERING_HISTORY records "a retention window with no
     * caller" three times in three days as a recurring finding; this is the
     * fourth instance and the same fix.
     *
     * Called from `DecayWorker` above its `decayEnabled` gate, beside the
     * worker-run, place and retrieval-label prunes: retention is not a feature
     * the user opted into, and must run whether or not decay is switched on.
     *
     * A constant rather than `EvolutionSettingsEntity.evidenceRetentionDays`,
     * which is still unread: that field is per-domain and this sweep is global,
     * so wiring it here would make it look live while answering a different
     * question. It stays in the dead-field register until something needs
     * per-domain retention.
     */
    suspend fun prune(now: kotlin.Long = System.currentTimeMillis()): Int =
        runCatching { dao.deleteOlderThan(now - RETENTION_MS) }
            .onFailure { android.util.Log.w(TAG, "evidence prune failed: ${it.message}", it) }
            .getOrDefault(0)

    companion object {
        private const val TAG = "EvolutionEvidence"

        /** Thirty days, matching the worker-run and retrieval-label windows. */
        const val RETENTION_MS: kotlin.Long = 30L * 24 * 60 * 60 * 1000
    }
}
