# UI/UX Redesign Plan — Aura Android v0.53.0

> **For Hermes:** Execute this plan phase-by-phase. Each phase is independently shippable.

**Goal:** Transform Aura Android from "13 different apps stitched together" into a cohesive, premium, organized product. Every screen must feel like it belongs to the same app — same accent color, same spacing rhythm, same card shapes, same empty states, same top bar, same typography hierarchy.

**Root cause:** The design system EXISTS and is well-architected (AuraSemanticColors with 34 semantic tokens, AuraSpacing with 7-step scale, Inter+Fraunces+JetBrains Mono typography, AuraShapes with 6 radii, AuraScreenShell/AuraCard/AuraSectionHeader/AuraListRow/AuraEmptyState shared components). But adoption is ~15%:
- 50 MaterialTheme.colorScheme.* bypasses in app module (two color systems coexisting)
- 782 hardcoded dp values in screens (no spacing rhythm)
- 10 of 14 secondary screens use raw Scaffold+TopAppBar instead of AuraScreenShell
- AuraCard: 0 callers. AuraSectionHeader: 0 callers. AuraListRow: 0 callers.
- 13 identical Home cards in a flat LazyRow with no grouping or hierarchy
- 15 Settings sections in a flat expandable list with no grouping
- Accent color is M3 default purple (#7C3AED) — no brand identity

---

## Phase 1: Brand Identity + Color Unification (1 commit)

### Task 1: Switch accent from M3 purple to Aura teal, unify color systems

The app's primary accent is Material 3 default purple. Aura needs its own identity. Teal (#2DD4BF dark / #0D9488 light) is distinctive, reads as "AI/tech" without being generic, pairs well with the dark-first palette, and is accessible on both themes.

**Files:**
- `AuraSemanticColors.kt` — update 7 accent-related colors in both dark and light
- `Theme.kt` — DarkColors/LightColors are derived from semantic colors so they auto-update, but the hardcoded `error = Color(0xFFEF4444)` stays (it's intentionally saturated for button legibility)

**Dark theme changes:**
| Token | Current | New | Rationale |
|---|---|---|---|
| `actionPrimary` | `#7C3AED` (purple) | `#2DD4BF` (teal-400) | Primary brand accent |
| `onActionPrimary` | White | White | Stays — white on teal is accessible |
| `assistantAccent` | `#9A7AF8` (light purple) | `#2DD4BF` | AI avatar/brand accent |
| `borderFocus` | `#9A7AF8` | `#14B8A6` (teal-500) | Focus ring slightly deeper than accent |
| `aiThinking` | `#409A7AF8` | `#402DD4BF` | Thinking shimmer tint |
| `selection` | `#669A7AF8` | `#662DD4BF` | Text selection highlight |
| `userBubble` | `#F4F4F5` (white) | `#F4F4F5` | Stays — user bubbles are white in dark mode |

**Light theme changes:**
| Token | Current | New | Rationale |
|---|---|---|---|
| `actionPrimary` | `#6D28D9` (deep purple) | `#0D9488` (teal-600) | Darker teal for light theme contrast |
| `assistantAccent` | `#6D28D9` | `#0D9488` | |
| `borderFocus` | `#6D28D9` | `#0D9488` | |
| `userBubble` | `#6D28D9` | `#0D9488` | User bubbles are accent-colored in light mode |
| `aiThinking` | `#266D28D9` | `#260D9488` | |
| `selection` | `#336D28D9` | `#330D9488` | |

**MaterialTheme bypass ratchet:** Execute a Python script that finds all 50 `MaterialTheme.colorScheme.*` references in `app/src/main/kotlin/` and replaces them with the corresponding `AuraThemeTokens.colors.*` token:

| MaterialTheme.colorScheme.* | AuraThemeTokens.colors.* |
|---|---|
| `.surface` | `.surface1` |
| `.onSurface` | `.textPrimary` |
| `.onSurfaceVariant` | `.textSecondary` |
| `.primary` | `.actionPrimary` |
| `.onPrimary` | `.onActionPrimary` |
| `.error` | `.error` |
| `.outline` | `.borderSubtle` |
| `.background` | `.background` |
| `.surfaceVariant` | `.surface2` |

After replacement, verify with: `grep -rn "MaterialTheme.colorScheme" app/src/main/kotlin/ | grep -v test | wc -l` — should be 0.

**Verification:** `./gradlew :app:compileDebugKotlin` — compiles. `./gradlew :app:assembleDebug` — APK builds.

**Commit:** `feat(theme): switch accent to Aura teal + eliminate 50 MaterialTheme color bypasses`

---

## Phase 2: Screen Shell + Shared Component Adoption (2 commits)

### Task 2: Migrate 10 screens from raw Scaffold+TopAppBar to AuraScreenShell

10 screens (Council, CrashLog, Dreams, IdentityEditor, ProactiveHistory, Profile, Schedule, Skills, TasteProfile, WorldModel) each roll their own `Scaffold(topBar = { TopAppBar(...) })`. This means 10 different top bar heights, 10 different back-arrow styles, 10 different title positions.

**AuraScreenShell** already provides:
- Consistent header with title + subtitle + optional action slot
- `AuraScreenHeader` at `AuraDimensions.topAppBarHeight` (56dp) with `headlineMedium` typography
- `ResponsiveContainer` for max-width constraint
- `AuraThemeTokens.colors.background` base

**Changes per screen (mechanical):**

Replace:
```kotlin
Scaffold(
    topBar = {
        TopAppBar(
            title = { Text("World model") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(ARROW_BACK, ...) } },
        )
    },
) { padding ->
    LazyColumn(modifier = Modifier.padding(padding)) { ... }
}
```

With:
```kotlin
AuraScreenShell(
    title = "World model",
    subtitle = "Beliefs, events, and opportunities",
    action = { /* any custom actions like Clear/Share */ },
) { padding ->
    LazyColumn(modifier = Modifier.padding(padding)) { ... }
}
```

Remove unused imports: `Scaffold`, `TopAppBar`, `ArrowBack`, `ExperimentalMaterial3Api`.

For screens with custom actions (CrashLog has "Clear", TasteProfile has "Clear all" + "Recompute"):
```kotlin
AuraScreenShell(
    title = "Crash logs",
    subtitle = "Local crash and error history",
    action = { TextButton(onClick = onClear) { Text("Clear") } },
) { ... }
```

Add subtitles where missing:
- WorldModel: "Beliefs, events, and opportunities"
- TasteProfile: "Learned style preferences"
- Dreams: "Memory consolidation summaries"
- Skills: "Saved skill definitions"
- ProactiveHistory: "Proactive events and interactions"
- Profile: "User profile and facts"
- Schedule: "Triggers and scheduled actions"
- CrashLog: "Local crash and error history"
- Council: "Multi-agent council results"
- IdentityEditor: "Custom persona override"

**Commit:** `refactor(ui): migrate 10 screens to AuraScreenShell for consistent navigation`

### Task 3: Adopt AuraCard, AuraSectionHeader, AuraListRow, AuraEmptyState in all secondary screens

These shared components have 0 callers. Every secondary screen builds its own inline `Card { Column { Text(...) } }` with different padding, radius, and border. This task replaces all inline card/section/list patterns with the shared components.

**Files (7 screens):** WorldModelScreen, TasteProfileScreen, DreamsScreen, SkillsScreen, ProactiveHistoryScreen, AgentRunsScreen, DiagnosticsScreen

**Pattern replacements:**

Replace inline section headers:
```kotlin
// BEFORE (WorldModelScreen)
Text("Beliefs (${beliefs.size})", style = titleMedium, fontWeight = SemiBold)

// AFTER
AuraSectionHeader("Beliefs", subtitle = "${beliefs.size} active")
```

Replace inline cards:
```kotlin
// BEFORE (varies per screen)
Card(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(16.dp), ...) { ... }
}

// AFTER
AuraCard { ... }
```

Replace inline list rows:
```kotlin
// BEFORE (SkillsScreen, AgentRunsScreen)
Row(modifier = Modifier.fillMaxWidth().padding(...)) {
    Column { Text(title); Text(subtitle) }
    trailing?.invoke()
}

// AFTER
AuraListRow(
    title = title,
    subtitle = subtitle,
    leading = { Icon(icon, ...) },
    trailing = { TextButton(...) { Text("Open") } },
)
```

Replace inline empty states:
```kotlin
// BEFORE (varies)
Text("No beliefs yet.", color = onSurfaceVariant, ...)

// AFTER
AuraEmptyState(
    title = "No beliefs yet",
    message = "Beliefs are extracted from conversations and promoted from memories.",
    icon = Icons.Outlined.Inbox,
)
```

**Commit:** `refactor(ui): adopt AuraCard/AuraSectionHeader/AuraListRow/AuraEmptyState in 7 screens`

---

## Phase 3: Home Screen Reorganization (2 commits)

### Task 4: Group Home secondary actions into 3 categorized sections

The current Home has 13 identical cards in a flat LazyRow. On a 360dp phone, 2.5 cards are visible. The user scrolls through 13 tiles with no visual hierarchy to find what they need. Tasks, Memory, and Calendar are equally weighted with Agent Runs, Production, and Capabilities.

**New layout — 3 categorized sections with visual hierarchy:**

```
┌─────────────────────────────────────┐
│ DAILY                               │ ← 10sp uppercase, textTertiary
│ ┌─────────┐ ┌─────────┐ ┌────────┐ │
│ │ Memory  │ │ Tasks   │ │ Calendar│ │ ← 140dp cards, surface1 bg
│ │ 3 saved │ │ 2 pend. │ │ 1 today│ │
│ └─────────┘ └─────────┘ └────────┘ │
│ ┌─────────┐                        │
│ │Reminder │                        │
│ │ 2 upcom.│                        │
│ └─────────┘                        │
│                                     │
│ CREATE                              │
│ ┌─────────┐ ┌─────────┐ ┌────────┐ │
│ │ Creative│ │ Hands   │ │ Skills │ │
│ │ 1 proj. │ │ 0 macros│ │ 3 skill│ │
│ └─────────┘ └─────────┘ └────────┘ │
│                                     │
│ SYSTEM                              │
│ ┌─────────┐ ┌─────────┐ ┌────────┐ │
│ │ Proactiv│ │ Evolve  │ │ Tools  │ │
│ │ 2 events│ │ 1 prop. │ │ 69 tool│ │
│ └─────────┘ └─────────┘ └────────┘ │
│ ┌─────────┐ ┌─────────┐ ┌────────┐ │
│ │ AgentR. │ │ Product.│ │ Capabi.│ │
│ │ 0 runs  │ │ 0 pipes │ │ 2 conn.│ │
│ └─────────┘ └─────────┘ └────────┘ │
└─────────────────────────────────────┘
```

**Design specs:**
- Section header: `labelSmall` (10sp), `textTertiary` color, uppercase, `AuraSpacing.md` top padding, `AuraSpacing.xs` bottom padding
- Cards: 140dp wide (down from 148dp), `AuraThemeTokens.colors.surface1` bg, `borderSubtle` border, `MaterialTheme.shapes.medium` (12dp radius), `AuraSpacing.sm` internal padding
- Cards within each section: `LazyRow` with `AuraSpacing.sm` spacing
- Section spacing: `AuraSpacing.md` between sections
- Category labels use `FontWeight.SemiBold` for readability at small size
- Icon: 20dp, `actionPrimary` tint (teal after Phase 1)
- Label: `labelLarge` (13sp), `textPrimary`, `SemiBold`
- Subtitle: `labelSmall` (10sp), `textSecondary`

**File:** `HomeSecondaryActions.kt` — restructure `destinations` from a flat list into 3 grouped lists, render each group with a section header + LazyRow.

**Reminders:** Move from a separate card into the "Daily" section (currently it's not in the LazyRow at all — it's in a separate section of HomeContent).

**Commit:** `feat(home): group 13 secondary actions into Daily/Create/System categories`

### Task 5: Redesign HomeAtAGlance into visual icon cards

Currently `HomeAtAGlance` renders tasks/reminders/calendar as plain text lines: "Task - title", "Reminder - label", "Calendar - event". It looks like a debug log.

**New design — 3 compact horizontal cards with icons:**

```
┌──────────────────────────────────────┐
│ At a glance                          │ ← labelLarge, textSecondary
│ ┌──────────────────────────────────┐ │
│ │ ☑  Review PR #42                 │ │ ← Row: icon + title
│ │    Due today                     │ │ ← subtitle: textTertiary
│ └──────────────────────────────────┘ │
│ ┌──────────────────────────────────┐ │
│ │ 🔔  Team standup                 │ │
│ │    In 30 minutes                 │ │
│ └──────────────────────────────────┘ │
│ ┌──────────────────────────────────┐ │
│ │ 📅  Dentist appointment          │ │
│ │    2:00 PM                       │ │
│ └──────────────────────────────────┘ │
└──────────────────────────────────────┘
```

**Design specs:**
- Each item: `AuraCard` (adopts the shared component from Phase 2) with `AuraSpacing.sm` padding
- Icon: 18dp, `actionPrimary` tint (TaskAlt / NotificationsActive / CalendarMonth)
- Title: `bodyMedium` (14sp), `textPrimary`, single line with ellipsis
- Subtitle: `labelSmall` (10sp), `textTertiary`
- 6dp spacing between cards
- "At a glance" header: `labelLarge` (13sp), `textSecondary`, `AuraSpacing.sm` bottom padding
- Max 3 items (current behavior preserved)

**File:** `HomeContent.kt` — rewrite `HomeAtAGlance` composable.

**Commit:** `feat(home): redesign At a Glance into visual icon cards`

---

## Phase 4: Settings Reorganization (1 commit)

### Task 6: Group 15 Settings sections into 4 visual categories

Currently Settings is a flat scrollable list of 15 expandable sections in random order: AI and Models, Usage, Appearance, Persona, Tool Permissions, Model Roles, MCP Servers, Privacy, Triggers, Emotion & Daemon, Integrations, Reasoning, Evolution, Data & Backup, Dream Consolidation.

**New grouped layout with 4 visual categories:**

```
AI & MODELS                           ← group header
  ⚙️ AI and Models                    ← existing SettingsSection
  🎭 Model Roles
  🧠 Reasoning
  🔌 Integrations
  🔗 MCP Servers

MEMORY & KNOWLEDGE
  🧬 Persona
  💤 Dream Consolidation
  🎭 Emotion & Daemon
  ⚡ Triggers

PRIVACY & DATA
  🔒 Privacy
  🛡️ Tool Permissions
  💾 Data & Backup
  🎨 Appearance

SYSTEM
  📊 Usage
  🔧 Capabilities
  🧬 Evolution Settings
```

**Design specs for group headers:**
- A new `SettingsGroupHeader` composable (inline in SettingsScreen, not a shared component — it's Settings-specific):
  - `labelSmall` (10sp), `textTertiary`, uppercase, `FontWeight.SemiBold`
  - `AuraSpacing.lg` top padding (24dp — extra space above each group), `AuraSpacing.xs` bottom padding
  - No card background — just a text label that separates the sections below it
- Sections within a group: no visual change to the `SettingsSection` composable itself
- Sections within a group: `AuraSpacing.xxs` (4dp) vertical padding between them (tighter than the current 5dp)
- Between groups: the group header's 24dp top padding provides the visual separation

**Reorder sections within SettingsScreen:**
Move the 15 `SettingsSection(...)` calls into 4 groups, each preceded by `SettingsGroupHeader("GROUP NAME")`. No section composable changes — just ordering and grouping.

**Also:** Move "AI and Models" to first position (it's the most important — API keys). Currently it's already first. "Usage" moves from position 2 to the System group. "Appearance" moves from position 3 to Privacy & Data group.

**File:** `SettingsScreen.kt` — reorder the 15 section calls, add 4 `SettingsGroupHeader` calls, add the `SettingsGroupHeader` composable.

**Commit:** `feat(settings): reorganize 15 sections into 4 visual groups`

---

## Phase 5: Spacing Token Ratchet + Empty State Consistency (1 commit)

### Task 7: Replace hardcoded padding/spacing in screens with AuraSpacing tokens

782 hardcoded dp values in screens. Not all should be tokenized (icon sizes, font sizes, widget dimensions stay as literals), but padding/arrangement values should use `AuraSpacing`.

**Approach:** Execute a Python script that:
1. Finds all `.dp` values in `app/src/main/kotlin/com/aura/ui/screens/` and `app/src/main/kotlin/com/aura/ui/components/`
2. Filters to only `padding(...)`, `Arrangement.spacedBy(...)`, and `Spacer(Modifier.height(...))` contexts
3. Maps: 4dp→xxs, 8dp→xs, 12dp→sm, 16dp→md, 24dp→lg, 32dp→xl, 48dp→xxl
4. Leaves non-matching values (e.g., 6dp, 14dp, 20dp) as literals — they're intentional fine-tuning, not scale violations
5. Adds `import com.aura.ui.theme.AuraSpacing` where missing

**Expected coverage:** ~60% of the 782 values will be replaced. The remaining 40% are non-standard values (6dp, 10dp, 14dp, 20dp, 148dp, etc.) that are intentional design decisions, not spacing-scale violations.

**Verify after:** `grep -rn "[0-9]*\.dp" app/src/main/kotlin/com/aura/ui/screens/ | wc -l` — should drop from 782 to ~300.

**Commit:** `refactor(ui): replace hardcoded padding with AuraSpacing tokens`

### Task 8: Unify all empty states to use AuraEmptyState

Multiple screens use inline `Text("No data", color = onSurfaceVariant)` for empty states. Replace all with `AuraEmptyState` for consistent icon + title + message + optional action.

**Files:** Check all screens for inline empty state patterns:
- `if (list.isEmpty()) { Text("No X yet", ...) }`
- Replace with `AuraEmptyState(title = "No X yet", message = "...", icon = ...)`

**Screens to check:** WorldModel, TasteProfile, Dreams, Skills, ProactiveHistory, AgentRuns, Diagnostics, Memory, Tasks, History, Hands, KnowledgeGraph

**Commit:** `refactor(ui): unify all empty states to AuraEmptyState component`

---

## Phase 6: Chat Screen Polish (1 commit)

### Task 9: Chat header cleanup and chat composer visual alignment

The chat header (334 lines) has 10+ icon buttons crammed into a single row. The composer (401 lines) has a dropdown menu for attachments that doesn't visually align with the input bar. Both use raw MaterialTheme tokens in places.

**Chat header changes:**
- Reduce icon buttons from 10+ to 5 visible + overflow menu
- Visible: History, New conversation, Model picker, Voice toggle, More (overflow)
- Overflow menu: Delete, Export, Clear, Deep mode, Incognito, Regenerate
- Model picker pill: use `AuraThemeTokens.colors.surface1` bg + `borderSubtle` border + `actionPrimary` accent dot
- All colors from `AuraThemeTokens.colors.*` (no MaterialTheme.colorScheme)

**Chat composer changes:**
- Attachment dropdown: replace `DropdownMenu` with a `ModalBottomSheet` that visually aligns with the input bar (slides up from the bottom edge)
- Input bar: `AuraThemeTokens.colors.surface1` bg, `borderSubtle` border, 24dp radius
- Send button: `actionPrimary` bg, morphing size (12dp empty → 20dp ready, spring-eased)
- All hardcoded dp values → AuraSpacing tokens where applicable

**Files:** `ChatHeader.kt`, `ChatComposer.kt`

**Commit:** `feat(chat): cleanup header to 5+overflow, align composer with input bar`

---

## Phase 7: Bottom Nav Polish + Motion (1 commit)

### Task 10: Bottom nav visual polish and item count reduction

Current: 6 items (Home, Chat, Memory, Tasks, Evolve, Settings) on a flat surface0 bar. On 360dp, each item gets 60dp — cramped.

**Changes:**
- Reduce to 5 items: Home, Chat, Memory, Tasks, Settings (move Evolve into Settings → System group, accessible from Home card)
- Floating pill style: `surface0` bg, 16dp radius, 8dp margin from edges, `borderSubtle` top border, subtle shadow
- Selected item: `surface1` pill bg with 180ms color animation, `actionPrimary` tint icon
- Unselected: `textTertiary` icon, transparent bg
- Badge: `actionPrimary` bg, `onActionPrimary` text, 10sp
- Icon size: 22dp (up from 21dp — more touch-friendly)
- Label: 11sp, `SemiBold` when selected, `Normal` when not
- Touch target: `AuraDimensions.minimumTouchTarget` (48dp)
- Motion: `animateColorAsState` with `tween(180)` for selection transition

**File:** `AuraBottomNavigation.kt`, `NavGraph.kt` (remove Evolve from topLevelRoutes)

**Commit:** `feat(nav): reduce to 5 nav items, floating pill style with motion`

---

## Summary

| Phase | Tasks | Files | Impact |
|-------|-------|-------|--------|
| 1 | 1 | ~25 | Brand teal accent + single color system (0 bypasses) |
| 2 | 2 | 17 | Consistent screen shell + shared components adopted |
| 3 | 2 | 2 | Home grouped into Daily/Create/System + visual At a Glance |
| 4 | 1 | 1 | Settings grouped into AI/Memory/Privacy/System |
| 5 | 2 | ~15 | Spacing tokens + unified empty states |
| 6 | 1 | 2 | Chat header/composer visual polish |
| 7 | 1 | 2 | Bottom nav 5-item pill with motion |
| **Total** | **10** | ~64 | Full visual consistency across the app |

**Verification gate per phase:**
```bash
./gradlew :aura-core:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug
```

**Estimated effort:** 5-7 hours total. Each task 30-45 min.

**What this plan does NOT do:**
- Does not redesign the chat message bubbles (already SOTA from 2026-07-08)
- Does not change the empty chat state (already redesigned with breathing glow)
- Does not add new screens or change navigation structure beyond bottom nav item count
- Does not change the font families (Inter/Fraunces/JetBrains Mono are correct)
- Does not add animations beyond the bottom nav selection transition

**Prior plans alignment:** The SOTA UI redesign plan from 2026-07-08 shipped the design tokens, message bubbles, empty state, input bar, and bottom nav. This plan is the adoption ratchet — the tokens exist but ~85% of the app doesn't use them. This plan enforces consistency, not new design.