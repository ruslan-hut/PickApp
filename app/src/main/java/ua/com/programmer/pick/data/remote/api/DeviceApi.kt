package ua.com.programmer.pick.data.remote.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import ua.com.programmer.pick.data.remote.dto.ApiEnvelope
import ua.com.programmer.pick.data.remote.dto.DeviceDto

/**
 * Retrofit binding for the backend device REST transport (the `/device`
 * endpoints): the device-initiated REST surface; every response is an
 * [ApiEnvelope]. Authorization is the device access JWT (added by
 * AuthInterceptor) except for the public login/refresh endpoints. Document
 * `{id}` accepts an ERP external_id (or ObjectID hex) per the canonical-ID rule.
 */
interface DeviceApi {

    // --- Auth (public) ---

    @POST("device/login")
    suspend fun login(@Body request: DeviceDto.LoginRequest): Response<ApiEnvelope<DeviceDto.LoginResponse>>

    @POST("device/refresh")
    suspend fun refresh(@Body request: DeviceDto.RefreshRequest): Response<ApiEnvelope<DeviceDto.RefreshResponse>>

    // --- Sync ---

    /** Delta pull. `full=1` resets cursors first; the device loops while has_more. */
    @POST("device/sync")
    suspend fun sync(
        @Body request: DeviceDto.SyncRequest,
        @Query("full") full: Int? = null,
    ): Response<ApiEnvelope<DeviceDto.SyncResponse>>

    /** Role-filtered authoritative document set (DOCUMENT_LIST_REFRESH equivalent). */
    @GET("device/documents")
    suspend fun listDocuments(
        @Query("document_type") documentType: String? = null,
    ): Response<ApiEnvelope<DeviceDto.SyncResponse>>

    @GET("device/documents/{id}/products")
    suspend fun documentProducts(@Path("id") documentId: String): Response<ApiEnvelope<DeviceDto.SyncResponse>>

    // --- Stage operations ---

    @POST("device/documents/{id}/lock")
    suspend fun stageLock(
        @Path("id") documentId: String,
        @Body request: DeviceDto.StageRequest,
    ): Response<ApiEnvelope<DeviceDto.StageLockResult>>

    @POST("device/documents/{id}/unlock")
    suspend fun stageUnlock(
        @Path("id") documentId: String,
        @Body request: DeviceDto.StageRequest,
    ): Response<ApiEnvelope<DeviceDto.StageLockResult>>

    @POST("device/documents/{id}/pause")
    suspend fun stagePause(
        @Path("id") documentId: String,
        @Body request: DeviceDto.StageRequest,
    ): Response<ApiEnvelope<DeviceDto.StageLockResult>>

    @POST("device/documents/{id}/complete")
    suspend fun stageComplete(
        @Path("id") documentId: String,
        @Body request: DeviceDto.StageRequest,
    ): Response<ApiEnvelope<DeviceDto.StageCompleteResult>>

    @PATCH("device/documents/{id}")
    suspend fun updateDocument(
        @Path("id") documentId: String,
        @Body request: DeviceDto.UpdateRequest,
    ): Response<ApiEnvelope<DeviceDto.DocumentUpdateResult>>

    // --- Boxes ---

    @POST("device/documents/{id}/boxes")
    suspend fun boxAdd(
        @Path("id") documentId: String,
        @Body request: DeviceDto.BoxAddRequest,
    ): Response<ApiEnvelope<DeviceDto.BoxAddResult>>

    @DELETE("device/documents/{id}/boxes/{boxNumber}")
    suspend fun boxRemove(
        @Path("id") documentId: String,
        @Path("boxNumber") boxNumber: Int,
    ): Response<ApiEnvelope<DeviceDto.BoxRemoveResult>>

    @POST("device/boxes/pickup")
    suspend fun boxPickup(@Body request: DeviceDto.BoxConfirmRequest): Response<ApiEnvelope<DeviceDto.BoxConfirmResult>>

    @POST("device/boxes/delivery")
    suspend fun boxDelivery(@Body request: DeviceDto.BoxConfirmRequest): Response<ApiEnvelope<DeviceDto.BoxConfirmResult>>

    // --- Lookups / signed URLs ---

    @GET("device/products/lookup")
    suspend fun productLookup(@Query("barcode") barcode: String): Response<ApiEnvelope<DeviceDto.ProductLookupResult>>

    @GET("device/boxes/lookup")
    suspend fun boxLookup(@Query("barcode") barcode: String): Response<ApiEnvelope<DeviceDto.BoxLookupResult>>

    @GET("device/documents/{id}/shipment/label")
    suspend fun shipmentLabel(@Path("id") documentId: String): Response<ApiEnvelope<DeviceDto.ShipmentLabelResult>>

    @POST("device/documents/{id}/shipment/track")
    suspend fun shipmentTrack(@Path("id") documentId: String): Response<ApiEnvelope<DeviceDto.ShipmentTrackResult>>

    @POST("device/documents/{id}/lines/{lineNumber}/photo-url")
    suspend fun linePhotoUploadUrl(
        @Path("id") documentId: String,
        @Path("lineNumber") lineNumber: Int,
    ): Response<ApiEnvelope<DeviceDto.LinePhotoUploadUrlResult>>

    // --- Diagnostics ---

    @POST("device/error-report")
    suspend fun errorReport(@Body request: DeviceDto.ErrorReportRequest): Response<Unit>

    @POST("device/debug-events")
    suspend fun debugEvents(@Body request: DeviceDto.DebugEventBatchRequest): Response<ApiEnvelope<DeviceDto.DebugEventBatchResult>>
}
