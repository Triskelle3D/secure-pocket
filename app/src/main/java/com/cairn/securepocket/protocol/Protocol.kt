package com.cairn.securepocket.protocol

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Device date format used by LIST/U/D: YYYY-MM-DD */
object Dates {
    val iso: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    fun parse(s: String): LocalDate = LocalDate.parse(s, iso)
}

/** Wire-verified device timestamp: YYYYMMDDHHMMSS */
object Ts14 {
    val fmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    fun now(): String = LocalDateTime.now().format(fmt)
    fun parse(s: String): LocalDateTime = LocalDateTime.parse(s, fmt)
}

object Commands {
    const val SERVICE_UUID = "001120a0-2233-4455-6677-889912345678"
    const val CH_WRITE = "001120a2-2233-4455-6677-889912345678"
    const val CH_RESPONSE_AUDIO = "001120a1-2233-4455-6677-889912345678"
    const val CH_EVENTS = "001120a3-2233-4455-6677-889912345678"
    val NEVER_SEND = listOf(
        "OTA", "OT", "UPL", "UPLDROP", "UPAUTH", "UPKEY", "ENCPK", "SCHED", "SYNC", "LOG", "LOGF", "LOGACK",
    )
    /** Encode command; pad to [padWidth] with zero bytes if requested (padding TBD confirm on first
     *  hardware run; simulator does not pad; padWidth<=0 => no padding). */
    fun encode(command: String, padWidth: Int = 0): ByteArray {
        require(command.startsWith("APP&")) { "commands must start with APP&" }
        val head = command.substringAfter("APP&").substringBefore("&")
        if (head == "D") require(Regex("""APP&D&\d{4}-\d{2}-\d{2}&(?:\d{14}|PH\d{12})""").matches(command)) {
            "Deletion requires one exact recording identifier."
        }
        require(head !in NEVER_SEND) { "refusing to send forbidden command group: $head" }
        val bytes = command.toByteArray(Charsets.US_ASCII)
        if (padWidth <= 0 || bytes.size >= padWidth) return bytes
        return bytes + ByteArray(padWidth - bytes.size)
    }
}

sealed interface Response {
    data class Ack(val token: String) : Response                  // MCU&SK&OK style
    data class Battery(val pct: Int) : Response
    data class Space(val freeRaw: String, val totalRaw: String) : Response
    data class VersionInfo(val raw: String, val kind: Kind) : Response {
        enum class Kind { FW, BUILD, VER, WF }
    }
    data class Mac(val mac: String) : Response
    data class Usb(val mode: Int) : Response
    data class Ste(val raw: String) : Response
    data class UploadState(val raw: String) : Response            // MCU&UPLE&...
    data class WifiStatus(val savedNetworks: Int, val secondArg: String?) : Response
    data class DirsListing(val dirs: List<String>, val summaryRaw: String?) : Response
    data class DeleteResult(val accepted: Boolean) : Response
    data class FileList(val files: List<DeviceFile>, val count: Int, val complete: Boolean) : Response
    data class FileSize(val bytes: Long) : Response
    data class RecordingStart(val startAt: LocalDateTime) : Response
    data object RecordingStop : Response
    data class Event(val name: String) : Response                 // REC&CON, REC&CALL, OFF...
    data class UnknownRaw(val raw: String) : Response             // unparseable MCU& payload
    data class Completed(val response: Response) : Response       // wrapper: completion marker satisfied
}

data class DeviceFile(val date: LocalDate, val startAt: LocalDateTime, val lengthSeconds: Int, val wireId: String = startAt.format(Ts14.fmt))

/** Pure incremental parser: feed it a1/a3 bytes; it emits completed Responses when
 *  completion markers are satisfied (split replies + LIST count terminator + DIRS_SUM). */
class ReplyParser {
    private val buf = java.io.ByteArrayOutputStream()
    private val accFiles = ArrayList<DeviceFile>()
    private val accDirs = ArrayList<String>()

    companion object {
        private val F_LINE = Regex("""^MCU&F&(YYYY-MM-DD)&(TS14)&(\d+)$""".replace("YYYY-MM-DD", """\d{4}-\d{2}-\d{2}""").replace("TS14", """(?:\d{14}|PH\d{12})"""))
        private val LIST_COUNT = Regex("""^MCU&LIST&(\d+)$""")
        private val DIRS_SUM = Regex("""^MCU&DIRS_SUM&(\d+)$""")
        private val DIRS_ONE = Regex("""^MCU&DIRS&(YYYY-MM-DD)$""".replace("YYYY-MM-DD", """\d{4}-\d{2}-\d{2}"""))

        /** Decode one self-contained payload (single notification, no streaming needed).
         *  Returns null if the payload is a completion marker or non-response. */
        fun decodeSingle(bytes: ByteArray): Response? {
            val s = String(bytes, Charsets.US_ASCII).trimEnd('\u0000')
            if (!s.startsWith("MCU&")) return null
            return classify(s)
        }

        fun classify(s: String): Response? = when {
            s == "MCU&D" -> Response.DeleteResult(true)
            s == "MCU&D&ERR" -> Response.DeleteResult(false)
            s == "MCU&STO" -> Response.RecordingStop
            s == "MCU&OFF" -> Response.Event("OFF")
            s == "MCU&T&OK" -> Response.Ack("T")
            s == "MCU&SK&OK" -> Response.Ack("SK")
            s == "MCU&UNKNOWN" -> Response.UnknownRaw(s)
            s.startsWith("MCU&STA&") -> runCatching {
                Response.RecordingStart(Ts14.parse(s.removePrefix("MCU&STA&").let { if (it.startsWith("PH")) "20" + it.drop(2) else it }))
            }.getOrElse { Response.UnknownRaw(s) }
            s.startsWith("MCU&BAT&") -> Response.Battery(s.removePrefix("MCU&BAT&").toIntOrNull() ?: -1)
            s.startsWith("MCU&SPA&") -> s.removePrefix("MCU&SPA&").split("&").let {
                Response.Space(it.getOrElse(0) { "?" }, it.getOrElse(1) { "?" })
            }
            s.startsWith("MCU&MAC&") -> Response.Mac(s.removePrefix("MCU&MAC&"))
            s.startsWith("MCU&FW&") -> Response.VersionInfo(s.removePrefix("MCU&FW&"), Response.VersionInfo.Kind.FW)
            s.startsWith("MCU&BUILD&") -> Response.VersionInfo(s.removePrefix("MCU&BUILD&"), Response.VersionInfo.Kind.BUILD)
            s.startsWith("MCU&VER&") -> Response.VersionInfo(s.removePrefix("MCU&VER&"), Response.VersionInfo.Kind.VER)
            s.startsWith("MCU&WF&") -> Response.VersionInfo(s.removePrefix("MCU&WF&"), Response.VersionInfo.Kind.WF)
            s.startsWith("MCU&USB&") -> Response.Usb(s.removePrefix("MCU&USB&").toIntOrNull() ?: -1)
            s.startsWith("MCU&STE&") -> Response.Ste(s.removePrefix("MCU&STE&"))
            s.startsWith("MCU&UPLE&") -> Response.UploadState(s.removePrefix("MCU&UPLE&"))
            s.startsWith("MCU&WIFIS&") -> s.removePrefix("MCU&WIFIS&").split("&").let {
                Response.WifiStatus(it.getOrElse(0) { "?" }.toIntOrNull() ?: -1, it.getOrNull(1))
            }
            s.startsWith("MCU&U&") -> Response.FileSize(s.removePrefix("MCU&U&").toLongOrNull() ?: -1L)
            s.startsWith("MCU&REC&") -> Response.Event(s.removePrefix("MCU&"))
            s.startsWith("MCU&WPING") || s.startsWith("MCU&WIFIC") || s.startsWith("MCU&WIFIO") ||
                s.startsWith("MCU&OT&OVER") -> Response.Ack(s.substring(4).substringBefore("&"))
            F_LINE.matches(s) -> null // part of FileList; handled by streaming feed
            LIST_COUNT.matches(s) || DIRS_SUM.matches(s) || DIRS_ONE.matches(s) -> null // markers
            s.startsWith("MCU&") -> Response.UnknownRaw(s)
            else -> null
        }
    }

    /** Feed streaming bytes; returns responses that became complete. Handles split replies:
     *  a payload is complete when a NEW 'MCU&' start is seen or a completion marker arrives. */
    fun feed(bytes: ByteArray, completeNow: Boolean = false): List<Response> {
        buf.write(bytes)
        return drain(completeNow)
    }

    /** Split a1 control stream into payloads. Only used for CONTROL-phase bytes (not audio). */
    fun drain(forceComplete: Boolean = false): List<Response> {
        val out = ArrayList<Response>()
        val s = String(buf.toByteArray(), Charsets.US_ASCII)
        // find payload boundaries at next 'MCU&' occurrence
        val starts = ArrayList<Int>()
        var i = s.indexOf("MCU&")
        while (i >= 0) { starts.add(i); i = s.indexOf("MCU&", i + 4) }
        if (starts.isEmpty()) {
            // no marker yet: keep buffering
            return out
        }
        for ((idx, st) in starts.withIndex()) {
            val end = if (idx + 1 < starts.size) starts[idx + 1] else s.length
            val payload = s.substring(starts[idx], end).trimEnd('\u0000')
            // completion rules
            val isDirPart = DIRS_ONE.matches(payload)
            val isFPart = F_LINE.matches(payload)
            val isSumTerm = DIRS_SUM.matches(payload)
            val isCountTerm = LIST_COUNT.matches(payload)
            if (idx + 1 == starts.size && !forceComplete && (isDirPart || isFPart || payload.endsWith("&"))) {
                // last payload may continue with its terminator/value later: park it
                buf.reset(); buf.write(s.substring(starts[idx]).toByteArray(Charsets.US_ASCII)); return out
            }
            when {
                isFPart -> accFiles += F_LINE.matchEntire(payload)!!.let {
                    val date = Dates.parse(it.groupValues[1])
                    val wireId = it.groupValues[2]
                    val ts = if (wireId.startsWith("PH")) date.year.toString().take(2) + wireId.drop(2) else wireId
                    DeviceFile(date, Ts14.parse(ts), it.groupValues[3].toInt(), wireId)
                }
                isCountTerm -> {
                    out += Response.FileList(accFiles.toList(), LIST_COUNT.matchEntire(payload)!!.groupValues[1].toInt(), true)
                    accFiles.clear()
                }
                isSumTerm -> {
                    out += Response.DirsListing(accDirs.toList(), DIRS_SUM.matchEntire(payload)!!.groupValues[1])
                    accDirs.clear()
                }
                isDirPart -> accDirs += DIRS_ONE.matchEntire(payload)!!.groupValues[1]
                else -> classify(payload)?.let { out.add(it) }
            }
        }
        buf.reset()
        return out
    }

    fun reset() { buf.reset(); accFiles.clear(); accDirs.clear() }
}
