package com.jonasschnell.quickscope.data

import android.net.Uri
import android.os.SystemClock
import com.jonasschnell.quickscope.BuildConfig
import com.jonasschnell.quickscope.logic.AUDIO_RECHECK_MS

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
    private var lastResult = false
    private val base = Uri.parse(BuildConfig.BASE_URL)

    /** Ton gewuenscht (Einstellung) UND Stimme verfuegbar; [force] ignoriert den 60-s-Zwischenspeicher. */
    suspend fun available(force: Boolean = false): Boolean {
        if (!store.audioEnabled) return false // kein einziger Audio-Aufruf bei "Vorlesen aus"
        if (!connectivity.isOnline()) return false
        val now = SystemClock.elapsedRealtime()
        if (!force && lastCheck != 0L && now - lastCheck < AUDIO_RECHECK_MS) return lastResult
        lastResult = try {
            api.audioStatus().available
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        lastCheck = now
        return lastResult
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
