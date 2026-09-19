package com.andropilot.telemetry

import java.io.File
import java.io.IOException

/**
 * A crash-safe, bounded queue of JSON Lines on disk.
 *
 * Disk rather than memory because the interesting runs are the ones that end badly: a
 * crash, a battery pull, an app the OS killed. Those are exactly the records worth
 * uploading, and an in-memory queue loses precisely them.
 *
 * Segments rather than one file with an offset pointer: a segment is uploaded whole and
 * then deleted, so "what has been sent" is a fact about the filesystem instead of a number
 * that can disagree with it after a partial write. The cost is that a batch may be re-sent
 * after a crash between the upload and the delete; the server keys on event ids and can
 * drop a repeat, which is the cheaper of the two mistakes.
 */
internal class Spool(
    private val directory: File,
    private val maxSegmentBytes: Long,
    private val maxTotalBytes: Long,
) {
    private val lock = Any()
    private var current: File? = null
    private var currentBytes: Long = 0
    private var sequence: Long = 0

    /** Lines discarded because the spool was full. Reported so a gap is never silent. */
    var dropped: Long = 0
        private set

    init {
        directory.mkdirs()
    }

    fun append(line: String) {
        synchronized(lock) {
            val bytes = line.toByteArray(Charsets.UTF_8).size.toLong() + 1
            if (totalBytes() + bytes > maxTotalBytes && !evictOldest()) {
                dropped++
                return
            }
            val target = current ?: newSegment().also { current = it; currentBytes = 0 }
            try {
                target.appendText(line + "\n", Charsets.UTF_8)
                currentBytes += bytes
            } catch (e: IOException) {
                // A sink that throws would take the session down with it. Losing telemetry
                // is always preferable to losing the automation it was observing.
                dropped++
                return
            }
            if (currentBytes >= maxSegmentBytes) seal()
        }
    }

    /** Closes the open segment so the uploader may take it. No-op when nothing is buffered. */
    fun seal() {
        synchronized(lock) {
            val open = current ?: return
            current = null
            currentBytes = 0
            if (open.length() == 0L) open.delete()
        }
    }

    /** Sealed segments, oldest first. The open segment is never returned. */
    fun pending(): List<File> = synchronized(lock) {
        val open = current
        directory.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) }
            ?.filter { it != open }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    fun totalBytes(): Long = directory.listFiles()?.sumOf { it.length() } ?: 0

    private fun newSegment(): File {
        sequence++
        return File(directory, "seg-%013d-%04d%s".format(System.currentTimeMillis(), sequence, SUFFIX))
    }

    /** Drops the oldest sealed segment. Returns false when only the open one is left. */
    private fun evictOldest(): Boolean {
        val oldest = pending().firstOrNull() ?: return false
        val lines = runCatching { oldest.readLines().size.toLong() }.getOrDefault(0L)
        if (!oldest.delete()) return false
        dropped += lines
        return true
    }

    private companion object {
        const val SUFFIX = ".jsonl"
    }
}
