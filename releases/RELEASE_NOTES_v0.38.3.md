## v0.38.3

- fix(triggers): TriggerWorker now actually executes `RunHand` and `StartChat` actions instead of logging and discarding them. `RunHand` resolves by ID then name and enqueues the hand via `HandRunEnqueuer`. `StartChat` posts a notification with the prompt.
- fix(dream): `DreamConsolidator.pruneStale()` now sets `decayScore = 0` via the new `MemoryStore.updateDecayScore()` call instead of just appending an unused `pruned:dream` tag.
- fix(providers): `ProviderContextWindows` no longer uses training-data model name substrings. Only Anthropic's platform-wide 200K window is hardcoded; unknown providers/models fall back to the compactor's safe 32K default.
- chore(release): versionName 0.38.3, versionCode 45.
