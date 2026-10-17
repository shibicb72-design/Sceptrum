package com.shibi.sceptrum.diagnostics

import java.io.File
import java.io.IOException
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ErrorLogTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun recordsNewestFirstAndSurvivesARestart() {
        val file = File(tmp.root, "errors.log")
        val log = ErrorLog(file, "0.1.1", "Pixel 9", clock = { 1_000L })
        log.record("Launch app", "Netflix did not open", IllegalStateException("boom", IOException("reset")), tv = "XGIMI (192.168.1.20) Connected")
        log.record("Connection", "Dropped")

        val reopened = ErrorLog(file, "0.1.1", "Pixel 9")
        assertEquals(2, reopened.entries.value.size)
        assertEquals("Connection", reopened.entries.value[0].step)
        assertEquals(listOf("IllegalStateException: boom", "IOException: reset"), reopened.entries.value[1].chain)
        assertEquals("XGIMI (192.168.1.20) Connected", reopened.entries.value[1].tv)
    }

    @Test
    fun keepsAtMostMaxEntries() {
        val file = File(tmp.root, "errors.log")
        val log = ErrorLog(file, "0.1.1", "Pixel 9", clock = { 1_000L }, max = 3)
        for (i in 0..4) log.record("Step", "m$i")

        assertEquals(listOf("m4", "m3", "m2"), log.entries.value.map { it.message })
        assertEquals(listOf("m4", "m3", "m2"), ErrorLog(file, "0.1.1", "Pixel 9").entries.value.map { it.message })
    }

    @Test
    fun tabsAndNewlinesRoundTrip() {
        val file = File(tmp.root, "errors.log")
        val log = ErrorLog(file, "0.1.1", "Pixel 9")
        log.record("Step\twith tab", "line1\nline2 \\ backslash")

        val entry = ErrorLog(file, "0.1.1", "Pixel 9").entries.value.single()
        assertEquals("Step\twith tab", entry.step)
        assertEquals("line1\nline2 \\ backslash", entry.message)
    }

    @Test
    fun reportListsEveryEntryWithTimeChainAndVersions() {
        val log = ErrorLog(File(tmp.root, "errors.log"), "0.1.1", "Pixel 9", clock = { 0L }, zone = ZoneOffset.UTC)
        log.record("Launch app", "Netflix did not open", IllegalStateException("boom", IOException("reset")), tv = "XGIMI")

        val report = log.report()
        assertTrue(report.contains("Sceptrum error log: 1 entries, app 0.1.1, phone Pixel 9"))
        assertTrue(report.contains("1970-01-01 00:00:00 Z  Launch app: Netflix did not open"))
        assertTrue(report.contains("  TV: XGIMI"))
        assertTrue(report.contains("  IllegalStateException: boom"))
        assertTrue(report.contains("  caused by IOException: reset"))
        assertTrue(report.contains("  app 0.1.1, phone Pixel 9"))
    }

    @Test
    fun emptyReportIsJustTheHeader() {
        val log = ErrorLog(File(tmp.root, "errors.log"), "0.1.1", "Pixel 9")
        assertEquals("Sceptrum error log: 0 entries, app 0.1.1, phone Pixel 9", log.report())
    }

    @Test
    fun aBrokenLineIsSkipped() {
        val file = File(tmp.root, "errors.log")
        file.writeText("garbage\n")
        val log = ErrorLog(file, "0.1.1", "Pixel 9")
        assertTrue(log.entries.value.isEmpty())

        log.record("Step", "message")
        assertEquals(1, ErrorLog(file, "0.1.1", "Pixel 9").entries.value.size)
    }
}
