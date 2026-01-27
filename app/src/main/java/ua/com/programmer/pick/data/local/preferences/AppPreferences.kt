package ua.com.programmer.pick.data.local.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_preferences")

@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private val AUTH_TOKEN = stringPreferencesKey("auth_token")
        private val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        private val CURRENT_USER_ID = stringPreferencesKey("current_user_id")
        private val SERVER_URL = stringPreferencesKey("server_url")
        private val OFFLINE_HASH = stringPreferencesKey("offline_hash")
        private val EXPIRES_AT = longPreferencesKey("expires_at")
    }

    val authToken: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[AUTH_TOKEN]
    }

    val refreshToken: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[REFRESH_TOKEN]
    }

    val currentUserId: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[CURRENT_USER_ID]
    }

    val serverUrl: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[SERVER_URL]
    }

    val offlineHash: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[OFFLINE_HASH]
    }

    val expiresAt: Flow<Long?> = context.dataStore.data.map { preferences ->
        preferences[EXPIRES_AT]
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

    // Synchronous getter for token expiry (use with caution)
    fun getExpiresAtSync(): Long? = runBlocking {
        context.dataStore.data.first()[EXPIRES_AT]
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
        }
    }

    suspend fun setAuthToken(token: String?) {
        context.dataStore.edit { preferences ->
            if (token != null) {
                preferences[AUTH_TOKEN] = token
            } else {
                preferences.remove(AUTH_TOKEN)
            }
        }
    }

    suspend fun setRefreshToken(token: String?) {
        context.dataStore.edit { preferences ->
            if (token != null) {
                preferences[REFRESH_TOKEN] = token
            } else {
                preferences.remove(REFRESH_TOKEN)
            }
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

    suspend fun setExpiresAt(expiresAt: Long?) {
        context.dataStore.edit { preferences ->
            if (expiresAt != null) {
                preferences[EXPIRES_AT] = expiresAt
            } else {
                preferences.remove(EXPIRES_AT)
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
        }
    }
}
