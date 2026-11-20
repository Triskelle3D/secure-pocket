package com.cairn.securepocket.ui

import android.media.MediaPlayer
import android.media.PlaybackParams
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cairn.securepocket.vault.VaultStore
import kotlinx.coroutines.delay

/** One player for both the full recording and timestamped clips. */
internal class RecordingPlayback {
    private val player = MediaPlayer()
    var ready by mutableStateOf(false)
        private set
    var playing by mutableStateOf(false)
        private set
    var duration by mutableIntStateOf(0)
        private set
    var position by mutableIntStateOf(0)
        private set
    var speed by mutableFloatStateOf(1f)
        private set
    var error by mutableStateOf(false)
        private set
    var clip by mutableStateOf<Int?>(null)
        private set

    private var clipEnd: Long? = null
    private var seeking = false
    private var startAfterSeek = false
    private var appliedSpeed = 1f

    init {
        player.setOnPreparedListener { duration = it.duration.coerceAtLeast(0); ready = true }
        player.setOnCompletionListener { playing = false; position = duration; clearClip() }
        player.setOnErrorListener { _, _, _ -> fail(); true }
        player.setOnSeekCompleteListener {
            seeking = false
            if (startAfterSeek && ready) guarded { start() }
            startAfterSeek = false
        }
    }

    fun prepare(path: String) = guarded { player.setDataSource(path); player.prepareAsync() }

    fun pause() {
        startAfterSeek = false
        if (playing && ready) guarded { player.pause() }
        playing = false
        clearClip()
    }

    fun playFull() {
        if (!ready) return
        if (playing || startAfterSeek) {
            pause()
            return
        }
        clearClip()
        if (position >= duration) requestSeek(0, play = true)
        else if (seeking) startAfterSeek = true
        else guarded { start() }
    }

    fun playClip(index: Int, startMs: Long, endMs: Long) {
        if (!ready || seeking || startMs >= duration || endMs <= startMs) return
        if (clip == index) {
            pause()
            return
        }
        pause()
        if (!ready) return
        clip = index
        clipEnd = endMs.coerceAtMost(duration.toLong())
        requestSeek(startMs, play = true)
    }

    fun seekFull(ms: Int) {
        if (!ready || seeking) return
        clearClip()
        requestSeek(ms.toLong(), play = false)
    }

    fun changeSpeed() {
        speed = when (speed) { 1f -> 1.5f; 1.5f -> 2f; else -> 1f }
        if (ready && playing) guarded { applySpeed() }
    }

    fun updatePosition() {
        if (!ready || !playing || seeking) return
        guarded {
            position = player.currentPosition.coerceIn(0, duration)
            if (clipEnd?.let { position >= it } == true) pause()
        }
    }

    fun release() { player.release() }

    private fun requestSeek(ms: Long, play: Boolean) = guarded {
        seeking = true
        startAfterSeek = play
        position = ms.toInt().coerceIn(0, duration)
        player.seekTo(position.toLong(), MediaPlayer.SEEK_CLOSEST)
    }

    private fun start() {
        applySpeed()
        player.start()
        playing = true
    }

    private fun applySpeed() {
        if (speed == appliedSpeed) return
        player.playbackParams = PlaybackParams().allowDefaults().setSpeed(speed)
        appliedSpeed = speed
    }

    private fun clearClip() {
        clip = null
        clipEnd = null
    }

    private fun fail() {
        error = true
        ready = false
        playing = false
        seeking = false
        startAfterSeek = false
        clearClip()
    }

    private inline fun guarded(call: () -> Unit) {
        try { call() } catch (_: Exception) { fail() }
    }
}

@Composable
internal fun rememberRecordingPlayback(record: VaultStore.Record): RecordingPlayback {
    val playback = remember(record.mp3) { RecordingPlayback() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(playback, lifecycle) {
        playback.prepare(record.mp3.absolutePath)
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) playback.pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); playback.release() }
    }
    LaunchedEffect(playback, playback.playing) {
        while (playback.playing) {
            playback.updatePosition()
            delay(if (playback.clip == null) 150 else 35)
        }
    }
    return playback
}
