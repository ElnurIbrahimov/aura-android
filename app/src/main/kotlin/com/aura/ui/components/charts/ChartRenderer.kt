package com.aura.ui.components.charts

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Chart data model. Parsed from fenced code blocks like:
 * ```chart-bar
 * {"title": "Sales", "labels": ["Jan","Feb"], "values": [100, 200]}
 * ```
 */
@Serializable
data class ChartData(
    val title: String = "",
    val labels: List<String> = emptyList(),
    val values: List<Double> = emptyList(),
)

enum class ChartType(val language: String) {
    BAR("chart-bar"),
    LINE("chart-line"),
    PIE("chart-pie");

    companion object {
        fun fromLanguage(lang: String): ChartType? =
            entries.firstOrNull { it.language == lang }
    }
}

/**
 * Parse chart data from a fenced code block body.
 * Returns null on parse error or empty data.
 */
fun parseChartData(raw: String): ChartData? = runCatching {
    val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    json.decodeFromString<ChartData>(raw.trim())
}.getOrNull()?.let {
    if (it.labels.isEmpty() || it.values.isEmpty()) null else it
}

/**
 * Normalize values to 0..1 range for rendering.
 * Returns empty list if all values are 0 or empty.
 */
fun normalizeValues(values: List<Double>): List<Float> {
    if (values.isEmpty()) return emptyList()
    val max = values.maxOrNull()?.coerceAtLeast(0.001) ?: return emptyList()
    return values.map { (it.coerceAtLeast(0.0) / max).toFloat() }
}