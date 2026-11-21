package com.cairn.securepocket.vault

import com.cairn.securepocket.capture.CaptureSink
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** The source of truth is a set of authenticated encrypted files; names retain the old date layout. */
class VaultStore(root: File, private val cacheDir: File = File(root.parentFile, "cache")) {
    private val rootDir = root
    private val plainDir = File(cacheDir, "secure-plaintext")
    private val timeFmt = DateTimeFormatter.ofPattern("HHmmss")

    enum class Verification { NOT_YET, MATCHED, MISMATCHED }
    enum class Presence { DEVICE_ONLY, LOCAL, INTERRUPTED }

    data class Record(
        val localId: String,
        val date: LocalDate,
        val startAt: LocalDateTime,
        val mp3: File,
        val sidecar: File,
        val bytes: Long,
        val sha256: String,
        val presence: Presence,
        val verification: Verification,
        val deviceExpectedBytes: Long?,
        val lengthSeconds: Int?,
        val partial: Boolean
    )

    @Volatile var migrationError: String? = null

    @Synchronized fun importCapture(capture: CaptureSink, date: LocalDate, startAt: LocalDateTime,
        deviceExpectedBytes: Long?, lengthSeconds: Int?): Record {
        val day = date.toString()
        val ts = startAt.format(timeFmt)
        val sha = capture.sha256Hex()
        val localId = "$day-${ts}-${sha.take(8)}"
        val dir = File(rootDir, "Pocket/$day").apply { mkdirs() }
        val mp3 = File(dir, "${ts}_${capture.captureId}.mp3.enc")
        DeviceEncryption.encryptFile(capture.file, mp3, purpose(mp3))
        val partial = capture.state == CaptureSink.State.BROKEN
        val rec = Record(localId, date, startAt, mp3, File(dir, "${ts}_${capture.captureId}.json.enc"),
            capture.written, sha, if (partial) Presence.INTERRUPTED else Presence.LOCAL,
            Verification.NOT_YET, deviceExpectedBytes, lengthSeconds, partial)
        writeSidecar(rec, Verification.NOT_YET)
        return rec
    }

    @Synchronized fun verify(rec: Record): Verification {
        val plain = materialize(rec.mp3)
        val verdict = try { when {
            plain.length() != rec.bytes -> Verification.MISMATCHED
            rec.deviceExpectedBytes != null && plain.length() != rec.deviceExpectedBytes -> Verification.MISMATCHED
            sha256(plain) != rec.sha256 -> Verification.MISMATCHED
            rec.deviceExpectedBytes != null -> Verification.MATCHED
            else -> Verification.NOT_YET
        } } finally { plain.delete() }
        if (verdict != Verification.NOT_YET) writeSidecar(rec.copy(verification = verdict), verdict)
        return verdict
    }

    @Synchronized fun list(): List<Record> {
        val vault = File(rootDir, "Pocket")
        if (!vault.isDirectory) return emptyList()
        return vault.walkTopDown().filter { it.isFile && it.name.endsWith(".json.enc") }
            .mapNotNull(::readSidecar).toList()
            .sortedWith(compareByDescending<Record> { it.date }.thenByDescending { it.startAt })
    }

    fun materialize(encrypted: File): File {
        require(encrypted.isFile) { "Encrypted recording artifact is missing." }
        plainDir.mkdirs()
        val target = File.createTempFile("audio-", ".plain", plainDir)
        try { DeviceEncryption.decryptFile(encrypted, target, purpose(encrypted)) }
        catch (e: Exception) { target.delete(); throw e }
        return target
    }

    fun artifact(rec: Record, suffix: String): File = File(rec.mp3.parentFile,
        rec.mp3.name.removeSuffix(".mp3.enc") + suffix + ".enc")

    fun readText(file: File): String = readEncryptedText(file)
    fun writeText(file: File, text: String) = encryptText(text, file)
    fun deleteArtifact(file: File) { file.delete() }

    private fun encryptText(text: String, file: File) = writeEncryptedText(file, text)

    private fun writeSidecar(rec: Record, verdict: Verification) {
        val json = buildString {
            appendLine("{")
            appendLine("  \"schema\": 2,")
            appendLine("  \"localId\": \"${rec.localId}\",")
            appendLine("  \"file\": \"${rec.mp3.name}\",")
            appendLine("  \"date\": \"${rec.date}\",")
            appendLine("  \"start\": \"${rec.startAt}\",")
            appendLine("  \"bytes\": ${rec.bytes},")
            appendLine("  \"sha256\": \"${rec.sha256}\",")
            appendLine("  \"presence\": \"${rec.presence}\",")
            appendLine("  \"verification\": \"$verdict\",")
            appendLine("  \"deviceBytes\": ${rec.deviceExpectedBytes ?: "null"},")
            appendLine("  \"lengthSec\": ${rec.lengthSeconds ?: "null"},")
            append("  \"partial\": \"${rec.partial}\"\n}")
        }
        encryptText(json, rec.sidecar)
    }

    private fun readSidecar(file: File): Record? {
        // Authentication failures must surface. Never hide a tampered record as if it were absent.
        val text = readText(file)
        return runCatching {
        fun value(key: String): String? = Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*(?:\\\"([^\\\"]*)\\\"|([^,}\\s]+))")
            .find(text)?.let { it.groups[1]?.value ?: it.groups[2]?.value }
        val mp3 = File(file.parentFile, value("file") ?: return null)
        if (!mp3.isFile) return null
        val date = LocalDate.parse(value("date") ?: return null)
        val start = LocalDateTime.parse(value("start") ?: return null)
        val device = value("deviceBytes")?.takeUnless { it == "null" }?.toLongOrNull()
        Record(value("localId") ?: "${date}_$start", date, start, mp3, file,
            value("bytes")?.toLongOrNull() ?: 0L, value("sha256") ?: "",
            runCatching { Presence.valueOf(value("presence") ?: "LOCAL") }.getOrDefault(Presence.LOCAL),
            runCatching { Verification.valueOf(value("verification") ?: "NOT_YET") }.getOrDefault(Verification.NOT_YET),
            device, value("lengthSec")?.takeUnless { it == "null" }?.toIntOrNull(), value("partial") == "true")
        }.getOrNull()
    }

    /** Import existing plaintext records and companion artifacts atomically before deleting their old copies. */
    @Synchronized fun migrateLegacyFiles() {
        val pocket = File(rootDir, "Pocket")
        if (!pocket.isDirectory) return
        val oldFiles = pocket.walkTopDown().filter { it.isFile && !it.name.endsWith(".enc") }.toList()
        oldFiles.forEach { old ->
            require(old.length() <= MAX_LEGACY_ARTIFACT_BYTES) {
                "A legacy file exceeds the safe 1 GiB migration limit; its original was preserved."
            }
            val encrypted = File(old.parentFile, old.name + ".enc")
            if (!encrypted.exists()) DeviceEncryption.encryptFile(old, encrypted, purpose(encrypted))
            val check = materialize(encrypted)
            try {
                check(old.length() == check.length() && sha256(old) == sha256(check)) {
                    "Legacy library migration verification failed; the original file was preserved."
                }
            } finally { check.delete() }
            check(old.delete()) { "Could not remove migrated plaintext file." }
        }
        // Legacy sidecars refer to their old MP3 name. Rewrite references to the encrypted artifact.
        pocket.walkTopDown().filter { it.isFile && it.name.endsWith(".json.enc") }.forEach { sidecar ->
            val text = readText(sidecar)
            val oldName = Regex("\\\"file\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(text)?.groupValues?.get(1) ?: return@forEach
            val newName = if (oldName.endsWith(".enc")) oldName else "$oldName.enc"
            if (newName != oldName) encryptText(text.replace(oldName, newName), sidecar)
        }
        migrationError = null
    }

    private fun purpose(file: File) = artifactPurpose(file)

    companion object {
        private const val MAX_LEGACY_ARTIFACT_BYTES = 1024L * 1024L * 1024L
        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { inp ->
                val buf = ByteArray(8192)
                while (true) { val n = inp.read(buf); if (n <= 0) break; md.update(buf, 0, n) }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

internal fun readEncryptedText(file: File): String {
    require(file.isFile) { "Encrypted artifact is missing." }
    val bytes = DeviceEncryption.decryptBytes(file.readBytes(), artifactPurpose(file))
    return String(bytes, Charsets.UTF_8)
}

internal fun writeEncryptedText(file: File, text: String) {
    file.parentFile?.mkdirs()
    val temp = File.createTempFile(".note-", ".tmp", file.parentFile)
    try {
        temp.writeBytes(DeviceEncryption.encryptBytes(text.toByteArray(Charsets.UTF_8), artifactPurpose(file)))
        java.io.FileOutputStream(temp, true).use { it.fd.sync() }
        java.nio.file.Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    } finally { temp.delete() }
}
