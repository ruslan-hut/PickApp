package ua.com.programmer.pick.data.remote.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import ua.com.programmer.pick.data.remote.dto.AuthDto

interface AuthApi {

    @POST("auth/login")
    suspend fun login(@Body request: AuthDto.LoginRequest): Response<AuthDto.LoginResponse>

    @POST("auth/logout")
    suspend fun logout(): Response<Unit>

    @POST("auth/refresh")
    suspend fun refreshToken(@Body request: AuthDto.RefreshRequest): Response<AuthDto.LoginResponse>
}
