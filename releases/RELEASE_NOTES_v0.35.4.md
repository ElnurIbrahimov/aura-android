# Aura Android v0.35.4

## Engineering history cleanup
- fix(agentic): provider failover no longer consumes a maxSteps slot
- fix(agentrun): approval/deny loop is two-way with STEP_RESET event
- chore(room): regenerate missing schema exports for MemoryDatabase v7-v10
- chore(logging): add Log.w failure hooks to high-risk silent runCatching sites

## Verification
- aura-core unit tests: green
- app unit tests: green
- assembleDebug: green

APK: `aura-debug-v0.35.4.apk` (38 MB)
