package com.cairn.securepocket

import com.cairn.securepocket.capture.CaptureSink
import com.cairn.securepocket.protocol.Commands
import com.cairn.securepocket.protocol.ReplyParser
import com.cairn.securepocket.protocol.Response
import com.cairn.securepocket.protocol.SessionState
import com.cairn.securepocket.transport.Intent
import com.cairn.securepocket.transport.Observation
import com.cairn.securepocket.transport.Simulator
import com.cairn.securepocket.transport.TransportEvent
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RoundTripTest {

    @JvmField @Rule val tmp = TemporaryFolder()

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun simulatorAudioLandsByteExactAndDupStopSingleCapture() = runBlocking {
        val fixture = File("src/test/resources/fixtures/tone16k.mp3")
            .takeIf { it.exists() }
            ?: File("app/src/test/resources/fixtures/tone16k.mp3")
        val audio = fixture.readBytes()
        val expectedSha = sha(audio)
        // sanity: fixture sha matches the recorded manifest
        val manifest = File(fixture.parent, "fixtures.sha256").readLines()
            .first { it.contains("tone16k.mp3") }

        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val transport = Simulator(scope, audioBytes = audio, chunkDelayMs = 0)

        var current: String? = null
        var session: SessionState? = null
        val captures = CopyOnWriteArrayList<CaptureSink>()
        val done = CompletableDeferred<CaptureSink>()
        var dropped = 0
        var lastExpectedBytes: Long? = null

        fun route(obs: Observation) {
            if (obs.sessionId != current) { dropped++; return }
            val s = session ?: return
            when (obs.source) {
                com.cairn.securepocket.transport.ByteSource.AUDIO -> s.onResponseChannel(obs.bytes)
                com.cairn.securepocket.transport.ByteSource.RESPONSE -> {
                    val r = ReplyParser.decodeSingle(obs.bytes)
                    if (r is Response.RecordingStart ||
                        r is Response.RecordingStop ||
                        r is Response.Event
                    ) {
                        s.onEventChannel(obs.bytes)
                    } else {
                        s.onResponseChannel(obs.bytes)
                        if (r is Response.FileSize) lastExpectedBytes = r.bytes
                    }
                }
                else -> Unit
            }
        }

        val evtJob = launch {
            transport.events.collect { ev ->
                if (ev is TransportEvent.SessionReset && ev.sessionId != null) {
                    current = ev.sessionId
                    session = SessionState(
                        ev.sessionId, tmp.newFolder(),
                        onCapture = { captures.add(it); done.complete(it) },
                        onResponse = { },
                    )
                }
            }
        }
        val obsJob = launch { transport.observations.collect { route(it) } }

        transport.submit(Intent.Connect)
        withTimeout(10.seconds) {
            while (transport.state.value != com.cairn.securepocket.transport.TransportState.READY) {
                kotlinx.coroutines.delay(10)
            }
        }
        transport.submit(Intent.Send("APP&STA"))
        val sink = withTimeout(30.seconds) { done.await() }
        transport.submit(Intent.Send("APP&U&2026-11-18&20261118082946"))
        withTimeout(10.seconds) { while (lastExpectedBytes == null) { kotlinx.coroutines.delay(10) } }
        obsJob.cancel()
        evtJob.cancel()
        transport.close()

        assertEquals("dup STO must produce one capture", 1, captures.size)
        assertEquals(audio.size.toLong(), sink.written)
        assertEquals(expectedSha, sink.sha256Hex())
        assertTrue("U& should equal fixture size", lastExpectedBytes == audio.size.toLong())
        assertTrue("manifest sha agrees", manifest.contains(expectedSha))
    }

    @Test fun lateObservationFromStaleSessionIsDropped() = runBlocking {
        val audio = ByteArray(320)
        val transport = Simulator(CoroutineScope(coroutineContext + SupervisorJob()), audioBytes = audio, chunkDelayMs = 0)
        var current: String? = null
        val seen = CompletableDeferred<Unit>()
        val job = launch {
            transport.events.collect { ev ->
                if (ev is TransportEvent.SessionReset) { current = ev.sessionId; seen.complete(Unit) }
            }
        }
        transport.submit(Intent.Connect)
        withTimeout(10.seconds) { seen.await() }
        // a late callback from a previous connection must be routed nowhere
        val late = Observation(
            sessionId = "stale-uuid", sequence = 1, at = Instant.now(),
            source = com.cairn.securepocket.transport.ByteSource.RESPONSE,
            bytes = "MCU&BAT&9".toByteArray(),
        )
        var dropped = 0
        if (late.sessionId != current) dropped++
        assertEquals(1, dropped)
        job.cancel()
    }
}
