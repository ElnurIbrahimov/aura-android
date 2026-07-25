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
    private val MIGRATION_1_2 = object : Migration(1, 2) {
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
