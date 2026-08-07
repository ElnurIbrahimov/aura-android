package com.aura.providers

import com.aura.integrations.IntegrationTokenStore
import com.aura.integrations.OAuthFlow
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Shared doubles for [ChatGptSubscriptionProvider]'s auth dependencies.
 *
 * The provider takes an [IntegrationTokenStore] and an [OAuthFlow] because its
 * credential is an OAuth grant that has to be renewed, not a static key. Four
 * test classes only care that a bearer token comes out, so they get it from
 * here rather than each rebuilding the same two mocks.
 */
internal fun chatGptTokenStore(token: String?): IntegrationTokenStore = mockk {
    coEvery { getValidChatGptAccessToken(any()) } returns token
    coEvery { migrateLegacyChatGptToken(any()) } returns false
    every { chatgptConnected } returns MutableStateFlow(token != null)
}

/** Never called in tests that hand out an unexpired token. */
internal fun chatGptOAuthFlow(): OAuthFlow = mockk(relaxed = true)
