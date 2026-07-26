# Belief Revision Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the loop between the knowledge graph, the world model and the dream cycle so Aura's beliefs about the user are created, revised on contradiction, and always traceable to the turns that support them.

**Architecture:** Beliefs are promoted from reinforced KG edges during the dream cycle. A pure-DB probe on the write path catches structural conflicts (same subject+predicate, different value) and supersedes immediately. Semantic conflicts are adjudicated by an LLM in the dream cycle. Superseding never deletes — the old belief keeps its evidence and gains `supersededBy`, forming a walkable chain.

**Tech Stack:** Kotlin, Room, Hilt, kotlinx.coroutines, JUnit4 + MockK + kotlin.test, Jetpack Compose.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-26-belief-revision-design.md`. Read it before starting.
- No new model calls on the per-turn write path. Slice 2's probe must be pure DB.
- Supersession never deletes a row. `status`/`supersededBy`/`validTo` only.
- `ARBITER_MIN_MARGIN = 0.15f` on a 0..1 normalised score. Below the margin, do not revise.
- Promotion bar: `confidence >= 0.7f` AND `lastReinforced > createdAt` AND subject is the user node.
- Evidence rows are append-only. Never rewrite or delete one during revision.
- Follow existing file idioms: `kotlin.String` in `world/` and `kg/` entity/DAO files, plain `String` elsewhere.
- Test command: `./gradlew :aura-core:testDebugUnitTest --offline --tests "<FQCN>"`.
- Full gate before any commit that touches production code: `./gradlew :aura-core:testDebugUnitTest :app:testDebugUnitTest --offline`.

## File Structure

| File | Responsibility | Slice |
|---|---|---|
| `aura-core/.../kg/KgId.kt` (modify) | Add `USER_NODE_ID` canonical constant | 1 |
| `aura-core/.../tools/KnowledgeGraphTool.kt` (modify) | Prompt rule: label the speaker `user` | 1 |
| `aura-core/.../world/BeliefPromoter.kt` (create) | KG edge → belief + evidence, applies the bar | 1 |
| `aura-core/.../dream/DreamConsolidator.kt` (modify) | Phase 10 PROMOTE, Phase 11 ADJUDICATE | 1, 3 |
| `aura-core/.../dream/DreamCycleReport.kt` (modify) | `beliefsPromoted`, `beliefsRevised` counters | 1, 3 |
| `aura-core/.../world/BeliefArbiter.kt` (create) | Pure scoring — which side wins, or neither | 2 |
| `aura-core/.../world/BeliefReviser.kt` (create) | Applies a verdict: supersede + evidence + resolve | 2 |
| `aura-core/.../world/BeliefConflictProbe.kt` (create) | Structural conflict check on the write path | 2 |
| `aura-core/.../kg/KnowledgeGraphRepository.kt` (modify) | Call the probe after `saveGraph` | 2 |
| `aura-core/.../dream/ContradictionEntity.kt` (modify) | Nullable belief id columns | 2 |
| `aura-core/.../dream/DreamConsolidationModule.kt` (modify) | DB v2→v3 migration, DI for new units | 2 |
| `app/.../ui/evolution/BeliefsScreen.kt` (modify) | Show beliefs, chain, evidence | 1, 2 |
| `app/.../ui/evolution/BeliefsViewModel.kt` (modify) | Introduce BeliefsUiState; load chain + evidence | 1, 2 |
| `aura-core/.../tools/QueryWorldModelTool.kt` (modify) | Return supersession history | 2 |

---

## Slice 1 — Promotion

### Task 1: Canonical user node

The KG has no concept of "the user". `KgId.node(type, label)` hashes type+label, and the extractor prompt never tells the model how to label the speaker, so edges about the user are indistinguishable from edges about anyone else. Promotion needs a stable id for "the user".

**Files:**
- Modify: `aura-core/src/main/kotlin/com/aura/kg/KgId.kt`
- Modify: `aura-core/src/main/kotlin/com/aura/tools/KnowledgeGraphTool.kt` (system prompt, ~line 99)
- Test: `aura-core/src/test/kotlin/com/aura/kg/KgIdUserNodeTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces: `KgId.USER_NODE_ID: String` — the node id every belief subject is checked against.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aura.kg

import org.junit.Test
import kotlin.test.assertEquals

class KgIdUserNodeTest {

    @Test
    fun `USER_NODE_ID matches a PERSON node labelled user`() {
        // The extractor is instructed to label the speaker "user"; promotion
        // identifies beliefs about the user by comparing an edge's sourceId
        // against this constant, so the two must agree exactly.
        assertEquals(KgId.node(NodeType.PERSON, "user"), KgId.USER_NODE_ID)
    }

    @Test
    fun `USER_NODE_ID is case and whitespace insensitive at the label`() {
        assertEquals(KgId.USER_NODE_ID, KgId.node(NodeType.PERSON, "  User "))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :aura-core:testDebugUnitTest --offline --tests "com.aura.kg.KgIdUserNodeTest"`
Expected: FAIL — `Unresolved reference: USER_NODE_ID`

- [ ] **Step 3: Add the constant**

In `KgId.kt`, inside `object KgId`, above `fun node`:

```kotlin
    /**
     * Canonical id of the node representing the app's user.
     *
     * The KG extractor is instructed to label the speaker "user" with type
     * `person`, so every edge whose `sourceId` equals this is a statement
     * about the user. [com.aura.world.BeliefPromoter] uses it as the
     * subject filter — without a stable id there is no way to tell a belief
     * about the user from a belief about anyone else they mentioned.
     */
    val USER_NODE_ID: String by lazy { node(NodeType.PERSON, "user") }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :aura-core:testDebugUnitTest --offline --tests "com.aura.kg.KgIdUserNodeTest"`
Expected: PASS

- [ ] **Step 5: Teach the extractor to use the label**

In `KnowledgeGraphTool.callLlm`, in the `Rules:` block, after the `- 'label' is a concise name for the node.` line:

```kotlin
            appendLine("- Refer to the speaker as label 'user' with type 'person'. Never use their real name, 'I', or 'me' for that node.")
```

- [ ] **Step 6: Run the full gate and commit**

```bash
./gradlew :aura-core:testDebugUnitTest :app:testDebugUnitTest --offline
git add aura-core/src/main/kotlin/com/aura/kg/KgId.kt aura-core/src/main/kotlin/com/aura/tools/KnowledgeGraphTool.kt aura-core/src/test/kotlin/com/aura/kg/KgIdUserNodeTest.kt
git commit -m "feat(kg): canonical user node id for belief promotion"
```

---

### Task 2: BeliefPromoter

**Files:**
- Create: `aura-core/src/main/kotlin/com/aura/world/BeliefPromoter.kt`
- Test: `aura-core/src/test/kotlin/com/aura/world/BeliefPromoterTest.kt`

**Interfaces:**
- Consumes: `KgId.USER_NODE_ID` (Task 1); `KnowledgeGraphDao.edgesFrom(sourceId: String): List<EdgeEntity>`; `BeliefDao.active(subject: kotlin.String, predicate: kotlin.String): BeliefEntity?`, `.upsert(belief)`, `.verify(id, confidence, timestamp)`; `EvidenceDao.upsert(evidence)`
- Produces: `BeliefPromoter.promote(now: Long): Int` — returns the number of beliefs created or re-verified. Called by Task 3.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aura.world

import com.aura.kg.EdgeEntity
import com.aura.kg.KgId
import com.aura.kg.KnowledgeGraphDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals

class BeliefPromoterTest {

    private val kgDao = mockk<KnowledgeGraphDao>(relaxed = true)
    private val beliefDao = mockk<BeliefDao>(relaxed = true)
    private val evidenceDao = mockk<EvidenceDao>(relaxed = true)

    private fun promoter() = BeliefPromoter(kgDao, beliefDao, evidenceDao)

    private fun edge(
        target: String,
        confidence: Float = 0.9f,
        createdAt: Long = 1_000L,
        lastReinforced: Long = 2_000L,
    ) = EdgeEntity(
        id = "e_$target",
        type = "USES",
        sourceId = KgId.USER_NODE_ID,
        targetId = target,
        confidence = confidence,
        sourceTurnId = "turn_$target",
        createdAt = createdAt,
        lastReinforced = lastReinforced,
        sourceConversationId = "conv1",
        sourceTurnTimestamp = lastReinforced,
    )

    @Test
    fun `promotes a reinforced high-confidence edge about the user`() = runBlocking {
        coEvery { kgDao.edgesFrom(KgId.USER_NODE_ID) } returns listOf(edge("kotlin"))
        coEvery { beliefDao.active(any(), any()) } returns null

        val promoted = promoter().promote(now = 5_000L)

        assertEquals(1, promoted)
        val captured = slot<BeliefEntity>()
        coVerify { beliefDao.upsert(capture(captured)) }
        assertEquals("user", captured.captured.subject)
        assertEquals("USES", captured.captured.predicate)
        assertEquals("active", captured.captured.status)
    }

    @Test
    fun `skips an edge seen only once`() = runBlocking {
        // lastReinforced == createdAt means the edge has never been seen
        // again. One offhand remark is not a belief.
        coEvery { kgDao.edgesFrom(KgId.USER_NODE_ID) } returns
            listOf(edge("kotlin", createdAt = 1_000L, lastReinforced = 1_000L))
        coEvery { beliefDao.active(any(), any()) } returns null

        assertEquals(0, promoter().promote(now = 5_000L))
        coVerify(exactly = 0) { beliefDao.upsert(any()) }
    }

    @Test
    fun `skips a low-confidence edge`() = runBlocking {
        coEvery { kgDao.edgesFrom(KgId.USER_NODE_ID) } returns listOf(edge("kotlin", confidence = 0.5f))
        coEvery { beliefDao.active(any(), any()) } returns null

        assertEquals(0, promoter().promote(now = 5_000L))
        coVerify(exactly = 0) { beliefDao.upsert(any()) }
    }

    @Test
    fun `re-promoting an unchanged edge verifies instead of duplicating`() = runBlocking {
        coEvery { kgDao.edgesFrom(KgId.USER_NODE_ID) } returns listOf(edge("kotlin"))
        coEvery { beliefDao.active("user", "USES") } returns BeliefEntity(
            id = "b1",
            subject = "user",
            predicate = "USES",
            valueJson = "\"kotlin\"",
        )

        assertEquals(1, promoter().promote(now = 5_000L))
        coVerify(exactly = 1) { beliefDao.verify("b1", any(), 5_000L) }
        coVerify(exactly = 0) { beliefDao.upsert(any()) }
    }

    @Test
    fun `writes evidence carrying the source turn`() = runBlocking {
        coEvery { kgDao.edgesFrom(KgId.USER_NODE_ID) } returns listOf(edge("kotlin"))
        coEvery { beliefDao.active(any(), any()) } returns null

        promoter().promote(now = 5_000L)

        val captured = slot<EvidenceEntity>()
        coVerify { evidenceDao.upsert(capture(captured)) }
        assertEquals("kg_edge", captured.captured.source)
        // "because Z" has to resolve back to a turn — this is that link.
        assert(captured.captured.detailJson.contains("turn_kotlin"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :aura-core:testDebugUnitTest --offline --tests "com.aura.world.BeliefPromoterTest"`
Expected: FAIL — `Unresolved reference: BeliefPromoter`

- [ ] **Step 3: Write the implementation**

```kotlin
package com.aura.world

import com.aura.kg.EdgeEntity
import com.aura.kg.KgId
import com.aura.kg.KnowledgeGraphDao
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Minimum edge confidence worth asserting as a belief. */
private const val MIN_EDGE_CONFIDENCE = 0.7f

/**
 * Promotes reinforced knowledge-graph edges about the user into world-model
 * beliefs.
 *
 * Before this existed the belief table had no producer at all — `CREATE_BELIEF`
 * was reachable only through an evolution proposal that nothing ever generated,
 * so the world model was a schema with no rows and belief revision had nothing
 * to revise.
 *
 * A belief is an edge that cleared three bars: it is about the user, the
 * extractor was confident, and it was seen again in a later turn. The last one
 * is deliberately a proxy — `EdgeEntity` has no reinforcement counter, so
 * `lastReinforced > createdAt` is the strongest "seen more than once" test
 * expressible today. See §5 of the design spec.
 */
@Singleton
class BeliefPromoter @Inject constructor(
    private val kgDao: KnowledgeGraphDao,
    private val beliefDao: BeliefDao,
    private val evidenceDao: EvidenceDao,
) {

    /**
     * Promote every qualifying edge. Idempotent: an edge whose belief already
     * exists bumps `lastVerifiedAt` rather than creating a duplicate.
     *
     * @return count of beliefs created or re-verified.
     */
    suspend fun promote(now: Long = System.currentTimeMillis()): Int {
        val edges = kgDao.edgesFrom(KgId.USER_NODE_ID).filter { it.qualifies() }
        var count = 0
        for (edge in edges) {
            val predicate = edge.type
            val valueJson = JsonPrimitive(edge.targetId).toString()
            val existing = beliefDao.active("user", predicate)
            if (existing != null) {
                // Same subject+predicate already believed. Conflicting values
                // are NOT resolved here — that is the reviser's job (slice 2).
                // Promotion only ever reinforces.
                beliefDao.verify(existing.id, edge.confidence, now)
                count++
                continue
            }
            val beliefId = UUID.randomUUID().toString()
            beliefDao.upsert(
                BeliefEntity(
                    id = beliefId,
                    subject = "user",
                    predicate = predicate,
                    valueJson = valueJson,
                    confidence = edge.confidence,
                    validFrom = now,
                    status = "active",
                    createdAt = now,
                    updatedAt = now,
                    lastVerifiedAt = now,
                ),
            )
            evidenceDao.upsert(edge.toEvidence(beliefId, now))
            count++
        }
        return count
    }

    private fun EdgeEntity.qualifies(): Boolean =
        confidence >= MIN_EDGE_CONFIDENCE && lastReinforced > createdAt

    /**
     * Snapshot the edge's provenance as evidence.
     *
     * This is load-bearing rather than decorative: `KnowledgeGraphRepository`
     * REPLACEs an edge on reinforcement and overwrites `sourceTurnId` with the
     * latest turn, so the edge itself cannot say when support first arrived.
     * Accumulated evidence rows are the only durable record, and they are what
     * [BeliefArbiter]'s recency and corroboration signals read.
     */
    private fun EdgeEntity.toEvidence(beliefId: String, now: Long) = EvidenceEntity(
        id = UUID.randomUUID().toString(),
        beliefId = beliefId,
        source = "kg_edge",
        summary = "$type → $targetId",
        detailJson = buildJsonObject {
            put("edgeId", id)
            put("sourceTurnId", sourceTurnId)
            put("conversationId", sourceConversationId)
        }.toString(),
        timestamp = now,
        confidence = confidence,
    )
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :aura-core:testDebugUnitTest --offline --tests "com.aura.world.BeliefPromoterTest"`
Expected: PASS (5 tests)

- [ ] **Step 5: Run the full gate and commit**

```bash
./gradlew :aura-core:testDebugUnitTest :app:testDebugUnitTest --offline
git add aura-core/src/main/kotlin/com/aura/world/BeliefPromoter.kt aura-core/src/test/kotlin/com/aura/world/BeliefPromoterTest.kt
git commit -m "feat(world): promote reinforced KG edges into beliefs"
```

---

### Task 3: Wire promotion into the dream cycle

**Files:**
- Modify: `aura-core/src/main/kotlin/com/aura/dream/DreamCycleReport.kt`
- Modify: `aura-core/src/main/kotlin/com/aura/dream/DreamConsolidator.kt` (constructor + `runCycle`, after the densifyGraph block ~line 213)
- Test: `aura-core/src/test/kotlin/com/aura/dream/DreamPromotePhaseTest.kt`

**Interfaces:**
- Consumes: `BeliefPromoter.promote(now: Long): Int` (Task 2)
- Produces: `DreamCycleReport.beliefsPromoted: Int`

- [ ] **Step 1: Add the report field**

In `DreamCycleReport.kt`, after `val profileUpdated: Boolean = false,`:

```kotlin
    // Phase 10: promote reinforced KG edges into world-model beliefs
    val beliefsPromoted: Int = 0,
```

- [ ] **Step 2: Write the failing test**

```kotlin
package com.aura.dream

import org.junit.Test
import kotlin.test.assertEquals

/**
 * Phase 10 reporting contract.
 *
 * `DreamCycleReport` is a value type, so what is worth pinning is that adding
 * the new counter did not disturb the counters the earlier phases write —
 * `copy(beliefsPromoted = n)` must leave every other field alone, because
 * runCycle threads a single report through nine prior phases via successive
 * `copy` calls and a clobbered field silently loses a phase's result.
 *
 * The best-effort behaviour of the phase itself (a thrown promoter must not
 * abort the cycle) is not unit-testable without constructing the whole
 * DreamConsolidator with nine collaborators; it is enforced by the
 * try/catch/log block added in Step 5 and matches every other phase in
 * runCycle. Asserting that `runCatching` catches would test the standard
 * library, not this code.
 */
class DreamPromotePhaseTest {

    @Test
    fun `report defaults the new counter to zero`() {
        assertEquals(0, DreamCycleReport().beliefsPromoted)
    }

    @Test
    fun `setting the promoted count preserves earlier phase counters`() {
        val afterEarlierPhases = DreamCycleReport(
            memoriesProcessed = 12,
            summariesWritten = 3,
            routinesExtracted = 2,
            contradictionsFound = 1,
            graphEdgesProposed = 5,
            memoriesArchived = 4,
            profileUpdated = true,
        )

        val withPromotion = afterEarlierPhases.copy(beliefsPromoted = 7)

        assertEquals(7, withPromotion.beliefsPromoted)
        assertEquals(12, withPromotion.memoriesProcessed)
        assertEquals(3, withPromotion.summariesWritten)
        assertEquals(2, withPromotion.routinesExtracted)
        assertEquals(1, withPromotion.contradictionsFound)
        assertEquals(5, withPromotion.graphEdgesProposed)
        assertEquals(4, withPromotion.memoriesArchived)
        assertEquals(true, withPromotion.profileUpdated)
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :aura-core:testDebugUnitTest --offline --tests "com.aura.dream.DreamPromotePhaseTest"`
Expected: FAIL — `Unresolved reference: beliefsPromoted` until Step 1's field is added.

- [ ] **Step 4: Add the constructor dependency**

In `DreamConsolidator`'s constructor parameter list, after `private val contradictionDao: ContradictionDao,`:

```kotlin
    private val beliefPromoter: BeliefPromoter? = null,
```

Nullable with a default so existing test constructions of `DreamConsolidator` keep compiling.

- [ ] **Step 5: Add Phase 10 to runCycle**

In `runCycle`, immediately after the `densifyGraph()` try/catch block and before the outer `} catch (cancelled:`:

```kotlin
            // 10. PROMOTE -- turn reinforced KG edges about the user into
            //     world-model beliefs. Runs after densifyGraph so it sees
            //     any edges this cycle added.
            try {
                val promoted = beliefPromoter?.promote() ?: 0
                report = report.copy(beliefsPromoted = promoted)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                try { android.util.Log.w("DreamConsolidator", "promoteBeliefs: ${t.message}") } catch (_: RuntimeException) {}
            }
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew :aura-core:testDebugUnitTest --offline --tests "com.aura.dream.DreamPromotePhaseTest"`
Expected: PASS

- [ ] **Step 7: Run the full gate and commit**

```bash
./gradlew :aura-core:testDebugUnitTest :app:testDebugUnitTest --offline
git add aura-core/src/main/kotlin/com/aura/dream/ aura-core/src/test/kotlin/com/aura/dream/DreamPromotePhaseTest.kt
git commit -m "feat(dream): phase 10 promotes KG edges into beliefs"
```

---

### Task 4: Beliefs screen shows evidence

`BeliefsScreen.kt` is 100 lines and read-only. With Task 3 landed the table finally has rows; this makes them inspectable.

**Files:**
- Modify: `app/src/main/kotlin/com/aura/ui/evolution/BeliefsViewModel.kt`
- Modify: `app/src/main/kotlin/com/aura/ui/evolution/BeliefsScreen.kt`
- Test: `app/src/test/kotlin/com/aura/ui/evolution/BeliefsViewModelTest.kt`

**Interfaces:**
- Consumes: `EvidenceDao.forBelief(beliefId: kotlin.String): List<EvidenceEntity>`
- Produces: `BeliefsUiState.evidence: Map<String, List<EvidenceEntity>>` keyed by belief id.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aura.ui.evolution

import com.aura.world.BeliefDao
import com.aura.world.BeliefEntity
import com.aura.world.EvidenceDao
import com.aura.world.EvidenceEntity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class BeliefsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val beliefDao = mockk<BeliefDao>(relaxed = true)
    private val evidenceDao = mockk<EvidenceDao>(relaxed = true)

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `loads evidence for each active belief`() = runTest(dispatcher) {
        val belief = BeliefEntity(
            id = "b1", subject = "user", predicate = "USES", valueJson = "\"kotlin\"",
        )
        coEvery { beliefDao.allActive(any()) } returns listOf(belief)
        coEvery { evidenceDao.forBelief("b1") } returns listOf(
            EvidenceEntity(id = "e1", beliefId = "b1", source = "kg_edge", summary = "USES → kotlin"),
        )

        val vm = BeliefsViewModel(beliefDao, evidenceDao)
        advanceUntilIdle()

        assertEquals(1, vm.state.value.beliefs.size)
        assertEquals(1, vm.state.value.evidence["b1"]?.size)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --offline --tests "com.aura.ui.evolution.BeliefsViewModelTest"`
Expected: FAIL — constructor does not take `evidenceDao`, `state.evidence` unresolved.

- [ ] **Step 3: Introduce BeliefsUiState and load evidence**

The ViewModel currently exposes two bare StateFlows (`beliefs`, `selected`) and
has no UI state class. Adding a second and third parallel flow would leave the
screen reading four unrelated sources; consolidate to one state object first,
matching `ChatUiState` / `SettingsUiState` elsewhere in the app.

Replace the top of `BeliefsViewModel.kt` (keeping `select`/`clearSelection`
untouched):

```kotlin
data class BeliefsUiState(
    val beliefs: List<BeliefEntity> = emptyList(),
    /** Evidence supporting each belief, keyed by belief id. */
    val evidence: Map<String, List<EvidenceEntity>> = emptyMap(),
)

@HiltViewModel
class BeliefsViewModel @Inject constructor(
    private val beliefDao: BeliefDao,
    private val evidenceDao: EvidenceDao,
) : ViewModel() {

    private val _state = MutableStateFlow(BeliefsUiState())
    val state: StateFlow<BeliefsUiState> = _state.asStateFlow()

    /** Retained for existing callers that observe the list directly. */
    val beliefs: StateFlow<List<BeliefEntity>> =
        _state.map { it.beliefs }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init { load() }

    private fun load() = viewModelScope.launch {
        val loaded = beliefDao.allActive(200)
        val evidenceByBelief = loaded.associate { belief ->
            belief.id to runCatching { evidenceDao.forBelief(belief.id) }.getOrDefault(emptyList())
        }
        _state.value = BeliefsUiState(beliefs = loaded, evidence = evidenceByBelief)
    }
```

Add the imports: `com.aura.world.EvidenceDao`, `com.aura.world.EvidenceEntity`,
`kotlinx.coroutines.flow.asStateFlow`, `kotlinx.coroutines.flow.map`,
`kotlinx.coroutines.flow.stateIn`, `kotlinx.coroutines.flow.SharingStarted`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --offline --tests "com.aura.ui.evolution.BeliefsViewModelTest"`
Expected: PASS

- [ ] **Step 5: Render evidence in the screen**

In `BeliefsScreen.kt`, inside the belief row's `Column`, after the existing belief text:

```kotlin
                val supporting = state.evidence[belief.id].orEmpty()
                if (supporting.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    supporting.take(3).forEach { evidence ->
                        Text(
                            text = "· ${evidence.summary}",
                            style = MaterialTheme.typography.labelSmall,
                            color = AuraThemeTokens.colors.textSecondary,
                        )
                    }
                }
```

- [ ] **Step 6: Run the full gate and commit**

```bash
./gradlew :aura-core:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug --offline
git add app/src/main/kotlin/com/aura/ui/ app/src/test/kotlin/com/aura/ui/evolution/BeliefsViewModelTest.kt
git commit -m "feat(ui): show supporting evidence on the beliefs screen"
```

---

## Slice 2 — Revision

### Task 5: BeliefArbiter

**Files:**
- Create: `aura-core/src/main/kotlin/com/aura/world/BeliefArbiter.kt`
- Test: `aura-core/src/test/kotlin/com/aura/world/BeliefArbiterTest.kt`

**Interfaces:**
- Consumes: `BeliefEntity`, `EvidenceEntity`
- Produces:
  - `data class BeliefSide(val belief: BeliefEntity, val evidence: List<EvidenceEntity>)`
  - `sealed interface Verdict { data class Winner(val winning: BeliefEntity, val losing: BeliefEntity, val margin: Float) : Verdict; data object TooClose : Verdict }`
  - `BeliefArbiter.arbitrate(a: BeliefSide, b: BeliefSide, now: Long): Verdict`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aura.world

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BeliefArbiterTest {

    private val now = 1_000_000L
    private val day = 86_400_000L

    private fun belief(id: String, confidence: Float = 0.8f) =
        BeliefEntity(id = id, subject = "user", predicate = "USES", valueJson = "\"$id\"", confidence = confidence)

    private fun evidence(beliefId: String, ageDays: Long, source: String = "user_statement", n: Int = 1) =
        (1..n).map {
            EvidenceEntity(
                id = "$beliefId-$it",
                beliefId = beliefId,
                source = source,
                summary = "s",
                timestamp = now - ageDays * day - it,
            )
        }

    @Test
    fun `recent evidence beats stale evidence`() {
        val fresh = BeliefSide(belief("fresh"), evidence("fresh", ageDays = 1))
        val stale = BeliefSide(belief("stale"), evidence("stale", ageDays = 200))

        val verdict = BeliefArbiter.arbitrate(fresh, stale, now)

        assertIs<Verdict.Winner>(verdict)
        assertEquals("fresh", verdict.winning.id)
        assertEquals("stale", verdict.losing.id)
    }

    @Test
    fun `corroboration beats a single remark of the same age`() {
        val many = BeliefSide(belief("many"), evidence("many", ageDays = 5, n = 4))
        val once = BeliefSide(belief("once"), evidence("once", ageDays = 5, n = 1))

        val verdict = BeliefArbiter.arbitrate(many, once, now)

        assertIs<Verdict.Winner>(verdict)
        assertEquals("many", verdict.winning.id)
    }

    @Test
    fun `a direct user statement outranks a derived inference`() {
        val stated = BeliefSide(belief("stated"), evidence("stated", ageDays = 5, source = "user_statement"))
        val derived = BeliefSide(belief("derived"), evidence("derived", ageDays = 5, source = "derived"))

        val verdict = BeliefArbiter.arbitrate(stated, derived, now)

        assertIs<Verdict.Winner>(verdict)
        assertEquals("stated", verdict.winning.id)
    }

    @Test
    fun `identical sides are too close to call`() {
        // The safety property: refusing to decide is a valid outcome. A tie
        // must not silently overwrite an established belief.
        val a = BeliefSide(belief("a"), evidence("a", ageDays = 5))
        val b = BeliefSide(belief("b"), evidence("b", ageDays = 5))

        assertIs<Verdict.TooClose>(BeliefArbiter.arbitrate(a, b, now))
    }

    @Test
    fun `a side with no evidence never wins`() {
        val withEvidence = BeliefSide(belief("has"), evidence("has", ageDays = 90))
        val without = BeliefSide(belief("none"), emptyList())

        val verdict = BeliefArbiter.arbitrate(without, withEvidence, now)

        assertIs<Verdict.Winner>(verdict)
        assertEquals("has", verdict.winning.id)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :aura-core:testDebugUnitTest --offline --tests "com.aura.world.BeliefArbiterTest"`
Expected: FAIL — `Unresolved reference: BeliefArbiter`

- [ ] **Step 3: Write the implementation**

```kotlin
package com.aura.world

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min

/** A belief and the evidence supporting it. */
data class BeliefSide(
    val belief: BeliefEntity,
    val evidence: List<EvidenceEntity>,
)

/** Outcome of arbitrating two conflicting beliefs. */
sealed interface Verdict {
    data class Winner(
        val winning: BeliefEntity,
        val losing: BeliefEntity,
        val margin: Float,
    ) : Verdict

    /** Neither side is sufficiently better supported. Do not revise. */
    data object TooClose : Verdict
}

/**
 * Decides which of two conflicting beliefs is better supported.
 *
 * Deliberately dependency-free — no Room, no coroutines — so the scoring rule
 * can be unit-tested exhaustively and driven directly by the convergence eval.
 *
 * Last-write-wins is the obvious rule and it is wrong: it lets one offhand
 * remark overturn a belief supported by a year of consistent behaviour. The
 * scoring below weights recency most heavily (people genuinely change) but
 * requires a real margin before acting.
 */
object BeliefArbiter {

    /** Minimum score gap before a revision is allowed. See design spec §6. */
    const val ARBITER_MIN_MARGIN = 0.15f

    private const val RECENCY_WEIGHT = 0.45f
    private const val CORROBORATION_WEIGHT = 0.30f
    private const val SOURCE_WEIGHT = 0.15f
    private const val CONFIDENCE_WEIGHT = 0.10f

    /** Evidence half-life for recency scoring. */
    private const val HALF_LIFE_DAYS = 30.0
    private const val DAY_MS = 86_400_000.0

    fun arbitrate(a: BeliefSide, b: BeliefSide, now: Long = System.currentTimeMillis()): Verdict {
        val scoreA = score(a, now)
        val scoreB = score(b, now)
        val margin = abs(scoreA - scoreB)
        if (margin < ARBITER_MIN_MARGIN) return Verdict.TooClose
        return if (scoreA > scoreB) {
            Verdict.Winner(a.belief, b.belief, margin)
        } else {
            Verdict.Winner(b.belief, a.belief, margin)
        }
    }

    /** Normalised 0..1 support score. */
    internal fun score(side: BeliefSide, now: Long): Float {
        if (side.evidence.isEmpty()) return 0f
        return RECENCY_WEIGHT * recency(side, now) +
            CORROBORATION_WEIGHT * corroboration(side) +
            SOURCE_WEIGHT * sourceRank(side) +
            CONFIDENCE_WEIGHT * side.belief.confidence
    }

    /** Exponential decay on the newest supporting evidence. */
    private fun recency(side: BeliefSide, now: Long): Float {
        val newest = side.evidence.maxOf { it.timestamp }
        val ageDays = (now - newest).coerceAtLeast(0L) / DAY_MS
        return exp(-ageDays / HALF_LIFE_DAYS).toFloat()
    }

    /**
     * Distinct supporting turns, saturating at 4. Repetition separates a real
     * change of mind from a one-off; beyond a handful it stops being
     * informative.
     */
    private fun corroboration(side: BeliefSide): Float {
        val distinct = side.evidence.map { it.detailJson }.distinct().size
        return min(distinct, 4) / 4f
    }

    /** A direct statement outranks a tool result, which outranks an inference. */
    private fun sourceRank(side: BeliefSide): Float =
        side.evidence.maxOf {
            when (it.source) {
                "user_statement" -> 1.0f
                "tool_result", "calendar", "notification" -> 0.6f
                "kg_edge" -> 0.6f
                else -> 0.3f
            }
        }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :aura-core:testDebugUnitTest --offline --tests "com.aura.world.BeliefArbiterTest"`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add aura-core/src/main/kotlin/com/aura/world/BeliefArbiter.kt aura-core/src/test/kotlin/com/aura/world/BeliefArbiterTest.kt
git commit -m "feat(world): scoring arbiter for conflicting beliefs"
```

---

### Task 6: ContradictionEntity belief columns + migration

**Files:**
- Modify: `aura-core/src/main/kotlin/com/aura/dream/ContradictionEntity.kt`
- Modify: `aura-core/src/main/kotlin/com/aura/dream/DreamConsolidationDatabase.kt` (version 2 → 3)
- Modify: `aura-core/src/main/kotlin/com/aura/dream/DreamConsolidationModule.kt` (add `MIGRATION_2_3` to the migrations array)
- Test: `aura-core/src/androidTest/kotlin/com/aura/dream/DreamDatabaseMigrationTest.kt`

**Interfaces:**
- Produces: `ContradictionEntity.olderBeliefId: String?`, `.newerBeliefId: String?`

- [ ] **Step 1: Add the columns**

In `ContradictionEntity`, before `val createdAt`:

```kotlin
    /**
     * Beliefs this contradiction is between, when it came from belief
     * revision rather than summary-text comparison. Null for the existing
     * summary-level rows, which is why both are nullable — the two
     * detectors share this table but link different things.
     */
    val olderBeliefId: String? = null,
    val newerBeliefId: String? = null,
```

- [ ] **Step 2: Bump the database version**

In `DreamConsolidationDatabase.kt`, change `version = 2` to `version = 3`.

- [ ] **Step 3: Write the migration**

In `DreamConsolidationModule.kt`, alongside the existing migrations:

```kotlin
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE contradictions ADD COLUMN olderBeliefId TEXT")
            db.execSQL("ALTER TABLE contradictions ADD COLUMN newerBeliefId TEXT")
        }
    }
```

Add `MIGRATION_2_3` to the `migrations = arrayOf(...)` list in `provideDatabase`.

- [ ] **Step 4: Write the migration test**

```kotlin
package com.aura.dream

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DreamDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DreamConsolidationDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate2To3_preservesRows_andAddsNullableBeliefColumns() {
        val name = "test-dream-2-3.db"
        val db = helper.createDatabase(name, 2)
        db.execSQL(
            "INSERT INTO contradictions (id, olderSummaryId, newerSummaryId, olderText, newerText, " +
                "triggerPhrase, confidence, status, createdAt) " +
                "VALUES ('c1', 's1', 's2', 'old', 'new', 'no longer', 0.6, 'UNRESOLVED', 1000)",
        )
        db.close()

        val migrated = helper.runMigrationsAndValidate(
            name, 3, true, DreamConsolidationModule.MIGRATION_2_3,
        )

        val cursor = migrated.query("SELECT olderBeliefId, newerBeliefId FROM contradictions WHERE id = 'c1'")
        cursor.use {
            assertTrue("pre-existing contradiction was lost", it.moveToFirst())
            // Existing summary-level rows must survive with NULL belief ids.
            assertTrue(it.isNull(0) && it.isNull(1))
        }
    }
}
```

- [ ] **Step 5: Verify compilation and commit**

`androidTest` cannot be executed without a device. Compile it and say so in the commit.

```bash
./gradlew :aura-core:compileDebugAndroidTestKotlin :aura-core:testDebugUnitTest --offline
git add aura-core/src/main/kotlin/com/aura/dream/ aura-core/src/androidTest/kotlin/com/aura/dream/DreamDatabaseMigrationTest.kt
git commit -m "feat(dream): link contradictions to beliefs (db v3)

Migration test compiles but has NOT been executed — no device attached."
```

---

### Task 7: BeliefReviser

**Files:**
- Create: `aura-core/src/main/kotlin/com/aura/world/BeliefReviser.kt`
- Test: `aura-core/src/test/kotlin/com/aura/world/BeliefReviserTest.kt`

**Interfaces:**
- Consumes: `Verdict`, `BeliefSide` (Task 5); `ContradictionEntity.olderBeliefId/newerBeliefId` (Task 6); `BeliefDao.supersede(id, status, supersededBy, timestamp)`; `ContradictionDao.insert(entity)`
- Produces: `BeliefReviser.applyVerdict(verdict: Verdict, now: Long): Boolean` — true when a revision was written.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aura.world

import com.aura.dream.ContradictionDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BeliefReviserTest {

    private val beliefDao = mockk<BeliefDao>(relaxed = true)
    private val contradictionDao = mockk<ContradictionDao>(relaxed = true)
    private fun reviser() = BeliefReviser(beliefDao, contradictionDao)

    private fun belief(id: String) =
        BeliefEntity(id = id, subject = "user", predicate = "USES", valueJson = "\"$id\"")

    @Test
    fun `winner supersedes loser without deleting it`() = runBlocking {
        val verdict = Verdict.Winner(belief("new"), belief("old"), margin = 0.4f)

        assertTrue(reviser().applyVerdict(verdict, now = 5_000L))

        // The loser is marked, never removed — the chain is the feature.
        coVerify { beliefDao.supersede("old", "superseded", "new", 5_000L) }
        coVerify(exactly = 0) { beliefDao.deleteAll() }
    }

    @Test
    fun `too close writes nothing`() = runBlocking {
        assertFalse(reviser().applyVerdict(Verdict.TooClose, now = 5_000L))
        coVerify(exactly = 0) { beliefDao.supersede(any(), any(), any(), any()) }
    }

    @Test
    fun `revision records a resolved contradiction linking both beliefs`() = runBlocking {
        val verdict = Verdict.Winner(belief("new"), belief("old"), margin = 0.4f)

        reviser().applyVerdict(verdict, now = 5_000L)

        coVerify {
            contradictionDao.insert(
                match { it.olderBeliefId == "old" && it.newerBeliefId == "new" && it.status == "RESOLVED" },
            )
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :aura-core:testDebugUnitTest --offline --tests "com.aura.world.BeliefReviserTest"`
Expected: FAIL — `Unresolved reference: BeliefReviser`

- [ ] **Step 3: Write the implementation**

```kotlin
package com.aura.world

import com.aura.dream.ContradictionDao
import com.aura.dream.ContradictionEntity
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applies an arbiter [Verdict] to the world model.
 *
 * The single invariant: revision never deletes. The losing belief keeps its
 * row and its evidence, and gains `status = "superseded"`, `supersededBy` and
 * `validTo`. Walking `supersededBy` backwards is what produces "I used to
 * think X" — it is the feature, not an audit side-effect.
 */
@Singleton
class BeliefReviser @Inject constructor(
    private val beliefDao: BeliefDao,
    private val contradictionDao: ContradictionDao,
) {

    /** @return true when a revision was written. */
    suspend fun applyVerdict(verdict: Verdict, now: Long = System.currentTimeMillis()): Boolean {
        if (verdict !is Verdict.Winner) return false

        beliefDao.supersede(
            verdict.losing.id,
            "superseded",
            verdict.winning.id,
            now,
        )

        // Record the resolution so the revision is auditable even after the
        // belief chain is later compacted or exported.
        contradictionDao.insert(
            ContradictionEntity(
                id = "contra_${UUID.randomUUID()}",
                olderSummaryId = "",
                newerSummaryId = "",
                olderText = verdict.losing.valueJson,
                newerText = verdict.winning.valueJson,
                triggerPhrase = "belief_conflict",
                confidence = verdict.margin.coerceIn(0f, 1f),
                status = "RESOLVED",
                createdAt = now,
                resolvedAt = now,
                olderBeliefId = verdict.losing.id,
                newerBeliefId = verdict.winning.id,
            ),
        )
        return true
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :aura-core:testDebugUnitTest --offline --tests "com.aura.world.BeliefReviserTest"`
Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
git add aura-core/src/main/kotlin/com/aura/world/BeliefReviser.kt aura-core/src/test/kotlin/com/aura/world/BeliefReviserTest.kt
git commit -m "feat(world): apply arbiter verdicts as supersession"
```

---

### Task 8: BeliefConflictProbe on the write path

**Files:**
- Create: `aura-core/src/main/kotlin/com/aura/world/BeliefConflictProbe.kt`
- Modify: `aura-core/src/main/kotlin/com/aura/kg/KnowledgeGraphRepository.kt` (end of `saveGraph`)
- Test: `aura-core/src/test/kotlin/com/aura/world/BeliefConflictProbeTest.kt`

**Interfaces:**
- Consumes: `BeliefDao.active(subject, predicate)`; `EvidenceDao.forBelief(beliefId)`; `BeliefArbiter.arbitrate(...)`; `BeliefReviser.applyVerdict(...)`
- Produces: `BeliefConflictProbe.check(edges: List<EdgeEntity>, now: Long): Int` — number of revisions written.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aura.world

import com.aura.kg.EdgeEntity
import com.aura.kg.KgId
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals

class BeliefConflictProbeTest {

    private val beliefDao = mockk<BeliefDao>(relaxed = true)
    private val evidenceDao = mockk<EvidenceDao>(relaxed = true)
    private val reviser = mockk<BeliefReviser>(relaxed = true)
    private fun probe() = BeliefConflictProbe(beliefDao, evidenceDao, reviser)

    private fun edge(target: String) = EdgeEntity(
        id = "e_$target",
        type = "USES",
        sourceId = KgId.USER_NODE_ID,
        targetId = target,
        confidence = 0.9f,
        createdAt = 1_000L,
        lastReinforced = 2_000L,
    )

    @Test
    fun `same value is not a conflict`() = runBlocking {
        coEvery { beliefDao.active("user", "USES") } returns BeliefEntity(
            id = "b1", subject = "user", predicate = "USES", valueJson = "\"kotlin\"",
        )

        assertEquals(0, probe().check(listOf(edge("kotlin")), now = 5_000L))
        coVerify(exactly = 0) { reviser.applyVerdict(any(), any()) }
    }

    @Test
    fun `different value on the same predicate is a conflict`() = runBlocking {
        coEvery { beliefDao.active("user", "USES") } returns BeliefEntity(
            id = "b1", subject = "user", predicate = "USES", valueJson = "\"kotlin\"",
        )
        coEvery { evidenceDao.forBelief("b1") } returns emptyList()
        coEvery { reviser.applyVerdict(any(), any()) } returns true

        assertEquals(1, probe().check(listOf(edge("rust")), now = 5_000L))
    }

    @Test
    fun `edges not about the user are ignored`() = runBlocking {
        val other = edge("rust").copy(sourceId = "someone_else")

        assertEquals(0, probe().check(listOf(other), now = 5_000L))
        coVerify(exactly = 0) { beliefDao.active(any(), any()) }
    }

    @Test
    fun `the incoming belief losing writes nothing at all`() = runBlocking {
        // The existing belief is well supported and recent; the incoming edge
        // is a single weak signal. Nothing should be written — in particular
        // the candidate must not be superseded, because it was never stored.
        val now = 10_000_000L
        coEvery { beliefDao.active("user", "USES") } returns BeliefEntity(
            id = "b1", subject = "user", predicate = "USES", valueJson = "\"kotlin\"",
        )
        coEvery { evidenceDao.forBelief("b1") } returns (1..4).map {
            EvidenceEntity(
                id = "ev$it",
                beliefId = "b1",
                source = "user_statement",
                summary = "s$it",
                detailJson = """{"turn":"t$it"}""",
                timestamp = now - it,
            )
        }

        assertEquals(0, probe().check(listOf(edge("rust")), now = now))

        coVerify(exactly = 0) { reviser.applyVerdict(any(), any()) }
        coVerify(exactly = 0) { beliefDao.upsert(any()) }
    }

    @Test
    fun `no existing belief means nothing to revise`() = runBlocking {
        coEvery { beliefDao.active(any(), any()) } returns null

        assertEquals(0, probe().check(listOf(edge("rust")), now = 5_000L))
        coVerify(exactly = 0) { reviser.applyVerdict(any(), any()) }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :aura-core:testDebugUnitTest --offline --tests "com.aura.world.BeliefConflictProbeTest"`
Expected: FAIL — `Unresolved reference: BeliefConflictProbe`

- [ ] **Step 3: Write the implementation**

```kotlin
package com.aura.world

import com.aura.kg.EdgeEntity
import com.aura.kg.KgId
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Structural conflict detection on the write path.
 *
 * Runs after every KG save, so it must stay cheap: one indexed DAO lookup per
 * distinct predicate and no model call. Anything requiring semantic judgement
 * ("vegetarian" vs "had steak") is deliberately out of scope here and handled
 * by the dream cycle's adjudication phase.
 */
@Singleton
class BeliefConflictProbe @Inject constructor(
    private val beliefDao: BeliefDao,
    private val evidenceDao: EvidenceDao,
    private val reviser: BeliefReviser,
) {

    /** @return number of revisions written. */
    suspend fun check(edges: List<EdgeEntity>, now: Long = System.currentTimeMillis()): Int {
        var revisions = 0
        for (edge in edges) {
            if (edge.sourceId != KgId.USER_NODE_ID) continue
            val existing = beliefDao.active("user", edge.type) ?: continue
            val incomingValue = JsonPrimitive(edge.targetId).toString()
            if (existing.valueJson == incomingValue) continue

            val candidate = BeliefEntity(
                id = UUID.randomUUID().toString(),
                subject = "user",
                predicate = edge.type,
                valueJson = incomingValue,
                confidence = edge.confidence,
                validFrom = now,
                createdAt = now,
                updatedAt = now,
                lastVerifiedAt = now,
            )
            val incomingEvidence = listOf(
                EvidenceEntity(
                    id = UUID.randomUUID().toString(),
                    beliefId = candidate.id,
                    source = "kg_edge",
                    summary = "${edge.type} → ${edge.targetId}",
                    timestamp = edge.lastReinforced,
                    confidence = edge.confidence,
                ),
            )
            val verdict = BeliefArbiter.arbitrate(
                BeliefSide(candidate, incomingEvidence),
                BeliefSide(existing, evidenceDao.forBelief(existing.id)),
                now,
            )
            // Only act when the INCOMING belief wins. If the existing belief
            // wins, or the sides are too close, the candidate was never
            // persisted — superseding it would mark a row that does not exist
            // and file a contradiction pointing at a phantom belief.
            if (verdict is Verdict.Winner && verdict.winning.id == candidate.id) {
                beliefDao.upsert(candidate)
                evidenceDao.upsert(incomingEvidence.first())
                if (reviser.applyVerdict(verdict, now)) revisions++
            }
        }
        return revisions
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :aura-core:testDebugUnitTest --offline --tests "com.aura.world.BeliefConflictProbeTest"`
Expected: PASS (4 tests)

- [ ] **Step 5: Call the probe from saveGraph**

In `KnowledgeGraphRepository`, add a nullable constructor parameter (nullable so existing constructions keep compiling):

```kotlin
    private val beliefConflictProbe: com.aura.world.BeliefConflictProbe? = null,
```

At the end of `saveGraph`, after the `for (edge in edges)` loop:

```kotlin
        // Structural belief conflicts are cheap enough to resolve inline — a
        // single indexed lookup per predicate, no model call. Best-effort:
        // never fail a KG save because revision had a problem.
        runCatching { beliefConflictProbe?.check(edges.map { it.toEntity() }) }
            .onFailure { android.util.Log.w("KgRepository", "belief probe failed: ${it.message}") }
```

- [ ] **Step 6: Run the full gate and commit**

```bash
./gradlew :aura-core:testDebugUnitTest :app:testDebugUnitTest --offline
git add aura-core/src/main/kotlin/com/aura/world/BeliefConflictProbe.kt aura-core/src/main/kotlin/com/aura/kg/KnowledgeGraphRepository.kt aura-core/src/test/kotlin/com/aura/world/BeliefConflictProbeTest.kt
git commit -m "feat(world): revise conflicting beliefs on the write path"
```

---

### Task 9: Surface the supersession chain

**Files:**
- Modify: `app/src/main/kotlin/com/aura/ui/evolution/BeliefsViewModel.kt`
- Modify: `app/src/main/kotlin/com/aura/ui/evolution/BeliefsScreen.kt`
- Modify: `aura-core/src/main/kotlin/com/aura/tools/QueryWorldModelTool.kt`
- Test: `app/src/test/kotlin/com/aura/ui/evolution/BeliefsHistoryTest.kt`

**Interfaces:**
- Consumes: `BeliefDao.history(subject: kotlin.String, predicate: kotlin.String): List<BeliefEntity>`
- Produces: `BeliefsUiState.history: Map<String, List<BeliefEntity>>` keyed by belief id.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aura.ui.evolution

import com.aura.world.BeliefDao
import com.aura.world.BeliefEntity
import com.aura.world.EvidenceDao
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class BeliefsHistoryTest {

    private val dispatcher = StandardTestDispatcher()
    private val beliefDao = mockk<BeliefDao>(relaxed = true)
    private val evidenceDao = mockk<EvidenceDao>(relaxed = true)

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `loads the supersession chain for an active belief`() = runTest(dispatcher) {
        val active = BeliefEntity(id = "b2", subject = "user", predicate = "USES", valueJson = "\"rust\"")
        val superseded = BeliefEntity(
            id = "b1", subject = "user", predicate = "USES", valueJson = "\"kotlin\"",
            status = "superseded", supersededBy = "b2",
        )
        coEvery { beliefDao.allActive(any()) } returns listOf(active)
        coEvery { beliefDao.history("user", "USES") } returns listOf(active, superseded)

        val vm = BeliefsViewModel(beliefDao, evidenceDao)
        advanceUntilIdle()

        // "I used to think kotlin" is exactly this row being present.
        assertEquals(2, vm.state.value.history["b2"]?.size)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --offline --tests "com.aura.ui.evolution.BeliefsHistoryTest"`
Expected: FAIL — `state.history` unresolved.

- [ ] **Step 3: Load history in the ViewModel**

Add the field to `BeliefsUiState` (introduced in Task 4):

```kotlin
    /** Full supersession chain per active belief, newest first. */
    val history: Map<String, List<BeliefEntity>> = emptyMap(),
```

In `load()`, alongside the evidence map, and include it in the assignment:

```kotlin
        val historyByBelief = loaded.associate { belief ->
            belief.id to runCatching {
                beliefDao.history(belief.subject, belief.predicate)
            }.getOrDefault(emptyList())
        }
        _state.value = BeliefsUiState(
            beliefs = loaded,
            evidence = evidenceByBelief,
            history = historyByBelief,
        )
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --offline --tests "com.aura.ui.evolution.BeliefsHistoryTest"`
Expected: PASS

- [ ] **Step 5: Render the chain**

In `BeliefsScreen.kt`, after the evidence lines added in Task 4:

```kotlin
                val chain = state.history[belief.id].orEmpty().filter { it.status == "superseded" }
                if (chain.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    chain.take(3).forEach { old ->
                        Text(
                            text = "previously: ${old.valueJson}",
                            style = MaterialTheme.typography.labelSmall,
                            color = AuraThemeTokens.colors.textSecondary,
                        )
                    }
                }
```

- [ ] **Step 6: Return history from the tool**

In `QueryWorldModelTool`, where active beliefs are formatted, append for each belief:

```kotlin
                val superseded = beliefDao.history(belief.subject, belief.predicate)
                    .filter { it.status == "superseded" }
                if (superseded.isNotEmpty()) {
                    append(" (previously: ")
                    append(superseded.joinToString(", ") { it.valueJson })
                    append(")")
                }
```

- [ ] **Step 7: Run the full gate and commit**

```bash
./gradlew :aura-core:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug --offline
git add app/src/main/kotlin/com/aura/ui/ aura-core/src/main/kotlin/com/aura/tools/QueryWorldModelTool.kt app/src/test/kotlin/com/aura/ui/evolution/BeliefsHistoryTest.kt
git commit -m "feat(ui): show belief supersession history"
```

---

## Slice 3 — Adjudication and measurement

### Task 10: LLM adjudication phase

**Files:**
- Create: `aura-core/src/main/kotlin/com/aura/world/SemanticConflictAdjudicator.kt`
- Modify: `aura-core/src/main/kotlin/com/aura/dream/DreamConsolidator.kt` (Phase 11)
- Modify: `aura-core/src/main/kotlin/com/aura/dream/DreamCycleReport.kt`
- Test: `aura-core/src/test/kotlin/com/aura/world/SemanticConflictAdjudicatorTest.kt`

**Interfaces:**
- Consumes: `Brain.stream(model, messages, tools, options)`; `BeliefDao.allActive(limit)`; `BeliefReviser.applyVerdict(...)`
- Produces: `SemanticConflictAdjudicator.adjudicate(model: String, now: Long): Int`; `DreamCycleReport.beliefsRevised: Int`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aura.world

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SemanticConflictAdjudicatorTest {

    @Test
    fun `parses a conflict verdict from model json`() {
        val json = """{"conflict":true,"winner":"b2","reason":"more recent and repeated"}"""
        val parsed = SemanticConflictAdjudicator.parseVerdict(json)
        assertEquals("b2", parsed?.winnerId)
    }

    @Test
    fun `parses no-conflict as null`() {
        val json = """{"conflict":false}"""
        assertNull(SemanticConflictAdjudicator.parseVerdict(json))
    }

    @Test
    fun `malformed model output is null rather than throwing`() {
        // A dream phase must never crash the cycle on bad model output.
        assertNull(SemanticConflictAdjudicator.parseVerdict("not json at all"))
        assertNull(SemanticConflictAdjudicator.parseVerdict(""))
    }

    @Test
    fun `a winner naming an unknown belief is rejected`() {
        val json = """{"conflict":true,"winner":"","reason":"x"}"""
        assertNull(SemanticConflictAdjudicator.parseVerdict(json))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :aura-core:testDebugUnitTest --offline --tests "com.aura.world.SemanticConflictAdjudicatorTest"`
Expected: FAIL — `Unresolved reference: SemanticConflictAdjudicator`

- [ ] **Step 3: Write the parser and adjudicator**

```kotlin
package com.aura.world

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Adjudicates belief conflicts the structural probe cannot see — two beliefs
 * whose values differ semantically rather than literally.
 *
 * Runs only in the dream cycle. The parser is a companion-level pure function
 * so the failure modes that matter (malformed model output must not crash the
 * cycle) are testable without a provider.
 */
object SemanticConflictAdjudicator {

    private val json = Json { ignoreUnknownKeys = true }

    data class ParsedVerdict(val winnerId: String, val reason: String)

    /** Null when there is no conflict, or the output cannot be trusted. */
    fun parseVerdict(raw: String): ParsedVerdict? = runCatching {
        val obj = json.parseToJsonElement(raw.trim()).jsonObject
        val conflict = obj["conflict"]?.jsonPrimitive?.booleanOrNull ?: false
        if (!conflict) return null
        val winner = obj["winner"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (winner.isBlank()) return null
        ParsedVerdict(winner, obj["reason"]?.jsonPrimitive?.contentOrNull.orEmpty())
    }.getOrNull()

    /** System prompt for the adjudication call. */
    fun systemPrompt(): String = buildString {
        appendLine("You compare two stated beliefs about a person and decide whether they genuinely conflict.")
        appendLine("Two beliefs conflict only if both cannot be true of the same person at the same time.")
        appendLine("Different topics do not conflict. A refinement does not conflict.")
        appendLine("Return ONLY JSON: {\"conflict\":true|false,\"winner\":\"<belief id>\",\"reason\":\"<short>\"}")
        appendLine("If there is no conflict return {\"conflict\":false}.")
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :aura-core:testDebugUnitTest --offline --tests "com.aura.world.SemanticConflictAdjudicatorTest"`
Expected: PASS (4 tests)

- [ ] **Step 5: Add the report field and Phase 11**

In `DreamCycleReport.kt`, after `beliefsPromoted`:

```kotlin
    // Phase 11: semantic belief conflicts resolved
    val beliefsRevised: Int = 0,
```

Add `private val semanticAdjudicator: SemanticConflictAdjudicatorRunner? = null,`
to the `DreamConsolidator` constructor, then add this block in `runCycle`
immediately after the Phase 10 block:

```kotlin
            // 11. ADJUDICATE -- resolve belief conflicts the structural probe
            //     cannot see, e.g. values that differ semantically rather
            //     than literally. Model-backed, so dream-cycle only.
            try {
                val revised = semanticAdjudicator?.run() ?: 0
                report = report.copy(beliefsRevised = revised)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                try { android.util.Log.w("DreamConsolidator", "adjudicateBeliefs: ${t.message}") } catch (_: RuntimeException) {}
            }
```

`SemanticConflictAdjudicatorRunner` is the injectable wrapper that pairs active
beliefs, calls the model with `SemanticConflictAdjudicator.systemPrompt()`,
parses with `parseVerdict`, and forwards winners to `BeliefReviser`. Its
`run(): Int` returns the number of revisions written.

- [ ] **Step 6: Run the full gate and commit**

```bash
./gradlew :aura-core:testDebugUnitTest :app:testDebugUnitTest --offline
git add aura-core/src/main/kotlin/com/aura/world/SemanticConflictAdjudicator.kt aura-core/src/main/kotlin/com/aura/dream/ aura-core/src/test/kotlin/com/aura/world/SemanticConflictAdjudicatorTest.kt
git commit -m "feat(world): LLM adjudication for semantic belief conflicts"
```

---

### Task 11: Convergence eval

This is the artifact that makes the whole design falsifiable: a measured curve, not an assertion that revision works.

**Files:**
- Test: `aura-core/src/test/kotlin/com/aura/world/BeliefConvergenceEvalTest.kt`

**Interfaces:**
- Consumes: `BeliefArbiter.arbitrate(...)`, `BeliefSide`, `Verdict` (Task 5)

- [ ] **Step 1: Write the eval**

```kotlin
package com.aura.world

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Convergence eval: replay fact sequences that contradict over time and measure
 * whether the arbiter settles on the truth, and how much evidence it needs.
 *
 * This is the measurable claim the belief-revision design exists to support.
 * It is deliberately a pure-arbiter harness — no Room, no provider — so the
 * curve reflects the scoring rule rather than storage or model noise.
 */
class BeliefConvergenceEvalTest {

    private val day = 86_400_000L
    private val now = 400 * day

    private fun belief(id: String, value: String) =
        BeliefEntity(id = id, subject = "user", predicate = "DIET", valueJson = "\"$value\"")

    private fun evidence(beliefId: String, ageDays: Long, n: Int) = (1..n).map {
        EvidenceEntity(
            id = "$beliefId-$it",
            beliefId = beliefId,
            source = "user_statement",
            summary = "s$it",
            detailJson = """{"turn":"$beliefId-$it"}""",
            timestamp = now - ageDays * day - it,
        )
    }

    /** Turns of corroboration the new belief needs before it wins. */
    private fun turnsToConverge(oldAgeDays: Long, oldN: Int, newAgeDays: Long): Int {
        for (n in 1..10) {
            val verdict = BeliefArbiter.arbitrate(
                BeliefSide(belief("new", "omnivore"), evidence("new", newAgeDays, n)),
                BeliefSide(belief("old", "vegetarian"), evidence("old", oldAgeDays, oldN)),
                now,
            )
            if (verdict is Verdict.Winner && verdict.winning.id == "new") return n
        }
        return -1
    }

    @Test
    fun `a recent repeated change overturns a stale belief`() {
        // Stated 200 days ago twice, contradicted twice this week.
        assertTrue(turnsToConverge(oldAgeDays = 200, oldN = 2, newAgeDays = 2) in 1..2)
    }

    @Test
    fun `a single stale remark cannot overturn a fresh well-supported belief`() {
        val verdict = BeliefArbiter.arbitrate(
            BeliefSide(belief("new", "omnivore"), evidence("new", ageDays = 300, n = 1)),
            BeliefSide(belief("old", "vegetarian"), evidence("old", ageDays = 2, n = 4)),
            now,
        )
        assertTrue(verdict is Verdict.Winner && verdict.winning.id == "old")
    }

    @Test
    fun `convergence is monotonic in corroboration`() {
        // More supporting turns must never make the new belief less likely to
        // win. A non-monotonic scoring rule would be a bug in the weights.
        val needed = turnsToConverge(oldAgeDays = 30, oldN = 3, newAgeDays = 10)
        assertTrue(needed > 0, "new belief never converged")
        for (n in needed..10) {
            val verdict = BeliefArbiter.arbitrate(
                BeliefSide(belief("new", "omnivore"), evidence("new", 10, n)),
                BeliefSide(belief("old", "vegetarian"), evidence("old", 30, 3)),
                now,
            )
            assertTrue(verdict is Verdict.Winner && verdict.winning.id == "new", "regressed at n=$n")
        }
    }

    @Test
    fun `ambiguous evidence refuses to decide`() {
        assertEquals(
            Verdict.TooClose,
            BeliefArbiter.arbitrate(
                BeliefSide(belief("new", "omnivore"), evidence("new", 10, 2)),
                BeliefSide(belief("old", "vegetarian"), evidence("old", 10, 2)),
                now,
            ),
        )
    }
}
```

- [ ] **Step 2: Run the eval**

Run: `./gradlew :aura-core:testDebugUnitTest --offline --tests "com.aura.world.BeliefConvergenceEvalTest"`
Expected: PASS. If a case fails, **tune the weights in `BeliefArbiter`, not the assertions** — the eval encodes the intended behaviour and is the reason the weights exist.

- [ ] **Step 3: Run the full gate and commit**

```bash
./gradlew :aura-core:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug --offline
git add aura-core/src/test/kotlin/com/aura/world/BeliefConvergenceEvalTest.kt
git commit -m "test(world): belief convergence eval"
```

---

## Known constraint: predicates are EdgeType values

`EdgeType` is a closed enum (`RELATES_TO`, `IS_A`, `USES`, `KNOWS`,
`WORKS_ON`, `LOCATED_AT`, …) with no preference/attitude relation. Because
`BeliefEntity.predicate` is populated from `edge.type`, beliefs inherit that
vocabulary and are therefore coarser than the design's "user prefers X"
phrasing suggests — "user USES kotlin" rather than "user PREFERS kotlin".

This is acceptable for all three slices: conflict detection keys on
(subject, predicate) equality, which works regardless of how expressive the
predicate is. If belief phrasing later needs to be finer, the change is to add
enum values to `EdgeType` and the allowed-types list in the extractor prompt —
not to widen `predicate` to free text, which would break the equality-based
conflict check.

## Notes for the implementer

- **DI is not wired by these tasks.** `BeliefPromoter`, `BeliefReviser` and `BeliefConflictProbe` are `@Singleton` with `@Inject` constructors, so Hilt resolves them from existing DAO providers. The nullable constructor params on `DreamConsolidator` and `KnowledgeGraphRepository` are deliberate — they keep every existing test construction compiling.
- **`androidTest` cannot be run here.** No device or emulator is attached, and CI runs unit tests only. Task 6's migration test compiles but is unverified; say so in the commit rather than implying it passed.
- **If a promotion turns out to be noisy in practice**, the tunable is a `reinforcementCount` column on `EdgeEntity` (design spec §5), not a loosening of the arbiter margin.
