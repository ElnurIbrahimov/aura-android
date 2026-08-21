package com.aura.tools

import com.aura.agent.Tool
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
import dagger.multibindings.IntoSet
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ToolsModule {

    /**
     * The registry, built from every [Tool] contributed to the set below.
     *
     * This was one @Provides with eighty parameters and eighty-two
     * `registry.register` calls, the two lists eighty lines apart and
     * nothing checking they agreed. Adding a tool meant two coordinated
     * edits, and forgetting the second one produced a tool that was
     * constructed, injected, and invisible to the model — the exact shape
     * of "present, tested and inert" this repo keeps finding.
     *
     * Multibinding makes it one edit the compiler checks.
     * `ProviderModule` has used @IntoMap for the same reason since it was
     * written; this file simply never followed.
     *
     * Registration order is irrelevant and always was: [ToolRegistry.register]
     * writes into a name-keyed map and [ToolRegistry.definitions] sorts by
     * name, with a KDoc explaining that the sort is the contract because
     * providers cache on the serialised tool array. A set with no defined
     * iteration order is therefore safe here, and would not have been if
     * that sort did not exist.
     */
    @Provides
    @Singleton
    fun provideToolRegistry(tools: Set<@JvmSuppressWildcards Tool>): ToolRegistry =
        ToolRegistry().apply { tools.forEach(::register) }

    @Provides
    @IntoSet
    fun provideWebSearch(t: WebSearchTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideParallelResearch(t: ParallelResearchTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideBraveSearch(t: BraveSearchTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideTavilySearch(t: TavilySearchTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideWikipediaSearch(t: WikipediaSearchTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideWikipediaRead(t: WikipediaReadTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideDdgInstantAnswer(t: DdgInstantAnswerTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideSearxngSearch(t: SearxngSearchTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideJinaReaderFree(t: JinaReaderFreeTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideNotifications(t: NotificationsTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideLocation(t: LocationNowTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideShare(t: ShareIntentTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideCalendarRead(t: CalendarReadTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideCalendarWrite(t: CalendarWriteTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideContactsSearch(t: ContactsSearchTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideSetReminder(t: SetReminderTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideScheduleTask(t: ScheduleTaskTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideGetCurrentTime(t: GetCurrentTimeTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideRemember(t: RememberTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideRecall(t: RecallTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideAppLauncher(t: AppLauncherTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideSystemVolume(t: SystemVolumeTool): Tool = t.tool

    @Provides
    @IntoSet
    fun providePhotoLibrary(t: PhotoLibraryTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideBiometricPrompt(t: BiometricPromptTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideClipboardRead(t: ClipboardReadTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideClipboardWrite(t: ClipboardWriteTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideOpenBrowserTab(t: OpenBrowserTabTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideCodeInterpreter(t: CodeInterpreterTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideHttpFileRead(t: HttpFileReadTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideHttpFileWrite(t: HttpFileWriteTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideTtsSpeak(t: TtsSpeakTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideCaptureScreen(t: CaptureScreenTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideSendEmailBackground(t: SendEmailBackgroundTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideBatteryState(t: BatteryStateTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideNetworkState(t: NetworkStateTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideDndMode(t: DndModeTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideTaskManager(t: TaskManagerTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideScreenRead(t: ScreenReadTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideScreenAct(t: ScreenActTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideNotificationList(t: NotificationListTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideFirecrawlFetch(t: FirecrawlFetchTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideDeepResearch(t: DeepResearchTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideVision(t: VisionTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideImageGen(t: ImageGenTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideImageGenCapability(t: ImageGenCapabilityTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideWebSearchCapability(t: WebSearchCapabilityTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideMediaCapabilityTtsTool(t: MediaCapabilityTools): Tool = t.ttsTool

    @Provides
    @IntoSet
    fun provideMediaCapabilityVideoTool(t: MediaCapabilityTools): Tool = t.videoTool

    @Provides
    @IntoSet
    fun provideMediaCapabilityWorld3dTool(t: MediaCapabilityTools): Tool = t.world3dTool

    @Provides
    @IntoSet
    fun provideTranscription(t: TranscriptionTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideRunHand(t: RunHandTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideKnowledgeGraph(t: KnowledgeGraphTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideKgQuery(t: KgQueryTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideEmailSend(t: EmailSendTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideSmsSend(t: SmsSendTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideTranslate(t: TranslateTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideTimer(t: TimerTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideWeather(t: WeatherTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideCreativeReadProject(t: CreativeReadProjectTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideCreativeAddWorldItem(t: CreativeAddWorldItemTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideCreativeEngine(t: CreativeEngineTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideCanonQuery(t: CanonQueryTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideLivingWorldQuery(t: LivingWorldQueryTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideIndexDocument(t: IndexDocumentTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideSearchDocuments(t: SearchDocumentsTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideQueryWorldModel(t: QueryWorldModelTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideQueryTaste(t: QueryTasteTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideProjectState(t: ProjectStateTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideUseSkill(t: UseSkillTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideListSkills(t: ListSkillsTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideApproveProposal(t: ApproveEvolutionProposalTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideRollbackEvolution(t: RollbackEvolutionTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideTriggerEvolution(t: TriggerEvolutionRunTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideDelegateToAgent(t: DelegateToAgentTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideRunCouncil(t: RunCouncilTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideRunLifeCouncil(t: com.aura.agent.council.RunLifeCouncilTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideGmail(t: GoogleGmailTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideGoogleCalendar(t: GoogleCalendarTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideGoogleDrive(t: GoogleDriveTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideOutlookMail(t: MicrosoftMailTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideOutlookCalendar(t: MicrosoftCalendarTool): Tool = t.tool

    @Provides
    @IntoSet
    fun provideOnedrive(t: MicrosoftFilesTool): Tool = t.tool
}
