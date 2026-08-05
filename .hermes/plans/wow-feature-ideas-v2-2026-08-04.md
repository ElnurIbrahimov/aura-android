# Aura Android — "Wow Wow" Feature Ideation Draft v2

Status: Initial batch rejected as "not wow wow enough." Second batch generated.

## The problem with Round 1
- Competitive-gap ideas (Dream Canvas, Life Threads, Phantom Mode) are things ChatGPT/Claude/Gemini already ship or are about to ship.
- They feel like "me too" features, not magic.

## Round 2: 3 actually "wow wow" directions

### 1. PARALLEL LIVES — Nightly Multiverse Simulator
**Hook:** Every morning Aura shows you 3 plausible alternate versions of your next 24 hours and lets you pick one.
**Why it's wow:** It doesn't just remind you of your calendar; it simulates futures. *"If you skip the gym, mood drops 12% by 8pm; if you call your sister, your affinity score rises; if you take the later flight, you save $80 but lose 2 hours of deep work."* It uses your memory graph, calendar, spending, mood history, and agentic simulation to run thousands of tiny Monte Carlo futures overnight.
**Build complexity:** Medium (strategy bandit + memory + creative engine + lightweight simulation already exist)
**Why only Aura:** Big Tech can't ship "predict your behavior" this aggressively without PR/privacy meltdown. A sideload personal app can.

### 2. THE COUNCIL — Agents That Live Their Own Lives
**Hook:** Your agents don't just wait for tasks. They talk to each other, form opinions about you, and sometimes stage interventions.
**Why it's wow:** Open the app and see: *"The Writer and the Researcher argued for 20 minutes about whether you're burnout-bound. The Executive overruled them and scheduled a 30-min walk."* Each agent has persistent mood, memory, goals, and relationships with other agents. They generate their own "dream logs" overnight. You can eavesdrop on their debates or call a council meeting.
**Build complexity:** Medium-High (multi-agent system exists; need agent-to-agent message bus + agent state persistence + council orchestrator)
**Why only Aura:** Consumer apps ship isolated agents. Nobody ships a society of agents that conspire about the user because it's weird, hard to debug, and potentially alarming. For a personal app it's a feature, not a bug.

### 3. REALITY CO-AUTHOR — Your Phone Rewrites Itself for You
**Hook:** Aura silently reshapes your digital environment to match your stated values: boosts people you love, dims attention bait, rewrites notifications in your tone, auto-composes replies that sound like you.
**Why it's wow:** Not a chatbot you open. A layer that acts *between* you and the OS. It feels like having a benevolent ghost in your phone. Big Tech calls this "digital wellness" but delivers settings menus. Aura delivers magic: you say *"I want to be less reactive"* and your notifications start arriving rephrased as calm questions instead of dopamine grenades.
**Build complexity:** High (AccessibilityService/NotificationListener + on-device LLM + persona model + intent injection)
**Why only Aura:** Apple/Google can't override other apps' UX or auto-reply as you without massive legal/safety theater. A sideload app can.

## Personal recommendation
**The Council** is the strongest wow-for-effort ratio. It turns Aura's existing multi-agent architecture into a living world the user can discover, not a tool they use. It creates emotional attachment ("my agents care about me"), endless shareable moments ("listen to what my agents fought about"), and is technically a layer on top of existing infrastructure.

Next step: pick one and write a build plan.
