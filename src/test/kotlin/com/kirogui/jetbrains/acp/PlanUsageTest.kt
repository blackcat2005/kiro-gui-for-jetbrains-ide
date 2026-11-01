package com.kirogui.jetbrains.acp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PlanUsageTest {

    /** The `data` object exactly as `kiro-cli` 2.37 returned it for `/usage`. */
    private val real = """
        {"planName":"KIRO FREE","billingCycleReset":"2026-12-01","overagesEnabled":false,"isEnterprise":false,
         "usageBreakdowns":[{"resourceType":"CREDIT","displayName":"Credits","used":30.57,"limit":50.0,
         "percentage":61.14000000000001,"currentOverages":0.0,"overageRate":0.04,"overageCharges":0.0,
         "currency":"USD","hasLimit":true}],"bonusCredits":[],"addOnCredits":[],"overageCapable":false}
    """.trimIndent()

    private fun parse(json: String) = PlanUsage.parse(Json.parseToJsonElement(json).jsonObject)

    @Test
    fun `parses the real payload`() {
        val u = assertNotNull(parse(real))
        assertEquals("KIRO FREE", u.planName)
        assertEquals(30.57, u.used)
        assertEquals(50.0, u.limit)
        assertEquals("30.6 / 50 credits · 61%", u.summary)
        assertEquals("30.6/50", u.compact)
        assertEquals("1 Dec 2026", u.resetText)
        assertEquals(UsageLevel.OK, u.level)
        assertEquals(0.6114, u.fraction, 0.0001)
    }

    @Test
    fun `warning and critical thresholds`() {
        fun level(pct: Double) = PlanUsage("p", null, pct / 2, 50.0, pct, false).level
        assertEquals(UsageLevel.OK, level(79.9))
        assertEquals(UsageLevel.WARNING, level(80.0))
        assertEquals(UsageLevel.WARNING, level(94.9))
        assertEquals(UsageLevel.CRITICAL, level(95.0))
        assertEquals(UsageLevel.CRITICAL, level(120.0))
        assertEquals(1.0, PlanUsage("p", null, 60.0, 50.0, 120.0, true).fraction, "bar never overflows")
    }

    @Test
    fun `plans without a cap show only what was used`() {
        val u = assertNotNull(
            parse("""{"planName":"Enterprise","usageBreakdowns":[{"resourceType":"CREDIT","used":1234.0,"hasLimit":false}]}"""),
        )
        assertNull(u.limit)
        assertEquals("1234 credits", u.summary)
        assertEquals(UsageLevel.OK, u.level)
        assertEquals(0.0, u.fraction)
        assertNull(u.resetText)
    }

    @Test
    fun `unusable payloads give null instead of crashing`() {
        assertNull(parse("""{"planName":"x"}"""))
        assertNull(parse("""{"usageBreakdowns":[]}"""))
        assertNull(parse("""{"usageBreakdowns":[{"resourceType":"CREDIT"}]}"""))
    }

    @Test
    fun `prefers the CREDIT row and tooltip mentions plan, reset and overages`() {
        val u = assertNotNull(
            parse("""{"planName":"Pro","billingCycleReset":"2026-12-01","overagesEnabled":true,"usageBreakdowns":[{"resourceType":"OTHER","used":1.0,"limit":2.0,"percentage":50.0},{"resourceType":"CREDIT","used":10.0,"limit":1000.0,"percentage":1.0}]}"""),
        )
        assertEquals(10.0, u.used)
        val tip = u.tooltip()
        assertEquals(true, tip.contains("Pro") && tip.contains("1 Dec 2026") && tip.contains("Overages enabled"))
    }
}
