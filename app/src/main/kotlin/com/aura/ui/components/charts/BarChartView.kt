package com.aura.ui.components.charts

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aura.ui.theme.AuraThemeTokens
import com.aura.ui.theme.AuraSpacing
import kotlin.math.max
import com.aura.ui.theme.AuraTokens

private const val MAX_POINTS = 20
private val CHART_COLORS: List<Color> = AuraTokens.chartPalette

@Composable
fun BarChartView(
    data: ChartData,
    modifier: Modifier = Modifier,
) {
    val colors = AuraThemeTokens.colors
    val chartData = remember(data) {
        data.copy(values = data.values.take(MAX_POINTS), labels = data.labels.take(MAX_POINTS))
    }
    val normalized = remember(chartData) { normalizeValues(chartData.values) }
    var animationTriggered by remember { mutableStateOf(false) }
    val animationProgress by animateFloatAsState(
        targetValue = if (animationTriggered) 1f else 0f,
        animationSpec = tween(300),
        label = "barChart",
    )

    Column(modifier = modifier.fillMaxWidth().padding(AuraSpacing.xs)) {
        if (chartData.title.isNotBlank()) {
            Text(
                text = chartData.title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textPrimary,
                modifier = Modifier.padding(bottom = AuraSpacing.xs),
            )
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp),
        ) {
            val barCount = normalized.size
            if (barCount == 0) return@Canvas
            val barWidth = (size.width / barCount) * 0.7f
            val gap = (size.width / barCount) * 0.3f
            normalized.forEachIndexed { i, normValue ->
                val colorIndex = i % CHART_COLORS.size
                val barHeight = size.height * normValue * animationProgress
                val x = i * (barWidth + gap) + gap / 2
                val y = size.height - barHeight
                drawRoundRect(
                    color = CHART_COLORS[colorIndex],
                    topLeft = Offset(x, y),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(4f, 4f),
                )
            }
        }
        // Labels
        Row(modifier = Modifier.fillMaxWidth()) {
            chartData.labels.forEachIndexed { i, label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
            }
        }
    }
    // Trigger animation on first composition
    androidx.compose.runtime.LaunchedEffect(Unit) { animationTriggered = true }
}