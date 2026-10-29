package com.voicedock.ai

import com.voicedock.ai.security.CrashLogStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong

class CrashLogTest {
    @Test fun keepsOnlyNewestTenReports() {
        val dir = Files.createTempDirectory("voicedock-crash-store").toFile()
        val clock = AtomicLong(1000)
        val store = CrashLogStore(dir, now = { clock.getAndIncrement() })
        try {
            repeat(12) { store.save("report-$it") }
            val list = store.list()
            assertEquals(10, list.size)
            assertEquals(1011L, list.first().createdAt)
            assertEquals(1002L, list.last().createdAt)
            assertEquals(10, dir.listFiles().orEmpty().count { it.name.endsWith(".txt") })
        } finally { dir.deleteRecursively() }
    }

    @Test fun reportsAreScrubbedReadableDeletableAndRejectTraversal() {
        val dir = Files.createTempDirectory("voicedock-crash-store").toFile()
        val store = CrashLogStore(dir, now = { 1234L })
        try {
            val entry = store.save("failure for alice@example.com apiKey=secretValue")
            assertNotNull(entry)
            val log = store.read(entry!!.id)
            assertTrue(log!!.contains("[REDACTED]"))
            assertFalse(log.contains("alice@example.com"))
            assertNull(store.read("../secret.txt"))
            assertTrue(store.delete(entry.id))
            assertNull(store.read(entry.id))
            assertEquals(0, store.list().size)
        } finally { dir.deleteRecursively() }
    }
}
