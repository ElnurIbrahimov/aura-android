package com.aura.memory

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aura.data.RoomConfig
import com.aura.creative.CreativeProjectDao
import com.aura.creative.CreativeArtifactDao
import com.aura.creative.CreativeRevisionDao
import com.aura.creative.CreativeBranchDao
import com.aura.creative.CreativeGenerationJobDao
import com.aura.creative.CanonFactDao
import com.aura.creative.CreativeSimulationDao
import com.aura.creative.ContinuityIssueDao
import com.aura.creative.ArtifactDependencyDao
import com.aura.world.BeliefDao
import com.aura.world.EvidenceDao
import com.aura.world.WorldEventDao
import com.aura.world.OpportunityDao
import com.aura.taste.PreferenceSignalDao
import com.aura.taste.StyleProfileDao
import com.aura.taste.ReferenceIdentityDao
import com.aura.taste.RoutingOutcomeDao
import com.aura.documents.DocumentDao
import com.aura.documents.DocumentChunkDao
import com.aura.documents.DocumentChunkEntity
import com.aura.providers.ProviderKeys
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MemoryModule {

    @Provides
    @Singleton
    fun provideGeneratedMediaDao(db: MemoryDatabase): com.aura.media.GeneratedMediaDao =
        db.generatedMediaDao()

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MemoryDatabase =
        RoomConfig.builder(
            context,
            MemoryDatabase::class.java,
            "aura-memory.db",
            migrations = arrayOf(MemoryMigrations.MIGRATION_1_2, MemoryMigrations.MIGRATION_2_3, MemoryMigrations.MIGRATION_3_4, MemoryMigrations.MIGRATION_4_5, MemoryMigrations.MIGRATION_5_6, MemoryMigrations.MIGRATION_6_7, MemoryMigrations.MIGRATION_7_8, MemoryMigrations.MIGRATION_8_9, MemoryMigrations.MIGRATION_9_10, MemoryMigrations.MIGRATION_10_11, MemoryMigrations.MIGRATION_11_12, MemoryMigrations.MIGRATION_12_13, MemoryMigrations.MIGRATION_13_14, MemoryMigrations.MIGRATION_14_15, MemoryMigrations.MIGRATION_15_16, MemoryMigrations.MIGRATION_16_17, MemoryMigrations.MIGRATION_17_18, MemoryMigrations.MIGRATION_18_19, MemoryMigrations.MIGRATION_19_20, MemoryMigrations.MIGRATION_20_21, MemoryMigrations.MIGRATION_21_22, MemoryMigrations.MIGRATION_22_23, MemoryMigrations.MIGRATION_23_24, MemoryMigrations.MIGRATION_24_25, MemoryMigrations.MIGRATION_25_26, MemoryMigrations.MIGRATION_26_27, MemoryMigrations.MIGRATION_27_28, MemoryMigrations.MIGRATION_28_29, MemoryMigrations.MIGRATION_29_30, MemoryMigrations.MIGRATION_30_31),
            // Room's createAllTables builds the FTS virtual table but not the
            // triggers that fill it, so a fresh install needs this or the index
            // stays permanently empty — silently, since an empty index is
            // indistinguishable from "no memory matched".
            callback = MemoryFtsSchema.triggerCallback,
        ).build()

    @Provides
    fun provideMemoryFtsDao(db: MemoryDatabase): MemoryFtsDao = db.memoryFtsDao()

    @Provides
    fun provideCorrectionDao(db: MemoryDatabase): CorrectionDao = db.correctionDao()

    @Provides
    fun provideOpenQuestionDao(db: MemoryDatabase): com.aura.curiosity.OpenQuestionDao = db.openQuestionDao()

    @Provides
    fun provideRetrievalLabelDao(db: MemoryDatabase): RetrievalLabelDao = db.retrievalLabelDao()

    @Provides
    fun provideProjectDao(db: MemoryDatabase): com.aura.projects.ProjectDao = db.projectDao()

    @Provides
    fun provideProjectNoteDao(db: MemoryDatabase): com.aura.projects.ProjectNoteDao = db.projectNoteDao()

    @Provides
    fun provideClaimResolutionDao(db: MemoryDatabase): com.aura.calibration.ClaimResolutionDao =
        db.claimResolutionDao()

    @Provides
    fun provideMemoryDao(db: MemoryDatabase): MemoryDao = db.memoryDao()

    @Provides
    fun provideMemoryEditDao(db: MemoryDatabase): MemoryEditDao = db.memoryEditDao()

    @Provides
    fun provideDocumentDao(db: MemoryDatabase): DocumentDao = db.documentDao()

    @Provides
    fun provideCreativeProjectDao(db: MemoryDatabase): CreativeProjectDao = db.creativeProjectDao()

    @Provides
    fun provideDocumentChunkDao(db: MemoryDatabase): DocumentChunkDao = db.documentChunkDao()

    @Provides
    fun provideCreativeArtifactDao(db: MemoryDatabase): CreativeArtifactDao = db.creativeArtifactDao()

    @Provides
    fun provideCreativeRevisionDao(db: MemoryDatabase): CreativeRevisionDao = db.creativeRevisionDao()

    @Provides
    fun provideCreativeBranchDao(db: MemoryDatabase): CreativeBranchDao = db.creativeBranchDao()

    @Provides
    fun provideCreativeGenerationJobDao(db: MemoryDatabase): CreativeGenerationJobDao = db.creativeGenerationJobDao()

    @Provides
    fun provideCanonFactDao(db: MemoryDatabase): CanonFactDao = db.canonFactDao()

    @Provides
    fun provideCreativeSimulationDao(db: MemoryDatabase): CreativeSimulationDao = db.creativeSimulationDao()

    @Provides
    fun provideLivingWorldDao(db: MemoryDatabase): com.aura.creative.livingworld.LivingWorldDao =
        db.livingWorldDao()

    @Provides
    fun provideLivingEventDao(db: MemoryDatabase): com.aura.creative.livingworld.LivingEventDao =
        db.livingEventDao()

    @Provides
    fun provideContinuityIssueDao(db: MemoryDatabase): ContinuityIssueDao = db.continuityIssueDao()

    @Provides
    fun provideArtifactDependencyDao(db: MemoryDatabase): ArtifactDependencyDao = db.artifactDependencyDao()

    @Provides
    fun provideBeliefDao(db: MemoryDatabase): BeliefDao = db.beliefDao()

    @Provides
    fun provideEvidenceDao(db: MemoryDatabase): EvidenceDao = db.evidenceDao()

    @Provides
    fun provideWorldEventDao(db: MemoryDatabase): WorldEventDao = db.worldEventDao()

    @Provides
    fun provideOpportunityDao(db: MemoryDatabase): OpportunityDao = db.opportunityDao()

    @Provides
    fun providePreferenceSignalDao(db: MemoryDatabase): PreferenceSignalDao = db.preferenceSignalDao()

    @Provides
    fun provideStyleProfileDao(db: MemoryDatabase): StyleProfileDao = db.styleProfileDao()

    @Provides
    fun provideReferenceIdentityDao(db: MemoryDatabase): ReferenceIdentityDao = db.referenceIdentityDao()

    @Provides
    fun provideRoutingOutcomeDao(db: MemoryDatabase): RoutingOutcomeDao = db.routingOutcomeDao()

    @Provides
    fun provideMemoryFeedbackDao(db: MemoryDatabase): MemoryFeedbackDao = db.memoryFeedbackDao()

    @Provides
    fun providePlaceVisitDao(db: MemoryDatabase): com.aura.place.PlaceVisitDao = db.placeVisitDao()

    @Provides
    fun provideCreativeAnalysisDao(db: MemoryDatabase): com.aura.creative.CreativeAnalysisDao =
        db.creativeAnalysisDao()

    @Provides
    @Singleton
    fun provideLocalEmbedder(): LocalEmbedder = LocalEmbedder()

    @Provides
    @Singleton
    fun provideWordPieceTokenizer(
        @ApplicationContext context: Context,
    ): com.aura.memory.onnx.WordPieceTokenizer =
        com.aura.memory.onnx.WordPieceTokenizer(
            context.assets.open("nomic_vocab.txt").bufferedReader().readLines(),
        )

    @Provides
    @Singleton
    fun provideOnDeviceEmbedder(
        modelStore: com.aura.memory.onnx.EmbeddingModelStore,
        tokenizer: com.aura.memory.onnx.WordPieceTokenizer,
    ): com.aura.memory.onnx.OnDeviceEmbedder =
        com.aura.memory.onnx.OnDeviceEmbedder(modelStore.modelFile, tokenizer)

    /**
     * On-device first, then cloud, then the hash sketch.
     *
     * The hash was never a semantic model — SHA-256 over character n-grams — so two
     * sentences meaning the same thing scored no better than two unrelated ones. It stays
     * as the floor, because something has to answer before the 137 MB model has finished
     * downloading, and every vector it produces is tagged as its own so it is excluded
     * from scoring and repaired later rather than silently mixed in.
     */
    @Provides
    @Singleton
    fun provideEmbedder(
        localEmbedder: LocalEmbedder,
        providerKeys: ProviderKeys,
        httpClient: OkHttpClient,
        onDevice: com.aura.memory.onnx.OnDeviceEmbedder,
        modelStore: com.aura.memory.onnx.EmbeddingModelStore,
    ): Embedder = com.aura.memory.onnx.RoutedEmbedder(
        onDevice = onDevice,
        fallback = CloudEmbedder(localEmbedder, providerKeys, httpClient),
        modelStore = modelStore,
    )

    @Provides
    @Singleton
    fun provideWriteGate(): WriteGate = WriteGate()

    /**
     * Dagger does not honour Kotlin default arguments on an `@Inject`
     * constructor — it requires a binding for every parameter — so
     * [MemoryStore]'s `config` default is invisible to the graph and this
     * binding is what actually supplies it in production.
     *
     * Deliberately [RetrievalConfig.DEFAULT] and not something read from
     * preferences. These are values for an eval harness to sweep, not for a
     * user to set; promote an individual knob to Settings only once it has
     * earned it.
     */
    @Provides
    @Singleton
    fun provideRetrievalConfig(): RetrievalConfig = RetrievalConfig.DEFAULT
}
