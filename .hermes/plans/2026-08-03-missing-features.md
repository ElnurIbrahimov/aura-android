# Missing Features Implementation Plan

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** Close 8 verified feature gaps from the Round 13 comprehensive audit.

**Architecture:** All gaps are in the existing app module — no new Room migrations needed. Changes span backup data classes, UI screens, ViewModels, voice infrastructure, and widget XML.

**Tech Stack:** Kotlin, Jetpack Compose, Room, WorkManager, Android TTS, AppWidgetProvider

---

## Pre-Audit: What Exists vs What's Needed

| Component | Status | Evidence |
|-----------|--------|----------|
| Notification channels | EXISTS | `CalendarMonitorService.kt:55` creates channel, `MorningBriefBuilder.kt:189` creates channel — original claim was a false positive |
| Dreams screen actions | EXISTS | `DreamsScreen.kt:319-352` has Dismiss/Use newer/Accept/Reject buttons — original claim was a false positive |
| BeliefDao.supersede/verify | EXISTS | `WorldModelDaos.kt:36,39` — DAO has both methods |
| WorldModelViewModel.verify/retire | MISSING | `WorldModelViewModel.kt` only has `resolveOpportunity` — no belief actions |
| WorldModelScreen belief actions | MISSING | `WorldModelScreen.kt` shows beliefs as text cards, no action buttons |
| Evolution Home card | MISSING | `HomeContent.kt` has 11 cards, none for evolution |
| Streaming TTS | MISSING | `TextToSpeech.kt:82` only uses Android TTS QUEUE_ADD — no chunked streaming |
| Theme preview in Settings | MISSING | `AppearanceSection.kt` has no preview composable |
| Reminder widget | MISSING | Only `widget_ask_aura_info.xml` exists — no reminder widget |
| Taste profile charts | MISSING | `TasteProfileScreen.kt` is 195 lines, text-only, no visual breakdown |
| Crash reporting remote | MISSING | `CrashLogger.kt` writes to local file only — no Crashlytics/Sentry |
| 6 prefs missing from backup | MISSING | `dreamEnabled`, `decayEnabled`, `triggersEnabled`, `triggersJson`, `planningEnabled`, `agentId` not in `PreferencesBackup` |

---

## Phase 1: Backup Prefs + World Model Belief Actions (2 commits)

### Task 1: Add 6 missing prefs to PreferencesBackup

**Objective:** Stop losing 6 user preferences on backup/restore.

**Files:**
- Modify: `aura-core/src/main/kotlin/com/aura/backup/AuraBackup.kt` (PreferencesBackup data class)
- Modify: `aura-core/src/main/kotlin/com/aura/backup/BackupManager.kt` (snapshot + restore paths)

**Step 1:** Add 6 fields to PreferencesBackup:
```kotlin
val dreamEnabled: Boolean = true,
val decayEnabled: Boolean = true,
val triggersEnabled: Boolean = true,
val triggersJson: String = "[]",
val planningEnabled: Boolean = false,
val defaultAgentId: String = "",
```

**Step 2:** Add snapshot reads in BackupManager.snapshot() for each new field — read from UserPreferences flows.

**Step 3:** Add restore writes in BackupManager.restore() for each new field — call the corresponding setter on UserPreferences.

**Step 4:** Verify with existing backup serialization test — add assertions for the new fields.

**Commit:** `fix(backup): add 6 missing prefs to PreferencesBackup roundtrip`

### Task 2: Wire belief Verify/Retire actions in World Model screen

**Objective:** User can verify or retire beliefs from the UI, not just view them.

**Files:**
- Modify: `app/src/main/kotlin/com/aura/ui/viewmodel/WorldModelViewModel.kt` (add verifyBelief, retireBelief)
- Modify: `app/src/main/kotlin/com/aura/ui/screens/WorldModelScreen.kt` (add action buttons to belief cards)

**Step 1:** Add to WorldModelViewModel:
```kotlin
fun verifyBelief(id: String) { viewModelScope.launch { beliefDao.verify(id, 1.0f, System.currentTimeMillis()) } }
fun retireBelief(id: String) { viewModelScope.launch { beliefDao.supersede(id, "retired", "", System.currentTimeMillis()) } }
```

**Step 2:** In WorldModelScreen, add two TextButtons to each belief card:
- "Verify" (calls verifyBelief) — green accent
- "Retire" (calls retireBelief) — error color

**Step 3:** Test: add WorldModelViewModelTest with verify/retire assertions.

**Commit:** `feat(world-model): wire belief Verify/Retire buttons in UI`

---

## Phase 2: Evolution Home Card + Taste Profile Charts (2 commits)

### Task 3: Add Evolution card to Home screen

**Objective:** User can reach evolution inbox from Home, not just Settings.

**Files:**
- Modify: `app/src/main/kotlin/com/aura/ui/screens/home/HomeContent.kt` (add card)
- Modify: `app/src/main/kotlin/com/aura/ui/screens/home/HomeRoute.kt` (add callback)
- Modify: `app/src/main/kotlin/com/aura/ui/nav/NavGraph.kt` (wire callback)

**Step 1:** Add `onOpenEvolution: () -> Unit` to HomeRoute params and HomeContent params.

**Step 2:** Add a HomeSecondaryAction card with `Icons.Filled.AutoAwesome` (distinct from Creative's icon) navigating to "evolution/inbox".

**Step 3:** Wire in NavGraph: `onOpenEvolution = { navController.navigate("evolution/inbox") }`

**Commit:** `feat(home): add Evolution card to home screen`

### Task 4: Add visual breakdown to Taste Profile screen

**Objective:** User sees a visual chart of their learned style preferences, not just a text list.

**Files:**
- Modify: `app/src/main/kotlin/com/aura/ui/screens/TasteProfileScreen.kt`
- Use existing `ChartDispatcher` / `BarChartView` from `ui/components/charts/`

**Step 1:** Group preference signals by attribute key (tone, verbosity, formality, humor, etc.).

**Step 2:** Render a BarChartView showing the dominant value per key (count-weighted).

**Step 3:** Keep the existing text list below the chart for detail view.

**Commit:** `feat(taste): add visual preference breakdown chart to Taste Profile screen`

---

## Phase 3: Streaming TTS + Theme Preview (2 commits)

### Task 5: Add sentence-level streaming TTS

**Objective:** TTS starts speaking the first sentence while the model is still generating the rest, instead of waiting for the full response.

**Files:**
- Modify: `aura-core/src/main/kotlin/com/aura/voice/TextToSpeech.kt`
- Modify: `app/src/main/kotlin/com/aura/ui/voice/VoiceViewModel.kt`

**Step 1:** Add a `speakStreaming(textFlow: Flow<String>)` method to TextToSpeech that:
- Accumulates text from the flow
- Splits on sentence boundaries (`. `, `! `, `? `, `\n`)
- Speaks each complete sentence immediately via Android TTS
- Buffers incomplete trailing text until the next boundary

**Step 2:** In VoiceViewModel, when streaming mode is active, pipe the BrainChunk.TextDelta flow into `speakStreaming` instead of waiting for the full response.

**Step 3:** Test: unit test the sentence boundary splitter with edge cases (abbreviations, no terminal punctuation, very long sentences).

**Commit:** `feat(voice): sentence-level streaming TTS — speak first sentence while model generates`

### Task 6: Add theme preview to Appearance settings

**Objective:** User sees a mini preview of the selected theme before applying.

**Files:**
- Modify: `app/src/main/kotlin/com/aura/ui/settings/sections/AppearanceSection.kt`

**Step 1:** Add a small composable preview showing:
- A mock message bubble (user + assistant) with current tokens
- The bottom nav bar in the selected theme
- The input bar

**Step 2:** Place it above the theme selector so the user sees the preview change when they switch options.

**Step 3:** Use `AuraThemeTokens` to render the preview so it reflects the actual theme.

**Commit:** `feat(settings): add theme preview to Appearance section`

---

## Phase 4: Reminder Widget + Crash Log Viewer (2 commits)

### Task 7: Add Reminder widget to home screen

**Objective:** User can see upcoming reminders on the Android home screen without opening the app.

**Files:**
- Create: `app/src/main/kotlin/com/aura/widget/ReminderWidgetProvider.kt`
- Create: `app/src/main/res/layout/widget_reminders.xml`
- Create: `app/src/main/res/xml/widget_reminders_info.xml`
- Modify: `app/src/main/AndroidManifest.xml` (register receiver)

**Step 1:** Create ReminderWidgetProvider extending AppWidgetProvider:
- Query ReminderStore for next 3 upcoming reminders
- Render into a RemoteViews with a LinearLayout of 3 rows
- Tap on any row opens the app at "reminders" route
- "Add reminder" button at bottom opens app at chat with prefill "remind me to "

**Step 2:** Create the widget layout XML (compact, dark-themed, 4x2 cells).

**Step 3:** Create the widget info XML (minWidth=250dp, minHeight=180dp, updatePeriodMillis=1800000).

**Step 4:** Register in AndroidManifest with `<receiver android:name=".widget.ReminderWidgetProvider">` + intent-filter for APPWIDGET_UPDATE.

**Step 5:** Update widget on reminder create/delete via AppWidgetManager.notifyAppWidgetViewDataChanged.

**Commit:** `feat(widget): add Reminder home screen widget showing next 3 reminders`

### Task 8: Add in-app crash log viewer

**Objective:** User can view crash logs in Settings without ADB, since there's no remote crash reporting.

**Files:**
- Modify: `app/src/main/kotlin/com/aura/ui/settings/sections/DataAndBackupSection.kt` (add entry point)
- Create: `app/src/main/kotlin/com/aura/ui/screens/CrashLogScreen.kt`
- Modify: `app/src/main/kotlin/com/aura/ui/nav/NavGraph.kt` (add route)

**Step 1:** Add "Crash logs" button to DataAndBackupSection that navigates to "crash_logs".

**Step 2:** Create CrashLogScreen:
- Reads crash log files from CrashLogger's directory (context.cacheDir/crash_logs/)
- Shows each crash as an expandable card: timestamp, exception type, stack trace
- "Clear logs" button deletes all crash log files
- "Share" button sends the log file via ShareIntent

**Step 3:** Register "crash_logs" route in NavGraph.

**Commit:** `feat(settings): add in-app crash log viewer — no ADB needed`

---

## Summary

| Phase | Tasks | New Files | Modified Files | New Tests | Dependencies |
|-------|-------|-----------|----------------|-----------|--------------|
| 1 | 2 | 0 | 4 | 2 | None |
| 2 | 2 | 0 | 4 | 0 | None |
| 3 | 2 | 0 | 3 | 1 | None |
| 4 | 2 | 4 | 3 | 0 | None |
| **Total** | **8** | **4** | **14** | **3** | None |

**Verification gate per phase:**
```bash
./gradlew :aura-core:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug
```

**Estimated effort:** 4-6 hours total (each task 30-45 min).

**Prior plans alignment:** No prior plans in `.hermes/plans/` for these items. This plan is the first.