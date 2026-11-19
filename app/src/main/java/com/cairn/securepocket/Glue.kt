package com.cairn.securepocket

import android.app.Application
import com.cairn.securepocket.capture.CaptureSink
import com.cairn.securepocket.protocol.Response
import com.cairn.securepocket.protocol.SessionState
import com.cairn.securepocket.transport.Intent
import com.cairn.securepocket.transport.Observation
import com.cairn.securepocket.transport.Simulator
import com.cairn.securepocket.transport.Transport
import com.cairn.securepocket.transport.TransportEvent
import com.cairn.securepocket.vault.DriveImporter
import com.cairn.securepocket.vault.ImportSummary
import com.cairn.securepocket.vault.VaultStore
import java.io.File
import java.time.LocalDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SessionRunner(
    private val transport: Transport,
    private val vaultRoot: File,
    private val cacheDir: File,
    private val scope: CoroutineScope,
) {
    val vault = VaultStore(vaultRoot)
    private val vaultLock = Any()

    private val _health = MutableStateFlow<Map<String, Response>>(emptyMap())
    val health: StateFlow<Map<String, Response>> = _health.asStateFlow()
    private val _records = MutableStateFlow<List<VaultStore.Record>>(emptyList())
    val records: StateFlow<List<VaultStore.Record>> = _records.asStateFlow()
    private val _connection = MutableStateFlow("IDLE")
    val connection: StateFlow<String> = _connection.asStateFlow()
    private val _lastCapture = MutableStateFlow<String?>(null)
    val lastCapture: StateFlow<String?> = _lastCapture.asStateFlow()
    private val _recordingBytes = MutableStateFlow(0L)
    val recordingBytes: StateFlow<Long> = _recordingBytes.asStateFlow()
    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    @Volatile private var currentSession: String? = null
    private var session: SessionState? = null
    private var pendingBytes = 0L
    private var lastExpectedBytes: Long? = null

    init {
        refresh()
        scope.launch {
            transport.events.collect { ev ->
                when (ev) {
                    is TransportEvent.StateChanged ->
                        _connection.value = ev.state.name + (ev.sessionId?.let { " · ${it.take(4)}" } ?: "")
                    is TransportEvent.SessionReset -> startSession(ev.sessionId)
                    is TransportEvent.Error -> _connection.value = "ERROR: ${ev.message}"
                }
            }
        }
        scope.launch {
            transport.observations.collect { obs ->
                if (obs.sessionId != currentSession) return@collect // late-delivery drop
                route(obs)
            }
        }
        scope.launch {
            while (true) {
                val act = session?.phase == SessionState.Phase.CAPTURING
                _recording.value = act
                if (act) {
                    pendingBytes = session?.recording?.written ?: 0L
                    _recordingBytes.value = pendingBytes
                }
                delay(250)
            }
        }
    }

    fun submit(intent: Intent) = transport.submit(intent)

    fun syncNow() {
        val ts = com.cairn.securepocket.protocol.Ts14.now()
        listOf(
            "APP&T&$ts", "APP&BAT", "APP&SPACE", "APP&FW", "APP&BUILD", "APP&VER", "APP&WF",
            "APP&UPLE", "APP&GET&USB", "APP&STE", "APP&WIFIS", "APP&LIST_DIRS",
            "APP&LIST&${com.cairn.securepocket.protocol.Dates.iso.format(java.time.LocalDate.now())}",
        ).forEach { transport.submit(Intent.Send(it)) }
    }

    private fun startSession(id: String) {
        currentSession = id
        pendingBytes = 0L
        lastExpectedBytes = null
        session = SessionState(
            sessionId = id,
            tempDir = File(cacheDir, "captures").apply { mkdirs() },
            onCapture = ::onCapture,
            onResponse = ::onResponse,
        )
    }

    private fun route(obs: Observation) {
        val s = session ?: return
        when (obs.source) {
            com.cairn.securepocket.transport.ByteSource.COMMAND -> {}
            com.cairn.securepocket.transport.ByteSource.PING,
            com.cairn.securepocket.transport.ByteSource.PONG -> {}
            com.cairn.securepocket.transport.ByteSource.AUDIO -> s.onResponseChannel(obs.bytes)
            com.cairn.securepocket.transport.ByteSource.RESPONSE -> {
                val r = com.cairn.securepocket.protocol.ReplyParser.decodeSingle(obs.bytes)
                if (r == null) { s.onResponseChannel(obs.bytes) }
                else if (r is Response.RecordingStart || r is Response.RecordingStop || r is Response.Event) {
                    s.onEventChannel(obs.bytes)
                } else {
                    s.onResponseChannel(obs.bytes)
                    onResponse(r)
                }
            }
        }
    }

    private fun onResponse(r: Response) {
        when (r) {
            is Response.FileSize -> lastExpectedBytes = r.bytes
            is Response.Battery, is Response.Space, is Response.VersionInfo, is Response.Mac,
            is Response.Usb, is Response.Ste, is Response.UploadState, is Response.WifiStatus -> {
                val kind = when (r) {
                    is Response.VersionInfo -> r.kind.name
                    else -> r::class.simpleName!!
                }
                _health.value = _health.value + (kind to r)
            }
            else -> {}
        }
    }

    private fun onCapture(sink: CaptureSink) {
        val startAt = session?.recordingStartedAt ?: LocalDateTime.now()
        val bytes = lastExpectedBytes
        val rec = synchronized(vaultLock) {
            val r = vault.importCapture(sink, startAt.toLocalDate(), startAt, bytes, null)
            vault.verify(r)
            vault.list()
        }
        _records.value = rec
        _lastCapture.value =
            "${sink.file.name} → ${startAt.toLocalTime().withNano(0)} · " +
                "device $bytes vs disk ${sink.file.length()} B · " +
                rec.firstOrNull { it.localId.contains(startAt.toLocalTime().withNano(0).toString().replace(":", "")) }
                    ?.verification
        sink.file.delete()
    }

    fun refresh() {
        _records.value = synchronized(vaultLock) { vault.list() }
    }

    /** Pull everything new from the configured Secure Pocket Link server. */
    fun importFromLink(): ImportSummary {
        val sum = Graph.link.importAll(vault, vaultLock, cacheDir) { it.sha256 }
        refresh()
        return sum
    }

    /** Pull everything off the attached Pocket drive that isn't already in the vault. */
    fun importFromDrive(): ImportSummary {
        val sum = Graph.drive.importAll(vault, vaultLock) { it.sha256 }
        refresh()
        return sum
    }
}

object Graph {
    lateinit var runner: SessionRunner
        private set
    lateinit var drive: DriveImporter
        private set
    lateinit var link: com.cairn.securepocket.vault.LinkClient
        private set
    @Volatile lateinit var transport: Transport
        private set
    private lateinit var app: Application
    private lateinit var scope: CoroutineScope

    fun init(app: Application) {
        this.app = app
        this.scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        drive = DriveImporter(app)
        link = com.cairn.securepocket.vault.LinkClient(app)
        transport = newTransport(app)
        runner = SessionRunner(
            transport = transport,
            vaultRoot = File(app.filesDir, "vault"),
            cacheDir = app.cacheDir,
            scope = scope,
        )
    }

    private fun newTransport(app: Application): Transport =
        when (app.getSharedPreferences("settings", Application.MODE_PRIVATE).getString("transport", "sim")) {
            "ble" -> com.cairn.securepocket.transport.BleTransport(app)
            else -> Simulator()
        }

    /** Rebuild the stack around a different transport; vault survives, old runner's loops leak by design in v0. */
    fun switchTransport(kind: String) {
        app.getSharedPreferences("settings", Application.MODE_PRIVATE).edit().putString("transport", kind).apply()
        try {
            if (::transport.isInitialized) transport.close()
        } catch (_: Exception) {}
        transport = newTransport(app)
        runner = SessionRunner(
            transport = transport,
            vaultRoot = File(app.filesDir, "vault"),
            cacheDir = app.cacheDir,
            scope = scope,
        )
    }
}

class PocketApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
    }
}
