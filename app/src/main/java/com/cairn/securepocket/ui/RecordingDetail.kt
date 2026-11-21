package com.cairn.securepocket.ui

import android.webkit.WebView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cairn.securepocket.Graph
import com.cairn.securepocket.vault.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

@Composable
fun RecordingDetail(rec: VaultStore.Record, showSummary: Boolean, dismiss: () -> Unit) {
    val jobs by Graph.notes.status.collectAsState()
    val status = jobs[rec.localId]
    val busy = status in listOf("Queued", "Transcribing…", "Generating summary…", "Formatting with Docling…")
    val file = if (showSummary) rec.summary else rec.transcript
    var saved by remember { mutableStateOf<String?>(null) }
    var duplicateSummary by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var editing by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    var discard by remember { mutableStateOf(false) }
    val notesVersion by Graph.notes.changed.collectAsState()
    LaunchedEffect(file, status, notesVersion) {
        try {
            val (text, copy) = withContext(Dispatchers.IO) {
                val markdown = if (file.isFile) readEncryptedText(file) else null
                markdown to (showSummary && markdown != null && rec.transcript.isFile &&
                    transcriptCopy(markdown, readEncryptedText(rec.transcript)))
            }
            saved = text
            duplicateSummary = copy
            loaded = true
        } catch (e: Exception) { error = "Could not read ${file.name}." }
    }
    val close = { if (editing && draft != saved.orEmpty()) discard = true else dismiss() }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val version by Graph.notes.changed.collectAsState()
        val title = remember(rec, version) { rec.title }
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (showSummary) "Summary" else "Recording", Modifier.weight(1f),
                        style = MaterialTheme.typography.headlineMedium)
                    if (showSummary && !editing)
                        OutlinedButton(enabled = loaded && !busy, onClick = { draft = saved.orEmpty(); editing = true }) { Text("Edit summary") }
                    if (editing) Button(onClick = {
                        try {
                            synchronized(Graph.notes) { file.saveMarkdown(draft) }
                            Graph.notes.changed.value++
                            Graph.backup.request()
                            saved = draft
                            duplicateSummary = showSummary && rec.transcript.isFile && transcriptCopy(draft, readEncryptedText(rec.transcript))
                            editing = false
                            error = ""
                        } catch (e: Exception) { error = "Save failed. Your draft is still open." }
                    }) { Text("Save") }
                    OutlinedButton(onClick = close) { Text("Close") }
                }
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text("${rec.date} · ${rec.startAt.toLocalTime().withNano(0)}", style = MaterialTheme.typography.labelLarge)
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
                if (!showSummary) {
                    val playback = rememberRecordingPlayback(rec)
                    AudioPlayer(playback)
                    if (loaded && saved == null && !busy) {
                        TextButton(onClick = { Graph.notes.process(rec, makeSummary = false) }) { Text("Transcribe") }
                    }
                    if (status != null && status != "Saved") Text(status, style = MaterialTheme.typography.bodySmall)
                    Text("Transcript", style = MaterialTheme.typography.titleLarge)
                    SpeakerTranscript(rec, playback, busy, saved, Modifier.weight(1f))
                } else if (editing) {
                    OutlinedTextField(draft, { draft = it }, modifier = Modifier.fillMaxSize(),
                        label = { Text("Markdown source") }, textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
                } else {
                    if (loaded && saved == null) {
                        Text("Generate a summary with the LLM selected in Settings.")
                        Button(enabled = !busy, onClick = { Graph.notes.process(rec, true) }) { Text("Generate summary") }
                    }
                    if (status != null && status != "Saved") Text(status, style = MaterialTheme.typography.bodySmall)
                    if (saved != null) {
                        if (duplicateSummary) Text("This summary repeats the transcript. Regenerate it for a shorter version.")
                        var confirm by remember { mutableStateOf(false) }
                        Button(enabled = !busy, onClick = { confirm = true }) { Text("Regenerate summary") }
                        if (confirm) AlertDialog(onDismissRequest = { confirm = false },
                            title = { Text("Replace this summary?") },
                            text = { Text("The model will replace the summary. Any title you set manually will be kept.") },
                            confirmButton = { TextButton(onClick = { confirm = false; Graph.notes.process(rec, true, replace = true) }) { Text("Replace") } },
                            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
                    }
                    if (!duplicateSummary) saved?.let { MarkdownPreview(it) }
                }
            }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Discard your edits?") },
        confirmButton = { TextButton(onClick = dismiss) { Text("Discard") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("Keep editing") } })
}

@Composable
private fun MarkdownPreview(source: String) {
    val html = remember(source) {
        val renderer = HtmlRenderer.builder().escapeHtml(true).sanitizeUrls(true).build()
        val body = renderer.render(Parser.builder().build().parse(source))
        """<meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'">
        <style>body{background:#121216;color:#f4f4f6;font:16px/1.6 sans-serif;margin:12px;overflow-wrap:anywhere}pre{white-space:pre-wrap}blockquote{border-left:2px solid #71717a;padding-left:12px}a{color:#ace}</style>$body"""
    }
    AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
        WebView(ctx).apply {
            settings.blockNetworkLoads = true
            settings.allowContentAccess = false
            settings.allowFileAccess = false
            webViewClient = object : android.webkit.WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest) = true
            }
            setBackgroundColor(android.graphics.Color.rgb(18,18,22))
        }
    }, onRelease = { it.destroy() }, update = {
        if (it.tag != html) { it.tag = html; it.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null) }
    })
}

private fun clock(ms: Int) = "%d:%02d".format(ms / 60000, ms / 1000 % 60)

@Composable
private fun AudioPlayer(playback: RecordingPlayback) {
    var scrub by remember { mutableStateOf<Float?>(null) }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = 12.dp)) {
            Text(if (playback.error) "This file could not be played." else "Original audio")
            Slider(
                value = (scrub ?: playback.position.toFloat()).coerceAtMost(playback.duration.toFloat()),
                onValueChange = { scrub = it },
                enabled = playback.ready,
                valueRange = 0f..playback.duration.coerceAtLeast(1).toFloat(),
                onValueChangeFinished = {
                    scrub?.let { playback.seekFull(it.toInt()) }
                    scrub = null
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = playback.ready, onClick = playback::playFull) {
                    Text(if (playback.playing) "Pause" else "Play")
                }
                Spacer(Modifier.width(8.dp))
                Text("${clock(scrub?.toInt() ?: playback.position)} / ${clock(playback.duration)}",
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                TextButton(enabled = playback.ready, onClick = playback::changeSpeed) {
                    Text("Speed  " + when (playback.speed) { 1f -> "1×"; 2f -> "2×"; else -> "1.5×" })
                }
            }
        }
    }
}
