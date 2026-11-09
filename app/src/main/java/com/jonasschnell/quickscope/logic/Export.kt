package com.jonasschnell.quickscope.logic

import java.io.InputStream
import java.io.OutputStream

/** Streamt den Datenexport in die vom Nutzer gewaehlte Datei und liefert die Anzahl Bytes. */
fun copyExport(input: InputStream, out: OutputStream): Long {
    val n = input.copyTo(out)
    out.flush()
    return n
}

enum class ExportOutcome { Saved, Failed }

/** Gespeichert nur, wenn wirklich Daten geschrieben wurden; null (Fehler) oder 0 Bytes gelten als Fehlschlag. */
fun exportOutcome(bytes: Long?): ExportOutcome = if (bytes != null && bytes > 0) ExportOutcome.Saved else ExportOutcome.Failed

/** Fehlergrund fuer das Debug-Log (nie fuer den Nutzer): Klasse, HTTP-Status oder Meldung. */
fun exportFailureReason(e: Throwable): String = when (e) {
    is retrofit2.HttpException -> "HTTP ${e.code()} ${e.message()}"
    else -> "${e.javaClass.simpleName}: ${e.message}"
}
