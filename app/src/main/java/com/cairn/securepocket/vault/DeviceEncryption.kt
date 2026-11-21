package com.cairn.securepocket.vault

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import com.cairn.securepocket.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keystore-backed AES-256-GCM for vault artifacts and app preferences. */
internal object DeviceEncryption {
    private const val LEGACY_KEY_ALIAS = "secure-pocket-data-v1"
    private const val VERSION: Byte = 2
    private val magic = byteArrayOf('S'.code.toByte(), 'P'.code.toByte(), 'A'.code.toByte(), 'E'.code.toByte())
    private const val NONCE_BYTES = 12
    /** JVM tests may inject a key; production leaves this unset and always uses AndroidKeyStore. */
    @Volatile internal var testKeyProvider: (() -> SecretKey)? = null

    @Synchronized private fun key(purpose: String, version: Byte, create: Boolean): SecretKey {
        if (BuildConfig.BUILD_TYPE == "validation") testKeyProvider?.let { return it() }
        val alias = when {
            version == 1.toByte() -> LEGACY_KEY_ALIAS
            purpose.startsWith("preferences:") -> "secure-pocket-settings-v2"
            else -> "secure-pocket-library-v2"
        }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "The encryption key for this data is unavailable." }
        fun generate(strongBox: Boolean): SecretKey {
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setIsStrongBoxBacked(strongBox)
            // Android 12–14 can delete unlocked-device-only keys after lock screen changes.
            if (Build.VERSION.SDK_INT >= 35 && alias == "secure-pocket-library-v2") spec.setUnlockedDeviceRequired(true)
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(spec.build())
            return generator.generateKey()
        }
        return try { generate(strongBox = true) }
        catch (_: StrongBoxUnavailableException) { generate(strongBox = false) }
    }

    private fun cipher(mode: Int, nonce: ByteArray? = null, purpose: String, version: Byte = VERSION): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            if (mode == Cipher.ENCRYPT_MODE) init(mode, key(purpose, version, create = true))
            else init(mode, key(purpose, version, create = false), GCMParameterSpec(128, requireNotNull(nonce)))
            updateAAD(("secure-pocket:v$version:" + purpose).toByteArray(Charsets.UTF_8))
        }

    fun encrypt(input: InputStream, output: OutputStream, purpose: String) {
        val c = cipher(Cipher.ENCRYPT_MODE, purpose = purpose)
        output.write(magic)
        output.write(VERSION.toInt())
        output.write(c.iv)
        CipherOutputStream(output, c).use { encrypted -> input.copyTo(encrypted) }
    }

    fun decrypt(input: InputStream, output: OutputStream, purpose: String) {
        val header = ByteArray(magic.size + 1 + NONCE_BYTES)
        var offset = 0
        while (offset < header.size) {
            val n = input.read(header, offset, header.size - offset)
            require(n > 0) { "Encrypted data is truncated." }
            offset += n
        }
        require(header.copyOfRange(0, magic.size).contentEquals(magic)) { "Encrypted data format is unsupported." }
        val version = header[magic.size]
        require(version == 1.toByte() || version == VERSION) { "Encrypted data version is unsupported." }
        val nonce = header.copyOfRange(magic.size + 1, header.size)
        val c = cipher(Cipher.DECRYPT_MODE, nonce, purpose, version)
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (n == 0) continue
            c.update(buffer, 0, n)?.let(output::write)
        }
        // GCM authenticity is established only here. Callers write to a private temporary file
        // and publish it only after this returns, so unauthenticated bytes are never consumed.
        c.doFinal()?.let(output::write)
    }

    fun encryptBytes(bytes: ByteArray, purpose: String): ByteArray = ByteArrayOutputStream().also {
        encrypt(ByteArrayInputStream(bytes), it, purpose)
    }.toByteArray()

    fun decryptBytes(bytes: ByteArray, purpose: String): ByteArray = ByteArrayOutputStream().also {
        decrypt(ByteArrayInputStream(bytes), it, purpose)
    }.toByteArray()

    fun encryptFile(source: File, destination: File, purpose: String) {
        destination.parentFile?.mkdirs()
        val temp = File.createTempFile(".encrypt-", ".tmp", destination.parentFile)
        try {
            FileInputStream(source).use { input -> FileOutputStream(temp).use { encrypt(input, it, purpose) } }
            FileOutputStream(temp, true).use { it.fd.sync() }
            Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
    }

    fun decryptFile(source: File, destination: File, purpose: String) {
        destination.parentFile?.mkdirs()
        val temp = File.createTempFile(".decrypt-", ".tmp", destination.parentFile)
        try {
            FileInputStream(source).use { input -> FileOutputStream(temp).use { decrypt(input, it, purpose) } }
            FileOutputStream(temp, true).use { it.fd.sync() }
            Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
    }

    fun clearPlaintextTemps(context: Context) {
        File(context.cacheDir, "secure-plaintext").deleteRecursively()
        File(context.cacheDir, "captures").deleteRecursively()
        context.cacheDir.listFiles()?.filter {
            it.name.startsWith("remote-recording-") || it.name.startsWith("pocket-upload-") || it.name.startsWith("pocket-")
        }
            ?.forEach { it.deleteRecursively() }
    }
}

internal object SecurePreferences {
    private val instances = mutableMapOf<String, SharedPreferences>()
    @Synchronized fun open(context: Context, name: String): SharedPreferences =
        instances.getOrPut("${context.applicationContext.packageName}:$name") { EncryptedPreferences(context, name) }
}

internal fun artifactPurpose(file: File) = "vault-artifact:${file.name}"

/** SharedPreferences-compatible store. The legacy XML is imported once and cleared only after a durable encrypted write. */
internal class EncryptedPreferences(context: Context, private val name: String) : SharedPreferences {
    private val app = context.applicationContext
    private val file = File(app.noBackupFilesDir, "$name.preferences.aead")
    private val lock = Any()
    private val listeners = LinkedHashSet<SharedPreferences.OnSharedPreferenceChangeListener>()
    private var values: MutableMap<String, Any> = load()

    private fun load(): MutableMap<String, Any> = synchronized(lock) {
        if (file.exists()) {
            val loaded = decode(DeviceEncryption.decryptBytes(file.readBytes(), "preferences:$name"))
            val legacy = app.getSharedPreferences(name, Context.MODE_PRIVATE)
            if (legacy.all.isNotEmpty()) check(legacy.edit().clear().commit()) { "Could not clear migrated legacy settings." }
            loaded
        }
        else {
            val legacy = app.getSharedPreferences(name, Context.MODE_PRIVATE)
            val imported = legacy.all.mapValuesNotNull()
            if (imported.isNotEmpty()) {
                persist(imported)
                check(legacy.edit().clear().commit()) { "Could not clear legacy settings after encrypted migration." }
            }
            imported.toMutableMap()
        }
    }

    private fun persist(next: Map<String, Any>) {
        file.parentFile?.mkdirs()
        val temp = File.createTempFile(".$name-", ".tmp", file.parentFile)
        try {
        temp.writeBytes(DeviceEncryption.encryptBytes(encode(next), "preferences:$name"))
        java.io.FileOutputStream(temp, true).use { it.fd.sync() }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
    }

    override fun getAll(): Map<String, *> = synchronized(lock) { values.toMap() }
    override fun getString(key: String, defValue: String?): String? = synchronized(lock) { values[key]?.let { it as? String ?: mismatch(key) } ?: defValue }
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = synchronized(lock) {
        @Suppress("UNCHECKED_CAST") val current = values[key]?.let { it as? Set<String> ?: mismatch(key) }
        (current ?: defValues)?.toMutableSet()
    }
    override fun getInt(key: String, defValue: Int): Int = synchronized(lock) { values[key]?.let { it as? Int ?: mismatch(key) } ?: defValue }
    override fun getLong(key: String, defValue: Long): Long = synchronized(lock) { values[key]?.let { it as? Long ?: mismatch(key) } ?: defValue }
    override fun getFloat(key: String, defValue: Float): Float = synchronized(lock) { values[key]?.let { it as? Float ?: mismatch(key) } ?: defValue }
    override fun getBoolean(key: String, defValue: Boolean): Boolean = synchronized(lock) { values[key]?.let { it as? Boolean ?: mismatch(key) } ?: defValue }
    override fun contains(key: String): Boolean = synchronized(lock) { key in values }
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) { synchronized(lock) { listeners.add(listener) } }
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) { synchronized(lock) { listeners.remove(listener) } }
    override fun edit(): SharedPreferences.Editor = Editor()

    private fun mismatch(key: String): Nothing = throw ClassCastException("Encrypted preference '$key' has a different type.")

    private inner class Editor : SharedPreferences.Editor {
        private val changes = LinkedHashMap<String, Any?>()
        private var clear = false
        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { changes[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = apply { changes[key] = values?.toSet() }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { changes[key] = value }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { changes[key] = value }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { changes[key] = value }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { changes[key] = value }
        override fun remove(key: String): SharedPreferences.Editor = apply { changes[key] = null }
        override fun clear(): SharedPreferences.Editor = apply { clear = true }
        override fun commit(): Boolean = save()
        override fun apply() { save() }
        private fun save(): Boolean = synchronized(lock) {
            val oldKeys = values.keys.toSet()
            val next = if (clear) mutableMapOf() else values.toMutableMap()
            changes.forEach { (key, value) -> if (value == null) next.remove(key) else next[key] = value }
            persist(next)
            values = next
            val changed = if (clear) oldKeys + changes.keys else changes.keys
            listeners.toList().forEach { listener -> changed.forEach { listener.onSharedPreferenceChanged(this@EncryptedPreferences, it) } }
            true
        }
    }

    private fun encode(map: Map<String, Any>): ByteArray {
        val root = JSONObject()
        map.forEach { (key, value) ->
            val item = JSONObject()
            when (value) {
                is String -> item.put("t", "s").put("v", value)
                is Boolean -> item.put("t", "b").put("v", value)
                is Int -> item.put("t", "i").put("v", value)
                is Long -> item.put("t", "l").put("v", value)
                is Float -> item.put("t", "f").put("v", value.toDouble())
                is Set<*> -> item.put("t", "ss").put("v", JSONArray(value.toList()))
                else -> error("Unsupported preference type for $key")
            }
            root.put(key, item)
        }
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    private fun decode(bytes: ByteArray): MutableMap<String, Any> {
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        return buildMap {
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next(); val item = root.getJSONObject(key)
                put(key, when (item.getString("t")) {
                    "s" -> item.getString("v")
                    "b" -> item.getBoolean("v")
                    "i" -> item.getInt("v")
                    "l" -> item.getLong("v")
                    "f" -> item.getDouble("v").toFloat()
                    "ss" -> item.getJSONArray("v").let { arr -> (0 until arr.length()).map { arr.getString(it) }.toSet() }
                    else -> error("Encrypted settings format is unsupported.")
                })
            }
        }.toMutableMap()
    }

    private fun Map<String, *>.mapValuesNotNull(): Map<String, Any> = mapNotNull { (key, value) ->
        val safe = when (value) {
            is String, is Boolean, is Int, is Long, is Float -> value
            is Set<*> -> value.filterIsInstance<String>().toSet()
            else -> null
        }
        safe?.let { key to it }
    }.toMap()
}
