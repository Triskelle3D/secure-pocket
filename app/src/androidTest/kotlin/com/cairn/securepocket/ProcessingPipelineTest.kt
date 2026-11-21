package com.cairn.securepocket

import android.Manifest
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import androidx.test.platform.app.InstrumentationRegistry
import com.cairn.securepocket.capture.CaptureSink
import com.cairn.securepocket.vault.*
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.time.LocalDateTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Provider contract and opt-in live integration coverage. All generated recordings live in the
 * validation variant's isolated application data (`.isotest`). The loopback server returns only
 * deterministic synthetic results; live Link credentials are read from runner arguments and are
 * never included in assertion messages or logs.
 */
class ProcessingPipelineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val prefs get() = SecurePreferences.open(context, "settings")
    private lateinit var saved: Map<String, *>
    private val captures = mutableListOf<CaptureSink>()
    private val records = mutableListOf<VaultStore.Record>()
    private val timeOffset get() = nextTimeOffset

    @Before fun saveSettings() {
        check(context.packageName.endsWith(".isotest")) { "Pipeline fixtures require the isolated validation app." }
        saved = prefs.all.toMap()
        prefs.edit().putBoolean("allow_processing_servers", true)
            .putBoolean("auto_transcribe", false).putBoolean("auto_summary", false).commit()
    }

    @After fun restoreAndClean() {
        if (!context.packageName.endsWith(".isotest") || !::saved.isInitialized) return
        val editor = prefs.edit().clear()
        saved.forEach { (key, value) -> when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
        } }
        editor.commit()
        records.forEach { r ->
            listOf(r.mp3, r.sidecar, r.transcript, r.summary, r.titleFile, r.manualTitleFile, r.speakersFile).forEach { it.delete() }
        }
        captures.forEach { it.file.delete() }
        Graph.runner.refresh()
    }

    @Test fun compatibleSpeechAndSummaryProvidersUseExpectedContracts() {
        LoopbackProvider().use { server ->
            configureCompatible(server.baseUrl)
            val r = recording()
            Graph.notes.process(r, makeSummary = true)
            awaitDone(r)
            assertTrue("transcript should be stored; status=${Graph.notes.status.value[r.localId]}, requests=${server.paths}", r.transcript.isFile)
            assertEquals("Synthetic speech from the loopback provider.", readEncryptedText(r.transcript))
            assertTrue("summary should be stored", r.summary.isFile)
            assertEquals(listOf("/v1/audio/transcriptions", "/v1/chat/completions"), server.paths.sorted())
            assertEquals("Bearer synthetic-stt-token", server.authorizationFor("/v1/audio/transcriptions"))
            assertEquals("Bearer synthetic-llm-token", server.authorizationFor("/v1/chat/completions"))
        }
    }

    @Test fun ollamaBaseUrlWithVersionSuffixNormalizesToOpenAiCompatibleRoute() {
        LoopbackProvider().use { server ->
            prefs.edit().putString("llm_provider", "Ollama").putString("llm_url", server.baseUrl + "/v1/")
                .putString("llm_model", "synthetic-ollama-model").putBoolean("allow_processing_servers", true).commit()
            val r = recording()
            r.transcript.saveMarkdown("The team will review the synthetic launch checklist on Friday.")
            Graph.notes.process(r, makeSummary = true)
            awaitDone(r)
            assertTrue("summary should be returned by the loopback Ollama-compatible provider", r.summary.isFile)
            assertEquals(listOf("/v1/chat/completions"), server.paths)
        }
    }

    @Test fun cloudProviderMissingKeysFailBeforeAnyNetworkRequest() {
        val r = recording()
        val providers = ProcessingProviders(prefs, OnDeviceModel(context))
        prefs.edit().putString("stt_provider", "OpenAI").remove("stt_openai_token").commit()
        val speechError = runCatching { providers.speech(r.mp3) }.exceptionOrNull()
        assertEquals("Enter your OpenAI speech API key.", speechError?.message)

        for (provider in listOf("OpenAI", "OpenRouter")) {
            prefs.edit().putString("llm_provider", provider).putString("llm_model", "synthetic-model")
                .remove("llm_token_$provider").putBoolean("allow_processing_servers", true).commit()
            val summaryError = runCatching { providers.summarize("Synthetic transcript for missing-key validation.") }.exceptionOrNull()
            assertEquals("Enter your $provider API key.", summaryError?.message)
        }
    }

    @Test fun doclingOffPrePostAndBothHaveExpectedPipelineBehavior() {
        LoopbackProvider().use { server ->
            configureCompatible(server.baseUrl)
            for (mode in listOf("Off", "Pre", "Post", "Both")) {
                server.reset()
                prefs.edit().putString("docling_mode", mode).putString("docling_url", server.baseUrl)
                    .putString("docling_token", "synthetic-docling-token")
                    .putString("stt_provider", "Compatible server").commit()
                val r = recording()
                Graph.notes.process(r, makeSummary = true)
                awaitDone(r)
                assertTrue("$mode transcript missing; status=${Graph.notes.status.value[r.localId]}, requests=${server.paths}", r.transcript.isFile)
                assertTrue("$mode summary missing", r.summary.isFile)
                val paths = server.paths
                when (mode) {
                    "Off" -> assertTrue(paths.none { it.startsWith("/docling/") })
                    "Pre" -> assertTrue(paths.any { it == "/docling/pre" })
                    "Post" -> assertTrue(paths.any { it == "/docling/post" })
                    "Both" -> assertTrue(paths.containsAll(listOf("/docling/pre", "/docling/post")))
                }
                if (mode == "Pre" || mode == "Both") {
                    val speakers = r.readSpeakers()
                    assertEquals("docling", speakers?.source)
                    assertTrue("Docling should persist timed speaker segments", speakers?.segments?.isNotEmpty() == true)
                }
                if (mode == "Post" || mode == "Both") {
                    assertEquals("Formatted synthetic notes\n\n- Review the launch plan Friday.", readEncryptedText(r.summary))
                }
                if (mode == "Off" || mode == "Post") assertTrue(paths.contains("/v1/audio/transcriptions"))
                else assertFalse(paths.contains("/v1/audio/transcriptions"))
            }
        }
    }

    @Test fun bundledVoskProcessesFixtureAudioLocally() {
        prefs.edit().putString("docling_mode", "Off").putString("stt_provider", "On-device Vosk")
            .putString("llm_provider", "Off").commit()
        val r = recording()
        Graph.notes.process(r, makeSummary = false)
        awaitDone(r, 180)
        assertTrue("Vosk transcript missing; status=${Graph.notes.status.value[r.localId]}", r.transcript.isFile)
        assertTrue(readEncryptedText(r.transcript).isNotBlank())
    }

    /** Real Secure Pocket Link checks are opt-in. Run with instrumentation arguments
     * `docling_url` and optionally `docling_token`; secrets are not copied to output or assertions.
     */
    @Test fun liveDoclingPrePostAndBoth() {
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("docling_url")?.trim().orEmpty()
        assumeTrue("Set instrumentation arg docling_url to run live Link checks", url.isNotBlank())
        val token = args.getString("docling_token").orEmpty()
        LoopbackProvider().use { llm ->
            prefs.edit().putString("llm_provider", "Compatible server").putString("llm_url", llm.baseUrl + "/v1")
                .putString("llm_model", "synthetic-model").putString("llm_token_Compatible server", "synthetic-llm-token")
                .putString("stt_provider", "On-device Vosk").putString("docling_url", url)
                .putString("docling_token", token).putBoolean("allow_processing_servers", true).commit()
            for (mode in listOf("Pre", "Post", "Both")) {
                llm.reset()
                prefs.edit().putString("docling_mode", mode).commit()
                val r = recording()
                Graph.notes.process(r, makeSummary = true)
                awaitDone(r, 240)
                assertTrue("$mode live Docling transcript missing; status=${Graph.notes.status.value[r.localId]}", r.transcript.isFile)
                assertTrue("$mode live Docling summary missing; status=${Graph.notes.status.value[r.localId]}", r.summary.isFile)
                assertTrue("summary should have passed through the fake LLM", llm.paths.contains("/v1/chat/completions"))
                if (mode == "Pre" || mode == "Both") {
                    val speakers = r.readSpeakers()
                    assertEquals("docling", speakers?.source)
                    assertTrue(speakers?.segments?.isNotEmpty() == true)
                }
                assertTrue(readEncryptedText(r.summary).isNotBlank())
            }
        }
    }

    /** The user's selected model is not copied from the production package automatically. To run,
     * temporarily provision `no_backup/summary.task` in the `.isotest` package (instructions in
     * app/src/androidTest/README.md), then the test removes that temporary model in finally.
     */
    @Test fun provisionedGemmaSummarizesLocalTranscriptAndIsRemoved() {
        val model = File(context.noBackupFilesDir, "summary.task")
        assumeTrue("Provision a temporary validation-package Gemma model to run this test", model.isFile)
        try {
            prefs.edit().putString("docling_mode", "Off").putString("llm_provider", "On-device LLM").commit()
            val r = recording()
            r.transcript.saveMarkdown("The team agreed to meet Friday and review the updated launch plan.")
            Graph.notes.process(r, makeSummary = true)
            awaitDone(r, 240)
            assertTrue("Gemma summary missing; status=${Graph.notes.status.value[r.localId]}", r.summary.isFile)
            assertTrue(readEncryptedText(r.summary).contains("- "))
        } finally {
            val previous = File(context.noBackupFilesDir, "summary.task.validation-backup")
            if (previous.isFile) {
                model.delete()
                check(previous.renameTo(model)) { "Could not restore the previous validation model." }
            } else model.delete()
        }
    }

    /** Opt-in NAS integration using an isolated synthetic WebDAV fixture. The URL must be passed
     * explicitly so this can never redirect an existing library to a real server.
     */
    @Test fun isolatedWebDavPreflightAndLibrarySync() {
        val args = InstrumentationRegistry.getArguments()
        val base = args.getString("webdav_url")?.trim().orEmpty()
        assumeTrue("Set instrumentation arg webdav_url to run the synthetic WebDAV check", base.isNotBlank())
        assumeTrue("Use an empty validation vault so sync cannot include unrelated records", Graph.runner.vault.list().isEmpty())
        assumeTrue("Grant ACCESS_FINE_LOCATION to the validation package before the WebDAV run",
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
        val wifi = context.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION")
        val ssid = wifi.connectionInfo.ssid?.removeSurrounding("\"").orEmpty()
        assumeTrue("A connected Wi-Fi network is required for the NAS validation test", ssid.isNotBlank() && ssid != "<unknown ssid>")
        prefs.edit().putString("library_destination", "NAS").putBoolean("store_phone", true)
            .putString("nas_method", "WebDAV").putString("nas_url", base)
            .putString("nas_user", args.getString("webdav_user", "SyntheticTest"))
            .putString("nas_token", args.getString("webdav_password", "FixtureOnly"))
            .putString("nas_ssid", ssid).commit()
        val preflight = runBlocking { Graph.backup.validateImportDestination() }
        assertNull("Synthetic WebDAV preflight failed", preflight)
        val r = recording()
        runBlocking { Graph.backup.sync(force = true) }
        assertEquals("Saved 1 recording bundles.", Graph.backup.status.value)
        assertTrue("The fixture recording should remain in the isolated local vault", r.mp3.isFile)
    }

    @Test fun isolatedWebDavLibrarySyncUsesPutWithoutPreflight() {
        val args = InstrumentationRegistry.getArguments()
        val base = args.getString("webdav_url")?.trim().orEmpty()
        assumeTrue("Set instrumentation arg webdav_url to run the synthetic WebDAV check", base.isNotBlank())
        assumeTrue("Use an empty validation vault so sync cannot include unrelated records", Graph.runner.vault.list().isEmpty())
        assumeTrue("Grant ACCESS_FINE_LOCATION to the validation package before the WebDAV run",
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
        @Suppress("DEPRECATION")
        val ssid = context.getSystemService(WifiManager::class.java).connectionInfo.ssid?.removeSurrounding("\"").orEmpty()
        assumeTrue("A connected Wi-Fi network is required for the NAS validation test", ssid.isNotBlank() && ssid != "<unknown ssid>")
        prefs.edit().putString("library_destination", "NAS").putBoolean("store_phone", true)
            .putString("nas_method", "WebDAV").putString("nas_url", base)
            .putString("nas_user", args.getString("webdav_user", "SyntheticTest"))
            .putString("nas_token", args.getString("webdav_password", "FixtureOnly"))
            .putString("nas_ssid", ssid).commit()
        val r = recording()
        runBlocking { Graph.backup.sync(force = true) }
        assertEquals("Saved 1 recording bundles.", Graph.backup.status.value)
        assertTrue("The fixture recording should remain in the isolated local vault", r.mp3.isFile)
    }

    private fun configureCompatible(base: String) {
        prefs.edit().putString("docling_mode", "Off")
            .putString("stt_provider", "Compatible server").putString("stt_url", base.trimEnd('/') + "/v1")
            .putString("stt_model", "synthetic-whisper").putString("stt_token", "synthetic-stt-token")
            .putString("llm_provider", "Compatible server").putString("llm_url", base.trimEnd('/') + "/v1")
            .putString("llm_model", "synthetic-model").putString("llm_token_Compatible server", "synthetic-llm-token")
            .putBoolean("allow_processing_servers", true).commit()
    }

    private fun recording(): VaultStore.Record {
        val now = LocalDateTime.of(2001, 1, 1, 0, 0).plusSeconds(timeOffset.getAndIncrement())
        val sink = CaptureSink("pipeline-${System.nanoTime()}", context.cacheDir)
        val bytes = instrumentation.context.assets.open("library-speech.mp3").use { it.readBytes() }
        sink.begin(); sink.append(bytes); sink.finish()
        captures += sink
        val r = Graph.runner.vault.importCapture(sink, now.toLocalDate(), now, bytes.size.toLong(), null)
        records += r
        Graph.runner.refresh()
        return r
    }

    private fun awaitDone(record: VaultStore.Record, timeoutSeconds: Long = 45) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (System.nanoTime() < deadline) {
            val status = Graph.notes.status.value[record.localId]
            if (status == "Saved" || (status != null && status !in setOf("Queued", "Transcribing…", "Generating summary…", "Formatting with Docling…"))) return
            Thread.sleep(250)
        }
        fail("Pipeline timed out; status=${Graph.notes.status.value[record.localId]}")
    }

    private class LoopbackProvider : AutoCloseable {
        private val socket = ServerSocket(0, 20, java.net.InetAddress.getByName("127.0.0.1"))
        private val requests = CopyOnWriteArrayList<Request>()
        @Volatile private var running = true
        private val worker = Thread {
            while (running) try {
                socket.accept().use { client ->
                    client.soTimeout = 15000
                    val input = BufferedInputStream(client.getInputStream())
                    val requestLine = line(input)
                    val headers = linkedMapOf<String, String>()
                    while (true) {
                        val header = line(input)
                        if (header.isEmpty()) break
                        val split = header.indexOf(':')
                        if (split > 0) headers[header.substring(0, split).trim().lowercase()] = header.substring(split + 1).trim()
                    }
                    val body = when {
                        headers["transfer-encoding"]?.equals("chunked", true) == true -> chunks(input)
                        else -> ByteArray(headers["content-length"]?.toIntOrNull() ?: 0).also { readFully(input, it) }
                    }
                    val path = requestLine.split(' ').getOrNull(1).orEmpty()
                    requests += Request(path, headers, body)
                    val response = response(path).toByteArray()
                    client.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(response); flush()
                    }
                }
            } catch (_: Exception) { if (running) Unit }
        }.apply { isDaemon = true; name = "synthetic-provider"; start() }

        val baseUrl get() = "http://127.0.0.1:${socket.localPort}"
        val paths get() = requests.map { it.path }
        fun authorizationFor(path: String) = requests.firstOrNull { it.path == path }?.headers?.get("authorization")
        fun reset() = requests.clear()

        private fun response(path: String): String = when (path) {
            "/v1/audio/transcriptions" -> """{"text":"Synthetic speech from the loopback provider."}"""
            "/v1/chat/completions" -> JSONObject().put("choices", org.json.JSONArray().put(
                JSONObject().put("message", JSONObject().put("content",
                    """{"title":"Synthetic Project Update","overview":"The team aligned on the next step and its timing.","points":["Meet Friday to review the launch plan."]}""")))).toString()
            "/docling/pre" -> """{"markdown":"Synthetic Docling transcript.","segments":[{"start":0.1,"end":1.5,"text":"Synthetic Docling transcript."}]}"""
            "/docling/post" -> """{"markdown":"Formatted synthetic notes\n\n- Review the launch plan Friday."}"""
            else -> "{}"
        }

        override fun close() {
            running = false
            socket.close()
            worker.join(1000)
        }

        private data class Request(val path: String, val headers: Map<String, String>, val body: ByteArray)

        companion object {
            private fun line(input: BufferedInputStream): String {
                val out = ByteArrayOutputStream()
                while (true) {
                    val byte = input.read()
                    if (byte < 0 || byte == 10) break
                    if (byte != 13) out.write(byte)
                }
                return out.toString("UTF-8")
            }
            private fun readFully(input: BufferedInputStream, out: ByteArray) {
                var at = 0
                while (at < out.size) { val n = input.read(out, at, out.size - at); if (n < 0) error("short body"); at += n }
            }
            private fun chunks(input: BufferedInputStream): ByteArray {
                val output = ByteArrayOutputStream()
                while (true) {
                    val sizeLine = line(input).substringBefore(';')
                    val size = sizeLine.toInt(16)
                    if (size == 0) { while (line(input).isNotEmpty()) Unit; break }
                    val chunk = ByteArray(size); readFully(input, chunk); output.write(chunk); line(input)
                }
                return output.toByteArray()
            }
        }
    }

    companion object {
        private val nextTimeOffset = AtomicLong()
    }
}
