package com.aura.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTableParsingTest {

    @Test
    fun `parseTableCells_splits_pipe_delimited_cells`() {
        val cells = parseTableCells("| Name | Age | City |")
        assertEquals(3, cells.size)
        assertEquals("Name", cells[0])
        assertEquals("Age", cells[1])
        assertEquals("City", cells[2])
    }

    @Test
    fun `parseTableCells_trims_whitespace`() {
        val cells = parseTableCells("  | Hello World | 42  |  ")
        assertEquals(2, cells.size)
        assertEquals("Hello World", cells[0])
        assertEquals("42", cells[1])
    }

    @Test
    fun `parseTableCells_handles_single_column`() {
        val cells = parseTableCells("| only |")
        assertEquals(1, cells.size)
        assertEquals("only", cells[0])
    }

    @Test
    fun `tableDelimiterRegex_matches_delimiter_row`() {
        assertTrue(tableDelimiterRegex.matches("|---|---|"))
        assertTrue(tableDelimiterRegex.matches("|:---|:---:|---:|"))
        assertTrue(tableDelimiterRegex.matches(" |---|---| "))
        assertTrue(tableDelimiterRegex.matches("|------|------|"))
    }

    @Test
    fun `tableDelimiterRegex_rejects_non_delimiter`() {
        assertTrue(!tableDelimiterRegex.matches("| Name | Age |"))
        assertTrue(!tableDelimiterRegex.matches("Hello world"))
        assertTrue(!tableDelimiterRegex.matches("|---|"))
        // Must have at least 2 columns
    }

    @Test
    fun `splitMarkdownBlocks_extracts_table`() {
        val text = """
            Some text before.

            | Name | Score |
            |------|-------|
            | Alice | 95 |
            | Bob | 87 |

            Text after.
        """.trimIndent()

        val blocks = splitMarkdownBlocks(text)
        val tableBlock = blocks.firstOrNull { it is MarkdownBlock.Table }
        assertTrue("Expected a Table block", tableBlock != null)
        val table = tableBlock as MarkdownBlock.Table
        assertEquals(listOf("Name", "Score"), table.headers)
        assertEquals(2, table.rows.size)
        assertEquals("Alice", table.rows[0][0])
        assertEquals("95", table.rows[0][1])
        assertEquals("Bob", table.rows[1][0])
        assertEquals("87", table.rows[1][1])
    }

    @Test
    fun `splitMarkdownBlocks_table_only`() {
        val text = """
            | A | B | C |
            |---|---|---|
            | 1 | 2 | 3 |
        """.trimIndent()

        val blocks = splitMarkdownBlocks(text)
        val table = blocks.firstOrNull { it is MarkdownBlock.Table } as MarkdownBlock.Table
        assertEquals(listOf("A", "B", "C"), table.headers)
        assertEquals(1, table.rows.size)
        assertEquals("1", table.rows[0][0])
    }

    @Test
    fun `splitMarkdownBlocks_preserves_surrounding`() {
        val text = """
            Before table.

            | H1 | H2 |
            |----|----|
            | a  | b  |

            ```kotlin
            val x = 1
            ```
        """.trimIndent()

        val blocks = splitMarkdownBlocks(text)
        // Should have: Text, Table, Code
        assertTrue(blocks.any { it is MarkdownBlock.Text })
        assertTrue(blocks.any { it is MarkdownBlock.Table })
        assertTrue(blocks.any { it is MarkdownBlock.Code })
    }
}