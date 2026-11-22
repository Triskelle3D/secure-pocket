package com.cairn.securepocket.protocol

import com.cairn.securepocket.capture.CaptureSink
import java.io.File
import java.time.LocalDateTime

/**
 * Per-connection session state. One instance per sessionId, always fresh —
 * stale-session observations are dropped upstream (Glue) and this class never
 * carries state across sessions.
 *
 * Channel routing (based on observed PKT01 responses):
 *  - a3 (events): MCU&STA&ts / MCU&STO / MCU&REC&CON|CALL / MCU&OFF
 *  - a1 (responses): control replies while STANDBY; raw audio while CAPTURING,
 *    terminated by the embedded byte-sequence MCU&STO (marker scan with
 *    hold-back of up-to-6-byte partial tails so split markers still fire).
 */
class SessionState(
    val sessionId: String,
    val tempDir: File,
    private val onCapture: (CaptureSink) -> Unit = {},
    private val onResponse: (Response) -> Unit = {}
) {
    enum class Phase { STANDBY, CAPTURING }

    @Volatile var phase: Phase = Phase.STANDBY
        private set

    @Volatile var recording: CaptureSink? = null
        private set

    @Volatile var recordingStartedAt: LocalDateTime? = null
        private set

    val health = HashMap<String, Response>()
    var lastFileList: Response.FileList? = null
        private set
    var lastDirs: Response.DirsListing? = null
        private set

    private val parser = ReplyParser()
    private var stoArmed = false

    /** bytes from the a3 event channel */
    fun onEventChannel(bytes: ByteArray) {
        val resp = ReplyParser.decodeSingle(bytes) ?: return
        onResponse(resp)
        handle(resp)
    }

    /** bytes from the a1 response+audio channel */
    fun onResponseChannel(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        if (phase == Phase.CAPTURING) {
            appendAudio(bytes)
            return
        }
        for (r in parser.feed(bytes)) {
            onResponse(r)
            if (r is Response.FileList && r.complete) lastFileList = r
            if (r is Response.DirsListing && r.summaryRaw != null) lastDirs = r
            handle(r)
        }
    }

    /** device announced sleep/disconnect mid-session */
    fun onSleep() {
        recording?.let { sink ->
            sink.append(pendingTail)
            sink.markPartial()
            onCapture(sink)
        }
        resetToStandby()
    }

    private fun handle(resp: Response) {
        when (resp) {
            is Response.RecordingStart -> if (phase == Phase.STANDBY) {
                val ts = resp.startAt.format(
                    java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
                )
                val sink = CaptureSink("$sessionId-$ts", tempDir)
                sink.begin()
                recording = sink
                recordingStartedAt = resp.startAt
                stoArmed = true
                phase = Phase.CAPTURING
                pendingTail = ByteArray(0)
                parser.reset()
            }

            is Response.RecordingStop -> if (stoArmed && phase == Phase.CAPTURING) {
                val sink = recording
                if (sink != null) {
                    sink.append(pendingTail)
                    sink.flush()
                    sink.finish()
                    onCapture(sink)
                }
                resetToStandby()
            }

            is Response.Event -> if (resp.name == "OFF") onSleep()

            is Response.VersionInfo -> health[resp.kind.toString()] = resp
            is Response.Battery -> health["Battery"] = resp
            is Response.Space -> health["Space"] = resp
            is Response.Mac -> health["Mac"] = resp
            is Response.Usb -> health["Usb"] = resp
            is Response.Ste -> health["Ste"] = resp
            is Response.UploadState -> health["UploadState"] = resp
            is Response.WifiStatus -> health["WifiStatus"] = resp
            is Response.FileSize -> health["lastFileSize"] = resp

            else -> Unit
        }
    }

    // ---- audio path ----------------------------------------------------------

    private val marker = "MCU&STO".toByteArray(Charsets.US_ASCII)
    private var pendingTail = ByteArray(0)

    private fun appendAudio(bytes: ByteArray) {
        val sink = recording ?: return
        val buf = if (pendingTail.isEmpty()) bytes else pendingTail + bytes
        val idx = buf.indexOfMarker(marker)
        if (idx >= 0) {
            if (idx > 0) sink.append(buf.copyOfRange(0, idx))
            pendingTail = ByteArray(0)
            handle(Response.RecordingStop)
            // anything after the marker within this chunk is a control reply
            if (idx + marker.size < buf.size) {
                val rest = buf.copyOfRange(idx + marker.size, buf.size)
                for (r in parser.feed(rest)) onResponse(r)
            }
            return
        }
        val hold = partialMarkerLen(buf)
        val appendLen = buf.size - hold
        if (appendLen > 0) sink.append(buf.copyOfRange(0, appendLen))
        pendingTail = if (hold > 0) buf.copyOfRange(buf.size - hold, buf.size) else ByteArray(0)
    }

    /** how many trailing bytes of buf could still grow into the marker */
    private fun partialMarkerLen(buf: ByteArray): Int {
        val max = minOf(marker.size - 1, buf.size)
        for (k in max downTo 1) {
            var ok = true
            for (i in 0 until k) if (buf[buf.size - k + i] != marker[i]) { ok = false; break }
            if (ok) return k
        }
        return 0
    }

    private fun ByteArray.indexOfMarker(pat: ByteArray): Int {
        if (pat.size > this.size) return -1
        outer@ for (i in 0..this.size - pat.size) {
            for (j in pat.indices) if (this[i + j] != pat[j]) continue@outer
            return i
        }
        return -1
    }

    private fun resetToStandby() {
        stoArmed = false
        recording = null
        phase = Phase.STANDBY
        pendingTail = ByteArray(0)
        // clear timestamp only after capture delivered (Glue reads it in onCapture)
        recordingStartedAt = null
    }
}
