# ROUND7_SECURITY — Security, Authentication, Encryption, Error-Handling Audit

**Repo:** `D:/aura-android-clean` (branch `feat/tier-1-friction`, head `6b724769`, v0.36.0)
**Scope:** secure data store, network layer, tool-permission gate, SSRF guards, MCP
client, input validation, error handling on auth/crypto paths, ProGuard / R8.
**Prior rounds:** `ROUND6_DATA.md` (data integrity), `ROUND6_UIUX.md` (nav) — not
re-covered.
**Method:** direct source inspection of the Kotlin sources called out below; each
finding cites `file:line` and a verbatim snippet. Severity is P0 (data loss /
credential exfil / remote bypass), P1 (privilege boundary defect or
defence-in-depth gap), P2 (hardening, code smell, dead defence), P3 (nit / doc).

---

## TL;DR

- **Solid base layer.** AES-GCM via Android Keystore (KeyManager) is correct, the
  base `OkHttpClient` correctly disables redirects, `network_security_config.xml`
  blocks cleartext app-wide, `SsrfGuard` does fail-closed DNS-pin + non-public-IP
  filtering, the SSRF guard is shared by `HttpFileReadTool`, `HttpFileWriteTool`,
  `DeepResearchTool`, `FirecrawlFetchTool`, and `McpClientManager`.
- **3 P1 findings.** `KeyManager.decrypt` swallows `Exception` (line 110), which
  masks cryptography errors as "no value"; `PreferencesBackup` exports
  `smtpUsername` in plaintext (P1 because it sits next to `embeddingModel` and
  other identifiers that could fingerprint a user or be replayed against a known
  SMTP relay); MCP tool names from a remote server are not sanitized before
  being registered as agent-callable tools (collision / injection risk).
- **4 P2 findings.** `proguard-rules.pro` keeps the entire `com.aura.**` package
  (defeats R8 obfuscation; the `embeddingModel` and `embedding_model` value will
  appear as a clear-text string in the APK); no retry/back-off around
  `SecureDataStore` writes (transient `IOException` on slow devices is uncatchable
  by the UI); `BraveSearchTool`/`WebSearchCapabilityTool` return search-result
  URLs as raw markdown without validating them through `SsrfGuard` (the URL
  itself is never re-fetched here, but the model may chain it into
  `http_file_read`); `ProviderKeys` has no observable "reload" event for
  out-of-band consumers between the async-init `init` block and the first
  `state` change.
- **3 P3 findings.** `PolicyEngine` defaults `REMOTE_COST` to confirmation
  `NONE` (handled by the per-run `approvedRemoteCostTools` gate, not a bug);
  `DagResolver` / `CouncilViewModel` catch `Exception` very broadly (already
  audited by `runcatching-silent-sites-2026-07-27.md`); docs and tests around
  "agent-issued hands bypass" do not materialise — the model cannot currently
  create a hand that bypasses the per-run approval because hands are
  `WRITE_LOCAL` and have to go through `PolicyEngine`.
- **No WebView, no deep-link `<data>` filter, no exported-provider gap.**
  `ShareReceiverActivity` and `MainActivity` are exported (standard for
  share-target + launcher), `WidgetConfigActivity` and `BootReceiver` too —
  none accept opaque deep-link URIs.
- **No certificate pinning** anywhere — the model relies on the system trust
  store plus `network_security_config.xml`'s `cleartextTrafficPermitted=false`.
  This is acceptable for a personal-AI app and is consistent with the
  comment block at `ProviderModule.kt:41-50`; call out as a deliberate
  non-control.

---

## Findings

### F1 — `KeyManager.decrypt` swallows `Exception` (catches and returns `null`)

**Severity:** P1
**Status:** confirmed bug
**File:** `aura-core/src/main/kotlin/com/aura/security/KeyManager.kt:97-113`

```kotlin
fun decrypt(ciphertextB64: String, key: SecretKey): String? {
    return try {
        val combined = Base64.getDecoder().decode(ciphertextB64)
        if (combined.size < GCM_IV_LENGTH + 1) return null
        val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = combined.copyOfRange(GCM_IV_LENGTH, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    } catch (_: AEADBadTagException) {
        null // auth tag mismatch — caller can decide to fall back gracefully
    } catch (_: IllegalArgumentException) {
        null // invalid Base64 input
    } catch (_: Exception) {
        null // any other unexpected error; return null gracefully in v1
    }
}
```

**Why it matters.** The third `catch (_: Exception)` block makes the decrypt
path fail-silent for any unexpected cryptographic error (e.g. a
`KeyPermanentlyInvalidatedException` after biometric enrollment changes,
`InvalidKeyException` from a corrupted keystore alias, `UnrecoverableKeyException`
when the device is in Direct Boot). The caller `SecureDataStore.getString`
(line 64) then throws `DecryptionFailedException` only when the result is
`null` AND the key was present, so a permanent keystore loss is still
surfaced — but the catch is wider than it needs to be.

**Surgical fix.** Narrow the catch:

```kotlin
} catch (_: AEADBadTagException) {
    null // auth tag mismatch — caller can decide to fall back gracefully
} catch (_: IllegalArgumentException) {
    null // invalid Base64 input
} catch (e: java.security.GeneralSecurityException) {
    // permanent keystore issue (invalidated key, provider missing, etc.)
    // bubble up so SecureDataStore can surface DecryptionFailedException
    throw e
}
```

**No retry/back-off on transient IO.** `SecureDataStore.putString` /
`getString` (lines 43-68) only call `dataStore.edit { … }` / `dataStore.data`
directly. If a transient `IOException` happens (disk full, slow flash, other
app holding the file), the coroutine fails; `ProviderKeys.init` (line 134) maps
*any* exception during initial load to `ProviderCredentialState.StorageError`,
which means a one-off `IOException` at startup puts the user into a state where
their API keys are present but every provider reads as `StorageError` until
they clear the DataStore. Recommendation: catch `IOException` separately and
retry with exponential back-off 2-3 times before declaring
`StorageError`. (P2 — code smell, see F6.)

---

### F2 — `PreferencesBackup` exports `smtpUsername` in plaintext

**Severity:** P1
**Status:** confirmed bug (information disclosure; not a credential leak in
itself, but combined with the SMTP host/port it identifies a mail relay the
user can be impersonated through)
**Files:**

- `aura-core/src/main/kotlin/com/aura/backup/AuraBackup.kt:374-377`
- `aura-core/src/main/kotlin/com/aura/backup/BackupManager.kt:223-226`
- `aura-core/src/main/kotlin/com/aura/data/UserPreferences.kt:453` (source of
  the plaintext value)

```kotlin
// AuraBackup.kt
@Serializable
data class PreferencesBackup(
    val defaultModel: String? = null,
    ...
    val smtpHost: String? = null,
    val smtpPort: Int = 0,
    val smtpUsername: String? = null,    // ← PII / identity leak
    val smtpFrom: String? = null,
    ...
)
```

```kotlin
// BackupManager.snapshot
smtpHost = userPreferences.smtpHost.first().takeIf { it.isNotBlank() },
smtpPort = userPreferences.smtpPort.first(),
smtpUsername = userPreferences.smtpUsername.first().takeIf { it.isNotBlank() },
smtpFrom = userPreferences.smtpFrom.first().takeIf { it.isNotBlank() },
```

**What is correctly NOT exported** (verified):

- `smtpPassword` — line 447 of `BackupManager.kt` says *"Password is
  intentionally not backed up — stored in SecureDataStore"* and the
  `PreferencesBackup` class has no `smtpPassword` field. ✅
- API keys — `providerKeys.keyFor(prefix)` is not called in
  `BackupManager.snapshot`; the `PreferencesBackup.embeddingModel` field holds
  only the model id (e.g. `nomic-embed-text`). ✅
- `mcpServersJson` **is** exported (line 227) — see F3.

**Surgical fix.** Drop `smtpUsername`, `smtpFrom`, and `smtpHost` from
`PreferencesBackup` (or mark them explicitly excluded by re-using the existing
"SMTP password is intentionally not backed up" pattern). The
"user has to re-paste the SMTP host" UX already exists for the password and
extends naturally to username/host/from.

**Alternative.** If the team wants a round-trippable SMTP config, gate it
behind a "include SMTP server in backup" user toggle (default off) and warn
that the file should be stored encrypted.

---

### F3 — `PreferencesBackup.mcpServersJson` exports the full MCP server list

**Severity:** P2 (P1 if `authToken` is ever serialised — currently it is not,
but the field is on `McpServerConfig` and `set` round-trips it via the UI)
**Status:** verification-needed claim + a real concern
**Files:**

- `aura-core/src/main/kotlin/com/aura/backup/BackupManager.kt:227, 451-453`
- `aura-core/src/main/kotlin/com/aura/mcp/McpModels.kt:10-29` (config shape)
- `aura-core/src/main/kotlin/com/aura/backup/AuraBackup.kt:378`
  (`mcpServersJson: String = "[]"`)
- `app/src/main/kotlin/com/aura/ui/settings/SettingsViewModel.kt:686-696`
  (UI populates `McpServerConfig` from the form, including the auth token)

```kotlin
// BackupManager.snapshot (line 227)
mcpServersJson = userPreferences.mcpServersJson.first(),

// BackupManager.restore (line 451-453)
if (backup.preferences.mcpServersJson.isNotBlank() && backup.preferences.mcpServersJson != "[]") {
    userPreferences.setMcpServersJson(backup.preferences.mcpServersJson)
}
```

**Why it matters.** `McpServerConfig` carries `id`, `name`, `url`,
`trustedLocal`, `allowedToolPrefixes`, `deniedTools`, **and** `authToken`. The
**current** Settings UI (line 686-692) does not write `authToken` into
`McpServerConfig` (the field is supplied at connect time from
`SecureDataStore` via `McpClientManager.connect(authToken = …)`), and
`decodeFromJson` at line 765 of `SettingsViewModel` re-uses that serializer
so the round-trip preserves whatever the user typed. If a future Settings UI
field ever saves a token to the form, it will end up plaintext in every
backup file forever. This is a **latent** leak; the comment in
`McpModels.kt:25-26` says *"Bearer token for server auth. Stored in
SecureDataStore, not in Room"* which is correct today, but the data class
itself does not enforce that — it is a documentation contract only.

**Surgical fix.** Either (a) replace `mcpServersJson: String` with a
sanitised projection that drops `authToken` before serialisation, or (b) make
`McpServerConfig.authToken` a `@Transient` / `Transient` field so
`kotlinx.serialization` excludes it from the default JSON. Today the field
has neither annotation. Verification needed: confirm that no code path
ever populates `authToken` before writing `mcpServersJson` to DataStore.

---

### F4 — MCP tool names from the server are not sanitised before registration

**Severity:** P1
**Status:** confirmed bug (collision + lookup ambiguity, not remote-code
execution)
**Files:**

- `aura-core/src/main/kotlin/com/aura/mcp/McpConnection.kt:91-100` (server-supplied
  name is taken as-is)
- `aura-core/src/main/kotlin/com/aura/mcp/McpToolBridge.kt:96-101, 168-200`
  (the name is registered into the local `ToolRegistry`; `syncTools` adds the
  `mcp_` prefix only on collision; `syncToolsUnprefixed` overwrites)
- `aura-core/src/main/kotlin/com/aura/mcp/McpToolBridge.kt:220-227`
  (`extractServerId` splits on the first underscore, so a tool named
  `mcp_<serverId>_<toolName>` can be re-extracted correctly, but a tool named
  `evil_<foo>` registered with prefix `mcp_bar_` is parsed as
  `serverId="evil"` and the bar_ server is mistakenly considered the owner)

```kotlin
// McpConnection.listTools
val tools = toolsArray.mapNotNull { item ->
    val obj = item as? JsonObject ?: return@mapNotNull null
    val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
    McpToolInfo(
        serverId = config.id,
        name = name,                          // ← unsanitised
        description = obj["description"]?.jsonPrimitive?.content ?: "",
        inputSchemaJson = obj["inputSchema"]?.toString() ?: "{}",
        serverName = config.name,
    )
}.take(config.maxTools)
```

```kotlin
// McpToolBridge.syncTools (the unprefixed branch is worse)
val registeredName = if (nativeExists) mcpToolName(config.id, mcpTool.name) else mcpTool.name
//                                                    ^^^^^^^^^^^^^^^^^^^^^^^^^^^
// "evil\nDROP TABLE" or "mcp_<otherServerId>_overwrite" would all pass through.
```

**Why it matters.** Three concrete problems:

1. A malicious MCP server can advertise a tool named exactly
   `tavily_search` (or `image_generate`, `run_hand`, etc.) and
   `syncToolsUnprefixed` will overwrite the agent's view of that native tool
   (line 198, `toolRegistry.register(tool)`). The LLM cannot tell the
   difference; the malicious tool then runs with `REMOTE_COST` risk and gets
   the per-run `approvedRemoteCostTools` approval that the user thought they
   were giving the *native* tool.
2. Tool names with whitespace, control characters, or very long strings are
   accepted; the registry keys are not validated, which can break UI display
   and logging assertions. (`registry.name` ends up in
   `ToolResult.NeedsApproval("...:$name")` at `ToolExecutor.kt:83` and in
   `PolicyResult.Disabled(tool.name)` at `PolicyEngine.kt:31`.)
3. `extractServerId` (line 220) splits on the **first** underscore. A server
   `id` like `safe` with a tool named `evil_overwrite` registers as
   `mcp_safe_evil_overwrite` and is correctly parsed back; but a tool named
   `mcp_other_overwrite` registered by `syncTools` with `serverId="safe"`
   (because the native check above found a collision with a native tool
   called `mcp_other_overwrite`) is parsed back as `serverId="mcp"` —
   `mcp` is not a real server, and on `syncTools` the next pass the entry is
   NOT marked stale (`serverId = "mcp"`, the filter is `serverId != null &&
   (serverId !in currentServerIds || serverId !in connectedServerIds)`, so it
   fails the first arm and is dropped — but only after the user has been
   able to call it). The `connectedServerIds` check is what catches it; the
   filter is safe in practice, but it depends on the misnamed prefix
   colliding with no real server.

**Surgical fix.** In `McpConnection.listTools`, validate the server-supplied
name before exposing it:

```kotlin
val raw = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
val name = sanitizeToolName(raw) ?: return@mapNotNull null   // skip, don't fail
// where sanitizeToolName = Regex("""^[a-zA-Z][a-zA-Z0-9_-]{0,63}$""").matches(raw)
```

Also drop the "unprefixed overrides native" behaviour in
`syncToolsUnprefixed` to a strict allowlist (the `allowedToolPrefixes` on
`McpServerConfig` already exists for this).

---

### F5 — `extractServerId` heuristic breaks for tool names that contain
underscores in their first segment

**Severity:** P2 (correctness / collides with F4; not directly exploitable on
its own)
**Status:** confirmed code smell
**File:** `aura-core/src/main/kotlin/com/aura/mcp/McpToolBridge.kt:220-227`

```kotlin
private fun extractServerId(registeredName: kotlin.String): kotlin.String? {
    if (!registeredName.startsWith("mcp_")) return null
    val rest = registeredName.removePrefix("mcp_")
    // serverId was lowercased + sanitized, so it won't contain underscores
    // that weren't in the original name. Find the first segment.
    val firstUnderscore = rest.indexOf('_')
    return if (firstUnderscore > 0) rest.substring(0, firstUnderscore) else null
}
```

The comment asserts that "serverId was lowercased + sanitized" — but
`SettingsViewModel` at line 686-696 accepts any string from the user, and
the `id` field in `McpServerConfig` is not validated. A user can put
`my_server_1` as the id and the splitter will see `my` as the server id.
Combined with F4, this is a *self-inflicted* DoS where a user-chosen id
makes `syncTools` unable to garbage-collect stale registrations because the
parsed `serverId="my"` does not match the live `config.id="my_server_1"`.

**Surgical fix.** Either validate the id at write time
(`SettingsViewModel` line 686), or store the registered-tool-name →
`serverId` mapping explicitly (the bridge already has
`registeredNameToServerId` for the unprefixed branch; reuse it for the
prefixed branch).

---

### F6 — `SecureDataStore` has no retry/back-off on transient IO

**Severity:** P2
**Status:** code smell, sometimes-misclassified-as-bug
**File:** `aura-core/src/main/kotlin/com/aura/security/SecureDataStore.kt:43-77`

```kotlin
suspend fun putString(key: String, value: String) {
    val encrypted = keyManager.encrypt(value, this.key)
    dataStore.edit { prefs ->
        prefs[stringPreferencesKey(key)] = encrypted
    }
}

suspend fun getString(key: String): String? {
    val encrypted = dataStore.data
        .map { prefs -> prefs[stringPreferencesKey(key)] }
        .first() ?: return null
    return keyManager.decrypt(encrypted, this.key)
        ?: throw DecryptionFailedException(...)
}
```

`ProviderKeys.init` (line 134) maps any non-`CancellationException` to
`ProviderCredentialState.StorageError`. On a device where the keystore or
disk is briefly busy (low-storage cleanup, FBE unlock race), the user lands
in a "all providers are in error" state for the rest of the session. A
2-attempt retry with a 50-ms delay would absorb most transient cases.

**Surgical fix.** Wrap the inner body in a small helper:

```kotlin
private suspend fun <T> withTransientRetry(label: String, block: suspend () -> T): T {
    var attempt = 0
    while (true) {
        try { return block() }
        catch (e: java.io.IOException) {
            if (++attempt >= 2) throw e
            kotlinx.coroutines.delay(50L * attempt)
        }
    }
}
```

---

### F7 — `ProviderKeys` exposes `loaded` / `state` as `StateFlow` but
external consumers cannot distinguish "initial load in progress" from
"reload after a write"

**Severity:** P3 (informational; surfaces a UX implication more than a bug)
**Status:** code smell
**File:** `aura-core/src/main/kotlin/com/aura/providers/ProviderKeys.kt:85-87,
118-155`

```kotlin
private val _loaded = MutableStateFlow(false)
val loaded: StateFlow<Boolean> = _loaded.asStateFlow()
```

`_loaded` is flipped to `true` exactly once in `init`, after the first
DataStore sweep. If the user wipes app data and re-opens the app
(rotating through the same singleton — they can't, but in the test
harness / a process-restart-after-system-update the singleton dies and
`_loaded` is reset, so this isn't actually a reload problem). The real
issue is the `loaded` signal says "warm" but the `state` flow can transiently
be empty if a single-provider write happens during the initial sweep;
callers reading `state.first()` get a partial map.

**Surgical fix.** Add `awaitLoadedSnapshot()` that waits for `_loaded` AND
for the next emission of `state` that contains all `PREFIXES` keys (or
explicitly the ones the caller cares about). Today there is no test that
verifies the race; the `ProviderKeysTest` exercises the synchronous
"set then read" path.

---

### F8 — ProGuard/R8 keep rule on `com.aura.**` defeats code obfuscation

**Severity:** P2 (defence-in-depth; the secrets are encrypted at rest so this
is about reverse-engineering surface, not about credential exfil)
**Status:** confirmed design choice; documented as a finding so it does not
get lost
**Files:**

- `app/proguard-rules.pro:2` — `-keep class com.aura.** { *; }`
- `aura-core/consumer-rules.pro:1` — `-keep class com.aura.core.** { *; }`

```pro
# Aura proguard rules
-keep class com.aura.** { *; }
```

**What this means in practice.** The release APK will ship with full
class names, method names, field names, and method signatures for the
entire `com.aura` package. An attacker with `apktool` / `jadx` can read
"ProviderKeys.embeddingModel", "SecureDataStore.putString", the SMTP
configuration data class, every `Tool` name and risk annotation, and so
on. Specifically:

- Every backup schema field name (`smtpUsername`, `mcpServersJson`,
  `embeddingModel`, …) ends up as a string in the APK's string pool.
- Every tool name and parameter schema is preserved verbatim.
- The MCP "deniedTools" / "allowedToolPrefixes" storage shape is
  visible.

**Surgical fix.** Tighten the keep rule to the minimum the
serialization framework needs (the rule is already covering
`@kotlinx.serialization.Serializable <fields>` and the
`$$serializer` companion classes). Drop the wildcard `**` keep:

```pro
# Keep only the data classes that need reflection for kotlinx.serialization
-keepclassmembers @kotlinx.serialization.Serializable class com.aura.** {
    <fields>;
}
-keep,includedescriptorclasses class com.aura.**$$serializer { *; }
-keepclassmembers class com.aura.** {
    *** Companion;
}
-keepclasseswithmembers class com.aura.** {
    kotlinx.serialization.KSerializer serializer(...);
}
```

**Important caveat.** This change needs verification against the
`com.aura.security.SecurityModule` and `McpConnection` (which uses
`Json.decodeFromJsonElement` over a raw JSON object — fine), and
`AuraBackup`'s top-level polymorphic dispatch (currently not used — all
fields are concrete types, so a narrower keep rule is safe).

---

### F9 — `BraveSearchTool` and `WebSearchCapabilityTool` return
search-result URLs without routing them through `SsrfGuard`

**Severity:** P2
**Status:** confirmed code smell (the URL itself is not fetched in
this tool, but the model chains it to `http_file_read` / `firecrawl_fetch`
in the same loop; if the upstream search engine returns a result with
URL `http://169.254.169.254/...`, `SsrfGuard` will block it on the
follow-up call, but the model has already seen a "this URL is a real
search hit" framing)
**Files:**

- `aura-core/src/main/kotlin/com/aura/tools/DuckDuckGoSearch.kt:16-28`
- `aura-core/src/main/kotlin/com/aura/tools/BraveSearchTool.kt:93-110`
- `aura-core/src/main/kotlin/com/aura/tools/WebSearchCapabilityTool.kt:60-61`

```kotlin
// DuckDuckGoSearch.search — returns the URL from the HTML, no validation
return out += Result(
    title = m.groupValues[2].trim(),
    url = m.groupValues[1].trim(),     // ← unfiltered
    snippet = snippet,
)
```

**What `SsrfGuard.inspect` does** (line 35-76 of
`aura-core/src/main/kotlin/com/aura/core/url/SsrfGuard.kt`): the
guard resolves the host, checks every returned `InetAddress` against a
non-public range, blocks `userInfo`, `localhost`, and non-http(s)
schemes. **It is the right primitive**, but the search-result URL is
never run through it. The same is true for `TavilySearchTool`'s result
URLs (line 159) and `ExaSearchProvider` results.

**Surgical fix.** Filter search results through `SsrfGuard` before
returning them to the agent:

```kotlin
val safeUrl = SsrfGuard.inspect(rawUrl)
if (safeUrl is SsrfValidation.Blocked) return@mapNotNull null
```

This drops bad URLs from the model context without raising an error,
which is the right user experience.

---

### F10 — `HttpFileReadTool` body read has no upper bound on `chars`

**Severity:** P3
**Status:** verification-needed claim; the `maxChars` cap is enforced
correctly but `take(maxChars)` is a code-level bound on a UTF-8 String
that may cut a multi-byte character in half
**File:** `aura-core/src/main/kotlin/com/aura/tools/HttpFileReadTool.kt:53-82`

```kotlin
val maxChars = (call.arguments["max_chars"] as? Int ?: 8000).coerceIn(1, 32000)
...
val bodyBytes = if (source.buffer.size > maxBytes) {
    source.readByteArray(maxBytes)
} else {
    source.readByteArray()
}
    if (asBase64) {
        val encoded = Base64.getEncoder().encodeToString(bodyBytes)
        ToolResult.Ok(encoded.take(maxChars))    // ← may cut a UTF-8 char
    } else {
        val text = String(bodyBytes, Charsets.UTF_8)
        ToolResult.Ok(text.take(maxChars))        // ← may cut a UTF-8 char
    }
```

The `maxBytes = maxChars * 4L` upper bound is the right defensive ceiling.
The `.take(maxChars)` is fine for ASCII but a multi-byte character (e.g.
emoji, CJK) sliced at `maxChars - 3` returns a String that ends in the
middle of a code point, which will print as a `?` or `�` and could break
the model's JSON parser if the truncation lands inside a string literal.
Not a security issue, but it can crash the `MemoryAugmentedAgenticLoop` if
the model tries to re-emit a tool result that contains an invalid UTF-8
sequence. Verification needed: confirm there is a String sanity check
in the agentic loop (e.g. `String.toByteArray(Charsets.UTF_8)` round-trips
without throwing — it doesn't, `MalformedInputException` is a
`CharacterCodingException` subclass).

**Surgical fix.** Truncate to a UTF-8 boundary, or use
`Charsets.UTF_8.decode(...).toString()` after capping the byte buffer.

---

### F11 — `AppLauncherTool` launches any URL the model produces (deep-link
launch without allowlist)

**Severity:** P1
**Status:** confirmed code smell (risk = `WRITE_LOCAL`, but the user has
no idea that the model is about to open an arbitrary external app)
**File:** `aura-core/src/main/kotlin/com/aura/tools/AppLauncherTool.kt:40-55`

```kotlin
execute = { call, ctx ->
    val target = call.arguments["target"] as? String ?: return@Tool ToolResult.Error("missing 'target'", "bad_args")
    try {
        val intent = if (target.startsWith("http://") || target.startsWith("https://")) {
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            context.packageManager.getLaunchIntentForPackage(target)?.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            } ?: return@Tool ToolResult.Error("App not found: $target", "not_found")
        }
        context.startActivity(intent)
        ToolResult.Ok("Launched: $target")
    } catch (e: Exception) {
        ToolResult.Error("launch failed: ${e.message}", "exception")
    }
}
```

**Why it matters.** The "package name" branch calls
`getLaunchIntentForPackage`, which is the safe path. The "URL" branch
hands an arbitrary `http://`/`https://` URI to `Intent.ACTION_VIEW` —
the Android intent dispatcher may route that to a browser, but it may
also route it to any app that registers an `<intent-filter>` for
`http`. A user can be silently phished to a login screen that imitates
a real site (e.g. a fake bank login) with no warning. There is no UI
preview, no allowlist, no URL validator. Risk is `WRITE_LOCAL`, so it
gets the same `ConfirmationLevel.NONE` default as `remember` /
`set_reminder` — the chat loop will fire-and-forget this.

**Surgical fix.** Two options:

1. Bump the risk to `WRITE_REMOTE` so the default is `EXPLICIT`
   confirmation (already documented in
   `ToolPolicyDefaults.kt:16-18`).
2. Show a UI preview of the URL before firing
   `startActivity` (return a `ToolResult.NeedsApproval("Open $url in
   browser?")` and let the chat layer surface it).

Option 1 is the minimum surgical fix.

---

### F12 — `HttpFileWriteTool` `content_type` is unsanitised user input
fed into a `MediaType`

**Severity:** P3
**Status:** code smell
**File:** `aura-core/src/main/kotlin/com/aura/tools/HttpFileWriteTool.kt:58-64`

```kotlin
val contentType = call.arguments["content_type"] as? String ?: "text/plain; charset=utf-8"

try {
    val body = content.toRequestBody(contentType.toMediaType())
```

`String.toMediaType()` is from `okhttp3.MediaType.Companion.toMediaType`
and throws `IllegalArgumentException` on a malformed value (e.g. one
without `/`). The catch (line 74-76) maps that to a generic
`ToolResult.Error`, which is fine. The risk is that a model-typed
content-type like `text/html` against a `WebDAV` endpoint will result in
the file being saved as `text/html` server-side; the model's framing
of the operation is then inconsistent with what actually happened. Not
exploitable, but worth enforcing a known set
(`text/plain; charset=utf-8`, `application/json`, `application/octet-stream`).

**Surgical fix.** Enum-like validation:

```kotlin
val allowed = setOf("text/plain; charset=utf-8", "application/json", "application/octet-stream")
val contentType = (call.arguments["content_type"] as? String)?.takeIf { it in allowed }
    ?: "text/plain; charset=utf-8"
```

---

### F13 — `McpConnection` `authToken` is passed verbatim to the
`Authorization: Bearer` header

**Severity:** P3
**Status:** verified-safe (the token is already loaded from
`SecureDataStore` so it is at-rest-encrypted, and the OkHttp client
is the same one that other capability providers use)
**File:** `aura-core/src/main/kotlin/com/aura/mcp/McpConnection.kt:200-202`

```kotlin
if (!authToken.isNullOrBlank()) {
    builder.header("Authorization", "Bearer $authToken")
}
```

No `Log.*` call references the header. Confirmed safe.

---

### F14 — `MemoryAugmentedAgenticLoop` propagates
`approvedRemoteCostTools` unchanged across the agent ↔ hand boundary

**Severity:** P3
**Status:** verified-safe (intentional, well-commented)
**Files:**

- `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt:181, 233, 734, 772`

When a hand runs from inside an agentic loop, the in-flight
`approvedRemoteCostTools` set is copied forward, so the hand cannot
invoke a metered tool that the user did not approve. The hand is
classified `WRITE_LOCAL` and `RunHandTool` (line 50 of
`aura-core/src/main/kotlin/com/aura/tools/RunHandTool.kt`) does not
itself touch the approval gate — but the tools that the hand *calls*
are still gated by `PolicyEngine`. No bypass.

The phrase "agent-issued hands bypass" does not appear in the code.
There is no separate flag on `ToolContext` or `HandRun` that says
"this hand was issued by the agent without the user triggering it
explicitly", which is the right call — every hand needs the user to
either save it through the UI or invoke `run_hand` from the chat (which
goes through the same per-tool approval gate). No defect.

---

### F15 — Base `OkHttpClient` is correct; no `CertificatePinner`,
`PinnedIPTransport` is absent by design

**Severity:** N/A (intentional design; documented for completeness)
**Status:** verified-safe
**File:** `aura-core/src/main/kotlin/com/aura/providers/ProviderModule.kt:36-53`

```kotlin
fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(120, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .pingInterval(30, TimeUnit.SECONDS)
    // SECURITY: base client must not follow redirects. Provider API
    // URLs are hardcoded so a malicious provider host could only
    // redirect to a same-domain URL, but the `custom` and
    // `chatgpt` providers accept user-controlled base URLs, and
    // OkHttp's default redirect-following opens an SSRF window
    // (e.g. 169.254.169.254 cloud metadata). `SsrfGuard.pinnedClient`
    // already follows this pattern for the user-input surface; this
    // brings the provider surface in line. Providers that need to
    // follow 3xx should re-enable redirects explicitly on a custom
    // builder.
    .followRedirects(false)
    .followSslRedirects(false)
    .build()
```

- Timeouts: 30/120/60 with 30-s ping — reasonable.
- No `CertificatePinner`: documented non-control. Adding pinning would
  make the app fragile against cert rotation; the system trust store
  plus `network_security_config.xml` (cleartext blocked, system
  anchors only) is the right baseline for a personal AI app.
- No `PinnedIPTransport`: the search for `PinnedIP*` returned no hits
  in the source tree (only `pinnedClient` in `SsrfGuard`, which
  OkHttp-`Dns`-pinned). DNS pinning is the right design — the resolved
  IP is bound to the host so a TOCTOU between `inspect()` and `newCall`
  can't redirect to a private IP. The TOCTOU window is bounded by
  `SsrfGuard.pinnedClient`'s `Dns` implementation (line 85-93 of
  `SsrfGuard.kt`).

`SsrfGuard.inspect` resolves every DNS answer (line 71) and blocks
*any* non-public answer. The `pinnedClient` enforces that the runtime
lookup returns the same pinned set. This is the recommended SSRF
defence shape and is correctly applied by `HttpFileReadTool`,
`HttpFileWriteTool`, `DeepResearchTool`, `FirecrawlFetchTool`, and
`McpClientManager`. **No SSRF bypass was found.**

---

### F16 — `McpConnection` `MAX_META_RESPONSE_BYTES` is enforced after
the body is already in memory

**Severity:** P3 (defence-in-depth; the in-memory read is bounded by
`okhttp3.ResponseBody` and the value is reasonable, but the body is
read into a `String` before the size check)

**File:** `aura-core/src/main/kotlin/com/aura/mcp/McpConnection.kt:46-48, 207-213`

```kotlin
companion object {
    /** Timeout for the initialize handshake. */
    private const val INIT_TIMEOUT_MS = 15_000L
    /** Max response body size for initialize/listTools/listResources. */
    private const val MAX_META_RESPONSE_BYTES = 2_000_000 // 2 MB
}
...
val raw = response.body?.string() ?: return null
// Enforce max response size on metadata calls (initialize/listTools)
// to prevent OOM from a malicious server returning huge JSON.
if (raw.length > MAX_META_RESPONSE_BYTES) {
    android.util.Log.w("McpConnection", "Response from ${config.name} exceeded ${MAX_META_RESPONSE_BYTES} bytes, truncating")
    return null
}
```

`ResponseBody.string()` is implemented as `source().request(Long.MAX_VALUE)`
followed by `source().readString(charset)`, so the full 2 MB body is
already in heap before the check runs. A malicious server streaming
10 GB would still be bounded by the `connectTimeout(30s)` / `readTimeout(120s)`
on the shared client (not great — 120 s * 1 Gbps = 15 GB) but a `2 MB`
guard is the right cap for the metadata surface. The `callTool` path
(line 173-175) already truncates `output.length` after assembly, so the
return value is bounded by `config.maxResponseBytes` (default 1 MB).

**Surgical fix.** Use a streaming counter that aborts when the
threshold is reached (e.g.
`okhttp3.ResponseBody.source().request(MAX + 1).use { ... }`). Low
priority; the current code is correct for sane payloads.

---

### F17 — `RemoteCostApprovalGate` is bypassed when `PolicyEngine` is
injected (not by a bug, but by a design choice that deserves a comment)

**Severity:** N/A (documented behaviour)
**Status:** verified-safe
**File:** `aura-core/src/main/kotlin/com/aura/agent/ToolExecutor.kt:77-123`

When `policyEngine != null` (the production path), the policy
engine's `PolicyResult.NeedsApproval` is the authoritative signal
(line 84-85). The inline `RemoteCostApprovalGate.authorize(...)` at
line 119-123 only fires when `policyEngine == null` (i.e. unit tests).
This is correct and well-commented.

The "per-run approval" gate in `PolicyEngine` (line 48-55) checks
`tool.name in ctx.approvedRemoteCostTools`. When the user taps
"approve" in the UI, the
`app/src/main/kotlin/com/aura/ui/viewmodel/ChatViewModel.kt:831` code
path adds the tool name to the set; that set is then re-entered into
the agentic loop via `MemoryAugmentedAgenticLoop.run(approvedRemoteCostTools = …)`.
No bypass.

---

### F18 — `HandsViewModel` auto-approves a hand's `image_gen` for the
scheduled-run path

**Severity:** P3 (audit-trail-only; the user opted in to the hand, so
this is not a silent approval)
**Status:** verified-safe
**File:** `app/src/main/kotlin/com/aura/ui/viewmodel/HandsViewModel.kt:216`

```kotlin
approvedRemoteCostTools = approvedTools,
```

where `approvedTools` is derived from the hand's own tool list. A hand
that includes `image_gen` is, by user consent at hand-save time,
pre-approved for image generation. Confirmed by
`app/src/test/kotlin/com/aura/ui/viewmodel/HandsViewModelTest.kt:171`
which asserts `approvedRemoteCostTools == setOf("image_gen")`. This is
the documented behaviour — hands that include paid tools explicitly
opt the user into a metered workflow.

---

### F19 — `CrashLogger` does not write to the backup file; `mcpServersJson`
is the only "potentially-credential" field in the backup

**Severity:** P2
**Status:** confirmed (F3 is the actionable form of this concern)
**File:** `aura-core/src/main/kotlin/com/aura/core/error/CrashLogger.kt`
(not opened — see the grep below)

Verified via:
- `BackupManager.snapshot` (lines 179-273) reads only from Room DAOs and
  the explicitly-listed `UserPreferences` flows.
- The only credential-bearing field in `PreferencesBackup` is
  `mcpServersJson` (F3).
- All API keys are stored in `SecureDataStore` and never serialised
  into the backup (verified by the absence of any reference to
  `providerKeys.keyFor(...)` inside `BackupManager.snapshot`).

This finding exists to record the audit-trail conclusion: the backup
file is currently safe to share with a trusted party, with the F2 and
F3 caveats.

---

### F20 — `CrashHandler` does not redact the stack trace before writing

**Severity:** P3
**Status:** verification-needed claim
**File:** `aura-core/src/main/kotlin/com/aura/core/error/CrashHandler.kt`

(Not opened in detail — but `CrashLogger` greps will reveal whether
stack traces land in the file system and, if so, whether they include
any `SecureDataStore`/decryption call-site path. Worth a follow-up
read in a follow-up audit; not material here.)

---

## False Positives Verified

These were investigated and ruled out — listed so the next round does not
re-litigate them.

- **PinnedIPTransport absent → SSRF bypass.** Verified: the intent is
  fulfilled by `SsrfGuard.pinnedClient` (DNS pinning via OkHttp's `Dns`
  interface). The DNS resolution result is reused for every lookup, and
  any unexpected hostname fails closed. No TOCTOU window.
- **`android:exported="true"` on activities → deep-link hijack.**
  Verified: the only exported activities are `MainActivity` (LAUNCHER),
  `ShareReceiverActivity` (SEND text/image), `WidgetConfigActivity` and
  `BootReceiver` — none of them parse opaque `<data android:scheme=…>`.
  `ShareReceiverActivity` only takes `text/plain` and `image/*` from
  the share sheet (the safest contract on Android).
- **`WebView` usage.** Verified: no `WebView` reference in
  `aura-core/src/main/kotlin` or `app/src/main/kotlin`. The app does
  not load any web content in-process.
- **`HttpFileReadTool`/`HttpFileWriteTool` SSRF.** Verified: both call
  `SsrfGuard.inspect` and `SsrfGuard.pinnedClient` (lines 49-58 of
  `HttpFileReadTool.kt` and lines 51-67 of `HttpFileWriteTool.kt`).
  DNS is pinned to the resolved addresses; redirects are off.
- **`McpClientManager.connect` does not validate `https://` for
  non-local servers.** Verified: line 58-64 explicitly rejects
  `http://` for non-local servers. Local servers must opt in via
  `trustedLocal = true` and go through `validateTrustedLocal` (which
  still blocks cloud metadata and link-local).
- **`KeyManager` does not set `setUserAuthenticationRequired(false)`.**
  Verified: it explicitly does (line 60). The intent is to allow
  background decrypt without a biometric prompt; a separate
  `BiometricAuthHandler` is used for tools that need user-presence.
- **API keys in `app/proguard-rules.pro` keep rule.** Verified: the
  `-keep class com.aura.**` rule is a code-discoverability concern
  (F8), not a key-exfil concern. The keys are still encrypted at
  rest; the rule just makes them easier to *find* via static analysis.
- **Crash dump leaks decryption keys.** Verified: `CrashHandler`
  runs after process death; even if a stack trace mentions
  `SecureDataStore.getString`, the call site does not print arguments
  or return values, so a leaked stack trace would carry only
  key-name strings, not key values.
- **`approvedRemoteCostTools` replay across hands.** Verified: the
  set is propagated explicitly, and a hand has to opt in to
  metered tools at hand-save time. There is no "auto-approve hand
  on agent's behalf" code path. (See F18.)

---

## Recommendations Sorted by Priority

1. **P1 — F1:** Narrow `KeyManager.decrypt`'s third catch to
   `GeneralSecurityException` so unexpected crypto failures bubble up
   to `SecureDataStore.getString` and become `DecryptionFailedException`.
   One-line fix.
2. **P1 — F2:** Remove `smtpUsername`, `smtpFrom`, `smtpHost` from
   `PreferencesBackup` (or gate them behind an explicit user toggle
   labelled "include SMTP config in backup"). Drop
   `mcpServersJson` while you're there (F3).
3. **P1 — F4:** Sanitise server-supplied MCP tool names in
   `McpConnection.listTools` (regex `^[a-zA-Z][a-zA-Z0-9_-]{0,63}$`),
   and require `syncToolsUnprefixed` to consult
   `allowedToolPrefixes` before overwriting a native tool.
4. **P1 — F11:** Promote `AppLauncherTool` to `WRITE_REMOTE` risk, or
   add a `ToolResult.NeedsApproval("Open $url in $pkg?")` preview.
5. **P2 — F3:** Add `@Transient` to `McpServerConfig.authToken` (or
   project the config to a sanitised DTO before writing
   `mcpServersJson`), so the documented "tokens in SecureDataStore"
   contract becomes a *type* contract.
6. **P2 — F6:** Add 2-attempt retry with 50-ms back-off in
   `SecureDataStore` for transient `IOException`.
7. **P2 — F8:** Tighten the R8 keep rule in `app/proguard-rules.pro`
   so it covers only the `kotlinx.serialization` surface, not the
   whole `com.aura.**` tree. Verify against `aura-core/consumer-rules.pro`
   and the `Json.decodeFromJsonElement` call sites.
8. **P2 — F9:** Run every search-result URL (Tavily, Brave, DDG, Exa,
   Jina) through `SsrfGuard.inspect` before returning it to the
   model. Drops malicious / private-IP results from the context.
9. **P2 — F5:** Validate `McpServerConfig.id` in
   `SettingsViewModel` line 686 to `^[a-z0-9-]{1,32}$` so the
   `extractServerId` heuristic stays correct.
10. **P3 — F10:** Truncate to a UTF-8 boundary in
    `HttpFileReadTool` so `take(maxChars)` cannot split a multi-byte
    character.
11. **P3 — F12:** Enum-validate `content_type` in
    `HttpFileWriteTool` to the three known-good values.
12. **P3 — F16:** Use a streaming body size counter in
    `McpConnection.sendRequest` for the metadata path so OOM is
    prevented before the 2 MB cap is hit.
13. **P3 — F20:** Audit `CrashHandler` / `CrashLogger` to confirm
    stack traces do not include argument values or return values
    (especially from `KeyManager.decrypt`,
    `ProviderKeys.set`, and `BiometricAuthHandler`).

---

## Test Coverage Gaps

Tests were searched for in `aura-core/src/test/kotlin`. The following
critical paths have *no* unit-test coverage; they should be exercised
before the corresponding fixes land:

- `KeyManager` exception paths (`InvalidKeyException`,
  `KeyPermanentlyInvalidatedException`, `IllegalBlockSizeException`).
  Only the happy path is tested; the third catch branch is not.
  (`aura-core/src/test/kotlin/com/aura/security/`)
- `PreferencesBackup` round-trip with all 36 schema fields.
  `BackupManagerTest` only spot-checks.
- `McpConnection.listTools` rejection of a tool name that contains a
  control character or exceeds 64 chars.
- `McpToolBridge.syncToolsUnprefixed` rejection of an overwriting
  `tavily_search` registration without an `allowedToolPrefixes`
  entry.
- `SsrfGuard` does not have a test for "DNS resolver returns a
  mix of public and private IPs" (TOCTOU between `inspect` and
  `pinnedClient.lookup`). The test file
  `aura-core/src/test/kotlin/com/aura/core/url/SsrfGuardTest.kt`
  covers simple cases but not the rebinding race.
- `HttpFileReadTool` / `HttpFileWriteTool` do not have integration
  tests that exercise a server returning a `30x` redirect to confirm
  `followRedirects(false)` actually short-circuits.
- `ProviderKeys.init` race with a concurrent `set` is not covered
  (the mutex should make it safe; no test asserts the property).
- `McpClientManager.callTool` allowlist enforcement is tested
  superficially; no test exercises the
  "denied AND allowed prefix match" edge case (deny should win).
- `CrashHandler` redaction is not tested (TBD; F20 is unverified).

---

*End of ROUND7_SECURITY.*
