package fr.alerteresidents.cloud

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import fr.alerteresidents.util.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class SupabaseException(
    message: String,
    val httpCode: Int = 0,
    /** Session refusée (jeton expiré ou révoqué) : il faut se reconnecter. */
    val authExpired: Boolean = false,
    /** Compte connecté mais absent de la table staff. */
    val notStaff: Boolean = false
) : Exception(message)

data class CloudSession(val accessToken: String, val refreshToken: String, val expiresAt: Long, val email: String?)

/**
 * Accès minimal à un projet Supabase quelconque : Auth (e-mail + mot de passe) et appels
 * des fonctions SQL (PostgREST /rpc). Aucune dépendance au SDK Supabase.
 */
class SupabaseClient(
    val baseUrl: String,
    private val apiKey: String,
    private val http: OkHttpClient = HttpClients.base,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val moshi: Moshi = Moshi.Builder().build()
    private val tokenAdapter = moshi.adapter(TokenDto::class.java)
    private val errorAdapter = moshi.adapter(ErrorDto::class.java)
    private val argsAdapter: JsonAdapter<Map<String, Any?>> =
        moshi.adapter<Map<String, Any?>>(Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)).serializeNulls()
    private val json = "application/json".toMediaType()

    suspend fun signIn(email: String, password: String): CloudSession =
        token("password", argsAdapter.toJson(mapOf("email" to email.trim(), "password" to password)))

    suspend fun refresh(refreshToken: String): CloudSession =
        token("refresh_token", argsAdapter.toJson(mapOf("refresh_token" to refreshToken)))

    suspend fun signOut(accessToken: String) {
        runCatching { execute(request("auth/v1/logout", "{}", accessToken)) }
    }

    /** Appelle une fonction SQL et renvoie le JSON brut de la réponse. */
    suspend fun rpc(function: String, args: Map<String, Any?>, accessToken: String): String =
        execute(request("rest/v1/rpc/$function", argsAdapter.toJson(args), accessToken))

    private suspend fun token(grant: String, body: String): CloudSession {
        val raw = execute(request("auth/v1/token?grant_type=$grant", body, null))
        val t = tokenAdapter.fromJson(raw) ?: throw SupabaseException("Réponse d'authentification illisible")
        return CloudSession(t.accessToken, t.refreshToken, clock() + t.expiresIn * 1000L, t.user?.email)
    }

    private fun request(path: String, body: String, accessToken: String?): Request =
        Request.Builder()
            .url("$baseUrl/$path")
            .header("apikey", apiKey)
            .apply { if (accessToken != null) header("Authorization", "Bearer $accessToken") }
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(json))
            .build()

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        try {
            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (resp.isSuccessful) return@withContext text
                throw errorFor(resp.code, text, request.url.toString())
            }
        } catch (e: IOException) {
            throw SupabaseException("Serveur Supabase injoignable (${e.message ?: e.javaClass.simpleName})")
        }
    }

    private fun errorFor(code: Int, body: String, url: String): SupabaseException {
        val err = runCatching { errorAdapter.fromJson(body) }.getOrNull()
        val detail = err?.message ?: err?.msg ?: err?.errorDescription ?: body.take(200)
        val isAuthEndpoint = url.contains("/auth/v1/token")
        return when {
            isAuthEndpoint && url.contains("grant_type=password") ->
                SupabaseException(
                    if (code == 400) "E-mail ou mot de passe incorrect" else "Connexion refusée : $detail",
                    code
                )
            isAuthEndpoint && code in 400..403 -> SupabaseException(SESSION_LOST, code, authExpired = true)
            isAuthEndpoint -> SupabaseException("Authentification Supabase indisponible ($code)", code)
            err?.code == "42501" && detail.contains("staff") ->
                SupabaseException("Ce compte n'est pas autorisé dans ce projet (table staff)", code, notStaff = true)
            code == 401 && (err?.code == "PGRST301" || err?.code == "PGRST303" || detail.contains("JWT", ignoreCase = true)) ->
                SupabaseException("Session expirée", code, authExpired = true)
            code == 404 && url.contains("/rpc/") ->
                SupabaseException("Base non initialisée : exécutez le script SQL de supabase/migrations dans ce projet", code)
            code == 401 || code == 403 ->
                SupabaseException("Accès refusé par Supabase : $detail", code)
            else -> SupabaseException("Erreur Supabase ($code) : $detail", code)
        }
    }

    companion object {
        const val SESSION_LOST = "Session expirée : reconnectez-vous"

        /**
         * Accepte « https://xxxx.supabase.co », « xxxx.supabase.co », l'identifiant seul « xxxx »
         * ou un domaine personnalisé en https. null si l'adresse n'est pas exploitable.
         */
        fun normalizeUrl(input: String): String? {
            var s = input.trim().trimEnd('/')
            if (s.isEmpty()) return null
            for (suffix in listOf("/rest/v1", "/auth/v1")) if (s.endsWith(suffix)) s = s.removeSuffix(suffix).trimEnd('/')
            if (Regex("^[a-z0-9]{15,40}$").matches(s)) return "https://$s.supabase.co"
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
            val host = s.substringAfter("://").substringBefore('/')
            if (host.isBlank() || !host.contains('.') && !host.startsWith("localhost") && !host.startsWith("127.")) return null
            return s
        }
    }
}
