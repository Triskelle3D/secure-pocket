package com.cairn.securepocket.vault

import com.cairn.securepocket.capture.CaptureSink
import com.cairn.securepocket.protocol.DeviceFile
import java.io.File
import java.util.UUID

/** a3 announces the byte count, a1 streams the MP3. Publish only after the entire file arrives. */
class PocketDownload(val item: DeviceFile, cache: File) {
    private val sink = CaptureSink("pull-" + UUID.randomUUID(), cache)
    var expected = 0L
        private set
    val received get() = sink.written
    val temporaryAudio get() = sink.file

    fun verifiedSha256(): String {
        check(expected > 0 && received == expected)
        sink.finish()
        return sink.sha256Hex()
    }

    fun begin(bytes: Long) {
        require(expected == 0L && bytes > 0) { "Invalid recording length." }
        require(bytes < sink.tempDir.usableSpace) { "Insufficient phone storage." }
        expected = bytes
        sink.begin()
    }

    fun append(bytes: ByteArray) {
        check(expected > 0 && received + bytes.size <= expected) { "Unexpected audio length." }
        sink.append(bytes)
        check(sink.state == CaptureSink.State.APPENDING) { "Could not write audio." }
    }

    fun matches(copy: VaultStore.Record): Boolean {
        if (expected <= 0 || received != expected || copy.partial ||
            copy.verification == VaultStore.Verification.MISMATCHED || copy.bytes != expected ||
            !copy.mp3.isFile || copy.mp3.length() != expected) return false
        sink.finish()
        return sink.sha256Hex() == copy.sha256 && VaultStore.sha256(copy.mp3) == copy.sha256
    }

    fun commit(vault: VaultStore): VaultStore.Record {
        check(expected > 0 && received == expected)
        sink.finish()
        val sha = sink.sha256Hex()
        val existing = vault.list().firstOrNull { it.startAt == item.startAt && it.sha256 == sha && !it.partial && it.mp3.length() == expected }
        val r = existing?.copy(deviceExpectedBytes = expected, lengthSeconds = item.lengthSeconds)
            ?: vault.importCapture(sink, item.date, item.startAt, expected, item.lengthSeconds)
        check(vault.verify(r) == VaultStore.Verification.MATCHED)
        sink.file.delete()
        return r
    }

    fun discard() {
        sink.markPartial()
        sink.file.delete()
    }
}
