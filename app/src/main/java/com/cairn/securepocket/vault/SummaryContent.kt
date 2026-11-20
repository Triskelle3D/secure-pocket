package com.cairn.securepocket.vault

import java.util.Locale

private val word = Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}]+)?")
private fun words(text: String): List<String> =
    word.findAll(text.lowercase(Locale.ROOT)).map { it.value.replace('’', '\'') }.toList()

internal fun transcriptCopy(summary: String, transcript: String): Boolean {
    val source = words(transcript)
    val body = words(summary.lineSequence().filterNot { it.trimStart().startsWith("#") }.joinToString("\n"))
    if (body.isEmpty() || source.isEmpty()) return false
    if (body == source) return true
    if (source.size < 6) return body.size <= source.size + 5 && body.joinToString(" ").contains(source.joinToString(" "))
    if (body.size < 4 || body.size < source.size * 0.72) return false
    val sourcePhrases = source.windowed(4).toHashSet()
    val candidates = body.windowed(4)
    return candidates.count { it in sourcePhrases } >= candidates.size * 0.75
}

internal fun cleanRecordingTitle(raw: String): String {
    val clean = raw.replace(Regex("[\\p{Cntrl}]+"), " ").trim().trim('#', '*', '"', '\'', '`').trim()
    return clean.trimEnd('.', ':', ',', ' ')
}

internal class SummaryQualityException(message: String) : IllegalArgumentException(message)
