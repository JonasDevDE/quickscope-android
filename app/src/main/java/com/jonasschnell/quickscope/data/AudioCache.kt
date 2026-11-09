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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

@Serializable
data class AudioPrefetch(val itemIds: List<String>, val parts: List<String>)

/** Cloud-Stimme nicht nutzbar (503, Limit oder nicht eingerichtet); [message] ist die kurze Backend-Meldung. */
class AudioUnavailable(message: String) : IOException(message)

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

    private fun download(examId: String, itemId: String, part: VoicePart, target: File): File {
        dir.mkdirs()
        val url = "${BuildConfig.BASE_URL}api/exams/$examId/items/$itemId/audio?part=${part.query}"
        val tmp = File(dir, target.name + ".part")
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (resp.code == 503) throw AudioUnavailable(backendText(resp.body.string()) ?: "Stimme gerade nicht verfügbar")
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

    /** Kleine Meldung an das Backend, damit es die Teile schon erzeugt; Fehler sind unkritisch. */
    suspend fun requestPrefetch(examId: String, itemIds: List<String>) {
        if (itemIds.isEmpty()) return
        runCatching { api.audioPrefetch(examId, AudioPrefetch(itemIds, VoicePart.entries.map { it.query })) }
    }

    private fun prune() {
        val files = dir.listFiles { f -> f.name.endsWith(".wav") }?.map { it.name to (it.length() to it.lastModified()) } ?: return
        filesToPrune(files, MAX_BYTES).forEach { File(dir, it).delete() }
    }

    /** Alles loeschen (Ende der Runde). */
    suspend fun clear() = withContext(Dispatchers.IO) { dir.deleteRecursively(); Unit }

    private companion object {
        const val MAX_BYTES = 40L * 1024 * 1024
    }
}
