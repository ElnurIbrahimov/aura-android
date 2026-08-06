package com.aura

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MainActivityLaunchRequestTest {
    @Test
    fun `brief event id implies chat and increments request sequence`() {
        val request = resolveAuraLaunchRequest(
            openChat = false,
            openMemory = false,
            morningBriefEventId = 42L,
            previousSequence = 4,
        )

        requireNotNull(request)
        assertEquals(5, request.sequence)
        assertTrue(request.openChat)
        assertFalse(request.openMemory)
        assertEquals(42L, request.morningBriefEventId)
    }

    @Test
    fun `memory request does not accidentally open chat`() {
        val request = resolveAuraLaunchRequest(
            openChat = false,
            openMemory = true,
            morningBriefEventId = null,
            previousSequence = 8,
        )

        requireNotNull(request)
        assertEquals(9, request.sequence)
        assertFalse(request.openChat)
        assertTrue(request.openMemory)
        assertNull(request.morningBriefEventId)
    }

    @Test
    fun `empty intent produces no navigation request`() {
        assertNull(
            resolveAuraLaunchRequest(
                openChat = false,
                openMemory = false,
                // 0 is the getLongExtra default for "extra absent" —
                // it must not be treated as a real brief id.
                morningBriefEventId = 0L,
                previousSequence = 2,
            ),
        )
    }

    @Test
    fun `non-positive brief id is discarded but explicit openChat still wins`() {
        val request = resolveAuraLaunchRequest(
            openChat = true,
            openMemory = false,
            morningBriefEventId = -1L,
            previousSequence = 0,
        )

        requireNotNull(request)
        assertTrue(request.openChat)
        assertNull(request.morningBriefEventId)
    }
}
