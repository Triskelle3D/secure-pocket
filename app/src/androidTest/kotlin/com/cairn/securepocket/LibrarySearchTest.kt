package com.cairn.securepocket

import com.cairn.securepocket.vault.saveMarkdown
import com.cairn.securepocket.vault.SecurePreferences
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.cairn.securepocket.capture.CaptureSink
import com.cairn.securepocket.vault.manualTitleFile
import com.cairn.securepocket.vault.speakersFile
import com.cairn.securepocket.vault.summary
import com.cairn.securepocket.vault.transcript
import com.cairn.securepocket.vault.VaultStore
import com.cairn.securepocket.vault.titleFile
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicLong
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Library search only indexes fixture title/date/speaker metadata; these checks never use user
 * production-package files. */
class LibrarySearchTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val prefs get() = SecurePreferences.open(context, "settings")
    private lateinit var savedSettings: Map<String, *>
    private var sink: CaptureSink? = null
    private var record: VaultStore.Record? = null

    @Before fun requireIsolatedPackage() {
        check(context.packageName.endsWith(".isotest")) { "Search fixtures require the isolated validation app." }
        savedSettings = prefs.all.toMap()
        prefs.edit().putString("library_destination", "Phone").putBoolean("store_phone", true).commit()
    }

    @After fun cleanFixture() {
        if (!context.packageName.endsWith(".isotest")) return
        record?.let { r ->
            listOf(r.mp3, r.sidecar, r.transcript, r.summary, r.titleFile, r.manualTitleFile, r.speakersFile).forEach { it.delete() }
        }
        sink?.file?.delete()
        if (::savedSettings.isInitialized) {
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
        }
        Graph.runner.refresh()
    }

    @Test fun searchFindsFixtureTitleAndShowsNoMatchesForUnknownQuery() {
        val now = LocalDateTime.of(2000, 1, 1, 0, 0).plusSeconds(nextTimestamp.getAndIncrement())
        val capture = CaptureSink("search-${System.nanoTime()}", context.cacheDir)
        val bytes = instrumentation.context.assets.open("library-speech.mp3").use { it.readBytes() }
        capture.begin(); capture.append(bytes); capture.finish()
        sink = capture
        val fixture = Graph.runner.vault.importCapture(capture, now.toLocalDate(), now, bytes.size.toLong(), null)
        record = fixture
        val title = "SearchFixtureNeedle${System.nanoTime()}"
        fixture.titleFile.saveMarkdown(title)
        Graph.notes.changed.value++
        Graph.runner.refresh()

        ui.onNodeWithContentDescription("LIBRARY").performClick()
        ui.waitUntil(5000) { ui.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithContentDescription("Search library").performClick()
        ui.onNode(hasSetTextAction()).performTextInput(title)
        ui.waitUntil(5000) { ui.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(ui.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty())

        ui.onNode(hasSetTextAction()).performTextReplacement("SearchFixtureNoSuchTitle${System.nanoTime()}")
        ui.waitUntil(5000) { ui.onAllNodesWithText("NO MATCHES").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("NO MATCHES").assertExists()
        assertTrue("The synthetic fixture remains in the isolated vault", fixture.mp3.isFile)
    }

    companion object {
        private val nextTimestamp = AtomicLong()
    }
}
