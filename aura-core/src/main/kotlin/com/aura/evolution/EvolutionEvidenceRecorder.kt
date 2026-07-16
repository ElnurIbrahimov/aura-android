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
}
