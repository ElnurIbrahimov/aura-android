package com.aura.widget

import android.content.Context
import com.aura.proactive.WidgetRefresher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The binding :app hands down to :aura-core's [com.aura.proactive.ProactiveBootstrap].
 * [WidgetRefresher]'s KDoc records why this is a call and not a broadcast.
 */
@Module
@InstallIn(SingletonComponent::class)
object WidgetRefreshModule {

    @Provides
    @Singleton
    fun widgetRefresher(@ApplicationContext context: Context): WidgetRefresher =
        WidgetRefresher {
            AskAuraWidget.requestRefresh(context)
            WorkingOnWidget.requestRefresh(context)
        }
}
