package com.jonasschnell.quickscope.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source

/** Eine Datei zum Hochladen: Quelle (content:// oder file://), Anzeigename und Typ. */
data class UploadFile(val uri: Uri, val name: String, val mime: String, val size: Long)

/** Was hochgeladen wird: ein Dokument ([scan] = false) oder mehrere Seiten als EIN Scan. */
data class UploadJob(val files: List<UploadFile>, val role: String, val scan: Boolean)

/** Liest Name, Typ und Groesse einer Auswahl, ohne den Inhalt zu laden. */
fun describeUri(context: Context, uri: Uri): UploadFile {
    val resolver = context.contentResolver
    var name = "Datei"
    var size = -1L
    runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) ?: name }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
            }
        }
    }
    if (uri.scheme == "file") uri.path?.let { p -> java.io.File(p).let { f -> name = f.name; size = f.length() } }
    val mime = resolver.getType(uri) ?: "application/octet-stream"
    return UploadFile(uri, name, mime, size)
}

/** Streamt den Inhalt direkt aus dem ContentResolver (nie komplett im Speicher) und meldet die gesendeten Bytes. */
class StreamingBody(
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val type: MediaType?,
    private val length: Long,
    private val onBytes: (Long) -> Unit,
) : RequestBody() {
    override fun contentType() = type
    override fun contentLength() = length

    override fun writeTo(sink: BufferedSink) {
        val input = resolver.openInputStream(uri) ?: throw java.io.IOException("Datei nicht lesbar")
        input.source().use { source ->
            val buffer = okio.Buffer()
            while (true) {
                val read = source.read(buffer, 64 * 1024L)
                if (read < 0) break
                sink.write(buffer, read)
                onBytes(read)
            }
        }
    }
}

/** Multipart-Teile fuer den Upload: `file` bzw. `files[]`, und `role`. */
fun buildParts(context: Context, job: UploadJob, onBytes: (Long) -> Unit): List<MultipartBody.Part> {
    val parts = mutableListOf<MultipartBody.Part>()
    val field = if (job.scan) "files[]" else "file"
    for (f in job.files) {
        val body = StreamingBody(context.contentResolver, f.uri, f.mime.toMediaTypeOrNull(), f.size, onBytes)
        parts += MultipartBody.Part.createFormData(field, f.name, body)
    }
    parts += MultipartBody.Part.createFormData("role", job.role)
    return parts
}

/** Gesamtgroesse in Byte fuer den Fortschritt; 0, wenn eine Groesse unbekannt ist. */
fun totalSize(job: UploadJob): Long = if (job.files.any { it.size < 0 }) 0 else job.files.sumOf { it.size }
