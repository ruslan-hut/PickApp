package ua.com.programmer.pick.data.remote.interceptor

import okhttp3.Interceptor
import okhttp3.Response
import ua.com.programmer.pick.data.local.preferences.AppPreferences
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthInterceptor @Inject constructor(
    private val appPreferences: AppPreferences
) : Interceptor {

    private companion object {
        // Optional server features this build understands, sent on every
        // request. "parking": the server may send PARKED documents — an older
        // build would map that unknown state to LOADED, so it never gets them.
        const val FEATURES_HEADER = "X-Device-Features"
        const val FEATURES = "parking"
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request().newBuilder()
            .header(FEATURES_HEADER, FEATURES)
            .build()

        // Skip auth header for login endpoint
        if (originalRequest.url.encodedPath.contains("auth/login")) {
            return chain.proceed(originalRequest)
        }

        val token = appPreferences.getAuthTokenSync()

        val newRequest = if (token != null) {
            originalRequest.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            originalRequest
        }

        return chain.proceed(newRequest)
    }
}
