# COMPREHENSIVE AUDIT — Aura Android v0.56.1 (Round 14)

**Date:** 2026-08-03  
**Auditor:** Michaela Osbourne (elite multidisciplinary team)  
**Branch:** feat/tier-1-friction  
**Head:** c17b614d  

---

## PHASE 1 — SYSTEM UNDERSTANDING

### Architecture

Aura Android is a native Kotlin/Compose personal AI assistant — a port of the Python Aura desktop app to Android. It is a **single-user, offline-first, privacy-focused** application with no server-side component. All data lives on-device in 11 Room databases.

**Module structure:**
- `aura-core` — domain layer: agents, providers, tools, memory, evolution, dream, proactive, MCP, capabilities, integrations
- `app` — presentation layer: Compose UI, ViewModels, navigation, widgets, theme

**Key numbers:**
- 553 main .kt files, 80,969 LOC main
- 296 test .kt files, 34,962 LOC test
- 1,759 tests, 0 failures
- 69 tools, 17 LLM providers, 7 specialists
- 11 Room databases, 49 entities, 37 backup data classes
- 14 migrations (MemoryDB v1→v15), 58 ForeignKey references
- APK: 36.7 MB (debug), 34 DEX files, 8 font files (2.8 MB)

### Design Philosophy

The app is built on a **ReAct agentic loop** with memory augmentation. Key design decisions:
- Dark-first Material 3 with custom Aura design tokens (teal accent, Inter/Fraunces/JetBrains Mono)
- Provider-agnostic — 17 LLM providers via a single `Provider` interface
- Memory SOTA pipeline: BM25 → RRF 6-signal → cross-encoder reranker → recall cache
- Multi-agent system with per-agent memory scopes, personality profiles, delegation, council
- Evolution system: self-improvement proposals with human approval gates
- Extended thinking always on (32K budget, provider-native for Anthropic/OpenAI/Gemini/DeepSeek/Ollama)
- Thinking blocks parsed from SSE and rendered as collapsible UI (NEW in v0.56)

### State Flow

```
User input → ChatSendController → MemoryAugmentedAgenticLoop.run()
  → Brain.stream() → Provider.chat() → SSE stream
    → ProviderChunk(text/thinking/toolCall/finishReason)
      → BrainChunk(Text/Thinking/ToolCallStart/Delta/End/Finished)
        → AgentEvent(TextDelta/ThinkingDelta/ToolCallStart/End/Result/Done)
          → ChatUiState(streamingThinking / conversation / inFlightToolCalls)
            → Compose UI (MessageBubble / ThinkingBlock / ToolCallBadge)
```

### Data Flow

```
Conversation → Room (ConversationDatabase v6)
Memory → Room (MemoryDatabase v15) + Cloud embeddings (384-dim)
Beliefs/Events/Opportunities → Room (MemoryDatabase v15)
Dream summaries → Room (DreamConsolidationDatabase v3)
Evolution proposals → Room (EvolutionDatabase v3)
Agent runs → Room (AgentRunDatabase v1)
Tasks/Reminders → Room (TaskDatabase v5)
Proactive events → Room (ProactiveEventDatabase v5)
User profile → Room (UserProfileDatabase v2)
Agents → Room (AgentDatabase v1)
Strategy bandit → Room (StrategyBanditDatabase v1)
Hands → Room (HandDatabase v2)
Backup → JSON file (AuraBackup, SCHEMA_VERSION 11, 37 backup types)
```

### Security Boundaries

- Network security config: cleartext blocked, system CAs only
- SecureDataStore: AES-256-GCM via Android Keystore for API keys, SMTP password, OAuth tokens
- Biometric: BIOMETRIC_STRONG + DEVICE_CREDENTIAL for app lock
- SSRF guards: SsrfGuard + pinnedClient on all HTTP tools (32 references)
- API keys: header-only (no URL params), verified across all 17 providers
- Backup: API keys NOT exported (stored in SecureDataStore, not in backup JSON)

### Build Pipeline

- GitHub Actions CI: logging lint → Gradle test → assembleDebug
- 45-minute timeout, 2-core runner
- Dependencies: Kotlin 1.9.24, AGP 8.2.2, Compose BOM 2024.10.01, Room 2.6.1, Hilt 2.51

---

## PHASE 2-16 — FINDINGS BY SEVERITY

### P0 — Critical (3)

**P0-1. Thinking content not persisted on Turn**  
**File:** `Conversation.kt:173-201`  
**Root cause:** `Turn` data class has no `thinking: String?` field. The agentic loop accumulates thinking in `accumulatedThinking` but never stores it on the Turn when saving the conversation. `streamingThinking` on `ChatUiState` is transient — lost on config change, process death, and history replay.  
**Impact:** User sees thinking blocks during streaming, but when they scroll back through history, the thinking is gone. Claude persists thinking per message.  
**Fix:** Add `val thinking: String? = null` to `Turn`. In the agentic loop, set `thinking = accumulatedThinking.toString().ifBlank { null }` when constructing the final Turn. In ChatSendController, clear `streamingThinking` and store it on the Turn.  
**Effort:** 30 min  
**Risk:** Low — additive field, backward-compatible serialization.

**P0-2. TTS streamBuffer is not thread-safe**  
**File:** `TextToSpeech.kt:113-140`  
**Root cause:** `streamBuffer` is a plain `StringBuilder`. `feed()` is called from the SSE collection coroutine (Dispatchers.IO) while `flushStream()` can be called from the UI thread. Concurrent StringBuilder access can corrupt the buffer or cause IndexOutOfBoundsException.  
**Impact:** Race condition — intermittent crashes or garbled TTS output during streaming voice mode.  
**Fix:** Use `StringBuffer()` (synchronized) or wrap access in a `Mutex`.  
**Effort:** 5 min  
**Risk:** Low — drop-in replacement.

**P0-3. Only 1 @Index annotation across 49 entities**  
**File:** All entity files  
**Root cause:** Room entities have 58 ForeignKey references but only 1 @Index. SQLite automatically creates indexes for primary keys, but NOT for foreign key columns. Every FK-based query (e.g., "get all memories for agent X") does a full table scan.  
**Impact:** O(n) table scans on 10+ frequently queried FK columns. As the database grows (1000+ memories, 500+ conversations), queries slow linearly.  
**Fix:** Add `@Index` on every FK column. Room's `@ForeignKey` even has an `indices` parameter that warns about this. Key entities needing indexes:
  - MemoryEntity: scope, agentScope, category, decayScore
  - ConversationEntity: agentId, deletedAt
  - TaskEntity: status, dueAt
  - ProactiveEventEntity: type, timestamp
  - All creative entities: projectId
  - All evolution entities: proposalId
  - All agent run entities: runId  
**Effort:** 2-3 hours (add indices + migrations)  
**Risk:** Medium — requires Room migrations for each affected database.

### P1 — High (8)

**P1-1. 4 screens still use raw Scaffold instead of AuraScreenShell**  
**Files:** HandsScreen.kt, IdentityEditorScreen.kt, RemindersScreen.kt, TasksScreen.kt  
**Impact:** Inconsistent top bar, padding, and scroll behavior vs the 14 screens using AuraScreenShell.

**P1-2. AuraCard, AuraSectionHeader, AuraListRow have 0 usages**  
**Files:** `AuraCards.kt`, `AuraSectionHeader.kt`, `AuraListRow.kt`  
**Impact:** Shared components built but never adopted. Every screen builds its own inline Card/Row/Header with different padding/radius/border.

**P1-3. 23 hardcoded Color(0x...) values in UI code (outside theme/)**  
**Impact:** Colors that don't respond to theme changes (dark/light). Some are the old purple palette that should be teal.

**P1-4. 500 hardcoded dp values in screens**  
**Impact:** Spacing rhythm inconsistency. Down from 782 (after Phase 5 ratchet) but still high.

**P1-5. 24 screens with zero test coverage**  
**Impact:** 15,000+ lines of untested UI code. No regression protection for visual or interaction changes.

**P1-6. README says v0.51.2 (versionCode 62), actual is v0.56.1 (versionCode 68)**  
**File:** `README.md`  
**Impact:** Stale documentation. Anyone reading the README gets wrong version, wrong test count, wrong tool count.

**P1-7. Kotlin 1.9.24 / AGP 8.2.2 / Compose BOM 2024.10.01 are outdated**  
**Impact:** Missing Compose performance improvements (lazy list prefetching, text measurement cache), Kotlin 2.0 K2 compiler speed, AGP 8.7 build features. Not blocking but accumulating tech debt.

**P1-8. GlobalScope in AskAuraWidget**  
**File:** `AskAuraWidget.kt:75`  
**Impact:** Coroutine launched on GlobalScope from a BroadcastReceiver — leak risk if the receiver is destroyed before the coroutine completes.  
**Fix:** Use `goAsync()` + a coroutine scope tied to the receiver's lifetime.

### P2 — Medium (10)

**P2-1. 4 runBlocking sites in production code** (excl. runInterruptible which is correct)  
**P2-2. 366 total runCatching — 52 with .onFailure but need verification that all log the throwable**  
**P2-3. DreamConsolidator is 969 lines — god-class candidate**  
**P2-4. SettingsViewModel is 1135 lines — god-class, should be split by domain**  
**P2-5. MemoryScreen is 1096 lines — god-class, should be split by feature section**  
**P2-6. ChatViewModel is 1077 lines — already has 4 controllers but still large**  
**P2-7. Font files total 2.8 MB — could use variable font to reduce APK size**  
**P2-8. 34 DEX files — could benefit from R8 shrinking in release builds**  
**P2-9. No ProGuard/R8 rules for release builds (debug-only distribution)**  
**P2-10. No Crashlytics or remote crash reporting (local CrashLogger only)**

---

## PHASE 17 — FINAL SCORECARD

| Category | Score (0-10) | Notes |
|----------|:---:|-------|
| Architecture | 9 | Clean module separation, Hilt DI, ReAct loop, provider abstraction |
| Code Quality | 8.5 | Well-documented, consistent style, some god-classes |
| Maintainability | 8 | 14 god-classes >500 lines, 24 untested screens |
| Performance | 8 | SOTA memory pipeline, parallel tool exec, recall cache. Missing DB indexes |
| Scalability | 7.5 | 49 entities, 11 DBs, 14 migrations — well-structured but index-starved |
| Security | 8.5 | SSRF guards, SecureDataStore, BIOMETRIC_STRONG, header-only API keys |
| Reliability | 8 | 1759 tests, 0 failures, CI lint gate. TTS race condition, thinking not persisted |
| Developer Experience | 8 | Good docs, CI, lint gate. Stale README, outdated deps |
| User Experience | 8.5 | SOTA chat UI, thinking blocks, teal theme, grouped home/settings |
| Documentation | 7 | architecture.md good, README stale, inline KDoc excellent |
| Testing | 7.5 | 1759 tests but 24 untested screens, no E2E, no mutation testing |
| Accessibility | 6.5 | contentDescription on most icons, no TalkBack audit, no semantic grouping |
| Infrastructure | 7 | CI with lint gate, GitHub Releases. No monitoring, no crash reporting |
| Deployment | 7 | Debug APK only, no R8, no Play Store. Acceptable for personal use |
| **Overall Engineering** | **8.3** | Production-grade core, polish needed on edges |
| **Overall Product** | **8.7** | SOTA feature set: thinking blocks, multi-agent, evolution, dream, proactive |

---

## PHASE 18 — MASTER IMPROVEMENT PLAN

### Immediate (this session)
1. **P0-1: Persist thinking on Turn** — add `thinking` field, wire through loop → Turn → UI (30 min)
2. **P0-2: Fix TTS streamBuffer race** — StringBuffer or Mutex (5 min)
3. **P0-3: Add Room indexes** — @Index on all FK columns + migrations (2-3 hours)

### Short-term (next session)
4. Migrate 4 remaining screens to AuraScreenShell
5. Adopt AuraCard/AuraSectionHeader/AuraListRow in secondary screens
6. Replace 23 hardcoded colors with AuraThemeTokens
7. Update README to v0.56.1

### Medium-term (1-2 sessions)
8. Split god-classes: SettingsViewModel (1135), MemoryScreen (1096), DreamConsolidator (969)
9. Add tests for 24 untested screens (contract tests minimum)
10. Upgrade Kotlin to 2.0, AGP to 8.7, Compose BOM to 2025.x

### Long-term (architectural)
11. R8 release builds with ProGuard rules
12. Variable font to reduce APK size
13. Remote crash reporting (Crashlytics or self-hosted)
14. TalkBack accessibility audit
15. Compose multiplatform for desktop reuse

---

*Subagent reports: ROUND14_AGENT.md, ROUND14_PROVIDERS.md, ROUND14_DATA.md (pending completion)*