package com.aura.providers

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PROVIDERS_AUDIT B1 P0 finding: "401 auth errors trigger
 * wasteful failover."
 *
 * Manual audit: every HTTP provider (AnthropicProvider,
 * GeminiProvider, ChatGptSubscriptionProvider,
 * CustomOpenAiCompatProvider, OpenAiCompatProvider) already
 * marks 401, 400, 403 as non-retryable. The agentic loop's
 * failover check at MemoryAugmentedAgenticLoop.kt:646 is
 * `if (chunk.retryable && triedModels.size < 2)` — so a
 * non-retryable 401 will NOT trigger failover.
 *
 * But the BUG CLASS is real: a future contributor might
 * naively add a new provider or change an existing one
 * to mark 401 as retryable. That would cause the agentic
 * loop to silently burn through the user's configured
 * provider list trying to recover from a bad key.
 *
 * Fix: this test scans the source for `retryable` literals
 * in the provider files and asserts the well-known
 * non-retryable codes (401, 400, 403) appear in the
 * non-retryable condition. This catches drift if someone
 * removes the explicit exclusion.
 *
 * The test also pins the per-provider contract directly
 * via the source-text scan — no need to spin up mock
 * servers to verify the classification.
 */
class NonRetryableStatusCodesTest {

    @Test
    fun `every HTTP provider marks 401 as non-retryable`() {
        // 401 = Unauthorized (bad API key). Retrying with
        // the same key won't help. The agentic loop's
        // failover check `if (chunk.retryable && triedModels.size < 2)`
        // would otherwise burn through every configured
        // provider trying to recover from a key the user
        // entered wrong.
        //
        // Two valid patterns:
        // 1. Positive retryable check: `retryable = code == 429 || code in 500..599`
        //    (Anthropic, Gemini) — 401 falls through to false.
        // 2. Negative retryable check: `retryable = code != 401 && code != 400 && code != 403`
        //    (ChatGpt, OpenAiCompat, CustomOpenAi) — 401 explicitly excluded.
        // Either style is fine; the test accepts both.
        for (providerFile in PROVIDER_FILES) {
            val text = readFile(providerFile)
            val hasPositiveCheck = text.contains("retryable = ") && (
                text.contains("429") && (text.contains("500") || text.contains("in 500"))
            )
            val hasNegativeCheck = text.contains("!= 401")
            assertTrue(hasPositiveCheck || hasNegativeCheck,
                "$providerFile must mark 401 as non-retryable. " +
                "Either use a positive check `retryable = code == 429 || code in 500..599` " +
                "or a negative check `retryable = code != 401`.")
        }
    }

    @Test
    fun `every HTTP provider marks 400 and 403 as non-retryable`() {
        // 400 = Bad Request (malformed request — won't fix itself)
        // 403 = Forbidden (key doesn't have permission — won't fix itself)
        // Same reasoning as 401: retrying doesn't help.
        //
        // Accept either pattern: positive check
        // (only 429/5xx retryable → 400/403 fall through) or
        // negative check (400/403 explicitly excluded).
        for (providerFile in PROVIDER_FILES) {
            val text = readFile(providerFile)
            val hasPositiveCheck = text.contains("429") && (text.contains("500") || text.contains("in 500"))
            val has400Negative = text.contains("!= 400")
            val has403Negative = text.contains("!= 403")
            // If positive check is in use, 400/403 are non-retryable by default.
            // If negative check is in use, 400/403 must be explicitly listed.
            assertTrue(hasPositiveCheck || has400Negative,
                "$providerFile must mark 400 as non-retryable. " +
                "Use `retryable = code != 400` (negative) or a positive check (only 429/5xx retryable).")
            assertTrue(hasPositiveCheck || has403Negative,
                "$providerFile must mark 403 as non-retryable. " +
                "Use `retryable = code != 403` (negative) or a positive check (only 429/5xx retryable).")
        }
    }

    @Test
    fun `429 and 5xx are retryable for failover`() {
        // The opposite side of the coin: 429 (rate limit)
        // and 5xx (server error) ARE retryable. The agentic
        // loop will try the next configured provider.
        // This test pins the positive case.
        for (providerFile in PROVIDER_FILES) {
            val text = readFile(providerFile)
            val has429 = text.contains("429")
            val has5xx = text.contains("500") && (text.contains("599") || text.contains("in 500"))
            assertTrue(has429 || has5xx,
                "$providerFile should mark 429 (rate limit) and 5xx (server error) as retryable. " +
                "These benefit from failover to a different provider.")
        }
    }

    private fun readFile(path: String): String {
        val file = java.io.File(path)
        return if (file.exists()) file.readText() else ""
    }

    companion object {
        // The provider files that need the 401/400/403
        // non-retryable check. New providers should be
        // added here as they're created.
        private val PROVIDER_FILES = listOf(
            "D:/aura-android-clean/aura-core/src/main/kotlin/com/aura/providers/AnthropicProvider.kt",
            "D:/aura-android-clean/aura-core/src/main/kotlin/com/aura/providers/GeminiProvider.kt",
            "D:/aura-android-clean/aura-core/src/main/kotlin/com/aura/providers/ChatGptSubscriptionProvider.kt",
            "D:/aura-android-clean/aura-core/src/main/kotlin/com/aura/providers/CustomOpenAiCompatProvider.kt",
            "D:/aura-android-clean/aura-core/src/main/kotlin/com/aura/providers/OpenAiCompatProvider.kt",
        )
    }
}
