# Aura Android

![CI](https://github.com/ElnurIbrahimov/aura-android/actions/workflows/ci.yml/badge.svg)

A native Android superapp — a full port of the Python [Aura](https://github.com/ElnurIbrahimov/apprentice-agent) assistant to Kotlin/Compose. Single-user, offline-first storage, all brains in the cloud.

This is my personal copy.

## Status

**v0.46.0** (versionCode 55).

- 69 tools (web search x4, vision, image gen x2, deep research, firecrawl fetch, knowledge graph, weather, translate, timer, code interpreter, SMS, email, biometric prompt, phone-native tools, reminders, skills, creative studio, MCP tools, evolution, world model, taste, document indexing, canon query, media generation, agent delegation, agent council, schedule task, run council, gmail, google calendar, google drive, outlook mail, outlook calendar, onedrive)
- Creative Studio (Room-backed projects, world bible, simulations, drafts, continuity, 6 creative-engine modes)
- Creative Council (10-role multi-agent review: Director, Writer, Story Editor, Continuity Editor, World Simulator, Researcher, Art Director, Cinematographer, Sound Designer, Audience Critic)
- Production Pipelines (novel, screenplay, short film, trailer, podcast drama, RPG campaign)
- Skills (installable skill cards, skill-backed tool dispatch)
- Memory stack (Room + 384-dim cloud embeddings + 6-signal RRF retrieval + 14-day FadeMem with access-frequency decay + heuristic + LLM WriteGate)
- Knowledge graph (Room-backed, 11 node types, 18 edge types, LLM-extracted per turn)
- Hands (user-defined automation macros, persisted, triggerable by phrase)
- Tasks + Reminders (Room-backed, manageable in-app and via tool)
- Agentic loop (ReAct-style, 10 steps max, streams text + tool calls, abort-safe, parallel tool execution, 4k char tool-result truncation budget)
- Optional pre-answer planning pass (off by default — costs an extra model call per message; enable in Settings → AI & Models for tool-heavy work)
- 17 LLM providers (Ollama Cloud, Anthropic, OpenAI, DeepSeek, Gemini, Groq, OpenRouter, Mixture-of-Agents, Mistral, xAI Grok, Together AI, Cerebras, NVIDIA NIM, Meta Llama, Agnes AI, ChatGPT subscription, Custom OpenAI-compatible endpoint)
- 7 specialists (general, coder, researcher, writer, creative, executive, phone-native) with keyword router + tool-allowlist enforcement
- 4-tab bottom nav (Home, Chat, Memory, Settings) + 21 secondary routes (History, Hands, Tasks, Reminders, Proactive, Skills, Creative, Creative Project, Production, Agent Runs, Beliefs, Evolution Inbox, Evolution Rollback, Diagnostics, Knowledge Graph, Profile, Identity Editor, Tools, Search, Onboarding)
- Voice I/O (push-to-talk STT via Android SpeechRecognizer, auto-TTS via Android TextToSpeech, continuous voice mode)
- Proactive: WorkManager daily morning brief (customizable time) + 6h memory decay + 5-min calendar monitor (foreground service) + daemon thinking worker (every ~15 min, background model)
- Emotional state engine (4 dimensions: tension, connection, energy, focus — with inertia, decay, and heuristic signal detection)
- Adaptive response profiles (tone adapts based on emotional state)
- Share receiver (`text/plain` + `image/*` from Android share sheet)
- User profile (learned from conversations via regex, editable in Settings)
- Specialist and persona identity customization (Settings)
- Onboarding wizard (paste API key + verify connectivity)
- Biometric gate for sensitive tools and app lock (BIOMETRIC_STRONG)
- MCP client (connect external tool servers, auto-registers discovered tools into ToolRegistry, persists server configs, auth token support via SecureDataStore)
- Evolution system (self-improvement proposals, 19 EvolutionAction handlers, approve/reject from inbox, apply saga, rollback manager, safety guard, shadow evaluator)
- Agent runs (durable, resumable, DAG-resolved step execution via WorkManager, checkpoint/resume, approval flow)
- World model (beliefs, evidence, events, opportunities in separate Room tables, surfaced in system prompt)
- Taste engine (preference signal recording, style profiling, model routing)
- Capability router (Exa search, Jina reader, Stability image, Kling video, WorldLabs 3D, ElevenLabs TTS — each requires its own API key)
- Tool policy engine (layered precedence: built-in risk -> incognito gate -> user policy -> per-run approval, configurable per tool)
- Agent trace + observability (20 event types via TraceSink)
- Document indexing (PDF/text import, chunking, embedding, retrieval)
- Global search (conversations, memories, tasks, hands, skills, knowledge graph in one query)
- Backup/restore (JSON export/import, SecureDataStore for credentials, schema v14)
- 299 unit test files, 1,559 tests, 0 failures
- 12 connected-device tests passing (10 Room migrations + 2 app smoke tests)
- 6 daily-use UX round-1 fixes (regenerate, edit-resend, share, export, clear, code copy, friendly errors, draft persistence)
- 4 daily-use UX round-2 fixes (offline indicator, image paste, TTS state mirror + stop pill, response duration footer)
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
- **Foreground service** — for the calendar monitor
- **Biometric** — for the biometric gate tool
- **Boot completed** — to reschedule proactive workers after reboot

## Architecture (TL;DR)

```
+--------------------------------------------+
| :app  (Compose UI, 4 tabs + 20 routes)     |
|   ViewModels (Hilt @HiltViewModel)         |
|   22 screens + 27 ViewModels               |
+------------+-------------------------------+
             | depends on
+------------v-------------------------------+
| :aura-core  (logic library, no Compose)    |
|   MemoryAugmentedAgenticLoop -> Brain      |
|   ToolRegistry (61) -> ToolExecutor        |
|     -> PolicyEngine (layered precedence)   |
|   ProviderRegistry (17 providers)          |
|   Memory (Room + RRF + FadeMem + WriteGate)|
|   KnowledgeGraph (Room, 11+18 types)       |
|   Hands + Tasks + Reminders (Room)         |
|   Proactive (Brief + Decay + Calendar      |
|     + Daemon + EmotionEngine)              |
|   Creative (Engine + Council + Pipelines)  |
|   Evolution (Proposals + ApplySaga         |
|     + Rollback + ShadowEval)               |
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
- **Tools** — browse all 63 registered tools with risk levels.
- **Diagnostics** — provider health, model catalog, usage tracking.
- **Profile** — view/edit user profile (name, traits, facts).
- **Identity Editor** — customize Aura's persona.

## Tool catalog (61)

### Web & research
| Tool | What it does | Risk |
|---|---|---|
| `web_search` | DuckDuckGo HTML search (free, no key) | READ_ONLY |
| `brave_search` | Brave Search API (needs key) | READ_ONLY |
| `tavily_search` | Tavily Search API (needs key, key in header) | READ_ONLY |
| `web_search_capability` | Capability-routed search (Exa if configured) | READ_ONLY |
| `fetch_url` | Firecrawl fetch (needs key, SSRF-guarded) | READ_ONLY |
| `http_file_read` | HTTP GET with DNS-pinned client | READ_ONLY |
| `http_file_write` | HTTP PUT/POST with DNS-pinned client | WRITE_REMOTE |
| `deep_research` | Multi-source synthesis with citations | REMOTE_COST |
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
| `remember` | Store a fact in memory (WriteGate-gated) | WRITE_LOCAL |
| `recall` | Search memory by semantic + text query | READ_ONLY |
| `knowledge_graph_extract` | Extract entities/edges from text | WRITE_LOCAL |
| `kg_query` | Query the knowledge graph | READ_ONLY |
| `query_world_model` | Query beliefs/events/opportunities | READ_ONLY |
| `query_taste` | Query taste profile + routing outcomes | READ_ONLY |
| `canon_query` | Query creative project canon facts | READ_ONLY |
| `index_document` | Import + chunk + embed a document | WRITE_LOCAL |
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
| `post_notification` | System notification | WRITE_LOCAL |
| `notification_list` | Read active notifications | PRIVACY |
| `location_now` | Last-known GPS | PRIVACY |
| `calendar_read` | Next N days of events | PRIVACY |
| `calendar_write` | Create event (two-phase confirmation) | PRIVACY |
| `contacts_search` | Find contact by name | PRIVACY |
| `photo_library` | List recent photos | PRIVACY |
| `capture_screen` | Screenshot via MediaProjection | PRIVACY |
| `clipboard_read` | Read clipboard | READ_ONLY |
| `clipboard_write` | Write clipboard | WRITE_LOCAL |
| `share` | Android share sheet | WRITE_LOCAL |
| `biometric_prompt` | Face/fingerprint gate (BIOMETRIC_STRONG) | WRITE_LOCAL |

### Communication
| Tool | What it does | Risk |
|---|---|---|
| `email_send` | Send email via SMTP (credentials in SecureDataStore) | WRITE_REMOTE |
| `send_email_background` | Background email send (cc/bcc supported) | WRITE_REMOTE |
| `sms_send` | Send SMS | WRITE_REMOTE |
| `tts_speak` | Android TextToSpeech | WRITE_LOCAL |

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

`moa:default` uses `glm-5.2` + `kimi-k2.7-code` as references and `deepseek-v4-pro` as aggregator. Presets are loaded from app assets; see `MoaPresetRepository`.

## Specialists (7)

Keyword-routed (see `SpecialistRouter`). Each has a system prompt and allowed tool set. Tool allowlists are enforced in the loop — a specialist cannot call a tool not in its allowlist. Specialist system prompts are user-customizable via Settings.

- **general** — fallback, all tools
- **coder** — brave/tavily/web search + fetch_url
- **researcher** — deep_research + brave/tavily/web search
- **writer** — creative_read_project, creative_add_world_item, recall (fiction, scripts, world-building)
- **creative** — image generation and visual ideation
- **executive** — calendar + contacts + remember/recall
- **phone_native** — all device-state + camera + location tools

## Proactive workers

Scheduled via WorkManager. Re-scheduled on app start (idempotent, UPDATE policy). All toggleable via Settings.

- `MorningBriefWorker` — daily at user-configured time (default 7am). Pulls last 10 memories, asks the configured provider for a 3-5 line brief, posts as a notification.
- `DecayWorker` — every 6h. Runs a memory decay pass via `MemoryStore.runDecayPass`.
- `CalendarMonitorService` — 5-min foreground service. Polls upcoming events, surfaces to `ProactiveEventBus`.
- `DaemonWorker` — every 8 min. Reviews recent conversation, if the background model generates something substantive, posts as a proactive event. Respects `daemonEnabled` preference.
- `ReminderWorker` — fires per `set_reminder` request.
- `EvolutionWorker` — runs self-improvement cycle when triggered. Respects `evolutionEnabled` preference.

## Room databases (10)

| Database | Version | Contents |
|---|---|---|
| MemoryDatabase | v14 | Memories, memory edits, document chunks, creative artifacts/revisions/branches/jobs, canon facts/simulations/continuity, beliefs/evidence/events/opportunities, preference signals/style profiles/reference identities/routing outcomes |
| ConversationDatabase | v6 | Conversations with embeddings for semantic search |
| ProactiveEventDatabase | v5 | Proactive events with structured payload |
| TaskDatabase | v4 | Tasks + reminders |
| EvolutionDatabase | v3 | Proposals, skill revisions, metrics |
| DreamConsolidationDatabase | v2 | Dream summaries, routines, contradictions, KG edge proposals |
| HandDatabase | v2 | User-defined automation macros |
| UserProfileDatabase | v2 | User profile (name, traits, facts) |
| AgentDatabase | v1 | Agent definitions and personality profiles |
| AgentRunDatabase | v1 | Agent runs, goals, steps, events, approvals, checkpoints |

All databases have schema export enabled (`room.schemaLocation`).

Known gap: `MemoryDatabase` schema exports jump from `6.json` to `11.json` — versions 7
through 10 were never committed, so migration tests cannot verify that range. See
[ENGINEERING_HISTORY.md](ENGINEERING_HISTORY.md) §3.

Ten separate databases means no cross-database transactions or joins, ten independent
migration chains, and a backup path that has to coordinate ten schemas. That is the main
reason `BackupManager.kt` is the largest file in the project.

## Build

```bash
./gradlew :app:assembleDebug            # debug APK
./gradlew :app:assembleRelease          # release APK (currently signed with debug key — sideload only)
./gradlew :aura-core:testDebugUnitTest  # unit tests (aura-core)
./gradlew :app:testDebugUnitTest        # unit tests (app)
./gradlew :app:assembleDebug connectedAndroidTest  # androidTests (needs device)
```

CI (`.github/workflows/ci.yml`) runs `assembleDebug` + unit tests + lint on every push and PR to `main` and `feat/tier-1-friction`.

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
│       ├── agent/        # Brain, MemoryAugmentedAgenticLoop, Conversation, ToolRegistry, ToolExecutor, Specialist, SpecialistRouter, PolicyEngine, ToolPolicy, TraceSink
│       ├── agents/       # SubagentManager, SubagentContracts
│       ├── agentrun/     # AgentRunDatabase, DagResolver, AgentRunExecutorWorker, AgentRunStore
│       ├── providers/    # 17 providers + MoA + ProviderKeys + ProviderRegistry + ModelRoleRouter + ModelCatalogRepository
│       ├── memory/       # Room + vector + RRF retrieval + FadeMem + WriteGate + LlmWriteGate + Embedder (cloud + local)
│       ├── kg/           # Knowledge graph (Room + extractor + repository)
│       ├── hands/        # Automation macros (Room + repository + worker)
│       ├── tasks/        # Task manager (Room)
│       ├── tools/        # 61 tool implementations + ToolsModule
│       ├── voice/        # SpeechToText + TextToSpeech
│       ├── proactive/    # MorningBrief + Decay + CalendarMonitor + DaemonWorker + ProactiveEventBus + ProactiveScheduler
│       ├── emotion/      # EmotionEngine (4-dimension state) + ResponseProfile
│       ├── profile/      # UserProfile (learned from conversations)
│       ├── evolution/    # EvolutionCoordinator + ApplySaga + RollbackManager + SafetyGuard + ShadowEvaluator + 19 EvolutionAction handlers
│       ├── creative/     # CreativeEngine + CreativeCouncil (10 roles) + ProductionPipelineEngine + WorldBible + stores
│       ├── pipeline/     # ProductionPipeline (6 pipeline types)
│       ├── capabilities/ # CapabilityRouter + Exa/Jina/Stability/Kling/WorldLabs/ElevenLabs providers
│       ├── mcp/          # MCP client (JSON-RPC, tool bridge, server persistence)
│       ├── world/        # World model DAOs + entities (beliefs, events, opportunities)
│       ├── search/       # GlobalSearchRepository (6-source parallel search)
│       ├── skills/       # Skill definitions + SkillsStore
│       ├── documents/    # Document chunking + repository
│       ├── security/     # SecureDataStore, KeyManager, BiometricActivityHolder, ScreenCaptureHolder
│       ├── backup/       | BackupManager + AuraBackup
│       ├── data/         # RoomConfig (centralized DB config) + UserPreferences
│       └── usage/        # UsageTracker
└── docs/                 # architecture.md, design, ANDROID_TEST_PLAN.md
```

## Tech stack

- Kotlin 1.9.24, AGP 8.2.2, JVM target 17
- Jetpack Compose (BOM 2024.10.01), Material 3, Navigation Compose
- Hilt 2.51 (DI) + Hilt Work (for WorkManager injection)
- Room 2.6.1 (10 databases, 48 entities, 30 migrations, schema export)
- WorkManager 2.9.1 (proactive workers, agent run executor, reminders)
- OkHttp 4.12.0 + okhttp-sse (streaming LLM responses, DNS-pinned clients)
- kotlinx-serialization 1.6.3, kotlinx-coroutines 1.9.0
- DataStore Preferences 1.1.1, Biometric 1.2.0-alpha05
- PDFBox Android (document text extraction)
- javax.mail (SMTP email sending)
- Testing: JUnit 4, MockK, Turbine, Robolectric, kotlinx-coroutines-test, OkHttp MockWebServer
- minSdk 26 (Android 8.0), targetSdk/compileSdk 35

## Changelog

`git log --oneline` is the changelog. 521 commits across the full development history.

## Engineering history

[ENGINEERING_HISTORY.md](ENGINEERING_HISTORY.md) consolidates the review and audit
trail: what was found and fixed, what is still open (§3), and what the review cadence
itself cost (§4). It replaces 29 separate dated report files, all recoverable from git
history.