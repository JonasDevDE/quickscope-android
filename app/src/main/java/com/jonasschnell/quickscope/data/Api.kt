package com.jonasschnell.quickscope.data

import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST

/** Vertrag mit dem Backend: Konto, Sitzungs-Tausch und Selbst-Update. Der SSE-Stream (`api/stream`) laeuft ueber [LiveSync]. */
interface QuickscopeApi {
    @GET("api/me") suspend fun me(): Me

    @POST("api/auth/session") suspend fun createSession(): SessionResponse

    @DELETE("api/me") suspend fun deleteMe()

    @GET("api/app/latest") suspend fun latestRelease(): AppRelease
}
