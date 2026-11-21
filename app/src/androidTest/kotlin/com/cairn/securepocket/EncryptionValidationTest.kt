package com.cairn.securepocket

import androidx.test.platform.app.InstrumentationRegistry
import com.cairn.securepocket.capture.CaptureSink
import com.cairn.securepocket.vault.DeviceEncryption
import com.cairn.securepocket.vault.EncryptedPreferences
import com.cairn.securepocket.vault.VaultStore
import java.io.File
import java.time.LocalDateTime
import javax.crypto.AEADBadTagException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptionValidationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext.also {
        check(it.packageName.endsWith(".isotest")) { "This test requires the isolated package." }
    }

    @Test fun keystoreRoundTripRejectsChangedCiphertextAndWrongPurpose() {
        check(context.packageName.endsWith(".isotest"))
        val payload = "Synthetic recording for encryption validation".toByteArray()
        val purpose = "vault-artifact:verification.mp3"
        val encrypted = DeviceEncryption.encryptBytes(payload, purpose)
        assertArrayEquals(payload, DeviceEncryption.decryptBytes(encrypted, purpose))
        assertNotEquals(encrypted.toList(), DeviceEncryption.encryptBytes(payload, purpose).toList())
        assertTrue(runCatching { DeviceEncryption.decryptBytes(encrypted, "vault-artifact:other.mp3") }
            .exceptionOrNull() is AEADBadTagException)
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        assertTrue(runCatching { DeviceEncryption.decryptBytes(encrypted, purpose) }
            .exceptionOrNull() is AEADBadTagException)
    }

    @Test fun legacyPreferencesAreMigratedAndCleared() {
        val name = "synthetic-migration-${System.nanoTime()}"
        val marker = "synthetic-settings-marker"
        val legacy = context.getSharedPreferences(name, 0)
        val disk = File(context.noBackupFilesDir, "$name.preferences.aead")
        try {
            assertTrue(legacy.edit().putString("secret", marker).commit())
            assertEquals(marker, EncryptedPreferences(context, name).getString("secret", null))
            assertTrue(legacy.all.isEmpty())
            assertFalse(disk.readBytes().toList().windowed(marker.length).any { it == marker.toByteArray().toList() })
            assertEquals(marker, EncryptedPreferences(context, name).getString("secret", null))
        } finally {
            context.deleteSharedPreferences(name)
            disk.delete()
        }
    }

    @Test fun voiceCapturePersistsAsCiphertextAndProvidesVerifiedPlaybackBytes() {
        val fixture = context.cacheDir.resolve("synthetic-vault-${System.nanoTime()}").apply { mkdirs() }
        try {
            val capture = CaptureSink("security-test", fixture)
            val original = "Synthetic audio".toByteArray()
            capture.begin()
            capture.append(original)
            capture.finish()
            val vault = VaultStore(fixture.resolve("vault"), fixture.resolve("cache"))
            val now = LocalDateTime.of(2026, 11, 21, 12, 0)
            val record = vault.importCapture(capture, now.toLocalDate(), now, original.size.toLong(), null)
            assertEquals(VaultStore.Verification.MATCHED, vault.verify(record))
            assertFalse(record.mp3.readBytes().contentEquals(original))
            val playback = vault.materialize(record.mp3)
            try { assertArrayEquals(original, playback.readBytes()) } finally { playback.delete() }
        } finally {
            fixture.deleteRecursively()
        }
    }
}
