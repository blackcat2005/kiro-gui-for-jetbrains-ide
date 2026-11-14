package com.kirogui.jetbrains.jobs

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TestTextTest {

    private val junitTrace = """
        org.opentest4j.AssertionFailedError: expected: <23.0> but was: <25.0>
        	at org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:151)
        	at org.junit.jupiter.api.Assertions.assertEquals(Assertions.java:197)
        	at demo.PriceCalculatorTest.tenPensGetTheDiscount(PriceCalculatorTest.java:18)
        	at java.base/java.lang.reflect.Method.invoke(Method.java:580)
        	at org.junit.platform.commons.util.ReflectionUtils.invokeMethod(ReflectionUtils.java:728)
    """.trimIndent()

    @Test
    fun `stack frames keep the project's code and drop the frameworks`() {
        assertEquals(listOf("at demo.PriceCalculatorTest.tenPensGetTheDiscount(PriceCalculatorTest.java:18)"), TestText.frames(junitTrace))
        val caused = "java.lang.IllegalStateException: boom\n\tat demo.Inventory.reserve(Inventory.java:20)\n" +
            "\tat demo.InventoryTest.reserve(InventoryTest.java:12)\nCaused by: java.io.IOException: disk\n\tat demo.Store.load(Store.java:5)"
        assertEquals(
            listOf(
                "at demo.Inventory.reserve(Inventory.java:20)", "at demo.InventoryTest.reserve(InventoryTest.java:12)",
                "at demo.Store.load(Store.java:5)", "Caused by: java.io.IOException: disk",
            ),
            TestText.frames(caused),
        )
        // Only framework frames: keep them rather than nothing.
        assertEquals(1, TestText.frames("x\n\tat org.junit.Assert.fail(Assert.java:89)").size)
        // Not a JVM trace (pytest): the end of it.
        val py = "Traceback (most recent call last):\n  File \"test_cart.py\", line 9, in test_total\n    assert total(cart) == 31.1\nAssertionError"
        assertEquals(listOf("File \"test_cart.py\", line 9, in test_total", "assert total(cart) == 31.1", "AssertionError"), TestText.frames(py))
        assertEquals(emptyList(), TestText.frames(null))
    }

    @Test
    fun `message falls back to the first line of the trace and is kept short`() {
        assertEquals("expected: <23.0> but was: <25.0>", TestText.message("expected: <23.0> but was: <25.0>", junitTrace))
        assertEquals("org.opentest4j.AssertionFailedError: expected: <23.0> but was: <25.0>", TestText.message(null, junitTrace))
        assertEquals("1\n2\n3\n4\n5\n6\n…", TestText.message((1..9).joinToString("\n"), null))
    }

    @Test
    fun `outcomes`() {
        assertEquals("3 of 44 tests failed", TestText.outcome(TestTally(40, 3, 1, 0), stopped = false))
        assertEquals("all 12 tests passed (1 ignored)", TestText.outcome(TestTally(12, 0, 1, 0), stopped = false))
        assertEquals("all 12 tests passed", TestText.outcome(TestTally(12, 0, 0, 0), stopped = false))
        assertEquals("no tests ran", TestText.outcome(TestTally(0, 0, 0, 0), stopped = false))
        assertEquals("stopped after 5 of 9 tests passed", TestText.outcome(TestTally(5, 0, 0, 4), stopped = true))
        assertEquals("5 of 9 tests passed, 4 did not run", TestText.outcome(TestTally(5, 0, 0, 4), stopped = false))
    }

    @Test
    fun `report lists each failure with where and why, then what to do next`() {
        val failures = listOf(
            TestFailure("PriceCalculatorTest > tenPensGetTheDiscount()", "test/demo/PriceCalculatorTest.java:16",
                "expected: <23.0> but was: <25.0>", TestText.frames(junitTrace)),
            TestFailure("CouponTest", null, "Class init failed", emptyList()),
        )
        val text = TestText.report("Test job #7 (all tests) 2 of 15 tests failed after 4s.", "All in kiro-debug-demo",
            TestTally(13, 2, 0, 0), failures, 7)
        assertTrue(text.startsWith("Test job #7 (all tests) 2 of 15 tests failed after 4s. 13 passed, 2 failed.\nrun configuration: All in kiro-debug-demo\nFailed:\n"))
        assertTrue(text.contains(
            "1. PriceCalculatorTest > tenPensGetTheDiscount()  [test/demo/PriceCalculatorTest.java:16]\n" +
                "     expected: <23.0> but was: <25.0>\n" +
                "     at demo.PriceCalculatorTest.tenPensGetTheDiscount(PriceCalculatorTest.java:18)\n" +
                "2. CouponTest\n     Class init failed\n",
        ))
        assertTrue(text.contains("test_run rerun_failed=7"))
        val passed = TestText.report("Test job #8 (all tests) all 15 tests passed after 3s.", "All", TestTally(15, 0, 0, 0), emptyList(), 8)
        assertTrue(!passed.contains("Failed:") && !passed.contains("rerun_failed"))
    }

    @Test
    fun `failed build report lists the compile errors`() {
        val text = TestText.buildFailed("Test job #9 (all tests) build failed, 1 error(s) after 2s.", "All",
            listOf("src/demo/Coupon.java:14: cannot find symbol"), 9)
        assertTrue(text.contains("Compile errors:\n  src/demo/Coupon.java:14: cannot find symbol\nFix them"))
        assertTrue(TestText.buildFailed("h", "All", emptyList(), 9).contains("Build tool window"))
    }

    @Test
    fun `test job texts use the report`() {
        val job = Job(7, JobKind.TEST, "all tests", "All in demo", "s", startedAt = 0)
        job.summary = "2 of 15 tests failed"
        job.end(JobState.FAILED, 255, now = 4_000)
        job.report = "the report"
        assertEquals("Test job #7 (all tests) 2 of 15 tests failed", JobText.endedSummary(job))
        assertEquals("the report", JobText.ended(job))
        assertEquals("#7 [failed] all tests — 2 of 15 tests failed, 4s", JobText.line(job, now = 4_000))
        assertEquals("Test job #7: 2 of 15 tests failed after 4s.\nconfiguration: All in demo", JobText.header(job))
    }
}
