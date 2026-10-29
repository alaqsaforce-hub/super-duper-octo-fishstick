package com.voicedock.ai

import com.voicedock.ai.security.CrashReportScrubber
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportTest {
    @Test fun scrubsEmailCredentialsAndProviderKeys() {
        val raw = "contact=a.person+qa@example.com apiKey=sk-proj-abcdefghijklmnopqrstuvwxyz password: \"s3cret-value\""
        val safe = CrashReportScrubber.scrub(raw)
        listOf("a.person+qa@example.com", "sk-proj-abcdefghijklmnopqrstuvwxyz", "s3cret-value").forEach { assertFalse(safe.contains(it)) }
        assertTrue(safe.contains("[REDACTED]"))
    }

    @Test fun scrubsJwtAuthorizationAndLongDigits() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyIn0.signature12345"
        val raw = "Authorization: Bearer $jwt card=4111111111111111"
        val safe = CrashReportScrubber.scrub(raw)
        assertFalse(safe.contains(jwt))
        assertFalse(safe.contains("4111111111111111"))
        assertTrue(safe.contains("[REDACTED]"))
    }

    @Test fun preservesShortDiagnosticNumbers() {
        val safe = CrashReportScrubber.scrub("VoiceDock 0.6.1 failed in 2026 at line 42")
        assertTrue(safe.contains("2026"))
        assertTrue(safe.contains("line 42"))
    }

    @Test fun scrubsHundredKilobyteTraceInLinearTime() {
        val row = "apiKey=sk-proj-abcdefghijklmnopqrstuvwxyz contact=a.person@example.com digits=1234567890123456\n"
        val raw = row.repeat((100_000 / row.length) + 1).take(100_000)
        val start = System.nanoTime()
        val safe = CrashReportScrubber.scrub(raw)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("100 KB scrub took ${elapsedMs}ms", elapsedMs < 1500)
        assertTrue(safe.contains("[REDACTED]"))
        assertFalse(safe.contains("a.person@example.com"))
    }
}
