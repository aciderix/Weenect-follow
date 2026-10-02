package fr.alerteresidents.util

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class AddressSuggestion(
    val label: String,
    val street: String,
    val city: String,
    val postcode: String,
    val latitude: Double,
    val longitude: Double
)

object AddressSearchHelper {
    private val client = HttpClients.base.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    /**
     * Recherche d'adresses en direct via l'API BAN (Base Adresse Nationale - data.gouv.fr)
     * Très rapide, gratuite, sans clé d'API et ultra précise pour toute la France.
     */
    suspend fun searchAddress(query: String): List<AddressSuggestion> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.length < 3) return@withContext emptyList()

        val results = mutableListOf<AddressSuggestion>()

        // 1. Essayer d'abord l'API BAN (Base Adresse Nationale)
        try {
            val encodedQuery = URLEncoder.encode(trimmed, "UTF-8")
            val url = "https://api-adresse.data.gouv.fr/search/?q=$encodedQuery&limit=6"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", HttpClients.userAgent)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (!body.isNullOrBlank()) {
                        val json = JSONObject(body)
                        val features = json.optJSONArray("features")
                        if (features != null) {
                            for (i in 0 until features.length()) {
                                val feat = features.getJSONObject(i)
                                val props = feat.optJSONObject("properties") ?: continue
                                val geom = feat.optJSONObject("geometry") ?: continue
                                val coords = geom.optJSONArray("coordinates") ?: continue

                                val lon = coords.getDouble(0)
                                val lat = coords.getDouble(1)
                                val label = props.optString("label", "")
                                val name = props.optString("name", "")
                                val city = props.optString("city", "")
                                val postcode = props.optString("postcode", "")

                                if (label.isNotBlank()) {
                                    results.add(
                                        AddressSuggestion(
                                            label = label,
                                            street = name,
                                            city = city,
                                            postcode = postcode,
                                            latitude = lat,
                                            longitude = lon
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("AddressSearchHelper", "BAN search error: ${e.message}")
        }

        // 2. Si pas de résultat (ex: nom de lieu spécifique / hors France), fallback Nominatim
        if (results.isEmpty()) {
            try {
                val encodedQuery = URLEncoder.encode(trimmed, "UTF-8")
                val url = "https://nominatim.openstreetmap.org/search?q=$encodedQuery&format=json&limit=5&countrycodes=fr"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", HttpClients.userAgent)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (!body.isNullOrBlank()) {
                            val array = org.json.JSONArray(body)
                            for (i in 0 until array.length()) {
                                val item = array.getJSONObject(i)
                                val lat = item.optDouble("lat")
                                val lon = item.optDouble("lon")
                                val displayName = item.optString("display_name", "")
                                if (displayName.isNotBlank()) {
                                    results.add(
                                        AddressSuggestion(
                                            label = displayName.split(",").take(3).joinToString(", ").trim(),
                                            street = displayName.split(",").firstOrNull()?.trim() ?: "",
                                            city = "",
                                            postcode = "",
                                            latitude = lat,
                                            longitude = lon
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("AddressSearchHelper", "Nominatim search error: ${e.message}")
            }
        }

        results
    }
}
