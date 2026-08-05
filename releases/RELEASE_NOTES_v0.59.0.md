# v0.59.0 — The Council

## The wow feature

A persistent society of AI agents that live inside your phone, debate about you while you sleep, and propose interventions.

## What's new

### The Council (9 commits, 8 phases)

**Overnight Council** — While your phone is idle, 3-5 agents from your life council (general, researcher, writer, executive, creative) are selected to debate proactive findings (stress, goal-blockers, relationship gaps, deadlines). Each agent argues from its personality, mood, energy, relationships, and private observations. They run 2 debate rounds, vote, and if 60% quorum is reached, a concrete intervention is proposed.

**Emergency Live Council** — The model can now call `run_life_council` (70th tool) to convene an instant council on any topic. Returns the full debate transcript + vote tally + approved proposal or dissent notes.

**Agent State** — Each agent now has persistent mood (0-100), energy (0-100), current goal, stance on user (-100 to +100), and participation count. Mood decays with overuse and recovers during idle. Burned-out agents refuse to participate.

**Agent Relationships** — Agents gain/lose affinity based on voting together or against each other. Co-sponsors (allies with >30 affinity) get bonus affinity per shared vote. Relationships are visible in the Agent Profile screen.

**5 Intervention Types** — Schedule (task/calendar), Message (draft for approval), Reminder (with rationale), SelfCare (break/walk/sleep), Memory (surface forgotten connection).

**3 New Screens**:
- Council: pending interventions with approve/reject, debate thread viewer
- Dream Log: morning-readable overnight activity log with timestamps and vote tallies
- Agent Profiles: mood/energy bars, goals, relationships (ally/rival/neutral)

**Forum System** — Agent-to-agent message bus with posts (debate/proposal/intervention/dream), votes (for/against/abstain), quorum checking (60% for, min 3 voters).

**Settings** — Council enable/disable toggle, auto-apply interventions toggle (off by default), activity level slider (1-5).

### Audit fixes (1 commit)
- P0: Council entities now in backup (SCHEMA_VERSION 15→16)
- P1: 120s timeout on council sessions, 30s timeout per agent stance
- P1: Thread-safe vote casting (mutex)
- P2: Parallel debate rounds (4x faster)

## Stats
- 70 tools (was 69)
- AgentDatabase v3 (was v1)
- SCHEMA_VERSION 16 (was 15)
- 1406 tests, 0 failures
- 572 .kt main files, ~84K LOC