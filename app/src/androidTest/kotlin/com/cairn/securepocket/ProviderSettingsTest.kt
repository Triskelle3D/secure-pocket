package com.cairn.securepocket

import com.cairn.securepocket.vault.SecurePreferences
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Settings choices are checked through the same pending draft and Save path used by the app. */
class ProviderSettingsTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val prefs by lazy { SecurePreferences.open(ui.activity, "settings") }
    private val draft by lazy { SecurePreferences.open(ui.activity, "settings_draft") }
    private lateinit var original: Map<String, *>

    @Before fun snapshotSettings() {
        check(ui.activity.packageName.endsWith(".isotest")) { "Provider settings fixtures require the isolated validation app." }
        original = prefs.all.toMap()
    }

    @After fun restoreSettings() {
        if (!ui.activity.packageName.endsWith(".isotest") || !::original.isInitialized) return
        val editor = prefs.edit().clear()
        original.forEach { (key, value) -> when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
        } }
        editor.commit()
        draft.edit().clear().commit()
    }

    @Test fun everySpeechDoclingAndSummaryProviderChoiceCanBeSaved() {
        ui.onNodeWithContentDescription("SETTINGS").performClick()

        selectAll("Choose Speech engine", listOf("On-device Vosk", "OpenAI", "Compatible server"))
        selectAll("Choose Docling stages", listOf("Off", "Pre", "Post", "Both"))
        ui.onNodeWithText("Summaries").performScrollTo().performClick()
        selectAll("Choose LLM provider", listOf("On-device LLM", "Off", "Ollama", "OpenAI", "OpenRouter", "Compatible server"))
        ui.onNodeWithText("Library Location").performScrollTo().performClick()
        for (destination in listOf("Secure Pocket Link", "NAS", "Phone")) {
            ui.onNodeWithText(destination).performScrollTo().performClick()
            ui.waitUntil(5000) { draft.getString("library_destination", null) == destination }
        }
        ui.onNodeWithText("App").performScrollTo().performClick()
        selectAll("Choose Color theme", listOf("dark", "light", "dark"))

        assertEquals("Compatible server", draft.getString("stt_provider", null))
        assertEquals("Both", draft.getString("docling_mode", null))
        assertEquals("Compatible server", draft.getString("llm_provider", null))
        assertEquals("Phone", draft.getString("library_destination", null))
        assertEquals("dark", draft.getString("app_theme", null))
        ui.onNodeWithText("Save settings").performScrollTo().performClick()
        ui.waitUntil(5000) { prefs.getString("llm_provider", null) == "Compatible server" }
        assertEquals("Compatible server", prefs.getString("stt_provider", null))
        assertEquals("Both", prefs.getString("docling_mode", null))
        assertEquals("Compatible server", prefs.getString("llm_provider", null))
        assertEquals("Phone", prefs.getString("library_destination", null))
        assertEquals("dark", prefs.getString("app_theme", null))
    }

    private fun selectAll(contentDescription: String, options: List<String>) {
        options.forEach { option ->
            ui.onNodeWithContentDescription(contentDescription).performScrollTo().performClick()
            ui.onAllNodes(hasText(option) and hasClickAction()).onLast().performClick()
            val key = when (contentDescription) {
                "Choose Speech engine" -> "stt_provider"
                "Choose Docling stages" -> "docling_mode"
                "Choose Color theme" -> "app_theme"
                else -> "llm_provider"
            }
            ui.waitUntil(5000) { draft.getString(key, null) == option }
        }
    }
}
