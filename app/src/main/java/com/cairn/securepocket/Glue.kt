package com.cairn.securepocket

import androidx.compose.runtime.*
import kotlinx.coroutines.cancel
import com.cairn.securepocket.protocol.*
import com.cairn.securepocket.vault.PocketDownload
import android.app.Application
import com.cairn.securepocket.capture.CaptureSink
import com.cairn.securepocket.protocol.Response
import com.cairn.securepocket.protocol.SessionState
import com.cairn.securepocket.transport.Intent
import com.cairn.securepocket.transport.Observation
import com.cairn.securepocket.transport.Simulator
import com.cairn.securepocket.transport.Transport
import com.cairn.securepocket.transport.TransportEvent
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

fun VaultStore.Record.isSavedCopyOf(item: DeviceFile): Boolean =
    startAt == item.startAt && date == item.date &&
        (lengthSeconds == null || lengthSeconds == item.lengthSeconds) &&
        !partial && verification != VaultStore.Verification.MISMATCHED && bytes > 0 && mp3.isFile

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
    val inventory = MutableStateFlow<List<DeviceFile>>(emptyList())
    val transfer = MutableStateFlow("")
    val downloading = MutableStateFlow(false)
    private val control = ReplyParser()
    private var pull: PocketDownload? = null
    private var lastPacket = 0L
    private var pullTimeout: kotlinx.coroutines.Job? = null
    private var deleteCopy: VaultStore.Record? = null
    private var deleting: DeviceFile? = null
    private var deleteAcknowledged = false
    private var deleteListSent = false

    @Synchronized fun deleteImported(item: DeviceFile) {
        if (transport is Simulator || downloading.value || _connection.value != "READY" ||
            session?.phase != SessionState.Phase.STANDBY || item !in inventory.value) return
        val copy = records.value.filter { it.isSavedCopyOf(item) }
            .maxByOrNull { it.verification == VaultStore.Verification.MATCHED } ?: return
        deleteCopy = copy
        startPull(item)
    }

    private fun finishDelete(message: String) {
        deleting = null
        deleteAcknowledged = false
        deleteListSent = false
        downloading.value = false
        pullTimeout?.cancel()
        transfer.value = message
    }

    private fun sendDelete(item: DeviceFile) {
        deleting = item
        deleteAcknowledged = false
        deleteListSent = false
        transfer.value = "Deleting verified Pocket copy…"
        transport.submit(Intent.Send("APP&D&${item.date}&${item.wireId}"))
        pullTimeout = scope.launch {
            delay(10000)
            synchronized(this@SessionRunner) {
                if (deleting == item) finishDelete("Pocket did not confirm deletion. Refresh its recordings to check.")
            }
        }
    }

    @Synchronized fun download(item: DeviceFile) {
        if (downloading.value || _connection.value != "READY" || session?.phase != SessionState.Phase.STANDBY) return
        startPull(item)
    }

    private fun startPull(item: DeviceFile) {
        pull = PocketDownload(item, cacheDir)
        downloading.value = true
        transfer.value = if (deleteCopy == null) "Requesting recording…" else "Rechecking the saved copy…"
        lastPacket = android.os.SystemClock.elapsedRealtime()
        transport.submit(Intent.Send("APP&U&${item.date}&${item.wireId}"))
        pullTimeout = scope.launch {
            while (downloading.value) {
                delay(1000)
                synchronized(this@SessionRunner) {
                    if (pull != null && android.os.SystemClock.elapsedRealtime() - lastPacket > 15000) {
                        abortPull("Transfer timed out. Reconnect and retry.")
                        transport.submit(Intent.Disconnect)
                    }
                }
            }
        }
    }

    private fun abortPull(reason: String) {
        pull?.discard()
        pull = null
        deleteCopy = null
        downloading.value = false
        pullTimeout?.cancel()
        transfer.value = reason
    }

    private fun receiveFile(bytes: ByteArray) {
        val active = pull ?: return
        try {
            active.append(bytes)
            lastPacket = android.os.SystemClock.elapsedRealtime()
            val done = active.received == active.expected
            if (done) {
                val copy = deleteCopy
                if (copy != null) {
                    check(active.matches(copy)) { "Saved audio does not match the Pocket. Nothing was deleted." }
                    active.discard()
                    pull = null
                    deleteCopy = null
                    pullTimeout?.cancel()
                    sendDelete(active.item)
                    return
                }
                if (!Graph.backup.storesOnPhone()) {
                    active.verifiedSha256()
                    transfer.value = "Sending verified recording to server…"
                    scope.launch(Dispatchers.IO) {
                        try {
                            val sha = active.verifiedSha256()
                            val result = Graph.backup.uploadPocketRecording(active.temporaryAudio, active.item, sha)
                            Graph.backup.markServerSaved(active.item)
                            synchronized(this@SessionRunner) {
                                if (pull === active) {
                                    active.discard()
                                    pull = null
                                    pullTimeout?.cancel()
                                    downloading.value = false
                                    transfer.value = if (result.alreadyPresent) "Already saved on server · audio verified"
                                        else "Saved to server · audio verified"
                                }
                            }
                        } catch (e: Exception) {
                            synchronized(this@SessionRunner) {
                                if (pull === active) abortPull("Server upload failed. Pocket copy is safe. ${e.localizedMessage ?: "Reconnect and retry."}")
                            }
                        }
                    }
                    return
                }
                val record = synchronized(vaultLock) { active.commit(vault) }
                pull = null
                pullTimeout?.cancel()
                downloading.value = false
                transfer.value = "Saved to Library · ${record.bytes} bytes verified"
                Graph.notes.pocketTitle(active.item.wireId)?.let { Graph.notes.rename(record, it) }
                refresh()
                Graph.notes.imported(record)
            } else {
                transfer.value = (if (deleteCopy == null) "Importing" else "Verifying") + " · ${active.received * 100 / active.expected}%"
            }
        } catch (e: Exception) {
            abortPull(e.message ?: "Transfer failed.")
            transport.submit(Intent.Disconnect)
        }
    }

    init {
        refresh()
        scope.launch {
            transport.events.collect { ev ->
                when (ev) {
                    is TransportEvent.StateChanged -> {
                        _connection.value = ev.state.name
                        if (ev.state == com.cairn.securepocket.transport.TransportState.DISCONNECTED) endSession()
                        if (ev.state == com.cairn.securepocket.transport.TransportState.READY && transport !is Simulator) {
                            syncNow()
                        }
                    }
                    is TransportEvent.SessionReset -> startSession(ev.sessionId)
                    is TransportEvent.Error -> { endSession(); _connection.value = "ERROR: ${ev.message}" }
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
                    _recordingBytes.value = session?.recording?.written ?: 0L
                }
                delay(250)
            }
        }
    }

    fun submit(intent: Intent) { if (!downloading.value || intent == Intent.Disconnect) transport.submit(intent) }

    fun lastPocketMode(): String? = when (Graph.preferences().getString("pocket_slider_mode", null)) {
        "CALL" -> "Call"
        "NORMAL" -> "Normal"
        else -> null
    }

    @Synchronized fun syncNow() {
        if (downloading.value || session?.phase == SessionState.Phase.CAPTURING) return
        inventory.value = emptyList()
        val ts = com.cairn.securepocket.protocol.Ts14.now()
        listOf(
            "APP&BAT", "APP&T&$ts", "APP&SPACE", "APP&FW", "APP&BUILD", "APP&VER", "APP&WF",
            "APP&UPLE", "APP&GET&USB", "APP&STE", "APP&WIFIS", "APP&LIST_DIRS",
            "APP&LIST&${com.cairn.securepocket.protocol.Dates.iso.format(java.time.LocalDate.now())}",
        ).forEach { transport.submit(Intent.Send(it)) }
    }

    @Synchronized private fun endSession() {
        if (deleting != null) finishDelete("Pocket disconnected before deletion was confirmed.")
        if (pull != null) abortPull("Disconnected before the transfer finished.")
        inventory.value = emptyList()
        currentSession = null
        session?.onSleep()
        session = null
        _health.value = emptyMap()
    }

    @Synchronized private fun startSession(id: String) {
        currentSession = id
        _recordingBytes.value = 0L
        transfer.value = ""
        control.reset()
        inventory.value = emptyList()
        session = SessionState(
            sessionId = id,
            tempDir = File(cacheDir, "captures").apply { mkdirs() },
            onCapture = ::onCapture,
            onResponse = ::onResponse,
        )
    }

    @Synchronized private fun route(obs: Observation) {
        if (obs.sessionId != currentSession) return
        val s = session ?: return
        when (obs.source) {
            com.cairn.securepocket.transport.ByteSource.COMMAND -> {
                val target = deleting
                if (target != null && deleteAcknowledged &&
                    obs.bytes.toString(Charsets.US_ASCII) == "APP&LIST&${target.date}") deleteListSent = true
            }
            com.cairn.securepocket.transport.ByteSource.PING,
            com.cairn.securepocket.transport.ByteSource.PONG -> {}
            com.cairn.securepocket.transport.ByteSource.AUDIO -> if (pull != null) receiveFile(obs.bytes) else s.onResponseChannel(obs.bytes)
            com.cairn.securepocket.transport.ByteSource.EVENT -> {
                val event = ReplyParser.decodeSingle(obs.bytes)
                if (event is Response.RecordingStart || event is Response.RecordingStop || event is Response.Event) {
                    if (pull != null && event is Response.RecordingStart) {
                        abortPull("Recording started during import. Reconnect and retry.")
                        transport.submit(Intent.Disconnect)
                        return
                    }
                    s.onEventChannel(obs.bytes)
                } else control.feed(obs.bytes).forEach(::onResponse)
            }
            com.cairn.securepocket.transport.ByteSource.RESPONSE -> when {
                transport is Simulator -> s.onResponseChannel(obs.bytes)
                pull != null -> receiveFile(obs.bytes)
                else -> s.recording?.append(obs.bytes)
            }
        }
    }

    private fun onResponse(r: Response) {
        when (r) {
            is Response.FileSize -> try { pull?.begin(r.bytes) } catch (e: Exception) {
                abortPull(e.message ?: "Transfer failed.")
                transport.submit(Intent.Disconnect)
            }
            is Response.DirsListing -> if (pull == null && deleting == null) r.dirs.distinct().forEach { transport.submit(Intent.Send("APP&LIST&$it")) }
            is Response.FileList -> {
                val target = deleting
                if (target != null) {
                    if (deleteListSent && r.complete && r.files.size == r.count &&
                        r.files.all { it.date == target.date }) {
                        if (r.files.none { it.wireId == target.wireId }) {
                            inventory.value = inventory.value.filterNot { it == target }
                            finishDelete("Deleted from Pocket. Phone copy kept.")
                            transport.submit(Intent.Send("APP&SPACE"))
                        } else finishDelete("This recording is still on the Pocket.")
                    }
                } else {
                    inventory.value = (inventory.value + r.files).groupBy { it.wireId }.values.map { entries ->
                        entries.firstOrNull { it.date == it.startAt.toLocalDate() } ?: entries.first()
                    }.sortedByDescending { it.startAt }
                }
            }
            is Response.DeleteResult -> deleting?.let { target ->
                if (!r.accepted) finishDelete("Pocket refused deletion.")
                else if (!deleteAcknowledged) {
                    deleteAcknowledged = true
                    transport.submit(Intent.Send("APP&LIST&${target.date}"))
                }
            }
            is Response.Battery, is Response.Space, is Response.VersionInfo, is Response.Mac,
            is Response.Usb, is Response.Ste, is Response.UploadState, is Response.WifiStatus,
            is Response.Event -> {
                val kind = when (r) {
                    is Response.VersionInfo -> r.kind.name
                    is Response.Event -> "PocketMode"
                    else -> r::class.simpleName!!
                }
                if (r !is Response.Event || r.name == "REC&CON" || r.name == "REC&CALL")
                    _health.value = _health.value + (kind to r)
                if (r is Response.Event) {
                    val mode = when (r.name) { "REC&CALL" -> "CALL"; "REC&CON" -> "NORMAL"; else -> null }
                    if (mode != null) Graph.preferences()
                        .edit().putString("pocket_slider_mode", mode).apply()
                }
            }
            else -> {}
        }
    }

    private fun onCapture(sink: CaptureSink) {
        val startAt = session?.recordingStartedAt ?: LocalDateTime.now()
        if (!Graph.backup.storesOnPhone() && sink.state == CaptureSink.State.CLOSED && transport !is Simulator) {
            val item = DeviceFile(startAt.toLocalDate(), startAt, 0,
                startAt.format(com.cairn.securepocket.protocol.Ts14.fmt))
            scope.launch(Dispatchers.IO) {
                try {
                    val sha = com.cairn.securepocket.vault.VaultStore.sha256(sink.file)
                    val result = Graph.backup.uploadPocketRecording(sink.file, item, sha)
                    Graph.backup.markServerSaved(item)
                    _lastCapture.value = if (result.alreadyPresent) "Already saved on server · audio verified"
                        else "Saved to server · audio verified"
                } catch (e: Exception) {
                    _lastCapture.value = "Server upload failed. Pocket copy is safe. ${e.localizedMessage ?: "Reconnect and retry."}"
                } finally { sink.file.delete() }
            }
            transport.submit(Intent.Send("APP&LIST&${startAt.toLocalDate()}"))
            return
        }
        val rec = synchronized(vaultLock) {
            val r = vault.importCapture(sink, startAt.toLocalDate(), startAt, null, null)
            vault.verify(r)
            Graph.notes.imported(r)
            vault.list()
        }
        _records.value = rec
        _lastCapture.value = "Saved to Library · ${sink.file.length()} bytes"
        sink.file.delete()
        if (sink.state == CaptureSink.State.CLOSED && transport !is Simulator) {
            transport.submit(Intent.Send("APP&LIST&${startAt.toLocalDate()}"))
        }
    }

    fun refresh() {
        _records.value = synchronized(vaultLock) { vault.list() }
    }

    /** Pull everything new from the configured Secure Pocket Link server. */
    fun importFromLink(): ImportSummary {
        val previous = _records.value.map { it.localId }.toSet()
        val sum = Graph.link.importAll(vault, vaultLock, cacheDir) { it.sha256 }
        refresh()
        _records.value.filter { it.localId !in previous }.forEach(Graph.notes::imported)
        return sum
    }


}

object Graph {
    lateinit var pocketLocation: PocketLocation
        private set
    lateinit var backup: com.cairn.securepocket.vault.LibraryBackup
        private set
    lateinit var notes: com.cairn.securepocket.vault.RecordingNotes
        private set
    private var active by mutableStateOf<SessionRunner?>(null)
    var runner: SessionRunner
        get() = requireNotNull(active)
        private set(value) { active = value }
    lateinit var link: com.cairn.securepocket.vault.LinkClient
        private set
    @Volatile lateinit var transport: Transport
        private set
    private lateinit var app: Application
    private lateinit var scope: CoroutineScope

    fun init(app: Application) {
        pocketLocation = PocketLocation(app)
        notes = com.cairn.securepocket.vault.RecordingNotes(app)
        this.app = app
        this.scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        link = com.cairn.securepocket.vault.LinkClient(app)
        transport = newTransport(app)
        runner = SessionRunner(
            transport = transport,
            vaultRoot = File(app.filesDir, "vault"),
            cacheDir = app.cacheDir,
            scope = scope,
        )
        backup = com.cairn.securepocket.vault.LibraryBackup(app)
        backup.schedule()
    }

    fun preferences(): android.content.SharedPreferences =
        app.getSharedPreferences("settings", Application.MODE_PRIVATE)

    private fun newTransport(app: Application): Transport =
        when (app.getSharedPreferences("settings", Application.MODE_PRIVATE).getString("transport", "ble")) {
            "ble" -> com.cairn.securepocket.transport.BleTransport(app)
            else -> Simulator(audioBytes = app.resources.openRawResource(R.raw.demo_tone).use { it.readBytes() })
        }

    /** Rebuild the stack around a different transport; vault survives, release the previous session before switching. */
    fun switchTransport(kind: String) {
        app.getSharedPreferences("settings", Application.MODE_PRIVATE).edit().putString("transport", kind).apply()
        try {
            if (::transport.isInitialized) transport.close()
        } catch (_: Exception) {}
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
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
