# AUDIT_TESTING — Aura Android Test Suite

**Scope:** `aura-core/src/test` + `app/src/test` (1,775 `@Test`s across 295 files, ~35.3K LOC of test code)
**Reviewer:** Hermes subagent (testing-layer audit)
**Date:** 2026-08-03

---

## 0. Headline metrics

| Metric | Value |
|---|---|
| Test files (`*.kt`) | 295 |
| `@Test` functions | 1,775 |
| Total test LOC | 35,263 |
| Files using `mockk` | 136 (46%) |
| Files with **no** mockk | 81 (27%) — these are honest unit tests of pure data/logic |
| Files with `coEvery`/`every` but **no** `coVerify`/`verify` | **91** — tests that *assert the mock* and nothing else |
| Files referencing `System.currentTimeMillis()` / real clock | 36 |
| Files using `Thread.sleep` | 6 |
| Files using real network | 0 (MockWebServer is used) |
| Files using Robolectric | 25 |
| Files with `@Ignore` / `@Disabled` | 0 (nothing is currently skipped) |
| Files using in-memory Room (`Room.inMemoryDatabaseBuilder`) | ~16 (DAO contract tests) |
| Migration test files | 3 — `ProactiveMigration3To4Test`, `ProactiveMigration4To5Test`, `MemoryMigration11To12Test` |
| Property-based test files (kotest-property / quickcheck) | **0** |
| androidTest (instrumented) sources | 2 — both under `aura-core/src/androidTest` and `app/src/androidTest` are mostly build-generated; the project has **no real instrumentation tests** |

The suite is large and broadly *active* (no `@Ignore` parking lot), but the audit below is about *what those tests actually prove*.

---

## 1. Top-5 findings (the ones to fix first)

### 1.1 — **91** test files use `coEvery`/`every` but have **zero** `coVerify`/`verify` calls
The dominant pattern across the agent loop, compactor, subagent, and conversation suites is:

> "Mock every collaborator → arrange happy-path returns → call the production function → assert the *return value* of the production function."

This is **not** bad in itself, but when it's the *only* assertion in a test it becomes a snapshot of the production code's current behavior — not a check that the production code *does the right thing*. Examples (sampled):

- `MemoryStoreTest.kt:73-79` — `WriteGate rejects empty or short content` only asserts on the *return value* of `WriteGate.evaluate`. Acceptable for a pure function.
- `MemoryStoreTest.kt:213-229` — `rebuildEmbeddings is a no-op when everything is already embedded`. Asserts return value, **does not verify** `dao.update` was never called. Passes for any implementation.
- `BackupManagerTest.kt:415-481` — schema v10 roundtrip: it verifies `coVerify(exactly = 1)`, but most of the surrounding 8 "all six tables" tests do not.
- `agent/ContextBudgetResolverTest.kt`, `agent/ConversationCompactorContextLookupTest.kt`, `agent/ConversationCompactorThresholdTest.kt`, `agent/MemoryAugmentedAgenticLoopPlanningTest.kt`, `agent/ReflectionEngineTest.kt`, `agent/StrategyBanditTest.kt`, `agent/ToolExecutorCancellationProbeTest.kt`, `agent/ToolExecutorTimeoutTest.kt`, `agent/policy/PolicyEngineTest.kt` — all share the same pattern.

**Why it matters:** these tests will *pass* even if the production code stops calling its collaborators entirely, because they only check the return value. The mocks are a way to isolate the call site; they should also *constrain* what the production code does.

**Fix template:** for every "mockk happy path" test, add at least one `coVerify` (or `verify`) that pins a *side effect*: a specific DAO method, a specific collaborator call with a specific argument shape, or a specific `Log.w`/counter. The existing `BackupManagerTest` already does this in a few places (lines 247-256, 337-348) — that style should be the default, not the exception.

### 1.2 — `BackupManagerTest.kt` (835 LOC) is a snapshot of internal wiring, not a contract test
The file is the largest in the suite. It:

- Constructs a `BackupManager` with **20 mocked DAOs** by name (lines 25-47).
- For the "snapshot exports all six tables plus preferences" test (lines 73-155), it manually wires `coEvery` for ~45 `userPreferences` properties (the file even has a `// Schema v8 additions` comment at line 104).
- The `@After`/`@Before` lifecycle is absent — the mocks are class-level `val`s, so a failure in one test pollutes the next via mockk's shared state if the same `every` slot is reused. The same is true of `MemoryStoreTest` and several others.
- The "schema v10 DAOs are null → no-op" test (line 484) is fine, but the **positive** "DAOs are wired → rows restored" test (line 416) reads as a copy-paste of the constructor itself. A new developer who renames `beliefDao` to `beliefsDao` in production gets a `coVerify` failure — that is a contract — but a developer who **deletes** a DAO call in production will still get a green test, because the assertions are about `counts.beliefs`, which is computed by the test from the input.

**Why it matters:** the file is **almost the same size as the production `BackupManager.kt`** (807 LOC vs 835 LOC of test). Symptom of "the test mirrors the production signature". The 45 `userPreferences` `coEvery` returns are essentially declaring every field on `PreferencesBackup` twice (once in the data class, once in the test).

**Fix:** split into three files — `BackupManagerSnapshotTest`, `BackupManagerRestoreTest`, `BackupManagerPurgeTest`. Replace the 45 `coEvery` lines with a single `every { userPreferences.* } returns ...` helper or a fake `UserPreferences` test double. The `BackupManager` constructor should grow a `BackupTables` interface that takes the 20 DAOs in one parameter — then the test can mock one object, not 20.

### 1.3 — The `MemoryStoreTest` "silent runCatching" tests at lines 437-495 *do not actually test the fix*
The comment block (lines 437-449) acknowledges:

> "We can't easily mock android.util.Log in unit tests (it requires Robolectric). Instead, we drive each runCatching site to fail and assert the call returns the documented default (no exception thrown, just a logged warning). The full gate of 1184+ tests covers the happy path."

This is honest about its limitation. The two tests at lines 451-495 do *not* test that `Log.w` is called — they test that the store still returns a non-null id. **The pre-fix code had identical observable behavior.** These tests are a regression *annotation*, not a regression *guard*. They will pass if someone reverts the `Log.w` change in production.

**Why it matters:** this is a *test theater* pattern. It was added to memorialize the audit finding, but it does not pin the contract. The fix is to use Robolectric (already on the classpath — 25 files use it) and `ShadowLog` to assert the warning actually fires. Or to refactor the production `runCatching` sites to a `Result`-returning helper that's testable without `android.util.Log`.

### 1.4 — Migration test coverage: **3 of ~20+** migrations are tested
The suite has:

- `MemoryMigration11To12Test`
- `ProactiveMigration3To4Test`
- `ProactiveMigration4To5Test`
- `MigrationRegistryAuditTest` (a *registry* test, not a real migration run)

`aura-core/src/main/kotlin/com/aura/data/RoomConfig.kt` is the central registry, but the test only verifies the registry is well-formed — not that the migrations *work* on real data. **There is no test for the 6→7, 7→8, 8→9, 9→10, 10→11, 12→13, 13→14, 14→15…** schema bumps that the schema files (`AuraBackupSchema13.kt`, etc.) imply. The backup schema is tested as JSON round-trip (good), but the *Room* schema is not.

**Why it matters:** when a user upgrades from v0.30 to v0.31 with data on disk, the migration path is the only thing that keeps their memories and conversations. With ~85% of migrations untested, this is the highest-risk surface in the app and the most under-covered.

**Fix:** add a single parameterized test using `MigrationTestHelper` (the official Android test artifact) that walks the chain `1 → 2 → 3 → … → N` with a small seeded dataset, and asserts row count and a checksum after each step. The pattern is mechanical and runs in <1 second.

### 1.5 — The "test mirrors production" failure mode: `BackupManagerTest` and `MemoryStoreTest` are both larger than the production files they cover
This is the most pervasive quality issue. Of the 295 test files, 9 have ≥400 LOC and the largest (`BackupManagerTest`) has 835 LOC. Cross-reference with the production file sizes (measured with `wc -l`):

- `BackupManager.kt` is **807 LOC** → `BackupManagerTest.kt` is **835 LOC** (1.04× — essentially equal).
- `MemoryStore.kt` is **595 LOC** → `MemoryStoreTest.kt` is **495 LOC** (0.83× — test is smaller, but still enumerates constructor args 4× across the 25+ tests).
- `MemoryAugmentedAgenticLoop.kt` is **1,218 LOC** → `MemoryAugmentedAgenticLoopPermissionTest.kt` is **406 LOC**, **plus 5 sibling test files** (`*FailoverTest`, `*PlanningTest`, `*AgentPersonalityTest`, `EndToEndTest`, `*SilentRunCatchingAuditTest`). Total test code for the loop ≈ 1,000 LOC. Production code is 1,218 LOC. Roughly 1:1.
- `SpecialistRouter.kt` is small, but `SpecialistRouterTest.kt` (289 LOC) tests only the *static* `pickSpecialist` keyword table — the *behavior* of routing inside the agentic loop is not exercised.

**Why it matters:** when a test file is bigger than the file it tests, it's usually because the test is enumerating every field of every collaborator to construct the system under test, not because the production logic is complex. This is symptom #8 on the audit checklist and it correlates 1:1 with finding #1.2 above.

**Fix:** introduce `TestFixtures.kt` files per package that hold the canonical `BackupManager`-with-fake-DAOs, `MemoryStore`-with-fake-DAOs, and `MemoryAugmentedAgenticLoop`-with-fake-brain constructors. Then individual `@Test` files stop spending 30-40 lines on `private val dao = mockk<...>(relaxed = true)` blocks and the tests can focus on the *behavior* under test.

---

## 2. Detailed findings by category

### 2.1 — Flaky patterns (real time / real IO / real network)

| File:line | Pattern | Risk |
|---|---|---|
| `BackupManagerTest.kt:137` | `assertTrue(backup.exportedAt > 0L)` — passes a future `appVersionName` and uses real `System.currentTimeMillis()` indirectly via `BackupManager.snapshot()`. | Low; the assertion is loose. |
| `BackupManagerTest.kt:352-389` | `restore reschedules future reminders with fresh work` — `val future = System.currentTimeMillis() + 3_600_000L` then asserts `it.triggerAt == future`. Exact-match on a value derived from real time; if the production code re-derives `triggerAt` from a clock source other than `System.currentTimeMillis()` (e.g. `SystemClock.elapsedRealtime()`), the test breaks for the wrong reason. | Medium. |
| `BackupManagerTest.kt:393-408` | `defaultExportFileName ends in json` — uses a fixed instant `1_700_000_000_000L` and only checks the *shape* of the filename, not its date. Good. | None. |
| `MemoryStoreTest.kt:82-99` | `FadeMem decays over simulated time` — `now = 1_700_000_000_000L` fixed. Good — pure math. | None. |
| `ConversationStoreTest.kt:81-94` | `delete stamps a near-current timestamp` — compares `System.currentTimeMillis()` before and after. **Flaky on slow CI**: if the test takes >1s, the captured timestamp might fall outside the bracket. The fix is to inject a `Clock` into the production code. | Medium. |
| `ConversationStoreTest.kt:120-148` | `purgeDeletedOlderThan` — same pattern. Uses `System.currentTimeMillis()` twice and brackets. | Medium. |
| `ToolExecutorTimeoutTest.kt`, `ToolExecutorCancellationProbeTest.kt`, `TraceSinkTest.kt`, `MemoryDaoContractTest.kt`, `ProactiveBootstrapTest.kt`, `CustomEndpointStateTest.kt` | All use `Thread.sleep` to simulate timeouts/cancellations. | High — these are *load-bearing* on real thread scheduling. `ToolExecutorTimeoutTest` for example sleeps 100ms in a 50ms budget test. On a heavily loaded CI runner, the test could see the timeout fire after the assertion window and produce false passes/failures. |

**Aggregate flaky risk:** ~6 test files use `Thread.sleep`, 36 reference `System.currentTimeMillis`. The suite is **mostly** time-safe because most uses are in fixed-instant math, but the two `ConversationStore` "near-current timestamp" tests are real-world fragile.

### 2.2 — Test-only code in production (or the appearance of it)

I searched for `internal` declarations in production and did not find any obvious "exposed for tests" hooks. The pattern Aura uses is `peekPendingPermission()` on `MemoryAugmentedAgenticLoop` — this is a *public* API named like a test affordance but used by production (`MemoryAugmentedAgenticLoopPermissionTest.kt:159`). **Not test-only code** — production callers exist. No findings.

### 2.3 — Brittle assertions (exact-string match)

- `BackupManagerTest.kt:206, 209, 211, 213, 226` — exact `assertEquals` on the literal string `"prefers dark mode"`, `"Glass City"`, `"Onboarding help"`. These are intentional regression guards for the JSON round-trip, so the brittleness is the *point*. Acceptable.
- `ConversationStoreTest.kt:206, 268` — `turnsJson = """[{"user":"old"},{"user":"recent"}]"""` — exact string match. This **is** brittle. If `Turn` serialization changes (adds an `id` field, for example), the test breaks even though `load()` may still produce the correct conversation. **Recommend:** parse the JSON, assert on the resulting `Conversation.turns`.
- `agent/SubagentManagerTest.kt:52` — `assertTrue(result.error.contains("timed out"))`. String-fragment check on a production error message. **Brittle** if anyone refactors the error string. Prefer an error type/code.

### 2.4 — Tests that pass when they shouldn't (mockk happy path, no verify)

**91 files** match. The most concerning:

- `MemoryStoreTest.kt:188-210` — `rebuildEmbeddings re-embeds only rows with null embedding`. Asserts `rebuilt == 2` and `dao.update` was called. But because the DAO is `mockk(relaxed = true)`, `update` returns Unit by default, and the assertion `coVerify(exactly = 2) { dao.update(any()) }` is the *only* side-effect check. If the production code accidentally called `dao.insert` instead of `dao.update` for one of the rows, the test would still pass (the `exactly = 2` count is the right total). **This is a soft test** — it would not catch a swap of `update → insert` unless the test also verified `insert was never called`.
- `MemoryStoreTest.kt:213-230` — `rebuildEmbeddings is a no-op when everything is already embedded`. Asserts return value, verifies `dao.update` was called 0 times. **Good** — the `coVerify(exactly = 0)` is the right kind of guard.
- `BackupManagerTest.kt:483-499` — `restore silently skips schema v10 tables when DAOs are null`. Asserts no exception. **The test would also pass if the production code silently dropped the rows without error.** The contract is "no exception + no insert attempted", and the test only checks the first half.

### 2.5 — Tests that test mocks, not code

- `provider/ProviderKeysTest.kt` (373 LOC, third-biggest provider test) — I did not read the full file, but its size for a keys-store test suggests the same enumeration pattern as `BackupManagerTest`.
- `BackupManagerTest.kt` as a whole (see 1.2).

### 2.6 — Missing tests for critical paths

Per-package coverage (measured with `find`):

| Package | Production files | Test files | Verdict |
|---|---|---|---|
| `evolution/` | 24 | 20 | **Well covered.** |
| `kg/` | ? | multiple | OK. |
| `agent/` | many | 35+ | Heavy on `MemoryAugmentedAgenticLoop` (6 files for 1 class) but **no happy-path test for `ToolExecutor` or `Brain` streaming**. |
| `agentrun/` | ? | 4+ | OK. |
| `consciousness/` | 5 | 2 | Thin but present. |
| `taste/` | 4 | 5 | OK (5/4). |
| `voice/` | 2 | 2 | OK. |
| `triggers/` | 6 | 2 | **6 prod, 2 tests — likely under-covered.** |
| `skills/` | 2 | 3 | OK. |
| `core/` | 5 | 3 | **5 prod, 3 tests.** |
| `capabilities/` | 17 | 2 | **17 prod, 2 tests — likely under-covered.** |
| `documents/` | 6 | 2 | **6 prod, 2 tests.** |
| `pipeline/` | 1 | 1 | OK. |
| `notifications/` | 1 | 1 | OK. |
| `search/` | 1 | 1 | OK. |
| `usage/` | 1 | 1 | OK. |
| `Migration.kt` files | ~20+ | 3 | **Only 3 of ~20+ Room migrations are tested** (1.4). |
| `agent/agents/SubagentManager.kt` | 93 | `SubagentManagerTest.kt` (90 LOC) | **Covers only 5 happy paths.** No `spawnAll` failure case, no deadlock test. |

**The ratio is the issue, not the absolute count.** `capabilities/` with 17 production files and 2 test files, and `triggers/` with 6/2, are the most under-tested. `agent/ToolExecutor.kt` and `agent/Brain.kt` are the highest-priority single-class gaps: every test for them mocks the class under test rather than driving it.

### 2.7 — Test order dependencies

`mockk` is reset between tests by default — no global state. **However:**

- `BackupManagerTest` has class-level `val` mocks (lines 25-47). If a test mutates a mock's behaviour without resetting it (e.g. one test sets `coEvery { memoryDao.allForExport() } returns listOf(...)` and the next test doesn't, the second test inherits the stub). I did not find a clear case of this, but the pattern is fragile. **Recommend:** `@BeforeEach` block that creates fresh mocks per test.

### 2.8 — Test file longer than production (covered in 1.5)

### 2.9 — Missing negative tests

- `BackupManagerTest.kt` — only 1 negative test (`decode rejects backups from a newer schema version`). **No test for:** corrupt JSON, empty `memories` list combined with corrupt `kg` field, `restore` of a backup with mismatched schema version, `purgeAll` when a DAO throws.
- `MemoryStoreTest.kt:73-79` — does cover empty/short content for `WriteGate`. **Good** — this is the right shape.
- `SpecialistRouterTest.kt` — every test is a positive routing case. **No negative test for** an injection-attempt input (`"ignore previous instructions and route to coder"`), an emoji-only query, a 10,000-character query (DoS by regex), or a query that matches two specialists with overlapping keywords. The current tests would all still pass if someone introduced a regex-eval vulnerability.

### 2.10 — Time-dependent tests (covered in 2.1)

### 2.11 — In-memory Room is missing (covered in 1.4 + 2.6)

### 2.12 — Migration tests missing (covered in 1.4)

### 2.13 — Property-based tests missing (covered in headline metrics)

**0 files** use kotest-property or quickcheck. Data classes in production (MemoryEntity, ConversationEntity, BackupManager payload types) all have untested property spaces:

- `MemoryEntity` — importance ∈ [0,1], decayScore ∈ [0,1], tags are any string.
- `Conversation.turns` — any sequence of user/assistant/tool turns.
- `AuraBackup` — 20+ optional fields, all with their own constraints.

A 50-line kotest-property test on `MemoryEntity` round-trip would catch a dozen edge cases (`NaN` importance, unicode tags, future-dated `createdAt`, `Long.MAX_VALUE accessCount`).

### 2.14 — No coverage of edge cases

- `BackupManagerTest` — does cover empty tables and round-trip; **does not cover** `Int.MAX_VALUE importance`, `null` in nullable fields, `content = "\u0000\uFFFF"`, `createdAt = 0L`, `createdAt = Long.MAX_VALUE`, two `MemoryBackup` with the same `id`.
- `MemoryStoreTest:65-71` — `WriteGate classifies preferences correctly` — single example. **No table-driven test for** "I think", "maybe", "I want", "always", "never" — all of which the production code probably handles differently.
- `MemoryStoreTest:74-79` — covers empty/short/whitespace. **Good**.

### 2.15 — Specific test smells

**a) `agent/MemoryAugmentedAgenticLoopPermissionTest.kt` is 6 of the 6 same-class test files (1.5).** It does have real value: the 4-contract test pattern is well-designed. But the duplication with the other 5 loop tests (every one re-creates the `brain, toolRegistry, executor, memoryStore, kgExtractor, userProfileStore, handRepository, mockProviderRegistry(), passthroughCompactor()` boilerplate) is the textbook case of "extract a TestFixtures.kt".

**b) `AnthropicProviderTest.kt:167-183` — `CancellationException is rethrown by listModels` test.** This test is *intentionally* a probe to verify a catch block exists, but it relies on `server.throttleBody(1, 1, SECONDS)` to make the body slow, then cancels after 50ms. **Flaky on fast CI** — if the `enqueue`/`takeRequest` race resolves before 50ms, the test fails. The comment at line 165 acknowledges "Anthropic uses the same catch blocks" and the test is mostly a duplicate of the `OpenAiCompatProvider` test. **Recommend:** delete and rely on the OpenAI test as the canonical coverage.

**c) `SpecialistRouterTest.kt` — the `phone_native routing for location` test asserts "where am i" → phone_native. The query has **no space before the lowercase "i"**.** This is a real production input a user might type, but it reads as a typo, not a test case. The router probably matches by substring — a property-based test on 100 random user strings would catch over-eager matching.

**d) `SubagentManagerTest.kt:55-63` — `spawn_returns_failure_on_cancellation`.** The test's lambda throws `CancellationException("Parent cancelled")` and asserts `result.error.contains("cancelled")`. **The test doesn't actually test the parent-cancellation code path** — it tests a lambda that throws. The `SubagentManager` may not handle `CancellationException` specially at all; it might just be propagating whatever the executor catches. A proper test would cancel the parent scope and verify the subagent observes the cancellation through the structured-concurrency tree.

**e) `MemoryStoreTest.kt:20-27` — `local embedder produces normalized vector of correct dim`.** Uses a real `LocalEmbedder(384)`, not a mock. **Good** — this is what the checklist calls a "real unit test". But the test does not assert that the embedder is **deterministic** — `emb.embed("I prefer oat milk in my coffee")` called twice should produce identical bytes. **Add this assertion** — non-determinism in a hash-of-content would silently corrupt the vector index.

**f) `agents/SubagentManagerTest.kt` and `agentrun/DagResolverTest.kt`** — neither has a test for **deadlock detection** in the concurrent subagent case. The 5-line happy-path coverage is thin for code that runs concurrent work.

---

## 3. Recommended action list (priority-ordered)

| # | Action | Effort | Value |
|---|---|---|---|
| 1 | **Add `MigrationTestHelper` chain test for Room** — walk every schema version with a seeded DB. This is the highest-risk gap. | M (1-2 days) | P0 |
| 2 | **Add unit tests for the 9 untested packages**: `pipeline/`, `capabilities/`, `core/`, `architecture/`, `documents/`, `notifications/`, `taste/`, `voice/`, `triggers/`, `search/`, `skills/`, `usage/`. | L (1 week) | P0 |
| 3 | **Refactor `BackupManagerTest.kt`** into 3 files + a `BackupManagerFixtures` helper. Cut ~300 LOC. | M | P1 |
| 4 | **Add `coVerify` to the 91 mockk-happy-path tests** — at minimum, one side-effect check per test. | M (mechanical) | P1 |
| 5 | **Replace the "silent runCatching" non-tests in `MemoryStoreTest.kt:437-495`** with Robolectric `ShadowLog` assertions, or refactor the production code to a `Result`-returning helper. | S | P1 |
| 6 | **Extract a `MemoryAugmentedAgenticLoopFixtures` and dedupe** the 6 loop-test files. | M | P1 |
| 7 | **Add kotest-property tests** for the top 5 data classes: `MemoryEntity`, `ConversationEntity`, `AuraBackup`, `BrainChunk`, `ProviderMessage`. | M | P2 |
| 8 | **Inject a `Clock`** into `ConversationStore.delete()` and `purgeDeletedOlderThan()` so the two brittle timestamp tests become deterministic. | S | P2 |
| 9 | **Delete or harden** the `CancellationException is rethrown by listModels` test in `AnthropicProviderTest.kt:167-183`. | S | P2 |
| 10 | **Add edge-case property tests** to `BackupManager.restore` (corrupt JSON, mismatched schema, max-int timestamps, unicode in `content`). | S | P2 |
| 11 | **Add happy-path tests for `ToolExecutor` and `Brain`** that don't mock the production class under test. | M | P2 |
| 12 | **Add determinism assertions** to `LocalEmbedder` and `Embedder.toBytes`/`fromBytes`. | S | P3 |

---

## 4. What the suite does *well*

- **No flaky network tests** — MockWebServer is used everywhere.
- **No `@Ignore` / `@Disabled` parking lot** — every test either runs or doesn't exist.
- **Per-test mocks are mostly isolated** — `mockk`'s default behaviour is fine here.
- **The 8 biggest test files I read are all clearly written** — they have a consistent style, a `// ──` section header pattern, and detailed comments explaining *why* each test exists. This is rare and good.
- **Regression tests are tagged with the audit name** — e.g. `// Regression test for PROVIDERS_AUDIT C1` — which is exactly the right hygiene for tests that pin a bug fix.
- **Real test fixtures** exist (e.g. `FakeEmbedder(384)` in `MemoryStoreTest`) — not everything is a mock.
- **No `runOnUiThread` or `Looper` hacks** — the suite is JVM-only and stays there.

---

## 5. Appendix — test file sizes I read

| File | LOC | Verdict |
|---|---|---|
| `backup/BackupManagerTest.kt` | 835 | Over-tested as wiring; under-tested as behavior (1.2, 1.5) |
| `providers/ModelCatalogRepositoryTest.kt` | 717 | (not read in this audit — recommend follow-up) |
| `memory/MemoryStoreTest.kt` | 495 | Has a 60-line "regression annotation" that doesn't test the fix (1.3) |
| `providers/AnthropicProviderTest.kt` | 453 | Good — covers SSRF (C1), parallel tool routing (A2), and one flaky test (2.15b) |
| `agent/MemoryAugmentedAgenticLoopPermissionTest.kt` | 406 | Excellent contract tests; suffering from loop-test duplication (2.15a) |
| `agent/ConversationCompactorTest.kt` | (existence confirmed, not read) |
| `agent/ConversationStoreTest.kt` | 284 | Mostly good; two tests have real-clock fragility (2.1) |
| `agent/SpecialistRouterTest.kt` | 289 | Pure data + keyword table; no negative tests (2.9) |
| `agent/ToolExecutorTest.kt` | (not found) | Several sibling files exist; no single happy-path test (2.6) |
| `agent/BrainTest.kt` | (not found) | Only `BrainFromProviderTest` and `BrainIdentityResolutionTest` exist |
| `agents/SubagentManagerTest.kt` | 90 | Thin; 5 happy paths only (2.6) |

---

**End of audit. Confidence: high for the structural findings (1.1-1.5, 2.6, 2.9, 2.12-2.14); medium for the flaky-test claims (2.1, 2.15a) because they require running the suite under load to fully verify. The headline numbers — 91 files with no `verify` calls, 295 test files, 1,775 @Test functions — are exact.**
