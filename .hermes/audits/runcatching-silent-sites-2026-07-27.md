# runCatching Silent Failure Audit

Total suspicious sites: 42

| Line | File | Snippet |
|------|------|---------|
| 512 | `app/src/main/kotlin\com\aura\ui\components\MarkdownText.kt` | `runCatching { uriHandler.openUri(annotation.item) } } }, ) } } private fun splitMarkdo` |
| 525 | `app/src/main/kotlin\com\aura\ui\screens\KnowledgeGraphScreen.kt` | `runCatching { Json.parseToJsonElement(propertiesText) as? JsonObject ?: error("Properties must be a ` |
| 259 | `app/src/main/kotlin\com\aura\ui\settings\SettingsViewModel.kt` | `runCatching { emotionEngine.load() _emotionSnapshot.value = emotionEngine.snapshot() } } viewModelSc` |
| 265 | `app/src/main/kotlin\com\aura\ui\settings\SettingsViewModel.kt` | `runCatching { _daemonThoughtsCount.value = proactiveEventDao.countByType("daemon_thought") } } } fun` |
| 206 | `app/src/main/kotlin\com\aura\ui\viewmodel\ChatMediaController.kt` | `runCatching { extractor.extract(uri) } result.onFailure { e -> crashLogger.log(code = "doc` |
| 411 | `app/src/main/kotlin\com\aura\ui\viewmodel\ChatViewModel.kt` | `runCatching { textToSpeech.state.collect { tts -> _state.update { it.copy(ttsState = tts) } } } } //` |
| 419 | `app/src/main/kotlin\com\aura\ui\viewmodel\ChatViewModel.kt` | `runCatching { val cm = application.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as` |
| 501 | `app/src/main/kotlin\com\aura\ui\viewmodel\ChatViewModel.kt` | `runCatching { memoryStore.storeIfAbsent( content = "This user started using Aura. They went through ` |
| 514 | `app/src/main/kotlin\com\aura\ui\viewmodel\ChatViewModel.kt` | `runCatching { val cm = getApplication<Application>().getSystemService(android.content.Context.CONNEC` |
| 866 | `app/src/main/kotlin\com\aura\ui\viewmodel\ChatViewModel.kt` | `runCatching { conversationStore.delete(convId) } } _state.update { it.copy(` |
| 978 | `app/src/main/kotlin\com\aura\ui\viewmodel\ChatViewModel.kt` | `runCatching { val count = knowledgeGraphRepository.stats().nodeCount if (count > _state.value.kgNode` |
| 76 | `app/src/main/kotlin\com\aura\ui\viewmodel\CreativeStudioViewModel.kt` | `runCatching { store.create(name, description, genre, tone, templateId) } .onSuccess { project -> _st` |
| 111 | `app/src/main/kotlin\com\aura\ui\viewmodel\CreativeStudioViewModel.kt` | `runCatching { store.updateProject(id, name, description, genre, tone, templateId) } .onSuccess { pro` |
| 122 | `app/src/main/kotlin\com\aura\ui\viewmodel\CreativeStudioViewModel.kt` | `runCatching { store.updateWorld(id, world) } .onSuccess { project -> _state.update { it.copy(selecte` |
| 47 | `app/src/main/kotlin\com\aura\ui\viewmodel\DiagnosticsViewModel.kt` | `runCatching { crashLogger.entries() } .onSuccess { entries -> _state.update {` |
| 72 | `app/src/main/kotlin\com\aura\ui\viewmodel\DiagnosticsViewModel.kt` | `runCatching { crashLogger.clear() traceSink.clear() crashLogger.entries() }.onSuccess { entries -> _` |
| 95 | `app/src/main/kotlin\com\aura\ui\viewmodel\DiagnosticsViewModel.kt` | `runCatching { crashLogger.exportTo(context.cacheDir) } .onSuccess { file -> _state.update { it.cop` |
| 47 | `app/src/main/kotlin\com\aura\ui\viewmodel\DocumentImportViewModel.kt` | `runCatching { val extracted = extractor.extract(uri) _state.update { it.copy(stage = "Creating searc` |
| 79 | `app/src/main/kotlin\com\aura\ui\viewmodel\DocumentImportViewModel.kt` | `runCatching { repository.delete(id) } .onSuccess { _state.update { it.copy(messag` |
| 73 | `app/src/main/kotlin\com\aura\ui\viewmodel\HandsViewModel.kt` | `runCatching { repository.getAll() } .onSuccess { hands -> _state.value = _state.value.copy(hands =` |
| 103 | `app/src/main/kotlin\com\aura\ui\viewmodel\HandsViewModel.kt` | `runCatching { if (existing == null) repository.insert(hand) else repository.update(hand) scheduler.s` |
| 118 | `app/src/main/kotlin\com\aura\ui\viewmodel\HandsViewModel.kt` | `runCatching { repository.update(updated) if (updated.enabled) scheduler.schedule(updated) else sched` |
| 128 | `app/src/main/kotlin\com\aura\ui\viewmodel\HandsViewModel.kt` | `runCatching { repository.deleteById(hand.id) scheduler.cancel(hand.id) }.onSuccess { load() } .onFai` |
| 217 | `app/src/main/kotlin\com\aura\ui\viewmodel\HandsViewModel.kt` | `runCatching { repository.deleteRunHistory() } .onSuccess { _state.value = _state.value.copy(runs = e` |
| 257 | `app/src/main/kotlin\com\aura\ui\viewmodel\HomeViewModel.kt` | `runCatching { calendarReadTool.readTodaysEvents() } val reminders = reminderDao.observeUpcoming(now)` |
| 64 | `app/src/main/kotlin\com\aura\ui\viewmodel\KnowledgeGraphViewModel.kt` | `runCatching { val nodes = repository.recent(500) val stats = repository.stats() nodes to stats }.onS` |
| 106 | `app/src/main/kotlin\com\aura\ui\viewmodel\KnowledgeGraphViewModel.kt` | `runCatching { repository.getNeighbors(node.id) } .onSuccess { neighbors -> val labels = allN` |
| 166 | `app/src/main/kotlin\com\aura\ui\viewmodel\KnowledgeGraphViewModel.kt` | `runCatching { block() } .onSuccess { _state.update { it.copy(mutati` |
| 99 | `app/src/main/kotlin\com\aura\ui\viewmodel\MemoryViewModel.kt` | `runCatching { dreamConsolidationDao?.observeCount()?.collect { count -> _state.update { it.copy(drea` |
| 108 | `app/src/main/kotlin\com\aura\ui\viewmodel\MemoryViewModel.kt` | `runCatching { routineDao?.observeCount()?.collect { c -> _state.update { it.copy(routineCount = c) }` |
| 113 | `app/src/main/kotlin\com\aura\ui\viewmodel\MemoryViewModel.kt` | `runCatching { contradictionDao?.observeUnresolvedCount()?.collect { c -> _state.update { it.copy(con` |
| 356 | `app/src/main/kotlin\com\aura\ui\viewmodel\MemoryViewModel.kt` | `runCatching { memoryStore.store( content = content.trim(), source = "manual", category = category, i` |
| 389 | `app/src/main/kotlin\com\aura\ui\viewmodel\MemoryViewModel.kt` | `runCatching { feedbackDao.insert( MemoryFeedbackEntity( id = UUID.randomUUID().toString(), memoryId ` |
| 886 | `aura-core/src/main/kotlin\com\aura\agent\MemoryAugmentedAgenticLoop.kt` | `runCatching { extractProfileFromText(lastAssistant) } // .onFailure { android.util.Log.w("AgenticLoo` |
| 282 | `aura-core/src/main/kotlin\com\aura\hands\HandRepository.kt` | `runCatching { dao.insertRun(HandRun( id = runId, handId = hand.id, handName = hand.name, trigger = t` |
| 120 | `aura-core/src/main/kotlin\com\aura\proactive\MorningBriefBuilder.kt` | `runCatching { evolutionHooks?.onProactiveDelivered("mb_${now}", "morning_brief") } return androidx.w` |
| 74 | `aura-core/src/main/kotlin\com\aura\proactive\ProactiveBootstrap.kt` | `runCatching { engine.load() } } } // Seed builtin agents on first run. scope.launch {` |
| 276 | `aura-core/src/main/kotlin\com\aura\proactive\ProactiveBootstrap.kt` | `runCatching { mcpClientManager.connect(config, config.authToken) } } // Register all discovered MCP ` |
| 105 | `aura-core/src/main/kotlin\com\aura\proactive\ProactiveEvents.kt` | `runCatching { val persisted = dao.recent(100) _history.value = persisted.mapNotNull { it.toEvent() }` |
| 185 | `aura-core/src/main/kotlin\com\aura\proactive\ProactiveEvents.kt` | `runCatching { interactionDao.insert( ProactiveInteractionEntity( eventId = eventId, action = action,` |
| 194 | `aura-core/src/main/kotlin\com\aura\proactive\ProactiveEvents.kt` | `runCatching { when (action) { "dismissed" -> evolutionHooks?.onProactiveDismissed(eventId.toString()` |
| 102 | `aura-core/src/main/kotlin\com\aura\tools\SendEmailBackgroundTool.kt` | `runCatching { val props = Properties().apply { put("mail.smtp.auth", "true") put("mail.smtp.starttls` |
