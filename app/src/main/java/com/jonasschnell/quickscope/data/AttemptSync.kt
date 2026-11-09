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
    fun record(examId: String, attempt: AttemptRequest) =
        queue.add(examId, attempt.copy(clientId = attempt.clientId ?: java.util.UUID.randomUUID().toString()))

    fun hasPending(examId: String) = queue.pending(examId).isNotEmpty()

    /** Sendet alles Vorgemerkte; liefert die Antwort (Prognose, ggf. nextLessonId) oder null (nichts zu senden oder kein Netz). */
    suspend fun flush(examId: String): AttemptsResponse? = mutex.withLock {
        var last: AttemptsResponse? = null
        // der Server nimmt bis zu 500 Antworten je Aufruf; mehr geht in Stapeln nacheinander
        while (true) {
            val batch = queue.pending(examId).take(MAX_BATCH)
            if (batch.isEmpty()) break
            try {
                last = api.attempts(examId, batch)
                queue.removeSent(examId, batch.size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                if (e.code() in 400..499 && e.code() != 401 && e.code() != 408 && e.code() != 429) queue.removeSent(examId, batch.size) else break
            } catch (_: Exception) {
                break
            }
        }
        last
    }

    companion object {
        const val MAX_BATCH = 500
    }
}
