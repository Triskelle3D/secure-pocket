package com.cairn.securepocket

import com.cairn.securepocket.capture.CaptureSink
import com.cairn.securepocket.protocol.SessionState
import com.cairn.securepocket.vault.VaultStore
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CaptureVaultTest {

    @JvmField @Rule val tmp = TemporaryFolder()

    private fun ascii(s: String) = s.toByteArray(java.nio.charset.StandardCharsets.US_ASCII)

    @Test fun duplicateStopProducesSingleCapture() {
        val captures = CopyOnWriteArrayList<CaptureSink>()
        val session = SessionState(
            "s1", tmp.newFolder(),
            onCapture = { captures.add(it) },
            onResponse = { },
        )
        session.onEventChannel(ascii("MCU&STA&20261118085640"))
        assertNotNull("recording should be open", session.recording)
        session.onResponseChannel(ascii("MCU&BAT&50")) // arrives as a1 payload = audio while recording
        assertEquals(10L, session.recording?.written)
        session.onEventChannel(ascii("MCU&STA&20261118085640")) // duplicate start ignored
        assertEquals(10L, session.recording?.written)
        session.onEventChannel(ascii("MCU&STO"))
        session.onEventChannel(ascii("MCU&STO")) // duplicate stop: single capture
        assertEquals(1, captures.size)
        assertEquals(10L, captures[0].written)
        assertNull(session.recording)
    }

    @Test fun sleepMidRecordingKeepsPartial() {
        val captures = CopyOnWriteArrayList<CaptureSink>()
        val session = SessionState(
            "s2", tmp.newFolder(),
            onCapture = { captures.add(it) },
            onResponse = { },
        )
        session.onEventChannel(ascii("MCU&STA&20261118085640"))
        session.onResponseChannel(ascii("MCU&BAT&50MCU&BAT&51MCU&BAT&52"))
        session.onSleep()
        assertEquals(1, captures.size)
        assertEquals(CaptureSink.State.BROKEN, captures[0].state)
        assertTrue("partial must retain bytes", captures[0].written > 0)
    }

    @Test fun vaultImportVerifyAndLayout() {
        val payload = ByteArray(4096) { (it % 251).toByte() }
        val sinkDir = tmp.newFolder()
        val sink = CaptureSink("c1", sinkDir)
        sink.begin()
        sink.append(payload)
        sink.finish()

        val store = VaultStore(File(tmp.root, "vaultA"))
        val rec = store.importCapture(
            sink, LocalDate.of(2026, 11, 18),
            LocalDateTime.of(2026, 11, 18, 8, 29, 46),
            deviceExpectedBytes = payload.size.toLong(), lengthSeconds = null,
        )
        assertEquals(VaultStore.Verification.MATCHED, store.verify(rec))
        val rel = rec.mp3.absolutePath.substringAfter("vaultA/")
        assertTrue("layout Pocket/date/HHmmss: $rel", rel.matches(Regex("""Pocket/2026-11-18/082946_.*\.mp3""")))
        assertTrue(rec.sidecar.exists())
        assertTrue(rec.mp3.exists())
        assertEquals(1, store.list().size)

        // wrong device claim -> FAILED, and never touches the original
        val storeB = VaultStore(File(tmp.root, "vaultB"))
        val sinkB = CaptureSink("c2", sinkDir)
        sinkB.begin(); sinkB.append(payload); sinkB.finish()
        val recB = storeB.importCapture(
            sinkB, LocalDate.of(2026, 11, 18),
            LocalDateTime.of(2026, 11, 18, 8, 29, 46),
            deviceExpectedBytes = 999L, lengthSeconds = null,
        )
        assertEquals(VaultStore.Verification.MISMATCHED, storeB.verify(recB))
        assertEquals(payload.size.toLong(), recB.mp3.lengthOfSafe())
    }

    private fun File.lengthOfSafe(): Long = length()
}
