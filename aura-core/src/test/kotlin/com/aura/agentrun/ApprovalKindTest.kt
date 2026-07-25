package com.aura.agentrun

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [ApprovalKind] is the contract between `AgentRunExecutorWorker`, which
 * writes an approval rationale, and the Agent Runs UI, which decides whether
 * tapping Approve should first ask Android for a runtime permission.
 *
 * Getting this wrong is not cosmetic. If the UI fails to recognise a
 * permission approval it records the approval, resets the step to PENDING and
 * re-enqueues the worker — which re-runs the tool, finds the permission still
 * ungranted, and blocks again. That is an unbreakable approve → block →
 * approve loop, and it is what the screen did before this existed.
 */
class ApprovalKindTest {

    @Test
    fun `round-trips a permission through build and parse`() {
        val rationale = ApprovalKind.permissionRationale("android.permission.READ_CALENDAR")
        assertEquals("android.permission.READ_CALENDAR", ApprovalKind.permissionOf(rationale))
    }

    @Test
    fun `a cost approval has no permission`() {
        assertNull(ApprovalKind.permissionOf("This will call a paid image API. Continue?"))
        assertNull(ApprovalKind.permissionOf("Approval needed: sends an email"))
        assertNull(ApprovalKind.permissionOf(""))
    }

    @Test
    fun `prefix must be at the start, not merely present`() {
        // A tool rationale that happens to quote the phrase must not be
        // mistaken for a permission request and launched at Android.
        assertNull(
            ApprovalKind.permissionOf("The tool reported: Permission needed: something"),
        )
    }

    @Test
    fun `a prefix with no permission after it is not a permission approval`() {
        // Launching the permission contract with an empty string throws at
        // runtime, so an empty tail must read as "not a permission".
        assertNull(ApprovalKind.permissionOf(ApprovalKind.PERMISSION_PREFIX))
        assertNull(ApprovalKind.permissionOf(ApprovalKind.PERMISSION_PREFIX + "   "))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals(
            "android.permission.CAMERA",
            ApprovalKind.permissionOf("${ApprovalKind.PERMISSION_PREFIX}  android.permission.CAMERA  "),
        )
    }
}
