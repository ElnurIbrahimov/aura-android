package com.aura.tools

import com.aura.agent.ToolRegistry
import com.aura.integrations.google.GoogleGmailTool
import com.aura.integrations.google.GoogleCalendarTool
import com.aura.integrations.google.GoogleDriveTool
import com.aura.integrations.microsoft.MicrosoftMailTool
import com.aura.integrations.microsoft.MicrosoftCalendarTool
import com.aura.integrations.microsoft.MicrosoftFilesTool
import com.aura.tools.evolution.ApproveEvolutionProposalTool
import com.aura.tools.evolution.RollbackEvolutionTool
import com.aura.tools.evolution.TriggerEvolutionRunTool
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ToolsModule {

    @Provides
    @Singleton
    fun provideToolRegistry(
        webSearch: WebSearchTool,
        braveSearch: BraveSearchTool,
        tavilySearch: TavilySearchTool,
        wikipediaSearch: WikipediaSearchTool,
        wikipediaRead: WikipediaReadTool,
        ddgInstantAnswer: DdgInstantAnswerTool,
        searxngSearch: SearxngSearchTool,
        jinaReaderFree: JinaReaderFreeTool,
        parallelResearch: ParallelResearchTool,
        notifications: NotificationsTool,
        location: LocationNowTool,
        share: ShareIntentTool,
        calendarRead: CalendarReadTool,
        calendarWrite: CalendarWriteTool,
        contactsSearch: ContactsSearchTool,
        setReminder: SetReminderTool,
        scheduleTask: ScheduleTaskTool,
        getCurrentTime: GetCurrentTimeTool,
        remember: RememberTool,
        recall: RecallTool,
        appLauncher: AppLauncherTool,
        systemVolume: SystemVolumeTool,
        photoLibrary: PhotoLibraryTool,
        biometricPrompt: BiometricPromptTool,
        clipboardRead: ClipboardReadTool,
        clipboardWrite: ClipboardWriteTool,
        openBrowserTab: OpenBrowserTabTool,
        codeInterpreter: CodeInterpreterTool,
        httpFileRead: HttpFileReadTool,
        httpFileWrite: HttpFileWriteTool,
        ttsSpeak: TtsSpeakTool,
        captureScreen: CaptureScreenTool,
        sendEmailBackground: SendEmailBackgroundTool,

        batteryState: BatteryStateTool,
        networkState: NetworkStateTool,
        dndMode: DndModeTool,
        taskManager: TaskManagerTool,
        notificationList: NotificationListTool,
        screenRead: ScreenReadTool,
        screenAct: ScreenActTool,
        firecrawlFetch: FirecrawlFetchTool,
        deepResearch: DeepResearchTool,
        vision: VisionTool,
        imageGen: ImageGenTool,
        imageGenCapability: ImageGenCapabilityTool,
        webSearchCapability: WebSearchCapabilityTool,
        mediaCapability: MediaCapabilityTools,
        transcription: TranscriptionTool,
        runHand: RunHandTool,
        knowledgeGraph: KnowledgeGraphTool,
        kgQuery: KgQueryTool,
        emailSend: EmailSendTool,
        smsSend: SmsSendTool,
        translate: TranslateTool,
        timer: TimerTool,
        weather: WeatherTool,
        creativeReadProject: CreativeReadProjectTool,
        creativeAddWorldItem: CreativeAddWorldItemTool,
        creativeEngine: CreativeEngineTool,
        canonQuery: CanonQueryTool,
        livingWorldQuery: LivingWorldQueryTool,
        indexDocument: IndexDocumentTool,
        searchDocuments: SearchDocumentsTool,
        queryWorldModel: QueryWorldModelTool,
        queryTaste: QueryTasteTool,
        projectState: ProjectStateTool,
        useSkill: UseSkillTool,
        listSkills: ListSkillsTool,
        approveProposal: ApproveEvolutionProposalTool,
        rollbackEvolution: RollbackEvolutionTool,
        triggerEvolution: TriggerEvolutionRunTool,
        delegateToAgent: DelegateToAgentTool,
        runCouncil: RunCouncilTool,
        runLifeCouncil: com.aura.agent.council.RunLifeCouncilTool,
        gmail: GoogleGmailTool,
        googleCalendar: GoogleCalendarTool,
        googleDrive: GoogleDriveTool,
        outlookMail: MicrosoftMailTool,
        outlookCalendar: MicrosoftCalendarTool,
        onedrive: MicrosoftFilesTool,
    ): ToolRegistry {
        val registry = ToolRegistry()
        registry.register(webSearch.tool)
        registry.register(parallelResearch.tool)
        registry.register(braveSearch.tool)
        registry.register(tavilySearch.tool)
        registry.register(wikipediaSearch.tool)
        registry.register(wikipediaRead.tool)
        registry.register(ddgInstantAnswer.tool)
        registry.register(searxngSearch.tool)
        registry.register(jinaReaderFree.tool)
        registry.register(notifications.tool)
        registry.register(location.tool)
        registry.register(share.tool)
        registry.register(calendarRead.tool)
        registry.register(calendarWrite.tool)
        registry.register(contactsSearch.tool)
        registry.register(setReminder.tool)
        registry.register(scheduleTask.tool)
        registry.register(getCurrentTime.tool)
        registry.register(remember.tool)
        registry.register(recall.tool)
        registry.register(appLauncher.tool)
        registry.register(systemVolume.tool)
        registry.register(photoLibrary.tool)
        registry.register(biometricPrompt.tool)

        registry.register(clipboardRead.tool)
        registry.register(clipboardWrite.tool)
        registry.register(openBrowserTab.tool)
        registry.register(codeInterpreter.tool)
        registry.register(httpFileRead.tool)
        registry.register(httpFileWrite.tool)
        registry.register(ttsSpeak.tool)
        registry.register(captureScreen.tool)
        registry.register(sendEmailBackground.tool)

        registry.register(batteryState.tool)
        registry.register(networkState.tool)
        registry.register(dndMode.tool)
        registry.register(taskManager.tool)
        // Registered unconditionally so the in-chat enable flow can fire, but
        // hidden from the model until the master switch is on — see
        // filterUnavailableTools in MemoryAugmentedAgenticLoop. Off by default
        // means zero token cost and zero reachability for anyone who never
        // opts in.
        registry.register(screenRead.tool)
        registry.register(screenAct.tool)
        registry.register(notificationList.tool)
        registry.register(firecrawlFetch.tool)
        registry.register(deepResearch.tool)
        registry.register(vision.tool)
        registry.register(imageGen.tool)
        registry.register(imageGenCapability.tool)
        registry.register(webSearchCapability.tool)
        registry.register(mediaCapability.ttsTool)
        registry.register(mediaCapability.videoTool)
        registry.register(mediaCapability.world3dTool)
        registry.register(transcription.tool)
        registry.register(runHand.tool)
        registry.register(knowledgeGraph.tool)
        registry.register(kgQuery.tool)
        registry.register(emailSend.tool)
        registry.register(smsSend.tool)
        registry.register(translate.tool)
        registry.register(timer.tool)
        registry.register(weather.tool)
        registry.register(creativeReadProject.tool)
        registry.register(creativeAddWorldItem.tool)
        registry.register(creativeEngine.tool)
        registry.register(canonQuery.tool)
        registry.register(livingWorldQuery.tool)
        registry.register(indexDocument.tool)
        registry.register(searchDocuments.tool)
        registry.register(queryWorldModel.tool)
        registry.register(queryTaste.tool)
        registry.register(projectState.tool)
        registry.register(useSkill.tool)
        registry.register(listSkills.tool)
        registry.register(approveProposal.tool)
        registry.register(rollbackEvolution.tool)
        registry.register(triggerEvolution.tool)
        registry.register(delegateToAgent.tool)
        registry.register(runCouncil.tool)
        registry.register(runLifeCouncil.tool)
        registry.register(gmail.tool)
        registry.register(googleCalendar.tool)
        registry.register(googleDrive.tool)
        registry.register(outlookMail.tool)
        registry.register(outlookCalendar.tool)
        registry.register(onedrive.tool)
        return registry
    }
}
