package ua.com.programmer.pick.data.local.preferences

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.DeterministicAead
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig2026
import com.google.crypto.tink.daead.DeterministicAeadConfig2026
import com.google.crypto.tink.integration.android.AndroidKeystore
import com.google.crypto.tink.subtle.Hex
import ua.com.programmer.pick.core.util.AppLog
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The credentials file written by an older app version, read once and then removed. */
interface LegacyCredentialSource {
    /** True when the old file still holds anything. */
    fun exists(): Boolean

    /** Every string value of the old file by its plain key. Throws when the file can't be decrypted. */
    fun readAll(): Map<String, String>

    /** Removes the old file (and whatever key material only it used). */
    fun delete()
}

enum class MigrationResult { NOTHING_TO_MIGRATE, MIGRATED, READ_FAILED, WRITE_FAILED }

object LegacyCredentialMigration {

    private const val TAG = "CredentialMigration"

    /**
     * Copies the old encrypted credentials into [target], then deletes the old
     * file. Values already in [target] win (they are newer). Nothing is deleted
     * unless the copy reached disk; a file that can't be read is left for the
     * next start rather than destroyed.
     */
    fun migrate(legacy: LegacyCredentialSource, target: SecureStringStore): MigrationResult {
        if (!legacy.exists()) return MigrationResult.NOTHING_TO_MIGRATE

        val values = try {
            legacy.readAll()
        } catch (e: Exception) {
            AppLog.e(TAG, "Cannot read legacy encrypted credentials: ${e.message}", e)
            return MigrationResult.READ_FAILED
        }

        val written = try {
            target.putAll(values, overwrite = false, sync = true)
        } catch (e: Exception) {
            AppLog.e(TAG, "Cannot write migrated credentials: ${e.message}", e)
            false
        }
        if (!written) return MigrationResult.WRITE_FAILED

        try {
            legacy.delete()
        } catch (e: Exception) {
            // The values are safe in the new store; the next start retries the delete.
            AppLog.e(TAG, "Cannot delete legacy credentials file: ${e.message}", e)
        }
        AppLog.d(TAG, "Migrated ${values.size} legacy encrypted value(s)")
        return MigrationResult.MIGRATED
    }
}

/**
 * Reads the file `androidx.security:security-crypto`'s EncryptedSharedPreferences
 * wrote, through Tink directly (the androidx wrapper is deprecated).
 *
 * Format of that file: two Tink keysets stored hex-encoded under fixed names,
 * each encrypted by the Keystore key `_androidx_security_master_key_`; every
 * other entry is Base64(AES-SIV(key name, ad = file name)) →
 * Base64(AES-GCM(typed payload, ad = the encrypted key name)).
 */
class LegacyEncryptedPrefsReader(
    private val context: Context,
    private val fileName: String
) : LegacyCredentialSource {

    private val prefs get() = context.getSharedPreferences(fileName, Context.MODE_PRIVATE)

    override fun exists(): Boolean = prefs.all.isNotEmpty()

    override fun readAll(): Map<String, String> {
        val entries = prefs.all
        val keyKeyset = entries[KEY_KEYSET_ALIAS] as? String
            ?: throw IllegalStateException("Legacy file has no key keyset")
        val valueKeyset = entries[VALUE_KEYSET_ALIAS] as? String
            ?: throw IllegalStateException("Legacy file has no value keyset")

        val masterKey = AndroidKeystore.getAead(MASTER_KEY_ALIAS)
        val daeadConfig = DeterministicAeadConfig2026.get() // AES-SIV key names
        val aeadConfig = AeadConfig2026.get() // AES-GCM values
        val daead = TinkProtoKeysetFormat
            .parseEncryptedKeyset(Hex.decode(keyKeyset), masterKey, ByteArray(0), daeadConfig)
            .getPrimitive(daeadConfig, DeterministicAead::class.java)
        val aead = TinkProtoKeysetFormat
            .parseEncryptedKeyset(Hex.decode(valueKeyset), masterKey, ByteArray(0), aeadConfig)
            .getPrimitive(aeadConfig, Aead::class.java)

        val result = mutableMapOf<String, String>()
        for ((encryptedKey, encryptedValue) in entries) {
            if (encryptedKey == KEY_KEYSET_ALIAS || encryptedKey == VALUE_KEYSET_ALIAS) continue
            if (encryptedValue !is String) continue
            val key = String(
                daead.decryptDeterministically(
                    Base64.decode(encryptedKey, Base64.DEFAULT),
                    fileName.toByteArray(Charsets.UTF_8)
                ),
                Charsets.UTF_8
            )
            val payload = aead.decrypt(
                Base64.decode(encryptedValue, Base64.DEFAULT),
                encryptedKey.toByteArray(Charsets.UTF_8)
            )
            val value = decodeStringPayload(payload)
            if (value == null) {
                AppLog.w(TAG, "Skipping non-string legacy value '$key'")
                continue
            }
            result[key] = value
        }
        return result
    }

    override fun delete() {
        context.deleteSharedPreferences(fileName)
        try {
            // Only EncryptedSharedPreferences used this key in the app.
            if (AndroidKeystore.hasKey(MASTER_KEY_ALIAS)) AndroidKeystore.deleteKey(MASTER_KEY_ALIAS)
        } catch (e: Exception) {
            AppLog.w(TAG, "Cannot delete legacy master key: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "LegacyEncryptedPrefs"
        const val KEY_KEYSET_ALIAS = "__androidx_security_crypto_encrypted_prefs_key_keyset__"
        const val VALUE_KEYSET_ALIAS = "__androidx_security_crypto_encrypted_prefs_value_keyset__"
        private const val MASTER_KEY_ALIAS = "_androidx_security_master_key_"
        private const val TYPE_STRING = 0
        private const val NULL_VALUE = "__NULL__"

        /**
         * Decodes EncryptedSharedPreferences' value payload: int type, then for
         * a string an int byte length and the UTF-8 bytes (big-endian). Returns
         * null for any other type and for the library's null marker.
         */
        fun decodeStringPayload(payload: ByteArray): String? {
            val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
            if (buffer.remaining() < 8) return null
            if (buffer.int != TYPE_STRING) return null
            val length = buffer.int
            if (length < 0 || length > buffer.remaining()) return null
            val bytes = ByteArray(length)
            buffer.get(bytes)
            val value = String(bytes, Charsets.UTF_8)
            return if (value == NULL_VALUE) null else value
        }
    }
}
