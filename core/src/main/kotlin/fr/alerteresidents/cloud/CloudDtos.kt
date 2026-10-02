package fr.alerteresidents.cloud

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/* Réponses de Supabase (Auth et fonctions SQL de supabase/migrations). */

@JsonClass(generateAdapter = true)
data class AuthUserDto(val id: String? = null, val email: String? = null)

@JsonClass(generateAdapter = true)
data class TokenDto(
    @Json(name = "access_token") val accessToken: String,
    @Json(name = "refresh_token") val refreshToken: String,
    @Json(name = "expires_in") val expiresIn: Long = 3600,
    val user: AuthUserDto? = null
)

@JsonClass(generateAdapter = true)
data class ErrorDto(
    val message: String? = null,
    val msg: String? = null,
    val code: String? = null,
    @Json(name = "error_code") val errorCode: String? = null,
    @Json(name = "error_description") val errorDescription: String? = null
)

@JsonClass(generateAdapter = true)
data class AccessDto(val staff: Boolean = false, @Json(name = "display_name") val displayName: String? = null)

@JsonClass(generateAdapter = true)
data class IncidentDto(
    val id: String,
    @Json(name = "tracker_id") val trackerId: Long,
    @Json(name = "resident_name") val residentName: String = "",
    @Json(name = "is_drill") val isDrill: Boolean = false,
    val status: String,
    @Json(name = "opened_at") val openedAt: String? = null,
    @Json(name = "opened_by_device_name") val openedByDeviceName: String? = null,
    @Json(name = "handled_by") val handledBy: String? = null,
    @Json(name = "handled_at") val handledAt: String? = null,
    @Json(name = "resolved_by") val resolvedBy: String? = null,
    @Json(name = "resolved_at") val resolvedAt: String? = null,
    val resolution: String? = null,
    @Json(name = "updated_at") val updatedAt: String? = null
) {
    companion object {
        const val ACTIVE = "active"
        const val HANDLING = "handling"
        const val RESOLVED = "resolved"
    }
}

@JsonClass(generateAdapter = true)
data class PauseDto(
    @Json(name = "tracker_id") val trackerId: Long,
    @Json(name = "resident_name") val residentName: String = "",
    @Json(name = "paused_until") val pausedUntil: String? = null,
    val reason: String? = null,
    @Json(name = "set_by") val setBy: String? = null,
    @Json(name = "updated_at") val updatedAt: String? = null
)

@JsonClass(generateAdapter = true)
data class DeviceDto(
    val id: String,
    val name: String,
    val platform: String = "other",
    @Json(name = "app_version") val appVersion: String? = null,
    @Json(name = "monitoring_ok") val monitoringOk: Boolean = true,
    @Json(name = "residents_count") val residentsCount: Int = 0,
    @Json(name = "last_seen_at") val lastSeenAt: String? = null
)

@JsonClass(generateAdapter = true)
data class ConfigMetaDto(
    @Json(name = "published_by") val publishedBy: String? = null,
    @Json(name = "published_at") val publishedAt: String? = null,
    val payload: String? = null
)

@JsonClass(generateAdapter = true)
data class SyncStateDto(
    val now: String? = null,
    val incidents: List<IncidentDto> = emptyList(),
    val pauses: List<PauseDto> = emptyList(),
    val devices: List<DeviceDto> = emptyList(),
    val config: ConfigMetaDto? = null
)
