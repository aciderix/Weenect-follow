package fr.alerteresidents.data.remote

import fr.alerteresidents.data.model.WeenectLoginRequest
import fr.alerteresidents.data.model.WeenectLoginResponse
import fr.alerteresidents.data.model.WeenectModeRequest
import fr.alerteresidents.data.model.WeenectPositionDto
import fr.alerteresidents.data.model.WeenectTrackersResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** API Weenect v4 (cf. weenect-go). Les en-têtes communs sont ajoutés par [fr.alerteresidents.util.HttpClients.weenect]. */
interface WeenectApiService {

    @POST("user/login")
    suspend fun login(@Body request: WeenectLoginRequest): Response<WeenectLoginResponse>

    @GET("mytracker")
    suspend fun getTrackers(@Header("Authorization") authHeader: String): Response<WeenectTrackersResponse>

    @GET("mytracker/{tracker_id}/position")
    suspend fun getPositions(
        @Header("Authorization") authHeader: String,
        @Path("tracker_id") trackerId: Long,
        @Query("start") startIso: String? = null,
        @Query("end") endIso: String? = null
    ): Response<List<WeenectPositionDto>>

    @POST("mytracker/{tracker_id}/position/refresh")
    suspend fun refreshPosition(
        @Header("Authorization") authHeader: String,
        @Path("tracker_id") trackerId: Long
    ): Response<Unit>

    @POST("mytracker/{tracker_id}/st-mode")
    suspend fun activateSuperLive(
        @Header("Authorization") authHeader: String,
        @Path("tracker_id") trackerId: Long
    ): Response<Unit>

    @POST("mytracker/{tracker_id}/ring")
    suspend fun ring(
        @Header("Authorization") authHeader: String,
        @Path("tracker_id") trackerId: Long
    ): Response<Unit>

    @POST("mytracker/{tracker_id}/vibrate")
    suspend fun vibrate(
        @Header("Authorization") authHeader: String,
        @Path("tracker_id") trackerId: Long
    ): Response<Unit>

    @POST("mytracker/{tracker_id}/mode")
    suspend fun setUpdateInterval(
        @Header("Authorization") authHeader: String,
        @Path("tracker_id") trackerId: Long,
        @Body modeRequest: WeenectModeRequest
    ): Response<Unit>
}
