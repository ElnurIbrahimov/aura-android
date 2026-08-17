package com.aura.backup

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the backup implementation to the seam its consumers depend on.
 *
 * `@Binds` rather than `@Provides` so Hilt keeps constructing [BackupManager]
 * through its own `@Inject` constructor — the alternative would mean naming all
 * seventy-five of its collaborators here, which is the problem, not the fix.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class BackupModule {

    @Binds
    @Singleton
    abstract fun bindBackupService(impl: BackupManager): BackupService
}
