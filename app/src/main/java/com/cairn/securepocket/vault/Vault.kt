package com.cairn.securepocket.vault

import com.cairn.securepocket.capture.CaptureSink
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The vault is the source of truth: an immutable MP3 plus a small JSON sidecar.
 * Room indexes this later; the files carry the truth.
 */
class VaultStore(root: File) {

    private val rootDir = root
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

    /** Persist a finished capture under Pocket/<date>/<hhmmss>_id.mp3 + sidecar. */
    fun importCapture(
        capture: CaptureSink,
        date: LocalDate,
        startAt: LocalDateTime,
        deviceExpectedBytes: Long?,
        lengthSeconds: Int?
    ): Record {
        val day = date.toString()
        val ts = startAt.format(timeFmt)
        val sha = capture.sha256Hex()
        val localId = "$day-${ts}-${sha.take(8)}"
        val dir = File(rootDir, "Pocket/$day").apply { mkdirs() }
        val mp3 = File(dir, "${ts}_${capture.captureId}.mp3")
        capture.file.copyTo(mp3, overwrite = false)
        val partial = capture.state == CaptureSink.State.BROKEN
        val rec = Record(
            localId = localId,
            date = date,
            startAt = startAt,
            mp3 = mp3,
            sidecar = File(dir, "${ts}_${capture.captureId}.json"),
            bytes = mp3.length(),
            sha256 = sha,
            presence = if (partial) Presence.INTERRUPTED else Presence.LOCAL,
            verification = Verification.NOT_YET,
            deviceExpectedBytes = deviceExpectedBytes,
            lengthSeconds = lengthSeconds,
            partial = partial
        )
        writeSidecar(rec, Verification.NOT_YET)
        return rec
    }

    /** Verification is separate from persistence: size vs device claim + rehash. */
    fun verify(rec: Record): Verification {
        val size = rec.mp3.length()
        val verdict = when {
            rec.deviceExpectedBytes != null && size != rec.deviceExpectedBytes -> Verification.MISMATCHED
            sha256(rec.mp3) != rec.sha256 -> Verification.MISMATCHED
            rec.deviceExpectedBytes != null -> Verification.MATCHED
            else -> Verification.NOT_YET
        }
        if (verdict != Verification.NOT_YET) writeSidecar(rec.copy(verification = verdict), verdict)
        return verdict
    }

    /** All records, newest first. */
    fun list(): List<Record> {
        val out = mutableListOf<Record>()
        val vault = File(rootDir, "Pocket")
        if (!vault.isDirectory) return out
        vault.walkTopDown().filter { it.isFile && it.extension == "json" }.forEach { j ->
            readSidecar(j)?.let { out.add(it) }
        }
        return out.sortedWith(compareByDescending<Record> { it.date }.thenByDescending { it.startAt })
    }

    private fun writeSidecar(rec: Record, verdict: Verification) {
        val json = buildString {
            appendLine("{")
            appendLine("  \"schema\": 1,")
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
            appendLine("  \"partial\": \"${rec.partial}\"")
            append("}")
        }
        rec.sidecar.writeText(json)
    }

    private fun kv(t: String, key: String): String? {
        val needle = "\"" + key + "\""
        var i = t.indexOf(needle)
        if (i < 0) return null
        i += needle.length
        i = t.indexOf(":", i)
        if (i < 0) return null
        i += 1
        while (i < t.length && (t[i] == ' ' || t[i] == '\t')) i++
        if (i < t.length && t[i] == '\"') {
            i++
            val j = t.indexOf("\"", i)
            if (j < 0) return null
            return t.substring(i, j)
        }
        var j = i
        while (j < t.length && t[j] != ',' && t[j] != '\n' && t[j] != '}') j++
        return t.substring(i, j).trim()
    }

    private fun readSidecar(j: File): Record? {
        val text = runCatching { j.readText() }.getOrNull() ?: return null
        val f = listOf("localId","file","date","start","sha256","presence","verification","partial").associateWith { k -> kv(text, k) }
        val fileName = f["file"] ?: return null
        val day = runCatching { LocalDate.parse(f["date"] ?: return null) }.getOrNull() ?: return null
        val start = runCatching { LocalDateTime.parse(f["start"] ?: return null) }.getOrNull() ?: return null
        val bytes = kv(text, "bytes")?.toLongOrNull() ?: 0L
        val dv = kv(text, "deviceBytes")
        val deviceBytes = if (dv == null || dv == "null") null else dv.toLongOrNull()
        val lv = kv(text, "lengthSec")
        val lengthSec = if (lv == null || lv == "null") null else lv.toIntOrNull()
        val partial = f["partial"] == "true"
        val verification = runCatching { Verification.valueOf(f["verification"] ?: "NOT_YET") }
            .getOrDefault(Verification.NOT_YET)
        val presence = runCatching { Presence.valueOf(f["presence"] ?: "LOCAL") }.getOrDefault(Presence.LOCAL)
        val mp3 = File(j.parentFile, fileName)
        if (!mp3.isFile) return null
        return Record(
            localId = f["localId"] ?: "${day}_${start}",
            date = day,
            startAt = start,
            mp3 = mp3,
            sidecar = j,
            bytes = if (bytes > 0) bytes else mp3.length(),
            sha256 = f["sha256"] ?: "",
            presence = presence,
            verification = verification,
            deviceExpectedBytes = deviceBytes,
            lengthSeconds = lengthSec,
            partial = partial
        )
    }

    companion object {
        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { ins ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
