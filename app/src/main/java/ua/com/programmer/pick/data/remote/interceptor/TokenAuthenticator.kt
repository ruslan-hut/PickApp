package ua.com.programmer.pick.data.remote.interceptor

import ua.com.programmer.pick.core.util.AppLog
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import ua.com.programmer.pick.core.Constants
import ua.com.programmer.pick.data.local.preferences.AppPreferences
import ua.com.programmer.pick.data.remote.dto.AuthDto
import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OkHttp Authenticator that handles 401 responses by refreshing the auth token.
 *
 * When a 401 Unauthorized response is received:
 * 1. Get the refresh token from preferences
 * 2. Call the refresh endpoint to get new tokens
 * 3. Store the new tokens
 * 4. Retry the original request with the new auth token
 */
@Singleton
class TokenAuthenticator @Inject constructor(
    private val appPreferences: AppPreferences,
    private val gson: Gson
) : Authenticator {

    companion object {
        private const val TAG = "TokenAuthenticator"
        private const val MAX_RETRY_COUNT = 1
    }

    @Volatile
    private var isRefreshing = false

    override fun authenticate(route: Route?, response: Response): Request? {
        // Don't retry if we've already tried
        if (responseCount(response) >= MAX_RETRY_COUNT) {
            AppLog.w(TAG, "Max retry count reached, giving up")
            return null
        }

        // Don't authenticate for refresh endpoint itself (avoid infinite loop)
        if (response.request.url.encodedPath.contains("auth/refresh")) {
            AppLog.w(TAG, "Refresh endpoint failed, clearing session")
            appPreferences.clearSessionSync()
            return null
        }

        // Don't authenticate for login endpoint
        if (response.request.url.encodedPath.contains("auth/login")) {
            return null
        }

        synchronized(this) {
            if (isRefreshing) {
                // Another thread is already refreshing, wait and retry with current token
                return retryWithCurrentToken(response.request)
            }

            isRefreshing = true
        }

        try {
            val refreshToken = appPreferences.getRefreshTokenSync()
            if (refreshToken == null) {
                AppLog.w(TAG, "No refresh token available")
                appPreferences.clearSessionSync()
                return null
            }

            val newTokens = refreshTokens(refreshToken)
            if (newTokens != null) {
                AppLog.d(TAG, "Token refresh successful")
                appPreferences.setTokensSync(newTokens.token, newTokens.refreshToken)

                return response.request.newBuilder()
                    .header("Authorization", "Bearer ${newTokens.token}")
                    .build()
            } else {
                AppLog.w(TAG, "Token refresh failed")
                appPreferences.clearSessionSync()
                return null
            }
        } finally {
            synchronized(this) {
                isRefreshing = false
            }
        }
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var priorResponse = response.priorResponse
        while (priorResponse != null) {
            count++
            priorResponse = priorResponse.priorResponse
        }
        return count
    }

    private fun retryWithCurrentToken(request: Request): Request? {
        val token = appPreferences.getAuthTokenSync() ?: return null
        return request.newBuilder()
            .header("Authorization", "Bearer $token")
            .build()
    }

    private fun refreshTokens(refreshToken: String): AuthDto.LoginResponse? {
        return try {
            val client = OkHttpClient.Builder()
                .build()

            val requestBody = gson.toJson(AuthDto.RefreshRequest(refreshToken))
                .toRequestBody("application/json".toMediaType())

            val request = okhttp3.Request.Builder()
                .url("${Constants.Network.BASE_URL}auth/refresh")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                response.body?.string()?.let { body ->
                    gson.fromJson(body, AuthDto.LoginResponse::class.java)
                }
            } else {
                AppLog.w(TAG, "Refresh request failed with code: ${response.code}")
                null
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "Error refreshing token", e)
            null
        }
    }
}
