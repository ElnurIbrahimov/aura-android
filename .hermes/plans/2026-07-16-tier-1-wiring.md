# Tier 1 wiring plan for Aura Android v0.18.0

**Goal:** Wire the beyond-SOTA backend substrate (already shipped) into the Settings UI and tool registry so users can actually use it.

**Scope:** 4 items, all backend-exists/UI-missing. No new Room migrations, no new external dependencies.

**Repo:** `D:\aura-android-clean`
**Branch:** `feat/tier-1-friction`
**Verification gate per commit:** `./gradlew :aura-core:testDebugUnitTest --tests <new-test>` then full gate at the end.

---

## Item 1: Register capability-backed tools, deprecate legacy bypass

**Why:** `ImageGenCapabilityTool` and `WebSearchCapabilityTool` exist but are not in `ToolsModule.kt`. `ImageGenTool` hardcodes DALL-E 3 and `WebSearchTool` bypasses `CapabilityRouter`.

**Files:**
- Modify: `aura-core/src/main/kotlin/com/aura/tools/ToolsModule.kt`
- Test: `aura-core/src/test/kotlin/com/aura/tools/ToolsModuleSanityTest.kt`

**Approach:**
1. Add `imageGenCapability: ImageGenCapabilityTool` and `webSearchCapability: WebSearchCapabilityTool` to `provideToolRegistry` constructor.
2. Register `imageGenCapability.tool` as `image_generate` and `webSearchCapability.tool` as `web_search_capability`.
3. Keep legacy `imageGen.tool` and `webSearch.tool` registered for backward compatibility, but the new capability-backed ones are the canonical paths.
4. Optionally update `ImageGenTool` description to say "legacy — use image_generate" if we want to later remove it.

**Test:**
- `ToolsModuleSanityTest` asserts that `image_generate`, `web_search_capability`, `web_search`, and `image_gen` are all in `registry.all()`.
- Assert that `image_generate.risk == REMOTE_COST` and `web_search_capability.risk == READ_ONLY`.

**Commit message:**
```
feat(tools): register capability-backed image and search tools

- Wire ImageGenCapabilityTool (image_generate) and WebSearchCapabilityTool
  (web_search_capability) into ToolsModule.
- Legacy ImageGenTool and WebSearchTool remain for backward compat.
- Adds ToolsModuleSanityTest covering tool registration and risk.
```

---

## Item 2: Tool permissions UI in Settings

**Why:** `ToolPolicyStore` + `PolicyEngine` exist but Settings has no per-tool controls.

**Files:**
- Create: `aura-core/src/main/kotlin/com/aura/agent/policy/ToolPolicyDefaults.kt` — default policy per risk.
- Modify: `app/src/main/kotlin/com/aura/ui/settings/SettingsViewModel.kt`
- Modify: `app/src/main/kotlin/com/aura/ui/screens/SettingsScreen.kt`
- Test: `app/src/test/kotlin/com/aura/ui/settings/ToolPolicyViewModelTest.kt`

**Approach:**
1. Add a `ToolPolicyStore` dependency to `SettingsViewModel`.
2. Add `toolPolicies: Map<String, ToolPolicy>` to `SettingsUiState`.
3. In `reload()`, load stored policies and merge with `ToolPolicyDefaults.forTool(tool)`.
4. Add `setToolEnabled(name, enabled)`, `setToolRequiresConfirmation(name, level)`.
5. Add a new Settings section "Tool Permissions" that lists all tools by category with:
   - name + risk chip
   - enabled Switch
   - confirmation level dropdown (None / Cost only / Always)
6. Persist changes immediately via `ToolPolicyStore.setPolicy(name, policy)`.

**Test:**
- ViewModel test: toggling a tool persists to policy store; reload reads it back.

**Commit message:**
```
feat(settings): add tool permissions section

- Expose per-tool enable/disable and confirmation-level controls.
- Default policy derived from ToolRisk; user overrides persisted via
  ToolPolicyStore.
- Adds ToolPolicyViewModelTest for persistence roundtrip.
```

---

## Item 3: Model roles UI in Settings

**Why:** `ModelRoleRouter` defines 8 roles. Settings currently uses string keys (`"chat"`, `"embedding"`, `"vision"`, `"background"`, `"deep"`) and only 5 rows. Missing: `creative_draft`, `creative_critic`, `planner`, `verifier`.

**Files:**
- Modify: `app/src/main/kotlin/com/aura/ui/settings/SettingsViewModel.kt`
- Modify: `app/src/main/kotlin/com/aura/ui/screens/SettingsScreen.kt`
- Test: `app/src/test/kotlin/com/aura/ui/settings/ModelRoleUiTest.kt`

**Approach:**
1. Replace ad-hoc string roles in SettingsUiState/SettingsScreen with `ModelRole.configurable` (7 roles).
2. Add `roleModels: Map<ModelRole, String>` to `SettingsUiState`.
3. In `reload()`, read each role via `ModelRoleRouter.observe(role).first()`.
4. Add `setRoleModel(role, model)` that calls `userPreferences.setRole(role, model)`.
5. Update `SettingsScreen` "Model roles" section to render rows from `ModelRole.configurable` instead of hardcoded strings.
6. Update the `ModelPickerSheet` `activeModelRole` handling to use `ModelRole` enum.

**Test:**
- UI test: selecting a role model updates the state and persists to UserPreferences.

**Commit message:**
```
feat(settings): expose all ModelRole choices in Settings

- Use ModelRole.configurable as the source of truth for role rows.
- Persist per-role model selections via UserPreferences.setRole().
- Replaces hardcoded string role keys with the typed ModelRole enum.
```

---

## Item 4: MCP server management UI in Settings

**Why:** `McpClientManager` exists but users cannot add/remove servers or inspect discovered tools.

**Files:**
- Create: `aura-core/src/main/kotlin/com/aura/mcp/McpServerEditorState.kt` — data class for UI state.
- Modify: `app/src/main/kotlin/com/aura/ui/settings/SettingsViewModel.kt`
- Modify: `app/src/main/kotlin/com/aura/ui/screens/SettingsScreen.kt`
- Test: `app/src/test/kotlin/com/aura/ui/settings/McpServerViewModelTest.kt`

**Approach:**
1. Add `McpClientManager` dependency to `SettingsViewModel`.
2. Add `mcpServers: List<McpServerConfig>` and `mcpTools: Map<String, List<String>>` to `SettingsUiState`.
3. Add CRUD ops: `addMcpServer(config)`, `removeMcpServer(id)`, `discoverTools(id)`.
4. Add Settings section "MCP Servers" with:
   - list of configured servers (name + allowed prefixes + denied tools)
   - "Add" button with fields: name, baseUrl, allowedToolPrefixes, deniedTools, maxConcurrentCalls, trustedLocal
   - per-server "Discover tools" button that calls `McpClientManager.discoverTools()` and shows discovered tool names
5. Keep this behind a simple section; no runtime invocation yet (that's Tier 2).

**Test:**
- ViewModel test: add/remove server updates state; discover returns tool list.
- Mock `McpClientManager` with mockk.

**Commit message:**
```
feat(settings): add MCP server management UI

- Configure, list, and remove MCP servers from Settings.
- Discover tools per server via McpClientManager.
- Adds McpServerViewModelTest for CRUD and discovery roundtrip.
```

---

## End-of-session verification

```bash
cd D:\aura-android-clean
./gradlew :aura-core:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --continue --console=plain
```

Expected: 112 tasks green, zero new regressions. Pre-existing `FirecrawlFetchToolTest` failures remain unrelated.

---

## Notes / risks

- `ToolPolicyStore` and existing tests may hit DataStore isolation when run in full suite; if so, mark in commit message and run tests in isolation to confirm.
- SettingsScreen is 1102 lines. Additions should be new sections following the existing `SettingsSection` pattern to avoid god-class explosion.
- `ModelRole` rows already partially overlap existing rows (chat default, embedding, vision, background, deep). Refactor to merge them rather than duplicate.
