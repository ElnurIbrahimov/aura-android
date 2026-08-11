package com.aura.kg

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object KnowledgeGraphModule {

    @Provides
    @Singleton
    fun provideKnowledgeGraphDao(db: com.aura.memory.MemoryDatabase): KnowledgeGraphDao =
        db.knowledgeGraphDao()

    /**
     * Both optional collaborators are passed explicitly. They are nullable with
     * `null` defaults on the constructor so direct constructions in tests keep
     * compiling, and that same default is what silently disabled each of them in
     * production: `entityResolver` was omitted here, so the resolver and its
     * passing test ran zero times in the app's life and the graph accumulated
     * paraphrase duplicates. `KnowledgeGraphModuleTest` pins both arguments.
     */
    @Provides
    @Singleton
    fun provideKnowledgeGraphRepository(
        dao: KnowledgeGraphDao,
        beliefConflictProbe: com.aura.world.BeliefConflictProbe,
        entityResolver: KgEntityResolver,
    ): KnowledgeGraphRepository = KnowledgeGraphRepository(dao, beliefConflictProbe, entityResolver)
}
