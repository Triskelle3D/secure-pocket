package com.cairn.securepocket.vault

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.sqrt

internal data class SpeechSegment(
    val start: Double,
    val end: Double,
    val text: String,
    val voice: List<Double> = emptyList(),
    val frames: Int = 0,
    val name: String? = null,
    val manual: Boolean = false,
)

internal data class Speakers(val people: List<String>, val segments: List<SpeechSegment>, val source: String = "vosk") {
    fun withVoiceMatches(): Speakers {
        val anchors = segments.filter { it.manual && !it.name.isNullOrBlank() && it.frames >= 65 && it.voice.isNotEmpty() }
            .groupBy { it.name!! }
        return copy(segments = segments.map { segment ->
            if (segment.manual) segment else {
                val candidates = if (segment.frames < 65 || segment.voice.isEmpty()) emptyList() else
                    anchors.mapNotNull { (name, samples) ->
                        samples.mapNotNull { cosine(segment.voice, it.voice) }.maxOrNull()?.let { name to it }
                    }.sortedByDescending { it.second }
                val first = candidates.firstOrNull()
                val second = candidates.getOrNull(1)?.second
                val match = first?.takeIf { it.second >= 0.78 && (second == null || it.second - second >= 0.10) }
                segment.copy(name = match?.first)
            }
        })
    }

    fun transcript(): String = segments.joinToString("\n\n") {
        "[${speakerTime(it.start)}–${speakerTime(it.end)}] ${it.name ?: "Unidentified speaker"}: ${it.text}"
    }

    private fun cosine(a: List<Double>, b: List<Double>): Double? {
        if (a.size != b.size) return null
        val den = sqrt(a.sumOf { it * it } * b.sumOf { it * it })
        if (den <= 0 || !den.isFinite()) return null
        return a.indices.sumOf { a[it] * b[it] } / den
    }
}

internal val VaultStore.Record.speakersFile get() = mp3.resolveSibling(mp3.name.removeSuffix(".mp3.enc") + ".speakers.json.enc")
internal val VaultStore.Record.manualTitleFile get() = mp3.resolveSibling(mp3.name.removeSuffix(".mp3.enc") + ".title-manual.enc")
internal fun VaultStore.Record.readSpeakers(): Speakers? {
    if (!speakersFile.isFile) return null
    val root = JSONObject(readEncryptedText(speakersFile))
    val names = root.getJSONArray("people")
    val rows = root.getJSONArray("segments")
    return Speakers(List(names.length()) { names.getString(it) }, List(rows.length()) {
        val row = rows.getJSONObject(it)
        val vector = row.optJSONArray("voice") ?: JSONArray()
        SpeechSegment(row.getDouble("start"), row.getDouble("end"), row.getString("text"),
            List(vector.length()) { i -> vector.getDouble(i) }, row.optInt("frames"),
            row.optString("name").ifBlank { null }, row.optBoolean("manual"))
    }, root.optString("source", "vosk"))
}

internal fun VaultStore.Record.saveSpeakers(speakers: Speakers) {
    val rows = JSONArray()
    for (segment in speakers.segments) rows.put(JSONObject()
        .put("start", segment.start).put("end", segment.end).put("text", segment.text)
        .put("voice", JSONArray(segment.voice)).put("frames", segment.frames)
        .put("name", segment.name.orEmpty()).put("manual", segment.manual))
    speakersFile.saveMarkdown(JSONObject().put("people", JSONArray(speakers.people))
        .put("segments", rows).put("source", speakers.source).toString())
}

internal fun speakerTime(seconds: Double): String {
    val whole = seconds.toInt().coerceAtLeast(0)
    return String.format(Locale.ROOT, "%02d:%02d:%02d", whole / 3600, whole / 60 % 60, whole % 60)
}
