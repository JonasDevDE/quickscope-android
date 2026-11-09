package com.jonasschnell.quickscope.logic

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

enum class UploadItemState { Waiting, Uploading, Done, Failed, Cancelled }

/** Eine Datei der Warteschlange mit Status; [error] ist ein ruhiger Klartext (Servermeldung, wenn es eine gibt). */
data class UploadItem(val name: String, val state: UploadItemState = UploadItemState.Waiting, val error: String? = null)

/** Ergebnis eines Durchlaufs: erledigte Dateien, Indizes der fehlgeschlagenen, Gesamtzahl. */
data class UploadSummary(val done: Int, val failed: List<Int>, val total: Int)

/** Bis zu so viele Versuche je Datei (der erste plus Wiederholungen mit Backoff), dann "Fehler" und weiter mit der naechsten. */
const val UPLOAD_MAX_ATTEMPTS = 3

/** Wiederholen bei Netzfehler, Zeitueberschreitung, 429 und 5xx; andere Fehler (z. B. zu gross, falsches Format) sofort als Fehler. */
fun uploadRetryable(e: Throwable): Boolean = when (e) {
    is retrofit2.HttpException -> e.code() == 408 || e.code() == 429 || e.code() >= 500
    is java.io.IOException -> true
    else -> false
}

/**
 * Laedt strikt eine Datei nach der anderen hoch (1 parallel), in der Reihenfolge der Liste; waehrend des Laufs angehaengte Dateien kommen
 * an die Reihe ([size] wird jedes Mal neu gefragt). Ein Fehler stoppt die anderen nicht: nach [maxAttempts] Versuchen (mit Backoff)
 * wird die Datei als Fehler gemeldet und die naechste folgt. Abbruch ([CancellationException]) beendet den Lauf.
 */
suspend fun <T> runUploadQueue(
    size: () -> Int,
    itemAt: (Int) -> T,
    retryable: (Throwable) -> Boolean = ::uploadRetryable,
    maxAttempts: Int = UPLOAD_MAX_ATTEMPTS,
    backoffMs: (Int) -> Long = { retryDelayMs(it) },
    wait: suspend (Long) -> Unit = { delay(it) },
    onState: (index: Int, state: UploadItemState, error: Throwable?) -> Unit = { _, _, _ -> },
    upload: suspend (index: Int, item: T) -> Unit,
): UploadSummary {
    var done = 0
    val failed = mutableListOf<Int>()
    var i = 0
    while (i < size()) {
        val item = itemAt(i)
        onState(i, UploadItemState.Uploading, null)
        var attempt = 1
        while (true) {
            try {
                upload(i, item)
                done++
                onState(i, UploadItemState.Done, null)
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (retryable(e) && attempt < maxAttempts) {
                    wait(backoffMs(attempt - 1))
                    attempt++
                    continue
                }
                failed += i
                onState(i, UploadItemState.Failed, e)
                break
            }
        }
        i++
    }
    return UploadSummary(done, failed, size())
}

/** "3 von 10" waehrend des Hochladens (laufende Datei von allen). */
fun uploadProgressText(current: Int, total: Int): String = "$current von $total"

/** Ruhige Zusammenfassung am Ende: "8 von 10 hochgeladen". */
fun uploadSummaryText(done: Int, total: Int): String = "$done von $total hochgeladen"

/** Fehlermeldungen: Servermeldung im Klartext, sonst ein neutraler Satz; nie eine feste Zahl wie "30 MB". */
fun uploadFailureText(serverMessage: String?, code: Int?, network: Boolean): String = when {
    !serverMessage.isNullOrBlank() -> serverMessage
    network -> "Kein Netz."
    code == 413 -> "Die Datei ist zu groß."
    code == 429 -> "Gerade zu viele Anfragen. Später nochmal."
    code != null && code >= 500 -> "Der Server ist gerade nicht erreichbar."
    else -> "Hochladen fehlgeschlagen."
}

/** Laengste Kante, auf die Fotos vor dem Upload verkleinert werden, und JPEG-Qualitaet. */
const val UPLOAD_MAX_EDGE = 2560
const val UPLOAD_JPEG_QUALITY = 85

/** Ab dieser Groesse werden Bilder auch dann neu kodiert, wenn die Kantenlaenge schon passt (Handyfotos mit 8-12 MB). */
const val UPLOAD_RECODE_BYTES = 1_500_000L

/** Neue Groesse bei laengster Kante <= [maxEdge], Seitenverhaeltnis bleibt; nie vergroessern. */
fun scaledSize(width: Int, height: Int, maxEdge: Int = UPLOAD_MAX_EDGE): Pair<Int, Int> {
    val longest = maxOf(width, height)
    if (longest <= maxEdge || longest <= 0) return width to height
    val f = maxEdge.toDouble() / longest
    return maxOf(1, Math.round(width * f).toInt()) to maxOf(1, Math.round(height * f).toInt())
}

/** Bilder (ausser GIF) werden verkleinert/neu kodiert, wenn sie zu gross sind (Kante oder Dateigroesse); Dokumente nie. */
fun needsImageCompression(mime: String, sizeBytes: Long, width: Int, height: Int): Boolean =
    mime.startsWith("image/") && mime != "image/gif" && (maxOf(width, height) > UPLOAD_MAX_EDGE || sizeBytes > UPLOAD_RECODE_BYTES || sizeBytes < 0)
