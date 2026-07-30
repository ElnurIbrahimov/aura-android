# Aura Android — 15-Feature SOTA Upgrade Plan

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** Bring Aura Android to feature parity with 2026's leading AI agent apps (ChatGPT, Claude, Perplexity, Replika) across 15 capability areas.

**Architecture:** Each feature is an independent module. Phases ordered by dependency: infrastructure (Canvas, WebView) → rendering (charts, tables) → input (gallery, files) → intelligence (proactive, affinity) → polish (templates, memory edit). No Room migrations needed for most features — ConversationEntity.metadataJson and existing fields cover the data model needs.

**Tech Stack:** Kotlin/Compose, Hilt, Room, WorkManager, existing MarkdownText renderer, existing PickVisualMedia/GetContent/OpenDocument launchers.

---

## Pre-Audit: What Exists vs What's Needed

| # | Feature | Status | Evidence |
|---|---------|--------|----------|
| 1 | Code Interpreter | DOES NOT EXIST | No sandbox/interpreter in codebase. No Rhino/QuickJS/GraalVM dependency. |
| 2 | Canvas/Artifacts | DOES NOT EXIST | No side-panel document editor. "artifact" appears only in Brain KDoc and SubagentContracts. |
| 3 | Data Visualization | DOES NOT EXIST | No chart library. No Vico/MPAndroidChart dependency. |
| 4 | In-App WebView | DOES NOT EXIST | No WebView in codebase. OpenBrowserTabTool launches system browser. |
| 5 | Conversation Projects | PARTIAL | ConversationEntity has `metadataJson: String = "{}"` — can store project/tag without migration. No UI. |
| 6 | Proactive In-Chat Messages | PARTIAL | AgentPresence.generateOutreachMessage() exists but not wired to chat. DaemonWorker emits events but not in-chat bubbles. |
| 7 | Affinity/Relationship | PARTIAL | EmotionEngine tracks `connection: Float` (0-1). No visible affinity score or progression UI. |
| 8 | Voice Call Mode | PARTIAL | ContinuousVoiceViewModel has LISTENING→THINKING→SPEAKING state machine. ContinuousVoiceOverlay renders it. No "call" UI (dialpad, hang-up, contact-style). |
| 9 | Image in Chat | EXISTS | `PickVisualMedia` launcher at ChatRoute.kt:298. `onImageCaptured` callback wired. Gallery button in input bar. **Working.** |
| 10 | Scheduled Tasks UI | PARTIAL | TriggersSection.kt exists with trigger list. TriggerWorker runs every 15min. No "create scheduled automation" UI — only trigger list display. |
| 11 | Agent Templates | DOES NOT EXIST | AgentEditorScreen has no template gallery. No preset agent definitions. |
| 12 | Inline Citations | EXISTS | CitationChipRow at MessageBubble.kt:547. CitationChip at :597. SourcesSheet at ChatDialogs.kt:82. **Working for deep research.** |
| 13 | File Upload | EXISTS | `OpenDocument` launcher at ChatRoute.kt:311. `onDocumentPicked` callback. DocumentTextExtractor handles PDF/DOCX/TXT/CSV/JSON. **Working.** |
| 14 | Multimodal Output | PARTIAL | MarkdownText renders markdown (bold, italic, code, links, lists). Does NOT render tables or inline images. ImageGenTool returns URL, not inline image. |
| 15 | Memory Edit | EXISTS | MemoryViewModel.update() at :250 calls memoryStore.update(). MemoryScreen has edit dialog with importance slider + tags. **Working.** |

**Key finding:** 4 of 15 features already exist (#9, #12, #13, #15). 5 are partial (#5, #6, #7, #8, #10, #14). 4 don't exist (#1, #2, #3, #4, #11). The plan focuses on the 11 that need work.

---

## Dependency Graph

```
Phase 1 (Canvas) ←── Phase 3 (Charts) ←── Phase 14 (Tables)
Phase 1 (Canvas) ←── Phase 2 (Code Interpreter)
Phase 4 (WebView) ── independent
Phase 5 (Projects) ── independent
Phase 6 (Proactive Messages) ←── Phase 7 (Affinity)
Phase 8 (Voice Call UI) ── independent
Phase 10 (Scheduled Tasks UI) ── independent
Phase 11 (Agent Templates) ── independent
Phase 14 (Multimodal Output) ←── Phase 3 (Charts)
```

---

## Phase 1: Canvas / Artifacts Surface

**Objective:** A side-panel document editor that the model can produce content into and the user can edit. Like Claude's Artifacts.

**Files:**
- Create: `app/src/main/kotlin/com/aura/ui/screens/canvas/CanvasSheet.kt`
- Create: `app/src/main/kotlin/com/aura/ui/screens/canvas/CanvasViewModel.kt`
- Modify: `app/src/main/kotlin/com/aura/ui/screens/chat/ChatRoute.kt` (add canvas trigger)
- Modify: `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt` (emit canvas events)

**Approach:**
- CanvasSheet is a ModalBottomSheet that slides up from the bottom, taking 70% of screen height.
- Content types: markdown (rendered), code (syntax-highlighted monospace), HTML (rendered in WebView from Phase 4), data (rendered as chart from Phase 3).
- The model emits a special marker in its response: `<<<CANVAS:type=markdown\ntitle="My Document"\n>>>content<<<END_CANVAS>>>`
- The agentic loop detects this marker, extracts the content, emits an `AgentEvent.CanvasContent(type, title, content)`, and strips the marker from the chat text.
- ChatRoute listens for CanvasContent events and opens CanvasSheet.
- User can edit the content in a BasicTextField, copy it, share it, or save it to memory.
- Canvas state persists in Conversation.metadata as `canvasContent` and `canvasType`.

**Test approach:**
- CanvasViewModel: test content parsing, type detection, edit/save cycle.
- Marker parsing: test that `<<<CANVAS:...>>>` is correctly extracted and stripped.
- No Compose rendering test (needs instrumentation).

**Commit:** `feat(canvas): artifact side-panel for rich content editing`

---

## Phase 2: Code Interpreter / Sandbox

**Objective:** The model writes JavaScript, Aura executes it in a sandbox, returns stdout. No Python (too heavy for Android). Uses Android's built-in JavaScriptCore (V8 alternative available via WebView).

**Files:**
- Create: `aura-core/src/main/kotlin/com/aura/tools/CodeInterpreterTool.kt`
- Modify: `aura-core/src/main/kotlin/com/aura/tools/ToolsModule.kt` (register)
- Create: `aura-core/src/test/kotlin/com/aura/tools/CodeInterpreterToolTest.kt`

**Approach:**
- Tool name: `code_interpreter`. Risk: REMOTE_COST (uses compute, not network).
- The model writes JavaScript. Aura evaluates it in a WebView's JS engine (hidden, off-screen).
- Input: `code` (string, JavaScript), `language` (string, always "javascript" for now).
- Execution: create a hidden WebView, inject the code, capture `console.log` output, return as tool result.
- Timeout: 10 seconds. Memory limit: WebView's default.
- Security: no access to DOM, no network, no file system. Pure computation only.
- The WebView is created in the tool's execute function, used once, then destroyed.
- Supported operations: math, string processing, array manipulation, JSON parsing, formatting.
- Output: stdout (console.log lines), truncated at 4000 chars.

**Test approach:**
- CodeInterpreterToolTest: test math (1+1=2), string manipulation, JSON parsing, timeout, error handling.
- Use mockk for WebView since unit tests can't create real WebViews.

**Commit:** `feat(tools): JavaScript code interpreter sandbox`

---

## Phase 3: Data Visualization (Charts)

**Objective:** The model can produce chart data and Aura renders it as a visual chart in the chat or canvas.

**Files:**
- Create: `app/src/main/kotlin/com/aura/ui/components/charts/BarChart.kt`
- Create: `app/src/main/kotlin/com/aura/ui/components/charts/LineChart.kt`
- Create: `app/src/main/kotlin/com/aura/ui/components/charts/PieChart.kt`
- Create: `app/src/main/kotlin/com/aura/ui/components/charts/ChartRenderer.kt`
- Modify: `app/src/main/kotlin/com/aura/ui/components/MarkdownText.kt` (detect chart blocks)
- Modify: `app/src/main/kotlin/com/aura/ui/components/MessageBubble.kt` (render chart composable)

**Approach:**
- Pure Compose Canvas drawing — no external chart library dependency.
- Chart data format: the model emits a fenced code block with language `chart-bar`, `chart-line`, or `chart-pie`:
  ````
  ```chart-bar
  {"title": "Sales by Month", "labels": ["Jan","Feb","Mar"], "values": [100, 200, 150]}
  ```
  ````
- MarkdownText detects `chart-*` fenced blocks and renders a ChartRenderer composable instead of code text.
- ChartRenderer parses the JSON and dispatches to BarChart/LineChart/PieChart.
- BarChart: vertical bars with labels, value scale, color from AuraThemeTokens.
- LineChart: polyline with data points, axis labels.
- PieChart: segments with labels, color palette.
- All charts: animated entrance (bars grow, line draws, pie sweeps), 300ms.
- Max 20 data points per chart (prevent OOM).

**Test approach:**
- ChartRenderer: test JSON parsing, type dispatch, data validation.
- BarChart/LineChart/PieChart: test data normalization (values to 0-1 range).

**Commit:** `feat(charts): Compose-native bar/line/pie chart rendering`

---

## Phase 4: In-App WebView

**Objective:** The model can browse the web without the user leaving the app. A WebView embedded in a ModalBottomSheet.

**Files:**
- Create: `app/src/main/kotlin/com/aura/ui/screens/browser/InAppBrowserSheet.kt`
- Create: `app/src/main/kotlin/com/aura/ui/screens/browser/InAppBrowserViewModel.kt`
- Modify: `aura-core/src/main/kotlin/com/aura/tools/OpenBrowserTabTool.kt` (open in-app instead of system browser)
- Modify: `app/src/main/kotlin/com/aura/ui/screens/chat/ChatRoute.kt` (listen for browser events)

**Approach:**
- InAppBrowserSheet: ModalBottomSheet with WebView, URL bar, back/forward, refresh, share.
- OpenBrowserTabTool emits an `AgentEvent.OpenBrowser(url)` instead of launching system browser.
- ChatRoute listens for OpenBrowser events and shows InAppBrowserSheet.
- WebView settings: JavaScript enabled (for modern sites), DOM storage enabled, cookies disabled (privacy).
- URL bar shows current URL, user can type a new URL.
- Back/forward buttons use WebView's history.
- Share button: Intent.ACTION_SEND with the URL.
- Close button: dismisses the sheet, returns to chat.
- Security: no file access, no content access, no mixed content (HTTPS only).
- The WebView is destroyed when the sheet dismisses (no memory leak).

**Test approach:**
- InAppBrowserViewModel: test URL validation, history management.
- Security: test that file:// URLs are blocked.

**Commit:** `feat(browser): in-app WebView for browsing without leaving chat`

---

## Phase 5: Conversation Projects / Grouping

**Objective:** Group related conversations by project. Uses existing metadataJson field — no migration needed.

**Files:**
- Create: `app/src/main/kotlin/com/aura/ui/screens/projects/ProjectsScreen.kt`
- Create: `app/src/main/kotlin/com/aura/ui/viewmodel/ProjectsViewModel.kt`
- Modify: `app/src/main/kotlin/com/aura/ui/screens/HistoryScreen.kt` (add project filter chips)
- Modify: `app/src/main/kotlin/com/aura/ui/nav/NavGraph.kt` (add "projects" route)
- Modify: `app/src/main/kotlin/com/aura/ui/screens/home/HomeSecondaryActions.kt` (add Projects card)
- Modify: `aura-core/src/main/kotlin/com/aura/agent/ConversationStore.kt` (add project CRUD)

**Approach:**
- A "project" is just a tag stored in ConversationEntity.metadataJson as `{"project": "Project Name"}`.
- ProjectsScreen: list all unique project names, conversation count per project, tap to filter.
- HistoryScreen: add a row of FilterChips for projects. Tapping a chip filters conversations by that project.
- Chat header: add a "Set project" option in the overflow menu. Sets metadata["project"].
- ConversationStore: add `setProject(convId, projectName)` and `byProject(projectName): List<ConversationEntity>`.
- No new Room entity, no migration. Projects are derived from conversation metadata.
- Project names are free-text (user types them). Autocomplete from existing project names.

**Test approach:**
- ProjectsViewModel: test project listing, conversation filtering.
- ConversationStore: test setProject/byProject roundtrip (using relaxed mock DAO).

**Commit:** `feat(projects): conversation grouping by project tags`

---

## Phase 6: Proactive In-Chat Messages

**Objective:** When the user opens the app after a gap, the agent has a proactive message waiting as a chat bubble — not just a notification.

**Files:**
- Create: `aura-core/src/main/kotlin/com/aura/proactive/ProactiveMessageStore.kt`
- Modify: `app/src/main/kotlin/com/aura/ui/screens/chat/ChatRoute.kt` (render proactive bubble on open)
- Modify: `aura-core/src/main/kotlin/com/aura/proactive/DaemonWorker.kt` (generate proactive messages)
- Modify: `aura-core/src/main/kotlin/com/aura/consciousness/AgentPresence.kt` (wire outreach generation)

**Approach:**
- ProactiveMessageStore: SharedPreferences-based store with `setMessage(text, timestamp)` and `consumeMessage(): String?`.
- DaemonWorker: on each 15-min cycle, if relationship gap > 2 days, call AgentPresence.generateOutreachMessage(). If non-null, store it.
- ChatRoute: on first composition, call `consumeMessage()`. If non-null, inject it as an assistant message bubble with a "proactive" badge (different color/opacity).
- The proactive bubble is NOT a real conversation turn — it's a UI-only injection that doesn't persist in the conversation history.
- Tapping the bubble's "Let's talk" button sends the message as a user prompt, starting a real conversation.
- Dismissing the bubble (X button) clears it without starting a conversation.

**Test approach:**
- ProactiveMessageStore: test set/consume/clear cycle.
- AgentPresence: test that outreachMessage is generated when daysSinceInteraction >= 3.

**Commit:** `feat(proactive): in-chat proactive messages from agent presence`

---

## Phase 7: Affinity / Relationship Progression

**Objective:** A visible relationship level that increases with interactions and unlocks different conversation styles.

**Files:**
- Create: `aura-core/src/main/kotlin/com/aura/consciousness/AffinityTracker.kt`
- Modify: `app/src/main/kotlin/com/aura/ui/screens/home/AgentPresence.kt` (show affinity level)
- Modify: `app/src/main/kotlin/com/aura/ui/viewmodel/HomeViewModel.kt` (load affinity)
- Modify: `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt` (record affinity per turn)

**Approach:**
- AffinityTracker: stores an `affinityScore: Float` (0-100) in DataStore.
- Score increases by 0.5 per conversation turn (capped at 100).
- Score decays by 0.1 per day without interaction (min 0).
- Levels: 0-10 "Acquaintance", 11-25 "Familiar", 26-50 "Connected", 51-75 "Trusted", 76-100 "Close".
- Each level unlocks: Acquaintance (basic), Familiar (remembers preferences), Connected (proactive outreach), Trusted (proactive suggestions), Close (emotional check-ins).
- Home screen: AgentPresence shows the level name + a progress bar to the next level.
- The level is injected into the system prompt so the model adapts its tone.
- AffinityTracker is a @Singleton with DataStore persistence, loaded in ProactiveBootstrap.

**Test approach:**
- AffinityTracker: test score increase, decay, level computation, persistence.

**Commit:** `feat(affinity): relationship progression with visible levels`

---

## Phase 8: Voice Call Mode

**Objective:** A phone-call-like UI for continuous voice mode. Natural turn-taking, call screen, hang-up.

**Files:**
- Create: `app/src/main/kotlin/com/aura/ui/voice/VoiceCallScreen.kt`
- Modify: `app/src/main/kotlin/com/aura/ui/voice/ContinuousVoiceOverlay.kt` (add "call" mode)
- Modify: `app/src/main/kotlin/com/aura/ui/screens/chat/ChatRoute.kt` (add call button)

**Approach:**
- VoiceCallScreen: full-screen composable with:
  - Agent avatar (breathing glow, color shifts with state)
  - State label: "Listening…", "Thinking…", "Speaking…"
  - Animated waveform when listening/speaking
  - Mute button, End Call button (red, bottom center)
  - Caller-style timer showing call duration
- ChatRoute: add a phone icon button in the chat header. Tapping it launches VoiceCallScreen as a full-screen activity (not a sheet).
- ContinuousVoiceViewModel: already has the state machine. VoiceCallScreen subscribes to it.
- End Call: stops STT + TTS, returns to chat screen.
- Mute: stops STT only, TTS continues (user can hear but not respond).
- The call UI replaces the current ContinuousVoiceOverlay (which is a floating panel). The overlay mode is kept for quick voice input; the call mode is for extended conversations.

**Test approach:**
- VoiceCallScreen: no unit test (pure Compose). State machine already tested in ContinuousVoiceViewModel tests.

**Commit:** `feat(voice): phone-call-style voice mode with full-screen UI`

---

## Phase 9: Inline Citations for Regular Web Search (already exists for deep research)

**Objective:** Show inline citation chips for regular web search results, not just deep research.

**Files:**
- Modify: `aura-core/src/main/kotlin/com/aura/tools/WebSearchTool.kt` (return citations)
- Modify: `aura-core/src/main/kotlin/com/aura/tools/BraveSearchTool.kt` (return citations)
- Modify: `aura-core/src/main/kotlin/com/aura/tools/TavilySearchTool.kt` (return citations)
- Modify: `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt` (extract citations from tool results)

**Approach:**
- Web search tools already return URLs in their text output. The issue is the agentic loop doesn't parse them into Citation objects.
- Add a `Citation` section to the tool result format: after the search results, append `\n\n[COURCES]\n1. Title - URL\n2. Title - URL\n[/SOURCES]`.
- The agentic loop detects `[SOURCES]...[/SOURCES]` in tool results, parses them into Citation objects, and attaches them to the current turn.
- The existing CitationChipRow in MessageBubble already renders citation chips.
- This makes citations work for ALL web search results, not just deep research.

**Test approach:**
- Citation parsing: test that `[SOURCES]...[/SOURCES]` is correctly extracted and parsed.
- Test that regular search results produce citation chips.

**Commit:** `feat(citations): inline citation chips for all web search results`

---

## Phase 10: Scheduled Tasks UI

**Objective:** User-facing UI to create scheduled automations. Currently triggers exist but the creation UI is minimal.

**Files:**
- Modify: `app/src/main/kotlin/com/aura/ui/settings/sections/TriggersSection.kt` (add creation dialog)
- Create: `app/src/main/kotlin/com/aura/ui/screens/ScheduleScreen.kt` (enhanced trigger creation)
- Modify: `app/src/main/kotlin/com/aura/ui/viewmodel/ScheduleViewModel.kt` (add trigger CRUD)

**Approach:**
- Enhanced TriggersSection with "Create Trigger" FAB.
- Trigger creation dialog:
  - Name field
  - Condition: time-based (daily at HH:MM, weekly on DOW, interval every N hours)
  - Action: Notify (title + body), Start Chat (prompt), Run Hand (hand picker)
  - Enable/disable toggle
- Trigger list: shows all triggers with condition + action summary, enable/disable switch, delete button.
- ScheduleScreen: existing screen enhanced with trigger management (not just task/reminder display).
- Triggers are stored in UserPreferences.triggers (existing JSON list). No new schema.

**Test approach:**
- ScheduleViewModel: test trigger creation, validation, deletion.

**Commit:** `feat(triggers): user-facing scheduled automation creation UI`

---

## Phase 11: Agent Templates

**Objective:** Pre-built agent templates the user can pick from when creating a new agent.

**Files:**
- Create: `aura-core/src/main/kotlin/com/aura/agent/AgentTemplates.kt`
- Modify: `app/src/main/kotlin/com/aura/ui/screens/AgentEditorScreen.kt` (add template picker)
- Modify: `app/src/main/kotlin/com/aura/ui/viewmodel/AgentEditorViewModel.kt` (load template)

**Approach:**
- AgentTemplates: a list of preset AgentEntity definitions:
  - "Research Assistant" — toolsAllowed: web_search, deep_research, recall. Personality: formal, verbose, low humor.
  - "Coding Buddy" — toolsAllowed: web_search, recall, code_interpreter. Personality: casual, technical, high humor.
  - "Creative Writer" — toolsAllowed: creative_read_project, creative_engine, recall. Personality: warm, verbose, high humor.
  - "Personal Trainer" — toolsAllowed: set_reminder, calendar_read, location_now. Personality: energetic, direct, low verbosity.
  - "Study Buddy" — toolsAllowed: recall, remember, web_search, index_document. Personality: patient, formal, methodical.
  - "Journal Companion" — toolsAllowed: remember, recall, tts_speak. Personality: warm, empathetic, high proactivity.
- AgentEditorScreen: when creating a new agent, show a template picker (grid of cards with icon + name + description). Tapping a template pre-fills the editor with the template's values.
- "Blank" option for custom agents (no template).
- Templates are read-only — the user can modify after selecting, but the template itself doesn't change.

**Test approach:**
- AgentTemplates: test that each template has valid toolsAllowed (all tool names exist in ToolRegistry).
- AgentEditorViewModel: test template loading and pre-fill.

**Commit:** `feat(agents): pre-built agent templates for quick setup`

---

## Phase 12: Multimodal Output — Tables

**Objective:** Render markdown tables in chat. Currently MarkdownText doesn't handle tables.

**Files:**
- Modify: `app/src/main/kotlin/com/aura/ui/components/MarkdownText.kt` (add table parsing + rendering)
- Create: `app/src/main/kotlin/com/aura/ui/components/MarkdownTable.kt`

**Approach:**
- Detect markdown table syntax in the text:
  ```
  | Header 1 | Header 2 |
  |----------|----------|
  | Cell 1   | Cell 2   |
  ```
- Parse into a data class: `MarkdownTable(headers: List<String>, rows: List<List<String>>).
- Render as a Compose Table: LazyColumn of rows, with header row styled differently (bold, surface1 background).
- Column widths: equal distribution, with horizontal scroll if total width exceeds screen.
- Cell styling: padding 8dp, border between cells (borderSubtle).
- Support alignment markers (`:---`, `:--:`, `---:`) for left/center/right text alignment.
- Integration with MarkdownText: when the parser encounters a table block, it emits a `MarkdownBlock.Table(table)` instead of `MarkdownBlock.Text(text)`.

**Test approach:**
- MarkdownTable: test parsing (headers, rows, alignment), edge cases (empty cells, missing columns, no header separator).

**Commit:** `feat(markdown): render markdown tables in chat`

---

## Phase 13: Multimodal Output — Inline Images

**Objective:** Render images inline in chat responses. When the model generates an image via ImageGenTool, show it inline instead of as a tool result URL.

**Files:**
- Modify: `app/src/main/kotlin/com/aura/ui/components/MessageBubble.kt` (detect image URLs in response)
- Modify: `aura-core/src/main/kotlin/com/aura/tools/ImageGenTool.kt` (return image in a structured format)
- Modify: `aura-core/src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt` (extract image results)

**Approach:**
- ImageGenTool already returns a URL or base64. The issue is it's returned as a tool result string, not rendered inline.
- Add a structured marker: tool results that contain images use `[IMAGE:url]` or `[IMAGE_BASE64:data]`.
- The agentic loop detects `[IMAGE:...]` in tool results, extracts the image, and attaches it to the current turn as `Turn.images: List<String>`.
- MessageBubble: after rendering the text, check for images on the turn. Render each as an AsyncImage (Coil) with rounded corners, max height 200dp, clickable to open full-screen.
- The image appears below the text, above the citation chips.
- For base64 images: decode and render directly. For URLs: load with Coil.
- Add Coil dependency if not already present (check build.gradle.kts).

**Test approach:**
- Image marker parsing: test `[IMAGE:...]` extraction.
- Turn image storage: test that images are correctly attached to turns.

**Commit:** `feat(multimodal): inline image rendering in chat responses`

---

## Phase 14: Features Already Working (No Action Needed)

These 4 features were identified in the audit but are already fully implemented:

| # | Feature | Evidence |
|---|---------|----------|
| 9 | Image in Chat | PickVisualMedia launcher at ChatRoute.kt:298, onImageCaptured callback, gallery button in input bar |
| 12 | Inline Citations | CitationChipRow at MessageBubble.kt:547, CitationChip at :597, SourcesSheet at ChatDialogs.kt:82 |
| 13 | File Upload | OpenDocument launcher at ChatRoute.kt:311, onDocumentPicked callback, DocumentTextExtractor handles PDF/DOCX/TXT/CSV/JSON |
| 15 | Memory Edit | MemoryViewModel.update() at :250, MemoryScreen edit dialog with importance slider + tags field |

**No action needed for these.** They are listed for completeness.

---

## Summary Table

| Phase | Feature | New Files | Modified Files | New Tests | Dependencies | Estimated Commits |
|-------|---------|-----------|----------------|-----------|--------------|-------------------|
| 1 | Canvas/Artifacts | 2 | 2 | 3 | None | 1 |
| 2 | Code Interpreter | 2 | 1 | 5 | None | 1 |
| 3 | Data Visualization | 4 | 2 | 4 | Phase 1 | 1 |
| 4 | In-App WebView | 2 | 2 | 3 | None | 1 |
| 5 | Conversation Projects | 2 | 4 | 4 | None | 1 |
| 6 | Proactive Messages | 1 | 3 | 3 | None | 1 |
| 7 | Affinity | 1 | 3 | 4 | None | 1 |
| 8 | Voice Call UI | 1 | 2 | 0 | None | 1 |
| 9 | Inline Citations (all search) | 0 | 5 | 3 | None | 1 |
| 10 | Scheduled Tasks UI | 1 | 3 | 3 | None | 1 |
| 11 | Agent Templates | 1 | 2 | 3 | None | 1 |
| 12 | Markdown Tables | 1 | 1 | 4 | None | 1 |
| 13 | Inline Images | 0 | 3 | 3 | None | 1 |
| 14 | Already Working (4 features) | 0 | 0 | 0 | None | 0 |
| **Total** | **11 new features** | **17** | **33** | **39** | | **13 commits** |

---

## Prior Plans Alignment

- `.hermes/plans/2026-07-16_130402-aura-beyond-sota-master-plan.md` — beyond-SOTA substrate (CapabilityRouter, ModelRoleRouter, ToolPolicy, TraceSink, AgentRun, MCP, Subagents, CreativeCouncil, TasteEngine). This plan builds ON TOP of that substrate — it adds user-facing features that the substrate enables.
- `.hermes/plans/2026-07-20-sota-upgrade-memory-research-agents.md` — memory SOTA (BM25, reranker, query rewriting) + multi-agent system. This plan is complementary — it adds UI/UX features, not core intelligence.
- No conflicts with prior plans. All features are new surfaces, not modifications to existing intelligence.

---

## Execution Order

Recommended execution order (by daily-use impact + dependency):

1. **Phase 4** — In-App WebView (immediate daily value, no deps)
2. **Phase 1** — Canvas/Artifacts (foundation for charts + code output)
3. **Phase 12** — Markdown Tables (quick win, high visibility)
4. **Phase 3** — Data Visualization (depends on Canvas)
5. **Phase 2** — Code Interpreter (depends on Canvas for output display)
6. **Phase 13** — Inline Images (quick win, high visibility)
7. **Phase 6** — Proactive Messages (makes agent feel alive)
8. **Phase 7** — Affinity (relationship progression)
9. **Phase 5** — Conversation Projects (organization)
10. **Phase 8** — Voice Call UI (voice mode upgrade)
11. **Phase 9** — Inline Citations for all search (quick win)
12. **Phase 10** — Scheduled Tasks UI (automation creation)
13. **Phase 11** — Agent Templates (quick setup)

Phases 1-13 are 13 commits. Phase 14 is no action. Total: 13 commits, ~39 new tests, 17 new files, 33 modified files.