package com.shibi.sceptrum.diagnostics

import java.io.File
import java.io.IOException
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun secretsInAnExceptionChainNeverReachTheLogOrTheFile() {
        val file = File(tmp.root, "errors.log")
        val log = ErrorLog(file, "0.1.1", "Pixel 9")
        val error = IllegalStateException(
            "handshake failed with $PEM_KEY",
            javax.net.ssl.SSLHandshakeException("bad cert $DER_BASE64 sha256 $SHA256_HEX fp $FINGERPRINT").apply {
                initCause(IOException("pairing code: 4FA21C, secret=$SECRET_HEX token=\"eyJhbGciOi.payload.sig\" Authorization: Bearer abc.def"))
            },
        )
        log.record(
            "Pairing",
            "Failed with client key RSA 2048; \"private_key\": \"$DER_BASE64\" and code 9b3e0f",
            error,
            tv = "XGIMI Horizon S Max (192.168.1.50:6467) mac AA:BB:CC:DD:EE:01",
        )

        val report = log.report()
        val onDisk = file.readText()
        for (text in listOf(report, onDisk)) {
            for (secret in listOf("MIIEvQIBADANBgkqhkiG9w0BAQEFAASC", "PRIVATE KEY", DER_BASE64.take(24), SHA256_HEX, FINGERPRINT,
                SECRET_HEX, "4FA21C", "9b3e0f", "eyJhbGciOi", "abc.def")) {
                assertFalse("leaked $secret", text.contains(secret))
            }
        }
        assertTrue("device name and address stay", report.contains("XGIMI Horizon S Max (192.168.1.50:6467)"))
        assertTrue("a MAC is not a secret", report.contains("AA:BB:CC:DD:EE:01"))
        assertTrue("diagnostics stay readable", report.contains("client key RSA 2048"))
        assertTrue(report.contains(Redaction.PEM))
        assertTrue(report.contains("SSLHandshakeException: bad cert"))
    }

    @Test
    fun oldEntriesOnDiskAreScrubbedWhenRead() {
        val file = File(tmp.root, "errors.log")
        file.writeText("1000\tPairing\tsecret=$SECRET_HEX\t\tTV (10.0.0.2)\t0.1.0\tPixel 9\n")
        val entry = ErrorLog(file, "0.1.1", "Pixel 9").entries.value.single()
        assertEquals("secret=${Redaction.BLOB}", entry.message)
        assertEquals("TV (10.0.0.2)", entry.tv)
    }

    @Test
    fun loadingRewritesAnUnscrubbedFileAndAMultiLinePemIsFullyRemoved() {
        val file = File(tmp.root, "errors.log")
        // On disk the chain is one field with "\n" escaped; decode splits it into lines.
        val chain = "IOException: -----BEGIN PRIVATE KEY-----\\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC7\\nAoIBAQC7\\n-----END PRIVATE KEY-----"
        file.writeText("1000\tPairing\tsecret=$SECRET_HEX\t$chain\tTV (10.0.0.2)\t0.1.0\tPixel 9\n")

        val log = ErrorLog(file, "0.1.1", "Pixel 9")
        val onDisk = file.readText()
        for (text in listOf(log.report(), onDisk)) {
            assertFalse(text.contains(SECRET_HEX))
            assertFalse("short last PEM line", text.contains("AoIBAQC7"))
            assertFalse(text.contains("PRIVATE KEY"))
        }
        assertTrue(log.report().contains("IOException: ${Redaction.PEM}"))
    }

    @Test
    fun aSecretFieldNextToAPemReadsCleanlyAndScrubbingTwiceChangesNothing() {
        val once = Redaction.scrub("private_key: $PEM_KEY")
        assertEquals("private_key: ${Redaction.PEM}", once)
        assertEquals(once, Redaction.scrub(once))
    }

    @Test
    fun longMessagesAreCutBeforeScrubbing() {
        val log = ErrorLog(File(tmp.root, "errors.log"), "0.1.1", "Pixel 9")
        log.record("Connection", "TLS", IllegalStateException("x".repeat(100_000)))
        assertTrue(log.entries.value.single().chain.single().length <= ErrorLog.MAX_MESSAGE + 40)
    }

    @Test
    fun ordinaryMessagesAreLeftAlone() {
        for (text in listOf(
            "Connection lost (reset by peer)",
            "Not sent, the TV is not connected: https://www.netflix.com/title",
            "The TV hung up right after opening market://launch?id=com.google.android.youtube.tv; reconnecting",
            "Cannot reach 192.168.1.50:6466",
            "The code failed the local check (typo?)",
            "polo status: 400 STATUS_ERROR",
        )) {
            assertEquals(text, Redaction.scrub(text))
        }
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

    private companion object {
        // Made-up material in the real formats; none of it is a real key.
        const val PEM_KEY = "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC7\n-----END PRIVATE KEY-----"
        const val DER_BASE64 = "MIIC+zCCAeOgAwIBAgIJAKx7g3Q2nV5zMA0GCSqGSIb3DQEBCwUAMBQxEjAQBgNV"
        const val SHA256_HEX = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
        const val SECRET_HEX = "a3f1c2d4e5b60718293a4b5c6d7e8f90"
        const val FINGERPRINT = "9F:86:D0:81:88:4C:7D:65:9A:2F:EA:A0:C5:5A:D0:15:A3:BF:4F:1B"
    }
}
