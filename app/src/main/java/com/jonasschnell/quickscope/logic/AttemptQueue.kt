package com.jonasschnell.quickscope.logic

import com.jonasschnell.quickscope.data.AppJson
import com.jonasschnell.quickscope.data.AttemptRequest
import kotlinx.serialization.builtins.ListSerializer

/** Ablage fuer die Warteschlange (SharedPreferences in der App, Map im Test). */
interface QueueStorage {
    fun read(key: String): String?
    fun write(key: String, value: String?)
}

/**
 * Lokale Warteschlange fuer Antworten je Pruefung: Antworten werden zuerst abgelegt und erst nach erfolgreichem
 * Senden entfernt, damit nichts verloren geht, wenn das Netz fehlt oder die App beendet wird.
 */
class AttemptQueue(private val storage: QueueStorage) {
    private fun key(examId: String) = "attempts_$examId"
    private val serializer = ListSerializer(AttemptRequest.serializer())

    fun pending(examId: String): List<AttemptRequest> =
        storage.read(key(examId))?.let { runCatching { AppJson.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    fun add(examId: String, attempt: AttemptRequest) {
        val list = pending(examId)
        if (attempt.clientId != null && list.any { it.clientId == attempt.clientId }) return // gleiche Antwort nie doppelt vormerken
        save(examId, list + attempt)
    }

    /** Entfernt die ersten [count] Eintraege (die gerade erfolgreich gesendeten); spaeter Hinzugekommene bleiben. */
    fun removeSent(examId: String, count: Int) {
        save(examId, pending(examId).drop(count))
    }

    private fun save(examId: String, list: List<AttemptRequest>) {
        storage.write(key(examId), if (list.isEmpty()) null else AppJson.encodeToString(serializer, list))
    }
}
