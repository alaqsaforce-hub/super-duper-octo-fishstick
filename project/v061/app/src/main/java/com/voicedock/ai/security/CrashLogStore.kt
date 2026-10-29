package com.voicedock.ai.security

import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger

/** Index data for one report kept inside the app-private files directory. */
data class CrashLogEntry(val id: String, val createdAt: Long, val bytes: Long)

/** Local-only, bounded crash-report storage. No upload/network code exists in this store. */
class CrashLogStore(
    private val directory: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxEntries: Int = MAX_ENTRIES,
) {
    companion object { const val MAX_ENTRIES = 10; const val MAX_CHARS = 100_000 }
    private val sequence = AtomicInteger()
    private val idPattern = Regex("crash-([0-9]+)-([0-9]+)\\.txt")

    /** Scrubs, atomically writes, then prunes older reports. Returns null on an I/O failure. */
    @Synchronized fun save(report: String): CrashLogEntry? = runCatching {
        if (!directory.exists() && !directory.mkdirs()) error("cannot create private crash-log directory")
        val timestamp = now()
        val id = "crash-$timestamp-${sequence.incrementAndGet()}.txt"
        val target = File(directory, id)
        val temp = File(directory, ".$id.tmp")
        val scrubbed = CrashReportScrubber.scrub(report).take(MAX_CHARS)
        FileOutputStream(temp).use { stream ->
            stream.write(("VoiceDock local crash report · $timestamp\n" + scrubbed).toByteArray(Charsets.UTF_8))
            stream.flush()
            stream.fd.sync()
        }
        if (!temp.renameTo(target)) {
            temp.delete()
            error("atomic crash-log rename failed")
        }
        prune()
        CrashLogEntry(id, timestamp, target.length())
    }.getOrNull()

    fun saveThrowable(error: Throwable): CrashLogEntry? = save(error.stackTraceToString())

    @Synchronized fun list(): List<CrashLogEntry> = runCatching {
        directory.listFiles().orEmpty().filter { it.isFile && idPattern.matches(it.name) }
            .sortedWith(compareByDescending<File> { timestamp(it.name) }.thenByDescending { sequenceNumber(it.name) })
            .take(maxEntries.coerceAtLeast(1))
            .map { file -> CrashLogEntry(file.name, timestamp(file.name), file.length()) }
    }.getOrDefault(emptyList())

    fun read(id: String): String? {
        val file = safeFile(id) ?: return null
        return runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
    }

    @Synchronized fun delete(id: String): Boolean = safeFile(id)?.let { runCatching { it.delete() }.getOrDefault(false) } ?: false

    @Synchronized fun deleteAll(): Int {
        val files = directory.listFiles().orEmpty().filter { it.isFile && idPattern.matches(it.name) }
        return files.count { runCatching { it.delete() }.getOrDefault(false) }
    }

    private fun safeFile(id: String): File? = if (idPattern.matches(id)) File(directory, id).takeIf { it.isFile } else null

    private fun prune() {
        val files = directory.listFiles().orEmpty().filter { it.isFile && idPattern.matches(it.name) }
            .sortedWith(compareByDescending<File> { timestamp(it.name) }.thenByDescending { sequenceNumber(it.name) })
        files.drop(maxEntries.coerceAtLeast(1)).forEach { it.delete() }
    }

    private fun timestamp(id: String): Long = idPattern.matchEntire(id)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
    private fun sequenceNumber(id: String): Int = idPattern.matchEntire(id)?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
}
