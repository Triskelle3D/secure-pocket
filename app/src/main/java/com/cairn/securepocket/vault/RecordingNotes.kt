package com.cairn.securepocket.vault

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.SpeakerModel

val VaultStore.Record.titleFile get() = mp3.resolveSibling(mp3.nameWithoutExtension + ".title")
val VaultStore.Record.title: String
    get() = runCatching {
        val manual = if (manualTitleFile.isFile) manualTitleFile.readText().trim() else ""
        if (manual.isNotEmpty() && manual != "manual") manual
        else if (manual == "manual") titleFile.readText().trim()
        else cleanRecordingTitle(titleFile.readText())
    }.getOrNull()?.takeIf { it.isNotEmpty() } ?: "Recording " + startAt.toLocalTime().toString().take(5)

val VaultStore.Record.transcript get() = mp3.resolveSibling(mp3.nameWithoutExtension + ".transcript.md")
val VaultStore.Record.summary get() = mp3.resolveSibling(mp3.nameWithoutExtension + ".summary.md")

fun File.saveMarkdown(text: String) {
    val temp = File.createTempFile("note-", ".tmp", parentFile)
    try {
        temp.writeText(text)
        Files.move(temp.toPath(), toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally { temp.delete() }
}

/** One speech decoder at a time. Jobs survive navigation and release native resources on completion. */
class RecordingNotes(private val context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val providers = ProcessingProviders(prefs, OnDeviceModel(context))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val decoder = Mutex()
    val status = MutableStateFlow<Map<String,String>>(emptyMap())
    private val active = mutableSetOf<String>()
    val changed = MutableStateFlow(0L)

    fun imported(record: VaultStore.Record) {
        if (!record.partial && prefs.getBoolean("auto_transcribe", true)) process(record,
            prefs.getBoolean("auto_summary", true) && providers.summaryProvider != "Off")
        com.cairn.securepocket.Graph.backup.request()
    }

    @Synchronized fun isBusy(record: VaultStore.Record) = record.localId in active

    @Synchronized fun rename(record: VaultStore.Record, title: String) {
        require(title.isNotBlank() && title.length <= 160) { "Use a title between 1 and 160 characters." }
        check(record.mp3.isFile) { "Recording no longer exists." }
        record.manualTitleFile.saveMarkdown(title.trim())
        record.titleFile.saveMarkdown(title.trim())
        changed.update { it + 1 }
        com.cairn.securepocket.Graph.backup.request()
    }

    @Synchronized fun deleteLocal(record: VaultStore.Record, summaryOnly: Boolean) {
        check(!isBusy(record)) { "Wait until processing finishes." }
        if (summaryOnly) java.nio.file.Files.deleteIfExists(record.summary.toPath())
        else {
            java.nio.file.Files.deleteIfExists(record.sidecar.toPath())
            listOf(record.mp3, record.transcript, record.summary, record.titleFile, record.manualTitleFile, record.speakersFile)
                .forEach { java.nio.file.Files.deleteIfExists(it.toPath()) }
        }
        changed.update { it + 1 }
        com.cairn.securepocket.Graph.runner.refresh()
    }

    internal fun pocketTitle(id: String): String? = prefs.getString(pocketTitleKey(id), null)

    internal fun renamePocket(id: String, title: String) {
        require(title.isNotBlank() && title.length <= 160) { "Use a title between 1 and 160 characters." }
        prefs.edit().putString(pocketTitleKey(id), title.trim()).apply()
        changed.update { it + 1 }
    }

    private fun pocketTitleKey(id: String) = "title_${prefs.getString("ble_mac", "")}_$id"

    internal suspend fun saveRoster(record: VaultStore.Record, people: List<String>) = withContext(Dispatchers.IO) {
        synchronized(this@RecordingNotes) {
            check(record.mp3.isFile) { "Recording no longer exists." }
            val old = record.readSpeakers() ?: Speakers(emptyList(), emptyList())
            record.saveSpeakers(old.copy(people = people))
        }
        changed.update { it + 1 }
    }

    internal suspend fun analyzeSpeakers(record: VaultStore.Record): Speakers = withContext(Dispatchers.IO) {
        decoder.withLock {
            val old = record.readSpeakers()
            if (providers.useDoclingPre && old?.source != "docling") {
                val result = providers.doclingTranscript(record.mp3)
                check(result.segments.isNotEmpty()) { "Docling returned no timed speech segments." }
                val updated = Speakers(old?.people.orEmpty(), result.segments, source = "docling")
                synchronized(this@RecordingNotes) {
                    check(record.mp3.isFile) { "Recording no longer exists." }
                    record.transcript.saveMarkdown(result.markdown)
                    record.saveSpeakers(updated)
                }
                changed.update { it + 1 }
                com.cairn.securepocket.Graph.backup.request()
                updated
            }
            else if (old != null && old.segments.isNotEmpty()) old
            else Speakers(old?.people.orEmpty(), transcribe(record.mp3, speakers = true)).also {
                check(it.segments.isNotEmpty()) { "No speech segments detected." }
                synchronized(this@RecordingNotes) {
                    if (record.mp3.isFile) record.saveSpeakers(it.copy(people = record.readSpeakers()?.people ?: it.people))
                }
            }.let { record.readSpeakers() ?: it }
        }
    }

    internal suspend fun applySpeakers(record: VaultStore.Record, labels: Speakers): Int = withContext(Dispatchers.IO) {
        val matched = decoder.withLock {
            val assigned = labels.withVoiceMatches()
            synchronized(this@RecordingNotes) {
                check(record.mp3.isFile && !isBusy(record)) { "Wait until recording processing finishes." }
                record.saveSpeakers(assigned)
                record.transcript.saveMarkdown(assigned.transcript())
            }
            changed.update { it + 1 }
            com.cairn.securepocket.Graph.backup.request()
            assigned
        }
        if (providers.summaryProvider != "Off") process(record, makeSummary = true, replace = true)
        matched.segments.count { it.name == null }
    }

    @Synchronized fun process(record: VaultStore.Record, makeSummary: Boolean = prefs.getBoolean("auto_summary", true) && providers.summaryProvider != "Off", replace: Boolean = false) {
        if (!active.add(record.localId)) return
        fun report(message: String) = status.update { it + (record.localId to message) }
        report("Queued")
        scope.launch {
            try {
                decoder.withLock {
                    if (!record.transcript.exists()) {
                        report("Transcribing…")
                        val doclingResult = if (providers.useDoclingPre) providers.doclingTranscript(record.mp3) else null
                        val text = when {
                            doclingResult != null -> doclingResult.markdown
                            providers.speechProvider == "On-device Vosk" -> transcribe(record.mp3).joinToString("\n\n") { it.text }
                            else -> providers.speech(record.mp3)
                        }
                        check(text.isNotBlank()) { "No speech detected." }
                        record.transcript.saveMarkdown(text)
                        if (doclingResult != null && doclingResult.segments.isNotEmpty()) {
                            val people = runCatching { record.readSpeakers()?.people }.getOrNull().orEmpty()
                            record.saveSpeakers(Speakers(people, doclingResult.segments, source = "docling"))
                        }
                        changed.update { it + 1 }
                        com.cairn.securepocket.Graph.backup.request()
                    }
                    if (makeSummary && (!record.summary.exists() || replace ||
                            record.summary.useLines { it.firstOrNull() } == "# Highlights")) {
                        report("Generating summary…")
                        val transcript = record.transcript.readText()
                        val result = providers.summarize(transcript, runCatching { record.readSpeakers()?.people }.getOrNull().orEmpty())
                        val markdown = if (providers.useDoclingPost) {
                            report("Formatting with Docling…")
                            providers.docling("post", null, result.markdown)
                        } else result.markdown
                        if (transcriptCopy(markdown, transcript)) throw SummaryQualityException("The model repeated the transcript.")
                        check(markdown.isNotBlank()) { "Docling returned an empty summary." }
                        synchronized(this@RecordingNotes) {
                            record.summary.saveMarkdown(markdown)
                            if (!record.manualTitleFile.isFile) record.titleFile.saveMarkdown(result.title)
                        }
                    }
                    changed.update { it + 1 }
                    com.cairn.securepocket.Graph.backup.request()
                    report("Saved")
                }
            } catch (e: Exception) {
                report(e.localizedMessage ?: "Could not process recording.")
            } catch (e: LinkageError) {
                report("Speech engine unavailable for this device.")
            } finally { synchronized(this@RecordingNotes) { active.remove(record.localId) } }
        }
    }

    private fun modelPath(name: String): File {
        val root = File(context.noBackupFilesDir, name)
        val ready = File(root, ".ready")
        if (ready.exists()) return root
        ZipInputStream(context.assets.open("$name.zip")).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val file = File(context.noBackupFilesDir, entry.name)
                if (!entry.isDirectory) {
                    require(file.canonicalPath.startsWith(root.canonicalPath + File.separator))
                    file.parentFile!!.mkdirs()
                    file.outputStream().use { zip.copyTo(it) }
                }
            }
        }
        ready.writeText("ok")
        return root
    }

    private fun transcribe(file: File, speakers: Boolean = false): List<SpeechSegment> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("No audio track found.")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            require(rate >= 16000) { "Speech recognition needs audio at 16 kHz or higher." }
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                Model(modelPath("vosk-model-small-en-us-0.15").absolutePath).use { model ->
                    if (speakers) SpeakerModel(modelPath("vosk-model-spk-0.4").absolutePath).use { speaker ->
                        Recognizer(model, rate.toFloat(), speaker).use {
                            return decode(extractor, codec, it, format)
                        }
                    } else Recognizer(model, rate.toFloat()).use {
                        return decode(extractor, codec, it, format)
                    }
                }
            } finally { codec.release() }
        } finally { extractor.release() }
    }

    private fun decode(extractor: MediaExtractor, codec: MediaCodec, recognizer: Recognizer, source: MediaFormat): List<SpeechSegment> {
        val lines = mutableListOf<SpeechSegment>()
        recognizer.setWords(true)
        fun append(json: String) {
            val result = JSONObject(json)
            val text = result.optString("text").trim()
            if (text.isEmpty()) return
            val entries = result.optJSONArray("result")
            val first = entries?.optJSONObject(0)
            val last = entries?.optJSONObject((entries.length() - 1).coerceAtLeast(0))
            val start = first?.optDouble("start", 0.0) ?: 0.0
            val end = last?.optDouble("end", start) ?: start
            val vector = result.optJSONArray("spk")
            lines += SpeechSegment(start, end, text,
                if (vector == null) emptyList() else List(vector.length()) { vector.getDouble(it) },
                result.optInt("spk_frames"))
        }
        val info = MediaCodec.BufferInfo()
        var channels = source.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var inputEnded = false
        while (true) {
            if (!inputEnded) {
                val index = codec.dequeueInputBuffer(10000)
                if (index >= 0) {
                    val buffer = codec.getInputBuffer(index)!!
                    val size = extractor.readSampleData(buffer, 0)
                    inputEnded = size < 0
                    codec.queueInputBuffer(index, 0, size.coerceAtLeast(0), extractor.sampleTime.coerceAtLeast(0),
                        if (inputEnded) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                    if (!inputEnded) extractor.advance()
                }
            }
            val index = codec.dequeueOutputBuffer(info, 10000)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                require(codec.outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT) == AudioFormat.ENCODING_PCM_16BIT)
            }
            if (index < 0) continue
            if (info.size > 0) {
                val buffer = codec.getOutputBuffer(index)!!.order(ByteOrder.LITTLE_ENDIAN)
                buffer.position(info.offset)
                val mono = ShortArray(info.size / (2 * channels)) {
                    var sum = 0
                    repeat(channels) { sum += buffer.short }
                    (sum / channels).toShort()
                }
                if (recognizer.acceptWaveForm(mono, mono.size)) append(recognizer.result)
            }
            codec.releaseOutputBuffer(index, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
        }
        append(recognizer.finalResult)
        return lines
    }
}
