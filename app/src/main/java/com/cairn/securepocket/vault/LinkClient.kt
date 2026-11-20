package com.cairn.securepocket.vault

import android.content.Context
import com.cairn.securepocket.capture.CaptureSink
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URI
import java.time.LocalDate
import java.time.LocalDateTime
import org.json.JSONArray
import org.json.JSONObject
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ImportSummary(val imported: Int, val skipped: Int, val failed: Int, val notes: List<String>)
data class RemoteUploadResult(val bundle: String, val sha256: String, val alreadyPresent: Boolean)

/** One entry from a Secure Pocket Link server /index.json. */
data class LinkFile(val name: String, val bytes: Long, val mtime: Long, val sha16: String, val sidecar: String?, val bundle: String? = null)

/**
 * Secure Pocket Link client: browses and pulls a desktop-hosted library over
 * LAN or tailnet. Zero cloud: the base URL is a private address the user typed.
 */
class LinkClient(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun usesNasLink(): Boolean =
        prefs.getString("library_destination", "Phone") in setOf("NAS", "NAS backup") &&
            prefs.getString("nas_method", "WebDAV") == "Secure Pocket Link"

    fun baseUrl(): String = prefs.getString(if (usesNasLink()) "nas_url" else "link_base", "")?.trim()?.trimEnd('/') ?: ""
    fun authToken(): String = prefs.getString(if (usesNasLink()) "nas_token" else "link_token", "")?.trim().orEmpty()
    fun enabled(): Boolean =
        (prefs.getString("library_destination", "Phone") == "Secure Pocket Link" || usesNasLink()) && baseUrl().isNotEmpty()

    fun preflightPocketUpload(baseUrl: String, token: String, webdav: Boolean,
        username: String = "", password: String = "") {
        val base = checkedBase(baseUrl)
        val connection = if (webdav) endpoint(base, "/") else endpoint(base, "/health")
        try {
            setCredentials(connection, token, webdav, username, password)
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = if (webdav) "OPTIONS" else "GET"
            val code = connection.responseCode
            check(code in 200..299 || (webdav && code == HttpURLConnection.HTTP_BAD_METHOD)) {
                if (code == 401 || code == 403) "Server rejected the configured credentials."
                else "Storage server is unavailable (HTTP $code)."
            }
        } catch (e: Exception) {
            throw IllegalStateException(e.message ?: "Could not reach the selected storage server.", e)
        } finally { connection.disconnect() }
    }

    fun uploadPocketRecording(audio: File, date: LocalDate, startAt: LocalDateTime, wireId: String,
        sha256: String, baseUrl: String, token: String, webdav: Boolean,
        username: String = "", password: String = ""): RemoteUploadResult {
        check(audio.isFile && audio.length() > 0) { "Downloaded recording is missing." }
        val actualSha = VaultStore.sha256(audio)
        check(actualSha.equals(sha256, ignoreCase = true)) { "Downloaded recording failed SHA-256 verification." }
        val base = checkedBase(baseUrl)
        val time = startAt.toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HHmmss"))
        val bundleName = "$date-$time-${actualSha.take(8)}.zip"
        if (webdav) {
            val bundle = File.createTempFile("pocket-upload-", ".zip", audio.parentFile)
            try {
                ZipOutputStream(bundle.outputStream()).use { zip ->
                    zip.putNextEntry(ZipEntry("${date}_$time.mp3"))
                    audio.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry("title.txt"))
                    zip.write("Recording $date ${time.take(2)}:${time.substring(2, 4)}".toByteArray())
                    zip.closeEntry()
                }
                putFile(base, "/$bundleName", bundle, token, true, username, password)
            } finally { bundle.delete() }
        } else {
            val connection = endpoint(base, "/library/import")
            try {
                setCredentials(connection, token, false, username, password)
                connection.connectTimeout = 8000
                connection.readTimeout = 60000
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(audio.length())
                connection.setRequestProperty("Content-Type", "audio/mpeg")
                connection.setRequestProperty("X-Recording-Date", date.toString())
                connection.setRequestProperty("X-Recording-Start", time)
                connection.setRequestProperty("X-Recording-Wire-Id", wireId)
                connection.setRequestProperty("X-Audio-SHA256", actualSha)
                audio.inputStream().use { input -> connection.outputStream.use { input.copyTo(it) } }
                val code = connection.responseCode
                val response = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                check(code in 200..299) { runCatching { JSONObject(response).optString("error") }.getOrNull()
                    .takeUnless { it.isNullOrBlank() } ?: "Storage server rejected the recording (HTTP $code)." }
                val json = JSONObject(response)
                return RemoteUploadResult(json.optString("bundle", bundleName), actualSha, json.optBoolean("alreadyPresent"))
            } finally { connection.disconnect() }
        }
        return RemoteUploadResult(bundleName, actualSha, false)
    }

    private fun putFile(base: String, path: String, file: File, token: String, webdav: Boolean,
        username: String, password: String) {
        val connection = endpoint(base, path)
        try {
            setCredentials(connection, token, webdav, username, password)
            connection.connectTimeout = 8000
            connection.readTimeout = 60000
            connection.requestMethod = "PUT"
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(file.length())
            connection.setRequestProperty("Content-Type", "application/zip")
            file.inputStream().use { input -> connection.outputStream.use { input.copyTo(it) } }
            val code = connection.responseCode
            check(code in 200..299) { "Storage server rejected the recording (HTTP $code)." }
        } finally { connection.disconnect() }
    }

    private fun setCredentials(connection: HttpURLConnection, token: String, webdav: Boolean,
        username: String, password: String) {
        if (webdav) {
            if (username.isNotEmpty() || password.isNotEmpty()) {
                val value = android.util.Base64.encodeToString("$username:$password".toByteArray(), android.util.Base64.NO_WRAP)
                connection.setRequestProperty("Authorization", "Basic $value")
            }
        } else if (token.isNotBlank()) connection.setRequestProperty("X-Pocket-Token", token)
    }

    private fun checkedBase(raw: String): String {
        val base = raw.trim().trimEnd('/')
        val uri = URI(base)
        require(uri.scheme in listOf("http", "https") && uri.host != null && uri.userInfo == null &&
            uri.query == null && uri.fragment == null) { "Enter a valid storage server URL." }
        return base
    }

    private fun get(path: String, timeoutMs: Int = 6000): Pair<Int, ByteArray> {
        val conn = endpoint(baseUrl(), path)
        authToken().takeIf { it.isNotEmpty() }?.let { conn.setRequestProperty("X-Pocket-Token", it) }
        conn.instanceFollowRedirects = false
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.requestMethod = "GET"
        return try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.readBytes() ?: ByteArray(0)
            code to body
        } finally {
            conn.disconnect()
        }
    }

    fun health(): String {
        return try {
            val (code, _) = get("/health", 4000)
            if (code == 200) "LINK ALIVE" else "LINK HTTP $code"
        } catch (e: Exception) {
            "LINK UNREACHABLE"
        }
    }

    fun fetchIndex(): List<LinkFile> {
        val (code, body) = get("/index.json")
        if (code != 200) throw IllegalStateException("index HTTP $code")
        val arr = JSONObject(String(body)).getJSONArray("files")
        val out = ArrayList<LinkFile>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                LinkFile(
                    o.getString("name"),
                    o.getLong("bytes"),
                    o.getLong("mtime"),
                    o.optString("sha16", ""),
                    if (o.isNull("sidecar")) null else o.optString("sidecar", null),
                    o.optString("bundle").takeIf { it.matches(Regex("[0-9a-f-]+[.]zip")) },
                )
            )
        }
        return out
    }

    /** Pull every file whose sha16 isn't already in the vault. Verifies each downloaded file against the server index. */
    fun importAll(
        vault: VaultStore,
        vaultLock: Any,
        cacheDir: File,
        knownSha: (VaultStore.Record) -> String,
    ): ImportSummary {
        val notes = ArrayList<String>()
        var imported = 0
        var skipped = 0
        var failed = 0
        val remote = try { fetchIndex() } catch (e: Exception) {
            return ImportSummary(0, 0, 1, listOf("link unreachable: ${e.message}"))
        }
        val have = synchronized(vaultLock) { vault.list() }.map(knownSha).map { it.take(16) }.toMutableSet()
        for (f in remote) {
            if (f.sha16.isNotEmpty() && have.contains(f.sha16)) {
                skipped++; notes.add("skip ${f.name} · already in vault"); continue
            }
            try {
                val (code, payload) = get(if (f.bundle != null) "/library/" + f.bundle
                    else "/file/" + java.net.URLEncoder.encode(f.name, "UTF-8").replace("+", "%20"), 30000)
                if (code != 200) { failed++; notes.add("fail ${f.name} · HTTP $code"); continue }
                val artifacts = if (f.bundle != null) readBundle(payload, f) else emptyMap()
                val body = if (f.bundle != null) artifacts.getValue(f.name) else payload
                if (body.size.toLong() != f.bytes) { failed++; notes.add("fail ${f.name} · byte count mismatch"); continue }
                val dt = dateOf(f.bundle ?: f.name)
                val sink = CaptureSink("link-${System.currentTimeMillis()}", cacheDir)
                sink.begin(); sink.append(body); sink.finish()
                val rec = synchronized(vaultLock) {
                    val r = vault.importCapture(sink, dt.toLocalDate(), dt, f.bytes, null)
                    vault.verify(r)
                    if (f.bundle != null) {
                        artifacts["title.txt"]?.decodeToString()?.trim()?.takeIf { it.isNotBlank() }?.let {
                            r.manualTitleFile.saveMarkdown(it)
                            r.titleFile.saveMarkdown(it)
                        }
                        artifacts.entries.firstOrNull { it.key.endsWith(".transcript.md") }?.value?.decodeToString()?.let { r.transcript.saveMarkdown(it) }
                        artifacts.entries.firstOrNull { it.key.endsWith(".summary.md") }?.value?.decodeToString()?.let { r.summary.saveMarkdown(it) }
                    }
                    r
                }
                sink.file.delete()
                have.add(knownSha(rec).take(16))
                imported++
                notes.add("import ${f.name} → ${rec.localId} · ${rec.verification}")
            } catch (e: Exception) {
                failed++; notes.add("fail ${f.name} · ${e.message}")
            }
        }
        return ImportSummary(imported, skipped, failed, notes)
    }

    private fun readBundle(payload: ByteArray, file: LinkFile): Map<String, ByteArray> {
        val parts = mutableMapOf<String, ByteArray>()
        java.util.zip.ZipInputStream(payload.inputStream()).use { zip ->
            repeat(4) {
                val entry = zip.nextEntry ?: return parts
                val limit = if (entry.name == file.name) file.bytes else 2L * 1024 * 1024
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(65536)
                while (true) {
                    val n = zip.read(buffer)
                    if (n < 0) break
                    check(out.size().toLong() + n <= limit) { "Invalid library bundle size." }
                    out.write(buffer, 0, n)
                }
                parts[entry.name] = out.toByteArray()
            }
        }
        return parts
    }

    companion object {
        private val ISO = Regex("""(\d{4}-\d{2}-\d{2})[_T-](\d{2})(\d{2})(\d{2})""")
        private val COMPACT = Regex("""(\d{4})(\d{2})(\d{2})[_-]?(\d{2})(\d{2})(\d{2})""")

        fun dateOf(name: String): java.time.LocalDateTime {
            ISO.find(name)?.destructured?.let { (d, h, m, s) ->
                return java.time.LocalDateTime.parse("${d}T$h:$m:$s")
            }
            COMPACT.find(name)?.destructured?.let { (y, mo, d, h, m, s) ->
                return java.time.LocalDateTime.of(
                    y.toInt(), mo.toInt(), d.toInt(), h.toInt(), m.toInt(), s.toInt()
                )
            }
            return java.time.LocalDateTime.now()
        }
    }
}
