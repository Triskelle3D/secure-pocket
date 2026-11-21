package com.cairn.securepocket.vault

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import com.cairn.securepocket.Graph
import com.cairn.securepocket.protocol.DeviceFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.io.IOException
import java.net.URLEncoder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Desktop stores recording bundles. NAS backup only uses the configured Wi-Fi network. */
class LibraryBackup(private val context: Context) {
    private val p = SecurePreferences.open(context, "settings")
    private val receipt = SecurePreferences.open(context, "library_receipts")
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    val status = MutableStateFlow("")
    private val wakeups = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    init {
        scope.launch { for (event in wakeups) sync() }
        cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { request() }
            })
    }

    fun request() { wakeups.trySend(Unit) }

    fun storesOnPhone(): Boolean = p.getBoolean("store_phone", true)

    fun usesRemoteStorage(): Boolean = p.getString("library_destination", "Phone") != "Phone"

    /** Mark a Pocket recording after the remote server confirms it stored the verified audio. */
    fun markServerSaved(item: DeviceFile) {
        if (usesRemoteStorage()) receipt.edit().putBoolean(serverReceiptKey(item), true).apply()
    }

    fun isServerSaved(item: DeviceFile): Boolean = usesRemoteStorage() && receipt.getBoolean(serverReceiptKey(item), false)

    private fun serverReceiptKey(item: DeviceFile): String {
        val destination = p.getString("library_destination", "Phone").orEmpty()
        val nas = destination == "NAS backup" || destination == "NAS"
        val method = if (nas) p.getString("nas_method", "WebDAV").orEmpty() else "Secure Pocket Link"
        val bases = serverBases(p, nas, method)
        val device = p.getString("ble_mac", "unknown-pocket").orEmpty().lowercase()
        return "serverSaved:$device:$destination:$method:${bases.joinToString("|")}:${item.startAt.toLocalDate()}:${item.wireId}"
    }

    private fun serverBases(settings: android.content.SharedPreferences, nas: Boolean, method: String?): List<String> {
        val localKey = if (nas) "nas_local_url" else "link_local_url"
        val fallbackKey = if (nas) "nas_url" else "link_base"
        val keys = if (method == "WebDAV") listOf(fallbackKey) else listOf(localKey, fallbackKey)
        return keys.mapNotNull { settings.getString(it, "")?.trim()?.trimEnd('/') }
            .filter(String::isNotEmpty).distinct()
    }

    /** Checks configured destination and credentials before starting a Pocket transfer. Null means ready. */
    suspend fun validateImportDestination(settings: android.content.SharedPreferences = p): String? = withContext(Dispatchers.IO) {
        val destination = settings.getString("library_destination", "Phone")
        if (destination == "Phone") return@withContext null
        val nas = destination == "NAS backup" || destination == "NAS"
        val method = if (nas) settings.getString("nas_method", "WebDAV") else "Secure Pocket Link"
        val bases = serverBases(settings, nas, method)
        if (bases.isEmpty()) return@withContext "Enter the server URL in Settings → Library Location."
        val network = if (nas) homeNetwork(settings) else null
        if (nas && network == null) return@withContext "Connect to your configured home Wi-Fi before importing to the NAS."
        var lastProblem: String? = null
        for (base in bases) try {
            val path = if (method == "WebDAV") "/" else "/health"
            val url = checkedRemoteUri(base + path)
            val connection = ((network?.openConnection(url.toURL()) ?: url.toURL().openConnection()) as HttpURLConnection)
            useTlsIdentity(connection, tlsFallbackIdentity(bases))
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.requestMethod = if (method == "WebDAV") "PROPFIND" else "GET"
                if (method == "WebDAV") {
                    connection.doOutput = true
                    connection.setRequestProperty("Depth", "0")
                    connection.setRequestProperty("Content-Type", "application/xml; charset=utf-8")
                    val user = settings.getString("nas_user", "").orEmpty()
                    val password = settings.getString("nas_token", "").orEmpty()
                    if (user.isNotEmpty() || password.isNotEmpty()) {
                        val value = android.util.Base64.encodeToString("$user:$password".toByteArray(), android.util.Base64.NO_WRAP)
                        connection.setRequestProperty("Authorization", "Basic $value")
                    }
                    val body = "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/></d:prop></d:propfind>".toByteArray()
                    connection.setFixedLengthStreamingMode(body.size)
                    connection.outputStream.use { it.write(body) }
                } else settings.getString(if (nas) "nas_token" else "link_token", "").orEmpty().takeIf { it.isNotBlank() }
                    ?.let { connection.setRequestProperty("X-Pocket-Token", it) }
                val code = connection.responseCode
                if (code in 200..299 && method == "Secure Pocket Link") {
                    val health = org.json.JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    lastProblem = when {
                        !health.optBoolean("ok") -> "Secure Pocket Link did not report a healthy server."
                        !health.optBoolean("authenticated") -> "Configure a Link token on the server and enter it in Settings → Library Location."
                        !health.optBoolean("writable") -> "The Secure Pocket Link server is not configured for library writes."
                        !health.optBoolean("backupConfigured") -> "Choose a backup folder in Secure Pocket Link settings before testing again."
                        else -> null
                    }
                }
                else if (code in 200..299) return@withContext null
                else if (code == 401 || code == 403) return@withContext "The server rejected the saved credentials. Check Settings → Library Location."
                else lastProblem = "The selected server is unavailable (HTTP $code). Check its connection and URL."
            } finally { connection.disconnect() }
            if (lastProblem == null) return@withContext null
            if (lastProblem!!.contains("rejected the saved credentials")) return@withContext lastProblem
        } catch (e: Exception) {
            lastProblem = when (e) {
                is UnknownHostException -> "The server address could not be found. Check the URL and network, then retry."
                is SocketTimeoutException -> "The selected server did not respond. Check the URL and network, then retry."
                is ConnectException, is IOException -> "Can't reach the selected server. Check its URL and network, then retry."
                else -> if (e is IllegalArgumentException && !e.message.isNullOrBlank()) e.message!!
                    else "Can't reach the selected server. Check its URL and network, then retry."
            }
        }
        lastProblem
    }

    /** Upload a verified Pocket download to the configured remote location. */
    suspend fun uploadPocketRecording(audio: File, item: DeviceFile, sha256: String): RemoteUploadResult =
        withContext(Dispatchers.IO) {
            val error = validateImportDestination()
            check(error == null) { error ?: "Selected storage destination is unavailable." }
            check(usesRemoteStorage()) { "Choose a server destination before uploading directly." }
            val nas = p.getString("library_destination", "Phone") in setOf("NAS backup", "NAS")
            val method = if (nas) p.getString("nas_method", "WebDAV") else "Secure Pocket Link"
            val bases = serverBases(p, nas, method)
            val token = p.getString(if (nas) "nas_token" else "link_token", "").orEmpty()
            val user = p.getString("nas_user", "").orEmpty()
            val webdav = method == "WebDAV"
            val tlsIdentity = tlsFallbackIdentity(bases)
            var failure: Exception? = null
            for (base in bases) try {
                Graph.link.preflightPocketUpload(base, token, webdav, user, token, tlsIdentity)
                return@withContext Graph.link.uploadPocketRecording(audio, item.date, item.startAt, item.wireId,
                    sha256, base, token, webdav, user, token, tlsIdentity)
            } catch (e: Exception) {
                if (e.message?.contains("rejected the configured credentials") == true) throw e
                failure = e
            }
            throw failure ?: IllegalStateException("Enter the server URL in Settings → Library Location.")
        }

    fun schedule() {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (p.getString("library_destination", "Phone") == "Phone" || !storesOnPhone()) {
            scheduler.cancel(20831)
        } else {
            scheduler.schedule(JobInfo.Builder(20831, ComponentName(context, LibraryBackupJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(15 * 60 * 1000L).setPersisted(true).build())
        }
    }

    @Suppress("DEPRECATION")
    private fun homeNetwork(settings: android.content.SharedPreferences = p): Network? {
        val wanted = settings.getString("nas_ssid", "").orEmpty().trim()
        if (wanted.isEmpty()) return null
        val current = context.applicationContext.getSystemService(WifiManager::class.java).connectionInfo.ssid?.removeSurrounding("\"")
        if (current != wanted) return null
        return cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }

    suspend fun sync(force: Boolean = false) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val destination = p.getString("library_destination", "Phone")
            if (destination == "Phone") { status.value = "Stored on phone."; return@withLock }
            if (!storesOnPhone()) { status.value = "Server only. New recordings go directly to the selected server."; return@withLock }
            val nas = destination == "NAS backup" || destination == "NAS"
            val network = if (nas) homeNetwork() else null
            if (nas && network == null) {
                status.value = "Waiting for the configured home Wi-Fi and location permission."
                return@withLock
            }
            val mode = if (nas) p.getString("nas_method", "WebDAV") else "Secure Pocket Link"
            val bases = serverBases(p, nas, mode)
            val token = p.getString(if (nas) "nas_token" else "link_token", "").orEmpty()
            val user = p.getString("nas_user", "").orEmpty()
            var count = 0
            try {
                status.value = "Checking library…"
                require(bases.isNotEmpty()) { "Enter the library server URL." }
                for (rec in Graph.runner.vault.list().filter { !it.partial }) {
                    val paths = listOf(rec.transcript, rec.summary, rec.titleFile)
                    val signature = rec.sha256 + paths.joinToString(":") { if (it.isFile) {
                        java.security.MessageDigest.getInstance("SHA-256")
                            .digest(readEncryptedText(it).toByteArray(Charsets.UTF_8))
                            .joinToString("") { byte -> "%02x".format(byte) }
                    } else "-" }
                    val key = "$destination:$mode:${bases.joinToString("|")}:${rec.localId}"
                    if (!force && receipt.getString(key, null) == signature) continue
                    if (nas) check(homeNetwork() == network) { "Home Wi-Fi disconnected." }
                    var failure: Exception? = null
                    var uploaded = false
                    val tlsIdentity = tlsFallbackIdentity(bases)
                    for (base in bases) try {
                        upload(rec, mode == "WebDAV", base, token, user, network, tlsIdentity)
                        uploaded = true
                        break
                    } catch (e: Exception) {
                        if (e.message?.contains("HTTP 401") == true || e.message?.contains("HTTP 403") == true) throw e
                        failure = e
                    }
                    if (!uploaded) throw failure ?: IOException("No configured server address could be reached.")
                    receipt.edit().putString(key, signature).apply()
                    count++
                }
                status.value = if (count == 0) "Library is up to date." else "Saved $count recording bundles."
            } catch (e: Exception) { status.value = e.message ?: "Library sync failed." }
        }
    }

    private fun upload(rec: VaultStore.Record, webdav: Boolean, base: String, token: String, user: String,
        network: Network?, tlsIdentityHost: String?) {
        val bundle = File.createTempFile("pocket-", ".zip", context.cacheDir)
        var audioPlain: File? = null
        try {
            val title = rec.title.replace(Regex("""[^\p{L}\p{N} ._-]"""), "_").trim(' ', '.').take(70).ifBlank { "Recording" }
            audioPlain = Graph.runner.vault.materialize(rec.mp3)
            ZipOutputStream(bundle.outputStream()).use { zip ->
                listOf(requireNotNull(audioPlain) to "$title.mp3", rec.transcript to "$title.transcript.md",
                    rec.summary to "$title.summary.md", rec.titleFile to "title.txt").forEach { (file, name) ->
                    if (file.isFile) {
                        zip.putNextEntry(ZipEntry(name))
                        if (file.name.endsWith(".plain")) file.inputStream().use { it.copyTo(zip) }
                        else zip.write(readEncryptedText(file).toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                    }
                }
            }
            val key = URLEncoder.encode(rec.localId, "UTF-8")
            val path = if (webdav) "/$key.zip" else "/library/$key.zip"
            val url = checkedRemoteUri(base + path)
            val c = ((network?.openConnection(url.toURL()) ?: url.toURL().openConnection()) as HttpURLConnection)
            useTlsIdentity(c, tlsIdentityHost)
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = 8000
                c.readTimeout = 60000
                c.requestMethod = "PUT"
                c.doOutput = true
                c.setFixedLengthStreamingMode(bundle.length())
                c.setRequestProperty("Content-Type", "application/zip")
                if (webdav && (user.isNotEmpty() || token.isNotEmpty())) {
                    val basic = android.util.Base64.encodeToString("$user:$token".toByteArray(), android.util.Base64.NO_WRAP)
                    c.setRequestProperty("Authorization", "Basic $basic")
                } else if (token.isNotEmpty()) c.setRequestProperty("X-Pocket-Token", token)
                c.outputStream.use { output -> bundle.inputStream().use { it.copyTo(output) } }
                c.result()
            } finally { c.disconnect() }
        } finally { bundle.delete(); audioPlain?.delete() }
    }
}

class LibraryBackupJob : JobService() {
    private var work: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        work = CoroutineScope(Dispatchers.IO).launch { Graph.backup.sync(); jobFinished(params, false) }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { work?.cancel(); return true }
}
