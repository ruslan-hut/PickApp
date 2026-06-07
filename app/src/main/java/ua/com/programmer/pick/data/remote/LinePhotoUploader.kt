package ua.com.programmer.pick.data.remote

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ua.com.programmer.pick.core.di.IoDispatcher
import ua.com.programmer.pick.core.util.AppLog
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Uploads a line photo to a server-issued signed URL. Uses a bare OkHttp client
 * with NO auth interceptor — the token embedded in the URL query is the only
 * auth, and the URL is absolute, so it is never prefixed with BASE_URL.
 */
@Singleton
class LinePhotoUploader @Inject constructor(
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    sealed class Result {
        data object Success : Result()
        /** 401 — token expired/invalid; request a fresh URL and retry. */
        data object Unauthorized : Result()
        /** 413 — payload over the cap; re-compress smaller. */
        data object TooLarge : Result()
        data class Failure(val code: Int, val message: String?) : Result()
    }

    suspend fun upload(uploadUrl: String, jpeg: ByteArray): Result = withContext(ioDispatcher) {
        try {
            val request = Request.Builder()
                .url(uploadUrl)
                .post(jpeg.toRequestBody(MEDIA_TYPE))
                .build()
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> Result.Success
                    response.code == 401 -> Result.Unauthorized
                    response.code == 413 -> Result.TooLarge
                    else -> Result.Failure(response.code, response.message)
                }
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "Photo upload failed: ${e.message}")
            Result.Failure(-1, e.message)
        }
    }

    private companion object {
        const val TAG = "LinePhotoUploader"
        val MEDIA_TYPE = "image/jpeg".toMediaType()
    }
}
