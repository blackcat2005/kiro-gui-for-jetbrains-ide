package com.kirogui.jetbrains.acp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class UsageLevel { OK, WARNING, CRITICAL }

/**
 * Account-wide credits for the billing cycle, as reported by Kiro's `/usage`
 * (`_kiro.dev/commands/execute` with `{command: "usage"}`). Not per session.
 */
data class PlanUsage(
    val planName: String,
    /** ISO date when the cycle resets, as sent. */
    val resetDate: String?,
    val used: Double,
    /** Null when the plan has no cap. */
    val limit: Double?,
    val percent: Double?,
    val overagesEnabled: Boolean,
) {
    val level: UsageLevel
        get() = when {
            percent == null -> UsageLevel.OK
            percent >= 95 -> UsageLevel.CRITICAL
            percent >= 80 -> UsageLevel.WARNING
            else -> UsageLevel.OK
        }

    /** 0..1 for a bar; 0 when there is no cap. */
    val fraction: Double get() = ((percent ?: 0.0) / 100.0).coerceIn(0.0, 1.0)

    /** "30.6 / 50 credits · 61%" */
    val summary: String
        get() = if (limit != null) "${number(used)} / ${number(limit)} credits" + (percent?.let { " · ${it.toInt()}%" } ?: "")
        else "${number(used)} credits"

    /** Short form for tight spaces. */
    val compact: String get() = if (limit != null) "${number(used)}/${number(limit)}" else number(used)

    val resetText: String?
        get() = resetDate?.let { runCatching { LocalDate.parse(it).format(RESET_FORMAT) }.getOrNull() ?: it }

    fun tooltip(): String = buildString {
        append("<html><b>").append(planName).append("</b><br>")
        append(summary)
        resetText?.let { append("<br>Resets ").append(it) }
        append("<br>").append(if (overagesEnabled) "Overages enabled" else "Overages off")
        append("<br><i>Click to refresh</i></html>")
    }

    companion object {
        private val RESET_FORMAT = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

        /** "30.6", but "50" rather than "50.0". */
        fun number(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else "%.1f".format(Locale.ENGLISH, v)

        /** Reads the `data` object of the `usage` command; null when it carries no credit figures. */
        fun parse(data: JsonObject): PlanUsage? {
            val rows = (data["usageBreakdowns"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            val row = rows.firstOrNull { str(it, "resourceType") == "CREDIT" } ?: rows.firstOrNull() ?: return null
            val used = num(row, "used") ?: return null
            val hasLimit = (row["hasLimit"] as? JsonPrimitive)?.booleanOrNull != false
            return PlanUsage(
                planName = str(data, "planName") ?: "Kiro",
                resetDate = str(data, "billingCycleReset"),
                used = used,
                limit = if (hasLimit) num(row, "limit") else null,
                percent = if (hasLimit) num(row, "percentage") else null,
                overagesEnabled = (data["overagesEnabled"] as? JsonPrimitive)?.booleanOrNull == true,
            )
        }

        private fun str(o: JsonObject, k: String) = o[k]?.takeUnless { it is JsonNull }?.let { (it as? JsonPrimitive)?.contentOrNull }
        private fun num(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.doubleOrNull
    }
}
