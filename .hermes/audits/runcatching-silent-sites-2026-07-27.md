# runCatching Silent Failure Audit

Remaining suspicious sites: 3

| Line | File | Snippet |
|------|------|---------|
| 257 | `app/src/main/kotlin\com\aura\ui\viewmodel\HomeViewModel.kt` | `runCatching { calendarReadTool.readTodaysEvents() } val reminders = reminderDao.observeUpcoming(now)` |
| 106 | `app/src/main/kotlin\com\aura\ui\viewmodel\KnowledgeGraphViewModel.kt` | `runCatching { repository.getNeighbors(node.id) } .onSuccess { neighbors -> val labels = allN` |
| 102 | `aura-core/src/main/kotlin\com\aura\tools\SendEmailBackgroundTool.kt` | `runCatching { val props = Properties().apply { put("mail.smtp.auth", "true") put("mail.smtp.starttls` |


## Verified False Positives

| Line | File | Why it is NOT silent |
|------|------|---------------------|
| 102 | `aura-core/src/main/kotlin\com\aura\tools\SendEmailBackgroundTool.kt` | Function returns `Result<Unit>`; the runCatching failure is propagated to the caller. |
| 257 | `app/src/main/kotlin\com\aura\ui\viewmodel\HomeViewModel.kt` | Result assigned to `calendarResult`; later consumed with `getOrDefault(...)` and `exceptionOrNull()`. |
| 106 | `app/src/main/kotlin\com\aura\ui\viewmodel\KnowledgeGraphViewModel.kt` | Has `.onFailure(::surfaceFailure)` on the following lines (long chain). |
