package fr.alerteresidents.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

class SupabaseSyncService {
    private val baseUrl = "https://iphngnvzdqmuibscmtjk.supabase.co/rest/v1"
    private val anonKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImlwaG5nbnZ6ZHFtdWlic2NtdGprIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODcwODYzODYsImV4cCI6MjEwMjY2MjM4Nn0.N156Xdb4z4nOrl8ipI9jHT9WY0EWbh4u34C0xyn1UlA"

    private val client = fr.alerteresidents.util.HttpClients.base

    suspend fun testConnection(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/")
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer $anonKey")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code == 404) {
                    Result.success(true)
                } else {
                    Result.failure(Exception("Supabase status: ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
