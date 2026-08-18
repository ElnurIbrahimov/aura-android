package com.aura.testing

import org.junit.Test
import kotlin.test.assertFalse

/**
 * No exported receiver's intent filter carries a custom broadcast action.
 *
 * Widget receivers must be exported for APPWIDGET_UPDATE, and the same filter
 * used to carry `com.aura.action.REFRESH_WIDGET` -- one filter, so the custom
 * action was sendable by every app on the device. The refresh is an in-process
 * `WidgetRefresher` call now.
 *
 * Scoped to receivers, not the whole manifest: `com.aura.action.CAPTURE` on
 * the capture activity is the launcher-shortcut contract, and launching a
 * visible activity is not the silent background entry this test pins.
 */
class ManifestExposesNoCustomActionsTest {

    @Test
    fun `no custom action rides in a receiver's intent filter`() {
        val manifest = sourceDir("src/main").resolve("AndroidManifest.xml").readText()
        val receivers = Regex("<receiver[\\s\\S]*?</receiver>").findAll(manifest).toList()
        check(receivers.isNotEmpty()) {
            "no <receiver> blocks found -- this scan would pass vacuously, which is the defect"
        }
        receivers.forEach { block ->
            assertFalse(
                block.value.contains("com.aura.action."),
                "custom broadcast action in a receiver's filter -- receivers exported for " +
                    "APPWIDGET_UPDATE make it callable by every app on the device:\n" +
                    block.value,
            )
        }
    }
}
