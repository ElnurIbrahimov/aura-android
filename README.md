# Aura Android

![CI](https://github.com/ElnurIbrahimov/aura-android/actions/workflows/ci.yml/badge.svg)

A native Android superapp — a full port of the Python [Aura](https://github.com/ElnurIbrahimov/apprentice-agent) assistant to Kotlin/Compose. Single-user, offline-first storage, all brains in the cloud.

This is my personal copy.

## Status

**v0.66.0** (versionCode 81) — the first version bump in 114 commits. v0.65.0 was set on 2026-08-06 and everything since shipped under it: the 2026-08-08 review remediation (tool-history fidelity, untrusted-context framing, consciousness persistence, FTS4 recall with corpus-weighted BM25, source-scan test integrity), the 2026-08-10 capability sweep (live voice calls, screen control, retrieval eval harness, prompt caching), and the 2026-08-11/12 wave (living worlds, task salience, proactive gates and outcome measurement, the memory correction spine, curiosity, situational awareness, background health). `check-version-docs.sh` gates docs against source, which is exactly why it could not notice: the two agreed on a number that had stopped moving.

Then the 2026-08-13 pass, which found the code itself in good shape and every real defect in the seam between code and process — a CI job that had stopped finishing, a `prune()` with no caller, a preference six subsystems gate on that nothing ever set, and this version string. See [ENGINEERING_HISTORY.md](ENGINEERING_HISTORY.md) §2.8 and §3.

- 81 built-in tools (web search over Tavily/Brave/DDG/SearXNG/Wikipedia, vision, image gen x2, deep + parallel research, firecrawl fetch, Jina reader, knowledge graph, weather, translate, timer, code interpreter, SMS, email, biometric prompt, phone-native tools, reminders, skills, creative studio, evolution, world model, taste, document indexing, canon query, living world query, media generation, agent delegation, councils, schedule task, gmail, google calendar, google drive, outlook mail, outlook calendar, onedrive) plus dynamically registered MCP tools
- Creative Studio (Room-backed projects, world bible, simulations, drafts, continuity, 6 creative-engine modes, genre craft prompts for 5 genres, narrative world bible rendering, conversation continuity via artifact history, word count targets, smart codex injection)
- Manuscript ledger (`SceneLedger`, `com.aura.creative.longform`): after each scene commits, one cheap-tier model call writes a two-sentence synopsis onto the beat and canon triples into `canon_facts`, flagging contradictions on a fixed single-valued predicate allowlist (location, age, alive, allegiance, occupation, rank) into `continuity_issues` — both tables had full DAOs, indices and backup mappers since the schema was designed and had never held a row. Every scene now also receives `storySoFar` (prior synopses, oldest dropped first) and `retrieved` (lexical search over drafted scenes, no embeddings) — `SceneContextBuilder` documented both since it was written and no production caller had ever supplied them, so scene twelve of a novel had never read scenes one through ten. `canon_query` now reads these facts instead of running a malformed filter against the user's personal memory store. A per-slice back-fill heals scenes drafted before this existed, which is why the change ships with no Room migration. Surfaced as a Canon card in the Manuscript tab: fact count, open contradictions, dismissible as intentional
- Living worlds (Creative → Living tab): a creative project's world bible compiles into a **simulation that runs on its own**, one world day per real hour, in a WorkManager job. Factions hold scarce quantities — land is a strictly conserved pool, so one faction only gains it by taking it from another — and executable rules fire on thresholds. **The engine never calls a model**: a tick is integer arithmetic, which is what makes it deterministic (and therefore replayable), catchable-up offline, and testable without a key. Quantities are scaled `Long`s and each decision draws from a content-keyed SplitMix64 substream, so adding a rule cannot shift an unrelated outcome. A long absence is collapsed by one closed-form `fold`, so returning after three months costs the same as after three days. Seeded from the world bible with author-supplied starting numbers, since the bible carries no quantities at all
- Living world narration: a `NotabilityScorer` (magnitude x scarcity x novelty, plus a bonus when the map changes hands) gates which events are worth a model call at all. Only the top 3 go out, in **one batched request**, on the background model with no thinking budget and a 700-token ceiling, capped at 12 per world per rolling day — counted from the events table itself rather than a counter row that could drift. A world that merely runs costs nothing; unnarrated moments can be written up later on demand, one tap at a time. Reach and surprise are deliberately **absent** from the score rather than faked: both need the belief layer, and three real factors beat five where two are invented
- Living world reports reach the user through `ProactiveEvents.record` only (never the `replay = 0` bus, where a background emit is dropped silently), and are the first writer `correlationTag` has ever had
- Prose craft tools (Show Don't Tell, Describe, Expand, Shrink Ray, Twist, Rewrite — operate on selected text)
- Voice calibration (learn user's prose style, mirror in generated content)
- Tension analyzer (per-scene tension scoring, pacing diagnosis, recommendations)
- Character progression tracker (auto-extract how characters change scene by scene)
- Creative Council (10-role multi-agent review: Director, Writer, Story Editor, Continuity Editor, World Simulator, Researcher, Art Director, Cinematographer, Sound Designer, Audience Critic)
- Production Pipelines (novel, screenplay, short film, trailer, podcast drama, RPG campaign — stage-specific prompts)
- Skills (installable skill cards, skill-backed tool dispatch)
- Memory stack (Room + 6-signal RRF retrieval + FTS4 lexical candidates + corpus-weighted BM25 + LLM relevance rescoring (a prompt scorer, not a cross-encoder model — up to 5 extra model calls per recall) + query rewriting + 14-day FadeMem with access-frequency decay + heuristic + LLM WriteGate + recall caching)
  - **Retrieval is lexical unless you set an embedding model.** This line said "cloud embeddings" and a default install has none: `ProviderKeys.embeddingModel` defaults to `""`, which falls through to `LocalEmbedder` — a 384-dim hash-and-project sketch whose own KDoc calls it a pseudo-embedding. `RetrievalConfig.vectorPoolSize` is also 0, measured: the vector arm scored 0.4837 against 0.7976 without it, because a hash sketch injects near-noise. Set an embedding model in Settings → AI & Models (Ollama Cloud) and `ReembedWorker` backfills. Whether the vector arm should then come back on is Gate B in ENGINEERING_HISTORY §3, and is a measurement, not an opinion.
- Knowledge graph (Room-backed, 11 node types, 18 edge types, LLM-extracted per turn, entity resolution via Levenshtein dedup)
- Hands (user-defined automation macros, persisted, triggerable by phrase)
- Tasks + Reminders (Room-backed, manageable in-app and via tool)
- Task salience: a list that **shrinks on its own**. Every pending task carries a salience score distinct from `priority` — priority is what you said it was worth when you wrote it down and never moves; salience is what the evidence says it is worth now. It halves every ~21 days untouched, drops on each **"Not now"**, and drops hard when a deadline passes and nothing breaks. Below the threshold a task moves into a collapsed **Quiet** section — never deleted, always searchable, and brought back by a tap or an approaching deadline. Deliberately inverts the universal todo-app behaviour of making overdue items louder: repeated deferral is treated as evidence *against* a task, because a list you abandon wholesale is a worse outcome than a task you miss. Rides the existing 6-hourly `DecayWorker`, and every disappearance can state its own reason
- Agentic loop (ReAct-style, 10 steps max, streams text + tool calls, abort-safe, parallel tool execution, 4k char tool-result truncation budget, reflection after failures, StrategyBandit Thompson Sampling over reasoning strategies)
- Extended thinking always on (Anthropic thinking block, OpenAI reasoning_effort=high, Gemini thinkingConfig — 32K default budget, configurable in Settings)
- Optional pre-answer planning pass (off by default — costs an extra model call per message; enable in Settings → AI & Models for tool-heavy work)
- 17 LLM providers (Ollama Cloud, Anthropic, OpenAI, DeepSeek, Gemini, Groq, OpenRouter, Mixture-of-Agents, Mistral, xAI Grok, Together AI, Cerebras, NVIDIA NIM, Meta Llama, Agnes AI, ChatGPT subscription, Custom OpenAI-compatible endpoint)
- 7 specialists (general, coder, researcher, writer, creative, executive, phone-native) with keyword router + tool-allowlist enforcement
- Multi-agent system (7 builtin agents seeded from specialists, per-agent memory scopes, 6-dimension personality profiles, delegate_to_agent tool, AgentCouncil, user-creatable agents via Settings)
- Consciousness layer (NarrativeSelf evolving identity fed by dream cycles, IntrinsicMotivation 4 drives fed by real DB signals via DriveSignals — KG gap nodes, unresolved contradictions, low-confidence strategies, TheoryOfMind user mental model, ProactiveAwarenessEngine, AgentPresence outreach). All five stateful components persist across cold starts, and all five are in the backup schema as of v18 (`AuraBackupSchema18.kt`) — this line said "none are in the backup schema yet" for three days after they went in, and the line 30 rows below it said the opposite.
- 5-tab bottom nav (Home, Chat, Memory, Tasks, Settings) + 27 secondary routes (History, Hands, Tasks, Reminders, Tools, Skills, Creative, Creative Project, Production, Proactive, Agent Runs, Agent Run Detail, Capabilities, Council, Dreams, Dream Log, Agent Profiles, Mind, Knowledge Graph, Profile, Identity Editor, Diagnostics, Crash Logs, Schedule, Evolution Inbox, Evolution Rollback, Agent Editor)
- Voice I/O (push-to-talk STT via Android SpeechRecognizer, auto-TTS via Android TextToSpeech, sentence-boundary streaming TTS in continuous voice mode, voice call UI)
- Live voice calls (OpenAI Realtime over WebSocket — duplex audio, server VAD, barge-in with playback-position truncation, tool calling capped at WRITE_LOCAL, 10-minute session budget, typed microphone foreground service). Reached from the mic button in chat, which now opens a sheet offering the live call or push-to-talk; push-to-talk remains the default and works with every provider.
  - **Reachable since 2026-08-18, and this line claimed it for weeks before that.** `RealtimeCallController` had no production caller and `LiveCallSheet` had no caller either, so the whole stack — 1,476 lines and ~60 tests — was reachable only from its own test suite. `ProjectSpineIsWiredTest` named it as the repo's standing example of exactly this defect and gated four *other* paths. It now gates this one. **Not yet exercised on a device:** the manual table in `docs/ANDROID_TEST_PLAN.md` has still never been run, and this is a beta protocol over a real socket.
- Screen control (accessibility-tree read + tap/type/scroll/swipe in any app, off by default; 5min/25-action sessions bound to one package, non-overridable denylist including Aura itself, semantic tripwire on irreversible labels, refusal while a password field is visible)
- Proactive suggestions are **actionable and self-measuring**. Every finding type now maps to a real `ProactiveAction` (navigate / open chat / hand off to the calendar app), dispatched from one place — two of the old route strings pointed at nothing (`"graph"` when the route is `"knowledge_graph"`, and `"calendar"` which has no screen) and were latent crashes that stayed harmless only because nothing ever navigated. `OpportunityEntity.suggestedActionJson` gained its first reader since it was written. Tapping a suggestion records `tapped`/`acted` — the first positive signals this system has ever produced
- Three proactive gates were pinned shut by one bug: `"dismissed"` was the only interaction anything recorded, so the salience filter's negative ratio hit 1.0 on the **first** dismissal, dropping every finding's best possible score under the threshold permanently; the motivation threshold sat at its ceiling; and `AdaptiveTimingEngine` scored every hour at zero against a 0.4 bar, so `isGoodTime()` could never return true. Fixed with an appetite sample floor, a neutral-centred timing scale where no evidence means 0.5 rather than 0, and local-hour bucketing (it bucketed in UTC and read local). `DaemonWorker` also published via the `replay = 0` bus at five sites, so background insights were dropped before ever reaching Room
- Proactive **outcome measurement**: when a suggestion goes out, Aura records what it was *about* — which task, which memories, which graph nodes — and a pass on the existing 6-hourly `DecayWorker` asks days later whether that thing actually changed. Optimising for outcome rather than engagement is the point: a tap says the card looked interesting, the task being finished says it was right. Signals are chosen to be unconfounded — task `deferCount`/`lastTouchedAt` rather than salience, and memory `accessCount` rather than `decayScore`, because both of those drift on their own on the very same worker. Three of the eight finding types are recorded `unobservable` and given no checker at all rather than being scored against something adjacent
- **Interruption is earned per category, and revocable.** Everything starts in-app and silent; a kind of suggestion earns a notification only after ≥8 closed outcomes in 30 days, a ≥50% success rate, and evidence it lands in this hour ±1 — and loses it below 35% (hysteresis so it can't flap), or immediately for 7 days if you dismiss one of its notifications. The ledger is a **derived read**, recomputed from countable rows every time, so "why did this notify me" is always a sentence. Per-category override is Always / Never / **Earned** (default), because the ledger measures what worked and that isn't the same as what you want
- Proactive: WorkManager daily morning brief (customizable time) + 6h memory decay + 15-min calendar check worker (Calendar Instances API, 30-min lookahead, persisted dedup — no foreground service) + daemon thinking worker (configurable interval, default 60 min, network-connected + battery-not-low constraints, background model; council debates off by default)
- Emotional state engine (4 dimensions: tension, connection, energy, focus — with inertia, decay, and heuristic signal detection)
- Adaptive response profiles (tone adapts based on emotional state)
- Affinity tracker (5-level relationship progression, injected into system prompt)
- Share receiver (`text/plain` + `image/*` from Android share sheet)
- User profile (learned from conversations via regex + LLM fallback, editable in Settings)
- Specialist and persona identity customization (Settings)
- Onboarding wizard (paste API key + verify connectivity)
- Biometric gate for sensitive tools and app lock (BIOMETRIC_STRONG). App lock now covers **every** door, not just the main screen: `AppLockState` is process-scoped and relocks when the whole app backgrounds (a started-activity count, so navigating from chat into Quick Ask does not demand a fingerprint mid-tap). Until 2026-08-18 "unlocked" was `remember`-scoped inside `MainActivity`'s composition, so `QuickAskActivity` — the same `ChatViewModel` with memory recall — opened straight from the home screen with no check, and the widgets painted memories and reminder bodies onto the home screen regardless. Both now show "Hidden while Aura is locked" and read nothing while locked.
- MCP client (connect external tool servers, auto-registers discovered tools into ToolRegistry, persists server configs, auth token support via SecureDataStore)
- Evolution system (LLM-authored patches over 4 action types — PATCH_SKILL, RETIRE_SKILL, PROMOTE_TO_HAND, CONSOLIDATE_MEMORIES; detector → author → validate → propose → apply → outcome pipeline, approve/reject from inbox, typed rollback snapshots, enforced safety guard — skills never auto-apply, deterministic evidence-based outcome scoring)
- Agent runs (durable, resumable, DAG-resolved step execution via WorkManager, checkpoint/resume, approval flow)
- World model (beliefs, evidence, events, opportunities in separate Room tables, surfaced in system prompt)
- Taste engine (preference signal recording, style profiling, model routing, prompt enhancer)
- Capability router (Exa search, Jina reader, Stability image, Kling video, WorldLabs 3D, ElevenLabs TTS — each requires its own API key)
- Tool policy engine (layered precedence: built-in risk -> incognito gate -> user policy -> per-run approval, configurable per tool)
- Agent trace + observability (`TraceSink`, a 10,000-event in-memory ring buffer surfaced in the Diagnostics screen). 8 of 20 TraceSink event types are emitted by production code: RUN_STARTED, RUN_COMPLETED, RUN_FAILED, STEP_STARTED, TOOL_CALL, TOOL_RESULT, MEMORY_RECALLED, PROVIDER_FAILOVER — all from `MemoryAugmentedAgenticLoop`. The other twelve (RUN_CANCELLED, STEP_COMPLETED, STEP_FAILED, APPROVAL_REQUESTED, APPROVAL_DECIDED, CHECKPOINT, SUBAGENT_SPAWNED, SUBAGENT_COMPLETED, ARTIFACT_CREATED, ARTIFACT_REVISED, CONTEXT_TRUNCATED, KNOWLEDGE_EXTRACTED) are declared and never written, so Diagnostics is a trace of the loop, not an audit trail of the run
- Document indexing (PDF/text import, chunking, embedding, retrieval)
- Global search (conversations, memories, tasks, hands, skills, knowledge graph in one query)
- Google Workspace + Microsoft Graph integrations (Gmail, Google Calendar, Google Drive, Outlook Mail, Outlook Calendar, OneDrive — OAuth 2.0, tokens in SecureDataStore)
- In-app WebView, Canvas/Artifacts, Compose-native charts, JavaScript code interpreter, inline image generation, proactive in-chat messages
- Backup/restore (JSON export/import, SecureDataStore for credentials, schema v29, 11 Room databases, merge-or-replace on import, disk-spooled snapshot-rollback when a restore fails mid-import, and a marker that reports an interrupted restore on next launch). v18 adds tool policies and all five consciousness components, which were never in a backup before.
- Craft guidance is **data, not constants**. The genre and mode prompts in `GenreCraftPrompts` are seeded into `SkillsStore` as builtin skills on first run (`CraftSkills`), so the author can read and rewrite them, `use_skill("craft-novel")` returns them, and the evolution system's `PATCH_SKILL` action finally has real targets. The constants stay as the seed source *and* the fallback: a deleted, blank or unreadable skill drafts with the craft that shipped rather than with none. Builtins are editable and resettable, never deletable.
- **A manuscript can leave the phone.** Export in the Manuscript tab compiles the drafted outline into one Markdown document and hands it to the share sheet — Drive, Obsidian, email, anywhere. Until now the only outbound paths in the app were images and URLs, so prose, the thing Creative Studio exists to produce, terminated in a Room table. Gaps are stated rather than hidden: a beat with no scene reads `*[not yet written]*`, one whose text cannot be recovered reads `*[scene text unavailable]*`, and scenes orphaned by a re-planned outline are counted in a footer instead of vanishing. Reads through `CreativeArtifactStore.currentRevision` rather than `currentContent`, whose `previewText` fallback would place 200-character stubs mid-novel that read like finished scenes.
- 3,379 unit tests, 0 failures (checked against the JUnit XML by `scripts/check-test-count.sh` in CI)
  - **Down 45, on purpose.** Deleting `SpecialistRouter`, `com.aura.pipeline.ProductionPipeline` and `AgentTextAccumulator` took their tests with them — all of it thorough coverage of code that had no production caller. ENGINEERING_HISTORY §4 records test count becoming the quality metric while screens went untested; a number that can only go up is the mechanism by which that happens.
- 78 instrumented test methods (31 Room migration-chain methods in :aura-core, 43 in :app — 38 Compose rendering, 5 device smoke). The :aura-core migration suites now **execute** in CI on an emulator (the `migrations` job); :app's are still **compiled** only, and run via `connectedAndroidTest` on a device
- 5 device smoke checks (`scripts/smoke.sh`) asserting outcomes against a real model and a real database: a stated preference becomes a categorised memory, a pleasantry becomes none, an imported document is retrievable and outlined, a fired worker leaves a non-empty run detail, the screenshot path answers rather than going silent. These are the only tests in the repo that exercise the real provider graph.
- 2 daily-use UX round-3 fixes (selection in code blocks + table cells, soft-delete with 7-day retention)

Note: the app uses **cloud providers only** — there is no on-device model.

## Quick start (sideload on a real device)

### Prerequisites
- Android 8.0+ (API 26+)
- ~100MB free storage
- A cloud LLM API key (Ollama Cloud is free: https://ollama.com/settings/keys)

### Build the APK
```bash
./gradlew :app:assembleDebug
# APK lands at: app/build/outputs/apk/debug/app-debug.apk
```

### Install on your phone
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```
Or transfer the APK to the phone and tap it (enable "Install from unknown sources" in Settings -> Apps).

### First-run setup
1. Open Aura.
2. Follow the onboarding wizard — paste your API key (Ollama Cloud recommended) and tap verify.
3. Tap the **Chat** tab.
4. Tap the model name in the header -> pick a model (or pick a specialist chip).
5. Type or tap the mic icon to start talking.

### Permissions the app will request
- **Internet** — for cloud LLMs and web search
- **Microphone** — for voice input (first time only)
- **Location** — for the `location_now` tool
- **Camera** — for image input to vision tools
- **Calendar** — for `calendar_read` / `calendar_write`
- **Contacts** — for `contacts_search`
- **Notifications** — for posting reminders + the morning brief
- **Foreground service (mediaProjection)** — for screen capture; consent is requested fresh for every capture
- **Biometric** — for the biometric gate tool
- **Boot completed** — to reschedule proactive workers after reboot

## Architecture (TL;DR)

```
+--------------------------------------------+
| :app  (Compose UI, 5 tabs + routes)        |
|   ViewModels (Hilt @HiltViewModel)         |
|   31 nav destinations + 41 ViewModels      |
+------------+-------------------------------+
             | depends on
+------------v-------------------------------+
| :aura-core  (logic library, no Compose)    |
|   MemoryAugmentedAgenticLoop -> Brain      |
|   ToolRegistry (81) -> ToolExecutor        |
|     -> PolicyEngine (typed gate            |
|        pause/resume for permission /       |
|        confirmation / cost approval)       |
|   ProviderRegistry (17 providers)          |
|   Memory (Room + RRF + FadeMem + WriteGate)|
|   KnowledgeGraph (Room, 11+18 types)       |
|   Hands + Tasks + Reminders (Room)         |
|   Proactive (Brief + Decay + CalendarCheck |
|     + Daemon + EmotionEngine)              |
|   Creative (Engine + Council + Pipelines)  |
|   Evolution (PatchAuthor + Validator       |
|     + ApplySaga + Rollback + Outcome)      |
|   AgentRun (DAG + Checkpoint + Resume)     |
|   MCP Client (JSON-RPC + tool bridge)      |
|   Capabilities (Exa/Jina/Stability/Kling   |
|     /WorldLabs/ElevenLabs)                 |
|   SecureDataStore for API keys             |
+--------------------------------------------+
```

The `:aura-core` module has no Compose dependencies. If you ever port to iOS via KMP, this is the layer you'd reuse.

### Bottom nav
- **Home** — greeting, quick actions, model status, cards for Skills, Creative, Tasks, Hands, Production, Beliefs, Evolution.
- **Chat** — streaming assistant with text/voice input, model picker, specialist chips, tool-call badges, citation chips, follow-up suggestions, markdown rendering, code blocks, tables.
- **Memory** — browse, search, edit, merge, bulk-clear, and rebuild embeddings.
- **Settings** — providers, model defaults, planning toggle, appearance, persona, app lock, proactive toggles, tool policies, MCP servers, evolution, diagnostics, profile, emotional state, daemon config.

### Secondary routes
- **History** — long-press to rename/pin; tap to resume; multi-select + swipe-to-delete.
- **Hands** — create, edit, run, and delete user-defined automation macros.
- **Tasks** — create, edit, complete, delete, filter by status, clear completed.
- **Reminders** — schedule and cancel notification reminders.
- **Proactive history** — review morning briefs, calendar events, memory decay warnings, daemon thoughts.
- **Skills** — browse installed skills and dispatch skill-backed tool calls.
- **Creative** — manage creative writing projects, world bibles, and simulations.
- **Creative Project** — world bible editor, artifact revisions, branch management.
- **Production** — production pipeline status and stage tracking.
- **Agent Runs** — durable run history with checkpoint/resume.
- **Beliefs** — world model beliefs with evidence.
- **Evolution Inbox** — review self-improvement proposals, approve/reject.
- **Evolution Rollback** — revert applied evolution changes.
- **Knowledge Graph** — browse extracted entities and edges.
- **Tools** — browse all 81 registered tools, grouped by category and searchable by name or description. No risk column: `ToolsScreen` renders `ToolRegistry.definitions()`, and `ToolDefinition` carries name, description, parameters and category only — `Tool.risk` never reaches the UI. The Risk column in the catalog below is read from the Kotlin source.
- **Diagnostics** — provider health, model catalog, usage tracking.
- **Profile** — view/edit user profile (name, traits, facts).
- **Identity Editor** — customize Aura's persona.

## Tool catalog (81 built-in)

All 81 registered tools (plus any MCP-discovered ones) are browsable in-app on the Tools screen, grouped by category. The Risk column below is the `Tool.risk` value in Kotlin, not something the screen displays. The tables cover the full built-in set — they previously came to 76 rows and called themselves complete, having omitted the two highest-risk tools in the app.

### Web & research
| Tool | What it does | Risk |
|---|---|---|
| `web_search` | Web search — dispatches to Tavily or Brave when a key is set, else DuckDuckGo | READ_ONLY |
| `brave_search` | Brave Search API (needs key). Registered but **not offered to the model** — `web_search` routes to it | READ_ONLY |
| `tavily_search` | Tavily Search API (needs key, key in header). Registered but **not offered to the model** — `web_search` routes to it | READ_ONLY |
| `ddg_instant_answer` | DuckDuckGo Instant Answer API (free) | READ_ONLY |
| `searxng_search` | SearXNG metasearch (self-hosted instance) | READ_ONLY |
| `wikipedia_search` | Wikipedia article search (free) | READ_ONLY |
| `wikipedia_read` | Read a Wikipedia article (free) | READ_ONLY |
| `web_search_capability` | Capability-routed search (Exa if configured) | READ_ONLY |
| `fetch_url` | Firecrawl fetch (needs key, SSRF-guarded) | READ_ONLY |
| `read_url` | Jina Reader page-to-markdown (free tier) | READ_ONLY |
| `http_file_read` | HTTP GET with DNS-pinned client | READ_ONLY |
| `http_file_write` | HTTP PUT/POST with DNS-pinned client | WRITE_REMOTE |
| `deep_research` | Multi-source synthesis with citations | REMOTE_COST |
| `parallel_research` | Parallel multi-query research fan-out | REMOTE_COST |
| `vision` | Describe an image (Gemini, key in header) | REMOTE_COST |
| `image_gen` | Generate an image (configurable model) | WRITE_REMOTE |
| `image_generate` | Capability-routed image gen (Stability) | WRITE_REMOTE |
| `transcribe` | Audio -> text | REMOTE_COST |
| `translate` | Translate text between languages | REMOTE_COST |
| `weather` | Weather via Open-Meteo (free, no key) | READ_ONLY |
| `timer` | In-memory countdown timer | WRITE_LOCAL |

### Knowledge & memory
| Tool | What it does | Risk |
|---|---|---|
| `remember` | Store a memory (heuristic + LLM WriteGate) | WRITE_LOCAL |
| `recall` | Retrieve memories (BM25 + RRF + LLM relevance rescoring) | READ_ONLY |
| `query_world_model` | Query beliefs, events, opportunities | READ_ONLY |
| `canon_query` | Ask questions about a creative project's canon | READ_ONLY |
| `living_world_query` | Mine a living world for drama, or diff two timeline branches | READ_ONLY |
| `knowledge_graph_extract` | Add knowledge graph nodes/edges | WRITE_LOCAL |
| `kg_query` | Query the knowledge graph | READ_ONLY |
| `code_interpreter` | Execute JavaScript in a sandboxed WebView | REMOTE_COST |
| `query_taste` | Query taste profile + routing outcomes | READ_ONLY |
| `index_document` | Import + chunk a document into `document_chunks` and `memories` | WRITE_LOCAL |
| `search_documents` | Search imported documents, returning passages with citations | READ_ONLY |
| `run_hand` | Execute a named hand (automation macro) | WRITE_LOCAL |
| `use_skill` | Dispatch a skill-backed tool call | WRITE_LOCAL |

### Creative
| Tool | What it does | Risk |
|---|---|---|
| `creative_engine` | 6-mode creative writing engine | REMOTE_COST |
| `creative_read_project` | Read creative project state | READ_ONLY |
| `creative_add_world_item` | Add to world bible | WRITE_LOCAL |

### Phone-native
| Tool | What it does | Risk |
|---|---|---|
| `get_current_time` | Local time helper | READ_ONLY |
| `launch_app` | Open app or URL | WRITE_LOCAL |
| `open_browser_tab` | Open URL in browser | WRITE_LOCAL |
| `system_volume` | Get/set audio streams | WRITE_LOCAL |
| `battery_state` | Battery level + charging | READ_ONLY |
| `network_state` | Connection type | READ_ONLY |
| `dnd_mode` | Do-Not-Disturb | WRITE_LOCAL |
| `set_reminder` | Schedule a notification reminder | WRITE_LOCAL |
| `manage_tasks` | Create/list/complete/delete tasks | WRITE_LOCAL |
| `schedule_task` | Schedule a future task/reminder (notify or start a chat, optional recurrence) | WRITE_LOCAL |
| `post_notification` | System notification | WRITE_LOCAL |
| `notification_list` | Read active notifications | PRIVACY |
| `location_now` | Last-known GPS | PRIVACY |
| `calendar_read` | Next N days of events | PRIVACY |
| `calendar_write` | Create event (two-phase confirmation) | PRIVACY |
| `contacts_search` | Find contact by name | PRIVACY |
| `photo_library` | List recent photos | PRIVACY |
| `capture_screen` | Screenshot. MediaProjection FGS with fresh consent per capture by default; a silent accessibility screenshot with no dialog when the screen-control master switch is on | PRIVACY |
| `clipboard_read` | Read clipboard | READ_ONLY |
| `clipboard_write` | Write clipboard | WRITE_LOCAL |
| `share` | Android share sheet | WRITE_LOCAL |
| `biometric_prompt` | Face/fingerprint gate (BIOMETRIC_STRONG) | WRITE_LOCAL |

### Screen control

Both are registered unconditionally so the in-chat enable flow can fire, and both
are hidden from the model entirely while the screen-control master switch is off
(`filterUnavailableTools` in `MemoryAugmentedAgenticLoop`). They were absent from
this catalog for as long as it described itself as complete, which left the only
DESTRUCTIVE tool in the app undocumented.

| Tool | What it does | Risk |
|---|---|---|
| `screen_read` | Read the foreground app's accessibility tree as a numbered element list (actionable / text / full) | PRIVACY |
| `screen_act` | Tap, long-press, type, clear, scroll, swipe, back/home/recents in the foreground app | DESTRUCTIVE |

### Communication
| Tool | What it does | Risk |
|---|---|---|
| `email_send` | Send email via SMTP (credentials in SecureDataStore) | WRITE_REMOTE |
| `send_email_background` | Background email send (cc/bcc supported) | WRITE_REMOTE |
| `sms_send` | Send SMS | WRITE_REMOTE |
| `tts_speak` | Android TextToSpeech | WRITE_LOCAL |

### Integrations (Google + Microsoft)
| Tool | What it does | Risk |
|---|---|---|
| `gmail` | List, read, search, send via Gmail API | REMOTE_COST |
| `google_calendar` | List, create, delete via Google Calendar API | REMOTE_COST |
| `google_drive` | List, search files via Google Drive API | REMOTE_COST |
| `outlook_mail` | List, read, search, send via Microsoft Graph | REMOTE_COST |
| `outlook_calendar` | List, create via Microsoft Graph | REMOTE_COST |
| `onedrive` | List, search files via Microsoft Graph | REMOTE_COST |

### Media generation (capability-backed)
| Tool | What it does | Risk |
|---|---|---|
| `text_to_speech` | ElevenLabs TTS (needs key) | REMOTE_COST |
| `video_generate` | Kling video generation (needs key) | WRITE_REMOTE |
| `world_3d_generate` | WorldLabs 3D generation (needs key) | WRITE_REMOTE |

### Evolution
| Tool | What it does | Risk |
|---|---|---|
| `trigger_evolution_run` | Trigger a self-improvement cycle | WRITE_LOCAL |
| `approve_evolution_proposal` | Approve an evolution proposal | WRITE_LOCAL |
| `rollback_evolution_change` | Rollback an applied evolution | WRITE_LOCAL |

### Agents
| Tool | What it does | Risk |
|---|---|---|
| `delegate_to_agent` | Hand a task to another agent and return its result | REMOTE_COST |
| `run_council` | Run the multi-role council review over a draft | REMOTE_COST |
| `run_life_council` | Run the life-advice council debate | REMOTE_COST |

## Providers (17 prefixes)

The model picker routes by `prefix:model` string. Keys are stored locally via SecureDataStore and read on every chat call, so Settings changes take effect immediately.

| Prefix | Display name | Notes |
|---|---|---|
| `ollama` | Ollama Cloud | OpenAI-compatible, free tier available |
| `anthropic` | Anthropic | Native Messages API + tool use |
| `openai` | OpenAI | OpenAI-compatible |
| `deepseek` | DeepSeek | OpenAI-compatible |
| `gemini` | Google Gemini | Native streamGenerateContent |
| `groq` | Groq | OpenAI-compatible, fast inference |
| `openrouter` | OpenRouter | OpenAI-compatible, multi-model gateway |
| `moa` | Mixture of Agents | Virtual — runs 2 references in parallel, synthesizes via aggregator |
| `mistral` | Mistral | OpenAI-compatible |
| `xai` | xAI Grok | OpenAI-compatible |
| `together` | Together AI | OpenAI-compatible |
| `cerebras` | Cerebras | OpenAI-compatible, fast inference |
| `nvidia` | NVIDIA NIM | OpenAI-compatible |
| `llama` | Meta Llama | OpenAI-compatible |
| `agnes` | Agnes AI | OpenAI-compatible |
| `chatgpt` | ChatGPT | Subscription-based OAuth |
| `custom` | Custom | Any OpenAI-compatible endpoint |

MoA ships with no built-in presets (`moa_presets.json` is empty). Configure a custom mixture in Settings → AI & Models by picking 2+ reference models and an aggregator; the picker then exposes `moa:custom`. See `MoaPresetRepository`.

## Specialists (7)

Each has a system prompt and an allowed tool set, and `AgentStore` seeds the seven builtin agents from `Specialist.ALL`.

**Selected by the user, not automatically.** This line used to read "keyword-routed (see `SpecialistRouter`)". `SpecialistRouter` existed, had 167 lines and a full test suite, and had **no production caller** — nothing anywhere selected a specialist from the text of a message. It was superseded by the agent picker and has been deleted; the presets it chose between are live and always were.

The allowlist is **prompt filtering, not execution enforcement**. `MemoryAugmentedAgenticLoop` narrows `ToolRegistry.definitions()` to `Specialist.toolsAllowed` before the request goes out, and resolves MCP base names so an MCP server cannot smuggle a tool past it — so the model is never *offered* a tool outside the set. Nothing checks the set again at dispatch: `ToolExecutor.execute` is never told which specialist is active, so a name the model produces anyway — replayed from tool history, hallucinated, or injected through retrieved context — still runs. The one place a call is refused for being off-allowlist is `DelegateToAgentTool.kt` (lines 240-253), which compares the requested name against the child agent's tool list and writes a synthetic "not in your allowed tool set" result instead of dispatching.

Specialist system prompts are user-customizable via Settings.

- **general** — fallback, all tools
- **coder** — web search + fetch_url
- **researcher** — deep_research + web search + fetch_url
- **writer** — creative_read_project, creative_add_world_item, recall (fiction, scripts, world-building)
- **creative** — image generation and visual ideation
- **executive** — calendar + contacts + remember/recall
- **phone_native** — photo library, location, reminders, app launch, notification read/post, battery, network, volume, DND (no camera tool exists)

## Proactive workers

Scheduled via WorkManager. Re-scheduled on app start (idempotent, UPDATE policy). All toggleable via Settings.

- `MorningBriefWorker` — daily at user-configured time (default 7am). Pulls last 10 memories, asks the configured provider for a 3-5 line brief, posts as a notification.
- `DecayWorker` — every 6h. Runs a memory decay pass via `MemoryStore.runDecayPass`.
- `CalendarCheckWorker` — every 15 min. Queries the Calendar Instances API with a 30-min lookahead (recurring events expand correctly), dedups announced instances persistently, records events via `ProactiveEvents` so delivery survives background wakes. Replaced the old permanent `CalendarMonitorService` foreground service.
- `DaemonWorker` — configurable interval (default 60 min, options down to the 15-min WorkManager floor), constrained to network-connected + battery-not-low. Reviews recent conversation; if the background model generates something substantive, posts as a proactive event. Respects `daemonEnabled`; council debates are a separate opt-in (`councilEnabled`, default off).
- `ReminderWorker` — fires per `set_reminder` request.
- `EvolutionWorker` — runs self-improvement cycle when triggered. Respects `evolutionEnabled` preference.

## Room databases (11)

| Database | Version | Contents |
|---|---|---|
| MemoryDatabase | v29 | Memories, memory edits, document chunks, creative artifacts/revisions/branches/jobs, canon facts/simulations/continuity, beliefs/evidence/events/opportunities, preference signals/style profiles/reference identities/routing outcomes, FTS4 index over memory content, coarse place visits, creative analysis per revision |
| ConversationDatabase | v6 | Conversations with embeddings for semantic search |
| ProactiveEventDatabase | v7 | Proactive events with structured payload |
| TaskDatabase | v6 | Tasks + reminders |
| EvolutionDatabase | v4 | Candidates, proposals, evidence, outcomes (dedup index on domain/action/target) |
| DreamConsolidationDatabase | v3 | Dream summaries, routines, contradictions, KG edge proposals |
| AgentDatabase | v3 | Agent definitions and personality profiles |
| HandDatabase | v2 | User-defined automation macros |
| UserProfileDatabase | v2 | User profile (name, traits, facts) |
| AgentRunDatabase | v1 | Agent runs, goals, steps, events, approvals, checkpoints |
| StrategyBanditDatabase | v1 | Strategy bandit weights (Thompson Sampling Beta distributions) |

All databases have schema export enabled (`room.schemaLocation`). MemoryDatabase
schema exports span versions 1-29, all committed — migration tests cover
the full chain.

Eleven separate databases means no cross-database transactions or joins,
eleven independent migration chains, and a backup path that has to
coordinate eleven schemas — which is why `BackupManager.kt` is one of the
larger files here. It is not the largest: `MemoryAugmentedAgenticLoop.kt`,
`MemoryScreen.kt`, `SettingsViewModel.kt`, `ChatViewModel.kt` and
`DreamConsolidator.kt` are all bigger. The "largest file in the project"
phrasing stood for several releases and made the eleven-database split look
better-evidenced than it is. The split is a real cost; it is just not the
cost of producing the biggest file.

## Build

```bash
./gradlew :app:assembleDebug            # debug APK
./gradlew :app:assembleRelease          # release APK — R8-minified + resource-shrunk
./gradlew :aura-core:testDebugUnitTest  # unit tests (aura-core)
./gradlew :app:testDebugUnitTest        # unit tests (app)
./gradlew :app:assembleDebug connectedAndroidTest  # androidTests (needs device)
```

Release builds are R8-minified with targeted keep rules and signed with a real
upload keystore when `AURA_KEYSTORE_PATH` / `AURA_KEYSTORE_PASSWORD` /
`AURA_KEY_ALIAS` / `AURA_KEY_PASSWORD` are set in `local.properties` (gitignored)
or the environment. Without those values the build falls back to the debug key
with a loud warning — fine for local R8 verification, not for distribution.

CI (`.github/workflows/ci.yml`) runs on every branch (`branches: ['**']`) and every
pull request, in three jobs. `gates` is cheap and has no JDK or Gradle: it runs the
logging-hygiene lint and `scripts/check-version-docs.sh`, which derives every count
in this file and in architecture.md from source and fails when the prose disagrees.
`build-test` builds and unit-tests both modules, compiles the instrumented tests
(no device in CI, so they are compiled and not run), and runs
`scripts/check-test-count.sh` against the JUnit XML. `lint-release` runs lint on
both modules and `assembleRelease` for real R8 coverage.

The trigger used to be `[main, feat/tier-1-friction]` while development ran on
another branch, so 46 commits were gated by nothing at all.

## Project layout

```
aura-android/
├── app/                  # :app module — Compose UI, ViewModels, NavGraph
│   └── src/main/kotlin/com/aura/
│       ├── ui/           # screens, components, theme, viewmodel, voice, settings, nav
│       ├── di/           # Hilt module (AppModule)
│       ├── documents/    # Document text extractor
│       ├── notifications/# Notification helpers
│       ├── widget/       # Quick Ask widget + config activity
│       ├── MainActivity, AuraApp, ShareReceiverActivity, FirstRunGate
├── aura-core/            # :aura-core library — all logic (no Compose deps)
│   └── src/main/kotlin/com/aura/
│       ├── agent/        # Brain, MemoryAugmentedAgenticLoop, Conversation, ToolRegistry, ToolExecutor, Specialist, PolicyEngine, ToolPolicy, TraceSink
│       ├── agents/       # SubagentManager, SubagentContracts
│       ├── agentrun/     # AgentRunDatabase, DagResolver, AgentRunExecutorWorker, AgentRunStore
│       ├── providers/    # 17 providers + MoA + ProviderKeys + ProviderRegistry + ModelRoleRouter + ModelCatalogRepository
│       ├── memory/       # Room + vector + RRF retrieval + FadeMem + WriteGate + LlmWriteGate + Embedder (cloud + local)
│       ├── kg/           # Knowledge graph (Room + extractor + repository)
│       ├── hands/        # Automation macros (Room + repository + worker)
│       ├── tasks/        # Task manager (Room)
│       ├── tools/        # 81 tool implementations + ToolsModule
│       ├── voice/        # SpeechToText + TextToSpeech
│       ├── proactive/    # MorningBrief + Decay + CalendarCheckWorker + DaemonWorker + ProactiveEvents + ProactiveScheduler
│       ├── emotion/      # EmotionEngine (4-dimension state) + ResponseProfile
│       ├── profile/      # UserProfile (learned from conversations)
│       ├── evolution/    # Coordinator + PatchAuthor + PatchValidator + ApplySaga + RollbackManager + SafetyGuard + OutcomeScorer (4 EvolutionActions)
│       ├── creative/     # CreativeEngine + CreativeCouncil (10 roles) + ProductionPipelineEngine + WorldBible + ProseCraftTools + VoiceCalibration + TensionAnalyzer + CharacterProgressionTracker + SmartCodexInjector + GenreCraftPrompts + stores
│       ├── pipeline/     # ProductionPipeline (6 pipeline types)
│       ├── capabilities/ # CapabilityRouter + Exa/Jina/Stability/Kling/WorldLabs/ElevenLabs providers
│       ├── mcp/          # MCP client (JSON-RPC, tool bridge, server persistence)
│       ├── world/        # World model DAOs + entities (beliefs, events, opportunities)
│       ├── search/       # GlobalSearchRepository (6-source parallel search)
│       ├── skills/       # Skill definitions + SkillsStore
│       ├── documents/    # Document chunking + repository
│       ├── security/     # SecureDataStore, KeyManager, BiometricActivityHolder, ScreenCaptureHolder + ScreenCaptureService
│       ├── integrations/ # Google Workspace + Microsoft Graph (OAuth, token store, tools)
│       ├── backup/       # BackupManager + AuraBackup
│       ├── data/         # RoomConfig (centralized DB config) + UserPreferences
│       └── usage/        # UsageTracker
└── docs/                 # architecture.md, design, ANDROID_TEST_PLAN.md
```

## Tech stack

- Kotlin 2.4.10 (K2 compiler), Gradle 9.7, AGP 9.3.1, KSP 2.3.11, JVM target 17
- Jetpack Compose (BOM 2026.06.01) with the Compose compiler Gradle plugin, Material 3, Navigation Compose
- Hilt 2.60.1 (DI) + Hilt Work 1.4.0 (for WorkManager injection)
- Room 2.8.4 (11 databases, 68 entities, 53 migrations, schema export)
- WorkManager 2.11.2 (proactive workers, agent run executor, reminders)
- OkHttp 4.12.0 + okhttp-sse (streaming LLM responses, DNS-pinned clients)
- kotlinx-serialization 1.11.0, kotlinx-coroutines 1.11.0
- DataStore Preferences 1.1.1, Biometric 1.2.0-alpha05
- PDFBox Android (document text extraction)
- javax.mail (SMTP email sending)
- Testing: JUnit 4, MockK, Turbine, Robolectric 4.16.1, kotlinx-coroutines-test, OkHttp MockWebServer
- minSdk 26 (Android 8.0), targetSdk 35, compileSdk 37
- Release: R8 minification + resource shrinking with targeted keep rules; upload-keystore signing via `local.properties`

## Releases

`releases/` holds local build artifacts and the release notes. Only the `RELEASE_NOTES_*.md`
files are tracked — APKs are gitignored (~36 MB each; they would bloat history permanently).
The directory had accumulated 69 of them, 2.5 GB, going back to v0.26.0. It now keeps the
current build and the two before it; prune it again when it grows.

## Changelog

`git log --oneline` is the changelog. 750+ commits across the full development history.

## Engineering history

[ENGINEERING_HISTORY.md](ENGINEERING_HISTORY.md) consolidates the review and audit
trail: what was found and fixed, what is still open (§3), and what the review cadence
itself cost (§4). It replaces 29 separate dated report files, all recoverable from git
history.