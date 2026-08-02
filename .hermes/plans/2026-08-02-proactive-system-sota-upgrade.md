# Proactive System SOTA Upgrade Plan

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** Upgrade Aura Android's proactive system from 3 basic awareness checks + fixed-timer daemon to a full SOTA proactive intelligence system with motivation scoring, curiosity scanning, idle-time preparation, adaptive timing, salience filtering, and 5 additional awareness checks.

**Architecture:** All new components are @Singleton @Inject, heuristic-first (no LLM cost for checks), LLM reserved for idle-time preparation and outreach generation only. New systems slot into the existing DaemonWorker → ProactiveEventBus pipeline. No Room migrations needed — scoring state stored in DataStore.

**Tech Stack:** Kotlin, Hilt, WorkManager, DataStore, kotlinx.coroutines

**Research sources:**
- Python Aura: ProactiveAwarenessEngine (8 checks), MotivationAccumulator (5-factor scoring + adaptive threshold), CuriosityScanner (KG gaps), SalienceFilter (4-factor), SkillHealthMonitor, TheoryOfMind integration, ProactiveMessages variety
- ProAct (SJTU + Tencent, NeurIPS 2026): idle-time compute to predict future needs and pre-prepare answers (14.8% fewer turns, 11.7% less effort, 28.1% fewer hallucinations)
- ProActor (ACL 2026): timing-aware RL for proactive scheduling — multiple valid timing choices, not a single answer
- ChatGPT Pulse / Google CC: proactive morning briefings from connected apps
- Replika: proactive check-ins "at the right moments" with app integrations
- ProMemAssist (UIST 2025): working memory modeling for timely assistance
- Key research: 52% engagement at workflow boundaries vs 38% mid-task; recovery from interruption costs 10-60 min

---

## Pre-Audit: What Exists vs What's Needed

| Component | Status | Evidence |
|-----------|--------|----------|
| ProactiveAwarenessEngine | EXISTS, 3 checks | `ProactiveAwarenessEngine.kt` — staleness, stuck tasks, relationship gap |
| DaemonWorker | EXISTS | `DaemonWorker.kt:38` — 15min timer, awareness checks + outreach + LLM insight |
| ProactiveEventBus | EXISTS | `ProactiveEventBus.kt:17` — emits DaemonInsight events |
| ProactiveMessageStore | EXISTS | `ProactiveMessageStore.kt:26` — one-shot DataStore delivery |
| ProactivePolicyEngine | EXISTS | `ProactivePolicyEngine.kt:12` — adaptive weight from dismiss/tap counts |
| AgentPresence | EXISTS | `AgentPresence.kt:47` — emotion-based outreach messages |
| MorningBriefBuilder | EXISTS | `MorningBriefBuilder.kt:44` — daily brief |
| CalendarMonitor | EXISTS | `CalendarMonitor.kt:35` — 5-min calendar polling |
| DecayWorker | EXISTS | `DecayWorker.kt:21` — 6h memory decay |
| MotivationAccumulator | DOES NOT EXIST | Not in proactive/ directory. Python has 5-factor scoring |
| CuriosityScanner | DOES NOT EXIST | Not in proactive/ directory. Python has KG gap detection |
| SalienceFilter | DOES NOT EXIST | Not in proactive/ directory. Python has 4-factor filtering |
| IdleTimePreparation | DOES NOT EXIST | ProAct pattern — predict needs, pre-research |
| AdaptiveTimingEngine | DOES NOT EXIST | Learn when user engages vs dismisses |
| ProactiveMessageVariety | DOES NOT EXIST | Python has time-of-day + emotional-state library |
| SkillHealthMonitor | DOES NOT EXIST | Python detects underperforming skills |
| Deadline approaching check | DOES NOT EXIST | Not in ProactiveAwarenessEngine |
| Contradiction alert check | DOES NOT EXIST | Not in ProactiveAwarenessEngine |
| Stress correlation check | DOES NOT EXIST | Not in ProactiveAwarenessEngine |
| Pattern detection check | DOES NOT EXIST | Not in ProactiveAwarenessEngine |
| Priority shift check | DOES NOT EXIST | Not in ProactiveAwarenessEngine |
| ProactiveInteractionEntity | EXISTS | `ProactiveEventEntity.kt:27` — tracks action counts |

---

## Phase 1: MotivationAccumulator (5-factor scoring + adaptive threshold)

**Objective:** Score each potential proactive message on 5 factors. Only deliver when the score exceeds a learned threshold that adapts from user engagement/dismissal. Replaces the current fixed-timer approach.

### Task 1.1: Create MotivationAccumulator

**Files:**
- Create: `aura-core/src/main/kotlin/com/aura/proactive/MotivationAccumulator.kt`
- Test: `aura-core/src/test/kotlin/com/aura/proactive/MotivationAccumulatorTest.kt`

**Implementation:**

```kotlin
@Singleton
class MotivationAccumulator @Inject constructor(
    private val proactiveEventDao: ProactiveEventDao,
    private val userPreferences: UserPreferences,
) {
    data class PotentialMessage(
        val content: String,
        val source: String, // "curiosity", "staleness", "deadline", "daemon", etc.
        val relevanceToUser: Float = 0.5f,    // 0-1
        val timeSinceSimilar: Float = 0.5f,   // 0-1 (0=just sent, 1=long ago)
        val emotionalUrgency: Float = 0.5f,   // 0-1
        val curiosityDrive: Float = 0.5f,     // 0-1
        val userReceptivity: Float = 0.5f,    // 0-1
    )

    data class MotivationScore(
        val score: Float,
        val threshold: Float,
        val shouldDeliver: Boolean,
        val breakdown: String,
    )

    // 5-factor weighted formula
    // relevance 30%, time-since-similar 20%, emotional urgency 20%, curiosity 15%, receptivity 15%
    fun score(message: PotentialMessage): Float {
        return message.relevanceToUser * 0.30f +
               message.timeSinceSimilar * 0.20f +
               message.emotionalUrgency * 0.20f +
               message.curiosityDrive * 0.15f +
               message.userReceptivity * 0.15f
    }

    // Adaptive threshold: engagement lowers it, dismissal raises it
    suspend fun currentThreshold(): Float {
        val baseThreshold = 0.5f
        val recentInteractions = proactiveEventDao.recentInteractions(20)
        if (recentInteractions.isEmpty()) return baseThreshold

        val engaged = recentInteractions.count { it.action == "tapped" || it.action == "acted" }
        val dismissed = recentInteractions.count { it.action == "dismissed" || it.action == "snoozed" }
        val total = recentInteractions.size
        val engagementRatio = engaged.toFloat() / total
        val dismissalRatio = dismissed.toFloat() / total

        // High engagement → lower threshold (more messages)
        // High dismissal → raise threshold (fewer messages)
        return (baseThreshold - engagementRatio * 0.2f + dismissalRatio * 0.2f).coerceIn(0.2f, 0.8f)
    }

    suspend fun evaluate(message: PotentialMessage): MotivationScore {
        val score = score(message)
        val threshold = currentThreshold()
        return MotivationScore(
            score = score,
            threshold = threshold,
            shouldDeliver = score >= threshold,
            breakdown = "score=$score, threshold=$threshold, " +
                "rel=${message.relevanceToUser}, time=${message.timeSinceSimilar}, " +
                "urg=${message.emotionalUrgency}, curio=${message.curiosityDrive}, " +
                "recept=${message.userReceptivity}",
        )
    }
}
```

**Tests:** 8 tests — scoring formula, threshold adaptation (high engagement, high dismissal, no history, mixed), shouldDeliver boundary, breakdown format.

**Commit:** `feat(proactive): motivation accumulator with 5-factor scoring + adaptive threshold`

### Task 1.2: Wire MotivationAccumulator into DaemonWorker

**Files:**
- Modify: `aura-core/src/main/kotlin/com/aura/proactive/DaemonWorker.kt`

**Approach:** Before posting a DaemonInsight event, run it through the MotivationAccumulator. Only emit if `shouldDeliver == true`. Record the interaction outcome (tapped/dismissed) so the threshold adapts.

**Commit:** `fix(proactive): daemon respects motivation threshold before posting`

---

## Phase 2: CuriosityScanner (KG gap detection)

**Objective:** Scan the knowledge graph for isolated nodes, contextless mentions, stale topics, and shallow knowledge. Generate natural-language curiosity questions. Makes the agent curious about the user's world.

### Task 2.1: Create CuriosityScanner

**Files:**
- Create: `aura-core/src/main/kotlin/com/aura/proactive/CuriosityScanner.kt`
- Test: `aura-core/src/test/kotlin/com/aura/proactive/CuriosityScannerTest.kt`

**Implementation:**

```kotlin
@Singleton
class CuriosityScanner @Inject constructor(
    private val kgNodeDao: KgNodeDao,
    private val kgEdgeDao: KgEdgeDao,
    private val memoryDao: MemoryDao,
) {
    data class CuriosityTarget(
        val entityName: String,
        val entityType: String,
        val gapType: GapType,
        val urgency: Float,
        val question: String,
        val context: String,
    )

    enum class GapType {
        ISOLATED,      // < 3 connections
        CONTEXTLESS,   // mentioned but no description
        STALE,         // not mentioned in 14+ days
        SHALLOW,       // important entity with low access count
    }

    suspend fun scan(): List<CuriosityTarget> {
        val targets = mutableListOf<CuriosityTarget>()

        // 1. Isolated nodes: < 3 connections
        runCatching {
            val nodes = kgNodeDao.recent(100)
            for (node in nodes) {
                val edges = kgEdgeDao.edgesForNode(node.id)
                if (edges.size < 3) {
                    targets.add(CuriosityTarget(
                        entityName = node.label,
                        entityType = node.nodeType,
                        gapType = GapType.ISOLATED,
                        urgency = 0.3f,
                        question = "I noticed '$node.label' doesn't connect to much yet. What's its relationship to the rest?",
                        context = "Isolated node with only ${edges.size} connections",
                    ))
                }
            }
        }

        // 2. Contextless: mentioned in memory but no description in KG
        runCatching {
            val memories = memoryDao.recent(50)
            val nodes = kgNodeDao.all().associateBy { it.label.lowercase() }
            for (memory in memories) {
                val words = memory.content.split(Regex("\\s+")).filter { it.length > 4 }
                for (word in words.take(20)) {
                    val node = nodes[word.lowercase()]
                    if (node != null && node.description.isNullOrBlank()) {
                        targets.add(CuriosityTarget(
                            entityName = node.label,
                            entityType = node.nodeType,
                            gapType = GapType.CONTEXTLESS,
                            urgency = 0.4f,
                            question = "You've mentioned '$node.label' but I don't have context. Can you tell me more about it?",
                            context = "Mentioned in memory but no KG description",
                        ))
                        break // one per memory
                    }
                }
            }
        }

        // 3. Stale topics: not mentioned in 14+ days
        runCatching {
            val now = System.currentTimeMillis()
            val cutoff = now - 14L * 24 * 60 * 60 * 1000
            val nodes = kgNodeDao.recent(200)
            for (node in nodes) {
                if (node.updatedAt < cutoff) {
                    targets.add(CuriosityTarget(
                        entityName = node.label,
                        entityType = node.nodeType,
                        gapType = GapType.STALE,
                        urgency = 0.2f,
                        question = "It's been a while since we discussed '$node.label'. Still relevant?",
                        context = "Not mentioned in 14+ days",
                    ))
                }
            }
        }

        // 4. Shallow: important entity (high degree) with low access count
        runCatching {
            val nodes = kgNodeDao.recent(100)
            for (node in nodes) {
                if (node.accessCount < 2) {
                    val edges = kgEdgeDao.edgesForNode(node.id)
                    if (edges.size >= 5) {
                        targets.add(CuriosityTarget(
                            entityName = node.label,
                            entityType = node.nodeType,
                            gapType = GapType.SHALLOW,
                            urgency = 0.3f,
                            question = "'$node.label' keeps coming up but I barely know it. Tell me more?",
                            context = "High connectivity, low access count",
                        ))
                    }
                }
            }
        }

        return targets.distinctBy { it.entityName to it.gapType }
            .sortedByDescending { it.urgency }
            .take(5) // Top 5 only
    }
}
```

**Tests:** 6 tests — isolated nodes, contextless mentions, stale topics, shallow knowledge, dedup, take-5 limit.

**Commit:** `feat(proactive): curiosity scanner — KG gap detection with natural questions`

### Task 2.2: Wire CuriosityScanner into DaemonWorker

**Files:**
- Modify: `aura-core/src/main/kotlin/com/aura/proactive/DaemonWorker.kt`

**Approach:** After awareness checks, run curiosity scan. Post top curiosity target as a DaemonInsight event (if motivation score permits).

**Commit:** `fix(proactive): daemon runs curiosity scan and posts interesting gaps`

---

## Phase 3: SalienceFilter (4-factor event filtering)

**Objective:** Filter proactive findings by recency (25%), relevance (35%), importance (25%), novelty (15%). Only high-salience events reach the user. Prevents noise.

### Task 3.1: Create SalienceFilter

**Files:**
- Create: `aura-core/src/main/kotlin/com/aura/proactive/SalienceFilter.kt`
- Test: `aura-core/src/test/kotlin/com/aura/proactive/SalienceFilterTest.kt`

**Implementation:**

```kotlin
@Singleton
class SalienceFilter @Inject constructor(
    private val proactiveEventDao: ProactiveEventDao,
) {
    data class SalienceWeights(
        val recency: Float = 0.25f,
        val relevance: Float = 0.35f,
        val importance: Float = 0.25f,
        val novelty: Float = 0.15f,
    )

    data class FilteredFinding(
        val finding: ProactiveAwarenessEngine.ProactiveFinding,
        val salience: Float,
        val passed: Boolean,
    )

    private val weights = SalienceWeights()
    private val SALIENCE_THRESHOLD = 0.4f

    suspend fun filter(findings: List<ProactiveAwarenessEngine.ProactiveFinding>): List<FilteredFinding> {
        val recentTypes = proactiveEventDao.recent(30).map { it.type }.toSet()
        return findings.map { finding ->
            val recency = computeRecency(finding.type, recentTypes)
            val relevance = computeRelevance(finding)
            val importance = finding.urgency // urgency maps to importance
            val novelty = if (finding.type !in recentTypes) 1.0f else 0.3f

            val salience = recency * weights.recency +
                           relevance * weights.relevance +
                           importance * weights.importance +
                           novelty * weights.novelty

            FilteredFinding(finding, salience, salience >= SALIENCE_THRESHOLD)
        }
    }

    private fun computeRecency(type: String, recentTypes: Set<String>): Float {
        return if (type in recentTypes) 0.2f else 1.0f
    }

    private fun computeRelevance(finding: ProactiveAwarenessEngine.ProactiveFinding): Float {
        // Findings with action routes are more relevant (user can act)
        return if (finding.actionRoute != null) 0.8f else 0.4f
    }
}
```

**Tests:** 6 tests — recency scoring, relevance scoring, importance, novelty, threshold pass/fail, combined.

**Commit:** `feat(proactive): salience filter — 4-factor event filtering`

### Task 3.2: Wire SalienceFilter into DaemonWorker

**Files:**
- Modify: `aura-core/src/main/kotlin/com/aura/proactive/DaemonWorker.kt`

**Approach:** Before posting awareness findings, run through SalienceFilter. Only post findings that pass.

**Commit:** `fix(proactive): daemon filters findings through salience filter`

---

## Phase 4: 5 additional awareness checks

**Objective:** Add deadline approaching, contradiction alert, stress correlation, pattern detection, and priority shift checks to ProactiveAwarenessEngine.

### Task 4.1: Add 5 checks to ProactiveAwarenessEngine

**Files:**
- Modify: `aura-core/src/main/kotlin/com/aura/proactive/ProactiveAwarenessEngine.kt`
- Test: `aura-core/src/test/kotlin/com/aura/proactive/ProactiveAwarenessEngineTest.kt`

**New checks:**

```kotlin
// Check 4: Deadline approaching — calendar events in next 24h
private suspend fun checkDeadlineApproaching(): List<ProactiveFinding> {
    val events = calendarReadTool.readTodaysEvents()
    val now = System.currentTimeMillis()
    val tomorrow = now + 24L * 60 * 60 * 1000
    val upcoming = events.filter { it.startTime in now..tomorrow }
    if (upcoming.isEmpty()) return emptyList()
    return listOf(ProactiveFinding(
        type = "deadline_approaching",
        title = "${upcoming.size} event(s) in the next 24 hours",
        message = upcoming.joinToString(", ") { it.title },
        urgency = 0.6f,
        actionRoute = "calendar",
    ))
}

// Check 5: Contradiction alert — KG has conflicting relationships
private suspend fun checkContradictions(): List<ProactiveFinding> {
    val nodes = kgNodeDao.recent(100)
    val contradictions = mutableListOf<String>()
    for (node in nodes) {
        val edges = kgEdgeDao.edgesForNode(node.id)
        val relationshipsByTarget = edges.groupBy { it.targetId }
        for ((targetId, rels) in relationshipsByTarget) {
            if (rels.size > 1 && rels.map { it.relationType }.distinct().size > 1) {
                contradictions.add("${node.label} → ${kgNodeDao.byId(targetId)?.label}")
            }
        }
    }
    if (contradictions.isEmpty()) return emptyList()
    return listOf(ProactiveFinding(
        type = "contradiction_alert",
        title = "${contradictions.size} conflicting relationship(s) in knowledge graph",
        message = contradictions.joinToString("; "),
        urgency = 0.5f,
        actionRoute = "graph",
    ))
}

// Check 6: Stress correlation — emotion engine shows high tension
private suspend fun checkStressCorrelation(): List<ProactiveFinding> {
    val snapshot = emotionEngine.snapshot()
    if (snapshot.tension < 0.7f) return emptyList()
    return listOf(ProactiveFinding(
        type = "stress_correlation",
        title = "You seem tense lately",
        message = "Your tension has been high. Want to take a break or talk through what's on your mind?",
        urgency = 0.5f,
        actionRoute = "chat",
    ))
}

// Check 7: Pattern detection — conversation frequency changed significantly
private suspend fun checkConversationPattern(): List<ProactiveFinding> {
    val now = System.currentTimeMillis()
    val weekAgo = now - 7L * 24 * 60 * 60 * 1000
    val twoWeeksAgo = now - 14L * 24 * 60 * 60 * 1000
    val thisWeek = conversationStore.countSince(weekAgo)
    val lastWeek = conversationStore.countSince(twoWeeksAgo) - thisWeek
    if (lastWeek == 0 && thisWeek == 0) return emptyList()
    if (lastWeek == 0) return listOf(ProactiveFinding(
        type = "pattern_alert",
        title = "You're talking more than usual",
        message = "$thisWeek conversations this week vs $lastWeek last week. What's on your mind?",
        urgency = 0.2f,
    ))
    val ratio = thisWeek.toFloat() / lastWeek
    if (ratio < 0.3f) return listOf(ProactiveFinding(
        type = "pattern_alert",
        title = "You've been quiet this week",
        message = "$thisWeek conversations this week vs $lastWeek last week. Everything okay?",
        urgency = 0.3f,
    ))
    return emptyList()
}

// Check 8: Priority shift — task priorities changed since last check
private suspend fun checkPriorityShift(): List<ProactiveFinding> {
    val tasks = taskDao.all()
    val highPriority = tasks.filter { it.priority == "high" && it.status == "pending" }
    if (highPriority.size > 5) return listOf(ProactiveFinding(
        type = "priority_shift",
        title = "${highPriority.size} high-priority tasks pending",
        message = "You have a lot of high-priority tasks. Want to review priorities?",
        urgency = 0.5f,
        actionRoute = "tasks",
    ))
    return emptyList()
}
```

**Tests:** 10 tests — one per check (happy path + empty case for each).

**Commit:** `feat(proactive): 5 additional awareness checks (deadline, contradiction, stress, pattern, priority)`

---

## Phase 5: Idle-Time Preparation (ProAct pattern)

**Objective:** During daemon cycles, predict what the user might need next and pre-research it. When the user opens the app, the answer is ready.

### Task 5.1: Create IdleTimePreparationEngine

**Files:**
- Create: `aura-core/src/main/kotlin/com/aura/proactive/IdleTimePreparationEngine.kt`
- Test: `aura-core/src/test/kotlin/com/aura/proactive/IdleTimePreparationEngineTest.kt`

**Implementation:**

```kotlin
@Singleton
class IdleTimePreparationEngine @Inject constructor(
    private val providerRegistry: ProviderRegistry,
    private val userPreferences: UserPreferences,
    private val conversationStore: ConversationStore,
    private val memoryStore: MemoryStore,
    private val taskDao: TaskDao,
) {
    data class PreparedAnswer(
        val predictedQuestion: String,
        val answer: String,
        val confidence: Float,
        val createdAt: Long,
    )

    private val _prepared = MutableStateFlow<PreparedAnswer?>(null)
    val prepared: StateFlow<PreparedAnswer?> = _prepared

    /**
     * Predict what the user might ask next based on recent conversation,
     * memory, and context. Pre-generate an answer so it's ready when
     * they open the app.
     */
    suspend fun prepare(): PreparedAnswer? {
        val backgroundModel = userPreferences.backgroundModel.first()
        if (backgroundModel.isNullOrBlank()) return null

        val lastConv = conversationStore.recent(1).firstOrNull() ?: return null
        val recentTurns = lastConv.turns.takeLast(4)
        if (recentTurns.size < 2) return null

        val turnsText = recentTurns.joinToString("\n") { turn ->
            val role = if (turn.user != null) "user" else "assistant"
            "$role: ${(turn.user ?: turn.assistant ?: "").take(300)}"
        }

        // Gather context
        val memories = memoryStore.search("", 3).map { it.content.take(100) }
        val tasks = taskDao.all().filter { it.status == "pending" }.take(5).map { it.title }

        val systemPrompt = """
            You are Aura's predictive assistant. Based on the recent conversation
            and context, predict ONE question the user is likely to ask next.
            Then provide a brief, helpful answer (2-3 sentences).
            
            Format:
            QUESTION: <predicted question>
            ANSWER: <brief answer>
            
            Recent conversation:
            $turnsText
            
            Recent memories: ${memories.joinToString("; ")}
            Pending tasks: ${tasks.joinToString("; ")}
        """.trimIndent()

        val messages = listOf(
            ProviderMessage(role = ProviderMessage.Role.system, content = systemPrompt),
            ProviderMessage(role = ProviderMessage.Role.user, content = "What will I likely need next?"),
        )

        return runCatching {
            val chunks = providerRegistry.chat(backgroundModel, messages).toList()
            val response = chunks.joinToString("") { it.text ?: "" }.trim()
            val question = response.substringAfter("QUESTION:").substringBefore("ANSWER:").trim()
            val answer = response.substringAfter("ANSWER:").trim()
            if (question.isBlank() || answer.isBlank()) return null
            val prepared = PreparedAnswer(question, answer, 0.5f, System.currentTimeMillis())
            _prepared.value = prepared
            prepared
        }.onFailure { Log.w("IdlePrep", "prepare failed: ${it.message}") }.getOrNull()
    }

    fun consume(): PreparedAnswer? {
        val value = _prepared.value
        _prepared.value = null
        return value
    }
}
```

**Tests:** 6 tests — prepare with valid data, no conversation, no background model, parse failure, consume clears state, confidence assignment.

**Commit:** `feat(proactive): idle-time preparation engine (ProAct pattern)`

### Task 5.2: Wire IdleTimePreparationEngine into DaemonWorker + ChatViewModel

**Files:**
- Modify: `aura-core/src/main/kotlin/com/aura/proactive/DaemonWorker.kt`
- Modify: `app/src/main/kotlin/com/aura/ui/viewmodel/ChatViewModel.kt`

**Approach:** DaemonWorker calls `prepare()` during each cycle. ChatViewModel checks `consume()` when the user opens the app — if a prepared answer exists, it's shown as a suggestion chip.

**Commit:** `fix(proactive): daemon pre-prepares answers, chat shows prepared suggestions`

---

## Phase 6: Adaptive notification timing

**Objective:** Learn when the user engages vs dismisses proactive messages. Only send during high-engagement windows.

### Task 6.1: Create AdaptiveTimingEngine

**Files:**
- Create: `aura-core/src/main/kotlin/com/aura/proactive/AdaptiveTimingEngine.kt`
- Test: `aura-core/src/test/kotlin/com/aura/proactive/AdaptiveTimingEngineTest.kt`

**Implementation:**

```kotlin
@Singleton
class AdaptiveTimingEngine @Inject constructor(
    private val proactiveEventDao: ProactiveEventDao,
) {
    /**
     * Engagement score by hour of day (0-23).
     * Learned from interaction history: tapped/acted = +1, dismissed/snoozed = -1.
     */
    suspend fun hourlyEngagement(): FloatArray {
        val interactions = proactiveEventDao.recentInteractions(200)
        val scores = FloatArray(24) { 0f }
        for (interaction in interactions) {
            val hour = ((interaction.timestamp / (1000L * 60 * 60)) % 24).toInt()
            when (interaction.action) {
                "tapped", "acted" -> scores[hour] += 1f
                "dismissed", "snoozed" -> scores[hour] -= 0.5f
            }
        }
        // Normalize to 0-1 range
        val max = scores.maxOrNull()?.coerceAtLeast(1f) ?: 1f
        return scores.map { (it / max).coerceIn(0f, 1f) }.toFloatArray()
    }

    /**
     * Is now a good time to send a proactive message?
     * Returns true if the current hour's engagement score is above 0.4.
     */
    suspend fun isGoodTime(): Boolean {
        val scores = hourlyEngagement()
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return scores[hour] >= 0.4f
    }

    /**
     * Best time to send in the next 24 hours.
     * Returns hour (0-23) with highest engagement score.
     */
    suspend fun bestTime(): Int {
        val scores = hourlyEngagement()
        var bestHour = 9 // default to 9am
        var bestScore = 0f
        for (hour in 0..23) {
            if (scores[hour] > bestScore) {
                bestScore = scores[hour]
                bestHour = hour
            }
        }
        return bestHour
    }
}
```

**Tests:** 6 tests — hourly scoring, engagement at specific hours, isGoodTime true/false, bestTime, empty history.

**Commit:** `feat(proactive): adaptive timing engine — learn when user engages`

### Task 6.2: Wire AdaptiveTimingEngine into DaemonWorker

**Files:**
- Modify: `aura-core/src/main/kotlin/com/aura/proactive/DaemonWorker.kt`

**Approach:** Before posting a proactive event, check `isGoodTime()`. If not a good time, defer the event to the next good window. This prevents notifications during low-engagement hours.

**Commit:** `fix(proactive): daemon respects adaptive timing — defers to high-engagement windows`

---

## Phase 7: Proactive message variety library

**Objective:** Replace hardcoded outreach messages with a varied, non-repetitive library organized by time-of-day and emotional state. Each message includes a rationale.

### Task 7.1: Create ProactiveMessageLibrary

**Files:**
- Create: `aura-core/src/main/kotlin/com/aura/proactive/ProactiveMessageLibrary.kt`
- Test: `aura-core/src/test/kotlin/com/aura/proactive/ProactiveMessageLibraryTest.kt`

**Implementation:**

```kotlin
@Singleton
class ProactiveMessageLibrary @Inject constructor() {
    private val recentMessages = ArrayDeque<String>()
    private val MAX_RECENT = 10

    enum class TimeOfDay { MORNING, AFTERNOON, EVENING, NIGHT }

    private val messages = mapOf(
        TimeOfDay.MORNING to listOf(
            "Morning. I've been thinking about what we discussed. Want to pick up where we left off?" to "You had a conversation recently.",
            "Good morning. I noticed something while reviewing your tasks — mind if I share?" to "Pending tasks found.",
            "Hey. I came across something that might help with what you're working on." to "Recent conversation topic detected.",
        ),
        TimeOfDay.AFTERNOON to listOf(
            "Afternoon. How's the day going? I noticed a few things you might want to check." to "Context review complete.",
            "Quick thought — want to hear it?" to "Daemon generated insight.",
            "I found a gap in my knowledge about something you mentioned. Can you fill me in?" to "Curiosity scan found a gap.",
        ),
        TimeOfDay.EVENING to listOf(
            "Evening. Want to wrap up the day with a quick review?" to "End of day context.",
            "I've been reflecting on our conversations today. Here's what I noticed." to "Daily pattern detected.",
            "Before you wind down — one thing caught my attention." to "Salience filter passed.",
        ),
        TimeOfDay.NIGHT to listOf(
            "Late night, huh? I'm here if you need to think something through." to "Late night activity detected.",
            "Can't sleep? Want to talk through what's on your mind?" to "High tension detected.",
        ),
    )

    fun pick(timeOfDay: TimeOfDay, rationale: String): String {
        val candidates = messages[timeOfDay] ?: messages[TimeOfDay.MORNING]!!
        val available = candidates.filter { it.first !in recentMessages }
        val pool = if (available.isEmpty()) { recentMessages.clear(); candidates } else available
        val (message, _) = pool.random()
        recentMessages.addLast(message)
        if (recentMessages.size > MAX_RECENT) recentMessages.removeFirst()
        return "$message\n\n_Why I'm reaching out: $rationale_"
    }

    fun timeOfDay(): TimeOfDay {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..11 -> TimeOfDay.MORNING
            in 12..16 -> TimeOfDay.AFTERNOON
            in 17..20 -> TimeOfDay.EVENING
            else -> TimeOfDay.NIGHT
        }
    }
}
```

**Tests:** 5 tests — time-of-day detection, non-repetition, rationale inclusion, deque overflow, fallback.

**Commit:** `feat(proactive): message variety library with rationale + non-repetition`

### Task 7.2: Wire ProactiveMessageLibrary into AgentPresence + DaemonWorker

**Files:**
- Modify: `aura-core/src/main/kotlin/com/aura/consciousness/AgentPresence.kt`
- Modify: `aura-core/src/main/kotlin/com/aura/proactive/DaemonWorker.kt`

**Approach:** Replace the hardcoded outreach messages in AgentPresence with the library. DaemonWorker uses the library to pick messages with the appropriate rationale (from the finding that triggered it).

**Commit:** `fix(proactive): outreach messages use variety library with rationale`

---

## Phase 8: Integration + wiring

### Task 8.1: Wire all new components into ProactiveModule

**Files:**
- Modify: `aura-core/src/main/kotlin/com/aura/proactive/ProactiveModule.kt`

**Commit:** `fix(proactive): wire MotivationAccumulator, CuriosityScanner, SalienceFilter, IdleTimePreparation, AdaptiveTiming, MessageLibrary into DI`

### Task 8.2: Update DaemonWorker to orchestrate all new components

**Files:**
- Modify: `aura-core/src/main/kotlin/com/aura/proactive/DaemonWorker.kt`

**Approach:** The daemon now runs:
1. Awareness checks (8 checks) → SalienceFilter → MotivationAccumulator → AdaptiveTiming → post if shouldDeliver && isGoodTime
2. CuriosityScanner → MotivationAccumulator → post if shouldDeliver
3. IdleTimePreparationEngine → prepare answer
4. AgentPresence outreach → ProactiveMessageLibrary → MotivationAccumulator → post if shouldDeliver

**Commit:** `feat(proactive): full daemon orchestration — 8 checks + curiosity + salience + motivation + timing + idle prep`

### Task 8.3: Add Settings UI for proactive system

**Files:**
- Modify: `app/src/main/kotlin/com/aura/ui/settings/sections/` (existing section or new ProactiveSection)

**Approach:** Toggle for each new component (motivation scoring, curiosity scan, idle prep, adaptive timing). Show engagement stats (current threshold, best time, engagement-by-hour chart).

**Commit:** `feat(proactive): settings UI for proactive system controls + engagement stats`

---

## Summary Table

| Phase | # Tasks | New Files | Modified Files | New Tests | Dependencies |
|-------|---------|-----------|----------------|-----------|--------------|
| 1 — MotivationAccumulator | 2 | 2 | 1 | 8 | ProactiveEventDao |
| 2 — CuriosityScanner | 2 | 2 | 1 | 6 | KgNodeDao, KgEdgeDao, MemoryDao |
| 3 — SalienceFilter | 2 | 2 | 1 | 6 | ProactiveEventDao, ProactiveAwarenessEngine |
| 4 — 5 awareness checks | 1 | 0 | 1 | 10 | CalendarReadTool, KgNodeDao, EmotionEngine, ConversationStore, TaskDao |
| 5 — Idle-Time Preparation | 2 | 2 | 2 | 6 | ProviderRegistry, UserPreferences, ConversationStore, MemoryStore, TaskDao |
| 6 — Adaptive Timing | 2 | 2 | 1 | 6 | ProactiveEventDao |
| 7 — Message Variety | 2 | 2 | 2 | 5 | None |
| 8 — Integration + UI | 3 | 0 | 3 | 0 | All above |
| **Total** | **16** | **12** | **12** | **47** | |

## Prior plans alignment

No prior proactive system plans exist in `.hermes/plans/`. The proactive system was last touched in the 2026-07-30 session which ported ProactiveAwarenessEngine (3 checks) and AgentPresence from Python Aura. This plan extends that work to full SOTA.