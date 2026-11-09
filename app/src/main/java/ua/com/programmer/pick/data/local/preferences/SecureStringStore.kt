package ua.com.programmer.pick.data.local.preferences

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import ua.com.programmer.pick.core.util.AppLog
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts one value of [SecureStringStore]. [associatedData] is the
 * preference key, so a ciphertext copied under another key fails to decrypt.
 * The output is a printable string, ready for SharedPreferences.
 */
interface ValueCipher {
    fun encrypt(plaintext: String, associatedData: String): String
    fun decrypt(ciphertext: String, associatedData: String): String
}

/**
 * AES-256-GCM with a non-exportable key in the Android Keystore.
 * Output: Base64(IV (12 bytes) || ciphertext+tag).
 */
class KeystoreAesGcmCipher(private val keyAlias: String) : ValueCipher {

    private val key: SecretKey = loadOrCreateKey()

    override fun encrypt(plaintext: String, associatedData: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(associatedData.toByteArray(Charsets.UTF_8))
        val sealed = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        check(iv.size == IV_SIZE) { "Unexpected GCM IV size ${iv.size}" }
        return Base64.encodeToString(iv + sealed, Base64.NO_WRAP)
    }

    override fun decrypt(ciphertext: String, associatedData: String): String {
        val bytes = Base64.decode(ciphertext, Base64.NO_WRAP)
        require(bytes.size > IV_SIZE) { "Ciphertext too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 0, IV_SIZE))
        cipher.updateAAD(associatedData.toByteArray(Charsets.UTF_8))
        val plain = cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE)
        return String(plain, Charsets.UTF_8)
    }

    private fun loadOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
    }
}

/**
 * String values encrypted one by one in a plain SharedPreferences file.
 * Keys are stored in clear (they are fixed names, not secrets).
 */
class SecureStringStore(
    private val prefs: SharedPreferences,
    private val cipher: ValueCipher
) {

    /** The decrypted value, or null when absent or unreadable (an unreadable entry is dropped). */
    fun getString(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        return try {
            cipher.decrypt(stored, key)
        } catch (e: Exception) {
            AppLog.e(TAG, "Cannot decrypt '$key', dropping it: ${e.message}", e)
            prefs.edit().remove(key).apply()
            null
        }
    }

    fun contains(key: String): Boolean = prefs.contains(key)

    /**
     * Writes all [values] in one edit. With [overwrite] false, keys already
     * present are kept. Returns false when the write did not reach disk.
     */
    fun putAll(values: Map<String, String>, overwrite: Boolean = true, sync: Boolean = false): Boolean {
        val editor = prefs.edit()
        for ((key, value) in values) {
            if (!overwrite && prefs.contains(key)) continue
            editor.putString(key, cipher.encrypt(value, key))
        }
        return if (sync) editor.commit() else {
            editor.apply()
            true
        }
    }

    fun remove(vararg keys: String) {
        val editor = prefs.edit()
        keys.forEach { editor.remove(it) }
        editor.apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val TAG = "SecureStringStore"
    }
}
