package com.jonasschnell.quickscope.data

import com.jonasschnell.quickscope.logic.AttemptQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException

/**
 * Sendet die lokal gesammelten Antworten batchweise. Fehlt das Netz, bleiben sie in der Warteschlange und
 * gehen beim naechsten Versuch mit. Abgelehnte Antworten (4xx ausser 401/426) werden verworfen, damit nichts haengt.
 */
class AttemptSync(private val api: QuickscopeApi, val queue: AttemptQueue) {
    private val mutex = Mutex()

    /** Antwort vormerken (sofort gespeichert, noch nicht gesendet). */
    fun record(examId: String, attempt: AttemptRequest) = queue.add(examId, attempt)

    fun hasPending(examId: String) = queue.pending(examId).isNotEmpty()

    /** Sendet alles Vorgemerkte; liefert die neue Prognose oder null (nichts zu senden oder kein Netz). */
    suspend fun flush(examId: String): Forecast? = mutex.withLock {
        val batch = queue.pending(examId)
        if (batch.isEmpty()) return@withLock null
        try {
            val response = api.attempts(examId, batch)
            queue.removeSent(examId, batch.size)
            response.forecast
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            if (e.code() in 400..499 && e.code() != 401 && e.code() != 408 && e.code() != 429) queue.removeSent(examId, batch.size)
            null
        } catch (_: Exception) {
            null
        }
    }
}
