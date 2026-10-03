package fr.alerteresidents.util

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

/** Clients HTTP partagés (un seul pool de connexions pour toute l'app). */
object HttpClients {
    /** Logs réseau (sans corps) : activé par l'application en version debug, avant le premier appel. */
    @Volatile var debugLogging: Boolean = false
    var userAgent: String = "AlerteResidents/2.0"

    val base: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                if (req.header("User-Agent") == null) {
                    chain.proceed(req.newBuilder().header("User-Agent", userAgent).build())
                } else chain.proceed(req)
            })
            .build()
    }

    /** Client Weenect : en-têtes de l'application web Weenect, logs réseau seulement en debug et sans corps. */
    val weenect: OkHttpClient by lazy {
        base.newBuilder()
            .addInterceptor(Interceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", "weenect-go/1.0.0")
                        .header("Accept", "application/json, text/plain, */*")
                        .header("Origin", "https://my.weenect.com")
                        .header("x-app-version", "0.1.0")
                        .header("x-app-type", "userspace")
                        .build()
                )
            })
            .apply {
                if (debugLogging) {
                    addInterceptor(HttpLoggingInterceptor().apply {
                        // Jamais BODY : le corps du login contient le mot de passe.
                        level = HttpLoggingInterceptor.Level.BASIC
                        redactHeader("Authorization")
                    })
                }
            }
            .build()
    }
}
