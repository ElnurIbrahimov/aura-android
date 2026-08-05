package com.aura.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aura.ui.theme.AuraSpacing
import com.aura.ui.theme.AuraThemeTokens
import com.aura.ui.util.displayIcon
import com.aura.ui.viewmodel.CapabilitiesViewModel
import com.aura.ui.viewmodel.CapabilityCardState

@Composable
fun CapabilitiesScreen(
    viewModel: CapabilitiesViewModel = hiltViewModel(),
    onBack: () -> Unit,
) {
    val cards by viewModel.state.collectAsStateWithLifecycle()
    val colors = AuraThemeTokens.colors

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = colors.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AuraSpacing.sm, vertical = AuraSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = colors.textPrimary,
                    )
                }
                Text(
                    text = "Capabilities",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = AuraSpacing.md, vertical = AuraSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(AuraSpacing.sm),
            ) {
                items(cards, key = { it.kind.name }) { card ->
                    CapabilityCard(card = card)
                }
            }
        }
    }
}

@Composable
private fun CapabilityCard(card: CapabilityCardState) {
    val colors = AuraThemeTokens.colors
    val icon = card.kind.displayIcon()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (card.isConfigured) colors.surface1 else colors.surface0,
        shape = RoundedCornerShape(AuraSpacing.md),
        border = androidx.compose.foundation.BorderStroke(
            width = AuraSpacing.hairline,
            color = if (card.isConfigured) colors.actionPrimary.copy(alpha = 0.5f) else colors.borderSubtle,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AuraSpacing.md),
            horizontalArrangement = Arrangement.spacedBy(AuraSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(AuraSpacing.sm),
                color = if (card.isConfigured) colors.actionPrimary.copy(alpha = 0.15f) else colors.surface1,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.padding(AuraSpacing.sm).size(AuraSpacing.lg),
                    tint = if (card.isConfigured) colors.actionPrimary else colors.textSecondary,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = card.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                )
                Spacer(modifier = Modifier.height(AuraSpacing.xxs))
                Text(
                    text = card.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                )
                Spacer(modifier = Modifier.height(AuraSpacing.xxs))
                val statusText = if (card.isConfigured) {
                    "Active · ${card.providerLabel ?: ""}"
                } else {
                    "Not configured — add a key in Settings"
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (card.isConfigured) colors.actionPrimary else colors.textTertiary,
                )
            }
        }
    }
}
