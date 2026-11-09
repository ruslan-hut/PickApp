package ua.com.programmer.pick.data.local.preferences

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class LegacyCredentialMigrationTest {

    @Test
    fun `copies every legacy value and deletes the old file`() {
        val legacy = FakeLegacySource(mapOf("user_login" to "ivan", "user_password" to "s3cret", "extra" to "x"))
        val store = newStore()

        val result = LegacyCredentialMigration.migrate(legacy, store.first)

        assertEquals(MigrationResult.MIGRATED, result)
        assertEquals("ivan", store.first.getString("user_login"))
        assertEquals("s3cret", store.first.getString("user_password"))
        assertEquals("x", store.first.getString("extra"))
        assertTrue(legacy.deleted)
        assertEquals(1, store.second.commits)
    }

    @Test
    fun `values are stored encrypted, not in clear`() {
        val legacy = FakeLegacySource(mapOf("user_password" to "s3cret"))
        val (store, prefs) = newStore()

        LegacyCredentialMigration.migrate(legacy, store)

        val raw = prefs.getString("user_password", null)
        assertFalse(raw == "s3cret")
        assertEquals("s3cret", store.getString("user_password"))
    }

    @Test
    fun `newer values already in the new store win`() {
        val legacy = FakeLegacySource(mapOf("user_login" to "old", "user_password" to "old-pass"))
        val (store, _) = newStore()
        store.putAll(mapOf("user_password" to "new-pass"))

        LegacyCredentialMigration.migrate(legacy, store)

        assertEquals("old", store.getString("user_login"))
        assertEquals("new-pass", store.getString("user_password"))
        assertTrue(legacy.deleted)
    }

    @Test
    fun `nothing happens without a legacy file`() {
        val legacy = FakeLegacySource(null)
        val (store, prefs) = newStore()

        assertEquals(MigrationResult.NOTHING_TO_MIGRATE, LegacyCredentialMigration.migrate(legacy, store))
        assertFalse(legacy.deleted)
        assertTrue(prefs.all.isEmpty())
    }

    @Test
    fun `an unreadable legacy file is kept for the next start`() {
        val legacy = FakeLegacySource(emptyMap(), readError = IllegalStateException("keystore key gone"))
        val (store, _) = newStore()

        assertEquals(MigrationResult.READ_FAILED, LegacyCredentialMigration.migrate(legacy, store))
        assertFalse(legacy.deleted)
    }

    @Test
    fun `the legacy file is kept when the copy does not reach disk`() {
        val legacy = FakeLegacySource(mapOf("user_password" to "s3cret"))
        val prefs = FakeSharedPreferences(commitSucceeds = false)
        val store = SecureStringStore(prefs, XorCipher)

        assertEquals(MigrationResult.WRITE_FAILED, LegacyCredentialMigration.migrate(legacy, store))
        assertFalse(legacy.deleted)
    }

    @Test
    fun `a legacy file holding only its keysets is deleted`() {
        val legacy = FakeLegacySource(emptyMap())
        val (store, _) = newStore()

        assertEquals(MigrationResult.MIGRATED, LegacyCredentialMigration.migrate(legacy, store))
        assertTrue(legacy.deleted)
    }

    @Test
    fun `a value that no longer decrypts reads as null and is dropped`() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("user_password", "not-a-ciphertext").commit()
        val store = SecureStringStore(prefs, XorCipher)

        assertNull(store.getString("user_password"))
        assertFalse(prefs.contains("user_password"))
    }

    @Test
    fun `a value moved under another key does not decrypt`() {
        val (store, prefs) = newStore()
        store.putAll(mapOf("user_login" to "ivan"))
        prefs.edit().putString("user_password", prefs.getString("user_login", null)).commit()

        assertNull(store.getString("user_password"))
    }

    // --- EncryptedSharedPreferences payload decoding ---

    @Test
    fun `decodes a string payload`() {
        assertEquals("пароль-1", LegacyEncryptedPrefsReader.decodeStringPayload(stringPayload("пароль-1")))
        assertEquals("", LegacyEncryptedPrefsReader.decodeStringPayload(stringPayload("")))
    }

    @Test
    fun `the library null marker decodes as null`() {
        assertNull(LegacyEncryptedPrefsReader.decodeStringPayload(stringPayload("__NULL__")))
    }

    @Test
    fun `non-string and malformed payloads decode as null`() {
        val boolPayload = ByteBuffer.allocate(5).putInt(5).put(1).array()
        assertNull(LegacyEncryptedPrefsReader.decodeStringPayload(boolPayload))
        val truncated = ByteBuffer.allocate(10).putInt(0).putInt(100).array()
        assertNull(LegacyEncryptedPrefsReader.decodeStringPayload(truncated))
        assertNull(LegacyEncryptedPrefsReader.decodeStringPayload(ByteArray(3)))
    }

    private fun stringPayload(value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(8 + bytes.size).putInt(0).putInt(bytes.size).put(bytes).array()
    }

    private fun newStore(): Pair<SecureStringStore, FakeSharedPreferences> {
        val prefs = FakeSharedPreferences()
        return SecureStringStore(prefs, XorCipher) to prefs
    }

    private class FakeLegacySource(
        private val values: Map<String, String>?,
        private val readError: Exception? = null
    ) : LegacyCredentialSource {
        var deleted = false
        override fun exists() = values != null && !deleted
        override fun readAll(): Map<String, String> {
            readError?.let { throw it }
            return values.orEmpty()
        }
        override fun delete() {
            deleted = true
        }
    }

    /** Reversible stand-in for the Keystore cipher: hex(xor(plaintext)) bound to the key. */
    private object XorCipher : ValueCipher {
        override fun encrypt(plaintext: String, associatedData: String): String =
            "$associatedData:" + plaintext.toByteArray().joinToString("") { "%02x".format(it.toInt() xor 0x5A and 0xFF) }

        override fun decrypt(ciphertext: String, associatedData: String): String {
            val prefix = "$associatedData:"
            require(ciphertext.startsWith(prefix)) { "wrong associated data" }
            val hex = ciphertext.removePrefix(prefix)
            require(hex.length % 2 == 0 && hex.all { it in "0123456789abcdef" }) { "bad ciphertext" }
            return String(hex.chunked(2).map { (it.toInt(16) xor 0x5A).toByte() }.toByteArray())
        }
    }
}

/** In-memory SharedPreferences; [commits] counts successful commit() calls. */
private class FakeSharedPreferences(private val commitSucceeds: Boolean = true) : SharedPreferences {
    private val data = mutableMapOf<String, Any?>()
    var commits = 0

    override fun getAll(): MutableMap<String, *> = data.toMutableMap()
    override fun getString(key: String?, defValue: String?) = data[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?) =
        @Suppress("UNCHECKED_CAST") (data[key] as? MutableSet<String> ?: defValues)
    override fun getInt(key: String?, defValue: Int) = data[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long) = data[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float) = data[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean) = data[key] as? Boolean ?: defValue
    override fun contains(key: String?) = data.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removed = mutableSetOf<String>()
        private var clear = false

        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { removed += key }
        override fun clear() = apply { clear = true }

        override fun commit(): Boolean {
            if (!commitSucceeds) return false
            write()
            commits++
            return true
        }

        override fun apply() = write()

        private fun write() {
            if (clear) data.clear()
            removed.forEach { data.remove(it) }
            data.putAll(pending)
        }
    }
}
