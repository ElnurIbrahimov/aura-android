package com.aura.ui.components.charts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartRendererTest {

    @Test
    fun `parseChartData parses valid bar chart JSON`() {
        val raw = """{"title":"Sales","labels":["Jan","Feb","Mar"],"values":[100,200,150]}"""
        val data = parseChartData(raw)
        assertNotNull(data)
        assertEquals("Sales", data!!.title)
        assertEquals(listOf("Jan", "Feb", "Mar"), data.labels)
        assertEquals(listOf(100.0, 200.0, 150.0), data.values)
    }

    @Test
    fun `parseChartData parses valid line chart JSON`() {
        val raw = """{"title":"Temperature","labels":["Mon","Tue"],"values":[72,68]}"""
        val data = parseChartData(raw)
        assertNotNull(data)
        assertEquals("Temperature", data!!.title)
    }

    @Test
    fun `parseChartData parses valid pie chart JSON`() {
        val raw = """{"title":"Browser Share","labels":["Chrome","Firefox","Safari"],"values":[65,20,15]}"""
        val data = parseChartData(raw)
        assertNotNull(data)
        assertEquals(3, data!!.labels.size)
    }

    @Test
    fun `parseChartData returns null for invalid JSON`() {
        val raw = "not json at all"
        val data = parseChartData(raw)
        assertNull(data)
    }

    @Test
    fun `parseChartData returns null for empty values`() {
        val raw = """{"title":"Empty","labels":[],"values":[]}"""
        val data = parseChartData(raw)
        assertNull(data)
    }

    @Test
    fun `parseChartData handles missing title gracefully`() {
        val raw = """{"labels":["A","B"],"values":[1,2]}"""
        val data = parseChartData(raw)
        assertNotNull(data)
        assertEquals("", data!!.title)
    }

    @Test
    fun `normalizeValues scales to 0-1 range`() {
        val normalized = normalizeValues(listOf(0.0, 50.0, 100.0))
        assertEquals(0f, normalized[0], 0.001f)
        assertEquals(0.5f, normalized[1], 0.001f)
        assertEquals(1.0f, normalized[2], 0.001f)
    }

    @Test
    fun `normalizeValues handles all zeros`() {
        val normalized = normalizeValues(listOf(0.0, 0.0, 0.0))
        assertEquals(3, normalized.size)
        assertTrue(normalized.all { it <= 0.001f })
    }

    @Test
    fun `normalizeValues handles empty list`() {
        val normalized = normalizeValues(emptyList())
        assertTrue(normalized.isEmpty())
    }

    @Test
    fun `normalizeValues clamps negative values to zero`() {
        val normalized = normalizeValues(listOf(-10.0, 10.0))
        assertEquals(0f, normalized[0], 0.001f)
        assertEquals(1.0f, normalized[1], 0.001f)
    }

    @Test
    fun `ChartType fromLanguage matches known types`() {
        assertEquals(ChartType.BAR, ChartType.fromLanguage("chart-bar"))
        assertEquals(ChartType.LINE, ChartType.fromLanguage("chart-line"))
        assertEquals(ChartType.PIE, ChartType.fromLanguage("chart-pie"))
    }

    @Test
    fun `ChartType fromLanguage returns null for unknown`() {
        assertNull(ChartType.fromLanguage("python"))
        assertNull(ChartType.fromLanguage("chart-unknown"))
        assertNull(ChartType.fromLanguage(""))
    }
}