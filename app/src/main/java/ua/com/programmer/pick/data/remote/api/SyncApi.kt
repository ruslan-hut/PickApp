package ua.com.programmer.pick.data.remote.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import ua.com.programmer.pick.data.remote.dto.SyncAckRequest
import ua.com.programmer.pick.data.remote.dto.SyncResponseDto

interface SyncApi {

    @GET("sync/delta")
    suspend fun getDeltaSync(
        @Query("entity") entityType: String,
        @Query("since") lastSyncTime: Long
    ): Response<SyncResponseDto>

    @GET("sync/full")
    suspend fun getFullSync(
        @Query("entity") entityType: String
    ): Response<SyncResponseDto>

    @POST("sync/ack")
    suspend fun acknowledgSync(
        @Body request: SyncAckRequest
    ): Response<Unit>
}
