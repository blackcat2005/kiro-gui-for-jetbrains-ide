package com.kirogui.jetbrains.kiro

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

enum class RecencyBucket(val label: String) {
    TODAY("Today"), YESTERDAY("Yesterday"), WEEK("Previous 7 days"), MONTH("Previous 30 days"), OLDER("Older")
}

object RelativeTime {

    private val SAME_YEAR = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    private val OTHER_YEAR = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

    /** "just now", "5 min ago", "3 h ago", "yesterday", "4 d ago", then a short date. */
    fun format(then: Instant?, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
        then ?: return ""
        val seconds = Duration.between(then, now).seconds
        if (seconds < 60) return "just now" // also covers small clock skew
        val days = daysBetween(then, now, zone)
        return when {
            seconds < 3600 -> "${seconds / 60} min ago"
            days == 0L -> "${seconds / 3600} h ago"
            days == 1L -> "yesterday"
            days < 7 -> "$days d ago"
            else -> {
                val date = then.atZone(zone)
                date.format(if (date.year == now.atZone(zone).year) SAME_YEAR else OTHER_YEAR)
            }
        }
    }

    fun bucket(then: Instant?, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): RecencyBucket {
        then ?: return RecencyBucket.OLDER
        return when (val days = daysBetween(then, now, zone)) {
            in Long.MIN_VALUE..0 -> RecencyBucket.TODAY
            1L -> RecencyBucket.YESTERDAY
            in 2..7 -> RecencyBucket.WEEK
            in 8..30 -> RecencyBucket.MONTH
            else -> if (days < 0) RecencyBucket.TODAY else RecencyBucket.OLDER
        }
    }

    /** Whole calendar days between the two instants in [zone] (so 23:50 yesterday is 1 day ago, not 0). */
    private fun daysBetween(then: Instant, now: Instant, zone: ZoneId): Long =
        ChronoUnit.DAYS.between(then.atZone(zone).toLocalDate(), now.atZone(zone).toLocalDate())
}
