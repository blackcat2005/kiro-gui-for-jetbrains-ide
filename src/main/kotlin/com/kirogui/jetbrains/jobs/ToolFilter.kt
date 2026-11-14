package com.kirogui.jetbrains.jobs

/** The "tools Kiro must not get" setting: names separated by commas, spaces or newlines. */
object ToolFilter {
    fun parse(text: String): Set<String> =
        text.split(',', ' ', '\n', '\r', '\t', ';').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}
