package com.cairn.securepocket

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.cairn.securepocket.capture.CaptureSink
import com.cairn.securepocket.vault.*
import java.time.LocalDateTime
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Verifies record actions only against one synthetic fixture in the isolated validation vault. */
class LibraryRecordActionsTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val prefs get() = SecurePreferences.open(context, "settings")
    private lateinit var savedSettings: Map<String, *>
    private var capture: CaptureSink? = null
    private var record: VaultStore.Record? = null

    @Before fun prepareIsolatedFixture() {
        check(context.packageName.endsWith(".isotest")) { "Record actions require the isolated validation app." }
        savedSettings = prefs.all.toMap()
        prefs.edit().putString("library_destination", "Phone").putBoolean("store_phone", true)
            .putString("llm_provider", "Off").commit()
    }

    @After fun cleanFixtureAndSettings() {
        if (!context.packageName.endsWith(".isotest") || !::savedSettings.isInitialized) return
        record?.let { r ->
            listOf(r.mp3, r.sidecar, r.transcript, r.summary, r.titleFile, r.manualTitleFile, r.speakersFile).forEach { it.delete() }
        }
        capture?.file?.delete()
        val editor = prefs.edit().clear()
        savedSettings.forEach { (key, value) -> when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
        } }
        editor.commit()
        Graph.runner.refresh()
    }

    @Test fun rosterSpeakerLabelRenameAndDeletePersistForFixture() {
        val start = LocalDateTime.of(2002, 1, 1, 0, 0)
        val sink = CaptureSink("actions-${System.nanoTime()}", context.cacheDir)
        val audio = instrumentation.context.assets.open("library-speech.mp3").use { it.readBytes() }
        sink.begin(); sink.append(audio); sink.finish()
        capture = sink
        val item = Graph.runner.vault.importCapture(sink, start.toLocalDate(), start, audio.size.toLong(), null)
        record = item
        val originalTitle = "ActionsFixture${System.nanoTime()}"
        item.titleFile.saveMarkdown(originalTitle)
        item.transcript.saveMarkdown("Synthetic meeting began.")
        item.saveSpeakers(Speakers(listOf("Ari"), listOf(SpeechSegment(0.1, 1.2, "Synthetic meeting began."))))
        Graph.notes.changed.value++
        Graph.runner.refresh()

        ui.onNodeWithContentDescription("LIBRARY").performClick()
        ui.waitUntil(5000) { ui.onAllNodesWithText(originalTitle).fetchSemanticsNodes().isNotEmpty() }

        ui.onNodeWithText(originalTitle).performTouchInput { longClick() }
        ui.onNodeWithText("Who?").performClick()
        ui.waitUntil(5000) { ui.onAllNodesWithText("New name").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("New name").performTextInput("Mina")
        ui.onNodeWithText("Add").performClick()
        ui.onNodeWithText("Save").performClick()
        ui.waitUntil(5000) { item.readSpeakers()?.people?.contains("Mina") == true }

        ui.onNodeWithText(originalTitle).performClick()
        ui.waitUntil(15000) { ui.onAllNodesWithText("Speaker").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("Speaker").performClick()
        ui.onNodeWithText("Mina").performClick()
        ui.onNodeWithText("Save transcript").performClick()
        ui.waitUntil(10000) { item.readSpeakers()?.segments?.firstOrNull()?.name == "Mina" }
        assertTrue(item.readSpeakers()?.segments?.firstOrNull()?.manual == true)
        assertTrue(readEncryptedText(item.transcript).contains("Mina: Synthetic meeting began."))
        ui.onNodeWithText("Close").performClick()

        ui.onNodeWithText(originalTitle).performTouchInput { longClick() }
        ui.onNodeWithText("Rename").performClick()
        val renamed = "RenamedFixture${System.nanoTime()}"
        ui.onNode(hasSetTextAction()).performTextReplacement(renamed)
        ui.onNodeWithText("Save").performClick()
        ui.waitUntil(5000) { item.title == renamed }
        assertEquals(renamed, readEncryptedText(item.manualTitleFile))

        ui.onNodeWithText(renamed).performTouchInput { longClick() }
        ui.onNodeWithText("Delete").performClick()
        ui.onNodeWithText("Delete").performClick()
        ui.waitUntil(5000) { !item.mp3.exists() }
        assertFalse(item.sidecar.exists())
        assertFalse(item.transcript.exists())
        assertFalse(item.speakersFile.exists())
    }
}
