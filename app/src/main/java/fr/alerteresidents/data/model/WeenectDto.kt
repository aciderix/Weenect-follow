package fr.alerteresidents.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class WeenectLoginRequest(
    @Json(name = "username") val username: String,
    @Json(name = "password") val password: String
)

@JsonClass(generateAdapter = true)
data class WeenectLoginResponse(
    @Json(name = "access_token") val accessToken: String? = null,
    @Json(name = "expires_in") val expiresIn: Double? = null,
    @Json(name = "refresh_token") val refreshToken: String? = null
)

@JsonClass(generateAdapter = true)
data class WeenectTrackersResponse(
    @Json(name = "items") val items: List<WeenectTrackerDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class WeenectTrackerDto(
    @Json(name = "id") val id: Long,
    @Json(name = "name") val name: String? = null
)

@JsonClass(generateAdapter = true)
data class WeenectPositionDto(
    @Json(name = "id") val id: String? = null,
    @Json(name = "latitude") val latitude: Double? = null,
    @Json(name = "longitude") val longitude: Double? = null,
    @Json(name = "battery") val battery: Int? = null,
    @Json(name = "speed") val speed: Double? = null,
    @Json(name = "direction") val direction: Int? = null,
    @Json(name = "valid_signal") val validSignal: Boolean? = null,
    @Json(name = "satellites") val satellites: Int? = null,
    @Json(name = "gsm") val gsm: Int? = null,
    @Json(name = "radius") val radius: Int? = null,
    @Json(name = "type") val type: String? = null,
    @Json(name = "last_message") val lastMessage: String? = null,
    @Json(name = "date_server") val dateServer: String? = null,
    @Json(name = "date_tracker") val dateTracker: String? = null,
    @Json(name = "is_in_deep_sleep") val isInDeepSleep: Boolean? = null,
    @Json(name = "gsm_state") val gsmState: String? = null,
    @Json(name = "battery_state") val batteryState: String? = null,
    @Json(name = "accuracy_state") val accuracyState: String? = null,
    @Json(name = "off_reason") val offReason: String? = null,
    @Json(name = "wifi_zone_id") val wifiZoneId: Int? = null
)

@JsonClass(generateAdapter = true)
data class WeenectModeRequest(
    @Json(name = "mode") val mode: String
)
