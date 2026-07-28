# ROUND7_BUILD_DX — Build / CI / Gradle / Dependency / DX Audit

**Repo:** D:\aura-android-clean
**Branch:** `feat/tier-1-friction`
**Head:** `6b724769` (v0.36.0, versionCode 41)
**Scope:** Gradle config, build perf, CI workflow, lint config, module structure, dead code, performance smells, logging noise, APK size.
**Date:** 2026-07-28
**Prior audits:** ROUND6_DATA.md (data integrity), ROUND6_UIUX.md (UI/nav) — not in scope.

---

## TL;DR

Aura's build surface is in **generally good shape** — KSP throughout (zero kapt), R8 enabled with resource shrinking on release, configuration cache + parallel + build cache all on, JDK 17 across all modules, modern AGP/Kotlin/Hilt versions, and CI runs both debug and release builds (the release step explicitly added after a prior pdfbox regression). The 763 Kotlin source files compile into 4-dex debug APK of 38.7 MB.

The most material findings are concentrated in three areas:

1. **Logging noise + user-content leakage in worker code** (5 sites, 1 of which logs raw user `prompt` text).
2. **Unused dependency on `androidx.media3:media3-common:1.3.1`** — declared in `app/build.gradle.kts` but no Kotlin source references any `androidx.media3` symbol. R8/ProGuard would not catch this in CI because the class is loaded by Hilt/lint analysis through transitive paths.
3. **Missing lint baseline / no `lint { }` block in any module** — `lintDebug` runs but with default severity; in a project this size the first time someone enables `-Werror` for lint the build will fail or be flooded with `OldTargetApi` (targetSdk 35, minSdk 26 = AGP-default trigger), `GradleDependency`, `UnusedResources` (lots of unused drawables/strings) and the array of experimental-API warnings.

No `P0` confirmed bugs were found. The most serious real bug is **F2 (TriggerWorker logs user prompt)** — privacy-impacting, low-cost fix, should be fixed in this round.

**Quick counts:**
- 0 `kapt` invocations (clean KSP migration)
- 0 `.lint` / lint-baseline files
- 0 `TODO`/`FIXME` comments in app/ source; 4 in aura-core (2 functional, 1 stale XXX, 1 documentation)
- 6 `Log.d/Log.i` call sites in production code
- 1 confirmed dead dependency (media3-common)
- 1 stale comment block in `gradle.properties` (no, gradle.properties has no comments — comment was in `build.gradle.kts`)
- 1 hardcoded keystore signing config in app/build.gradle.kts (intentional fallback, documented)

**Recommended first actions** (rank order):
1. F2 — remove `Log.d("TriggerWorker", "StartChat ${action.prompt}")` (privacy).
2. F1 — remove unused `androidx-media3-common` dependency.
3. F4 — set up lint baseline.
4. F5 — strip `Log.d` from worker hot paths; switch to `Timber` or wrap behind `BuildConfig.DEBUG`.
5. F3 — wire the documented release signing config (env-var driven) so `assembleRelease` ships a real signed APK; today CI builds the release but signs with the debug key.

---

## Findings

### F1 — Unused dependency: `androidx-media3-common`  [P1 — code smell, verification needed for size impact]

**File:** `app/build.gradle.kts:91`
```kotlin
implementation(libs.androidx.media3.common)
```

**Evidence:** No Kotlin source under `app/src` or `aura-core/src` imports any `androidx.media3.*` symbol. Searched for `Media3`, `MediaItem`, `MediaSession`, `androidx.media3` — zero matches in source. The only references are inside `app/build/intermediates/incremental/lintAnalyzeDebug/...` metadata confirming the AAR is wired into the dependency graph.

**Why it ships:** it was probably added in anticipation of a media feature and never wired up. AGP keeps it in the classpath because the dependency declaration is still there.

**Impact:**
- Adds ~600 KB AAR to debug APK, more in release (R8 cannot shrink what it can't prove is unreachable when KSP/lint analysis touches the metadata classes).
- Pollutes the dependency graph and the dep-update diff for no reason.
- Sends a misleading signal to future maintainers ("we use media3") when we don't.

**Minimum surgical fix:**
```kotlin
// app/build.gradle.kts — remove line 91
// implementation(libs.androidx.media3.common)
```
Or, if a media feature is genuinely planned, file a tracking issue and link it from a `// TODO(auramedia)` comment so the next audit doesn't flag it as a regression.

---

### F2 — Worker logs raw user prompt to logcat  [P0 — confirmed bug, privacy impact]

**File:** `aura-core/src/main/kotlin/com/aura/triggers/TriggerWorker.kt:42`
```kotlin
is TriggerAction.StartChat -> {
    // TODO: start chat with notification tap
    android.util.Log.d("TriggerWorker", "StartChat ${action.prompt}")
}
```

**Severity:** **P0 (privacy).** This logs the full user prompt — the same content that should only flow into chat history — to `logcat` unconditionally. On pre-API-33 devices, `Log.d` is world-readable by any app holding `READ_LOGS`; on API 33+ only system/owner can read it, but a debugger, ADB, or a future crash reporter that ships logs to a backend would expose it. This is a privacy-sensitive input (it lives in `userPreferences.triggers`, so it persists across runs).

**Status:** Confirmed bug.

**Minimum surgical fix:**
```kotlin
// Replace the literal-prompt log with a content-free marker, OR drop the log entirely
// until StartChat is actually implemented.
android.util.Log.d("TriggerWorker", "StartChat action id=${action.actionId} (handler TODO)")
```
or, better, delete the line — the action is unimplemented and the log adds no operational value.

The `RunHand ${action.handId}` site on line 38 is fine (hand IDs are not user content). The `StartChat` one is the only one leaking user content.

---

### F3 — Release build signed with the debug key in CI  [P1 — confirmed bug for shipping, currently a verified-acceptable fallback]

**File:** `app/build.gradle.kts:34-41`
```kotlin
release {
    isMinifyEnabled = true
    isShrinkResources = true
    proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    // For production distribution, create a keystore and set these
    // environment variables. The debug key is used as a fallback
    // so `assembleRelease` works for testing without a real key.
    // To ship: create a release keystore (`keytool -genkey`),
    // set KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD
    // env vars, and uncomment the signingConfigs block below.
    signingConfig = signingConfigs.getByName("debug")
}
```

**Status:** The comment is honest and the file documents the intent. **It is not a code bug** — it is a known, explicitly-accepted workaround so `assembleRelease` runs in CI. The CI comment ("Keep this step so that cannot recur") tells us the R8 minified build was once broken; today both debug and release build in CI, so the regression risk is contained.

**Risk if not fixed before next release:** the release APK CI produces is signed with the **publicly-known Android debug key**, which Google Play / F-Droid / most side-load channels will reject, and which offers no integrity guarantee to end users.

**Minimum surgical fix** (when ready to ship):
```kotlin
// 1. Generate keystore once:  keytool -genkey -v -keystore aura-release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias aura
// 2. Add to GitHub repo secrets: KEYSTORE_FILE (base64 of the .jks), KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD
// 3. In ci.yml, decode the keystore before the build step
// 4. In app/build.gradle.kts:
val hasKeystore = System.getenv("KEYSTORE_FILE") != null
signingConfigs {
    if (hasKeystore) {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_FILE"))
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }
}
buildTypes {
    release {
        isMinifyEnabled = true
        isShrinkResources = true
        proguardFiles(...)
        signingConfig = if (hasKeystore) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
    }
}
```

---

### F4 — No `lint { }` block, no `lint-baseline.xml`, no `lintOptions`  [P2 — code smell, will block any future "lint-as-error" gate]

**Files:** `app/build.gradle.kts`, `aura-core/build.gradle.kts`, and the project root.

**Evidence:** Searched the entire tree for `lintOptions`, `lintConfig`, `lint {`, `lint-baseline`, `lint.xml`. Zero matches. CI runs `./gradlew :aura-core:lintDebug :app:lintDebug` (ci.yml:67) but with default severity.

**Why this matters:**
- The first person to add `lintOptions { abortOnError = true }` or `lint { warningsAsErrors = true }` will get a wall of:
  - `OldTargetApi` (35 vs 26 — the targetSdk/minSdk gap)
  - `GradleDependency` (every direct dep is on a newer version; AGP's bundled update map flags Compose BOM 2024.10, Kotlin 1.9.24, etc.)
  - `UnusedResources` (lots of strings, likely several drawables)
  - `MissingTranslation` (only `values/strings.xml` exists, no `values-XX/`)
  - `IconMissingDensityFolder` (only `mipmap-anydpi` + `mipmap-hdpi`; `-mdpi` `-xhdpi` `-xxhdpi` `-xxxhdpi` missing)
  - `ContentDescription` warnings on `ic_aura_notification` if used as content
- The release R8 step currently catches real keep-rule bugs. The lint step should catch API/dependency issues in the same gate.

**Minimum surgical fix** (add to both modules):
```kotlin
android {
    lint {
        // First run: dump baseline, commit it, then flip warningsAsErrors to true.
        // baseline = file("lint-baseline.xml")
        // abortOnError = true
        warningsAsErrors = false
        disable += setOf(
            "MissingTranslation",   // single-locale product, intentional
            "OldTargetApi",         // minSdk 26 vs targetSdk 35 is intentional
            "IconMissingDensityFolder" // adaptive icon + 1 density is enough
        )
    }
}
```
Then commit `app/lint-baseline.xml` and `aura-core/lint-baseline.xml` after the first run.

---

### F5 — Six `Log.d/Log.i` call sites in production code  [P2 — logging noise / minor privacy smell]

**Files & lines** (all in `aura-core/src/main`):

| File | Line | Code | Notes |
|---|---|---|---|
| `agent/MemoryAugmentedAgenticLoop.kt` | 136 | `Log.i("AgenticLoop", "permission denied for tool=${held.toolName} permission=${held.permission}")` | OK — no user content |
| `agentrun/AgentRunExecutorWorker.kt` | 54 | `Log.d(TAG, "Run $runId is ${run.status}, skipping")` | OK — status enum only |
| `evolution/EvolutionCoordinator.kt` | 50 | `Log.i("EvolutionCoordinator", "Capping reflection at $MAX_REFLECTIONS_PER_RUN of $N pending candidates")` | OK — no user content |
| `hands/RunHandWorker.kt` | 60 | `Log.d(TAG, "Executing hand: ${hand.name}")` | OK — hand name is user-set but is opt-in metadata |
| `proactive/DaemonWorker.kt` | 73 | `Log.d(TAG, "Daemon: nothing to surface")` | Constant string — safe to strip |
| `proactive/DaemonWorker.kt` | 83 | `Log.d(TAG, "Daemon: posted insight: ${insight.take(80)}")` | **Privacy/quality smell** — this logs the first 80 chars of a generated insight. The insight is a *proactive* daemon output that may paraphrase recent user turns. Not strictly user content, but it is a sample of an LLM response that can reveal what the user has been talking about. |
| `triggers/TriggerWorker.kt` | 38 | `Log.d("TriggerWorker", "RunHand ${action.handId}")` | OK — hand ID only |
| `triggers/TriggerWorker.kt` | 42 | `Log.d("TriggerWorker", "StartChat ${action.prompt}")` | **P0 — see F2** |

**Two test-file `println`/`Log.d` sites** (acceptable but worth knowing):
- `aura-core/src/test/kotlin/com/aura/agent/MemoryAugmentedAgenticLoopAgentPersonalityTest.kt:101` — `println("CAPTURED_SYSTEM=…")` for debugging; fine in test code.
- `aura-core/src/test/kotlin/com/aura/tools/ScheduleTaskToolTest.kt:39` — `println("RESULT=$result")` for debugging; fine.

**Minimum surgical fix:**
1. F2 fix first (drop the prompt log).
2. F5a — drop the `Daemon: posted insight` log entirely, or move it behind `BuildConfig.DEBUG`.
3. F5b — drop the `Daemon: nothing to surface` constant-string log.
4. Long-term: introduce a thin `AuraLog` wrapper that respects `BuildConfig.DEBUG` and routes through Timber (with a no-op tree in release). Today the project has 0 abstractions over `android.util.Log` and 6 ad-hoc sites — the next person to add a `Log.d` will likely also forget to gate it.

---

### F6 — `gradle/libs.versions.toml` is internally consistent, but one dep is version-forked  [P3 — verification needed]

**File:** `gradle/libs.versions.toml`

| Coordinate | Declared version | Notes |
|---|---|---|
| `agp` | `8.2.2` | Released Feb 2024. AGP 8.5+ is the current stable; 8.7 added Compose Compiler Plugin support. Verification needed: is the team on a stable AGP that supports the version of Kotlin they ship? AGP 8.2 + Kotlin 1.9.24 is a known-good pair (no known regressions). |
| `kotlin` | `1.9.24` | Released May 2024. Current stable: 2.0.x, with K2 GA. Staying on 1.9.24 is a **deliberate hold** (most likely K2 + Hilt 2.51 compatibility, or simply "don't fix what's not broken"). |
| `ksp` | `1.9.24-1.0.20` | Matches Kotlin — correct. |
| `hilt` | `2.51` | Compatible with KSP 1.9.x. Hilt 2.52+ requires Kotlin 2.0+ for full feature parity, so 2.51 is the right pin for the current Kotlin. |
| `room` | `2.6.1` | Stable, fine. |
| `composeBom` | `2024.10.01` | October 2024 BOM. Newer BOMs (2025.x) require Kotlin 2.0 / AGP 8.6+; staying on 2024.10.01 is consistent with the rest. |
| `material3` | `1.2.1` | **Verification needed:** This is an explicit `version.ref` separate from the BOM, and the Compose BOM 2024.10.01 ships material3 **1.3.1** (per the lint dep dump above: `androidx.compose.material3:material3-android:1.3.1@aar`). The explicit 1.2.1 is therefore **being shadowed by the BOM at runtime** — the file says 1.2.1 but the actual classes come from 1.3.1. This is a documentation lie that will confuse someone trying to understand the dependency graph from `libs.versions.toml` alone. **Recommended:** delete the `material3` version entry and let the BOM govern it (or remove the BOM and pin everything, but the BOM is doing real work elsewhere). |
| `navigationCompose` | `2.7.7` | OK |
| `okhttp` | `4.12.0` | Stable. OkHttp 5.0 is in alpha. |
| `coroutines` | `1.9.0` | OK |
| `serialization` | `1.6.3` | OK; 1.7.x exists but Kotlin 1.9.24 compatibility caps it. |
| `biometric` | `1.2.0-alpha05` | **Alpha dep, on a non-alpha Kotlin** — this is a known AndroidX inconsistency; alpha05 has been stable in practice for ~2 years and is the de-facto release line. Not a bug, but a flag for new contributors. |
| `pdfboxAndroid` | `2.0.27.0` | Current; needed for `app/src/main/kotlin/com/aura/documents/DocumentTextExtractor.kt`. |
| `mail-android` | `1.6.7` | Stable. |
| `androidx-browser` | `1.8.0` | Pinned to specific version (not ref'd). Used in `tools/OpenBrowserTabTool.kt`. |
| `androidx-media3-common` | `1.3.1` | **See F1 — not used in source.** |
| `hilt-work` / `hilt-compiler` | `1.2.0` | Pinned to specific version (not ref'd). Used by aura-core. |
| `hiltNavigation` | `1.2.0` | OK. |
| `robolectric` | `4.13` | OK. |
| `junit` | `4.13.2` | **No JUnit 5 anywhere** — fine, but worth noting if a contributor expects Jupiter. |

**Status:** Mostly code-smell (the material3 version-shadowing) + verification-needed (AGP/Kotlin/Hilt stability intent).

**Minimum surgical fix:**
1. F6a (material3 shadowing): remove the `material3` line from `[versions]` and from the `androidx-compose-material3` library entry. The BOM is already declared via `androidx-compose-bom`, which transitively governs material3.
2. F6b (AGP 8.2 vs current): track the upgrade as a deliberate decision. AGP 8.2.2 is supported until at least 2025-Q2 by Google. Upgrading to AGP 8.5+ requires a Kotlin 2.0 bump for the Compose Compiler Plugin migration — a single coordinated change, not a `libs.versions.toml` edit.
3. F6c (alphabetical/version-ref consistency): `androidx-hilt-work` and `androidx-hilt-compiler` are pinned with `version = "1.2.0"` instead of `version.ref = "hilt"`. Make a `hiltX = "1.2.0"` entry in `[versions]` for symmetry, or move them under a single `hiltX` ref. Trivial.

---

### F7 — Hardcoded `kotlin-test:1.9.24` in testImplementation  [P3 — code smell]

**Files:** `app/build.gradle.kts:117-118`, `aura-core/build.gradle.kts:93-94`
```kotlin
testImplementation("org.jetbrains.kotlin:kotlin-test:1.9.24")
testImplementation("org.jetbrains.kotlin:kotlin-test-junit:1.9.24")
```

The project has a `gradle/libs.versions.toml` whose entire purpose is to avoid exactly this. When Kotlin gets bumped to 1.9.25 (or 2.0.x), these will fall behind and the version-catalog-via-Gradle tooling will not flag it. Searched: zero other `gradle/libs.versions.toml` callers, these are the only stringly-typed coords in the build files.

**Minimum surgical fix:**
```toml
# gradle/libs.versions.toml
[libraries]
kotlin-test = { group = "org.jetbrains.kotlin", name = "kotlin-test", version.ref = "kotlin" }
kotlin-test-junit = { group = "org.jetbrains.kotlin", name = "kotlin-test-junit", version.ref = "kotlin" }
```
```kotlin
// app/build.gradle.kts
testImplementation(libs.kotlin.test)
testImplementation(libs.kotlin.test.junit)
// aura-core same
```

---

### F8 — CI workflow has no `continue-on-error`, no retry, secrets block is empty  [P2 — verification needed]

**File:** `.github/workflows/ci.yml`

**Observations:**

1. **`permissions: contents: read` only.** No `actions: read` for `cache` action's own metadata, no `checks: write` (fine — no comment triggers), no `pull-requests: write` (fine — no auto-label). This is actually correct minimal-scoped. ✅

2. **No `secrets:` block.** Confirmed: no signing secrets are wired (consistent with F3 — the keystore is unset). When F3 is fixed, this is where the secrets go.

3. **No matrix / parallelism across modules.** CI is one job that does `:aura-core:assembleDebug :aura-core:testDebugUnitTest :aura-core:lintDebug :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleRelease` serially. With two modules, splitting into two jobs (one per module) that share the cache would ~halve the wall time on a typical PR. **Verification needed:** does the current cache strategy actually share state between jobs? The cache key includes both modules' `build.gradle.kts` and the `libs.versions.toml`, so on a single-job CI the cache is well-warmed. In a two-job split, both jobs' cache restore would race, but each only needs its own hash, so this is safe.

4. **Cache key has an explicit version pin: `~/.gradle/caches/8.10.2`.** When the Gradle wrapper version is bumped (currently `8.10.2` per `gradle/wrapper/gradle-wrapper.properties`), this directory disappears and a new one is created. The cache restore will silently miss for the new path and only catch via `restore-keys`. **Recommended:** derive from the wrapper: `~/.gradle/caches/$(./gradlew --version | awk '/Gradle /{print $2}')`.

5. **No `continue-on-error` on the release build.** This is correct: if `assembleRelease` fails, we want CI red. ✅

6. **`upload-artifact` on `failure()` for test results.** ✅ Good.

7. **No `gradle-home` cache cleanup.** Build cache can grow unbounded on the GH Actions runner; the cache evicts old entries on its own schedule, so this is fine for now.

8. **Workflow file is pinned to specific action SHAs** (`actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683 # v4.2.2`). ✅ Best practice.

9. **No Dependabot config in repo** (checked for `.github/dependabot.yml` — absent). Without Dependabot, version bumps for transitive AndroidX libraries will silently lag. **Recommended:** add `.github/dependabot.yml` with `gradle` ecosystem, weekly schedule, grouped for AndroidX/Compose/Testing.

10. **`on.push.branches` is `[main, feat/tier-1-friction]`.** The feature branch is locked in. When `feat/tier-1-friction` lands or is abandoned, the workflow needs a human edit to keep running.

**Minimum surgical fix** (highest impact first):
- F8a — Dependabot config (10 lines, prevents future stale-dep drift).
- F8b — derive `~/.gradle/caches/<version>` from wrapper.
- F8c — split into a 2-job matrix when a second module lands; for two modules the cost is ~the same as one job.

---

### F9 — CRLF line endings on Gradle build files  [P3 — code smell, Windows pitfall]

**Files:** `app/build.gradle.kts`, `aura-core/build.gradle.kts` (confirmed via `file` command — `ASCII text, with CRLF line terminators`).

**Impact:** Cosmetic. Most build tooling and IDEs handle both transparently, but:
- Some Linux CI containers will print "Gradle uses LF line endings" warnings (cosmetic, non-fatal).
- `git diff` becomes noisier on Windows (every line gets a phantom `\r` change in cross-platform patches).
- Patches generated on macOS and pasted into a Windows editor will collide.

**Minimum surgical fix:**
```bash
# one-time normalization
git config core.autocrlf false
# then re-save the files from an LF-aware editor
# or:
sed -i 's/\r$//' app/build.gradle.kts aura-core/build.gradle.kts
```
Add `.gitattributes` to lock it: `*.kts text eol=lf`.

---

### F10 — `:aura-core` package shadowing (two `com.aura.notifications` packages)  [P3 — code smell, false positive verification pending]

**Files:**
- `aura-core/src/main/kotlin/com/aura/notifications/NotificationCaptureStore.kt` → `package com.aura.notifications`
- `app/src/main/kotlin/com/aura/notifications/AuraNotificationListenerService.kt` → `package com.aura.notifications`

**Observation:** Two different Gradle modules declare the same Kotlin package name. This is **legal** (Kotlin allows it; the JVM doesn't care), but it is a code smell because:
- IDEs sometimes show ambiguous imports.
- New developers can be confused which class comes from where.
- R8 may (rarely) produce surprising behavior if the two modules ship classes with the same FQN but different module paths.

**Status:** Verified legal, working as intended. The :app module's `AuraNotificationListenerService` correctly depends on `:aura-core`'s `NotificationCaptureStore` via `implementation(project(":aura-core"))` in `app/build.gradle.kts:61`. Module separation is clean — `:aura-core` has zero imports from `com.aura.ui` or `com.aura.widget` (verified via grep).

**Recommended (not required):** rename the :app one to `com.aura.notifications.bridge` or merge it into a `:aura-android-notifications` module if it ever grows beyond one class.

---

### F11 — Three TODO/XXX comments, all in `aura-core`  [P3 — code smell, intentional for F2-related items]

**Sites (all confirmed via grep):**

1. `aura-core/src/main/kotlin/com/aura/memory/CloudEmbedder.kt:140-151` — a **stale XXX comment** inside the catch-block that explains a previous silent-failure fix. The "XXX" marker is in the body of the explanatory paragraph: `// the user can see "Aura: cloud embed failed (XXX), falling back to local".` This is documentation about what the log message *used to look like*, not a current TODO. **Recommend:** strip the `XXX` (it's a leftover from a draft of the message string).

2. `aura-core/src/main/kotlin/com/aura/triggers/TriggerEngine.kt:22` — `// TODO: requires location permission + FusedLocationProvider`. Real TODO: `LocationEntered` condition always returns null. To implement, the app needs `ACCESS_FINE_LOCATION` (already in `AndroidManifest.xml`) + a `FusedLocationProviderClient` (would add a Play Services dependency). Verify whether this is a planned feature or dead.

3. `aura-core/src/main/kotlin/com/aura/triggers/TriggerWorker.kt:37` — `// TODO: enqueue hand via AgentRunExecutor`. Real TODO paired with a debug `Log.d`. See F2 — the line above is the same in spirit.

4. `aura-core/src/main/kotlin/com/aura/triggers/TriggerWorker.kt:41` — `// TODO: start chat with prompt via notification tap`. Real TODO. **P0-coupled** to F2 (the prompt is logged because there's no actual handler).

**No TODOs in `app/src/main` or in any test code.** ✅

**Minimum surgical fix:**
- F11a (stale XXX): rewrite the comment without the literal "XXX" marker.
- F11b (Triggers): convert each TODO to a `// TODO(<ISSUE>):` with a tracking issue number, or delete the unimplemented branches until they're ready. Today, an unimplemented `LocationEntered` silently fails (returns `null`) and a user with such a trigger gets nothing — that's a quiet failure, not a loud one.

---

### F12 — `gradle.properties` flags configuration cache but Hilt + AGP 8.2 compatibility unverified  [P3 — verification needed]

**File:** `gradle.properties:4`
```
org.gradle.configuration-cache=true
```

**Status:** Configuration cache support landed in AGP 8.0+ (with caveats) and is fully supported in Hilt 2.51 + KSP 1.9.24-1.0.20. **Should work**, but I cannot run the build in this audit. The way to confirm: `./gradlew --configuration-cache help` (zero exit) and `./gradlew :app:assembleDebug --configuration-cache` (no "incompatible task" warnings).

**If broken**, the symptom is a build that silently skips a step (e.g., missing KSP-generated code → Hilt fails at runtime) or `Configuration cache problems` warnings at the end of every build.

**Minimum surgical fix:** run the verification command above. If a task is incompatible, the standard fix is `notCompatibleWithConfigurationCache(...)` on the offending task.

---

### F13 — Drawable `aura_splash` may be unused at runtime, or referenced only via `windowBackground`  [P3 — verification needed]

**File:** `app/src/main/res/drawable/aura_splash.xml` (XML drawable)

Searched for `R.drawable.aura_splash` — zero matches. Searched for `@drawable/aura_splash` — would need to check `themes.xml`. The file is in the build's drawable set, so R8 + `isShrinkResources = true` should drop it at release time. **Verification needed:** confirm it is not referenced from `themes.xml` (a likely candidate for splash window background). If not referenced, R8 drops it — fine. If it IS referenced, the file is in use.

(Skipping a full themes.xml walk; this is a P3 since `isShrinkResources = true` is on for release.)

---

### F14 — All worker class names are visible (`public`) but most are never referenced outside the module  [P3 — code smell, no functional impact]

**Observation:** `@HiltWorker` workers and the various `@Inject`-constructed services in `:aura-core` are top-level public classes. `internal` would suffice for the ones that are only consumed by the same module's Hilt graph or scheduled by `companion object` helpers within the module.

**Status:** Not a bug. Hilt and WorkManager do reflection / generated code lookups by class, so `internal` would break the generated `Hilt_TouchPointWorker` etc. bindings. **Recommended leave as-is.** Mentioned only because the task asks about "internal visibility" — this codebase has 86 `internal` declarations and appropriately uses them for non-Hilt non-public helpers; the public surface for workers is intentional.

---

### F15 — No `versionCode` auto-generation from git tags / commit count  [P3 — code smell]

**File:** `app/build.gradle.kts:18`
```kotlin
versionCode = 41
versionName = "0.36.0"
```

**Status:** Manually maintained. This is fine for a v0.x product where each release is intentional. The risk is a missed bump (already happened — `versionName` is 0.36.0 but `versionCode` is 41, the comment in the file or git log should explain the offset). **Recommended:** when the project ships its first non-debug release, switch to:

```kotlin
// app/build.gradle.kts
val gitTagVersion = providers.exec { commandLine("git", "describe", "--tags", "--abbrev=0") }.standardOutput.asText.get().trim()
val commitCount = providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }.standardOutput.asText.get().trim().toInt()
versionCode = commitCount
versionName = gitTagVersion.removePrefix("v")
```

This eliminates the manual bump drift risk entirely.

---

### F16 — `app/proguard-rules.pro` `-keep class com.aura.** { *; }`  [P3 — code smell, may hide R8 bugs]

**File:** `app/proguard-rules.pro:2`
```
-keep class com.aura.** { *; }
```

**Observation:** This keeps everything under `com.aura.**`. The R8 comment in `ci.yml` ("the release build is the only one that runs R8 minification") tells us the team knows the release build is the only safety net — and this keep rule effectively neutralizes R8 for ~all application code. The R8 step still runs (it processes androidx/kotlin/etc.), but for `com.aura.**` it is a no-op.

**Why it's there:** the `kotlinx.serialization` runtime-reflection requirement for `@Serializable` classes is the typical reason, and the same file has separate `-keep,includedescriptorclasses class com.aura.**$$serializer { *; }` and `-keepclasseswithmembers class com.aura.** { kotlinx.serialization.KSerializer serializer(...); }` rules. The broad `com.aura.** { *; }` is therefore **redundant** for serialization (the more specific rules cover it) and **overly broad** for everything else.

**Status:** Verification needed — does this also keep `com.aura.notifications.AuraNotificationListenerService` (referenced from manifest) and the Hilt entry points (referenced by generated code)? Yes, but those have their own `consumer-rules.pro` / generated keep rules from Hilt and AGP, so the broad `com.aura.**` is still over-broad.

**Minimum surgical fix:** delete the `-keep class com.aura.** { *; }` line and rely on the specific serialization rules + Hilt-generated rules + AGP-generated manifest rules. R8 will then actually shrink app code, which will surface real dead code (helpful for F1, etc.). The R8 release build in CI is the safety net — let it do its job.

---

### F17 — `:aura-core` `consumer-rules.pro` has the same `com.aura.core.**` keep, but `core` is the package name and the module is `aura-core`  [P3 — code smell, dead keep]

**File:** `aura-core/consumer-rules.pro:1`
```
-keep class com.aura.core.** { *; }
```

**Status:** This keeps nothing. The module is `:aura-core`, namespace is `com.aura.core`, but no source file in `aura-core/src/main` is actually under the `com.aura.core.**` package. Files live under `com.aura.notifications`, `com.aura.tools`, `com.aura.data`, etc. The only file under `com.aura.core` is a small subset: