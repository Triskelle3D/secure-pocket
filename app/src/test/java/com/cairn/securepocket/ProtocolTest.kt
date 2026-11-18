package com.cairn.securepocket

import com.cairn.securepocket.protocol.Commands
import com.cairn.securepocket.protocol.DeviceFile
import com.cairn.securepocket.protocol.ReplyParser
import com.cairn.securepocket.protocol.Response
import com.cairn.securepocket.protocol.Ts14
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {

    private fun bytes(s: String) = s.toByteArray(Charsets.US_ASCII)

    @Test fun encodeZeroPads() {
        val enc = Commands.encode("APP&BAT", padWidth = 16)
        assertEquals(16, enc.size)
        assertEquals("APP&BAT", String(enc, 0, 7, Charsets.US_ASCII))
        for (i in 7 until 16) assertEquals(0.toByte(), enc[i])
    }

    @Test fun encodeRejectsOta() {
        try {
            Commands.encode("APP&OTA&http://evil")
            fail("forbidden command accepted")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test fun encodeRejectsMissingPrefix() {
        try {
            Commands.encode("BAT")
            fail("unprefixed command accepted")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test fun splitReplyReassembles() {
        val p = ReplyParser()
        assertTrue(p.feed(bytes("MCU&BAT&")).isEmpty())
        val out = p.feed(bytes("97"))
        assertEquals(1, out.size)
        assertEquals(97, (out[0] as Response.Battery).pct)
    }

    @Test fun emptyDayTerminator() {
        val p = ReplyParser()
        val out = p.feed(bytes("MCU&LIST&0"))
        val list = out.filterIsInstance<Response.FileList>().single()
        assertTrue(list.files.isEmpty() && list.count == 0 && list.complete)
    }

    @Test fun fileLinesCollapse() {
        val p = ReplyParser()
        val d = "2026-11-18"
        p.feed(bytes("MCU&F&$d&20261118082946&6"))
        p.feed(bytes("MCU&F&$d&20261118083652&7"))
        p.feed(bytes("MCU&F&$d&20261118085640&6"))
        val out = p.feed(bytes("MCU&LIST&003"))
        val list = out.filterIsInstance<Response.FileList>().single()
        assertEquals(3, list.files.size)
        assertEquals(3, list.count)
        assertTrue(list.complete)
        val f0: DeviceFile = list.files[0]
        assertEquals(d, f0.date.toString())
        assertEquals(Ts14.parse("20261118082946"), f0.startAt)
        assertEquals(6, f0.lengthSeconds)
    }

    @Test fun singleRepliesDecode() {
        val p = ReplyParser()
        val sta = ReplyParser.decodeSingle(bytes("MCU&STA&20261118085640"))
        assertTrue(sta is Response.RecordingStart)
        assertEquals(Ts14.parse("20261118085640"), (sta as Response.RecordingStart).startAt)
        assertEquals(Response.RecordingStop, ReplyParser.decodeSingle(bytes("MCU&STO")))
        val v = ReplyParser.decodeSingle(bytes("MCU&FW&1.8.9"))
        assertEquals("1.8.9", (v as Response.VersionInfo).raw)
    }
}
