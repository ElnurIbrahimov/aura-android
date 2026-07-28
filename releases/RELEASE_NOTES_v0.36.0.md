# Aura Android v0.36.0

8 upgrade items shipped on `feat/tier-1-friction`.

## New / Improved
- **Capability surface** — `CapabilitiesScreen` + ViewModel + route, showing all 6 capability kinds and which providers power them.
- **Dreams screen** — full entity display for routines, contradictions, and edge proposals with actions.
- **World model + taste profile** — new `WorldModelScreen`/`TasteProfileScreen` routes wired through `EvolutionSettingsSection`.
- **Tool confirmation parity** — `WRITE_REMOTE` tools now require explicit confirmation; `IMPLICIT`/`EXPLICIT` prefixes distinguish implicit confirmation from cost approval.
- **Context-window budget propagation** — `ContextBudgetResolver` resolves provider-specific context windows; `Brain.stream` fills `maxTokens` when null.
- **runCatching logging enforcement** — added `.onFailure` logging to the last silent catch; `SilentRunCatchingAuditTest` guards against future regressions.
- **ChatViewModel extraction** — new `ChatConversationController` for save/load/fork/clear/export/delete/KG refresh; ChatViewModel down from 1112 to 1033 lines.
- **Onboarding scaffolding** — provider setup now offers 7 primary providers (ollama, anthropic, openai, deepseek, gemini, groq, openrouter) instead of 2; added `OnboardingViewModelTest`.

## Tests
- 1238+ unit tests passing, 0 failures.

## APK
`releases/aura-debug-v0.36.0.apk` (≈38.7 MB).
