package com.cairn.securepocket

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.cairn.securepocket.capture.CaptureSink
import com.cairn.securepocket.vault.*
import java.time.LocalDateTime
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RecordingFlowTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun summaryPreferenceSurvivesNavigation() {
        val prefs = SecurePreferences.open(ui.activity, "settings")
        val draft = SecurePreferences.open(ui.activity, "settings_draft")
        val original = prefs.getInt("summary_points", 3)
        val changed = if (original == 5) 8 else 5
        try {
            ui.onNodeWithContentDescription("SETTINGS").performClick()
            ui.onNodeWithText("Summaries").performClick()
            ui.onNodeWithText(changed.toString()).performScrollTo().performClick()
            ui.waitUntil(5000) { draft.getInt("summary_points", 3) == changed }
            ui.onNodeWithText("Save settings").performScrollTo().performClick()
            ui.onNodeWithText("Settings saved.").assertExists()
            ui.waitUntil(5000) { prefs.getInt("summary_points", 3) == changed }
            assertEquals(changed, prefs.getInt("summary_points", 3))
            ui.onNodeWithContentDescription("LIBRARY").performClick()
            ui.onNodeWithContentDescription("SETTINGS").performClick()
            ui.onNodeWithText("Summaries").performClick()
            ui.onNodeWithText(changed.toString()).assertIsSelected()
        } finally {
            prefs.edit().putInt("summary_points", original).commit()
            draft.edit().clear().commit()
        }
    }

    @Test fun speechPlaybackMarkdownRoundTrip() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val now = LocalDateTime.now().withNano(0)
        val sink = CaptureSink("ui-test-" + System.nanoTime(), ins.targetContext.cacheDir)
        val audio = ins.context.assets.open("library-speech.mp3").use { it.readBytes() }
        sink.begin(); sink.append(audio); sink.finish()
        val r = Graph.runner.vault.importCapture(sink, now.toLocalDate(), now, audio.size.toLong(), null)
        try {
            Graph.runner.refresh()
            ui.onNodeWithContentDescription("LIBRARY").performClick()
            val row = hasText("sha:" + r.sha256.take(8)) and hasClickAction()
            ui.onNode(row).performClick()
            ui.waitUntil(15000) { ui.onAllNodes(hasText("Play") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("Play").performClick()
            ui.onNodeWithText("Speed  1×").performClick()
            ui.onNodeWithText("Speed  1.5×").performClick()
            ui.onNodeWithText("Speed  2×").assertExists()
            ui.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).performSemanticsAction(SemanticsActions.SetProgress) { set -> set(2000f) }
            ui.onNodeWithText("Pause").performClick()
            ui.onNodeWithText("Transcribe").performClick()
            ui.waitUntil(90000) { r.transcript.isFile || (Graph.notes.status.value[r.localId]?.let {
                it !in listOf("Queued", "Transcribing…", "Generating summary…", "Formatting with Docling…", "Saved") } == true) }
            assertTrue("Speech result: " + Graph.notes.status.value[r.localId], r.transcript.isFile)
            assertTrue(readEncryptedText(r.transcript).contains("pocket", ignoreCase = true))
            ui.onNodeWithText("Close").performClick()
            r.summary.saveMarkdown("# Original summary\n")
            Graph.notes.changed.value++
            ui.onNodeWithText("SUMMARIES").performClick()
            ui.onNode(row).performClick()
            ui.waitUntil(15000) {
                ui.onAllNodes(hasText("Edit summary") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            ui.onNodeWithText("Edit summary").performClick()
            ui.waitUntil(10000) {
                ui.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
            }
            val revised = "# Field notes\n\n- Meet on **Friday**."
            ui.onNode(hasSetTextAction()).performTextReplacement(revised)
            ui.onNodeWithText("Save").performClick()
            ui.onNodeWithText("Close").performClick()
            ui.onNode(row).performClick()
            ui.onNodeWithText("Edit summary").performClick()
            ui.onNode(hasSetTextAction()).assertTextContains(revised)
            assertEquals(revised, readEncryptedText(r.summary))
            val audioForHash = Graph.runner.vault.materialize(r.mp3)
            try { assertEquals(r.sha256, VaultStore.sha256(audioForHash)) } finally { audioForHash.delete() }
        } finally {
            listOf(sink.file, r.mp3, r.sidecar, r.transcript, r.summary).forEach { it.delete() }
            Graph.runner.refresh()
        }
    }
}
