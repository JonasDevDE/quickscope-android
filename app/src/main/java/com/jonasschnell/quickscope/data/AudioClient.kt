package com.jonasschnell.quickscope.data

import android.net.Uri
import android.os.SystemClock
import com.jonasschnell.quickscope.BuildConfig
import com.jonasschnell.quickscope.logic.AUDIO_RECHECK_MS
import com.jonasschnell.quickscope.logic.statusAvailable

/**
 * Vorlesen per Live-Streaming: nichts wird vorgeladen oder gespeichert. Bei "Vorlesen aus" gibt es gar keine Audio-Aufrufe
 * (weder Status noch Stream noch Wortzeiten). Der Status (GET audio/status) wird beim Rundenstart geprueft und danach hoechstens
 * alle 60 s erneut; offline oder bei jedem Fehler laeuft die Runde still ohne Ton.
 */
class AudioClient(
    private val api: QuickscopeApi,
    private val auth: AuthManager,
    private val store: Store,
    private val connectivity: Connectivity,
) {
    private var lastCheck = 0L
    private var lastResult = true
    private var recheckMs = AUDIO_RECHECK_MS
    private val STATUS_TIMEOUT_MS = com.jonasschnell.quickscope.logic.AUDIO_STATUS_TIMEOUT_MS
    private val prefetchGate = kotlinx.coroutines.sync.Semaphore(1)
    private val base = Uri.parse(BuildConfig.BASE_URL)

    /** Ton gewuenscht (Einstellung) UND Stimme verfuegbar; [force] ignoriert den 60-s-Zwischenspeicher. */
    suspend fun available(force: Boolean = false): Boolean {
        if (!store.audioEnabled) return false // kein einziger Audio-Aufruf bei "Vorlesen aus"
        if (!connectivity.isOnline()) return false
        val now = SystemClock.elapsedRealtime()
        if (!force && lastCheck != 0L && now - lastCheck < recheckMs) return lastResult
        // Nur eine ausdrueckliche Antwort {available:false} schaltet still; 404/401/5xx/Timeout/Netzfehler heissen "unbekannt"
        // und der Stream wird trotzdem versucht (bei Fehler bleibt nur diese Karte still).
        val status = try {
            kotlinx.coroutines.withTimeoutOrNull(STATUS_TIMEOUT_MS) { api.audioStatus() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        lastResult = statusAvailable(status)
        // Der Server nennt bei Ausfall, wann es sich lohnt, wieder zu fragen
        recheckMs = if (!lastResult) (status?.retryAfterSeconds?.times(1000L)?.coerceIn(5_000L, 300_000L) ?: AUDIO_RECHECK_MS) else AUDIO_RECHECK_MS
        lastCheck = now
        return lastResult
    }

    /**
     * Stoesst das Audio einer kommenden Karte an, damit das Backend es im Cache hat: ein Request mit Range auf 1 Byte, nichts wird geladen
     * oder gespeichert, hoechstens einer gleichzeitig. Nur bei "Vorlesen an" und online; Fehler sind egal.
     */
    suspend fun prefetch(client: okhttp3.OkHttpClient, examId: String, target: com.jonasschnell.quickscope.logic.PrefetchTarget) {
        if (!store.audioEnabled || !connectivity.isOnline()) return
        val uri = if (target.lesson) lessonUri(examId, target.id) else itemUri(examId, target.id)
        prefetchGate.acquire()
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val request = okhttp3.Request.Builder().url(uri.toString()).header("Range", "bytes=0-0").build()
                val c = client.newBuilder().callTimeout(com.jonasschnell.quickscope.logic.streamStartTimeoutMs(target.lesson), java.util.concurrent.TimeUnit.MILLISECONDS).build()
                runCatching { c.newCall(request).execute().use { } }
            }
        } finally {
            prefetchGate.release()
        }
    }

    /** Zentrale Fehlerprotokollierung: Debug-Log immer, ans Backend gedrosselt (best effort, 404/Fehler egal). */
    suspend fun report(code: String, detail: String, lesson: Boolean) {
        AppLog.d("Audio", "Fehler $code (${if (lesson) "Lektion" else "Frage"}): $detail")
        if (!connectivity.isOnline()) return
        val now = System.currentTimeMillis()
        val day = java.time.LocalDate.now().toString()
        if (store.logDay != day) { store.logDay = day; store.logCount = 0 }
        if (!com.jonasschnell.quickscope.logic.clientLogAllowed(now, store.logLastMs, store.logCount)) return
        store.logLastMs = now
        store.logCount = store.logCount + 1
        try {
            api.clientLog(ClientLog("audio", code, com.jonasschnell.quickscope.logic.clientLogDetail(detail), BuildConfig.VERSION_NAME, if (lesson) "lesson" else "question"))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** Nach einem Fehlschlag: ein kleiner Range-Request zeigt HTTP-Status und Content-Type (MediaPlayer nennt sie nicht). Null = unauffaellig. */
    suspend fun diagnose(client: okhttp3.OkHttpClient, uri: Uri): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val c = client.newBuilder().callTimeout(4, java.util.concurrent.TimeUnit.SECONDS).build()
            val request = okhttp3.Request.Builder().url(uri.toString()).header("Range", "bytes=0-0").build()
            c.newCall(request).execute().use { r ->
                val code = com.jonasschnell.quickscope.logic.audioErrorCode(httpCode = r.code, contentType = r.header("Content-Type")?.substringBefore(';')?.trim())
                code.takeIf { it != "error" }
            }
        } catch (e: java.io.IOException) {
            "io:" + e.javaClass.simpleName
        }
    }

    fun itemUri(examId: String, itemId: String): Uri = Uri.parse("${BuildConfig.BASE_URL}api/exams/$examId/items/$itemId/audio?part=question")

    fun lessonUri(examId: String, lessonId: String): Uri = Uri.parse("${BuildConfig.BASE_URL}api/exams/$examId/lessons/$lessonId/audio")

    /** Anmelde-Header nur fuer den eigenen API-Host. */
    fun headers(uri: Uri): Map<String, String> =
        if (uri.scheme == base.scheme && uri.host == base.host && uri.port == base.port) {
            mapOf("Authorization" to "Bearer ${auth.token.orEmpty()}", "X-App-Version" to BuildConfig.VERSION_CODE.toString())
        } else {
            emptyMap()
        }

    /** Wortzeiten live parallel zum Stream; null bei Aus, Fehler oder Offline. */
    suspend fun timings(examId: String, itemId: String): AudioTimings? {
        if (!store.audioEnabled || !connectivity.isOnline()) return null
        return try {
            api.itemTimings(examId, itemId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}
