package com.cairn.securepocket.ui

import android.media.MediaPlayer
import android.media.PlaybackParams
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cairn.securepocket.Graph
import com.cairn.securepocket.vault.LinkFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.zip.ZipInputStream

private data class RemoteBundleContent(
    val audio: File,
    val transcript: String?,
    val summary: String?,
    val title: String,
)

@Composable
internal fun RemoteRecordingViewer(file: LinkFile, close: () -> Unit) {
    var content by remember(file) { mutableStateOf<RemoteBundleContent?>(null) }
    var error by remember(file) { mutableStateOf<String?>(null) }
    var loading by remember(file) { mutableStateOf(true) }
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(file) {
        if (file.bundle == null) {
            error = "This server entry has no recording bundle."
            loading = false
            return@LaunchedEffect
        }
        val result = withContext(Dispatchers.IO) { runCatching { fetchRemoteBundle(context, file) } }
        result.onSuccess { content = it }.onFailure { error = it.message ?: "Could not open the server recording." }
        loading = false
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(content?.title ?: "Server recording", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = close) { Text("Close") }
                }
                when {
                    loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                    content != null -> {
                        RemoteAudioPlayer(content!!.audio)
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            content!!.summary?.let { Text("Summary\n\n$it", style = MaterialTheme.typography.bodyLarge) }
                            content!!.transcript?.let { Text("Transcript\n\n$it", style = MaterialTheme.typography.bodyMedium) }
                            if (content!!.summary == null && content!!.transcript == null)
                                Text("Audio is stored on the server. No transcript or summary is available yet.")
                        }
                    }
                }
            }
        }
    }
}

private fun fetchRemoteBundle(context: android.content.Context, file: LinkFile): RemoteBundleContent {
    val bundle = file.bundle ?: error("This server entry has no recording bundle.")
    require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}-[0-9]{6}-[a-f0-9]{8}[.]zip").matches(bundle)) { "Invalid server bundle name." }
    var connection: HttpURLConnection? = null
    var lastFailure: Exception? = null
    for (base in Graph.link.baseUrls()) {
        var candidate: HttpURLConnection? = null
        try {
            val uri = URI("$base/library/$bundle")
            com.cairn.securepocket.vault.checkedRemoteUri(uri.toString())
            val current = uri.toURL().openConnection() as HttpURLConnection
            candidate = current
            current.instanceFollowRedirects = false
            current.connectTimeout = 8000
            current.readTimeout = 60000
            Graph.link.authToken().takeIf { it.isNotBlank() }
                ?.let { current.setRequestProperty("X-Pocket-Token", it) }
            val code = current.responseCode
            if (code == 200) {
                connection = current
                break
            }
            if (code == 401 || code == 403) error("Server rejected the saved Link token.")
            lastFailure = IllegalStateException("Server returned HTTP $code.")
        } catch (e: Exception) {
            if (e.message?.contains("rejected the saved Link token") == true) throw e
            lastFailure = e
        } finally {
            if (connection !== candidate) candidate?.disconnect()
        }
    }
    val activeConnection = connection ?: throw (lastFailure ?: IllegalStateException("Enter a Secure Pocket Link address."))
    val audioFile = File.createTempFile("remote-recording-", ".mp3", context.cacheDir)
    var transcript: String? = null
    var summary: String? = null
    var title: String? = null
    try {
        var total = 0L
        ZipInputStream(activeConnection.inputStream).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && '/' !in entry.name && '\\' !in entry.name && entry.name.length <= 110) { "Invalid server bundle entry." }
                val limit = if (entry.name.endsWith(".mp3")) 1024L * 1024 * 512 else 1024L * 1024 * 2
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(65536)
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    total += count
                    check(out.size().toLong() + count <= limit && total <= 1024L * 1024 * 520) { "Server bundle is too large." }
                    out.write(buffer, 0, count)
                }
                when {
                    entry.name.endsWith(".mp3") -> audioFile.writeBytes(out.toByteArray())
                    entry.name.endsWith(".transcript.md") -> transcript = out.toString(Charsets.UTF_8.name())
                    entry.name.endsWith(".summary.md") -> summary = out.toString(Charsets.UTF_8.name())
                    entry.name == "title.txt" -> title = out.toString(Charsets.UTF_8.name()).trim()
                    else -> error("Unsupported server bundle entry.")
                }
            }
        }
        check(audioFile.length() > 0) { "Server bundle has no audio." }
        return RemoteBundleContent(audioFile, transcript, summary, title?.takeIf(String::isNotBlank) ?: file.name.removeSuffix(".mp3"))
    } catch (e: Exception) {
        audioFile.delete()
        throw e
    } finally {
        activeConnection.disconnect()
    }
}

@Composable
private fun RemoteAudioPlayer(audio: File) {
    val player = remember(audio) { MediaPlayer() }
    var ready by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var duration by remember { mutableIntStateOf(0) }
    var position by remember { mutableIntStateOf(0) }
    var speed by remember { mutableFloatStateOf(1f) }
    var failed by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(player, lifecycle) {
        player.setDataSource(audio.absolutePath)
        player.setOnPreparedListener { duration = it.duration; ready = true }
        player.setOnCompletionListener { playing = false; position = duration }
        player.setOnErrorListener { _, _, _ -> failed = true; playing = false; true }
        player.prepareAsync()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && player.isPlaying) { player.pause(); playing = false }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); runCatching { player.release() }; audio.delete() }
    }
    LaunchedEffect(playing) {
        while (playing) {
            runCatching { position = player.currentPosition }
            delay(200)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(if (failed) "Audio playback failed." else "Original audio", style = MaterialTheme.typography.titleMedium)
        Slider(value = position.toFloat().coerceAtMost(duration.toFloat()), onValueChange = { position = it.toInt() },
            onValueChangeFinished = { if (ready) player.seekTo(position) }, valueRange = 0f..duration.coerceAtLeast(1).toFloat(), enabled = ready)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(enabled = ready, onClick = {
                if (playing) { player.pause(); playing = false }
                else {
                    if (position >= duration) player.seekTo(0)
                    player.playbackParams = PlaybackParams().allowDefaults().setSpeed(speed)
                    player.start(); playing = true
                }
            }) { Text(if (playing) "Pause" else if (ready) "Play" else "Loading audio…") }
            TextButton(enabled = ready, onClick = {
                speed = when (speed) { 1f -> 1.5f; 1.5f -> 2f; else -> 1f }
                if (playing) player.playbackParams = PlaybackParams().allowDefaults().setSpeed(speed)
            }) { Text("${speed}×") }
        }
    }
}
