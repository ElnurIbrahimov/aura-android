package com.aura.backup

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Regression tests for the backup JSON serialization shape. These tests
 * do not need Room or Hilt — they just verify that schema v12 fields
 * round-trip through JSON without being dropped.
 */
class AuraBackupSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `schema v12 fields roundtrip through json`() {
        val original = AuraBackup(
            exportedAt = 1L,
            appVersionName = "test",
            memories = emptyList(),
            memoryEdits = emptyList(),
            documents = emptyList(),
            documentChunks = listOf(
                DocumentChunkBackup(
                    id = "chunk-1",
                    documentId = "doc-1",
                    ordinal = 0,
                    charStart = 0,
                    charEnd = 10,
                    pageNumber = 1,
                    text = "hello",
                    contentHash = "abc",
                ),
            ),
            creativeProjects = emptyList(),
            conversations = emptyList(),
            hands = emptyList(),
            handRuns = emptyList(),
            tasks = emptyList(),
            reminders = emptyList(),
            proactiveEvents = emptyList(),
            memoryFeedback = listOf(
                MemoryFeedbackBackup(
                    id = "fb-1",
                    memoryId = "mem-1",
                    kind = "thumbs_up",
                    note = "useful",
                    createdAt = 123L,
                ),
            ),
            referenceIdentities = listOf(
                ReferenceIdentityBackup(
                    id = "id-1",
                    projectId = "proj-1",
                    identityType = "character",
                    name = "Aria",
                    attributesJson = "{}",
                    referenceArtifactIdsJson = "[]",
                    locked = false,
                    createdAt = 1L,
                    updatedAt = 2L,
                ),
            ),
            agentRuns = listOf(
                AgentRunBackup(
                    id = "run-1",
                    goalId = "goal-1",
                    status = "RUNNING",
                    triggerType = "USER_QUERY",
                    triggerPayload = "",
                    modelId = "any-model",
                    conversationId = "conv-1",
                    startedAt = 1L,
                    updatedAt = 2L,
                    errorMessage = "",
                    metadata = "{}",
                ),
            ),
            agentGoals = listOf(
                GoalBackup(
                    id = "goal-1",
                    agentRunId = "run-1",
                    description = "test",
                    doneCriteria = "[]",
                    successEvaluation = "",
                    isAchieved = false,
                ),
            ),
        )

        val serialized = json.encodeToString(AuraBackup.serializer(), original)
        val parsed = json.decodeFromString(AuraBackup.serializer(), serialized)

        assertEquals(1, parsed.documentChunks.size)
        assertEquals("chunk-1", parsed.documentChunks.first().id)
        assertEquals(1, parsed.memoryFeedback.size)
        assertEquals("thumbs_up", parsed.memoryFeedback.first().kind)
        assertEquals(1, parsed.referenceIdentities.size)
        assertEquals("Aria", parsed.referenceIdentities.first().name)
        assertEquals(1, parsed.agentRuns.size)
        assertEquals("RUNNING", parsed.agentRuns.first().status)
        assertEquals(1, parsed.agentGoals.size)
        assertEquals("test", parsed.agentGoals.first().description)
    }

    @Test
    fun `latest schema version is 29`() {
        assertEquals(29, AuraBackup.SCHEMA_VERSION)
    }
}
