package com.waenhancer.update

import com.waenhancer.app.R
import com.waenhancer.utils.UiText

/**
 * Represents a categorized changelog section (e.g. Added, Fixed, Removed, Improvements).
 */
data class ChangelogSection(
    val title: String,
    val items: List<String>,
    /** Markdown nesting level for each item. Older/native renderers may ignore it. */
    val itemLevels: List<Int> = emptyList()
)

object ChangelogParser {

    private val SECTION_HEADER_REGEX = Regex(
        "^\\s*(?:\\[([^\\]]+)\\]|###?\\s*([^\n\r]+)|\\*\\*([^*]+)\\*\\*)\\s*:?$",
        RegexOption.MULTILINE
    )

    /**
     * Parses release body markdown/plain-text into categorized sections.
     * Supports formats like:
     * - [Added], [Fixed], [Removed], [Improvements], [Updated]
     * - ## Added, ### Fixed, etc.
     * - **Added**, **Fixed**, etc.
     * If no sections are detected, returns a single section with whatever items/text exist.
     */
    fun parse(rawText: String): List<ChangelogSection> {
        val trimmed = rawText.trim()
        if (trimmed.isBlank()) return emptyList()

        val lines = trimmed.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        val sections = mutableListOf<ChangelogSection>()

        var currentTitle: String? = null
        val currentItems = mutableListOf<String>()
        val currentItemLevels = mutableListOf<Int>()

        fun flushCurrent() {
            val title = currentTitle
            if (title != null && currentItems.isNotEmpty()) {
                sections.add(ChangelogSection(title, currentItems.toList(), currentItemLevels.toList()))
            } else if (title == null && currentItems.isNotEmpty()) {
                // Intro text or ungrouped text
                sections.add(ChangelogSection(UiText.get(R.string.waex_ui_overview, "Overview"), currentItems.toList(), currentItemLevels.toList()))
            }
            currentItems.clear()
            currentItemLevels.clear()
        }

        for (line in lines) {
            val trimmedLine = line.trim()
            if (trimmedLine.isEmpty()) continue

            val match = SECTION_HEADER_REGEX.matchEntire(trimmedLine)
            if (match != null) {
                flushCurrent()
                val detected = (match.groupValues[1].takeIf { it.isNotBlank() }
                    ?: match.groupValues[2].takeIf { it.isNotBlank() }
                    ?: match.groupValues[3].takeIf { it.isNotBlank() }
                    ?: "Highlights").trim()
                currentTitle = cleanSectionTitle(detected)
            } else {
                val leadingWhitespace = line.takeWhile { it == ' ' || it == '\t' }
                val indentColumns = leadingWhitespace.sumOf { if (it == '\t') 4 else 1 }
                val bulletMatch = Regex("^(?:[-*+•]|\\d+[.)])\\s+(.+)$").matchEntire(trimmedLine)
                val cleanItem = (bulletMatch?.groupValues?.get(1) ?: trimmedLine).trim()
                // Markdown commonly uses either two or four spaces per nested level.
                // Treat the first indentation step as one child level in both styles.
                val nestingLevel = if (bulletMatch == null || indentColumns == 0) {
                    0
                } else {
                    ((indentColumns + 3) / 4).coerceIn(1, 3)
                }
                if (cleanItem.isNotBlank()) {
                    currentItems.add(cleanItem)
                    currentItemLevels.add(nestingLevel)
                }
            }
        }
        flushCurrent()

        return if (sections.isEmpty() && trimmed.isNotBlank()) {
            listOf(ChangelogSection("Highlights", listOf(trimmed)))
        } else {
            sections
        }
    }

    private fun cleanSectionTitle(raw: String): String {
        val lower = raw.lowercase()
        return when {
            lower.contains("add") -> "Added"
            lower.contains("fix") -> "Fixed"
            lower.contains("remov") -> "Removed"
            lower.contains("improv") -> "Improvements"
            lower.contains("updat") -> "Updated"
            lower.contains("secur") -> "Security"
            lower.contains("deprecat") -> "Deprecated"
            else -> raw.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }
}
