package com.aura.backup

import com.aura.evolution.EvolutionDomain
import com.aura.evolution.EvolutionProposalEntity
import com.aura.evolution.EvolutionRevisionEntity
import com.aura.evolution.EvolutionSettingsEntity
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class AuraBackupSchema12Test {
    @Test
    fun `schema version is 29`() {
        assertEquals(29, AuraBackup.SCHEMA_VERSION)
    }

    @Test
    fun `roundtrip preserves evolution tables`() {
        val original = AuraBackup(
            exportedAt = 1L,
            appVersionName = "test",
            evolutionProposals = listOf(
                EvolutionProposalBackup(
                    id = "p1",
                    domain = EvolutionDomain.SKILL.name,
                    action = "create",
                    targetId = "skill-1",
                    status = "PENDING_REVIEW",
                    createdAt = 2L,
                    updatedAt = 3L,
                ),
            ),
            evolutionSettings = listOf(EvolutionSettingsBackup(EvolutionDomain.MEMORY.name, true, 4L)),
            evolutionRevisions = listOf(
                EvolutionRevisionBackup(
                    id = "r1",
                    domain = EvolutionDomain.SKILL.name,
                    targetId = "skill-1",
                    snapshotCiphertext = "cipher",
                    createdAt = 5L,
                ),
            ),
            preferences = PreferencesBackup(evolutionEnabled = true, evolutionIntervalHours = 12),
        )
        val json = Json { encodeDefaults = true }
        val text = json.encodeToString(AuraBackup.serializer(), original)
        val restored = json.decodeFromString(AuraBackup.serializer(), text)
        assertEquals(1, restored.evolutionProposals.size)
        assertEquals("create", restored.evolutionProposals.first().action)
        assertEquals(1, restored.evolutionSettings.size)
        assertEquals(true, restored.evolutionSettings.first().enabled)
        assertEquals(1, restored.evolutionRevisions.size)
        assertEquals("cipher", restored.evolutionRevisions.first().snapshotCiphertext)
        assertEquals(true, restored.preferences.evolutionEnabled)
        assertEquals(12, restored.preferences.evolutionIntervalHours)
    }
}
