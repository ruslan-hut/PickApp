package ua.com.programmer.pick.data.remote.api

import com.google.gson.Gson
import retrofit2.Response
import ua.com.programmer.pick.BuildConfig
import ua.com.programmer.pick.core.Constants
import ua.com.programmer.pick.core.util.AppLog
import ua.com.programmer.pick.data.local.preferences.AppPreferences
import ua.com.programmer.pick.data.remote.dto.ApiEnvelope
import ua.com.programmer.pick.data.remote.dto.ApiError
import ua.com.programmer.pick.data.remote.dto.DeviceDto
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thrown for a non-2xx response or an error envelope. [code] carries the
 * backend error code so callers can branch on the document write-path codes
 * (LOCK_LOST / WRONG_STATE) that drive lock-loss recovery.
 */
class DeviceApiException(val code: String?, message: String) : Exception(message)

/**
 * Coroutine wrapper over [DeviceApi]: unwraps the [ApiEnvelope] to a [Result],
 * stores the JWT pair on login/refresh, and transparently refreshes once on a
 * 401 before retrying. This is the REST-operations core the RestTransport
 * delegates to; it holds no per-connection state and is independent of the
 * WebSocket layer.
 */
@Singleton
class DeviceRestClient @Inject constructor(
    private val deviceApi: DeviceApi,
    private val appPreferences: AppPreferences,
    private val gson: Gson,
) {
    private companion object {
        const val TAG = "DeviceRestClient"
        const val HTTP_UNAUTHORIZED = 401
    }

    // --- Auth ---

    suspend fun login(login: String, password: String): Result<DeviceDto.LoginResponse> {
        val request = DeviceDto.LoginRequest(
            appToken = Constants.Network.APP_TOKEN,
            deviceId = appPreferences.getDeviceIdSync(),
            login = login,
            password = password,
            appVersion = BuildConfig.VERSION_NAME,
        )
        // No refresh-retry: there is no session yet.
        val result = envelopeCall(allowRefresh = false) { deviceApi.login(request) }
        result.getOrNull()?.let { appPreferences.setTokensSync(it.accessToken, it.refreshToken) }
        return result
    }

    /** Exchanges the stored refresh token for a fresh access/refresh pair. */
    suspend fun refresh(): Result<DeviceDto.RefreshResponse> {
        val token = appPreferences.getRefreshTokenSync()
            ?: return Result.failure(DeviceApiException(null, "no refresh token"))
        val result = envelopeCall(allowRefresh = false) { deviceApi.refresh(DeviceDto.RefreshRequest(token)) }
        result.getOrNull()?.let { appPreferences.setTokensSync(it.accessToken, it.refreshToken) }
        return result
    }

    // --- Sync ---

    suspend fun sync(
        entityTypes: List<String>,
        appliedCursors: Map<String, String>? = null,
        full: Boolean = false,
    ): Result<DeviceDto.SyncResponse> = envelopeCall {
        deviceApi.sync(DeviceDto.SyncRequest(entityTypes, appliedCursors), if (full) 1 else null)
    }

    suspend fun listDocuments(documentType: String? = null): Result<DeviceDto.SyncResponse> =
        envelopeCall { deviceApi.listDocuments(documentType) }

    suspend fun documentProducts(documentId: String): Result<DeviceDto.SyncResponse> =
        envelopeCall { deviceApi.documentProducts(documentId) }

    // --- Stage operations ---

    suspend fun stageLock(documentId: String, stage: String): Result<DeviceDto.StageLockResult> =
        envelopeCall { deviceApi.stageLock(documentId, DeviceDto.StageRequest(stage)) }

    suspend fun stageUnlock(documentId: String, stage: String): Result<DeviceDto.StageLockResult> =
        envelopeCall { deviceApi.stageUnlock(documentId, DeviceDto.StageRequest(stage)) }

    suspend fun stagePause(documentId: String, stage: String): Result<DeviceDto.StageLockResult> =
        envelopeCall { deviceApi.stagePause(documentId, DeviceDto.StageRequest(stage)) }

    suspend fun stageComplete(documentId: String, stage: String): Result<DeviceDto.StageCompleteResult> =
        envelopeCall { deviceApi.stageComplete(documentId, DeviceDto.StageRequest(stage)) }

    suspend fun updateDocument(
        documentId: String,
        lines: List<DeviceDto.DocumentLineUpdate>,
    ): Result<DeviceDto.DocumentUpdateResult> =
        envelopeCall { deviceApi.updateDocument(documentId, DeviceDto.UpdateRequest(lines)) }

    // --- Boxes ---

    suspend fun boxAdd(documentId: String, barcode: String, weight: Int): Result<DeviceDto.BoxAddResult> =
        envelopeCall { deviceApi.boxAdd(documentId, DeviceDto.BoxAddRequest(barcode, weight)) }

    suspend fun boxRemove(documentId: String, boxNumber: Int): Result<DeviceDto.BoxRemoveResult> =
        envelopeCall { deviceApi.boxRemove(documentId, boxNumber) }

    suspend fun boxPickup(barcode: String, offlineSeq: Long, clientTs: Long): Result<DeviceDto.BoxConfirmResult> =
        envelopeCall { deviceApi.boxPickup(DeviceDto.BoxConfirmRequest(barcode, offlineSeq, clientTs)) }

    suspend fun boxDelivery(barcode: String, offlineSeq: Long, clientTs: Long): Result<DeviceDto.BoxConfirmResult> =
        envelopeCall { deviceApi.boxDelivery(DeviceDto.BoxConfirmRequest(barcode, offlineSeq, clientTs)) }

    // --- Lookups / signed URLs ---

    suspend fun productLookup(barcode: String): Result<DeviceDto.ProductLookupResult> =
        envelopeCall { deviceApi.productLookup(barcode) }

    suspend fun boxLookup(barcode: String): Result<DeviceDto.BoxLookupResult> =
        envelopeCall { deviceApi.boxLookup(barcode) }

    suspend fun shipmentLabel(documentId: String): Result<DeviceDto.ShipmentLabelResult> =
        envelopeCall { deviceApi.shipmentLabel(documentId) }

    suspend fun shipmentTrack(documentId: String): Result<DeviceDto.ShipmentTrackResult> =
        envelopeCall { deviceApi.shipmentTrack(documentId) }

    suspend fun linePhotoUploadUrl(documentId: String, lineNumber: Int): Result<DeviceDto.LinePhotoUploadUrlResult> =
        envelopeCall { deviceApi.linePhotoUploadUrl(documentId, lineNumber) }

    // --- Diagnostics ---

    /** Fire-and-forget; the backend replies 204. */
    suspend fun errorReport(request: DeviceDto.ErrorReportRequest): Result<Unit> = try {
        val response = deviceApi.errorReport(request)
        if (response.isSuccessful) Result.success(Unit)
        else Result.failure(DeviceApiException(null, "HTTP ${response.code()}"))
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun debugEvents(request: DeviceDto.DebugEventBatchRequest): Result<DeviceDto.DebugEventBatchResult> =
        envelopeCall { deviceApi.debugEvents(request) }

    // --- Internal ---

    /**
     * Executes [block], refreshing the token once on a 401 (when [allowRefresh])
     * and retrying, then unwraps the envelope: `status=ok` with non-null data →
     * success; an HTTP error or error envelope → failure carrying the code.
     */
    private suspend fun <T> envelopeCall(
        allowRefresh: Boolean = true,
        block: suspend () -> Response<ApiEnvelope<T>>,
    ): Result<T> = try {
        var response = block()
        if (response.code() == HTTP_UNAUTHORIZED && allowRefresh && refresh().isSuccess) {
            response = block()
        }

        if (!response.isSuccessful) {
            val err = parseError(response)
            Result.failure(DeviceApiException(err?.code, err?.message ?: "HTTP ${response.code()}"))
        } else {
            val body = response.body()
            when {
                body == null -> Result.failure(DeviceApiException(null, "empty response body"))
                !body.isOk || body.data == null ->
                    Result.failure(DeviceApiException(body.error?.code, body.error?.message ?: "request failed"))
                else -> Result.success(body.data)
            }
        }
    } catch (e: Exception) {
        AppLog.e(TAG, "request failed: ${e.message}", e)
        Result.failure(e)
    }

    /** Parses the `{status:error, error:{code,message}}` body of a non-2xx response. */
    private fun parseError(response: Response<*>): ApiError? = try {
        response.errorBody()?.string()?.let { gson.fromJson(it, ApiEnvelope::class.java).error }
    } catch (e: Exception) {
        null
    }
}
