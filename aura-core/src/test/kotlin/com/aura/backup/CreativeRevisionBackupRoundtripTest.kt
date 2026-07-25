package com.aura.backup

import com.aura.creative.CreativeRevisionEntity
import org.junit.Test
import org.junit.Assert.assertEquals

/**
 * Regression test for CreativeRevisionBackup field alignment.
 *
 * Before the fix, the backup type had revisionNumber, contentJson, summary
 * which don't exist on the entity, while the entity had authorKind,
 * providerPrefix, modelId, prompt, settingsJson which weren't in the backup.
 * This test verifies the backup type fields match the entity fields so
 * a roundtrip preserves all metadata.
 */
class CreativeRevisionBackupRoundtripTest {

    @Test
    fun `backup type has all entity fields with matching types`() {
        val entity = CreativeRevisionEntity(
            id = "rev-1",
            artifactId = "art-1",
            branchId = "br-1",
            parentRevisionId = null,
            contentText = "content",
            storageUri = null,
            contentHash = "hash",
            authorKind = "generation",
            providerPrefix = "ollama",
            modelId = "llama3",
            prompt = "Write a story",
            settingsJson = """{"temp":0.7}""",
            createdAt = 1700000000L,
        )

        // Manually construct the backup (simulating what toBackup() should do)
        val backup = CreativeRevisionBackup(
            id = entity.id,
            artifactId = entity.artifactId,
            branchId = entity.branchId,
            parentRevisionId = entity.parentRevisionId,
            contentText = entity.contentText,
            storageUri = entity.storageUri,
            contentHash = entity.contentHash,
            authorKind = entity.authorKind,
            providerPrefix = entity.providerPrefix,
            modelId = entity.modelId,
            prompt = entity.prompt,
            settingsJson = entity.settingsJson,
            createdAt = entity.createdAt,
        )

        // Verify all fields are present and correctly typed
        assertEquals(entity.id, backup.id)
        assertEquals(entity.artifactId, backup.artifactId)
        assertEquals(entity.branchId, backup.branchId)
        assertEquals(entity.parentRevisionId, backup.parentRevisionId)
        assertEquals(entity.contentText, backup.contentText)
        assertEquals(entity.storageUri, backup.storageUri)
        assertEquals(entity.contentHash, backup.contentHash)
        assertEquals(entity.authorKind, backup.authorKind)
        assertEquals(entity.providerPrefix, backup.providerPrefix)
        assertEquals(entity.modelId, backup.modelId)
        assertEquals(entity.prompt, backup.prompt)
        assertEquals(entity.settingsJson, backup.settingsJson)
        assertEquals(entity.createdAt, backup.createdAt)
    }

    @Test
    fun `old backup without new fields uses safe defaults`() {
        // An old backup file without authorKind/providerPrefix/etc should
        // still load correctly because the backup type's new fields have defaults.
        val oldBackup = CreativeRevisionBackup(
            id = "rev-2",
            artifactId = "art-2",
            branchId = "br-2",
            parentRevisionId = "rev-1",
            contentText = "Legacy content",
            storageUri = null,
            contentHash = "def456",
            createdAt = 1600000000L,
        )

        assertEquals("manual", oldBackup.authorKind)
        assertEquals("", oldBackup.providerPrefix)
        assertEquals("", oldBackup.modelId)
        assertEquals("", oldBackup.prompt)
        assertEquals("{}", oldBackup.settingsJson)
    }
}