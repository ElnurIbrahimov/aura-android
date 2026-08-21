package com.aura.memory

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Every MemoryDatabase migration, and nothing else.
 *
 * These lived in [MemoryModule], which made that file 1,195 lines of which
 * roughly 970 were migrations and the remaining 225 were the dependency graph.
 * Two unrelated things, one file, and the DI module was the harder of the two
 * to find anything in.
 *
 * The split is mechanical: the objects are unchanged, only their home and
 * their qualified name moved. They stay in `com.aura.memory` so nothing else
 * needed an import.
 *
 * A migration here is immutable once shipped. Editing one changes what a
 * device upgrading from that version receives, which is not the same database
 * the schema export for the next version describes — see
 * `MigrationReplayTest`, which replays each of these against the committed
 * exports for exactly that reason.
 */
object MemoryMigrations {

    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS kg_nodes (
                    id TEXT NOT NULL PRIMARY KEY,
                    label TEXT NOT NULL,
                    type TEXT NOT NULL,
                    properties TEXT NOT NULL,
                    confidence REAL NOT NULL,
                    sourceTurnId TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    accessCount INTEGER NOT NULL,
                    lastAccessed INTEGER NOT NULL
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS index_kg_nodes_label ON kg_nodes(label)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_kg_nodes_type ON kg_nodes(type)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_kg_nodes_label_type ON kg_nodes(label, type)")
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS kg_edges (
                    id TEXT NOT NULL PRIMARY KEY,
                    type TEXT NOT NULL,
                    sourceId TEXT NOT NULL,
                    targetId TEXT NOT NULL,
                    weight REAL NOT NULL,
                    properties TEXT NOT NULL,
                    confidence REAL NOT NULL,
                    sourceTurnId TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    lastReinforced INTEGER NOT NULL,
                    FOREIGN KEY(sourceId) REFERENCES kg_nodes(id) ON DELETE CASCADE,
                    FOREIGN KEY(targetId) REFERENCES kg_nodes(id) ON DELETE CASCADE
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS index_kg_edges_sourceId ON kg_edges(sourceId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_kg_edges_targetId ON kg_edges(targetId)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_kg_edges_sourceId_targetId_type ON kg_edges(sourceId, targetId, type)")
        }
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // memory_edits table: audit trail for memory edits.
            // ForeignKey CASCADE on memoryId so deleting a memory
            // also cleans up its edit history.
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS memory_edits (
                    id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                    memoryId TEXT NOT NULL,
                    oldContent TEXT NOT NULL,
                    newContent TEXT NOT NULL,
                    oldCategory TEXT NOT NULL,
                    newCategory TEXT NOT NULL,
                    editedAt INTEGER NOT NULL DEFAULT 0,
                    editedBy TEXT NOT NULL DEFAULT 'user',
                    FOREIGN KEY(memoryId) REFERENCES memories(id) ON DELETE CASCADE
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_edits_memoryId ON memory_edits(memoryId)")
        }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE memories ADD COLUMN sourceConversationId TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE memories ADD COLUMN sourceTurnTimestamp INTEGER NOT NULL DEFAULT 0")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_memories_sourceConversationId ON memories(sourceConversationId)")
            db.execSQL("ALTER TABLE kg_nodes ADD COLUMN sourceConversationId TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE kg_nodes ADD COLUMN sourceTurnTimestamp INTEGER NOT NULL DEFAULT 0")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_kg_nodes_sourceConversationId ON kg_nodes(sourceConversationId)")
            db.execSQL("ALTER TABLE kg_edges ADD COLUMN sourceConversationId TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE kg_edges ADD COLUMN sourceTurnTimestamp INTEGER NOT NULL DEFAULT 0")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_kg_edges_sourceConversationId ON kg_edges(sourceConversationId)")
        }
    }

    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS documents (
                    id TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    mimeType TEXT NOT NULL,
                    sourceUri TEXT NOT NULL,
                    importedAt INTEGER NOT NULL,
                    characterCount INTEGER NOT NULL,
                    chunkCount INTEGER NOT NULL
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS index_documents_name ON documents(name)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_documents_importedAt ON documents(importedAt)")
        }
    }

    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS creative_projects (
                    id TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    description TEXT NOT NULL,
                    genre TEXT NOT NULL,
                    tone TEXT NOT NULL,
                    worldJson TEXT NOT NULL,
                    templateId TEXT NOT NULL,
                    metadataJson TEXT NOT NULL,
                    turnCount INTEGER NOT NULL,
                    lastSessionEnded INTEGER NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS index_creative_projects_updatedAt ON creative_projects(updatedAt)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_creative_projects_name ON creative_projects(name)")
        }
    }

    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // First-class document chunks table with embedding metadata
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS document_chunks (
                    id TEXT NOT NULL PRIMARY KEY,
                    documentId TEXT NOT NULL,
                    ordinal INTEGER NOT NULL,
                    charStart INTEGER NOT NULL,
                    charEnd INTEGER NOT NULL,
                    pageNumber INTEGER NOT NULL DEFAULT 0,
                    text TEXT NOT NULL,
                    contentHash TEXT NOT NULL,
                    embedding BLOB,
                    embeddingModel TEXT,
                    embeddingVersion INTEGER NOT NULL DEFAULT 0,
                    embeddedAt INTEGER NOT NULL DEFAULT 0,
                    FOREIGN KEY(documentId) REFERENCES documents(id) ON DELETE CASCADE
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS index_document_chunks_documentId ON document_chunks(documentId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_document_chunks_documentId_ordinal ON document_chunks(documentId, ordinal)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_document_chunks_contentHash ON document_chunks(contentHash)")

            // Add embedding model/version metadata to memories
            db.execSQL("ALTER TABLE memories ADD COLUMN embeddingModel TEXT")
            db.execSQL("ALTER TABLE memories ADD COLUMN embeddingVersion INTEGER NOT NULL DEFAULT 0")

            // Add indexing status to documents
            db.execSQL("ALTER TABLE documents ADD COLUMN indexStatus TEXT NOT NULL DEFAULT 'pending'")
            db.execSQL("ALTER TABLE documents ADD COLUMN indexError TEXT NOT NULL DEFAULT ''")
        }
    }

    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Creative artifacts table
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS creative_artifacts (
                    id TEXT NOT NULL PRIMARY KEY,
                    projectId TEXT NOT NULL,
                    branchId TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    title TEXT NOT NULL,
                    currentRevisionId TEXT,
                    previewText TEXT NOT NULL DEFAULT '',
                    mimeType TEXT NOT NULL DEFAULT '',
                    storageUri TEXT,
                    contentHash TEXT NOT NULL DEFAULT '',
                    status TEXT NOT NULL DEFAULT 'pending',
                    metadataJson TEXT NOT NULL DEFAULT '{}',
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    FOREIGN KEY(projectId) REFERENCES creative_projects(id) ON DELETE CASCADE
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS index_creative_artifacts_projectId ON creative_artifacts(projectId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_creative_artifacts_projectId_kind ON creative_artifacts(projectId, kind)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_creative_artifacts_status ON creative_artifacts(status)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_creative_artifacts_updatedAt ON creative_artifacts(updatedAt)")

            // Creative revisions table
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS creative_revisions (
                    id TEXT NOT NULL PRIMARY KEY,
                    artifactId TEXT NOT NULL,
                    branchId TEXT NOT NULL,
                    parentRevisionId TEXT,
                    contentText TEXT NOT NULL DEFAULT '',
                    storageUri TEXT,
                    contentHash TEXT NOT NULL DEFAULT '',
                    authorKind TEXT NOT NULL DEFAULT 'manual',
                    providerPrefix TEXT NOT NULL DEFAULT '',
                    modelId TEXT NOT NULL DEFAULT '',
                    prompt TEXT NOT NULL DEFAULT '',
                    settingsJson TEXT NOT NULL DEFAULT '{}',
                    createdAt INTEGER NOT NULL,
                    FOREIGN KEY(artifactId) REFERENCES creative_artifacts(id) ON DELETE CASCADE
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS index_creative_revisions_artifactId ON creative_revisions(artifactId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_creative_revisions_branchId ON creative_revisions(branchId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_creative_revisions_parentRevisionId ON creative_revisions(parentRevisionId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_creative_revisions_createdAt ON creative_revisions(createdAt)")

            // Creative branches table
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS creative_branches (
                    id TEXT NOT NULL PRIMARY KEY,
                    projectId TEXT NOT NULL,
                    name TEXT NOT NULL,
                    baseRevisionId TEXT,
                    headRevisionId TEXT,
                    status TEXT NOT NULL DEFAULT 'active',
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    FOREIGN KEY(projectId) REFERENCES creative_projects(id) ON DELETE CASCADE
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS index_creative_branches_projectId ON creative_branches(projectId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_creative_branches_status ON creative_branches(status)")

            // Creative generation jobs table
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS creative_generation_jobs (
                    id TEXT NOT NULL PRIMARY KEY,
                    projectId TEXT NOT NULL,
                    branchId TEXT NOT NULL,
                    capabilityKind TEXT NOT NULL,
                    requestJson TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'queued',
                    progress INTEGER NOT NULL DEFAULT 0,
                    providerPrefix TEXT NOT NULL DEFAULT '',
                    providerOperationId TEXT,
                    resultArtifactIdsJson TEXT NOT NULL DEFAULT '[]',
                    errorCode TEXT NOT NULL DEFAULT '',
                    errorMessage TEXT NOT NULL DEFAULT '',
                    attempts INTEGER NOT NULL DEFAULT 0,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    FOREIGN KEY(projectId) REFERENCES creative_projects(id) ON DELETE CASCADE
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS index_creative_generation_jobs_projectId ON creative_generation_jobs(projectId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_creative_generation_jobs_branchId ON creative_generation_jobs(branchId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_creative_generation_jobs_status ON creative_generation_jobs(status)")
        }
    }

    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Canon facts table
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS canon_facts (
                    id TEXT NOT NULL PRIMARY KEY,
                    projectId TEXT NOT NULL,
                    branchId TEXT NOT NULL,
                    subjectType TEXT NOT NULL,
                    subjectId TEXT NOT NULL,
                    predicate TEXT NOT NULL,
                    valueJson TEXT NOT NULL,
                    validFrom INTEGER NOT NULL DEFAULT 0,
                    validTo INTEGER NOT NULL DEFAULT 0,
                    confidence REAL NOT NULL DEFAULT 1.0,
                    sourceRevisionId TEXT,
                    status TEXT NOT NULL DEFAULT 'active',
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    FOREIGN KEY(projectId) REFERENCES creative_projects(id) ON DELETE CASCADE
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS index_canon_facts_projectId ON canon_facts(projectId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_canon_facts_projectId_branchId ON canon_facts(projectId, branchId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_canon_facts_subjectType_subjectId ON canon_facts(subjectType, subjectId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_canon_facts_predicate ON canon_facts(predicate)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_canon_facts_status ON canon_facts(status)")

            // Creative simulations table
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS creative_simulations (
                    id TEXT NOT NULL PRIMARY KEY,
                    projectId TEXT NOT NULL,
                    branchId TEXT NOT NULL,
                    premise TEXT NOT NULL,
                    assumptionsJson TEXT NOT NULL DEFAULT '[]',
                    narrative TEXT NOT NULL DEFAULT '',
                    stateDeltaJson TEXT NOT NULL DEFAULT '[]',
                    causalGraphJson TEXT NOT NULL DEFAULT '[]',
                    confidence REAL NOT NULL DEFAULT 1.0,
                    contradictionsJson TEXT NOT NULL DEFAULT '[]',
                    createdAt INTEGER NOT NULL,
                    canonizedAt INTEGER NOT NULL DEFAULT 0,
                    canonizedFactIdsJson TEXT NOT NULL DEFAULT '[]',
                    FOREIGN KEY(projectId) REFERENCES creative_projects(id) ON DELETE CASCADE
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS index_creative_simulations_projectId ON creative_simulations(projectId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_creative_simulations_projectId_branchId ON creative_simulations(projectId, branchId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_creative_simulations_canonizedAt ON creative_simulations(canonizedAt)")

            // Continuity issues table
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS continuity_issues (
                    id TEXT NOT NULL PRIMARY KEY,
                    projectId TEXT NOT NULL,
                    branchId TEXT NOT NULL,
                    artifactId TEXT,
                    category TEXT NOT NULL,
                    severity TEXT NOT NULL,
                    message TEXT NOT NULL,
                    evidenceFactIdsJson TEXT NOT NULL DEFAULT '[]',
                    suggestedPatchJson TEXT NOT NULL DEFAULT '{}',
                    status TEXT NOT NULL DEFAULT 'open',
                    createdAt INTEGER NOT NULL,
                    resolvedAt INTEGER,
                    resolvedBy TEXT NOT NULL DEFAULT ''
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS index_continuity_issues_projectId ON continuity_issues(projectId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_continuity_issues_projectId_branchId ON continuity_issues(projectId, branchId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_continuity_issues_artifactId ON continuity_issues(artifactId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_continuity_issues_severity ON continuity_issues(severity)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_continuity_issues_status ON continuity_issues(status)")

            // Artifact dependencies table
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS artifact_dependencies (
                    id TEXT NOT NULL PRIMARY KEY,
                    sourceArtifactId TEXT NOT NULL,
                    targetArtifactId TEXT NOT NULL,
                    relation TEXT NOT NULL,
                    invalidationPolicy TEXT NOT NULL DEFAULT 'mark_review',
                    createdAt INTEGER NOT NULL,
                    FOREIGN KEY(sourceArtifactId) REFERENCES creative_artifacts(id) ON DELETE CASCADE,
                    FOREIGN KEY(targetArtifactId) REFERENCES creative_artifacts(id) ON DELETE CASCADE
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS index_artifact_dependencies_sourceArtifactId ON artifact_dependencies(sourceArtifactId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_artifact_dependencies_targetArtifactId ON artifact_dependencies(targetArtifactId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_artifact_dependencies_relation ON artifact_dependencies(relation)")
        }
    }

    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Beliefs table
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS beliefs (
                    id TEXT NOT NULL PRIMARY KEY,
                    subject TEXT NOT NULL,
                    predicate TEXT NOT NULL,
                    valueJson TEXT NOT NULL,
                    confidence REAL NOT NULL DEFAULT 1.0,
                    validFrom INTEGER NOT NULL DEFAULT 0,
                    validTo INTEGER NOT NULL DEFAULT 0,
                    status TEXT NOT NULL DEFAULT 'active',
                    supersededBy TEXT,
                    privacyClass TEXT NOT NULL DEFAULT 'personal',
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    lastVerifiedAt INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS index_beliefs_subject ON beliefs(subject)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_beliefs_predicate ON beliefs(predicate)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_beliefs_status ON beliefs(status)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_beliefs_validFrom ON beliefs(validFrom)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_beliefs_confidence ON beliefs(confidence)")

            // Evidence table
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS evidence (
                    id TEXT NOT NULL PRIMARY KEY,
                    beliefId TEXT NOT NULL,
                    source TEXT NOT NULL,
                    summary TEXT NOT NULL,
                    detailJson TEXT NOT NULL DEFAULT '{}',
                    timestamp INTEGER NOT NULL,
                    confidence REAL NOT NULL DEFAULT 1.0,
                    FOREIGN KEY(beliefId) REFERENCES beliefs(id) ON DELETE CASCADE
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS index_evidence_beliefId ON evidence(beliefId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_evidence_source ON evidence(source)")

            // World events table
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS world_events (
                    id TEXT NOT NULL PRIMARY KEY,
                    eventType TEXT NOT NULL,
                    source TEXT NOT NULL,
                    summary TEXT NOT NULL,
                    payloadJson TEXT NOT NULL DEFAULT '{}',
                    timestamp INTEGER NOT NULL,
                    consumed INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS index_world_events_timestamp ON world_events(timestamp)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_world_events_source ON world_events(source)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_world_events_eventType ON world_events(eventType)")

            // Opportunities table
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS opportunities (
                    id TEXT NOT NULL PRIMARY KEY,
                    title TEXT NOT NULL,
                    description TEXT NOT NULL,
                    kind TEXT NOT NULL DEFAULT 'suggestion',
                    benefit REAL NOT NULL DEFAULT 0.5,
                    urgency REAL NOT NULL DEFAULT 0.5,
                    confidence REAL NOT NULL DEFAULT 0.5,
                    costEstimateJson TEXT NOT NULL DEFAULT '{}',
                    evidenceJson TEXT NOT NULL DEFAULT '[]',
                    suggestedActionJson TEXT NOT NULL DEFAULT '{}',
                    status TEXT NOT NULL DEFAULT 'proposed',
                    createdAt INTEGER NOT NULL,
                    resolvedAt INTEGER,
                    snoozeUntil INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS index_opportunities_status ON opportunities(status)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_opportunities_benefit ON opportunities(benefit)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_opportunities_urgency ON opportunities(urgency)")
        }
    }

    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Preference signals table
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS preference_signals (
                    id TEXT NOT NULL PRIMARY KEY,
                    projectId TEXT NOT NULL DEFAULT '',
                    signalType TEXT NOT NULL,
                    category TEXT NOT NULL,
                    artifactId TEXT,
                    attributesJson TEXT NOT NULL DEFAULT '{}',
                    weight REAL NOT NULL DEFAULT 1.0,
                    createdAt INTEGER NOT NULL
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS index_preference_signals_projectId ON preference_signals(projectId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_preference_signals_signalType ON preference_signals(signalType)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_preference_signals_createdAt ON preference_signals(createdAt)")

            // Style profiles table
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS style_profiles (
                    id TEXT NOT NULL PRIMARY KEY,
                    projectId TEXT NOT NULL DEFAULT '',
                    attributesJson TEXT NOT NULL DEFAULT '{}',
                    signalCount INTEGER NOT NULL DEFAULT 0,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS index_style_profiles_projectId ON style_profiles(projectId)")

            // Reference identities table
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS reference_identities (
                    id TEXT NOT NULL PRIMARY KEY,
                    projectId TEXT NOT NULL,
                    identityType TEXT NOT NULL,
                    name TEXT NOT NULL,
                    attributesJson TEXT NOT NULL DEFAULT '{}',
                    referenceArtifactIdsJson TEXT NOT NULL DEFAULT '[]',
                    locked INTEGER NOT NULL DEFAULT 0,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    FOREIGN KEY(projectId) REFERENCES creative_projects(id) ON DELETE CASCADE
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS index_reference_identities_projectId ON reference_identities(projectId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_reference_identities_identityType ON reference_identities(identityType)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_reference_identities_name ON reference_identities(name)")

            // Routing outcomes table
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS routing_outcomes (
                    id TEXT NOT NULL PRIMARY KEY,
                    modelRole TEXT NOT NULL,
                    modelId TEXT NOT NULL,
                    success INTEGER NOT NULL,
                    latencyMs INTEGER NOT NULL DEFAULT 0,
                    costClass TEXT NOT NULL DEFAULT 'unknown',
                    outcomeType TEXT NOT NULL DEFAULT 'user_accepted',
                    createdAt INTEGER NOT NULL
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS index_routing_outcomes_modelRole ON routing_outcomes(modelRole)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_routing_outcomes_modelId ON routing_outcomes(modelId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_routing_outcomes_success ON routing_outcomes(success)")
        }
    }

    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE memories ADD COLUMN scope TEXT NOT NULL DEFAULT 'general'")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_memories_scope ON memories(scope)")
        }
    }

    val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS memory_feedback (
                    id TEXT NOT NULL PRIMARY KEY,
                    memoryId TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    note TEXT NOT NULL DEFAULT '',
                    createdAt INTEGER NOT NULL
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_feedback_memoryId ON memory_feedback(memoryId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_feedback_createdAt ON memory_feedback(createdAt)")
        }
    }

    val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Add agentScope column to all 8 world model + taste tables.
            // Default "general" so existing rows are visible to all agents
            // (backward compatible — no data is hidden after the upgrade).
            db.execSQL("ALTER TABLE beliefs ADD COLUMN agentScope TEXT NOT NULL DEFAULT 'general'")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_beliefs_agentScope ON beliefs(agentScope)")
            db.execSQL("ALTER TABLE evidence ADD COLUMN agentScope TEXT NOT NULL DEFAULT 'general'")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_evidence_agentScope ON evidence(agentScope)")
            db.execSQL("ALTER TABLE world_events ADD COLUMN agentScope TEXT NOT NULL DEFAULT 'general'")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_world_events_agentScope ON world_events(agentScope)")
            db.execSQL("ALTER TABLE opportunities ADD COLUMN agentScope TEXT NOT NULL DEFAULT 'general'")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_opportunities_agentScope ON opportunities(agentScope)")
            db.execSQL("ALTER TABLE preference_signals ADD COLUMN agentScope TEXT NOT NULL DEFAULT 'general'")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_preference_signals_agentScope ON preference_signals(agentScope)")
            db.execSQL("ALTER TABLE style_profiles ADD COLUMN agentScope TEXT NOT NULL DEFAULT 'general'")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_style_profiles_agentScope ON style_profiles(agentScope)")
            db.execSQL("ALTER TABLE reference_identities ADD COLUMN agentScope TEXT NOT NULL DEFAULT 'general'")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_reference_identities_agentScope ON reference_identities(agentScope)")
            db.execSQL("ALTER TABLE routing_outcomes ADD COLUMN agentScope TEXT NOT NULL DEFAULT 'general'")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_routing_outcomes_agentScope ON routing_outcomes(agentScope)")
        }
    }

    val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Add index on scope column — every recall query filters on scope
            // (searchByTextInScopes, searchByWordsInScopes, allByScopes).
            // Without this index, SQLite does a full table scan on every recall.
            db.execSQL("CREATE INDEX IF NOT EXISTS index_memories_scope ON memories(scope)")
        }
    }

    val MIGRATION_15_16 = object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Index the hot recall sort keys. searchByText/searchByWords/top
            // ORDER BY decayScore DESC; vectorScanCandidates ORDER BY
            // accessCount DESC, decayScore DESC. Without these, every recall
            // on a 10K+ memory install does a full table scan + sort.
            db.execSQL("CREATE INDEX IF NOT EXISTS index_memories_decayScore ON memories(decayScore)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_memories_accessCount ON memories(accessCount)")
        }
    }

    val MIGRATION_16_17 = object : Migration(16, 17) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Full-text index over memories.content, replacing the six
            // `content LIKE '%word%'` clauses lexical recall used to run.
            //
            // Those six clauses were a hard six-term ceiling baked into a DAO
            // signature (MemoryStore fed it the user's whole message and kept
            // only the first six non-stopwords) and a guaranteed full table
            // scan, since a leading-wildcard LIKE cannot use an index. They
            // also gave BM25 no way to know the corpus, so its IDF was computed
            // over the already-matched candidates and collapsed to its floor
            // for exactly the terms that should have discriminated.
            //
            // Triggers keep the index current — see MemoryFtsSchema for why
            // that is done in SQL rather than in the DAO layer.
            MemoryFtsSchema.createAndBackfill(db)
        }
    }

    val MIGRATION_17_18 = object : Migration(17, 18) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Living worlds: a simulated world attached to a creative project,
            // plus its event history.
            //
            // The DDL below is copied verbatim from the generated 18.json rather
            // than hand-written, because a migration that produces a schema even
            // slightly different from the one Room expects fails validation on
            // every upgrade install while passing every fresh-install test.
            //
            // History lives here rather than in `proactive_events` because that
            // table is swept of everything older than thirty days on every app
            // start, and a world's past has to outlive a month.
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `living_worlds` (`id` TEXT NOT NULL, `projectId` TEXT NOT NULL, " +
                    "`branchId` TEXT NOT NULL, `rootSeed` INTEGER NOT NULL, `branchSalt` INTEGER NOT NULL, " +
                    "`parentWorldId` TEXT NOT NULL, `forkedAtTick` INTEGER NOT NULL, `worldEpochMs` INTEGER NOT NULL, " +
                    "`currentTick` INTEGER NOT NULL, `stateJson` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                    "FOREIGN KEY(`projectId`) REFERENCES `creative_projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_living_worlds_projectId` ON `living_worlds` (`projectId`)")
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_living_worlds_projectId_branchId` " +
                    "ON `living_worlds` (`projectId`, `branchId`)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_living_worlds_status` ON `living_worlds` (`status`)")

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `living_events` (`id` TEXT NOT NULL, `worldId` TEXT NOT NULL, " +
                    "`branchId` TEXT NOT NULL, `tickIndex` INTEGER NOT NULL, `seq` INTEGER NOT NULL, " +
                    "`kind` TEXT NOT NULL, `actorId` TEXT NOT NULL, `targetId` TEXT NOT NULL, `ruleId` TEXT NOT NULL, " +
                    "`magnitudeMilli` INTEGER NOT NULL, `summary` TEXT NOT NULL, `notability` REAL NOT NULL, " +
                    "`narration` TEXT NOT NULL, `narratedAt` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`id`), FOREIGN KEY(`worldId`) REFERENCES `living_worlds`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_living_events_worldId_tickIndex` ON `living_events` (`worldId`, `tickIndex`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_living_events_worldId_notability` ON `living_events` (`worldId`, `notability`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_living_events_worldId_actorId_tickIndex` " +
                    "ON `living_events` (`worldId`, `actorId`, `tickIndex`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_living_events_worldId_narratedAt` ON `living_events` (`worldId`, `narratedAt`)",
            )
        }
    }

    val MIGRATION_18_19 = object : Migration(18, 19) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Retirement: a memory can stop being retrievable without being
            // destroyed. Consolidation used to hard-delete its sources, which
            // made "undo" a reconstruction from a snapshot rather than a
            // restore, and made the audit trail's CASCADE the second casualty.
            //
            // All three columns are nullable so the ALTERs need no defaults and
            // NULL means exactly what it reads as: this memory is live.
            db.execSQL("ALTER TABLE `memories` ADD COLUMN `retiredAt` INTEGER")
            db.execSQL("ALTER TABLE `memories` ADD COLUMN `supersededBy` TEXT")
            db.execSQL("ALTER TABLE `memories` ADD COLUMN `retiredReason` TEXT")
        }
    }

    val MIGRATION_19_20 = object : Migration(19, 20) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // The correction spine: the user telling Aura it was wrong, in a
            // form that has an effect.
            //
            // DDL copied verbatim from the generated 20.json rather than
            // hand-written, because a migration producing a schema even
            // slightly different from the one Room expects fails validation on
            // every upgrade install while passing every fresh-install test.
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `corrections` (`id` TEXT NOT NULL, `targetKind` TEXT NOT NULL, " +
                    "`targetId` TEXT NOT NULL, `kind` TEXT NOT NULL, `replacementId` TEXT, `note` TEXT NOT NULL, " +
                    "`queryText` TEXT NOT NULL, `queryEmbedding` BLOB, `sourceConversationId` TEXT NOT NULL, " +
                    "`sourceTurnTimestamp` INTEGER NOT NULL, `propagatedJson` TEXT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, `undoneAt` INTEGER, PRIMARY KEY(`id`))",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_corrections_targetId` ON `corrections` (`targetId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_corrections_kind` ON `corrections` (`kind`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_corrections_createdAt` ON `corrections` (`createdAt`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_corrections_undoneAt` ON `corrections` (`undoneAt`)")
        }
    }

    val MIGRATION_20_21 = object : Migration(20, 21) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Aura's open questions. The curiosity drive has always been
            // computable — gapNodeCount() has counted unexplored graph nodes
            // since it shipped — and has never had anywhere to put a question.
            //
            // DDL copied verbatim from the generated 21.json rather than
            // hand-written, because a migration producing a schema even
            // slightly different from the one Room expects fails validation on
            // every upgrade install while passing every fresh-install test.
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `open_questions` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                    "`subjectKind` TEXT NOT NULL, `subjectId` TEXT NOT NULL, `question` TEXT NOT NULL, " +
                    "`status` TEXT NOT NULL, `answerable` TEXT NOT NULL, `answerMemoryId` TEXT, " +
                    "`askedAt` INTEGER, `timesAsked` INTEGER NOT NULL, `answeredAt` INTEGER, " +
                    "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_open_questions_status` ON `open_questions` (`status`)")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_open_questions_subjectKind_subjectId` " +
                    "ON `open_questions` (`subjectKind`, `subjectId`)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_open_questions_createdAt` ON `open_questions` (`createdAt`)")
        }
    }

    val MIGRATION_21_22 = object : Migration(21, 22) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // The place log — a second source of truth about the user's life
            // that does not depend on them narrating it. See
            // com.aura.place.PlaceVisitEntity for why the coordinates are
            // deliberately coarse.
            //
            // DDL copied verbatim from the generated 22.json, per MIGRATION_20_21:
            // a migration producing a schema even slightly different from the one
            // Room expects fails validation on every upgrade install while passing
            // every fresh-install test.
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `place_visits` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`lat` REAL NOT NULL, `lon` REAL NOT NULL, `arrivedAt` INTEGER NOT NULL, " +
                    "`lastSeenAt` INTEGER NOT NULL, `samples` INTEGER NOT NULL, `label` TEXT NOT NULL)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_place_visits_arrivedAt` ON `place_visits` (`arrivedAt`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_place_visits_lat_lon` ON `place_visits` (`lat`, `lon`)")
        }
    }

    val MIGRATION_22_23 = object : Migration(22, 23) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Creative analysis, keyed to the revision it read. The creative
            // package could already score tension, track character change and
            // profile voice, and discarded every result — see
            // CreativeAnalysisEntity. Keyed to a revision it becomes comparable
            // across drafts, which is the whole point.
            //
            // DDL copied verbatim from the generated 23.json, per MIGRATION_20_21.
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `creative_analysis` (`id` TEXT NOT NULL, " +
                    "`revisionId` TEXT NOT NULL, `artifactId` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                    "`payloadJson` TEXT NOT NULL, `headline` REAL NOT NULL, `note` TEXT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                    "FOREIGN KEY(`revisionId`) REFERENCES `creative_revisions`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_creative_analysis_revisionId_kind` " +
                    "ON `creative_analysis` (`revisionId`, `kind`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_creative_analysis_artifactId` " +
                    "ON `creative_analysis` (`artifactId`)",
            )
        }
    }

    val MIGRATION_23_24 = object : Migration(23, 24) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Which memory was retrieved for which question, and how good that
            // turned out to be.
            //
            // The retrieval eval harness has always been able to measure fusion,
            // BM25, candidate pools and decay — against synthetic fixtures whose
            // absolute scores docs/RETRIEVAL_EVAL.md says mean nothing, and which
            // force Gate B to print "inconclusive". The one input it lacks is
            // judgments, and judgments were documented as a weekend of hand
            // grading. This is where they accumulate from ordinary use instead.
            //
            // No foreign key, and none is possible: `conversations` lives in
            // ConversationDatabase and SQLite has no cross-database foreign keys.
            // Orphans are therefore the default state and deletion is wired by
            // hand — see RetrievalLabelDao.deleteForConversation.
            //
            // DDL copied verbatim from the generated 24.json, per MIGRATION_20_21.
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `retrieval_labels` (`id` TEXT NOT NULL, " +
                    "`conversationId` TEXT NOT NULL, `turnTimestamp` INTEGER NOT NULL, " +
                    "`queryText` TEXT NOT NULL, `memoryId` TEXT NOT NULL, `rank` INTEGER NOT NULL, " +
                    "`grade` INTEGER, `gradeSource` TEXT NOT NULL, `heuristicGrade` INTEGER, " +
                    "`signalsJson` TEXT NOT NULL, `sampled` INTEGER NOT NULL, `judgedAt` INTEGER, " +
                    "`queryClass` TEXT, `supersededByEdit` INTEGER NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_retrieval_labels_conversationId` " +
                    "ON `retrieval_labels` (`conversationId`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_retrieval_labels_createdAt` " +
                    "ON `retrieval_labels` (`createdAt`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_retrieval_labels_sampled_grade` " +
                    "ON `retrieval_labels` (`sampled`, `grade`)",
            )
        }
    }

    val MIGRATION_24_25 = object : Migration(24, 25) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Projects, and the ledger that answers "where is this".
            //
            // `project` already existed three times in this schema as a tag —
            // a string in a conversation's metadata JSON, a `category` value on
            // `memories`, and `NodeType.PROJECT` in the graph, which nothing
            // wrote. None of them could hold a state, so "where is ARC-AGI-2"
            // had to be answered by a BM25 query over whatever had been said.
            //
            // `projects` is created first: `project_notes` carries a CASCADE
            // foreign key into it, and SQLite resolves that at DDL time.
            //
            // DDL copied verbatim from the generated 25.json, per MIGRATION_20_21.
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `projects` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                    "`description` TEXT NOT NULL, `status` TEXT NOT NULL, `lastTurnAt` INTEGER NOT NULL, " +
                    "`turnCount` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_projects_status` ON `projects` (`status`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_projects_lastTurnAt` ON `projects` (`lastTurnAt`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_projects_name` ON `projects` (`name`)")

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `project_notes` (`id` TEXT NOT NULL, " +
                    "`projectId` TEXT NOT NULL, `kind` TEXT NOT NULL, `subject` TEXT NOT NULL, " +
                    "`body` TEXT NOT NULL, `sourceConversationId` TEXT NOT NULL, " +
                    "`sourceTurnAt` INTEGER NOT NULL, `state` TEXT NOT NULL, `supersededBy` TEXT, " +
                    "`resolvedAt` INTEGER, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                    "FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_project_notes_projectId_state` " +
                    "ON `project_notes` (`projectId`, `state`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_project_notes_projectId_kind_subject` " +
                    "ON `project_notes` (`projectId`, `kind`, `subject`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_project_notes_createdAt` " +
                    "ON `project_notes` (`createdAt`)",
            )
        }
    }

    val MIGRATION_25_26 = object : Migration(25, 26) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Verdicts on Aura's own claims.
            //
            // `beliefs.confidence` has been asserted since v6 and never checked.
            // The only thing calling itself verification bumps `lastVerifiedAt`
            // when the same KG edge is seen twice, which tests nothing.
            //
            // No backfill, and none is possible: a verdict is a judgment about a
            // claim, and there is no record of anyone having made one. The table
            // starts empty and stays honest.
            //
            // DDL copied verbatim from the generated 26.json, per MIGRATION_20_21.
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `claim_resolutions` (`id` TEXT NOT NULL, " +
                    "`beliefId` TEXT NOT NULL, `verdict` TEXT NOT NULL, " +
                    "`verdictSource` TEXT NOT NULL, `assertedConfidence` REAL NOT NULL, " +
                    "`beliefSource` TEXT NOT NULL, `note` TEXT NOT NULL, " +
                    "`resolvedAt` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                    "FOREIGN KEY(`beliefId`) REFERENCES `beliefs`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_claim_resolutions_beliefId` " +
                    "ON `claim_resolutions` (`beliefId`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_claim_resolutions_verdict` " +
                    "ON `claim_resolutions` (`verdict`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_claim_resolutions_beliefSource_verdict` " +
                    "ON `claim_resolutions` (`beliefSource`, `verdict`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_claim_resolutions_resolvedAt` " +
                    "ON `claim_resolutions` (`resolvedAt`)",
            )
        }
    }

    /**
     * Scope the FTS update trigger to the only column the index contains.
     *
     * No schema change — `27.json` is `26.json` with a different version number
     * and the same identity hash, because Room's schema export does not record
     * hand-written triggers. The migration exists because the trigger SQL alone
     * cannot reach an installed device: every statement in
     * `MemoryFtsSchema.TRIGGERS` is `CREATE TRIGGER IF NOT EXISTS`, so editing
     * it changes fresh installs and silently leaves every upgraded device on the
     * old definition.
     *
     * What the old definition cost: `AFTER UPDATE ON memories` fired on every
     * column, and `MemoryDao.touch` — which bumps `accessedAt`, `accessCount`
     * and `decayScore` — runs once per returned memory on *every recall*. A
     * ten-hit query therefore did ten FTS delete-and-reindex cycles over text
     * that had not changed, on the critical path of the feature this index
     * exists to make fast.
     */
    val MIGRATION_26_27 = object : Migration(26, 27) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MemoryFtsSchema.reinstallTriggers(db)
        }
    }

    /**
     * The chunk index. `document_chunks` has existed since schema 12 and held
     * no rows until document import was routed into it; this is the index that
     * makes those rows searchable as documents rather than only as the
     * `memories` copies written beside them.
     */
    val MIGRATION_27_28 = object : Migration(27, 28) {
        override fun migrate(db: SupportSQLiteDatabase) {
            com.aura.documents.DocumentChunkFtsSchema.createAndBackfill(db)
        }
    }

    /**
     * Genesis for fork-at-past. The exact tick-0 state a world started from,
     * written once at creation; '' on every pre-v29 world, for which
     * fork-at-past stays disabled — re-seeding cannot reconstruct genesis once
     * the author has edited the bible. Column shape copied verbatim from the
     * generated 29.json, per MIGRATION_20_21.
     */
    val MIGRATION_28_29 = object : Migration(28, 29) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `living_worlds` ADD COLUMN `genesisJson` TEXT NOT NULL DEFAULT ''")
        }
    }

    /**
     * v29 -> v30: record why an open question was the one chosen.
     *
     * Both columns default to the value an unscored row has, so every existing question stays
     * valid and behaves exactly as it did — a score of 0 sorts nowhere in particular and a
     * null reason renders as no reason.
     */
    val MIGRATION_29_30 = object : Migration(29, 30) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `open_questions` ADD COLUMN `voiScore` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `open_questions` ADD COLUMN `voiReason` TEXT")
        }
    }

    /**
     * Somewhere durable to record what Aura generated.
     *
     * Images were written to `cacheDir` and recorded nowhere, so they could be reclaimed by
     * Android at any time with nothing left to say they had existed. The table is created
     * empty on purpose: the files that were in `cacheDir` when this shipped may already be
     * gone, and inventing rows for images that might not be there would put broken tiles in
     * the Library with nothing able to tell which were real.
     */
    /**
     * A seat inside a living world, and the journal its moves are written to.
     *
     * Five added columns, no table rewrites, no data touched. Every one
     * carries a default that matches what the entity declares, so a world
     * that existed before the seat did reads back as unseated and unplayed
     * rather than as a world with a null player.
     */
    val MIGRATION_31_32 = object : Migration(31, 32) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE living_worlds ADD COLUMN playerCharacterId TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE living_worlds ADD COLUMN playerFactionId TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE living_worlds ADD COLUMN sessionTicksBurned INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE living_events ADD COLUMN payloadJson TEXT NOT NULL DEFAULT ''")
        }
    }

    val MIGRATION_30_31 = object : Migration(30, 31) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `generated_media` (" +
                    "`id` TEXT NOT NULL, " +
                    "`kind` TEXT NOT NULL, " +
                    "`prompt` TEXT NOT NULL, " +
                    "`mimeType` TEXT NOT NULL, " +
                    "`storageUri` TEXT NOT NULL, " +
                    "`remoteUrl` TEXT NOT NULL, " +
                    "`byteSize` INTEGER NOT NULL, " +
                    "`conversationId` TEXT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`id`))",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_generated_media_createdAt` ON `generated_media` (`createdAt`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_generated_media_kind` ON `generated_media` (`kind`)")
        }
    }
}
