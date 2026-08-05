# AUDIT — Agent Loop / Memory Integration Layer
**Project:** Aura Android (`aura-android-clean`)
**Scope:** `aura-core/src/main/kotlin/com/aura/agent/*` + `aura-core/src/main/kotlin/com/aura/memory/*` + adjacent glue (`providers/ProviderRegistry`, `Brain`, `ToolExecutor`, `IdentityStore`, `UserProfileStore`, `SpecialistRouter`, `AgentCouncil`).
**Auditor:** Hermes subagent
**Date:** 2026-08-03
**Verification method:** every claim cites file:line and was cross-checked against the source on disk and (where possible) the compiled `.class` artifacts in `aura-core/build/intermediates/`.

> Files in the brief that do **not** exist as separate files: `agent/ConversationStore.kt` is real (428 LOC); `agent/ConversationCompactor.kt` is real (255 LOC); `agent/SpecialistRouter.kt` is real (167 LOC). The brief's `providers/BrainStreamBuilder.kt`, `memory/MemoryReranker.kt`, and `memory/QueryRewriter.kt` were found at the actual paths (Brain in agent/, MemoryReranker and QueryRewriter in memory/) and audited.

---

## Top‑5 critical findings (one‑paragraph summary)

The agent loop reads `brain.resolvedIdentity()` on **every step** (5–10 times per turn) — each call hits DataStore + a `Dispatchers.IO` file existence check + an AssetManager read on the legacy-file branch, so a 5-step run does 5–10 redundant identity resolves instead of caching one (BUG‑01). `MemoryAugmentedAgenticLoop.extractProfileFromText` is **defined at file scope with the wrong indentation** (line 1089) and only compiles because Kotlin ignores whitespace — it is a private class member by accident, and the function is one of two pieces of "post-turn user-profile learning" with **no test coverage** (BUG‑02). The failover inner loop (line 760) `throw CancellationException("failover")` aborts the upstream provider flow and the catch in line 768 propagates a **fresh `CancellationException` to the parent `coroutineScope`**, which silently terminates the whole `run()` flow instead of yielding control back to `stream@ while (true)` (BUG‑03). `LlmWriteGate` is constructed fresh inside the agent loop on every turn (line 950) with **no timeout** on the LLM classification call, so a slow provider can block the post-turn auto-store for minutes and the agent has no way to time out (BUG‑04). Finally, `MemoryStore.store()` — the public function called by the agentic loop's auto-store path at line 958 — does **no** exact-insert or semantic dedup, so the documented "I prefer dark mode stored 3 times → deduped" promise in `maybeStore` (line 35) is **bypassed** by the agentic loop, leading to duplicate rows that skew RRF ranking and pollute the morning brief (BUG‑05).

---

## Full findings

### BUG‑01 — `Brain.resolvedIdentity()` re-read on every loop step (perf)

`aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:612`
```kotlin
brain.resolvedIdentity().ifBlank { null },
```
The expression is inside `buildList { ... }` which is inside `while (!finished && step < maxSteps)`. With `maxSteps=10` and the agentic loop doing 5–10 steps per user turn, the call is made 5–10 times per turn, each one going through:

- `Brain.resolvedIdentity()` → `IdentityStore.readCurrent()` (line 43 of Brain.kt)
- → `userPreferences.customIdentity.first()` — `Flow.first()` is a DataStore suspension that registers a collector on every call (line 33 of IdentityStore.kt)
- → on cache miss, `withContext(Dispatchers.IO) { overrideFile.exists() && overrideFile.length() > 0L }` — a file I/O hop
- → on cache miss again, `withContext(Dispatchers.IO) { readAsset() }` — `context.assets.open("SOUL.md").bufferedReader().use { it.readText() }` — full file read of the bundled identity.

**Fix:** cache the resolved identity at the top of `run()` next to `cachedPersonality` (line 587 already does this for the *agent's* personality but not for the system identity). Suggested code:

```kotlin
val cachedIdentity = runCatching { brain.resolvedIdentity() }
    .onFailure { android.util.Log.w("AgenticLoop", "identity load failed: ${it.message}", it) }
    .getOrDefault("")
// ...
brain.resolvedIdentity().ifBlank { null },  // ← replace with cachedIdentity.ifBlank { null }
```

**Test gap:** no test currently exercises multi-step runs with `resolvedIdentity` to catch this. A timing-instrumented test would reveal 5x DataStore round-trips per turn.

---

### BUG‑02 — `extractProfileFromText` defined at file scope with misleading indentation (structure / risk)

`aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:1088-1102`
```kotlin
    /** Lightweight regex-based profile extraction. Updates name, traits, and facts. */
private suspend fun extractProfileFromText(text: String) {
    val facts = mutableListOf<String>()
    Regex(...).find(text)?.groupValues?.getOrNull(1)?.let { name ->
        userProfileStore.update(name = name.trim())   // ← uses class member
    }
    ...
}
```

The `private` keyword is flush left, the function body is indented 8 spaces, and the comment header is indented 4 spaces. The class `MemoryAugmentedAgenticLoop` doesn't close until line 1175, so the function **is** a private member. It compiles (verified against the generated `MemoryAugmentedAgenticLoop$extractProfileFromText$1.class` in `aura-core/build/intermediates/aar_main_jar/debug/classes.jar`). But the indentation makes it look like a file-level extension, and any future "refactor" that adds a closing brace at the wrong place will silently relocate the function out of the class. There is **no test** that exercises the regex capture paths (positive or negative cases) — only a comment in `AgentIncognitoTest.kt:24` saying it would be tested, but no test body implements it.

**Why it's a real bug:** the function is one of two places in the app that does post-turn profile learning, and a regression that breaks name extraction (`userProfileStore.update(name = "...")`) would silently degrade personalization. There is also no audit of the regexes themselves: the name regex (`(?:my name is|i'm|i am|call me)\s+([A-Z][a-z]+(?:\s+[A-Z][a-z]+)?)`) will extract a proper noun from `"I am the walrus"` only if "Walrus" is capitalized — fine — but `"call me later"` won't match because "later" doesn't start with an uppercase letter. Acceptable. But `"I'm Vincent"` will fail to match because the regex requires `call me` to be followed by whitespace and an uppercase, while `i'm` requires the apostrophe form. Test would catch this.

**Fix:** (1) Fix indentation so the function visually lives inside the class. (2) Add `MemoryAugmentedAgenticLoopTest.extractsNameFromImVincent` (positive), `extractsNoNameFromThanksForHelp` (negative), `extractsLivesInFromILiveInBaku` (positive).

---

### BUG‑03 — Failover `CancellationException` may escape the loop's `coroutineScope` (concurrency)

`aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:760, 768-770`
```kotlin
emit(
    AgentEvent.Warning(...)
)
currentModel = nextModel
effectiveModel = nextModel
currentConversation = currentConversation.copy(model = nextModel)
throw kotlinx.coroutines.CancellationException("failover")   // ← line 760
// ...
} catch (e: kotlinx.coroutines.CancellationException) {
    if (e.message == "failover") continue@stream
    throw e                                                    // ← line 770
}
```

The `throw` is inside `brain.stream(currentModel, messages, tools, options).collect { chunk -> ... }`. CancellationException has special meaning in coroutines: it propagates up the call stack to cancel the entire coroutine. The intent is to break out of `.collect` and fall into the `stream@` while loop's next iteration. The catch in line 768 works *most* of the time, but:

1. If `brain.stream` runs in a different coroutine context (some provider impls use `withContext` internally), the exception may be wrapped or replaced before the catch sees it.
2. If the chunk flow is already completing (e.g. on a `Finished` event after a partial stream), `throw` inside the terminal phase can be lost.
3. The existing `MemoryAugmentedAgenticLoopFailoverTest` tests the happy path but **does not** assert that the original `effectiveModel` is preserved when failover finds no alternate (line 747 — `if (nextModel != null)`) — the `else` branch at line 762 emits the error but **also leaves the `stream@ while (true)` loop in an inconsistent state**: `triedModels` was incremented before the check, so if the same retryable error fires twice in a row with no alternate available, the loop will exit the inner while after the second error, then check `if (stepError != null)` (line 774) and `finished = true; break`. The flow is correct, but the test at line 90‑127 only covers the path where a backup *exists*.

**Fix:** replace `throw CancellationException("failover")` with a control-flow return value (e.g. a local `var shouldFailover: Boolean`) and short-circuit the rest of the `when` branch. CancellationException-as-control-flow is a code smell that survives only because Kotlin's catch ordering is well-behaved; the day someone refactors to `flow { ... }` builder it breaks silently.

**Test gap:** no test for the "failover target unavailable" path. Add `MemoryAugmentedAgenticLoopFailoverTest.failoverWithoutAlternate_emitsErrorAndTerminates`.

---

### BUG‑04 — `LlmWriteGate` instantiated fresh every turn with no timeout (latency / hang)

`aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:950-967`
```kotlin
if (memoryEnabled && lastUserMessage.isNotBlank()) {
    runCatching {
        ...
        val gate = LlmWriteGate(
            heuristic = WriteGate(),
            registry = providerRegistry,
            modelId = gateModel,
        )
        val decision = gate.evaluate(lastUserMessage, "user")
        if (decision.shouldStore) { ... memoryStore.store(...) ... }
    }.onFailure { android.util.Log.w(...) }
}
```

`LlmWriteGate.llmEvaluate` (line 60 of `LlmWriteGate.kt`) calls `registry.chat(modelId, messages, ChatOptions(...), emptyList()).collect { ... }` with **no `withTimeout`**. If the provider is slow (e.g. a queued Ollama Cloud request with a 60s+ backlog), the auto-store path blocks the calling coroutine indefinitely. The outer `runCatching` only catches synchronous exceptions, not timeouts. Note that `QueryRewriter` and `MemoryReranker` *do* wrap their calls in `withTimeout(5_000L)` / `withTimeout(10_000L)` — the asymmetry is suspicious.

**Why it's a real bug:** every memorable user turn pays one LLM round-trip. The LlmWriteGate comment block says "The LLM gate only *improves* the decision — it never blocks storage on failure." But a *hang* is not a *failure* — the Future never completes, so the user-facing chat is stuck on a 60-second pause after their answer, with no logs, no spinner, and no way to cancel.

**Fix:** wrap the call:
```kotlin
val decision = kotlinx.coroutines.withTimeoutOrNull(8_000L) {
    gate.evaluate(lastUserMessage, "user")
} ?: heuristic_only_decision  // fallback if LLM gate times out
```

Also: `LlmWriteGate` and its `WriteGate` heuristic should be injected as `@Singleton` so the per-turn allocation is removed. The `runCatching` block also re-creates a new `WriteGate()` every turn, throwing away any state the heuristic might want to keep.

**Test gap:** no test for "slow LLM doesn't block the loop".

---

### BUG‑05 — `MemoryStore.store()` bypasses exact-insert dedup (data integrity)

`aura-core/src/main/kotlin/com/aura/memory/MemoryStore.kt:117-152` (public `store`) vs `30-98` (`maybeStore`).

The doc comment on `maybeStore` (line 35) says:
> **Dedup: skip if an identical memory already exists.** This prevents "I prefer dark mode" from being stored 3 times across 3 conversations…

But the public `store` (line 117) does **no** dedup check. It goes straight to `dao.insert`. The agent loop at line 958 calls `memoryStore.store(content = lastUserMessage, source = "user", ...)` — this is the public `store`, not `maybeStore`. So:

1. If the LLM write gate decides "store" on a memorable turn, but the user repeats themselves across two turns, two duplicate rows are inserted.
2. The semantic dedup (cosine > 0.92) in `maybeStore` is also bypassed.

The function `storeIfAbsent` (line 105) wraps `store` with the exact-match check and a mutex, but it isn't used by the agentic loop. Either:
- The agentic loop should call `storeIfAbsent` (or `maybeStore` for full dedup) instead of `store`, **or**
- `store` should refuse to insert duplicates and return the existing id.

**Why it's a real bug:** the agentic-loop auto-store path violates the documented contract of the memory system. Over time the table fills with duplicates ("user prefers dark mode", "user prefers dark mode.", "I prefer dark mode") which:
- Skew RRF ranking (the same hit appears at multiple positions).
- Pollute `rebuildEmbeddings` with N rows to re-embed where 1 would do.
- Inflate the morning brief.

**Fix:** change `MemoryAugmentedAgenticLoop.kt:958` to call `memoryStore.maybeStore(...)` instead of `memoryStore.store(...)`. `maybeStore` returns the id of either the new or existing matching memory and applies both exact and semantic dedup. The write-gate decision is still applied first, so there's no behavior change for the "should we store?" decision.

---

### BUG‑06 — `filterSearchTools` does not filter by API key (correctness vs. docstring)

`aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:1140-1148`
```kotlin
private suspend fun filterSearchTools(tools: List<ToolDefinition>): List<ToolDefinition> {
    if (providerKeys == null) return tools
    return tools.filter { def -> def.name != "tavily_search" && def.name != "brave_search" }
}
```

The function's KDoc (lines 1129‑1139) claims:
> `tavily_search`: hidden unless Tavily key is configured
> `brave_search`: hidden unless Brave key is configured

But the implementation **always hides them regardless of key state**. The check `if (providerKeys == null) return tools` is about the *injection*, not the key state. So a user who *has* configured a Tavily key (via Settings) will not see the `tavily_search` tool — they'll get only the consolidated `web_search` tool (line 1147). This is **probably intentional** (per the M5 consolidation comment at line 1142), but the KDoc above the function is misleading. Either the KDoc is stale, or the function does the wrong thing.

**Fix:** either rewrite the KDoc to say "always hidden — web_search routes internally", or actually check `providerKeys.isConfigured("tavily")` / `providerKeys.isConfigured("brave")` and unhide when configured.

---

### BUG‑07 — `MemoryStore.touch` is called from inside `query` without transactional safety (data integrity)

`aura-core/src/main/kotlin/com/aura/memory/MemoryStore.kt:305-311`
```kotlin
for ((index, mem) in results.withIndex()) {
    runCatching { touch(mem.id) }
        .onFailure { Log.w("MemoryStore", "touch on recall failed", it) }
    runCatching {
        evolutionHooks?.onMemoryRecalled(mem.id, text, index + 1, null, null, null)
    }.onFailure { Log.w("MemoryStore", "evolutionHooks.onMemoryRecalled failed (non-fatal)", it) }
}
```

`touch` is a Room write (line 153 of `MemoryDao.kt`):
```sql
UPDATE memories SET accessedAt = :now, accessCount = accessCount + 1, decayScore = MIN(1.0, decayScore + 0.1) WHERE id = :id
```

For each result, two independent writes fire — one to `memories`, one to whatever `evolutionHooks.onMemoryRecalled` does. If two concurrent `query()` calls run (one from the agentic loop, one from the memory screen), they each fire independent updates. SQLite serializes the writes, so there's no corruption, but:
- The `accessCount` increment is racy at the application level: if two callers do `touch` on the same id within 1ms, the second `+1` may or may not be observed depending on transaction ordering. The `+= 1` is a SQL-level atomic increment, so this is **fine in practice** but worth noting.
- The `decayScore` write uses `MIN(1.0, decayScore + 0.1)`. If the score is at 0.95, the +0.1 clamps to 1.0. If two concurrent touches happen, the second one is a no-op (still 1.0). **Fine.**

**Real issue:** the fire-and-forget `runCatching` in the loop swallows write failures silently (only logs). The doc on `touch` says "Touch is fire-and-forget; we don't want a failed decay update to break recall" — so this is by design. The bug is that **if `evolutionHooks` throws on memory id X, the next call to `query` will retry it and throw again** because the failure isn't persisted to a dead-letter queue. The log message will be a stream of identical "evolutionHooks.onMemoryRecalled failed" lines for the same memory.

**Fix:** (1) on evolution-hook failure, mark the memory id in a `failedHooks: Set<String>` and skip on subsequent recalls. (2) Document the dedup behavior in the comment.

---

### BUG‑08 — `MemoryEntity.equals` override breaks collection invariants (data integrity)

`aura-core/src/main/kotlin/com/aura/memory/MemoryEntity.kt:36-41`
```kotlin
override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is MemoryEntity) return false
    return id == other.id
}
override fun hashCode(): Int = id.hashCode()
```

The override is for Room (which requires stable equals/hashCode for `@Entity` types). But it makes two `MemoryEntity` instances with the same id but different `content` compare as equal. Anywhere downstream code uses `MemoryEntity` in a `Set` or as a `Map` key, the override is fine (one per id). But if code does:
```kotlin
val byId = memories.associateBy { it.id }  // ← fine
val byContent = memories.associateBy { it.content }  // ← BROKEN: collisions on entities with same content
```

Currently the codebase doesn't do this, but the override is a footgun for future code.

**Real risk:** the `Retrieval.rankCandidates` (line 79 of `Retrieval.kt`) builds `Rankable` per candidate, which wraps `MemoryEntity` and uses `index` (an Int) as the unique key, not the entity itself. **Fine.** But the comment block at the top of `Retrieval.kt:33-36` says:
> The output preserves the [MemoryEntity] objects sorted by fused score descending, truncated to [topK].

The output list could contain duplicate-id entities if the caller passes duplicate-id input (e.g. via `dao.searchByWordsInScopes` returning the same id twice — unlikely with SQL DISTINCT but possible). The override would silently collapse them.

**Fix:** remove the equals override, document the "two entities with same id are considered equal" semantic, and add `if (memories.distinctBy { it.id }.size != memories.size) error("expected unique ids")` at the top of `query` as a defensive check.

---

### BUG‑09 — `Conversation.toMessages` truncates tool results mid-Chinese-character or mid-emoji (correctness, minor)

`aura-core/src/main/kotlin/com/aura/agent/Conversation.kt:84-86`
```kotlin
val resultForModel = if (toolTurn.result.length > maxToolResultChars) {
    toolTurn.result.take(maxToolResultChars) + "\n[... truncated]"
} else {
    toolTurn.result
}
```

`String.take(n)` in Kotlin is *codepoint*-safe for BMP characters but **NOT safe for surrogate pairs** (a high+low surrogate forms one codepoint that encodes an emoji or a CJK extension-B character). `String.take(2000)` on a string ending in an unpaired high surrogate will produce a String that ends with a dangling surrogate, which is **invalid UTF-16** and can crash some downstream parsers (notably JSON serializers that re-encode the string).

**Fix:** use `take` then `String.replace(Regex("[\\uD800-\\uDBFF]$"), "")` to drop a trailing high surrogate, or use `CodePoints` API.

**Test gap:** no test for surrogate-pair truncation.

---

### BUG‑10 — `Conversation.addAssistant` has an off-by-one logic flaw (logic)

`aura-core/src/main/kotlin/com/aura/agent/Conversation.kt:102-107`
```kotlin
fun addAssistant(text: String, agentId: String? = null, thinking: String? = null): Conversation {
    if (turns.isEmpty() || turns.last().assistant != null || turns.last().user == null) {
        return copy(turns = turns + Turn(assistant = text, agentId = agentId, thinking = thinking), updatedAt = System.currentTimeMillis())
    }
    return replaceLastTurn(turns.last().copy(assistant = text, agentId = agentId, thinking = thinking))
}
```

Read the conditional:
- `turns.isEmpty()` → append (OK, fresh conversation, no last turn to fill in)
- `turns.last().assistant != null` → append (last turn already has assistant; this is a *new* turn, OK)
- `turns.last().user == null` → append (last turn has no user; this is weird, but OK to be safe)

So the else branch (replace last turn) requires: `turns` non-empty AND `turns.last().assistant == null` AND `turns.last().user != null`. That means: last turn has a user message but no assistant. The intent: the model streamed text and we want to **fill in** the assistant field of the existing user turn (a single-turn with both user + assistant). 

But the agentic loop calls `addAssistant` *after* the stream completes (line 788 of `MemoryAugmentedAgenticLoop.kt`):
```kotlin
if (accumulatedText.isNotEmpty()) {
    val thinkingText = accumulatedThinking.toString().ifBlank { null }
    currentConversation = currentConversation.addAssistant(accumulatedText.toString(), thinking = thinkingText)
}
```

At that point `turns.last()` is a `Turn(user = "...")` from the user's just-added turn, with no assistant yet. Good — `addAssistant` will fill it in. **OK so far.**

But: if the user sends a second message in the same `run()` call (the agentic loop currently doesn't do this, but it's a single call), or if the loop has a code path that adds an assistant turn first (e.g. the reflection injection at line 1024‑1028 sets `metadata["lastReflection"]` on the *previous* assistant turn, not adds one — so fine), the replace logic could overwrite a previously streamed assistant. **Probably safe in current code, but the condition is fragile.**

The bigger problem: when `turns.last().user == null` (e.g. the conversation has a `Turn(assistant = "...")` with no preceding user — see `addToolCall` which refuses to create that case, so this only happens if the conversation was created externally), `addAssistant` appends a *new* turn with `assistant` filled in but no `user`. The agentic loop at line 1032‑1041 then says "the model succeeded this time, so the reflection is no longer relevant" and tries to `currentConversation.copy(metadata = currentConversation.metadata - "lastReflection")`. OK, fine.

**Test gap:** no test for `addAssistant` when `turns.last().user == null`.

---

### BUG‑11 — `ConversationStore.fork` has a detached KDoc block (cosmetic but real)

`aura-core/src/main/kotlin/com/aura/agent/ConversationStore.kt:240-260`
```kotlin
    /**
     * Fork a conversation from a specific turn index. Creates a new
     * conversation with a new ID, copying turns 0..fromTurnIndex
     * (inclusive). The original is untouched. The fork's title is
     * "{original title} (fork)" and it inherits the system prompt +
     * model. Used by the "Fork from here" action in ChatScreen.
     */
    /**
     * Toggle the pinned state of a specific turn within a conversation.
     * Pinned turns are highlighted in the UI for quick reference.
     */
    suspend fun toggleTurnPin(id: kotlin.String, turnIndex: Int): Boolean {
        ...
    }

    suspend fun fork(id: String, fromTurnIndex: Int): kotlin.String? {
        ...
    }
```

The `/** ... */` block on lines 240‑245 documents `fork()`, but the next `/**` on line 246 starts a new KDoc for `toggleTurnPin` without first closing the fork KDoc. The Kotlin compiler treats both `/**` as start-of-comment and the closing `*/` at line 249 closes the *outer* one — so the fork KDoc text is associated with the **outer enclosing declaration** (the `ConversationStore` class), and the `toggleTurnPin` KDoc is its own. This is a documentation bug that breaks IDE quick-docs and generated docs.

Verified: the function `fork` is called at `app/src/main/kotlin/com/aura/ui/viewmodel/ChatConversationController.kt:66`, so it is not dead code.

**Fix:** close the first `/**` block with `*/` before line 246.

---

### BUG‑12 — `findMatchingHand` re-fetches enabled hands on every step (perf)

`aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:402, 1156-1174`

The agentic loop runs up to 10 steps per user turn. At each step (line 402), it calls `findMatchingHand(lastUserMessage)` which calls `handRepository.getEnabled()` — a Room query — and then does a regex match. For a 10-step run, this is 10 redundant DB queries for hands that don't change mid-turn.

The adjacent code already has caching patterns (`cachedRecall`, `cachedPersonality`, `cachedCheapModel`). Add `cachedHand: Hand? = null` and set it on the first call.

**Why it's a real bug:** the hands are loaded fresh from Room on each step but the user's message doesn't change, so the answer can't change either. The fix is one-line and saves N−1 DB hits per turn.

---

### BUG‑13 — `cachedCheapModel` only caches after the first *non-null* resolution (logic)

`aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:461-465`
```kotlin
val rerankModel = cheapModel
val rewriteModel = cheapModel
// Cache the resolved model for subsequent steps.
if (cachedCheapModel == null && rerankModel != null) {
    cachedCheapModel = rerankModel
}
```

If `cheapModel` resolves to null on step 1 (e.g. no providers configured), the cache stays null and every subsequent step does the same provider-resolution work. The provider list is unlikely to change mid-turn, so this should cache the *result* (including null):
```kotlin
if (cachedCheapModel == null) {
    cachedCheapModel = cheapModel
}
```

Same for the model catalog lookup, etc.

---

### BUG‑14 — `pendingPermissions` map can grow without bound if user denies (resource leak, slow)

`aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:101, 169-180`

`denyPendingPermission` correctly removes the entry. `resumeAfterPermission` correctly removes on success. But there's no automatic cleanup: if the user grants a permission, the resume runs, completes, and removes — fine. If the user **closes the app** with a held permission, the map is reset on process death — fine. If the user **denies and the UI doesn't call `denyPendingPermission`** (e.g. they navigate away), the entry stays. The data held is a full `Conversation` snapshot which can be many KB per entry. Over time this could grow.

**Fix:** add a TTL-based sweeper (e.g. evict entries older than 5 minutes) or use the UI's lifecycle to drive cleanup.

---

### BUG‑15 — `ConversationCompactor.resolveThreshold` has dead parameter (API hygiene)

`aura-core/src/main/kotlin/com/aura/agent/ConversationCompactor.kt:240-245`
```kotlin
fun resolveThreshold(@Suppress("UNUSED_PARAMETER") model: kotlin.String, contextWindow: Int? = null): Int {
    if (contextWindow != null && contextWindow > 0) {
        return (contextWindow * 0.8).toInt().coerceAtLeast(4_000)
    }
    return DEFAULT_UNCOMPACTED_TOKENS
}
```

The `model` parameter is documented as "kept in the signature for backward compatibility with existing callers" but **the function body does not use it**. The `compactIfNeeded` function (line 88) calls `resolveThreshold(compactModel, lookupContextWindow(compactModel))` — passing the model in case the function ever needs it. This is a YAGNI pattern: the function would be cleaner as `resolveThreshold(contextWindow: Int?)`. The `@Suppress` is a smell.

**Fix:** drop the `model` parameter and update the one caller. Or, if the suppression is intentional for future use, add a `// TODO: model-specific override` comment.

---

### BUG‑16 — `ConversationCompactor` has double `onFailure` logging (cosmetic)

`aura-core/src/main/kotlin/com/aura/agent/ConversationCompactor.kt:74-76`
```kotlin
}.onFailure {
    android.util.Log.w("ConversationCompactor", "cheap-model resolution failed: ${it.message}", it)
}.onFailure { Log.w("Compactor", "op failed: ${it.message}", it) }.getOrDefault(model)
```

`runCatching` returns a `Result`; chaining two `.onFailure` blocks causes **both to run** for the same exception. The user gets two log lines per failure, with two different tags ("ConversationCompactor" and "Compactor"). The second tag is also misleading — "op failed" is generic.

Same pattern in `AgentCouncil.kt:181-182`:
```kotlin
}.onFailure { android.util.Log.w("AgentCouncil", "resolveModel failed for ${agent.name}: ${it.message}", it) }
.onFailure { Log.w("Council", "op failed: ${it.message}", it) }.getOrNull()
```

**Fix:** keep one `.onFailure` block. The second one is a copy-paste artifact.

---

### BUG‑17 — `Brain.MAX_NAME_BY_ID` LRU never gets to grow past 32 (perf / correctness, minor)

`aura-core/src/main/kotlin/com/aura/agent/Brain.kt:114-118`
```kotlin
val nameById = object : LinkedHashMap<String, String>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
        return size > MAX_NAME_BY_ID
    }
}
```

The map is created with initialCapacity=16 but the override evicts the LRU entry when size > 32. With accessOrder=true, `get` moves the entry to the tail. **Fine.** But the eviction threshold (32) is also the `MAX_NAME_BY_ID` const used in the doc comment ("max tool calls in a single response"). When a model emits 33+ tool calls, the 33rd call's name will be evicted *while the deltas for tool 1 are still arriving* — because accessOrder only moves on `.get`, not on every put. So if 32 tool calls were all `.put`-ed (Start events) and the model then sends a delta for tool 1, the `.get(tool1)` puts tool1 at the tail but the eldest is now some middle tool — not necessarily tool1. This is a minor data race that rarely matters in practice but is a real bug for high-fanout streams.

**Fix:** either bump MAX_NAME_BY_ID to a sane upper bound (256) or change the strategy to never evict within a single stream (use a `mutableMapOf` and clear at the end of `stream()`).

---

### BUG‑18 — `runCatching` swallowing `cancellation` semantics

Several places use `runCatching { ... }` to swallow exceptions but **don't rethrow `CancellationException`**. From the Kotlin coroutines guide, `runCatching` should not be used inside suspend functions because it catches `CancellationException` and turns it into a regular `Result.failure`, which **breaks structured cancellation**.

Specific instances in the audited files:
- `MemoryAugmentedAgenticLoop.kt:200-206` (resumeAfterPermission): `runCatching { toolExecutor.execute(...) }.onFailure { e -> ... }.getOrElse { e -> ToolResult.Error("resumed tool threw: ${e.message}", "exception") }` — catches CancellationException from tool execution and turns it into a ToolResult.Error, **preventing the run's coroutine from being cancelled**. This is a serious bug: a user-initiated cancel during a long-running tool re-run will not propagate.
- `MemoryAugmentedAgenticLoop.kt:451-458` (cheap model resolution): lower stakes (the cheap-model fallback is best-effort).
- `MemoryAugmentedAgenticLoop.kt:932-967` (memory auto-store): lower stakes but also wrong shape.
- `MemoryAugmentedAgenticLoop.kt:984-994` (LLM profile extraction): lower stakes.
- `MemoryAugmentedAgenticLoop.kt:1015-1030` (reflection generation): lower stakes.

**Fix:** wrap in `coroutineContext.ensureActive()` before the runCatching, or use `try { ... } catch (e: CancellationException) { throw e } catch (e: Exception) { ... }` explicitly.

The **most critical** one is `MemoryAugmentedAgenticLoop.kt:200-206` because it directly affects user-perceived cancel latency.

---

### BUG‑19 — `withTimeout` in `ToolExecutor.execute` may not interrupt tool cleanly (resource leak)

`aura-core/src/main/kotlin/com/aura/agent/ToolExecutor.kt:127-138`
```kotlin
val result = try {
    withTimeout(ctx.timeout) {
        runInterruptible(toolDispatcher) {
            runBlocking { tool.execute(call, ctx) }
        }
    }
}
```

`runInterruptible(toolDispatcher)` dispatches the lambda to the IO dispatcher and uses `Thread.interrupt()` when the coroutine is cancelled. The `tool.execute` is wrapped in `runBlocking { ... }` which parks a thread. When the timeout fires, the surrounding `withTimeout` cancels the coroutine, which propagates to `runInterruptible` which interrupts the thread. But `runBlocking` catches `InterruptedException` and re-throws it as... well, in Kotlin `runBlocking` rethrows `InterruptedException` as `CancellationException` after restoring the interrupt flag. The `withTimeout` then catches that and the code falls to `catch (e: TimeoutCancellationException)`. **OK in theory.**

But: many tools use OkHttp or `URLConnection`, both of which have their own timeout mechanisms that **don't honor thread interrupts cleanly**. OkHttp's call.cancel() must be called explicitly, and a thread interrupt may be swallowed by NIO. The `withTimeout(ctx.timeout)` is the only cancellation lever. If a tool is genuinely stuck in an uninterruptible syscall (rare on Android but possible — e.g. a binder call to a dead system service), the timeout never returns and the tool thread leaks.

**Fix:** add a watchdog: log when timeout fires and the underlying thread is still alive after 1s. Or document the assumption.

---

### BUG‑20 — `MemoryReranker.scoreOneBatch` reuses `result` map across batches (logic)

`aura-core/src/main/kotlin/com/aura/memory/MemoryReranker.kt:175-204`
```kotlin
val result = mutableMapOf<Int, Float>()
val usedByIdx = mutableSetOf<Int>()
// First pass: explicit indices
for (pl in parsedLines) { ... }
// Second pass: positional for the rest
val remaining = parsedLines.filter { it.candidateIdx == null }
val positionalIdx = batch.indices.filter { it !in usedByIdx }
for ((pl, idx) in remaining.zip(positionalIdx)) {
    result[idx] = pl.score
    usedByIdx.add(idx)
}
// Default remaining candidates to neutral (0.5f) so the
// ranking is stable even when the model under-responds.
for (idx in batch.indices) {
    if (idx !in result) result[idx] = 0.5f
}
```

`scoreBatch` (line 70-89) batches candidates into chunks of 4 and runs them in parallel:
```kotlin
return coroutineScope {
    batches.mapIndexed { batchIdx, batch ->
        async {
            val offset = batchIdx * BATCH_SIZE
            val batchScores = scoreOneBatch(query, batch, model)
            batchScores.mapKeys { (localIdx, score) -> offset + localIdx }
        }
    }.awaitAll().fold(emptyMap<Int, Float>()) { acc, map -> acc + map }
}
```

`mapKeys` on a `Map<Int, Float>` **returns a new map** but preserves the original's keys via the transform. The fold is safe. **OK.**

But: `scoreOneBatch` returns a map keyed by *local* indices (0..batch.size-1), and `scoreBatch` re-keys them with `offset + localIdx`. The transform uses `.mapKeys { (localIdx, score) -> ... }` — the destructured `(localIdx, score)` is `(K, V)` from the input map, where K is the local idx. **Correct.** Verified.

So this is **not a bug**, but worth a defensive test to pin the behavior.

---

### BUG‑21 — `ToolRegistry.all()` allocates a new list on every call (perf, hot path)

`aura-core/src/main/kotlin/com/aura/agent/ToolRegistry.kt:85`
```kotlin
fun all(): List<Tool> = tools.values.toList()
fun definitions(): List<ToolDefinition> = tools.values.map { t -> ToolDefinition(...) }
```

Both `all()` and `definitions()` are called by the agentic loop on every run (`definitions()` at line 311). Each call allocates a new List and a new ToolDefinition per tool. For 30+ tools and 5+ steps per run, this is 150+ allocations per turn. Not a bug, but an easy optimization:
- Cache the `definitions()` result and invalidate on `register`/`unregister`.
- Or use `Collections.unmodifiableList(tools.values.toList())` for stable iteration.

---

### BUG‑22 — `SpecialistRouter.matchesAnyKeyword` uses `IGNORE_CASE` on already-lowercased input (perf, dead work)

`aura-core/src/main/kotlin/com/aura/agent/SpecialistRouter.kt:144-155`
```kotlin
private fun matchesAnyKeyword(lower: String, keywords: Set<String>): Boolean {
    return keywords.any { kw ->
        val word = kw.trim()
        val useWordBoundary = kw.endsWith(" ") || word.length <= 5
        if (useWordBoundary) {
            val escaped = Regex.escape(word)
            Regex("\\b$escaped\\b", RegexOption.IGNORE_CASE).containsMatchIn(lower)
        } else {
            lower.contains(word)
        }
    }
}
```

`lower` is already lowercased (line 18: `val lower = userMessage.lowercase()`). `RegexOption.IGNORE_CASE` causes the regex engine to lowercase the input on every match attempt. The keyword `word` is not lowercased, but the original keyword set is not — so the `IGNORE_CASE` is required for correctness. The fix: lowercase the keyword set once at the top of the function and remove the `IGNORE_CASE` option.

**Why it's a real bug (minor):** the router runs on every chat message and is called for every keyword set (7 specialist groups × ~20 keywords = 140 regex compilations/matches per message). The `Regex.escape` + `Regex(...)` call also re-compiles the regex on every call (no caching). The whole router should be rebuilt once at startup.

**Fix:** make `matchesAnyKeyword` work on pre-lowered keyword sets and cache compiled regexes in a `Map<String, Regex>`.

---

### BUG‑23 — `MemoryStore.query` calls `embedder.embed(retrievalQuery)` even on the vector-fallback path (wasted compute)

`aura-core/src/main/kotlin/com/aura/memory/MemoryStore.kt:215-228`
```kotlin
val qVec = embedder.embed(retrievalQuery)

if (textHits.isEmpty()) {
    // Vector fallback: no text overlap between query and any stored
    // memory in the scoped set. But the query might still be
    // semantically similar to a memory...
    val all = dao.vectorScanCandidates(scopes, VECTOR_FALLBACK_SCAN_LIMIT)
    ...
}
```

`qVec` is computed unconditionally on line 215 (an embedder call — cloud round-trip), even though the function then checks `if (textHits.isEmpty())` and only uses `qVec` in the fallback path AND in the main path (line 278). So `qVec` is used in both branches and the computation is necessary in both.

**Not a bug** — the embedder call is needed. But: the embedder call happens BEFORE the `queryRewriter.rewrite(...)` is checked for failure. If the rewrite throws and we fall back to the original `text`, the embedder is called twice (once for `retrievalQuery` and once implicitly via the search). Wait, no — the rewrite returns the original `text` on failure (line 95 of QueryRewriter), and the embedder is called on `retrievalQuery` (which is the rewrite output or the original). So the embedder is called exactly once. **Fine.**

But: the embedder call is **inside the recall hot path**, called per step of the agentic loop. The recall cache at the loop level (`cachedRecall` at line 367) prevents this from being called more than once per turn, so the per-loop cost is OK. **Verified.**

---

### BUG‑24 — `ConversationCompactor` does not free the `contextWindowCache` on memory pressure (resource leak, slow)

`aura-core/src/main/kotlin/com/aura/agent/ConversationCompactor.kt:37-49`
```kotlin
private val contextWindowCache = ConcurrentHashMap<String, Pair<List<ModelInfo>, Long>>()
private val contextWindowCacheTtlMs = 5 * 60 * 1000L // 5 minutes

private suspend fun cachedModelsWithContext(provider: Provider): List<ModelInfo> {
    val now = System.currentTimeMillis()
    val cached = contextWindowCache[provider.prefix]
    if (cached != null && now - cached.second < contextWindowCacheTtlMs) {
        return cached.first
    }
    val models = provider.listModelsWithContext()
    contextWindowCache[provider.prefix] = models to now
    return models
}
```

The cache is keyed by provider prefix and never invalidated by TTL — the **read** checks TTL but the **write** always overwrites. So an old entry will be replaced when re-read. **No memory leak.** But the value is `Pair<List<ModelInfo>, Long>` — `List<ModelInfo>` is the full model list which can be 100+ entries. Over 5 providers and 100 models each, that's ~500 ModelInfo objects cached for 5 minutes — small but unbounded if many providers are configured. **Not critical.**

**Fix:** cap the cache size or use TTL eviction on read (delete the entry when stale).

---

### BUG‑25 — `MemoryEntity` default `accessCount` is 0 but `Retrieval` uses it for ranking (logic, edge case)

`aura-core/src/main/kotlin/com/aura/Retrieval.kt:90`
```kotlin
val accessFreq = mem.accessCount.toFloat() / (mem.accessCount + 5).toFloat()
```

For `accessCount = 0`, `accessFreq = 0/5 = 0.0`. So a freshly-stored memory has `accessFreq = 0.0`, which means it never gets the access-frequency boost until it's been recalled 5+ times. The RRF rank for `accessScore` is therefore dominated by `accessRecency` (which is 1.0 for new memories). **OK, but worth knowing:** a brand-new memory's RRF score is based on recency, importance, and decay (all 1.0), but text and vector scores may also be 0 if the recall doesn't match. So new memories can rank at the top *only* when they match the query — otherwise they're pushed down by their `accessScore = 0.5` (blend of 1.0 recency and 0.0 freq). **Not a bug, intended behavior.**

---

### BUG‑26 — `ConversationStore.entitySearchText` decodes the full turn list for every save (perf)

`aura-core/src/main/kotlin/com/aura/agent/ConversationStore.kt:325-331`
```kotlin
private fun entitySearchText(entity: ConversationEntity): String {
    val turns = runCatching {
        convJson.decodeFromString<List<Turn>>(entity.turnsJson)
    }.onFailure { android.util.Log.w("ConversationStore", "entitySearchText: corrupt turnsJson for ${entity.id}: ${it.message}", it) }
        .getOrDefault(emptyList())
    return searchText(turns, entity.title)
}
```

Called on every `save()` to compare the new search text to the previous one (line 23). For a 100-turn conversation, this decodes the full turn list (a JSON blob) on every save. The save() is called frequently (every user message + every assistant turn). For long conversations, this is O(N) per save.

**Fix:** cache the search text on the entity as a new column (would require a Room migration) or skip the diff entirely and re-embed on every save (cheaper than decoding the turns).

---

### BUG‑27 — `ConversationStore.save` never deletes the embedding when the user clears the conversation (data integrity)

`aura-core/src/main/kotlin/com/aura/agent/ConversationStore.kt:18-58`

When `searchText.isBlank()` (line 25), the embedding is set to null. But when `searchText` is non-blank AND the previous embedding is non-null AND the texts differ, the new embedding is computed and stored. The previous embedding is overwritten. **Fine.**

But: when `searchText` is blank (e.g. a brand-new empty conversation with just a title), the embedding is set to null. The save then re-inserts the row with `embedding = null`. **Fine.**

Edge case: if the user deletes the conversation, the soft-delete (line 94) does NOT clear the embedding. A subsequent restore (line 99) will get the row back with the old embedding. **Probably fine**, but worth knowing.

---

### BUG‑28 — `AgentEvent.PermissionRequested` is defined but `PermissionGranted` is referenced in tests (test rot)

`aura-core/src/test/kotlin/com/aura/agent/MemoryAugmentedAgenticLoopPermissionTest.kt:23`
```kotlin
 * the `AgentEvent.PermissionGranted` event was dead code. When a tool
```

The test file's KDoc references `PermissionGranted`, but `AgentEvent` only has `PermissionRequested` (line 1195 of MemoryAugmentedAgenticLoop.kt). The KDoc is historical and not a bug, but the comment is stale — the test file is named `MemoryAugmentedAgenticLoopPermissionTest.kt` which is fine, but anyone reading the KDoc looking for the `PermissionGranted` event will be confused.

**Fix:** update the KDoc to reference `PermissionRequested`.

---

### BUG‑29 — `MemoryStore.deleteBySource` / `forgetByCategory` don't fire `evolutionHooks.onMemoryForgotten` (silently inconsistent)

`aura-core/src/main/kotlin/com/aura/memory/MemoryStore.kt:386, 400-410`
```kotlin
suspend fun deleteBySource(source: String) = dao.deleteBySource(source)

suspend fun forgetByCategory(category: String) {
    dao.deleteByCategory(category)
}
```

Compare with `forget(id)` at line 369:
```kotlin
suspend fun forget(id: String) {
    dao.delete(id)
    runCatching { evolutionHooks?.onMemoryForgotten(id) }
        .onFailure { Log.w("MemoryStore", "evolutionHooks.onMemoryForgotten failed (non-fatal)", it) }
}
```

The single-id `forget` fires the evolution hook, but the bulk variants do not. The Evolution engine's "memory forgotten" telemetry is therefore incomplete — bulk operations bypass it.

**Fix:** after `dao.deleteBySource(source)` and `dao.deleteByCategory(category)`, fire the hook for each affected id. But the DAO methods don't return the affected ids. **Need to:** add a new DAO method that returns the deleted ids, or do a select-then-delete in a transaction.

---

### BUG‑30 — `ConversationDao.updateTurns` may write the same `updatedAt` to the entity without concurrency guard (data integrity, edge)

The `toggleTurnPin` (line 250 of ConversationStore.kt) does:
```kotlin
dao.updateTurns(id, convJson.encodeToString(updated), System.currentTimeMillis())
```

Two simultaneous `toggleTurnPin` calls on the same conversation will both pass the `dao.getById(id)` check (line 251) with the same turns list, then both `updateTurns` with their own toggled value. The last writer wins; the other toggle is silently lost.

**Fix:** wrap the read-modify-write in a `@Transaction` DAO method (Room supports this) or add a `WHERE updatedAt = :expected` clause to detect concurrent updates.

---

### BUG‑31 — `CheapModelHeuristic.pick` is called with no fallback on empty list (test gap)

`aura-core/src/main/kotlin/com/aura/providers/CheapModelHeuristic.kt:124`
The agentic loop calls `CheapModelHeuristic.pick(candidates) ?: userModel` (line 1123 of MemoryAugmentedAgenticLoop.kt). If `candidates` is empty, `pick` returns null and the user's expensive model is used for the cheap task. **Documented behavior.** But the planning step (line 647) also uses `resolveCheapModel` which has the same fallback. A user with only one configured provider will get their main model for the planning step — defeating the purpose.

**Fix:** if `candidates` is empty, return a known small model (e.g. the user's first non-MoA model, even if it costs more — at least the planning step is consistent).

---

### BUG‑32 — `MemoryStore.recordFeedback` doesn't check if the memory exists (silent failure)

`aura-core/src/main/kotlin/com/aura/memory/MemoryStore.kt:375-384`
```kotlin
suspend fun recordFeedback(memoryId: String, kind: String, note: String = "") {
    val row = MemoryFeedbackEntity(...)
    runCatching { memoryFeedbackDao.insert(row) }
        .onFailure { Log.w("MemoryStore", "memoryFeedbackDao.insert failed", it) }
}
```

`memoryFeedbackDao.insert` is `@Insert` with no `OnConflictStrategy` declared. If the foreign key to `memories(id)` doesn't exist (e.g. user deletes a memory and then a UI element still has a reference), the insert will throw a `SQLiteConstraintException` — caught by `runCatching` and logged but not propagated. The UI shows "feedback recorded" while the DB has no row.

**Fix:** validate the memory exists first (`dao.getById(memoryId)`), or use `INSERT OR IGNORE` and check the return value.

---

### BUG‑33 — `ChatConversationController.fork` is not transactional (data integrity, edge)

`aura-core/src/main/kotlin/com/aura/agent/ConversationStore.kt:262-299` (`fork`)

The `fork` function does a read-decode-read-decode-encode-insert sequence. If the process dies (or Room is interrupted) between any of the reads and the final `dao.insert`, no row is written — but the original is untouched, so the user just doesn't get a fork. **Safe.** 

But: `dao.insert(...)` is the only write, and there's no `@Transaction` wrapper. The `metadata` and `turns` JSON are decoded independently (lines 264-271), and if the first decode fails (corrupt metadataJson), the code logs and uses an empty map — but the **turns decode** is also independent. If the turns JSON is corrupt, the code returns an empty list, then `fromTurnIndex !in allTurns.indices` is true (empty list, fromTurnIndex >= 0), and the function returns `null` — no insert. **Safe.** 

If both decode correctly, the function inserts. **No issue.**

The actual issue: the `fork` function's KDoc block on lines 240-245 is detached (see BUG-11), and the function does not check `fromTurnIndex >= 0`. If a caller passes `fromTurnIndex = -1`, `fromTurnIndex + 1 = 0` and `forkedTurns = emptyList()`. The insert proceeds with an empty turns list, creating a fork with no history. **Bug.**

**Fix:** add `if (fromTurnIndex < 0) return null` as the first check.

---

## Test coverage gaps (cross-cutting)

The agentic loop is well-tested for permissions and failover (`MemoryAugmentedAgenticLoopPermissionTest.kt`, `MemoryAugmentedAgenticLoopFailoverTest.kt`) but has **no** test for:
- The 10-step happy path with multiple tool rounds
- `extractProfileFromText` regex coverage (positive and negative)
- `findMatchingHand` trigger-phrase matching (boundary cases: hand boundary chars, multi-phrase `|` separator)
- `Conversation.addAssistant` edge cases (empty conversation, last turn has assistant, last turn has no user)
- `Conversation.toMessages` truncation behavior (CJK, emoji, surrogate pairs)
- `MemoryStore.store` vs `storeIfAbsent` vs `maybeStore` dedup semantics
- `MemoryAugmentedAgenticLoop.run` incognito mode (`AgentIncognitoTest.kt` exists but only covers the main path)

`ConversationStore` has no tests at all in the audited scope (the test files I found are for `EndToEndTest`, `FadeMemTest`, `MemoryStoreTest`, `MemoryStoreTouchTest`, `WriteGateTest` — none of them exercise `ConversationStore`).

`ConversationCompactor` has one test (`ConversationCompactorTest.kt`) that calls `toMessages()` on a fixture, not the actual `compactIfNeeded` function.

`MemoryReranker` and `QueryRewriter` have **zero** tests.

`SpecialistRouter` has **zero** tests. The keyword-routing logic is highly regex-driven and any change to the keyword sets could silently break routing.

`AgentCouncil` has **zero** tests. The timeout and producer-director split are complex enough to warrant integration tests.

---

## Anti-patterns (cross-cutting)

1. **Repeated `runCatching {}.onFailure{ Log.w }.getOrElse { default }` blocks.** At least 15 such patterns in `MemoryAugmentedAgenticLoop.kt` alone. Each is fine in isolation but the sheer volume makes it hard to spot the one that **should** propagate the error. Suggestion: extract a `safeCall("name") { ... } ?: default` helper and use it consistently, with a `propagate: Boolean` flag for the rare cases where errors should bubble.

2. **`@Suppress("UNUSED_PARAMETER")` on `resolveThreshold`'s `model` parameter (BUG-15).** Indicates a public API was changed and a parameter was kept for compat. Either remove it or document why it must stay.

3. **Two log tags for the same failure (BUG-16).** "ConversationCompactor" and "Compactor" both logging the same exception. The first tag is the class name; the second is a shortened alias. Pick one.

4. **`flow { ... }.collect { ... }` + `throw CancellationException` for control flow (BUG-03).** Works today because the surrounding catch handles it, but is fragile.

5. **Manual regex creation in hot paths** (`SpecialistRouter.matchesAnyKeyword`, `MemoryAugmentedAgenticLoop.findMatchingHand`). Each call re-compiles the regex. Should be cached.

6. **Inconsistent error reporting tags.** "AgenticLoop" vs "Compactor" vs "Council" vs "MemoryStore" vs "Brain" vs "AgentCouncil" — the codebase has 5+ tag conventions. Pick one (class name) and stick with it.

7. **Comments that lie.** The `addAssistant` comment block says it "Append or fill in an assistant turn" but the actual logic is a three-way conditional with implicit assumptions. Either simplify the code or expand the comment to match.

8. **Inconsistent parameter typing** (`id: String` vs `id: kotlin.String` — the latter is used in `ConversationStore.toggleTurnPin` and `fork` but not in `load`, `save`, `delete`). Cosmetic but suggests two different authors with different style guides merged.

---

## Structural concerns

1. **`MemoryAugmentedAgenticLoop` is now a 1218-line god class.** 27 constructor dependencies, ~10 private methods, 7 distinct subsystems (memory, tool execution, provider dispatch, KG, profile, hands, consciousness). The constructor signature alone is 27 lines. Each new concern is bolted on as an optional parameter, increasing the constructor length and the test-fixture boilerplate (see `MemoryAugmentedAgenticLoopFailoverTest.kt:68-87` for the mock setup). Suggestion: split into a `RecallContextBuilder`, a `ToolDispatcher`, and a `PostTurnProcessor` — the loop itself becomes ~200 lines of orchestration.

2. **`MemoryStore` is also a god class** (595 LOC, 10+ responsibilities: dedup, store, query, RRF, touch, decay, feedback, audit, bulk delete, category mgmt, embedding rebuild). Suggestion: split into `MemoryWriter` (store/maybeStore/storeIfAbsent), `MemoryReader` (query/recent/decayed/top), `MemoryEvolution` (touch/decay/feedback).

3. **The agentic loop, the compactor, the conversation store, the brain, and the tool executor all have their own "test" files but most of them test only one happy path.** Coverage is *appearance* of testing, not actual coverage. Run a coverage tool and the real number is probably <30% for the core loop branches.

4. **No integration test that exercises the full agentic loop with a real provider.** Every test mocks `Brain.stream()`. The only test that wires a real provider is `EndToEndTest` which also uses a mock provider. A real-provider integration test (gated on a `RECORD_REPLAY` env flag) would catch the bugs that mocks can't (timeouts, surrogate pairs, network errors).