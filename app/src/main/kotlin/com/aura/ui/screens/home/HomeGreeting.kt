package com.aura.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.aura.ui.theme.AuraSpacing
import com.aura.ui.theme.AuraThemeTokens

/**
 * The one piece of large type on Home, set in Fraunces.
 *
 * The theme has shipped Fraunces since the port began and no screen ever
 * used it: `displayLarge` and `displayMedium` had zero call sites, so
 * every heading in the app fell to Inter SemiBold at 19sp or below. With
 * nothing above 19sp anywhere, there was no typographic hierarchy to read
 * — the whole app sat in one flat band of small text.
 *
 * This is deliberately the only display-scale text on the screen. It
 * earns its size by being the thing you see first and read once; a second
 * element at this weight would cancel it out.
 */
@Composable
fun HomeGreeting(
    hour: Int,
    userName: String?,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    val colors = AuraThemeTokens.colors
    Column(
        modifier = modifier.fillMaxWidth().testTag("home-greeting"),
        verticalArrangement = Arrangement.spacedBy(AuraSpacing.xs),
    ) {
        Text(
            text = greetingFor(hour, userName),
            style = MaterialTheme.typography.displayMedium,
            color = colors.textPrimary,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.textSecondary,
        )
    }
}

/**
 * Time-of-day greeting, with the user's name when we know it.
 *
 * Boundaries follow ordinary English usage rather than even six-hour
 * blocks: "morning" starts at 5 and "evening" starts at 18, so a 17:00
 * greeting is still afternoon. Anything from 22:00 to 04:59 is treated as
 * night — "Good morning" at 3am is wrong in a way people notice.
 */
internal fun greetingFor(hour: Int, userName: String?): String {
    val timeOfDay = when (hour) {
        in 5..11 -> "Good morning"
        in 12..17 -> "Good afternoon"
        in 18..21 -> "Good evening"
        else -> "Still up"
    }
    val name = userName?.trim()?.takeIf { it.isNotEmpty() }
    return if (name != null) "$timeOfDay, $name" else timeOfDay
}
