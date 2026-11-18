package com.cairn.securepocket.transport

import java.time.Instant

enum class ByteSource { COMMAND, RESPONSE, AUDIO, PING, PONG }

/** One ordered transport observation. Everything the app knows about the device arrives as these. */
data class Observation(
    val sessionId: String,
    val sequence: Long,
    val at: Instant,
    val source: ByteSource,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is Observation && sessionId == other.sessionId && sequence == other.sequence &&
            at == other.at && source == other.source && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int =
        ((sessionId.hashCode() * 31 + sequence.hashCode()) * 31 + source.hashCode()) * 31 + bytes.contentHashCode()
}

/** Lifecycle events from the transport itself (not device bytes). */
sealed interface TransportEvent {
    data class StateChanged(val state: TransportState, val sessionId: String?) : TransportEvent
    data class SessionReset(val sessionId: String) : TransportEvent
    data class Error(val message: String, val retryable: Boolean) : TransportEvent
}

enum class TransportState { IDLE, SCANNING, CONNECTING, READY, SLEEPING, DISCONNECTED, ERROR }

/** UI submits intents; transport executes them and emits Observations + TransportEvents. */
sealed interface Intent {
    data object Connect : Intent
    data object Disconnect : Intent
    data class Send(val command: String) : Intent
}

interface Transport {
    val observations: kotlinx.coroutines.flow.Flow<Observation>
    val events: kotlinx.coroutines.flow.Flow<TransportEvent>
    val state: kotlinx.coroutines.flow.StateFlow<TransportState>
    fun submit(intent: Intent)
    fun close()
}
