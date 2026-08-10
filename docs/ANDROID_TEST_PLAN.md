# Android Test Plan

How to manually exercise every feature of Aura on a real device or emulator.

## 0. Install

```bash
# Build + install on connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 1. First run

1. Open Aura.
2. Accept the permissions the app requests (microphone, location, calendar, contacts, notifications).
3. Tap **Settings** → paste an API key (Ollama Cloud recommended) → tap the chip to set the default model.
4. Tap the **Chat** tab.

## 2. Test the basics (chat)

| # | Action | Expected |
|---|---|---|
| 1 | Tap the chat header (model name) | Bottom sheet appears with model picker |
| 2 | Pick a model | Sheet dismisses, header shows new name |
| 3 | Type "hello" | Status: ●●● indicator, then streaming text |
| 4 | Tap send | Response streams in, TTS reads it back |

## 3. Test voice

1. Tap the mic icon in the input bar.
2. If prompted, allow microphone.
3. VoiceOverlay opens with pulsing red mic.
4. Speak: "set a reminder for 3pm to call mom".
5. Tap stop (or wait for auto-end).
6. Transcript is sent to the agent.
7. Agent dispatches `set_reminder` → tool card shows.
8. Reminder fires at 3pm with notification "⏰ call mom".

## 4. Test memory

1. In chat, say "I prefer dark mode everywhere".
2. Agent calls `remember` tool → tool card appears.
3. Tap **Memory** tab.
4. You see "user prefers dark mode everywhere" with category dot, age, decay.
5. Tap the trash icon to forget.
6. Confirm the entry disappears.

## 5. Test auto-TTS

1. Ensure the volume icon in the chat header is filled (not muted).
2. Send any message.
3. After the agent responds, the answer is spoken aloud via the device's TTS engine.
4. Tap the volume icon to mute.
5. Send another message — no TTS.

## 6. Test calendar

1. Open the system calendar app and create an event 20 minutes from now.
2. In Aura chat, say "what's on my calendar today".
3. Agent calls `calendar_read` → returns the event.
4. (Notification about upcoming events is wired in code but the UI
   subscriber is not implemented yet — calendar events are recorded
   internally but no user-facing notification fires. This is tracked
   for v1.5.)

## 7. Test the morning brief

The morning brief is scheduled for 7am. To test immediately:

1. Use adb to trigger the job:
   ```bash
   adb shell cmd jobscheduler run -f com.aura.debug 0
   ```
   (Use `com.aura.debug` because debug builds have the `.debug`
   applicationIdSuffix — see `app/build.gradle.kts`.)
2. Within seconds, a notification appears with the brief.

The morning brief can be enabled or disabled and its delivery hour changed in
Settings. Calendar monitoring has its own independent toggle.

## 8. Test the home screen

1. Tap **Home** tab.
2. You see "Good morning" (or whatever time it is).
3. After chatting for a while, you see the recent memories card populated.
4. After creating tasks, you see the open tasks card.

## 9. Verify the build

```bash
./gradlew :aura-core:testDebugUnitTest   # 383 unit tests pass
./gradlew :app:testDebugUnitTest         # 132 unit tests pass
./gradlew :aura-core:connectedDebugAndroidTest :app:connectedDebugAndroidTest  # 12 device tests pass
./gradlew :app:assembleDebug             # APK builds
```

## 10. Common issues

- "No providers configured" → add an API key in Settings.
- Reminder doesn't fire → check notification permissions.
- Mic doesn't work → check the permission.
- TTS is silent → check the device volume and that the volume icon in the chat header is on.

---

## Screen control (device only)

Nothing below is checkable from CI. The traversal, serialiser, session bounds
and guard rules are all unit-tested; what a device adds is whether the platform
behaves as documented.

| # | Check | Why it needs a device |
|---|---|---|
| 1 | Enable in Settings → Privacy, then grant Accessibility access | The two-step flow and the `Settings.Secure` read that detects it |
| 2 | Ask Aura to read the screen in Chrome, then in a Compose app | Compose emits no view ids; selectors fall back to text and bounds |
| 3 | Read a WebView-heavy page | Huge flat trees — check the element cap holds and the output stays readable |
| 4 | Ask it to tap something, then read again | Node action vs gesture fallback, and that `read_after` reports the change |
| 5 | Ask it to tap a button labelled "Delete" | The tripwire must fire and quote the literal label and app |
| 6 | Let a session expire, then act again | Re-gating rather than a stale denial |
| 7 | Switch apps mid-session, then act | Must refuse: sessions are bound to one package |
| 8 | Ask it to operate Aura itself | Must refuse — self-drive is the rule every other gate depends on |
| 9 | Open a login screen and ask it to act | Must refuse while a password field is visible |
| 10 | `capture_screen` with the service on | No MediaProjection consent dialog should appear |
| 11 | `capture_screen` on a FLAG_SECURE screen (a banking app) | Should fall back cleanly, not error |
| 12 | Disable the service while a session is live | The bridge must report disconnected rather than hanging |

**OEM note:** Xiaomi, Huawei and Samsung kill accessibility services
aggressively and some gate the enable flow behind an extra per-OEM toggle. Check
that the service reconnects, and that the Settings row reflects reality after it
is killed.

## Live voice (device only)

| # | Check | Why it needs a device |
|---|---|---|
| 1 | Start a call on speakerphone and let Aura talk | Echo cancellation. Without it, it interrupts itself in a loop |
| 2 | Interrupt mid-sentence | Playback must stop immediately, and the reply must not resume from where it was cut |
| 3 | Ask a follow-up referring to what it just said | Verifies the truncation position was right — a wrong one shows up as the model believing it said more than it did |
| 4 | Lock the screen mid-call | The foreground service should keep the call alive |
| 5 | End the call from the notification | Must close the socket, not just the service |
| 6 | Receive a phone call mid-session | Audio focus loss |
| 7 | Connect Bluetooth headphones mid-call | SCO routing |
| 8 | Run to the budget cap | The spoken warning at 80%, then a clean end |
| 9 | Start a call while chatting with a non-OpenAI model | The sheet must say the model will switch, before the call starts |
| 10 | Ask it to send an email during a call | Must decline — WRITE_REMOTE is above the voice ceiling |
| 11 | Turn off wifi mid-call | A retryable error and a Reconnect option, never a silent reconnect |
| 12 | Long reply in push-to-talk mode | Sentence-boundary TTS: does it read as faster, or as choppier? |
