package com.aura.ui.screens.canvas

/**
 * Extract canvas content from the model's response text.
 *
 * The model emits fenced code blocks with canvas-specific languages:
 * ````
 * ```canvas-markdown
 * # My Document
 * Content here...
 * ```
 * ````
 *
 * The title is extracted from the first line if it starts with `#`,
 * or defaults to the canvas type label.
 *
 * @return The first CanvasContent found, or null if no canvas block.
 */
fun extractCanvas(responseText: String): CanvasContent? {
    // Match fenced code blocks with canvas-* language
    val canvasRegex = Regex(
        """```(canvas-\w+)\n(.+?)```""",
        RegexOption.DOT_MATCHES_ALL,
    )
    val match = canvasRegex.find(responseText) ?: return null
    val language = match.groupValues[1]
    val rawContent = match.groupValues[2].trim()
    val type = CanvasType.fromLanguage(language) ?: return null

    // Extract title from first markdown heading or first line
    val title = extractTitle(rawContent, type)

    return CanvasContent(
        type = type,
        title = title,
        content = rawContent,
    )
}

/**
 * Strip canvas blocks from the response text so they don't render
 * as plain code blocks in the chat.
 */
fun stripCanvasBlocks(responseText: String): String {
    val canvasRegex = Regex(
        """```(canvas-\w+)\n(.+?)```\n?""",
        RegexOption.DOT_MATCHES_ALL,
    )
    return canvasRegex.replace(responseText, "").trim()
}

private fun extractTitle(content: String, type: CanvasType): String {
    val firstLine = content.lineSequence().firstOrNull()?.trim() ?: return type.label
    return when {
        firstLine.startsWith("# ") -> firstLine.removePrefix("# ").take(80)
        firstLine.startsWith("## ") -> firstLine.removePrefix("## ").take(80)
        firstLine.startsWith("<!-- title:") -> {
            val end = firstLine.indexOf("-->")
            if (end > 13) firstLine.substring(12, end).trim().take(80) else type.label
        }
        firstLine.length <= 80 -> firstLine
        else -> type.label
    }
}