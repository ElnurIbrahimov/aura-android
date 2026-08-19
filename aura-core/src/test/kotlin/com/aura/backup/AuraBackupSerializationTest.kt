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
    fun `latest schema version is 30`() {
        assertEquals(30, AuraBackup.SCHEMA_VERSION)
    }

    @Test
    fun `a forum post survives the round trip under the id its votes reference`() {
        // The vote-to-post link was severed at export, not at import: ForumPostBackup
        // carried no id, so the mapper could not have restored one. Guarding the round
        // trip rather than the restore is what catches that — a restore test can be made
        // to pass by hand-building a backup that carries an id the exporter never writes.
        val entity = com.aura.agent.forum.ForumPostEntity(
            id = 42L, threadId = "t1", agentId = "a1", replyToId = 7L,
            type = "proposal", title = "T", body = "B", sentiment = 0.5f,
            status = "open", createdAt = 1L,
        )

        val restored = Json.decodeFromString<ForumPostBackup>(
            Json.encodeToString(entity.toBackup()),
        ).toEntity()

        assertEquals(entity, restored, "a forum post must round-trip unchanged, id included")
    }

    @Test
    fun `a backup written before post ids deserialises with an absent id`() {
        // Every backup taken before schema 30 is missing the field. It must still load —
        // BackupManager drops its votes rather than orphaning them, but the posts
        // themselves are fine and are the bulk of what the user wants back.
        val old = """{"threadId":"t1","agentId":"a1","type":"proposal","title":"T",
            "body":"B","sentiment":0.0,"status":"open","createdAt":1}"""

        assertEquals(0L, Json.decodeFromString<ForumPostBackup>(old).id)
    }
}
