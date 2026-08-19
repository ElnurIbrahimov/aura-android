package com.aura.profile

import android.content.Context
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aura.data.RoomConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object UserProfileModule {
    /**
     * `internal`, not `private`, so [com.aura.migration.MigrationReplayTest] can
     * name it. The test source set is a friend module and can see `internal`; it
     * cannot see `private`, and its registry is written out by hand precisely so
     * the compiler is the gate. While this was `private` it could not be listed,
     * so this database's only migration was verified by nothing at all.
     */
    internal val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE user_profile ADD COLUMN agentScope TEXT NOT NULL DEFAULT 'general'")
        }
    }

    @Provides
    @Singleton
    fun provideDb(@ApplicationContext ctx: Context): UserProfileDatabase =
        RoomConfig.builder(
            ctx,
            UserProfileDatabase::class.java,
            "aura-profile.db",
            migrations = arrayOf(MIGRATION_1_2),
        ).build()

    @Provides
    fun provideDao(db: UserProfileDatabase): UserProfileDao = db.userProfileDao()

    @Singleton
    @Provides
    fun provideUserProfileStore(dao: UserProfileDao): UserProfileStore =
        UserProfileStore(dao)
}
