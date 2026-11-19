package com.cairn.securepocket.vault

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.cairn.securepocket.capture.CaptureSink
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

data class DriveFile(val name: String, val uri: Uri, val size: Long)

data class ImportSummary(val imported: Int, val skipped: Int, val failed: Int, val notes: List<String>)

/**
 * Pulls recordings off the Pocket's USB-C mass-storage volume (the microSD card,
 * exposed as RECORD/). SAF tree URI is user-picked; permission persists across reboots.
 * Original bytes are sacred: stream copy, sha computed from what actually lands on disk.
 */
class DriveImporter(private val ctx: Context) {

    private val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun savedTree(): Uri? = prefs.getString("drive_tree", null)?.let { Uri.parse(it) }

    fun takeTree(tree: Uri) {
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                tree,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        prefs.edit().putString("drive_tree", tree.toString()).apply()
    }

    fun forgetTree() = prefs.edit().remove("drive_tree").apply()

    fun isLive(): Boolean {
        val t = savedTree() ?: return false
        return runCatching {
            ctx.contentResolver.persistedUriPermissions.any { it.uri == t && it.isReadPermission }
        }.getOrDefault(false)
    }

    private fun findRecordDir(tree: Uri): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree, DocumentsContract.getDocumentId(tree),
        )
        val proj = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE)
        ctx.contentResolver.query(children, proj, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val mime = c.getString(1)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR &&
                    id.substringAfterLast('/').equals("RECORD", true)
                ) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, id)
                }
            }
        }
        return null
    }

    fun listRecordings(): List<DriveFile> {
        val tree = savedTree() ?: return emptyList()
        val record = findRecordDir(tree) ?: return emptyList()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(record))
        val proj = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val out = mutableListOf<DriveFile>()
        ctx.contentResolver.query(children, proj, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0)
                val size = c.getLong(1)
                if (name.endsWith(".mp3", true)) {
                    out.add(DriveFile(name, DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getDocumentId(record) + "/" + name), size))
                }
            }
        }
        return out.sortedBy { it.name }
    }

    /** Copies one recording into the vault, verifying size against the drive's reported length. */
    fun importOne(rec: DriveFile, vault: VaultStore, vaultLock: Any): VaultStore.Record? {
        val sink = CaptureSink("drv-" + System.nanoTime(), File(ctx.cacheDir, "captures").apply { mkdirs() })
        sink.begin()
        val buf = ByteArray(65536)
        runCatching {
            ctx.contentResolver.openInputStream(rec.uri)?.use { inp ->
                while (true) {
                    val n = inp.read(buf)
                    if (n <= 0) break
                    sink.append(buf.copyOf(n))
                }
            }
        }.onFailure { sink.markPartial(); return null }
        sink.finish()
        val (date, startAt) = dateOf(rec.name, sink.file.lastModified())
        return synchronized(vaultLock) {
            val r = vault.importCapture(sink, date, startAt, rec.size, null)
            vault.verify(r)
            sink.file.delete()
            r
        }
    }

    /** Imports everything on the card not already in the vault (sha match = skip). */
    fun importAll(vault: VaultStore, vaultLock: Any, knownSha: (VaultStore.Record) -> String): ImportSummary {
        val files = listRecordings()
        if (files.isEmpty()) return ImportSummary(0, 0, 0, listOf("no recordings found on drive"))
        val existing = vault.list().map(knownSha).toMutableSet()
        var imported = 0
        var skipped = 0
        var failed = 0
        val notes = mutableListOf<String>()
        for (f in files) {
            if (failed > 3) { notes.add("abandoned after $failed failures"); break }
            val rec = runCatching { importOne(f, vault, vaultLock) }.getOrNull()
            if (rec == null) { failed++; notes.add("read failed: ${f.name}"); continue }
            if (rec.sha256 in existing) {
                skipped++
                synchronized(vaultLock) { rec.sidecar.delete(); rec.mp3.delete() }
                notes.add("skip dup: ${f.name}")
            } else {
                imported++
                existing.add(rec.sha256)
                notes.add("imported: ${f.name} · ${rec.verification}")
            }
        }
        return ImportSummary(imported, skipped, failed, notes)
    }

    private fun dateOf(name: String, fallbackMs: Long): Pair<LocalDate, LocalDateTime> {
        var m = Regex("(\\d{4}-\\d{2}-\\d{2})[_T](\\d{6})").find(name)
        val iso = m != null
        if (m == null) m = Regex("(\\d{4})(\\d{2})(\\d{2})[_-]?(\\d{2})(\\d{2})(\\d{2})").find(name)
        if (m != null) {
            runCatching {
                val d = if (iso) LocalDate.parse(m.groupValues[1])
                    else LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
                val t = if (iso) m.groupValues[2] else m.groupValues[4] + m.groupValues[5] + m.groupValues[6]
                return d to d.atTime(t.substring(0, 2).toInt(), t.substring(2, 4).toInt(), t.substring(4, 6).toInt())
            }
        }
        val ldt = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(fallbackMs), ZoneId.systemDefault())
        return ldt.toLocalDate() to ldt
    }
}
