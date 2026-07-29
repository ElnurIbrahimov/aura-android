# Aura Android UI Redesign — Presence First

Goal: transform the product metaphor from "AI toolbox" to "entity you live with."

## Phase 1: Presence-first home (4 commits)

1.1. `feat(home): AgentPresence composable with mood-aware avatar`
- Add `AgentPresence.kt`: large avatar, name label, mood ring, emotional state caption.
- Reads `HomeUiState.activeAgentId`, `EmotionSnapshot`, greeting from `HomeViewModel`.
- Avatar uses a generated initial + color derived from agent name until real art exists.

1.2. `feat(home): replace dashboard grid with relationship scene`
- Rewrite `HomeContent.kt` top half:
  - Header becomes AgentPresence + single contextual memory callback line.
  - Primary action: big "Continue" pill if last conversation exists, else "Start chatting".
  - Search recedes to a small icon in top-right.
  - Brief card stays but is visually subordinate (smaller, below fold).
- `HomeSecondaryActions` becomes a single horizontal scroll of icon-only tools at the bottom.

1.3. `feat(home): contextual memory callback line`
- `HomeViewModel` exposes `memoryCallback: String?` (last meaningful memory, recent task, or proactive event).
- Display under agent name. "Still thinking about your trip to Tbilisi?" style.

1.4. `feat(home): empty state as first-meeting scene`
- New `HomeEmptyState.kt`: avatar + welcome line + one rotating starter prompt.
- No tool grid until user scrolls down.

## Phase 2: Conversation as relationship (3 commits)

2.1. `refactor(chat): agent identity on assistant messages`
- `MessageBubble.kt`: per-agent color tint on assistant bubble.
- Agent name label only on first assistant message in a block.
- Remove tok/s badge from default view (keep behind long-press debug menu).

2.2. `refactor(chat): collapse tool call badges`
- ToolCallBadge shrinks to a 6dp dot + "thinking..." text that resolves to checkmark.
- Tap to expand full tool list. Default: minimal.

2.3. `refactor(chat): warm empty state`
- Empty chat shows active agent avatar + rotating contextual greeting.
- Remove quick-action card grid from chat empty state.

## Phase 3: Emotional continuity (2 commits)

3.1. `feat(home): emotion-driven avatar state`
- AgentPresence reacts to `EmotionSnapshot`: tension dims glow, energy quickens pulse.
- Connect `HomeViewModel` to `EmotionEngine` / `UserPreferences.emotionSnapshot`.

3.2. `feat(nav): shared element transitions home <-> chat`
- Compose `SharedTransitionLayout` around `NavHost`.
- Agent avatar and name animate from home to chat header.

## Verification
- `:app:compileDebugKotlin`
- `:app:testDebugUnitTest`
- `:app:assembleDebug`
- emulator screenshot of home and chat

## Anti-goals
- Do not add new dependencies beyond Compose animation APIs already available.
- Do not redesign settings, creative studio, or other secondary screens.
- Do not create custom illustrations; use generated initials + colors until real assets.
