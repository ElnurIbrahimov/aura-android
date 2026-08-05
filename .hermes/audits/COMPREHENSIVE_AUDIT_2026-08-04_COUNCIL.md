# COMPREHENSIVE AUDIT — Aura Android
## 18-Phase Engineering Audit | 2026-08-04

**Project**: Aura Android — Personal AI agent app (sideload, single user)  
**Branch**: feat/tier-1-friction  
**HEAD**: fc62c64b  
**Scope**: Full project including The Council feature (7 commits, Phases 1-7)

---

## Mechanical Baseline

| Metric | Value |
|--------|-------|
| Kotlin main files | 572 |
| Kotlin test files | 303 |
| Main LOC | 83,605 |
| Test LOC | 36,139 |
| Room databases | 11 |
| Room entities | 54 |
| Room migrations | 35 |
| Tools | 70 |
| Hilt modules | 17 |
| Composables | 118 |
| ViewModels | 36 |
| Workers | 11 |
| runCatching sites | 281 |
| runBlocking (non-test) | 1 (ToolExecutor — confirmed correct) |
| TODOs | 0 |
| FIXMEs | 0 |
| Test count | 1,822 |

---

## PHASE 1 — SYSTEM UNDERSTANDING

### Architecture
Aura Android is a 2-module Kotlin/Compose app (app + aura-core) with:
- **Hilt DI**: 17 @Module objects, @Singleton scoping, @HiltWorker for background jobs
- **Room**: 11 databases, 54 entities, 35 migrations, all using RoomConfig.builder with debug-only destructive fallback
- **Agentic loop**: MemoryAugmentedAgenticLoop (1114 lines) — 10-step max, ReAct pattern, tool execution via ToolExecutor with withTimeout + runInterruptible
- **Provider layer**: 17 LLM providers via ProviderRegistry, all with keyForAwaiting(), SSE streaming, extended thinking
- **Proactive layer**: DaemonWorker (8-step pipeline) + ProactiveAwarenessEngine (8 checks) + motivation/salience/timing engines
- **Memory**: SOTA pipeline — rewrite → BM25 → RRF 6-signal → cross-encoder reranker → recall cache
- **Multi-agent**: 7 builtin agents with personality profiles, per-agent memory scopes, delegate_to_agent, AgentCouncil
- **The Council** (new): Agent state + forum + orchestrator + mood engine + 5 intervention types + 3 UI screens + run_life_council tool

### Design philosophy
Personal-use, sideload-only, privacy-first. No app store, no regulator, no other users. This drives architectural decisions: local-only data, no telemetry, no crash reporting to external services, aggressive features that Big Tech can't ship.

---

## PHASE 2-3 — CODEBASE + ARCHITECTURE AUDIT

### P0 — Critical

**P0-1: Council entities NOT in backup**  
The 5 new Council entities (AgentStateEntity, AgentRelationshipEntity, AgentObservationEntity, ForumPostEntity, ForumVoteEntity) have no backup types in AuraBackup. On backup→restore, all council mood/relationship/observation/forum data is silently lost. AgentDatabase is in AuraBackup's `agents` field, but only AgentEntity itself — not the new tables.  
**Fix**: Add CouncilStateBackup, CouncilRelationshipBackup, CouncilObservationBackup, ForumPostBackup, ForumVoteBackup to AuraBackup. Bump SCHEMA_VERSION.

### P1 — High

**P1-1: No timeout/budget on CouncilOrchestrator.runSession()**  
The council session runs 2 debate rounds × up to 4 agents = 8 LLM calls with no overall timeout. If the provider is slow, the DaemonWorker could hang for minutes.  
**Fix**: Wrap runSession in withTimeout(120_000L) — 2-minute budget for overnight sessions.

**P1-2: No timeout on DebateRoundUseCase.generateStance()**  
Each individual agent stance generation has no timeout. A single slow provider response blocks the entire debate round.  
**Fix**: Wrap brain.stream() call in withTimeoutOrNull(30_000L) — 30s per agent.

**P1-3: CouncilOrchestrator extracts interventions via keyword heuristics**  
The `extractIntervention()` function uses `when { "break" in lower -> SelfCare(...) }` keyword matching. This is fragile — "take a break" and "break the news" both match.  
**Fix**: Acceptable for v1. Future: use a cheap LLM call to extract structured intervention from stance text. Document the limitation in the code.

**P1-4: ForumEngine has no thread safety on post()**  
ForumEngine.post() uses a Mutex but ForumEngine.vote() does not. Concurrent votes on the same post could race.  
**Fix**: Add mutex.withLock to vote() or use the existing mutex.

### P2 — Medium

**P2-1: God classes still present**  
- MemoryAugmentedAgenticLoop: 1114 lines (down from 1204+ after controller extraction)  
- DreamConsolidator: 911 lines  
- BackupManager: 736 lines  
- MemoryModule: 647 lines (mostly migration definitions — acceptable)  
- UserPreferences: 632 lines (growing with each feature — consider splitting)  

**P2-2: UserPreferences is a monolith**  
532+ lines, 40+ Flow properties, 30+ setters. Each feature adds 3-5 new properties. This will hit the kotlinx.coroutines.flow.combine 5-flow limit again.  
**Fix**: Split into focused preference stores (e.g., CouncilPreferences, CreativePreferences) or use a map-based approach.

**P2-3: CouncilSettingsSection not wired to SettingsScreen**  
The CouncilSettingsSection composable exists but is not included in SettingsScreen's section list. Users can't access the settings from the UI.  
**Fix**: Add CouncilSettingsSection to the SettingsScreen composable list.

**P2-4: DreamLogAndProfileViewModels in one file**  
Two ViewModels (DreamLogViewModel, AgentProfileViewModel) are in a single file. Minor, but inconsistent with the pattern of one-VM-per-file used elsewhere.  
**Fix**: Split into DreamLogViewModel.kt and AgentProfileViewModel.kt.

---

## PHASE 4 — PERFORMANCE

**P2-5: DebateRoundUseCase runs agents sequentially**  
Each agent stance is generated one at a time. For 4 agents × 2 rounds = 8 sequential LLM calls.  
**Fix**: Use coroutineScope { agents.map { async { generateStance(it) } }.awaitAll() } for parallel stance generation within a round. Would reduce wall time by ~4x.

**P2-6: CouncilOrchestrator.runFromFindings processes findings sequentially**  
Top 3 findings are debated one at a time.  
**Fix**: Could parallelize with async, but overnight cost is already bounded. Acceptable for v1.

**P3-1: ForumEngine.recent(100) loads all posts into memory**  
DreamLogGenerator calls forumEngine.recent(100) and filters in Kotlin. For 100 posts this is fine, but if the forum grows, consider SQL-level filtering by timestamp.

---

## PHASE 5 — DATABASE

**P2-7: AgentDatabase v3 has no Room indexes on forum_posts.threadId**  
ForumPostEntity has @Index on threadId, but the migration SQL creates the index. Verified correct. However, ForumVoteEntity has a unique index on (postId, agentId) but no standalone index on postId for tally queries.  
**Fix**: Add @Index("postId") to ForumVoteEntity for faster vote counting. Migration v3→v4 needed if added.

**P3-2: AgentRelationshipEntity stores bidirectional relationships**  
The `between()` query checks both (A,B) and (B,A) ordering. This is correct but means every relationship is stored once with arbitrary ordering. The `forAgent()` query correctly checks both agentAId and agentBId. Acceptable design.

**P3-3: No foreign key indexes on agent_observations.targetId**  
Observations query by targetType but not by targetId. The existing index on (agentId, resolved) is sufficient for the current query patterns.

---

## PHASE 6 — SECURITY

**P2-8: Council debate prompts include private observations**  
Agent observations about the user are injected into LLM prompts. If a provider logs prompts (e.g., for abuse monitoring), private observations could be exposed.  
**Fix**: Acceptable for a personal-use app where the user owns the provider keys. Document in Settings that council debates send observations to the configured LLM provider.

**P3-4: No input validation on CouncilOrchestrator.runSession topic**  
The topic string is passed directly into LLM prompts. No length limit, no sanitization.  
**Fix**: Add topic.take(500) before passing to prompt builder. Already effectively limited by maxTokens on the response.

---

## PHASE 7 — FRONTEND

**P2-9: Council UI not using AuraCard component**  
CouncilScreen renders intervention cards with plain Column instead of the AuraCard composable used by other screens. Inconsistent with the design system.  
**Fix**: Wrap InterventionCard content in AuraCard.

**P2-10: AgentProfileScreen mood/energy bars use raw LinearProgressIndicator**  
Other screens (EmotionDaemonSection) use styled progress bars with AuraThemeTokens colors. AgentProfileScreen uses the same pattern but with different color choices. Acceptable — consistent enough.

**P3-5: DreamLogScreen uses verticalScroll instead of LazyColumn**  
For a potentially long dream log, verticalScroll renders all content eagerly.  
**Fix**: Minor — dream logs are typically <1000 lines. Acceptable for v1.

---

## PHASE 8-9 — API + DEVOPS

**N/A** — Aura Android is a mobile app, not a web service. No REST API, no server infrastructure. CI runs on GitHub Actions with Gradle + lint gate.

**P3-6: CI timeout**  
CI workflow has timeout-minutes set. Previous sessions noted 28+ minute cold builds on GitHub's 2-core runner. Local gate is the real verification.

---

## PHASE 10 — TESTING

**P2-11: No UI tests for Council screens**  
CouncilScreen, DreamLogScreen, AgentProfileScreen have no ViewModel or state-machine tests.  
**Fix**: Add CouncilViewModelTest, DreamLogViewModelTest, AgentProfileViewModelTest in Phase 6 polish.

**P2-12: CouncilOrchestratorTest doesn't test mood-engine interaction**  
The test mocks moodEngine to always allow all agents. No test for the "all agents burned out" path.  
**Fix**: Add test where moodEngine.filterAvailable returns empty list → verify empty CouncilResult.

**P3-7: No integration test for overnight council in DaemonWorker**  
The council step in DaemonWorker is nullable-injected and wrapped in runCatching. No test verifies the end-to-end path: findings → council → interventions → eventBus emit.  
**Fix**: Difficult to test without real LLM. Acceptable — the components are individually tested.

---

## PHASE 11 — AI/ML

**P2-13: DebateRoundUseCase resolveCheapModel picks shortest-named model**  
The heuristic `models.minByOrNull { it.length }` picks the model with the shortest name as "cheapest." This doesn't correlate well with actual cost — "gpt-4o" is shorter than "claude-3-5-haiku-20241022" but more expensive.  
**Fix**: Use the existing resolveCheapModel pattern from DreamConsolidator (first non-MoA provider, first model from listModels). Or use the ModelRoleRouter to route to a designated "background" role model.

**P2-14: No token budget tracking for council sessions**  
Each debate round uses 400 max tokens per agent. With 4 agents × 2 rounds = 3200 output tokens + prompt tokens. No cumulative tracking or user-visible cost indicator.  
**Fix**: Log total tokens used per council session. Acceptable for v1 since overnight cost is bounded.

**P3-8: Intervention extraction is pure keyword matching**  
No LLM involved in translating debate text → concrete intervention. The current heuristic works for obvious cases ("take a break" → SelfCare) but misses nuanced proposals.  
**Fix**: Future — use a structured LLM call with the stance text + intervention types as a schema.

---

## PHASE 12 — DEPENDENCY AUDIT

No new dependencies added by The Council feature. All existing dependencies are maintained and necessary. The Council reuses:
- Room (already present)
- Hilt (already present)
- kotlinx.coroutines (already present)
- MockK (already present, test only)
- Robolectric (already present, test only)

---

## PHASE 13 — CODE QUALITY

| Dimension | Score | Notes |
|-----------|-------|-------|
| Readability | 8/10 | Clear naming, good KDoc on new code |
| Consistency | 7/10 | CouncilSettingsSection not wired; DreamLogAndProfile VMs in one file |
| Maintainability | 8/10 | Clean separation: state → forum → orchestrator → UI → tool |
| Extensibility | 9/10 | Sealed Intervention types, nullable injection pattern, DAO abstraction |
| Complexity | 7/10 | CouncilOrchestrator at 319 lines — approaching complexity limit |
| Documentation | 7/10 | Good KDoc on store/engine classes, missing on UI screens |
| Error handling | 8/10 | All runCatching sites have .onFailure, no silent catches |
| Logging | 8/10 | Consistent Log.w with throwable |
| Configuration | 8/10 | 3 new prefs with defaults, Settings UI section created |
| Reliability | 7/10 | Missing timeout on debate/council, no backup for council data |

---

## PHASE 14 — PRODUCT REVIEW

The Council is genuinely novel:
- No consumer app ships a persistent society of AI agents that debate, vote, and propose interventions
- Emotional evolution (mood decay, relationship drift) creates long-term attachment
- Dream log makes overnight activity tangible and shareable
- Emergency council via tool makes it accessible in-chat
- The "agents conspire about you" angle is shareable and unusual

**Product risks**:
- Agents might produce repetitive interventions if findings are similar day-to-day
- User might find overnight debates creepy if interventions are too prescriptive
- No way to "summon" specific agents for emergency council (auto-selected)

---

## PHASE 16 — PRIORITIZED FINDINGS

| # | Severity | Finding | Impact | Effort |
|---|----------|---------|--------|--------|
| P0-1 | Critical | Council entities not in backup | Data loss on restore | 4h |
| P1-1 | High | No timeout on CouncilOrchestrator | Worker hang | 1h |
| P1-2 | High | No timeout on DebateRoundUseCase | Slow provider blocks round | 1h |
| P1-3 | High | Keyword-based intervention extraction | Wrong intervention type | 4h (LLM) / 0h (document) |
| P1-4 | High | ForumEngine.vote() not thread-safe | Vote race | 0.5h |
| P2-1 | Medium | God classes (1114, 911, 736 lines) | Maintainability | 8h+ |
| P2-2 | Medium | UserPreferences monolith | Combine limit, readability | 4h |
| P2-3 | Medium | CouncilSettingsSection not wired | Users can't access settings | 0.5h |
| P2-5 | Medium | Sequential debate rounds | 4x slower than needed | 1h |
| P2-9 | Medium | Council UI not using AuraCard | Design inconsistency | 0.5h |
| P2-11 | Medium | No UI tests for council screens | Regression risk | 2h |
| P2-13 | Medium | Bad cheap-model heuristic | Wrong model for debates | 1h |
| P2-14 | Medium | No token budget tracking | Invisible cost | 1h |
| P2-4 | Low | Two VMs in one file | Code organization | 0.5h |
| P2-7 | Low | Missing postId index on votes | Slow vote tally | 1h |
| P3-1 | Low | ForumEngine.recent loads all | Memory (100 items OK) | 0.5h |
| P3-5 | Low | DreamLogScreen not Lazy | Rendering (small logs OK) | 0.5h |
| P3-8 | Low | Keyword intervention extraction | Misses nuanced cases | Future |

---

## PHASE 17 — FINAL SCORECARD

| Category | Score | Justification |
|----------|-------|---------------|
| Architecture | 9/10 | Clean module separation, Hilt DI, nullable injection pattern, 11 Room DBs with proper migrations |
| Code Quality | 8.5/10 | Good naming, consistent patterns, .onFailure on all runCatching, 0 TODOs |
| Maintainability | 8/10 | God classes identified but each under 1200 lines; new code well-separated |
| Performance | 8/10 | Missing parallel debate rounds and timeout; otherwise solid |
| Scalability | 8.5/10 | Room migrations proper, nullable injection allows feature removal |
| Security | 8.5/10 | Local-only, no secrets in code, all provider keys in SecureDataStore |
| Reliability | 8/10 | Missing council timeout + backup coverage; all silent catches logged |
| Developer Experience | 8.5/10 | 1822 tests, good test coverage, Gradle gate fast |
| User Experience | 8.5/10 | 3 new screens, design system mostly followed, Settings section created |
| Documentation | 7.5/10 | Good KDoc on domain layer; UI screens need docs |
| Testing | 8/10 | 50+ new tests for council; UI screens untested |
| Accessibility | 7/10 | contentDescription on icons; no TalkBack audit |
| Infrastructure | 8/10 | CI with lint gate, schema export, debug APK builds |
| Deployment | 8/10 | GitHub Releases with APK, versionCode bumping |
| **Overall Engineering** | **8.5/10** | Production-grade with identified gaps |
| **Overall Product** | **9/10** | Genuinely novel feature, emotional attachment, daily-use magic |

---

## PHASE 18 — MASTER IMPROVEMENT PLAN

### Immediate fixes (this session, ~8h)
1. **P0-1**: Add council entities to AuraBackup (SCHEMA_VERSION bump)
2. **P1-1**: Add withTimeout(120s) to CouncilOrchestrator.runSession
3. **P1-2**: Add withTimeoutOrNull(30s) to DebateRoundUseCase.generateStance
4. **P1-4**: Add mutex to ForumEngine.vote()
5. **P2-3**: Wire CouncilSettingsSection to SettingsScreen
6. **P2-5**: Parallelize debate rounds with async+awaitAll

### Short-term (next session, ~6h)
7. **P2-11**: Add CouncilViewModelTest, DreamLogViewModelTest
8. **P2-12**: Add "all agents burned out" test to CouncilOrchestratorTest
9. **P2-13**: Fix resolveCheapModel heuristic in DebateRoundUseCase
10. **P2-9**: Wrap InterventionCard in AuraCard

### Medium-term (future sessions)
11. **P2-2**: Split UserPreferences into focused stores
12. **P1-3**: LLM-based intervention extraction (replace keyword heuristics)
13. **P2-14**: Token budget tracking per council session
14. **P2-1**: Continue god-class decomposition (MemoryAugmentedAgenticLoop → more controllers)

### Long-term
15. **P3-8**: Structured LLM intervention extraction with schema
16. Agent-specific council summoning (user picks which agents debate)
17. Council outcome feedback loop (did the intervention help? feed back into agent observations)