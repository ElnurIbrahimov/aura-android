package com.aura.backup

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The capability model choices survive export/restore.
 *
 * `imageModel` was already in `PreferencesBackup`; `videoModel` and
 * `voiceModel` are new, and a preference that is not in the backup is one the
 * user silently loses on restore — the exact failure §2.2 of
 * ENGINEERING_HISTORY records for `MemoryBackup.scope` and `daemonEnabled`,
 * where "no error, no indication of what had gone" is the whole problem.
 *
 * Also pins that they are optional, so a backup written before they existed
 * still restores instead of failing the whole import.
 */
class CapabilityModelBackupTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `all three capability models round-trip`() {
        val prefs = PreferencesBackup(
            imageModel = "agnes:agnes-image-2.1-flash",
            videoModel = "agnes:agnes-video-v2.0",
            voiceModel = "openai:tts-1-hd",
        )

        val restored = json.decodeFromString<PreferencesBackup>(json.encodeToString(prefs))

        assertEquals("agnes:agnes-image-2.1-flash", restored.imageModel)
        assertEquals("agnes:agnes-video-v2.0", restored.videoModel)
        assertEquals("openai:tts-1-hd", restored.voiceModel)
    }

    @Test
    fun `a backup written before video and voice existed still restores`() {
        // Only imageModel present, as every backup taken before 2026-08-08 is.
        val old = """{"imageModel":"dall-e-3"}"""

        val restored = json.decodeFromString<PreferencesBackup>(old)

        assertEquals("dall-e-3", restored.imageModel)
        assertNull("absent means unset, which means automatic", restored.videoModel)
        assertNull(restored.voiceModel)
    }

    @Test
    fun `unset capability models stay null rather than becoming empty strings`() {
        // Null and "" mean the same thing downstream (both fall back to
        // discovery), but writing "" into a restore would turn "automatic" into
        // a stored choice of nothing.
        val restored = json.decodeFromString<PreferencesBackup>(json.encodeToString(PreferencesBackup()))

        assertNull(restored.imageModel)
        assertNull(restored.videoModel)
        assertNull(restored.voiceModel)
    }
}
