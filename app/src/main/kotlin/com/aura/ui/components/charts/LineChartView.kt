package com.aura.ui.components.charts

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aura.ui.theme.AuraThemeTokens
import com.aura.ui.theme.AuraSpacing
import com.aura.ui.theme.AuraTokens

private const val MAX_POINTS = 20

@Composable
fun LineChartView(
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
        animationSpec = tween(400),
        label = "lineChart",
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
            if (normalized.size < 2) return@Canvas
            val stepX = size.width / (normalized.size - 1)
            val lineColor = CHART_COLORS.first()
            val path = Path()
            normalized.forEachIndexed { i, value ->
                val x = i * stepX
                val y = size.height - (size.height * value * animationProgress)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path = path, color = lineColor, style = Stroke(width = 4f))
            // Data points
            normalized.forEachIndexed { i, value ->
                val x = i * stepX
                val y = size.height - (size.height * value * animationProgress)
                drawCircle(color = lineColor, radius = 6f, center = Offset(x, y))
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            chartData.labels.forEachIndexed { i, label ->
                if (i < chartData.labels.size) {
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
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { animationTriggered = true }
}

private val CHART_COLORS: List<Color> = AuraTokens.chartPalette