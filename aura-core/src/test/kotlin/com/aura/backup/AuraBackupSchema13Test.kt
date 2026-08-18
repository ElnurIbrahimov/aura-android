package com.aura.backup

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Schema v13 closed the last backup gap: 8 of the 48 Room entities had no
 * backup class, so a user who exported, wiped and restored silently lost the
 * creative dependency graph, continuity issues, simulations, the evolution
 * evidence trail, their responses to proactive suggestions, and model-routing
 * outcomes.
 *
 * Seven are now covered. `CreativeGenerationJobEntity` is deliberately
 * excluded as in-flight execution state — see AuraBackupSchema13.kt.
 *
 * These tests are pure serialization: no Room, no Hilt.
 */
class AuraBackupSchema13Test {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `schema version is 29`() {
        assertEquals(29, AuraBackup.SCHEMA_VERSION)
    }

    @Test
    fun `v13 fields roundtrip through json without loss`() {
        val original = AuraBackup(
            exportedAt = 1L,
            appVersionName = "test",
            artifactDependencies = listOf(
                ArtifactDependencyBackup(
                    id = "dep1",
                    sourceArtifactId = "art1",
                    targetArtifactId = "art2",
                    relation = "derived_from",
                    invalidationPolicy = "cascade",
                    createdAt = 100L,
                ),
            ),
            continuityIssues = listOf(
                ContinuityIssueBackup(
                    id = "ci1",
                    projectId = "p1",
                    branchId = "b1",
                    artifactId = "art1",
                    category = "timeline",
                    severity = "error",
                    message = "character is in two places",
                    evidenceFactIdsJson = """["f1","f2"]""",
                    suggestedPatchJson = """{"fix":"move scene"}""",
                    status = "open",
                    createdAt = 200L,
                    resolvedAt = null,
                    resolvedBy = "",
                ),
            ),
            creativeSimulations = listOf(
                CreativeSimulationBackup(
                    id = "sim1",
                    projectId = "p1",
                    branchId = "b1",
                    premise = "what if the bridge falls",
                    assumptionsJson = """["it is raining"]""",
                    narrative = "the bridge falls",
                    stateDeltaJson = """[{"subjectId":"bridge"}]""",
                    causalGraphJson = """[{"cause":"rain"}]""",
                    confidence = 0.75f,
                    contradictionsJson = "[]",
                    createdAt = 300L,
                    canonizedAt = 350L,
                    canonizedFactIdsJson = """["f9"]""",
                ),
            ),
            evolutionEvidence = listOf(
                EvolutionEvidenceBackup(
                    id = "ev1",
                    domain = "SKILL",
                    kind = "skill_invoked",
                    sourceEntityId = "skill1",
                    runId = "run1",
                    conversationId = "conv1",
                    turnTimestamp = 400L,
                    summary = "invoked",
                    payloadJson = """{"ok":true}""",
                    beforeCiphertext = "before",
                    afterCiphertext = "after",
                    createdAt = 410L,
                ),
            ),
            evolutionCandidates = listOf(
                EvolutionCandidateBackup(
                    id = "cand1",
                    domain = "MEMORY",
                    action = "FORGET_MEMORY",
                    targetId = "mem1",
                    argsJson = """{"reason":"stale"}""",
                    rationale = "unused for 90 days",
                    score = 0.62f,
                    evidenceIdsJson = """["ev1"]""",
                    status = "PENDING",
                    reflectionResult = "looks right",
                    createdAt = 500L,
                    updatedAt = 510L,
                ),
            ),
            proactiveInteractions = listOf(
                ProactiveInteractionBackup(
                    id = 7L,
                    eventId = 42L,
                    action = "dismissed",
                    feedback = "not useful",
                    timestamp = 600L,
                ),
            ),
            routingOutcomes = listOf(
                RoutingOutcomeBackup(
                    id = "ro1",
                    modelRole = "chat",
                    modelId = "openai:gpt-4o-mini",
                    success = true,
                    latencyMs = 1234L,
                    costClass = "cheap",
                    outcomeType = "user_accepted",
                    createdAt = 700L,
                    agentScope = "agent:agent_1",
                ),
            ),
        )

        val restored = json.decodeFromString<AuraBackup>(json.encodeToString(original))

        // Against the constant, not a literal. This test is about v13 fields
        // surviving a roundtrip; pinning the current schema number here made it
        // fail for every unrelated version bump, which is noise rather than
        // coverage — AuraBackupSerializationTest owns the version assertion.
        assertEquals(AuraBackup.SCHEMA_VERSION, restored.schemaVersion)

        val dep = restored.artifactDependencies.single()
        assertEquals("derived_from", dep.relation)
        assertEquals("cascade", dep.invalidationPolicy)

        val issue = restored.continuityIssues.single()
        assertEquals("timeline", issue.category)
        assertEquals("character is in two places", issue.message)
        assertEquals("""["f1","f2"]""", issue.evidenceFactIdsJson)

        val sim = restored.creativeSimulations.single()
        assertEquals("what if the bridge falls", sim.premise)
        assertEquals(0.75f, sim.confidence)
        assertEquals("""["f9"]""", sim.canonizedFactIdsJson)

        val evidence = restored.evolutionEvidence.single()
        assertEquals("skill_invoked", evidence.kind)
        assertEquals("before", evidence.beforeCiphertext)
        assertEquals("after", evidence.afterCiphertext)

        val candidate = restored.evolutionCandidates.single()
        assertEquals("FORGET_MEMORY", candidate.action)
        assertEquals(0.62f, candidate.score)

        val interaction = restored.proactiveInteractions.single()
        assertEquals(42L, interaction.eventId)
        assertEquals("dismissed", interaction.action)
        assertEquals("not useful", interaction.feedback)

        val outcome = restored.routingOutcomes.single()
        assertEquals("openai:gpt-4o-mini", outcome.modelId)
        assertEquals("agent:agent_1", outcome.agentScope)
        assertTrue(outcome.success)
    }

    @Test
    fun `a v12 backup still restores under v13`() {
        // Forward compatibility is the whole reason every v13 field defaults
        // to empty. A backup taken before this change has no v13 keys at all;
        // decoding must succeed and simply yield empty lists rather than
        // failing and costing the user everything else in the file.
        val v12Json = """
            {
              "schemaVersion": 12,
              "exportedAt": 1,
              "appVersionName": "old",
              "memories": [],
              "conversations": []
            }
        """.trimIndent()

        val restored = json.decodeFromString<AuraBackup>(v12Json)

        assertEquals(12, restored.schemaVersion)
        assertTrue(restored.artifactDependencies.isEmpty())
        assertTrue(restored.continuityIssues.isEmpty())
        assertTrue(restored.creativeSimulations.isEmpty())
        assertTrue(restored.evolutionEvidence.isEmpty())
        assertTrue(restored.evolutionCandidates.isEmpty())
        assertTrue(restored.proactiveInteractions.isEmpty())
        assertTrue(restored.routingOutcomes.isEmpty())
    }

    @Test
    fun `ContradictionBackup olderBeliefId and newerBeliefId roundtrip through json`() {
        // Belief-linked contradictions (schema v14) are the mapper's only
        // consumer of these two fields (see BeliefReviser.applyVerdict /
        // AuraBackup.toBackup / toEntity). Their symmetry has so far been
        // reviewer-verified only, and this codebase has shipped
        // silently-dropped backup fields before -- pin it with an actual
        // roundtrip.
        val original = AuraBackup(
            exportedAt = 1L,
            appVersionName = "test",
            contradictions = listOf(
                ContradictionBackup(
                    id = "contra_1",
                    olderSummaryId = "belief:b_old",
                    newerSummaryId = "belief:b_new",
                    olderText = "\"kotlin\"",
                    newerText = "\"rust\"",
                    triggerPhrase = "belief_conflict",
                    status = "RESOLVED",
                    createdAt = 100L,
                    resolvedAt = 100L,
                    olderBeliefId = "b_old",
                    newerBeliefId = "b_new",
                ),
            ),
        )

        val restored = json.decodeFromString<AuraBackup>(json.encodeToString(original))

        val contradiction = restored.contradictions.single()
        assertEquals("b_old", contradiction.olderBeliefId)
        assertEquals("b_new", contradiction.newerBeliefId)
    }

    @Test
    fun `a pre-v14 contradiction with no belief link still restores`() {
        // Forward compatibility: a backup taken before belief-linked
        // contradictions existed has no olderBeliefId/newerBeliefId keys at
        // all. Decoding must succeed and default both to null rather than
        // failing the whole restore.
        val json14MissingBeliefFields = """
            {
              "schemaVersion": 14,
              "exportedAt": 1,
              "appVersionName": "old",
              "contradictions": [
                {
                  "id": "contra_1",
                  "olderSummaryId": "sum_old",
                  "newerSummaryId": "sum_new",
                  "olderText": "dark mode",
                  "newerText": "light mode",
                  "triggerPhrase": "switched from",
                  "createdAt": 100
                }
              ]
            }
        """.trimIndent()

        val restored = json.decodeFromString<AuraBackup>(json14MissingBeliefFields)

        val contradiction = restored.contradictions.single()
        assertEquals(null, contradiction.olderBeliefId)
        assertEquals(null, contradiction.newerBeliefId)
    }

    @Test
    fun `restore stats count the v13 tables`() {
        // The `total` getter is a hand-written sum that has fallen out of
        // sync with the field list before. Pin that v13 rows are included.
        val counts = BackupManager.RestoreCounts(
            memories = 0,
            memoryEdits = 0,
            documents = 0,
            creativeProjects = 0,
            conversations = 0,
            nodes = 0,
            edges = 0,
            hands = 0,
            handRuns = 0,
            tasks = 0,
            reminders = 0,
            proactiveEvents = 0,
            profile = 0,
            artifactDependencies = 1,
            continuityIssues = 2,
            creativeSimulations = 3,
            evolutionEvidence = 4,
            evolutionCandidates = 5,
            proactiveInteractions = 6,
            routingOutcomes = 7,
        )
        assertEquals(28, counts.total)
    }
}
