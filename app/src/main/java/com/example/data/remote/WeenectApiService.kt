package com.example.data.remote

import com.example.data.model.WeenectLoginRequest
import com.example.data.model.WeenectLoginResponse
import com.example.data.model.WeenectModeRequest
import com.example.data.model.WeenectPositionDto
import com.example.data.model.WeenectTrackersResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface WeenectApiService {

    @Headers(
        "User-Agent: weenect-go/1.0.0",
        "Accept: application/json, text/plain, */*",
        "Origin: https://my.weenect.com",
        "x-app-version: 0.1.0",
        "x-app-type: userspace"
    )
    @POST("user/login")
    suspend fun login(
        @Body request: WeenectLoginRequest
    ): Response<WeenectLoginResponse>

    @Headers(
        "User-Agent: weenect-go/1.0.0",
        "Accept: application/json, text/plain, */*",
        "Origin: https://my.weenect.com",
        "x-app-version: 0.1.0",
        "x-app-type: userspace"
    )
    @GET("mytracker")
    suspend fun getTrackers(
        @Header("Authorization") authHeader: String
    ): Response<WeenectTrackersResponse>

    @Headers(
        "User-Agent: weenect-go/1.0.0",
        "Accept: application/json, text/plain, */*",
        "Origin: https://my.weenect.com",
        "x-app-version: 0.1.0",
        "x-app-type: userspace"
    )
    @GET("mytracker/{tracker_id}/position")
    suspend fun getPositions(
        @Header("Authorization") authHeader: String,
        @Path("tracker_id") trackerId: Long,
        @Query("start") startIso: String? = null,
        @Query("end") endIso: String? = null
    ): Response<List<WeenectPositionDto>>

    @Headers(
        "User-Agent: weenect-go/1.0.0",
        "Accept: application/json, text/plain, */*",
        "Origin: https://my.weenect.com",
        "x-app-version: 0.1.0",
        "x-app-type: userspace"
    )
    @POST("mytracker/{tracker_id}/position/refresh")
    suspend fun refreshPosition(
        @Header("Authorization") authHeader: String,
        @Path("tracker_id") trackerId: Long
    ): Response<Unit>

    @Headers(
        "User-Agent: weenect-go/1.0.0",
        "Accept: application/json, text/plain, */*",
        "Origin: https://my.weenect.com",
        "x-app-version: 0.1.0",
        "x-app-type: userspace"
    )
    @POST("mytracker/{tracker_id}/st-mode")
    suspend fun activateSuperLive(
        @Header("Authorization") authHeader: String,
        @Path("tracker_id") trackerId: Long
    ): Response<Unit>

    @Headers(
        "User-Agent: weenect-go/1.0.0",
        "Accept: application/json, text/plain, */*",
        "Origin: https://my.weenect.com",
        "x-app-version: 0.1.0",
        "x-app-type: userspace"
    )
    @POST("mytracker/{tracker_id}/ring")
    suspend fun ring(
        @Header("Authorization") authHeader: String,
        @Path("tracker_id") trackerId: Long
    ): Response<Unit>

    @Headers(
        "User-Agent: weenect-go/1.0.0",
        "Accept: application/json, text/plain, */*",
        "Origin: https://my.weenect.com",
        "x-app-version: 0.1.0",
        "x-app-type: userspace"
    )
    @POST("mytracker/{tracker_id}/vibrate")
    suspend fun vibrate(
        @Header("Authorization") authHeader: String,
        @Path("tracker_id") trackerId: Long
    ): Response<Unit>

    @Headers(
        "User-Agent: weenect-go/1.0.0",
        "Accept: application/json, text/plain, */*",
        "Origin: https://my.weenect.com",
        "x-app-version: 0.1.0",
        "x-app-type: userspace"
    )
    @POST("mytracker/{tracker_id}/mode")
    suspend fun setUpdateInterval(
        @Header("Authorization") authHeader: String,
        @Path("tracker_id") trackerId: Long,
        @Body modeRequest: WeenectModeRequest
    ): Response<Unit>
}
