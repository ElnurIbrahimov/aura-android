package com.aura.ui.components.charts

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aura.ui.theme.AuraThemeTokens
import com.aura.ui.theme.AuraSpacing

private const val MAX_POINTS = 20
private val PIE_COLORS = listOf(
    Color(0xFF2DD4BF),
    Color(0xFF60A5FA),
    Color(0xFFF59E0B),
    Color(0xFFEF4444),
    Color(0xFF8B5CF6),
    Color(0xFF10B981),
    Color(0xFFEC4899),
)

@Composable
fun PieChartView(
    data: ChartData,
    modifier: Modifier = Modifier,
) {
    val colors = AuraThemeTokens.colors
    val chartData = remember(data) {
        data.copy(values = data.values.take(MAX_POINTS), labels = data.labels.take(MAX_POINTS))
    }
    var animationTriggered by remember { mutableStateOf(false) }
    val animationProgress by animateFloatAsState(
        targetValue = if (animationTriggered) 1f else 0f,
        animationSpec = tween(400),
        label = "pieChart",
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Canvas(
                modifier = Modifier.size(140.dp),
            ) {
                val total = chartData.values.sum().coerceAtLeast(0.001)
                var startAngle = -90f
                chartData.values.forEachIndexed { i, value ->
                    val sweepAngle = (value / total * 360 * animationProgress).toFloat()
                    val color = PIE_COLORS[i % PIE_COLORS.size]
                    val pieSize = minOf(size.width - 16f, size.height - 16f)
                    drawArc(
                        color = color,
                        startAngle = startAngle,
                        sweepAngle = sweepAngle,
                        useCenter = true,
                        topLeft = Offset(8f, 8f),
                        size = androidx.compose.ui.geometry.Size(pieSize, pieSize),
                    )
                    startAngle += (value / total * 360).toFloat()
                }
            }
            // Legend
            Column(modifier = Modifier.padding(start = AuraSpacing.md)) {
                chartData.labels.forEachIndexed { i, label ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Canvas(modifier = Modifier.size(12.dp)) {
                            drawCircle(color = PIE_COLORS[i % PIE_COLORS.size])
                        }
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textPrimary,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
            }
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { animationTriggered = true }
}