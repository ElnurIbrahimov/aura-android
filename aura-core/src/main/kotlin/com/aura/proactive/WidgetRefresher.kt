package com.aura.proactive

/**
 * Pushes a redraw to the app module's home-screen widgets.
 *
 * Declared in core because [ProactiveBootstrap] is the caller; implemented in
 * :app, the only module that can name the widget classes. This used to be the
 * package-scoped `com.aura.action.REFRESH_WIDGET` broadcast -- but the widget
 * receivers are exported for APPWIDGET_UPDATE, and a custom action in an
 * exported receiver's filter is sendable by every app on the device. An
 * in-process interface needs no action at all.
 */
fun interface WidgetRefresher {
    fun refreshAll()
}
