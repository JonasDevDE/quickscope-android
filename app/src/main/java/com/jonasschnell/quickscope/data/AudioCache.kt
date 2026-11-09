package com.jonasschnell.quickscope.data

import android.content.Context
import com.jonasschnell.quickscope.BuildConfig
import com.jonasschnell.quickscope.logic.VoicePart
import com.jonasschnell.quickscope.logic.audioFileName
import com.jonasschnell.quickscope.logic.filesToPrune
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

@Serializable
data class AudioPrefetch(val itemIds: List<String> = emptyList(), val parts: List<String> = emptyList(), val lessonIds: List<String>? = null)

/** Cloud-Stimme gerade nicht nutzbar (503 oder 429); [retryAfterMs] aus dem Retry-After-Header. */
class AudioUnavailable(message: String, val retryAfterMs: Long? = null) : IOException(message)

/**
 * Laedt die vorgelesenen Teile (WAV) mit Anmelde-Header in den App-Cache. Doppelte Anfragen werden zusammengelegt,
 * die Cache-Groesse ist begrenzt, am Ende der Runde wird alles geloescht.
 */
class AudioCache(context: Context, private val client: OkHttpClient, private val api: QuickscopeApi) {
    private val dir = File(context.cacheDir, "audio")
    private val running = mutableMapOf<String, Deferred<File>>()
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun file(examId: String, itemId: String, part: VoicePart) = File(dir, audioFileName(examId, itemId, part))

    /** Datei aus dem Cache oder frisch geladen; wirft [AudioUnavailable] bzw. [IOException]. */
    suspend fun fetch(examId: String, itemId: String, part: VoicePart): File {
        val target = file(examId, itemId, part)
        if (target.exists() && target.length() > 0) return target
        val job = lock.withLock { running.getOrPut(target.name) { scope.async(Dispatchers.IO) { download(examId, itemId, part, target) } } }
        try {
            return job.await()
        } finally {
            lock.withLock { if (job.isCompleted) running.remove(target.name) }
        }
    }

    /** Lektions-Audio (Session-Lektionskarte): wie [fetch], aber von der Lektions-URL. */
    suspend fun fetchLesson(examId: String, lessonId: String): File {
        val target = File(dir, audioFileName(examId, "lesson_$lessonId", VoicePart.Question))
        if (target.exists() && target.length() > 0) return target
        val url = "${BuildConfig.BASE_URL}api/exams/$examId/lessons/$lessonId/audio"
        val job = lock.withLock { running.getOrPut(target.name) { scope.async(Dispatchers.IO) { downloadUrl(url, target) } } }
        try {
            return job.await()
        } finally {
            lock.withLock { if (job.isCompleted) running.remove(target.name) }
        }
    }

    private val prefetchGate = kotlinx.coroutines.sync.Semaphore(com.jonasschnell.quickscope.logic.PREFETCH_PARALLEL)

    /** Wie [fetch], aber gedrosselt (hoechstens 2 gleichzeitig): fuer das Vorladen der naechsten Karten. */
    suspend fun fetchThrottled(examId: String, itemId: String, part: VoicePart): File =
        prefetchGate.withPermit { fetch(examId, itemId, part) }

    suspend fun fetchLessonThrottled(examId: String, lessonId: String): File =
        prefetchGate.withPermit { fetchLesson(examId, lessonId) }

    /** Liegt das Audio schon lokal im Cache? */
    fun isCached(examId: String, t: com.jonasschnell.quickscope.logic.WarmTarget): Boolean {
        val f = if (t.lesson) File(dir, audioFileName(examId, "lesson_${t.id}", VoicePart.Question)) else file(examId, t.id, VoicePart.Question)
        return f.exists() && f.length() > 0
    }

    /**
     * Audio-Gate: wartet, bis das Audio im Cache liegt, mit unsichtbaren Wiederholungen (Backoff 1, 2, 4 ... s, Retry-After wird beachtet).
     * true = bereit, false = nach [com.jonasschnell.quickscope.logic.GATE_TIMEOUT_MS] nicht gekommen.
     */
    suspend fun ensureReady(examId: String, t: com.jonasschnell.quickscope.logic.WarmTarget): Boolean {
        val t0 = android.os.SystemClock.elapsedRealtime()
        var attempt = 0
        while (true) {
            when (com.jonasschnell.quickscope.logic.gateDecision(true, isCached(examId, t), android.os.SystemClock.elapsedRealtime() - t0)) {
                com.jonasschnell.quickscope.logic.GateResult.Ready -> return true
                com.jonasschnell.quickscope.logic.GateResult.Timeout -> return false
                com.jonasschnell.quickscope.logic.GateResult.Wait -> Unit
            }
            var retryAfter: Long? = null
            try {
                if (t.lesson) fetchLesson(examId, t.id) else fetch(examId, t.id, VoicePart.Question)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                retryAfter = (e as? AudioUnavailable)?.retryAfterMs
                AppLog.w("AudioCache", "Gate: Versuch ${attempt + 1} fehlgeschlagen", e)
                kotlinx.coroutines.delay(com.jonasschnell.quickscope.logic.retryDelayMs(attempt++, retryAfter))
            }
        }
    }

    /** Wird beim Start einer Runde abgewartet, damit das Aufraeumen der letzten Runde nie neue Downloads trifft. */
    @Volatile var clearing: kotlinx.coroutines.Job? = null

    private fun download(examId: String, itemId: String, part: VoicePart, target: File): File =
        downloadUrl("${BuildConfig.BASE_URL}api/exams/$examId/items/$itemId/audio?part=${part.query}", target)

    private fun downloadUrl(url: String, target: File): File {
        dir.mkdirs()
        val tmp = File(dir, target.name + ".part")
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (resp.code == 503 || resp.code == 429) throw AudioUnavailable("HTTP ${resp.code}", com.jonasschnell.quickscope.logic.parseRetryAfter(resp.header("Retry-After")))
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                resp.body.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            }
            if (!tmp.renameTo(target)) throw IOException("Datei nicht ablegbar")
        } finally {
            tmp.delete()
        }
        prune()
        return target
    }

    private fun backendText(body: String): String? = runCatching {
        (AppJson.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject)?.get("message")
            ?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private val timingsCache = mutableMapOf<String, AudioTimings?>()

    /** Wortzeiten zu einem Teil; null bei 404/503/Netzfehler (dann ohne Hervorhebung). Ergebnisse werden pro Runde gemerkt. */
    suspend fun timings(examId: String, itemId: String, part: VoicePart): AudioTimings? {
        val key = audioFileName(examId, itemId, part)
        synchronized(timingsCache) { if (timingsCache.containsKey(key)) return timingsCache[key] }
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val url = "${BuildConfig.BASE_URL}api/exams/$examId/items/$itemId/audio/timings?part=${part.query}"
                client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) null else AppJson.decodeFromString(AudioTimings.serializer(), resp.body.string())
                }
            }.getOrNull()
        }
        // Fehlschlaege nicht merken, damit ein spaeterer Versuch (Prefetch fertig) klappen kann
        if (result != null) synchronized(timingsCache) { timingsCache[key] = result }
        return result
    }

    /** Kleine Meldung an das Backend, damit es die Teile schon erzeugt; Fehler sind unkritisch. */
    suspend fun requestPrefetch(examId: String, itemIds: List<String>) {
        if (itemIds.isEmpty()) return
        runCatching { api.audioPrefetch(examId, AudioPrefetch(itemIds, VoicePart.entries.map { it.query })) }
    }

    /**
     * Rundenstart: das Backend sofort bitten, die ersten Audios zu erzeugen, und sie parallel laden. Die Jobs stehen in der
     * Reihenfolge des Plans (der erste gehoert zur ersten Karte); sie laufen auch weiter, wenn niemand mehr wartet.
     */
    fun warm(examId: String, plan: List<com.jonasschnell.quickscope.logic.WarmTarget>): List<Deferred<Boolean>> {
        scope.launch {
            requestPrefetch(examId, plan.filter { !it.lesson }.map { it.id })
            requestLessonPrefetch(examId, plan.filter { it.lesson }.map { it.id })
        }
        return plan.mapIndexed { index, t ->
            scope.async {
                val t0 = android.os.SystemClock.elapsedRealtime()
                // die erste Karte hat Vorrang, die uebrigen laden gedrosselt
                val ok = runCatching {
                    if (t.lesson) (if (index == 0) fetchLesson(examId, t.id) else fetchLessonThrottled(examId, t.id))
                    else (if (index == 0) fetch(examId, t.id, VoicePart.Question) else fetchThrottled(examId, t.id, VoicePart.Question))
                }
                    .onFailure { com.jonasschnell.quickscope.data.AppLog.w("AudioCache", "warm ${t.id} fehlgeschlagen", it) }.isSuccess
                com.jonasschnell.quickscope.data.AppLog.d("AudioCache", "warm ${if (t.lesson) "Lektion" else "Aufgabe"} ${t.id}: ${if (ok) "bereit" else "Fehler"} nach ${android.os.SystemClock.elapsedRealtime() - t0} ms")
                ok
            }
        }
    }

    /** Lektionen vorab erzeugen lassen (Hoerbuch und Session). */
    suspend fun requestLessonPrefetch(examId: String, lessonIds: List<String>) {
        if (lessonIds.isEmpty()) return
        runCatching { api.audioPrefetch(examId, AudioPrefetch(lessonIds = lessonIds)) }
    }

    private fun prune() {
        val files = dir.listFiles { f -> f.name.endsWith(".wav") }?.map { it.name to (it.length() to it.lastModified()) } ?: return
        filesToPrune(files, MAX_BYTES).forEach { File(dir, it).delete() }
    }

    /** Alles loeschen (Ende der Runde). */
    suspend fun clear() {
        synchronized(timingsCache) { timingsCache.clear() }
        withContext(Dispatchers.IO) { dir.deleteRecursively() }
    }

    private companion object {
        const val MAX_BYTES = 40L * 1024 * 1024
    }
}
