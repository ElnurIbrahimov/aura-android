package com.aura.ui.components.charts

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Dispatch chart rendering based on fenced code block language.
 * Called from MarkdownText when it encounters a chart-* block.
 *
 * @param language The fenced code block language (chart-bar, chart-line, chart-pie)
 * @param rawBody The JSON body inside the fence
 */
@Composable
fun ChartDispatcher(
    language: String,
    rawBody: String,
    modifier: Modifier = Modifier,
) {
    val type = ChartType.fromLanguage(language)
    val data = parseChartData(rawBody)
    if (type == null || data == null) return
    when (type) {
        ChartType.BAR -> BarChartView(data = data, modifier = modifier)
        ChartType.LINE -> LineChartView(data = data, modifier = modifier)
        ChartType.PIE -> PieChartView(data = data, modifier = modifier)
    }
}