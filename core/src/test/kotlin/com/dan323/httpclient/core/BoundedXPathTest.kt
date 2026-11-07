package com.dan323.httpclient.core

import com.dan323.httpclient.core.application.BoundedEvaluator
import com.dan323.httpclient.core.application.ResponseExtractor
import com.dan323.httpclient.core.port.out.JsonExtractor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoundedXPathTest {
    private val noJson = object : JsonExtractor {
        override fun extract(json: String, path: String): String? = null
    }

    private val xml = "<r>" + "<a>x</a>".repeat(800) + "</r>"
    private val slow = "count(//a[count(//a[count(//a[count(//a[count(//a[count(//a[count(//a[count(//a)>0])>0])>0])>0])>0])>0])>0])"

    /** Like the JDK XPath engine: keeps running when interrupted, until [latch] opens. */
    private fun uninterruptibleWait(latch: CountDownLatch) {
        while (latch.count > 0) try { Thread.sleep(5) } catch (_: InterruptedException) { }
    }

    private fun threads(prefix: String) =
        Thread.getAllStackTraces().keys.count { it.name.startsWith(prefix) && it.isAlive }

    @Test fun realPathologicalXPathNeverExceedsTheThreadBound() {
        val bound = 2
        val evaluator = BoundedEvaluator(maxThreads = bound, timeoutMillis = 50, threadName = "xp-real")
        val extractor = ResponseExtractor(noJson, evaluator)
        repeat(40) {
            assertNull(extractor.xpath(xml, slow))
            assertTrue(threads("xp-real") <= bound, "live evaluation threads: ${threads("xp-real")}")
        }
        // the pathological expression really keeps its slots busy, so later calls fail fast instead of piling up
        assertTrue(threads("xp-real") in 1..bound)
    }

    @Test fun saturatedPoolFailsFastAndRecoversOnceSlotsAreFree() {
        val evaluator = BoundedEvaluator(maxThreads = 2, timeoutMillis = 50, threadName = "xp-sat")
        val release = CountDownLatch(1)
        // two evaluations that time out but cannot be interrupted occupy both slots
        repeat(2) {
            assertNull(evaluator.evaluate<String> { uninterruptibleWait(release); "late" })
        }
        val start = System.nanoTime()
        assertNull(evaluator.evaluate { "never started" })
        assertTrue((System.nanoTime() - start) / 1_000_000 < 40, "must fail fast, not wait for the timeout")
        assertEquals(2, threads("xp-sat"))

        release.countDown()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var result: String? = null
        while (result == null && System.nanoTime() < deadline) {
            result = evaluator.evaluate { "ok" }
            if (result == null) Thread.sleep(10)
        }
        assertEquals("ok", result)
    }

    @Test fun normalEvaluationsStillWorkAfterThePoolWasSaturatedAndDrained() {
        val evaluator = BoundedEvaluator(maxThreads = 2, timeoutMillis = 50, threadName = "xp-norm")
        val extractor = ResponseExtractor(noJson, evaluator)
        val release = CountDownLatch(1)
        repeat(2) { assertNull(evaluator.evaluate<String> { uninterruptibleWait(release); "late" }) }
        assertNull(extractor.xpath("<r><a>one</a></r>", "//a")) // saturated: fails fast, finds nothing
        release.countDown()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var result: String? = null
        while (result == null && System.nanoTime() < deadline) {
            result = extractor.xpath("<r><a>one</a></r>", "//a")
            if (result == null) Thread.sleep(10)
        }
        assertEquals("one", result)
    }

    @Test fun failuresAndMalformedInputDoNotLeakSlots() {
        val evaluator = BoundedEvaluator(maxThreads = 1, timeoutMillis = 1_000, threadName = "xp-fail")
        val extractor = ResponseExtractor(noJson, evaluator)
        repeat(20) {
            assertNull(extractor.xpath("<r>", "/r"))
            assertNull(evaluator.evaluate<String> { error("boom") })
        }
        assertEquals("1", extractor.xpath("<r>1</r>", "/r"))
    }

    @Test fun concurrentCallersNeverExceedTheBoundAndStayConsistent() {
        val bound = 3
        val extractor = ResponseExtractor(noJson, BoundedEvaluator(maxThreads = bound, timeoutMillis = 50, threadName = "xp-conc"))
        val callers = Executors.newFixedThreadPool(8)
        try {
            val futures = (1..64).map { i ->
                callers.submit<String?> {
                    if (i % 2 == 0) extractor.xpath(xml, slow) else extractor.xpath("<r><a>$i</a></r>", "//a")
                }
            }
            val results = futures.map { it.get(120, TimeUnit.SECONDS) }
            assertTrue(threads("xp-conc") <= bound, "live evaluation threads: ${threads("xp-conc")}")
            results.forEachIndexed { idx, value ->
                val i = idx + 1
                // slow ones always find nothing; quick ones find their value or nothing (no free slot), never a wrong value
                if (i % 2 == 0) assertNull(value) else assertTrue(value == null || value == "$i", "got $value for $i")
            }
        } finally {
            callers.shutdownNow()
        }
    }

    @Test fun catastrophicRegexStopsQuicklyAndLeavesNoThreads() {
        val extractor = ResponseExtractor(noJson, BoundedEvaluator(maxThreads = 1, timeoutMillis = 50, threadName = "xp-re"))
        val before = Thread.activeCount()
        val start = System.nanoTime()
        repeat(3) { assertNull(extractor.regex("a".repeat(60) + "!", "(a+)+\$")) }
        assertTrue((System.nanoTime() - start) / 1_000_000 < 15_000)
        assertTrue(Thread.activeCount() <= before + 1)
        assertEquals("42", extractor.regex("id=42", "id=(\\d+)"))
    }
}
