package com.jonasschnell.quickscope.data

import okhttp3.MultipartBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

/** Vertrag mit dem Backend. Der SSE-Stream (`api/stream`) laeuft ueber [LiveSync], die Original-Vorschau ueber [MaterialPreview]. */
interface QuickscopeApi {
    @GET("api/me") suspend fun me(): Me

    @POST("api/auth/session") suspend fun createSession(): SessionResponse

    @DELETE("api/me") suspend fun deleteMe()

    /** Abmelden: die Sitzung auf dem Server beenden (neu im Backend; 404/405 werden ruhig behandelt). */
    @POST("api/auth/logout") suspend fun logout()

    /** Eigene Daten als Datei (Streaming, nicht komplett im Speicher). */
    @Streaming
    @GET("api/me/export") suspend fun export(): okhttp3.ResponseBody

    @GET("api/me/profile") suspend fun profile(): Profile
    @PUT("api/me/profile") suspend fun putProfile(@Body body: ProfileUpdate): Profile

    /** Sprachnachricht (aac/m4a, bis ca. 60 s) -> Zusammenfassung zum Bearbeiten. */
    @Multipart
    @POST("api/me/profile/voice")
    suspend fun profileVoice(@Part file: MultipartBody.Part): VoiceSummary

    @GET("api/app/latest") suspend fun latestRelease(): AppRelease

    @GET("api/exams") suspend fun exams(): List<Exam>
    @POST("api/exams") suspend fun createExam(@Body body: ExamCreate): Exam
    @GET("api/exams/{id}") suspend fun exam(@Path("id") id: String): Exam
    @PUT("api/exams/{id}") suspend fun updateExam(@Path("id") id: String, @Body body: ExamUpdate): Exam
    @DELETE("api/exams/{id}") suspend fun deleteExam(@Path("id") id: String)

    @GET("api/exams/{id}/materials") suspend fun materials(@Path("id") id: String): List<Material>
    @DELETE("api/exams/{id}/materials/{materialId}") suspend fun deleteMaterial(@Path("id") id: String, @Path("materialId") materialId: String)

    /** Teile: `file` (ein Dokument) oder mehrere `files[]` (Seiten eines Scans) plus optional `role` und `name`. */
    @Multipart
    @POST("api/exams/{id}/materials")
    suspend fun uploadMaterial(@Path("id") id: String, @Part parts: List<MultipartBody.Part>): Material

    @GET("api/exams/{id}/chapters") suspend fun chapters(@Path("id") id: String): List<Chapter>
    @GET("api/exams/{id}/lessons") suspend fun lessons(@Path("id") id: String, @Query("chapterId") chapterId: String? = null): List<Lesson>
    @GET("api/exams/{id}/audiobook") suspend fun audiobook(@Path("id") id: String, @Query("minutes") minutes: Int? = null, @Query("repeat") repeat: Boolean? = null): Audiobook
    @POST("api/exams/{id}/lessons/{lessonId}/heard") suspend fun heard(@Path("id") id: String, @Path("lessonId") lessonId: String, @Body body: HeardRequest)

    @GET("api/audio/status") suspend fun audioStatus(): AudioStatus
    @GET("api/exams/{id}/items/{itemId}/audio/timings") suspend fun itemTimings(@Path("id") id: String, @Path("itemId") itemId: String, @Query("part") part: String = "question"): AudioTimings

    @GET("api/exams/{id}/topics") suspend fun topics(@Path("id") id: String): List<Topic>
    @GET("api/exams/{id}/session") suspend fun session(@Path("id") id: String, @Query("minutes") minutes: Int? = null, @Query("lessons") lessons: Boolean? = null): LearnSession
    @POST("api/exams/{id}/attempts") suspend fun attempts(@Path("id") id: String, @Body body: List<AttemptRequest>): AttemptsResponse
    /** Offline-Paket der naechsten [days] Tage (Aufgaben, Lektionen, Reserve); 404, solange das Backend es nicht kennt. */
    @GET("api/exams/{id}/offline-pack") suspend fun offlinePack(@Path("id") id: String, @Query("days") days: Int = 3): okhttp3.ResponseBody
    @GET("api/exams/{id}/forecast") suspend fun forecast(@Path("id") id: String): Forecast
    @GET("api/exams/{id}/cheatsheet") suspend fun cheatsheet(@Path("id") id: String, @Query("minutes") minutes: Int = 5): CheatSheet
    /** Aufgaben neu erzeugen (202); der Lernstand wird zurueckgesetzt, die Pruefung ist danach wieder "processing". */
    @POST("api/exams/{id}/regenerate") suspend fun regenerate(@Path("id") id: String, @Query("resetProgress") resetProgress: Boolean? = null)
}
