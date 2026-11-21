package com.cairn.securepocket

import com.cairn.securepocket.protocol.*
import com.cairn.securepocket.vault.*
import java.io.File
import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PocketDownloadTest {
    @JvmField @Rule val tmp = TemporaryFolder()

    @Before fun setUpKey() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        DeviceEncryption.testKeyProvider = { key }
    }

    @After fun clearKey() {
        DeviceEncryption.testKeyProvider = null
    }

    private fun callRecording(): DeviceFile {
        val parser = ReplyParser()
        parser.feed("MCU&F&2026-11-19&PH261119155558&10".toByteArray())
        val response = parser.feed("MCU&LIST&001".toByteArray()).single() as Response.FileList
        return response.files.single()
    }

    @Test fun repeatCallImportPreservesAudioAndEditedSummary() {
        val item = callRecording()
        assertEquals("PH261119155558", item.wireId)
        assertEquals("2026-11-19T15:55:58", item.startAt.toString())
        val vault = VaultStore(tmp.newFolder())
        val bytes = "MCU&STO embedded in MP3 data".toByteArray()
        fun pull(): VaultStore.Record {
            val download = PocketDownload(item, tmp.root)
            download.begin(bytes.size.toLong())
            download.append(bytes.copyOfRange(0, 3))
            download.append(bytes.copyOfRange(3, bytes.size))
            return download.commit(vault)
        }
        val first = pull()
        first.summary.saveMarkdown("# User edit")
        assertEquals(first.mp3, pull().mp3)
        val saved = vault.list().single()
        val materialized = vault.materialize(saved.mp3)
        try { assertArrayEquals(bytes, materialized.readBytes()) } finally { materialized.delete() }
        assertEquals("# User edit", vault.readText(saved.summary))
        assertEquals(VaultStore.Verification.MATCHED, saved.verification)
    }

    @Test fun interruptedTransferDoesNotPublishOrLeaveAudio() {
        val cache = tmp.newFolder()
        val vault = VaultStore(tmp.newFolder())
        val download = PocketDownload(callRecording(), cache)
        download.begin(40)
        download.append(ByteArray(8))
        assertThrows(IllegalStateException::class.java) { download.commit(vault) }
        download.discard()
        assertTrue(vault.list().isEmpty())
        assertTrue(cache.listFiles()!!.isEmpty())
    }
}
