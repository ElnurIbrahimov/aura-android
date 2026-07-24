# MEMORY AUDIT

Aura Android (Kotlin/Compose) memory pipeline end-to-end audit. v0.33.0 at HEAD `251e67a5` on branch `feat/tier-1-friction`. Working tree clean. Scope: recall pipeline, storage, embeddings, backup roundtrip, consolidation/WriteGate.

All findings cite `file:line` with verbatim excerpts. No code changes were made. Severity: P0 = data loss / cross-tenant leak / production-blocking; P1 = silent degradation / wrong-data path; P2 = code smell / future-bug.

---

## A. Storage (MemoryEntity, MemoryDao, MemoryDatabase, migrations)

### A1. [P0] `MemoryBackup` schema omits `scope` — backup→restore loses every agent-scoped memory and silently leaks across agents
**File**: `aura-core/src/main/kotlin/com/aura/backup/AuraBackup.kt:124-138`; `aura-core/src/main/kotlin/com/aura/backup/BackupManager.kt:598-614`
```kotlin
@Serializable
data class MemoryBackup(
    val id: String,
    val content: String,
    val source: String,
    val category: String,
    val importance: Float,
    val createdAt: Long,
    val accessedAt: Long,
    val accessCount: Int,
    val decayScore: Float,
    val tags: String,
    val metadata: String,
    val sourceConversationId: String = "",
    val sourceTurnTimestamp: Long = 0L,
)   // ← no scope, no embeddingModel, no embeddingVersion

private fun MemoryBackup.toEntity() = MemoryEntity(
    ...
    embedding = null,
    ...
)   // ← scope defaults to "general"
```
**Impact**: Every memory with `scope = "agent:<id>"` (set by `MemoryAugmentedAgenticLoop.kt:634` and filtered at line 235) is silently re-homed to `scope = "general"` on restore. The agent's private memory namespace is destroyed. A subsequent agentic recall with `scopeFilter = setOf("general", "agent:<id>")` will surface *every other agent's* memories that were correctly scoped, in addition to its own. Cross-agent memory leak.
**Fix**: add `val scope: String = "general"`, `val embeddingModel: String? = null`, `val embeddingVersion: Int = 0` to `MemoryBackup`; include all three in `toBackup()` and `toEntity()` in `BackupManager.kt:582-614`. Bump `AuraBackup.SCHEMA_VERSION` to 12.

### A2. [P0] Eleven `runCatching` blocks in `MemoryStore` swallow exceptions silently — every read path can return empty on failure
**File**: `aura-core/src/main/kotlin/com/aura/memory/MemoryStore.kt:46, 59, 141, 232, 283-284, 341, 351, 413-417, 438, 474`
```kotlin
// line 46 — semantic dedup
val existing = runCatching { dao.allWithEmbeddings() }.getOrDefault(emptyList())
// line 59 — merge update on dedup hit
runCatching { dao.update(match.copy(content = content, embedding = null, ...)) }
// line 141 — evolution hook after store
runCatching { evolutionHooks?.onMemoryStored(id, ...) }
// line 232 — touch on vector-fallback recall hit
for (mem in results) { runCatching { touch(mem.id) } }
// line 351 — feedback insert
runCatching { memoryFeedbackDao.insert(row) }
// line 438 — edit history audit insert
runCatching { memoryEditDao.insert(MemoryEditEntity(...)) }
// line 474 — edit history read
return runCatching { memoryEditDao.getForMemory(memoryId) }.getOrDefault(emptyList())
```
**Impact**: SQLite corruption, lock timeout, or schema mismatch produces empty list / null and the system silently degrades. Particular damage: `recordFeedback` (line 351, user feedback is *lost*); `getEditHistory` (line 474, UI shows "no edit history" when DB has them); semantic dedup (line 46, paraphrased duplicates will be stored); merge on dedup hit (line 59, both old and new memory remain visible); edit audit (line 438, every edit loses its trail).
**Fix**: every `runCatching` should `Log.e("MemoryStore", "X failed", e)` before fallback. For `recordFeedback` and `getEditHistory`, propagate the exception so the ViewModel can show a "couldn't save" toast.

### A3. [P1] `MemoryStore.maybeStore` is dead code in production — only `EndToEndTest.kt` calls it
**File**: `aura-core/src/main/kotlin/com/aura/memory/MemoryStore.kt:25-91`
```kotlin
suspend fun maybeStore(
    content: String,
    source: String = "user",
    scope: String = "general",
    provenance: ConversationProvenance = ConversationProvenance(),
): String? { ... }
```
Call sites: only `aura-core/src/test/kotlin/com/aura/agent/EndToEndTest.kt:167`. The production write path is `memoryStore.store(...)` at `MemoryAugmentedAgenticLoop.kt:635`, `MemoryTools.kt:44`, `EvolutionApplySaga.kt:211`, `MemoryViewModel.kt:337`, and `storeIfAbsent` at `DocumentRepository.kt:39`, `ChatViewModel.kt:487`. 
**Impact**: The function is public on a `@Singleton` with a docstring ("Decides whether a piece of content is worth storing") that suggests it's the gate-keeping entry point. A future dev will read this docstring, think the agentic loop routes through it, and miss that the actual writes skip the WriteGate (it's done at the caller via `LlmWriteGate.evaluate` at `MemoryAugmentedAgenticLoop.kt:627-643`). Additionally, `maybeStore`'s `dao.insert` at lines 75-89 does **not** stamp `embeddingModel` or `embeddingVersion`, while `store` at lines 130-131 does — so a refactor that routes through `maybeStore` would silently default these fields to `null`/`0`.
**Fix**: delete `maybeStore`, or wire it into the agentic loop and add `embeddingModel = embedder.modelId(), embeddingVersion = embedder.dimension()` to the entity at line 75-88.

### A4. [P1] `MemoryStore.maybeStore` and `MemoryStore.store` both lack the exactInsertMutex — check-then-insert race on `existsByContent`
**File**: `aura-core/src/main/kotlin/com/aura/memory/MemoryStore.kt:25-91` (no mutex); `aura-core/src/main/kotlin/com/aura/memory/MemoryStore.kt:98-108` (`storeIfAbsent` has it)
```kotlin
// storeIfAbsent — properly serialized
suspend fun storeIfAbsent(...): String? = exactInsertMutex.withLock {
    if (dao.existsByContent(content) > 0) return@withLock null
    store(content, source, category, importance, tags, scope)
}

// maybeStore — no mutex
if (dao.existsByContent(content) > 0) return null   // line 37
val embedding = embedder.embed(content)            // line 38 — race window
// ... long semantic dedup scan ...
dao.insert(MemoryEntity(...))                       // line 74
```
**Impact**: Two concurrent writes of identical content (e.g., user retypes while a previous run is still finalizing) both pass `existsByContent` and both insert. Duplicate memories, duplicate recall hits, skewed RRF ranking.
**Fix**: route all writes through `storeIfAbsent`, or add `exactInsertMutex.withLock { ... }` to `maybeStore` and `store`.

### A5. [P1] `EvolutionApplySaga.applyConsolidateMemories` drops the source memories' scope — agent memories leak into `general` (or vice versa) on consolidation
**File**: `aura-core/src/main/kotlin/com/aura/evolution/EvolutionApplySaga.kt:200-220`
```kotlin
val storedId = memoryStore.store(consolidated, "evolution:consolidate", category, 0.7f)
//                                  ^content    ^source                       ^category  ^importance
// scope defaults to "general" — the source memories' scope is never read.
```
**Impact**: The source memories (e.g., three `scope = "agent:researcher"` facts) are forgotten and replaced by a single consolidated memory in `scope = "general"`. The agent's private memory namespace is destroyed. A subsequent `scopeFilter = setOf("general", "agent:researcher")` recall will return the consolidated memory to *every* agent, not just `researcher`. This is a real P0/P1 scope leak triggered by the user accepting a consolidation proposal.
**Fix**: read the source memories' scope before storing, and pass `scope = sourceScope` to `store(...)`. If the source memories have mixed scopes, refuse to consolidate (require manual review) or write to all of them (heavy-handed).

### A6. [P2] `MemoryEntity.embeddingModel` / `embeddingVersion` are never stamped on write except in `store`
**File**: `aura-core/src/main/kotlin/com/aura/memory/MemoryStore.kt:110-145, 408-420`
```kotlin
suspend fun store(...): String {
    ...
    embeddingModel = embedder.modelId(),       // ← only here
    embeddingVersion = embedder.dimension(),   // ← only here
    ...
}

suspend fun rebuildEmbeddings(): Int {
    val pending = dao.allForExport().filter { it.embedding == null }
    for (mem in pending) {
        runCatching {
            val vec = embedder.embed(mem.content)
            dao.update(mem.copy(embedding = Embedder.toBytes(vec)))   // ← no modelId, no version
        }.isSuccess
    }
}
```
**Impact**: After a backup restore (which intentionally nulls embeddings, see A1) followed by `rebuildEmbeddings()`, every row has a fresh `embedding` blob but stale `embeddingModel = null` / `embeddingVersion = 0`. The fields are documented as "for cache invalidation" (see `MemoryEntity.kt:30-33`) but no code path *reads* them today — so they're dead metadata. If a future change adds a "skip rows already on the current model" fast path, the data is already inconsistent.
**Fix**: line 415 should be `mem.copy(embedding = Embedder.toBytes(vec), embeddingModel = embedder.modelId(), embeddingVersion = embedder.dimension())`. Same fix in `maybeStore`'s dao.insert block.

### A7. [P2] `MIGRATION_11_12` adds `scope` column with default `'general'`; no backfill for historical memories
**File**: `aura-core/src/main/kotlin/com/aura/memory/MemoryModule.kt:535-540`
```kotlin
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE memories ADD COLUMN scope TEXT NOT NULL DEFAULT 'general'")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memories_scope ON memories(scope)")
    }
}
```
**Impact**: Every pre-v0.33 memory is in `scope = "general"`. The recall filter `setOf("general", "agent:<id>")` returns *all* unscoped memories to every agent. An agent that wants only its private memory will still see every historical memory. There's no path to "re-scope" historical data.
**Fix**: backfill `scope` from `sourceConversationId` (look up the conversation's `agentId`) during the migration, or add a "Rebuild scope index" action in the Memory screen that walks every unscoped memory and prompts the user.

### A8. [P2] `VectorIndex` is injected but never used — recall is doing brute-force in-memory cosine
**File**: `aura-core/src/main/kotlin/com/aura/memory/VectorIndex.kt:1-36`; `aura-core/src/main/kotlin/com/aura/memory/MemoryStore.kt:14-23`
```kotlin
// VectorIndex.kt — defined, hard-coded dim=384
class VectorIndex(private val dim: Int = 384) { ... }

// MemoryModule.kt:648 — provided
fun provideVectorIndex(): VectorIndex = VectorIndex()

// MemoryStore.kt — never calls vectorIndex.search(...)
// The recall path at MemoryStore.kt:217-234 and 255 does dao.allByScopes + per-row cosineSimilarity
```
**Impact**: As memory count grows past ~5k, the brute-force scan becomes a hot loop on every recall. `VectorIndex` is dead code that suggests an unrealized HNSW/ANN plan.
**Fix**: either implement a real HNSW-backed index and wire it into `query`, or delete `VectorIndex` and document the brute-force path.

### A9. [P1] `MemoryEntity` has no soft-delete `deletedAt` column — `forgetAll` / `forgetByCategory` are irreversible
**File**: `aura-core/src/main/kotlin/com/aura/memory/MemoryEntity.kt:14-42` (no `deletedAt`); `aura-core/src/main/kotlin/com/aura/memory/MemoryStore.kt:360, 368`
Compare with `ConversationBackup.deletedAt: Long? = null` at `AuraBackup.kt:200`, which is preserved across roundtrips. Memories have no soft-delete column, so `forgetAll()` is a hard delete. The "Clear all" button in the Memory screen (docstring at line 357-359) is irreversible.
**Impact**: A user tapping "Clear all" by accident loses all memories with no recovery; the only restore path is the backup file (if one exists and includes the lost memories).
**Fix**: add `deletedAt: Long? = null` to `MemoryEntity`, add `MIGRATION_13_14`, update `forgetAll`/`forgetByCategory` to soft-delete by default, and add a `purgeDeletedBefore(cutoff)` for the nightly job. The "Clear all" action should be hard-delete with a confirmation dialog.

---

## B. Recall pipeline (query, RRF, BM25, cross-encoder, query rewriting, cache)

### B1. [P1] Vector-fallback recall path skips `evolutionHooks.onMemoryRecalled` — recall telemetry is half-wired
**File**: `aura-core/src/main/kotlin/com/aura/memory/MemoryStore.kt:217-234` (fallback path); `aura-core/src/main/kotlin/com/aura/memory/MemoryStore.kt:281-287` (main path)
```kotlin
// Vector fallback — no text hits in scope (line 232)
for (mem in results) { runCatching { touch(mem.id) } }       // ← no recall hook
return results

// Main path — text hits present (line 281-287)
for ((index, mem) in results.withIndex()) {
    runCatching { touch(mem.id) }
    runCatching {
        evolutionHooks?.onMemoryRecalled(mem.id, text, index + 1, null, null, null)
    }
}
```
**Impact**: A query with zero shared words ("programming languages I enjoy" vs stored "I love Kotlin") triggers the vector fallback, bumps `accessCount` (good for decay), but does **not** record a recall event. The Evolution subsystem's `onMemoryRecalled` is the data source for "which memories actually help" — it will under-report by 5-15% (the rate of vector-fallback hits).
**Fix**: add the same `runCatching { evolutionHooks?.onMemoryRecalled(...) }` after each touch in the fallback path.

### B2. [P0] `CloudEmbedder.dimension()` hard-coded 384 — dimension validation rejects every real Ollama embedding model
**File**: `aura-core/src/main/kotlin/com/aura/memory/CloudEmbedder.kt:53, 129-134`
```kotlin
override fun dimension(): Int = 384   // ← hard-coded

...
if (vec.size != dimension()) {
    android.util.Log.w("CloudEmbedder", ...)
    throw RuntimeException("embedding dimension mismatch: ${vec.size} != ${dimension()}")
}
```
**Impact**: Ollama's `nomic-embed-text` returns 768-dim, `mxbai-embed-large` returns 1024-dim, `all-minilm` returns 384-dim (only model that works). When a user picks any non-384 model, the cloud call **always** throws, the embedder silently falls back to `LocalEmbedder`'s 384-dim SHA-256 hash, and `modelId()` reports the cloud model name while the stored vector is actually a 384-dim hash. Result: a user who picks `nomic-embed-text` and has 100 stored memories will get no signal at all that the cloud path is broken — the only log line is `"Falling back to local embedder"`, which the user never sees.
**Fix**: parse the model-to-dimension mapping from a known catalog (`KNOWN_EMBEDDING_DIMS` map: `nomic-embed-text → 768, mxbai-embed-large → 1024, all-minilm → 384, ...`) and use that. Or fetch the dimension from the API by issuing a 1-token probe embed. Either way, `dimension()` must reflect the actual configured model.

### B3. [P1] `CloudEmbedder.embed` catches **every** exception silently with no log line
**File**: `aura-core/src/main/kotlin/com/aura/memory/CloudEmbedder.kt:81-84`
```kotlin
if (!apiKey.isNullOrBlank() && model != null) {
    try {
        val vec = cloudEmbed(text, apiKey, model)
        synchronized(cache) { cache[cacheKey] = vec }
        return@withContext vec
    } catch (_: Exception) {
        // Fall through to local fallback
    }
}
```
**Impact**: 401 (expired key), 429 (rate-limited), 5xx (Ollama Cloud outage), timeout, malformed response, network unreachable — all silently fall through to the local SHA-256 hash embedder. Every cloud embedding failure is a *silent degradation* of recall quality. The user has no way to debug "why is recall so bad since Tuesday?".
**Fix**: `catch (e: Exception) { android.util.Log.w("CloudEmbedder", "cloud embed failed, falling back to local", e) }`. Optionally expose a `lastCloudError` field for the Settings screen.

### B4. [P1] `MemoryReranker.scoreOneBatch` parser is index-aligned, not content-aligned — extra or missing lines silently mis-score
**File**: `aura-core/src/main/kotlin/com/aura/memory/MemoryReranker.kt:137-154`
```kotlin
val lines = response.toString().trim().lines()
    .filter { it.isNotBlank() }
    .mapNotNull { line ->
        val cleaned = line.trim()
            .replace(Regex("^\\d+[.):]\\s*"), "")
            .replace(Regex("^[-*]\\s*"), "")
            .replace(Regex("(?i)^Memory\\s*\\d+\\s*:?\\s*"), "")
            .trim()
        Regex("""\d*\.?\d+""").find(cleaned)?.value?.toFloatOrNull()
    }

return batch.indices.associateWith { idx ->
    lines.getOrNull(idx) ?: 0.5f   // ← default to neutral 0.5 on missing
}
```
**Impact**: If the model returns 3 lines for a 4-candidate batch, the missing slot gets `0.5` (neutral) — *better than it deserves* for an irrelevant memory and *worse than it deserves* for a relevant one. With small/reasoning models, missing slots are a regular occurrence. No log line.
**Fix**: parse with a per-memory position anchor (look for `Memory 1:`, `Memory 2:` prefixes). If a memory's score is missing, log a warning. Or change the default from 0.5 to 0.0 so a missed parse doesn't accidentally boost.

### B5. [P2] Recall cache in agentic loop captures `agentId` at loop start — single-pass, but a future multi-agent loop would silently re-use stale hits
**File**: `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:172, 231-296`
```kotlin
var cachedRecall: Triple<String, String?, List<com.aura.memory.MemoryEntity>>? = null
...
if (cachedRecall?.first == lastUserMessage && cachedRecall?.second == agentId) {
    cachedRecall!!.third
}
```
**Impact**: Today the loop is single-pass and `agentId` is the parent's — a child's `query` call through `ToolExecutor` doesn't share this cache, so the leak is theoretical. But the cache key is fragile: any future refactor that varies `agentId` mid-loop will silently re-use the old hits.
**Fix**: include a turn-id in the cache key, or clear `cachedRecall` whenever `agentId` changes.

### B6. [P2] `QueryRewriter.rewrite` is synchronous — 5s timeout adds latency to every deictic-query turn
**File**: `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:182-186`; `aura-core/src/main/kotlin/com/aura/memory/QueryRewriter.kt:52-96`
```kotlin
val retrievalQuery = if (queryRewriter != null && rewriteModel != null && recentContext.isNotBlank()) {
    queryRewriter.rewrite(text, recentContext, rewriteModel)   // 5s timeout
} else {
    text
}
```
**Impact**: Up to 5s pause before the first token of every agentic turn that has a deictic query ("that thing we discussed", "what was it called"). For a typing-in-the-chat feel, this is bad.
**Fix**: race the rewriter with a 1s budget — start it, run the recall with the original query in parallel, and use the rewritten result only if it arrives first.

### B7. [P2] `BM25.normalizedScore` divisor is unsound — `raw` can exceed `maxPossible`, `coerceIn(0,1)` clips but flattens ranking
**File**: `aura-core/src/main/kotlin/com/aura/memory/BM25.kt:102-110`
```kotlin
fun normalizedScore(query: String, docIndex: Int): Float {
    val raw = score(query, docIndex)
    if (raw <= 0f) return 0f
    val queryTokens = tokenize(query)
    val maxPossible = queryTokens.mapNotNull { idf[it] }.sum()   // ← sum of IDF only, not the BM25 max
    if (maxPossible <= 0f) return 0f
    return (raw / maxPossible).coerceIn(0f, 1f)
}
```
**Impact**: For a query like "kotlin kotlin kotlin" and a doc containing "kotlin kotlin" twice, `raw` can exceed `maxPossible` (because per-term BM25 multiplies by `tf * (k1+1) / (tf + k1·b·...)`). The `coerceIn(0,1)` clips to 1.0, flattening the BM25 component of RRF for highly-relevant memories.
**Fix**: divide by the real max BM25, or normalize by the maximum `raw` across candidates in the same `rankCandidates` call (relative normalization).

### B8. [P2] `Retrieval.rankCandidates` uses `rankables.sortedByDescending { selector(it) }` six times — six full sorts of N candidates
**File**: `aura-core/src/main/kotlin/com/aura/memory/Retrieval.kt:106-117`
```kotlin
fun rankBy(selector: (Rankable) -> Float): Map<Int, Int> =
    rankables
        .sortedByDescending { selector(it) }   // ← N log N, six times
        .mapIndexed { rank, r -> r.index to rank + 1 }
        .toMap()
```
**Impact**: With N=20 (RRF pool) and six sorts, the constant factor is 6 × 20 × log(20) ≈ 257 comparisons per recall. Fine for N≤20; grows linearly. Not a hot path today but a future "expand the RRF pool to 100" change would 5x this.
**Fix**: if RERANK_POOL_SIZE grows, consider a single multi-key sort (e.g., lex-sort by (textRank, vectorRank, ...)) or keep ranks in a min-heap.

---

## C. Embeddings (LocalEmbedder, CloudEmbedder, dimension validation, modelId stamping, cache)

### C1. [P0] `CloudEmbedder.dimension()` hard-coded 384 — see B2.

### C2. [P1] `LocalEmbedder` has no in-process cache — every re-embedding re-tokenizes and re-hashes
**File**: `aura-core/src/main/kotlin/com/aura/memory/LocalEmbedder.kt:28-61`
The cloud embedder caches by SHA-256 hex (`CloudEmbedder.kt:62`), but the local embedder has no memoization. Every call to `LocalEmbedder.embed` re-tokenizes, re-hashes, re-normalizes.
**Impact**: `rebuildEmbeddings()` on 500 rows = 500 full re-tokenizes of the same content. For large backups this is real CPU. The cloud cache is 1.5 MB at 1000 entries; adding a local cache of the same size is cheap.
**Fix**: lift the same `LinkedHashMap` cache to a `BaseEmbedder` (or wrap `LocalEmbedder` with a memoizing decorator) and have `CloudEmbedder` use it as the fallback path's cache.

### C3. [P2] `LocalEmbedder.tokenize` adds bigrams asymmetrically — `"kotlin kotlin kotlin"` produces a different bigram set from `"kotlin kotlin"`
**File**: `aura-core/src/main/kotlin/com/aura/memory/LocalEmbedder.kt:63-73`
The hash trick at line 38-40 dedupes via `seenTokens`, so duplicate bigrams contribute only once. But the *count* of unique bigrams differs across rephrasings, biasing cosine toward the repetitive one. Minor for a v0 pseudo-embedder.
**Fix**: deduplicate tokens before adding bigrams; or skip the bigram addition for v0.33 (a pure bag-of-words hash is symmetric).

### C4. [P2] `LocalEmbedder.dimension` is `@Inject constructor(private val dim: Int = 384)` — Hilt could not provide an int, so the `provideLocalEmbedder()` factory at `MemoryModule.kt:636` is required but invisible
**File**: `aura-core/src/main/kotlin/com/aura/memory/MemoryModule.kt:634-636`
```kotlin
@Provides
@Singleton
fun provideLocalEmbedder(): LocalEmbedder = LocalEmbedder()
```
The `@Inject` default-parameter trick works for tests but is a footgun in production — if `provideLocalEmbedder` is ever deleted, Hilt fails to construct the type. A user-configurable embedding dimension (in `UserPreferences.embeddingModel`) would conflict with the hard-coded 384.
**Fix**: make `LocalEmbedder` use a top-level `const val DEFAULT_DIM = 384` and either drop the `@Inject constructor` (rely solely on the factory) or accept a `Config` object.

---

## D. Backup roundtrip (AuraBackup, schema v10/v11, toEntity mappers, insertAll wiring, restore + purge)

### D1. [P0] `MemoryBackup` missing `scope` — see A1.

### D2. [P1] `MemoryEntity` no soft-delete tombstone — see A9.

### D3. [P2] `BackupManager.restore` does not enforce `purgeAll` first — UI gates this via `BackupViewModel.confirmImport(purgeFirst)`, but the manager API is permissive
**File**: `aura-core/src/main/kotlin/com/aura/backup/BackupManager.kt:265-274`; `app/src/main/kotlin/com/aura/ui/settings/BackupViewModel.kt:119-145`
The docstring at `BackupManager.kt:266-270` documents the policy ("The caller is expected to call [purgeAll] first"), and the UI does gate via `purgeFirst: Boolean`. But the manager itself is permissive — a future caller (a CLI tool, a migration script) could call `restore(backup)` without purging, leaving stale rows.
**Impact**: Low today, but the API contract is implicit. A refactor that adds a new caller could silently corrupt the DB.
**Fix**: add a `require` check at the top of `restore` that compares `backup.memories.size` to `memoryDao.countOnce()` and refuses if the backup is much smaller than the current DB, unless `purgeFirst = true` is passed. Or rename the current `restore` to `restoreIncremental` and add a separate `restoreClean` that purges.

### D4. [P2] `BackupManager.snapshot` reads from many DAOs sequentially — N+1 round-trips on the main IO scope
**File**: `aura-core/src/main/kotlin/com/aura/backup/BackupManager.kt:161-238`
The `snapshot` function calls 25+ DAO methods in sequence, each a separate `suspend` round-trip. For a personal install with hundreds of memories and thousands of KG edges, this completes in <1s; for a power user with 50k KG edges and 10k memories, it's seconds.
**Impact**: Slow export on large installs.
**Fix**: use `async { ... }.awaitAll()` for independent tables, or batch the queries.

### D5. [P2] `BackupManager.purgeAll` does not delete from `userProfile` until line 490 — but `memoryEditDao.deleteAll()` is called at line 473 first, before the memories they reference
**File**: `aura-core/src/main/kotlin/com/aura/backup/BackupManager.kt:469-510`
```kotlin
memoryEditDao.deleteAll()       // line 473 — CASCADE will clear memoryEdits rows
documentDao.deleteAll()
creativeProjectDao.deleteAll()
memoryDao.deleteAll()           // line 476 — CASCADE will clear memoryEdits AND memoryFeedback
...
userProfileDao.deleteAll()      // line 490
```
**Impact**: `memory_edits` has `ForeignKey(... onDelete = CASCADE)` to `memories` (see `MemoryEditEntity.kt:19-26`), so deleting memories automatically clears the edit history. The `memoryEditDao.deleteAll()` at line 473 is redundant but not wrong. **However**, `memory_feedback` is a separate table (no foreign key to `memories` declared in `MemoryEntity.kt:46-55`) — so the `memory_feedback` rows are orphaned, not deleted. After `purgeAll`, the `memory_feedback` table has rows pointing to non-existent memory IDs. A future `memoryFeedbackDao.byMemoryId(id)` would return empty (because the memory is gone), but the rows accumulate.
**Fix**: add `memoryFeedbackDao.deleteAll()` to `purgeAll`, or add a CASCADE foreign key to `MemoryFeedbackEntity`.

---

## E. Consolidation / WriteGate (LlmWriteGate, heuristic pre-filter, KG extraction, profile extraction)

### E1. [P0] `ConversationKgExtractor.extract` drops the second-and-later turn when a previous extraction is in flight — silent data loss
**File**: `aura-core/src/main/kotlin/com/aura/kg/ConversationKgExtractor.kt:72-99`
```kotlin
fun extract(turnText: String, provenance: ConversationProvenance = ...) {
    if (turnText.isBlank()) return
    pendingExtraction = PendingExtraction(turnText, provenance)   // ← overwrites prior pending
    debounceJob?.cancel()
    debounceJob = scope.launch {
        delay(DEBOUNCE_MS)   // ← 2s debounce
        val request = pendingExtraction ?: return@launch
        pendingExtraction = null
        if (running) return@launch   // ← silently dropped
        running = true
        try { ... } catch (_: Exception) { /* swallowed */ }
        finally { running = false }
    }
}
```
**Impact**: If KG extraction takes >2s, and the user fires another turn at 1s, 2s, then 4s: the 1s and 2s `pendingExtraction` are overwritten by the 4s one, but the 4s is **dropped at line 84** because `running == true`. Result: the user's first turn's data is processed, but the second and third turns' data is lost. No log, no retry, no buffer. The `extract` is called from `MemoryAugmentedAgenticLoop.kt:596-600` after every agentic run.
**Fix**: replace the single `@Volatile pendingExtraction: PendingExtraction?` with a thread-safe queue (e.g., `ConcurrentLinkedQueue`). Drain the queue in the launched job, processing all pending turns serially. Or buffer N turns in memory and emit them as a batch.

### E2. [P1] `ConversationKgExtractor` swallows all exceptions with no log
**File**: `aura-core/src/main/kotlin/com/aura/kg/ConversationKgExtractor.kt:93-94`
```kotlin
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (_: Exception) {
    // Best effort: do not crash the chat stream.
}
```
**Impact**: A `knowledgeGraphTool.extract` failure (network, parse, OOM) is silently dropped. Combined with E1, the user has no signal that KG extraction is broken. This affects the world-model and taste subsystems that consume KG edges.
**Fix**: `} catch (e: Exception) { android.util.Log.w("KgExtractor", "extract failed", e) }` with a counter for "dropped extractions" surfaced in Settings → Diagnostics.

### E3. [P2] `LlmWriteGate.llmEvaluate` uses `chunk.text` from `ProviderChunk`, but the `extractJson` regex may fail on multi-line JSON values (e.g., category with embedded newline)
**File**: `aura-core/src/main/kotlin/com/aura/memory/LlmWriteGate.kt:80-91, 120-131`
```kotlin
registry.chat(modelId, messages, ChatOptions(temperature = 0.1, maxTokens = 100), emptyList())
    .collect { chunk -> chunk.text?.let { text.append(it) } }
...
private fun extractJson(text: String): String? {
    if (text.startsWith("{")) return text
    val fenceMatch = Regex("```(?:json)?\\s*(\\{.*?})\\s*```", RegexOption.DOT_MATCHES_ALL).find(text)
    ...
    val bareMatch = Regex("\\{(.*?)}", RegexOption.DOT_MATCHES_ALL).find(text)
    ...
}
```
**Impact**: A model that returns `{ "store": true, "category": "preference", "importance": 0.5, "note": "user said\n'set dark mode'" }` (with an embedded newline in the value) will fail the `\\{(.*?)}` non-greedy match because the closing `}` is on a different line and the regex needs `DOT_MATCHES_ALL` to match across newlines — which it has. But if the LLM returns multiple JSON objects in the response (it does occasionally), the first-match strategy picks the first one, which may be a partial output.
**Fix**: prefer a streaming JSON parser (kotlinx.serialization's `Json.parseToJsonElement` with `allowTrailingCommas = false`) and try the entire response first; fall back to the regex only if the whole response fails to parse.

### E4. [P2] `LlmWriteGate` returns null on parse failure, falling back to heuristic — but the heuristic's category is the same `WriteGate` keyword classifier that may have just been wrong
**File**: `aura-core/src/main/kotlin/com/aura/memory/LlmWriteGate.kt:46-58`; `aura-core/src/main/kotlin/com/aura/memory/WriteGate.kt:15-50`
```kotlin
// LlmWriteGate
val llmDecision = runCatching { llmEvaluate(content) }.getOrNull()
return llmDecision ?: heuristicDecision   // ← fallback is the heuristic that *triggered* the LLM call

// WriteGate
fun evaluate(content: String, source: String): Decision {
    ...
    val category = when {
        listOf("i prefer", "i like", ...).any { lower.contains(it) } -> "preference"
        ...
        else -> "fact"   // ← default, not classified
    }
    ...
    return Decision(shouldStore = true, category = category, importance = importance, reason = "classified")
}
```
**Impact**: The LLM gate's *purpose* is to override the heuristic when the heuristic is wrong. If the LLM is unreachable (network, rate limit), we fall back to the same heuristic that triggered the call. The store still happens with the wrong category/importance, and the user has no signal that the LLM gate didn't fire. For a fact like "Schedule a meeting with John at 3pm tomorrow" the heuristic returns `task`, importance 0.6; the LLM might return `task`, importance 0.9 (or `episode`, importance 0.3 if the user said "I met John yesterday"). The fallback is silent.
**Fix**: when the LLM gate fails, log a `android.util.Log.w("LlmWriteGate", "LLM gate failed, falling back to heuristic")` and surface a "LLM gate offline — heuristics only" badge in Settings → Diagnostics.

### E5. [P2] `MemoryAugmentedAgenticLoop.extractProfileFromText` is fire-and-forget with no debounce — a long conversation fires `update(name=...)` 20+ times
**File**: `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:652-660, 681-694`
```kotlin
if (memoryEnabled && lastUserMessage.isNotBlank()) {
    runCatching { extractProfileFromText(lastUserMessage) }
        .onFailure { android.util.Log.w("AgenticLoop", "profile extraction (user) failed: ${it.message}") }
}
val lastAssistant = currentConversation.turns.lastOrNull()?.assistant
if (memoryEnabled && !lastAssistant.isNullOrBlank()) {
    runCatching { extractProfileFromText(lastAssistant) }
        .onFailure { android.util.Log.w("AgenticLoop", "profile extraction (assistant) failed: ${it.message}") }
}
```
**Impact**: Each turn triggers two `userProfileStore.update`/`mergeFacts` calls, each a Room write. For a 10-turn conversation, that's 20 writes. No batch, no debounce. The regex at line 683 fires on every turn for "my name is" — a user who says it once in turn 1 and never again will still have it re-matched in turns 2-10, each writing the same value to `userProfileStore`.
**Fix**: extract once at the end of the run (post-`kgExtractor.extract`), or debounce by `userProfileStore.lastUpdated` (skip re-write if value is unchanged and < 5s old).

### E6. [P2] `WriteGate` classifies empty content as "empty" but doesn't strip the content — a user message of " " (single space) is correctly rejected by `length < 4` but a message of "   abc   " is classified as "fact" with importance 0.5
**File**: `aura-core/src/main/kotlin/com/aura/memory/WriteGate.kt:15-50`
```kotlin
fun evaluate(content: String, source: String): Decision {
    val lower = content.lowercase().trim()
    if (lower.isEmpty()) return Decision(false, reason = "empty")
    if (lower.length < 4) return Decision(false, reason = "too_short")
    ...
}
```
**Impact**: Minor — a memory of "    abc    " (3 visible chars, 11 with whitespace) is rejected, but "   abcd   " (4 chars trimmed) is stored as "fact". The keyword classifier at line 22-33 only matches if the keyword is in `lower`, so whitespace doesn't break classification. This is fine — P2 only because the docstring at line 4 says "v1.5: learned" suggesting a more sophisticated classifier was planned.

---

## F. Cross-cutting / general wiring

### F1. [P1] `MemoryDatabase.version = 13` is hard-coded in two places that must stay in sync
**File**: `aura-core/src/main/kotlin/com/aura/memory/MemoryDatabase.kt:74`; `aura-core/src/main/kotlin/com/aura/memory/MemoryModule.kt:565`
```kotlin
// MemoryDatabase.kt:74
@Database(entities = [...], version = 13, exportSchema = true)

// MemoryModule.kt:565
migrations = arrayOf(MIGRATION_1_2, MIGRATION_2_3, ..., MIGRATION_12_13)
```
**Impact**: If a new entity is added (and `version` is bumped to 14), the developer must remember to add `MIGRATION_13_14` to the array. If they forget, Room throws `IllegalStateException: Migration didn't properly handle...` at runtime on first launch. A test could catch this — `MemoryDatabaseMigrationTest.kt` exists, but the test relies on the developer running it manually.
**Fix**: extract `const val DATABASE_VERSION = 13` to a `companion object` and validate at startup that the array length matches `DATABASE_VERSION - 1`. Or use a Room migration test that runs on every CI build.

### F2. [P2] `MemoryDatabase` declares 23 entities; the `@Provides` methods in `MemoryModule` are 23 boilerplate getters — adding a new entity requires touching 3 files
**File**: `aura-core/src/main/kotlin/com/aura/memory/MemoryDatabase.kt:78-100`; `aura-core/src/main/kotlin/com/aura/memory/MemoryModule.kt:568-632`
**Impact**: Maintenance burden. A new entity added to `MemoryDatabase` requires an `@Provides` in `MemoryModule`. Easy to miss; produces a `MembersInjector` failure at first injection.
**Fix**: use `@Inject constructor` on the database itself (Room supports this), and let Hilt resolve DAOs via `db.dao()`. Drop the explicit `@Provides` block.

### F3. [P2] `BackupManager` `Snapshot` reads `providerKeys.embeddingModel` at line 187 but writes it to the *preferences* field, not the entity — the actual memory-level embedding model is lost
**File**: `aura-core/src/main/kotlin/com/aura/backup/BackupManager.kt:187`
```kotlin
embeddingModel = providerKeys.embeddingModel.takeIf { it.isNotBlank() },
```
This writes the global embedding-model preference to `PreferencesBackup.embeddingModel`, but `MemoryBackup` doesn't carry a per-row embedding model — see A1.
**Impact**: After restore, the global preference is set correctly, but a user who migrated from `nomic-embed-text` to `all-minilm` mid-life and then restored a backup from the `nomic-embed-text` era will rebuild embeddings with the *current* (384-dim `all-minilm`) model. The docstring at `MemoryEntity.kt:30-33` says the field is "for cache invalidation" — but no code path *uses* it. So this is a P2 dead-metadata issue, not a P1 data-corruption issue.

---

## SUMMARY

Sorted by severity, then by subsystem.

| # | Sev | Subsystem | File:Line | Finding |
|---|-----|-----------|-----------|---------|
| A1 | P0 | Storage + Backup | `AuraBackup.kt:124-138`, `BackupManager.kt:598-614` | `MemoryBackup` missing `scope`/`embeddingModel`/`embeddingVersion` — agent-scoped memories leak to `general` on restore |
| A2 | P0 | Storage | `MemoryStore.kt:46,59,141,232,283-284,341,351,413-417,438,474` | 11 `runCatching` blocks swallow exceptions silently — lost feedback, lost edit history, broken dedup |
| B2 | P0 | Embeddings + Recall | `CloudEmbedder.kt:53,129-134` | `dimension()` hard-coded 384 — every real Ollama model silently rejected, falls back to local hash |
| E1 | P0 | Consolidation | `ConversationKgExtractor.kt:72-99` | Turns silently dropped when prior extraction in flight; no retry, no buffer |
| A5 | P1 | Consolidation + Storage | `EvolutionApplySaga.kt:200-220` | `applyConsolidateMemories` drops source memories' scope — agent memories leak to `general` on consolidation |
| A9 | P1 | Storage | `MemoryEntity.kt:14-42`, `MemoryStore.kt:360,368` | No soft-delete `deletedAt` column — `forgetAll`/`forgetByCategory` irreversible |
| A3 | P1 | Storage | `MemoryStore.kt:25-91` | `maybeStore` is dead code in production; if used, `embeddingModel`/`embeddingVersion` not stamped |
| A4 | P1 | Storage | `MemoryStore.kt:25-91,98-108` | `maybeStore`/`store` lack `exactInsertMutex` — check-then-insert race, duplicate memories |
| B1 | P1 | Recall | `MemoryStore.kt:217-234` | Vector-fallback recall path skips `evolutionHooks.onMemoryRecalled` — telemetry under-reports |
| B3 | P1 | Embeddings + Recall | `CloudEmbedder.kt:81-84` | All cloud exceptions caught silently with no log — recall quality degrades invisibly |
| B4 | P1 | Recall | `MemoryReranker.kt:137-154` | Parser is index-aligned, not content-aligned — missing/extra lines silently mis-score to 0.5 |
| C2 | P1 | Embeddings | `LocalEmbedder.kt:28-61` | No in-process cache — re-embedding re-tokenizes every call |
| A6 | P2 | Storage | `MemoryStore.kt:408-420` | `rebuildEmbeddings` doesn't stamp `embeddingModel`/`embeddingVersion` |
| A7 | P2 | Storage | `MemoryModule.kt:535-540` | `MIGRATION_11_12` doesn't backfill `scope` for pre-v0.33 memories |
| A8 | P2 | Storage | `VectorIndex.kt:1-36` | `VectorIndex` injected but never used — recall is brute-force |
| B5 | P2 | Recall | `MemoryAugmentedAgenticLoop.kt:172,231-296` | Recall cache captures `agentId` at loop start — fragile to future multi-agent loops |
| B6 | P2 | Recall | `MemoryAugmentedAgenticLoop.kt:182-186` | `QueryRewriter` synchronous — 5s worst-case latency before first token |
| B7 | P2 | Recall | `BM25.kt:102-110` | `normalizedScore` divisor unsound — clips to 1.0 for high-tf queries, flattens ranking |
| B8 | P2 | Recall | `Retrieval.kt:106-117` | Six full sorts of N candidates per `rankCandidates` call |
| C3 | P2 | Embeddings | `LocalEmbedder.kt:63-73` | Bigram asymmetry biases cosine toward repetitive text |
| C4 | P2 | Embeddings | `MemoryModule.kt:634-636` | `LocalEmbedder` `@Inject` default param is a footgun if `provideLocalEmbedder` is deleted |
| D3 | P2 | Backup | `BackupManager.kt:265-274` | `restore` doesn't enforce `purgeAll` — manager API is permissive |
| D4 | P2 | Backup | `BackupManager.kt:161-238` | `snapshot` does 25+ sequential DAO round-trips |
| D5 | P2 | Backup | `BackupManager.kt:469-510` | `purgeAll` doesn't clear `memory_feedback` — orphans accumulate |
| E2 | P1 | Consolidation | `ConversationKgExtractor.kt:93-94` | All KG extraction exceptions swallowed with no log |
| E3 | P2 | Consolidation | `LlmWriteGate.kt:80-91,120-131` | `extractJson` regex first-match may pick partial JSON output |
| E4 | P2 | Consolidation | `LlmWriteGate.kt:46-58` | LLM-gate fallback to heuristic is silent — no "offline" signal |
| E5 | P2 | Consolidation | `MemoryAugmentedAgenticLoop.kt:652-660,681-694` | `extractProfileFromText` fires every turn, no debounce — 20+ Room writes per conversation |
| E6 | P2 | Consolidation | `WriteGate.kt:15-50` | Whitespace-only messages with 4+ chars (after trim) are stored as "fact" |
| F1 | P1 | Cross-cutting | `MemoryDatabase.kt:74`, `MemoryModule.kt:565` | `version = 13` hard-coded in two places; migration array must match |
| F2 | P2 | Cross-cutting | `MemoryDatabase.kt:78-100`, `MemoryModule.kt:568-632` | 23 boilerplate `@Provides` for DAOs; new entity requires 3-file change |
| F3 | P2 | Backup | `BackupManager.kt:187` | Per-row `embeddingModel` not in `MemoryBackup` — see A1 |

**Total**: 5 P0, 8 P1, 19 P2.

**Top three to fix first** (in order):
1. **A1** — `MemoryBackup` scope leak is silent, cross-tenant, and triggered by every restore.
2. **B2** — `CloudEmbedder.dimension()` hard-coded 384 means any non-`all-minilm` user has a silently broken cloud path.
3. **E1** — KG extraction drops turns silently when prior extraction is in flight; affects the world-model subsystem.
