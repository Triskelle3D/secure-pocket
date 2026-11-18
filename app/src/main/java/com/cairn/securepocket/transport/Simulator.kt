package com.cairn.securepocket.transport

import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Deterministic PKT01 simulator. Same Transport contract as the real BLE link so the
 * whole stack (protocol, capture, vault, UI) runs and is testable without hardware.
 * Wire-accurate replies captured from live sessions (docs/PROTOCOL.md).
 */
class Simulator(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    val audioBytes: ByteArray = DEFAULT_AUDIO,
    private val chunkSize: Int = 180,
    private val chunkDelayMs: Long = 20L,
) : Transport {

    companion object {
        // Deterministic filler for UI demos; tests inject the real tone fixture bytes.
        val DEFAULT_AUDIO: ByteArray = ByteArray(6 * 4000) { (it * 7 + 13).toByte() }
        const val TONE_DATE = "2026-11-18"
        private const val UUID_SUFFIX = "-2233-4455-6677-889912345678"
    }

    private val _state = MutableStateFlow(TransportState.IDLE)
    override val state: StateFlow<TransportState> = _state

    private val obsChannel = Channel<Observation>(Channel.UNLIMITED)
    override val observations: Flow<Observation> = obsChannel.receiveAsFlow()

    private val evtChannel = Channel<TransportEvent>(Channel.UNLIMITED)
    override val events: Flow<TransportEvent> = evtChannel.receiveAsFlow()

    private val seq = AtomicLong(0)
    @Volatile private var sessionId: String = ""
    @Volatile private var recording = false
    @Volatile private var startedAt: String = ""
    @Volatile private var batSplitDone = false

    override fun submit(intent: Intent) {
        scope.launch {
            when (intent) {
                is Intent.Connect -> connect()
                is Intent.Disconnect -> disconnect()
                is Intent.Send -> handleCommand(intent.command)
            }
        }
    }

    private suspend fun connect() {
        _state.value = TransportState.SCANNING
        emitEvent(TransportEvent.StateChanged(TransportState.SCANNING, null))
        kotlinx.coroutines.delay(60)
        _state.value = TransportState.CONNECTING
        emitEvent(TransportEvent.StateChanged(TransportState.CONNECTING, null))
        kotlinx.coroutines.delay(60)
        sessionId = UUID.randomUUID().toString()
        seq.set(0)
        batSplitDone = false
        recording = false
        emitEvent(TransportEvent.SessionReset(sessionId))
        emitEvent(TransportEvent.StateChanged(TransportState.READY, sessionId))
        _state.value = TransportState.READY
    }

    private suspend fun disconnect() {
        if (recording) {
            // device sleeps mid-record: OFF first, capture stays partial upstream
            recording = false
            emit(ByteSource.RESPONSE, "MCU&OFF")
        }
        _state.value = TransportState.DISCONNECTED
        emitEvent(TransportEvent.StateChanged(TransportState.DISCONNECTED, null))
    }

    private suspend fun handleCommand(cmd: String) {
        if (_state.value != TransportState.READY && !cmd.startsWith("APP&STA")) {
            emitEvent(TransportEvent.Error("not ready", retryable = true))
            return
        }
        emit(ByteSource.COMMAND, cmd)
        when {
            cmd == "APP&BAT" -> battery()
            cmd == "APP&SPACE" -> reply("MCU&SPA&059802&059814")
            cmd == "APP&FW" -> reply("MCU&FW&1.8.9")
            cmd == "APP&BUILD" -> reply("MCU&BUILD&1.8.9+97&V9+?")
            cmd == "APP&VER" -> reply("MCU&VER&26.11.2+97&?")
            cmd == "APP&WF" -> reply("MCU&WF&V9")
            cmd == "APP&MAC" -> reply("MCU&MAC&f44b26423747")
            cmd == "APP&GET&USB" -> reply("MCU&USB&0")
            cmd == "APP&STE" -> reply("MCU&STE&0")
            cmd == "APP&UPLE" -> reply("MCU&UPLE&END&0&10350")
            cmd == "APP&WIFIS" -> reply("MCU&WIFIS&0")
            cmd == "APP&WPING" -> reply("MCU&WPING")
            cmd.startsWith("APP&T&") -> reply("MCU&T&OK")
            cmd == "APP&LIST_DIRS" -> { reply("MCU&DIRS&$TONE_DATE"); reply("MCU&DIRS_SUM&001") }
            cmd == "APP&LIST&$TONE_DATE" -> {
                reply("MCU&F&$TONE_DATE&20261118082946&6")
                reply("MCU&F&$TONE_DATE&20261118083652&7")
                reply("MCU&F&$TONE_DATE&20261118085640&6")
                reply("MCU&LIST&003")
            }
            cmd.startsWith("APP&LIST&") -> reply("MCU&LIST&0")
            cmd.startsWith("APP&U&") -> reply("MCU&U&${audioBytes.size}")
            cmd == "APP&STA" -> startRecording()
            cmd == "APP&STO" -> stopRecording()
            else -> reply("MCU&UNKNOWN")
        }
    }

    private suspend fun battery() {
        if (!batSplitDone) {
            // exercise split-reply reassembly once per session: 'MCU&BAT&' + '97'
            batSplitDone = true
            emitRaw(ByteSource.RESPONSE, "MCU&BAT&".toByteArray())
            kotlinx.coroutines.delay(10)
            emitRaw(ByteSource.RESPONSE, "97".toByteArray())
        } else reply("MCU&BAT&97")
    }

    private suspend fun startRecording() {
        recording = true
        startedAt = com.cairn.securepocket.protocol.Ts14.now()
        reply("MCU&STA&$startedAt")
        var off = 0
        while (off < audioBytes.size) {
            if (!recording) return // stopped/slept: caller saw STO/OFF already
            val end = minOf(off + chunkSize, audioBytes.size)
            emitRaw(ByteSource.AUDIO, audioBytes.copyOfRange(off, end))
            off = end
            kotlinx.coroutines.delay(chunkDelayMs)
        }
        if (recording) {
            // natural end of recording: device announces stop and repeats it once
            recording = false
            reply("MCU&STO")
            reply("MCU&STO")
        }
    }

    private suspend fun stopRecording() {
        if (!recording) return
        recording = false
        reply("MCU&STO")
        // duplicate STO exactly once: real devices repeat; must not double-capture
        reply("MCU&STO")
    }

    private suspend fun reply(text: String) {
        kotlinx.coroutines.delay(25)
        emitRaw(ByteSource.RESPONSE, text.toByteArray())
    }

    private suspend fun emit(src: ByteSource, text: String) = emitRaw(src, text.toByteArray())

    private suspend fun emitRaw(src: ByteSource, bytes: ByteArray) {
        obsChannel.send(Observation(sessionId, seq.incrementAndGet(), Instant.now(), src, bytes))
    }

    private suspend fun emitEvent(e: TransportEvent) { evtChannel.send(e) }

    override fun close() { scope.cancel() }
}
