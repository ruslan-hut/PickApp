package ua.com.programmer.pick.data.local.preferences

import android.content.Context
import android.content.SharedPreferences
import ua.com.programmer.pick.core.util.AppLog
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_preferences")

@Singleton
class AppPreferences @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "AppPreferences"
        private val AUTH_TOKEN = stringPreferencesKey("auth_token")
        private val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        private val CURRENT_USER_ID = stringPreferencesKey("current_user_id")
        private val SERVER_URL = stringPreferencesKey("server_url")
        private val OFFLINE_HASH = stringPreferencesKey("offline_hash")
        private val DEVICE_ID = stringPreferencesKey("device_id")
        // TODO(legacy): remove after all clients migrate to server-driven document types
        private val SELECTED_OPERATING_MODE = stringPreferencesKey("selected_operating_mode")
        private val AVAILABLE_DOCUMENT_TYPES = stringPreferencesKey("available_document_types")
        private val SELECTED_DOCUMENT_TYPE = stringPreferencesKey("selected_document_type")
        private val TENANT_ID = stringPreferencesKey("tenant_id")
        private val LAST_LOGIN = stringPreferencesKey("last_login")
        private val DEBUG_JOURNAL_ENABLED = booleanPreferencesKey("debug_journal_enabled")
        private val DEMO_MODE = booleanPreferencesKey("demo_mode")
        private val LAST_REPORTED_EXIT_TS = longPreferencesKey("last_reported_exit_ts")
        // Plaintext keys kept for migration only
        private val USER_LOGIN = stringPreferencesKey("user_login")
        private val USER_PASSWORD = stringPreferencesKey("user_password")

        // Encrypted SharedPreferences keys
        private const val ENCRYPTED_PREFS_NAME = "encrypted_credentials"
        private const val KEY_ENCRYPTED_LOGIN = "user_login"
        private const val KEY_ENCRYPTED_PASSWORD = "user_password"
    }

    private val encryptedPrefs: SharedPreferences? by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                ENCRYPTED_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to create EncryptedSharedPreferences: ${e.message}", e)
            null
        }
    }

    init {
        // Migrate plaintext credentials to encrypted storage
        migratePlaintextCredentials()
    }

    private fun migratePlaintextCredentials() {
        try {
            runBlocking {
                val prefs = context.dataStore.data.first()
                val login = prefs[USER_LOGIN]
                val password = prefs[USER_PASSWORD]
                if (login != null && password != null) {
                    // Move to encrypted storage
                    encryptedPrefs?.edit()
                        ?.putString(KEY_ENCRYPTED_LOGIN, login)
                        ?.putString(KEY_ENCRYPTED_PASSWORD, password)
                        ?.apply()
                    // Remove from plaintext DataStore
                    context.dataStore.edit { mutable ->
                        mutable.remove(USER_LOGIN)
                        mutable.remove(USER_PASSWORD)
                    }
                    AppLog.d(TAG, "Migrated plaintext credentials to encrypted storage")
                }
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to migrate credentials: ${e.message}", e)
        }
    }

    val currentUserId: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[CURRENT_USER_ID]
    }

    val serverUrl: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[SERVER_URL]
    }

    val deviceId: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[DEVICE_ID] ?: generateAndStoreDeviceId()
    }

    // TODO(legacy): remove after all clients migrate to server-driven document types
    val selectedOperatingMode: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[SELECTED_OPERATING_MODE]
    }

    // TODO(legacy): remove after all clients migrate to server-driven document types
    suspend fun setSelectedOperatingMode(mode: String) {
        context.dataStore.edit { preferences ->
            preferences[SELECTED_OPERATING_MODE] = mode
        }
    }

    val availableDocumentTypes: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[AVAILABLE_DOCUMENT_TYPES]
    }

    suspend fun setAvailableDocumentTypes(json: String) {
        context.dataStore.edit { preferences ->
            preferences[AVAILABLE_DOCUMENT_TYPES] = json
        }
    }

    val selectedDocumentType: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[SELECTED_DOCUMENT_TYPE]
    }

    suspend fun setSelectedDocumentType(code: String) {
        context.dataStore.edit { preferences ->
            preferences[SELECTED_DOCUMENT_TYPE] = code
        }
    }

    suspend fun setTenantId(tenantId: String?) {
        context.dataStore.edit { preferences ->
            if (tenantId != null) {
                preferences[TENANT_ID] = tenantId
            } else {
                preferences.remove(TENANT_ID)
            }
        }
    }

    fun getTenantIdSync(): String? = runBlocking {
        context.dataStore.data.first()[TENANT_ID]
    }

    // Synchronous getter for interceptor (use with caution)
    fun getAuthTokenSync(): String? = runBlocking {
        context.dataStore.data.first()[AUTH_TOKEN]
    }

    // Synchronous getter for authenticator (use with caution)
    fun getRefreshTokenSync(): String? = runBlocking {
        context.dataStore.data.first()[REFRESH_TOKEN]
    }

    // Synchronous getter for offline hash (use with caution)
    fun getOfflineHashSync(): String? = runBlocking {
        context.dataStore.data.first()[OFFLINE_HASH]
    }

    // Synchronous setter for authenticator (use with caution)
    fun setTokensSync(authToken: String, refreshToken: String) = runBlocking {
        context.dataStore.edit { preferences ->
            preferences[AUTH_TOKEN] = authToken
            preferences[REFRESH_TOKEN] = refreshToken
        }
    }

    // Synchronous clear for authenticator (use with caution)
    fun clearSessionSync() = runBlocking {
        context.dataStore.edit { preferences ->
            preferences.remove(AUTH_TOKEN)
            preferences.remove(REFRESH_TOKEN)
            preferences.remove(CURRENT_USER_ID)
            preferences.remove(DEMO_MODE)
        }
    }

    suspend fun setCurrentUserId(userId: String?) {
        context.dataStore.edit { preferences ->
            if (userId != null) {
                preferences[CURRENT_USER_ID] = userId
            } else {
                preferences.remove(CURRENT_USER_ID)
            }
        }
    }

    suspend fun setOfflineHash(hash: String?) {
        context.dataStore.edit { preferences ->
            if (hash != null) {
                preferences[OFFLINE_HASH] = hash
            } else {
                preferences.remove(OFFLINE_HASH)
            }
        }
    }

    suspend fun setServerUrl(url: String) {
        context.dataStore.edit { preferences ->
            preferences[SERVER_URL] = url
        }
    }

    suspend fun clearSession() {
        context.dataStore.edit { preferences ->
            preferences.remove(AUTH_TOKEN)
            preferences.remove(REFRESH_TOKEN)
            preferences.remove(CURRENT_USER_ID)
            preferences.remove(TENANT_ID)
            preferences.remove(DEMO_MODE)
        }
        encryptedPrefs?.edit()?.clear()?.apply()
    }

    /**
     * Get device ID synchronously, generating one if not exists.
     * Device ID is a unique identifier for this device installation.
     */
    fun getDeviceIdSync(): String = runBlocking {
        val prefs = context.dataStore.data.first()
        prefs[DEVICE_ID] ?: generateAndStoreDeviceId()
    }

    private suspend fun generateAndStoreDeviceId(): String {
        val newId = UUID.randomUUID().toString()
        context.dataStore.edit { preferences ->
            if (preferences[DEVICE_ID] == null) {
                preferences[DEVICE_ID] = newId
            }
        }
        return context.dataStore.data.first()[DEVICE_ID] ?: newId
    }

    val lastLogin: Flow<String?> = context.dataStore.data.map { it[LAST_LOGIN] }

    /**
     * Offline demo session marker. When true the app runs entirely against a
     * local fake server (no network) — see RoutingTransport / DemoTransport.
     * Read synchronously by the transport router at construction so a demo
     * session survives process death.
     */
    val demoMode: Flow<Boolean> = context.dataStore.data.map { it[DEMO_MODE] ?: false }

    fun getDemoModeSync(): Boolean = runBlocking {
        context.dataStore.data.first()[DEMO_MODE] ?: false
    }

    suspend fun setDemoMode(enabled: Boolean) {
        context.dataStore.edit { it[DEMO_MODE] = enabled }
    }

    val debugJournalEnabled: Flow<Boolean> = context.dataStore.data.map { it[DEBUG_JOURNAL_ENABLED] ?: false }

    suspend fun setDebugJournalEnabled(enabled: Boolean) {
        context.dataStore.edit { it[DEBUG_JOURNAL_ENABLED] = enabled }
    }

    fun getDebugJournalEnabledSync(): Boolean = runBlocking {
        context.dataStore.data.first()[DEBUG_JOURNAL_ENABLED] ?: false
    }

    /**
     * Timestamp of the newest process exit already reported as an APP_START
     * journal event, so the same exit is not re-reported on every launch.
     */
    suspend fun getLastReportedExitTimestamp(): Long =
        context.dataStore.data.first()[LAST_REPORTED_EXIT_TS] ?: 0L

    suspend fun setLastReportedExitTimestamp(timestamp: Long) {
        context.dataStore.edit { it[LAST_REPORTED_EXIT_TS] = timestamp }
    }


    suspend fun setLastLogin(login: String) {
        context.dataStore.edit { it[LAST_LOGIN] = login }
    }

    /**
     * Store user credentials for transport login (encrypted at rest).
     * These are used to re-authenticate after transport reconnects.
     */
    fun setUserCredentials(login: String, password: String) {
        encryptedPrefs?.edit()
            ?.putString(KEY_ENCRYPTED_LOGIN, login)
            ?.putString(KEY_ENCRYPTED_PASSWORD, password)
            ?.apply()
    }

    /**
     * Get stored user credentials synchronously.
     * Returns Pair(login, password) or null if not stored.
     */
    fun getUserCredentialsSync(): Pair<String, String>? {
        val login = encryptedPrefs?.getString(KEY_ENCRYPTED_LOGIN, null)
        val password = encryptedPrefs?.getString(KEY_ENCRYPTED_PASSWORD, null)
        return if (login != null && password != null) {
            Pair(login, password)
        } else {
            null
        }
    }

    /**
     * Clear stored user credentials
     */
    fun clearUserCredentials() {
        encryptedPrefs?.edit()
            ?.remove(KEY_ENCRYPTED_LOGIN)
            ?.remove(KEY_ENCRYPTED_PASSWORD)
            ?.apply()
    }
}
