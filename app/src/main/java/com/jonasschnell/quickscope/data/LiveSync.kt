package com.jonasschnell.quickscope.data

import com.jonasschnell.quickscope.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Nutzlast des SSE-Ereignisses `change`. kind benennt den geaenderten Datenbereich, by den ausloesenden Nutzer. */
@Serializable
data class LiveChange(val kind: String, val scopeId: String? = null, val by: String? = null)

/** Nutzlast des SSE-Ereignisses `update`. */
@Serializable
data class LiveUpdate(val versionCode: Int, val versionName: String? = null, val notes: String? = null)

/**
 * Haelt eine SSE-Verbindung (`GET api/stream`) fuer den Nutzer offen und meldet Aenderungen.
 * Reconnect mit Backoff (1, 2, 4 ... max 30 s); nach jedem (Wieder-)Verbinden [onConnected].
 * Fallback auf Polling ([onPoll] alle 10 s), wenn 3 Versuche in Folge scheitern oder der Proxy den Stream puffert
 * (kein `hello`/Heartbeat); der Stream wird alle 2 Minuten erneut versucht.
 * Alle Callbacks laufen im Kontext von [scope] (Main).
 */
class LiveSync(
    private val streamClient: OkHttpClient,
    private val scope: CoroutineScope,
    private val onChange: (LiveChange) -> Unit,
    private val onConnected: () -> Unit,
    private val onPoll: () -> Unit,
    /** Server lehnt den Stream mit 403/404 ab. */
    private val onForbidden: () -> Unit = {},
    /** Server meldet eine neue App-Version (Ereignis `update`). */
    private val onUpdate: (LiveUpdate) -> Unit = {},
) {
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { loop() }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private class Session(var gotHello: Boolean = false, var stalled: Boolean = false, var code: Int = 0)

    private suspend fun loop() {
        var failures = 0
        var backoff = INITIAL_BACKOFF_MS
        while (scope.isActive) {
            val session = Session()
            try {
                connectOnce(session)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            if (session.code == 403 || session.code == 404) onForbidden()
            if (session.gotHello) {
                failures = 0
                backoff = INITIAL_BACKOFF_MS
            } else {
                failures++
            }
            if (session.stalled || failures >= MAX_FAILURES) {
                pollFor(POLL_PHASE_MS)
                // Nach der Polling-Phase genuegt ein einzelner Fehlschlag, um zurueck ins Polling zu gehen.
                failures = MAX_FAILURES - 1
                backoff = INITIAL_BACKOFF_MS
                continue
            }
            delay(backoff)
            backoff = minOf(backoff * 2, MAX_BACKOFF_MS)
        }
    }

    private suspend fun pollFor(durationMs: Long) {
        val until = System.currentTimeMillis() + durationMs
        onPoll()
        while (System.currentTimeMillis() < until) {
            delay(POLL_INTERVAL_MS)
            onPoll()
        }
    }

    private suspend fun connectOnce(session: Session) = coroutineScope {
        val url = BuildConfig.BASE_URL.toHttpUrl().newBuilder().addPathSegments("api/stream").build()
        val request = Request.Builder().url(url).header("Accept", "text/event-stream").header("Cache-Control", "no-cache").build()
        val call = streamClient.newCall(request)
        val lastActivity = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
        val watchdog = launch {
            while (isActive) {
                delay(WATCHDOG_STEP_MS)
                val limit = if (session.gotHello) HEARTBEAT_TIMEOUT_MS else HELLO_TIMEOUT_MS
                if (System.currentTimeMillis() - lastActivity.get() > limit) {
                    // Verbindung offen, aber nichts kommt an: ohne hello puffert wohl der Proxy.
                    if (!session.gotHello) session.stalled = true
                    call.cancel()
                    return@launch
                }
            }
        }
        val reader = async(Dispatchers.IO) {
            call.execute().use { response ->
                session.code = response.code
                if (!response.isSuccessful) return@use
                lastActivity.set(System.currentTimeMillis())
                val source = response.body.source()
                var name = "message"
                val data = StringBuilder()
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    lastActivity.set(System.currentTimeMillis())
                    when {
                        line.isEmpty() -> {
                            if (data.isNotEmpty() || name != "message") dispatch(name, data.toString(), session)
                            name = "message"
                            data.setLength(0)
                        }
                        line.startsWith(":") -> {} // Heartbeat-Kommentar
                        line.startsWith("event:") -> name = line.substring(6).trim()
                        line.startsWith("data:") -> {
                            if (data.isNotEmpty()) data.append('\n')
                            data.append(line.substring(5).removePrefix(" "))
                        }
                    }
                }
            }
        }
        try {
            reader.await()
        } catch (e: CancellationException) {
            call.cancel()
            throw e
        } finally {
            watchdog.cancel()
            call.cancel()
        }
    }

    private suspend fun dispatch(name: String, data: String, session: Session) = withContext(scope.coroutineContext[kotlin.coroutines.ContinuationInterceptor]!!) {
        when (name) {
            "hello" -> {
                session.gotHello = true
                onConnected()
            }
            "change" -> {
                val change = try {
                    AppJson.decodeFromString(LiveChange.serializer(), data)
                } catch (_: Exception) {
                    LiveChange(kind = "unknown")
                }
                onChange(change)
            }
            "update" -> {
                val update = try {
                    AppJson.decodeFromString(LiveUpdate.serializer(), data)
                } catch (_: Exception) {
                    null
                }
                if (update != null) onUpdate(update)
            }
        }
    }

    private companion object {
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
        const val MAX_FAILURES = 3
        const val HEARTBEAT_TIMEOUT_MS = 45_000L
        const val HELLO_TIMEOUT_MS = 20_000L
        const val WATCHDOG_STEP_MS = 5_000L
        const val POLL_INTERVAL_MS = 10_000L
        const val POLL_PHASE_MS = 120_000L
    }
}
