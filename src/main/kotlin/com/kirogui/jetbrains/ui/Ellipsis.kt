package com.kirogui.jetbrains.ui

import java.awt.FontMetrics

/** Shortens text with an ellipsis so it never draws wider than a given pixel width. */
object Ellipsis {

    private const val DOTS = "…"

    /** [text] unchanged if it fits in [maxWidth] pixels, else the longest prefix that fits together with "…". */
    fun fit(text: String, maxWidth: Int, width: (String) -> Int): String {
        if (maxWidth <= 0) return ""
        if (width(text) <= maxWidth) return text
        if (width(DOTS) > maxWidth) return ""
        var lo = 0
        var hi = text.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (width(text.substring(0, mid).trimEnd() + DOTS) <= maxWidth) lo = mid else hi = mid - 1
        }
        return text.substring(0, lo).trimEnd() + DOTS
    }

    fun fit(text: String, maxWidth: Int, fm: FontMetrics): String = fit(text, maxWidth) { fm.stringWidth(it) }
}
