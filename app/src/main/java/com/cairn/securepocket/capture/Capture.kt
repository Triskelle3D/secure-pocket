package com.cairn.securepocket.capture

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * One file writer per capture: begin -> append* -> finish (or markPartial on loss).
 * A sink is created the moment capture identity is known, before any audio bytes.
 * Appends after finish are dropped (dup STO cannot double-write a capture).
 * Original bytes are sacred: this class never transforms them.
 */
class CaptureSink(val captureId: String, val tempDir: File) {

    enum class State { IDLE, APPENDING, CLOSED, BROKEN, FAILED }

    val file = File(tempDir, "capture-$captureId.part")
    @Volatile var state: State = State.IDLE
        private set
    @Volatile var written: Long = 0
        private set
    private var raf: RandomAccessFile? = null

    fun begin() {
        if (state != State.IDLE) return
        tempDir.mkdirs()
        raf = RandomAccessFile(file, "rw").apply { setLength(0) }
        state = State.APPENDING
    }

    fun append(bytes: ByteArray) {
        if (state != State.APPENDING) return // duplicates after close are dropped
        try {
            raf!!.write(bytes)
            written += bytes.size
        } catch (e: Exception) {
            state = State.FAILED
        }
    }

    fun flush() {
        try { raf?.channel?.force(true) } catch (_: Exception) {}
    }

    /** Closes the writer, returns the file. Idempotent. */
    fun finish(): File {
        if (state == State.APPENDING) {
            flush(); closeQuietly()
            state = State.CLOSED
        }
        return file
    }

    /** Interruption (device sleep / disconnect / crash-recovery): keep bytes, mark broken. */
    fun markPartial() {
        if (state == State.APPENDING) {
            flush(); closeQuietly()
            state = State.BROKEN
        }
    }

    private fun closeQuietly() {
        try { raf?.close() } catch (_: Exception) {}
        raf = null
    }

    /** Recomputes digest from the bytes actually on disk (never trusts a cache). */
    fun sha256Hex(): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { inp ->
            val buf = ByteArray(8192)
            while (true) { val n = inp.read(buf); if (n <= 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
