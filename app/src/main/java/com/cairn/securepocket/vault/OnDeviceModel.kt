package com.cairn.securepocket.vault

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.ByteArrayInputStream
import java.io.File
import java.io.SequenceInputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream

internal class OnDeviceModel(private val context: Context) {
    val file get() = File(context.noBackupFilesDir, "summary.task")
    private val nameFile get() = File(context.noBackupFilesDir, "summary.model-name")
    val displayName: String
        get() = nameFile.takeIf { file.isFile && it.isFile }?.readText()?.trim()?.take(100)
            ?.takeIf { it.isNotBlank() } ?: "MediaPipe model"

    fun remove() {
        file.delete()
        nameFile.delete()
    }

    fun import(uri: Uri) {
        val displayName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?.takeIf { it.isNotBlank() } ?: "MediaPipe model"
        val temp = File.createTempFile("llm-", ".task", context.noBackupFilesDir)
        try {
            val free = context.noBackupFilesDir.usableSpace
            requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    var written = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        written += count
                        require(written <= minOf(free, 2L * 1024 * 1024 * 1024)) { "Choose a small model with enough free storage." }
                        output.write(buffer, 0, count)
                    }
                }
            }
            temp.inputStream().use { source ->
                val prefix = ByteArray(4)
                require(source.read(prefix) == prefix.size) { "Model file is too short." }
                val isZip = prefix.contentEquals(byteArrayOf(0x50, 0x4b, 0x03, 0x04))
                require(isZip || prefix.all { it == 0.toByte() }) { "Choose a MediaPipe LLM .task model." }
                val input = if (isZip) SequenceInputStream(ByteArrayInputStream(prefix), source) else source
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null && !entry.name.endsWith(".tflite") && entry.name != "TF_LITE_PREFILL_DECODE") {
                        entry = zip.nextEntry
                    }
                    require(entry != null) { "Choose a MediaPipe LLM .task model." }
                }
            }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            nameFile.writeText(displayName)
        } finally { temp.delete() }
    }

    fun summarize(instructions: String, transcript: String): ProcessingProviders.Summary {
        check("arm64-v8a" in android.os.Build.SUPPORTED_ABIS) { "On-device summaries require an ARM64 phone." }
        require(file.isFile) { "Choose a MediaPipe .task model in Settings → Summaries first." }
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(file.absolutePath)
            .setPreferredBackend(LlmInference.Backend.CPU)
            .setMaxTokens(1280)
            .build()
        LlmInference.createFromOptions(context, options).use { llm ->
            fun reduce(text: String, depth: Int): ProcessingProviders.Summary {
                val prompt = "$instructions\n\nTranscript:\n$text"
                if (llm.sizeInTokens(prompt) <= 900) {
                    return ProcessingProviders.parseSummary(llm.generateResponse(prompt), text)
                }
                check(depth < 8) { "The transcript is too long for this model." }
                val midpoint = text.indexOf(' ', text.length / 2).takeIf { it in 1 until text.lastIndex } ?: text.length / 2
                val first = reduce(text.substring(0, midpoint), depth + 1)
                val second = reduce(text.substring(midpoint), depth + 1)
                return reduce(first.markdown + "\n\n" + second.markdown, depth + 1)
            }
            return reduce(transcript, 0)
        }
    }
}
