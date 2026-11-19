package com.cairn.securepocket.vault

import android.content.Context
import com.cairn.securepocket.capture.CaptureSink
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/** One entry from a Secure Pocket Link server /index.json. */
data class LinkFile(val name: String, val bytes: Long, val mtime: Long, val sha16: String, val sidecar: String?)

/**
 * Secure Pocket Link client: browses and pulls a desktop-hosted library over
 * LAN or tailnet. Zero cloud: the base URL is a private address the user typed.
 */
class LinkClient(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun baseUrl(): String = prefs.getString("link_base", "")?.trim()?.trimEnd('/') ?: ""
    fun enabled(): Boolean =
        prefs.getString("sync_dest", "none") == "link" && baseUrl().isNotEmpty()

    private fun url(path: String): String {
        val token = prefs.getString("link_token", "")?.trim().orEmpty()
        val sep = if (path.contains('?')) "&" else "?"
        return if (token.isNotEmpty()) baseUrl() + path + sep + "k=" + token else baseUrl() + path
    }

    private fun get(path: String, timeoutMs: Int = 6000): Pair<Int, ByteArray> {
        val conn = URL(url(path)).openConnection() as HttpURLConnection
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
                )
            )
        }
        return out
    }

    /** Pull every file whose sha16 isn't already in the vault. Size-verified like drive import. */
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
                val (code, body) = get("/file/" + java.net.URLEncoder.encode(f.name, "UTF-8"), 30000)
                if (code != 200) { failed++; notes.add("fail ${f.name} · HTTP $code"); continue }
                if (body.size.toLong() != f.bytes) { failed++; notes.add("fail ${f.name} · wire size ${body.size} != index ${f.bytes}"); continue }
                val dt = dateOf(f.name)
                val sink = CaptureSink("link-${System.currentTimeMillis()}", cacheDir)
                sink.begin(); sink.append(body); sink.finish()
                val rec = synchronized(vaultLock) {
                    val r = vault.importCapture(sink, dt.toLocalDate(), dt, f.bytes, null)
                    vault.verify(r)
                    r
                }
                sink.file.delete()
                imported++
                notes.add("import ${f.name} → ${rec.localId} · ${rec.verification}")
            } catch (e: Exception) {
                failed++; notes.add("fail ${f.name} · ${e.message}")
            }
        }
        return ImportSummary(imported, skipped, failed, notes)
    }

    companion object {
        private val ISO = Regex("""(\d{4}-\d{2}-\d{2})[_T](\d{2})(\d{2})(\d{2})""")
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
