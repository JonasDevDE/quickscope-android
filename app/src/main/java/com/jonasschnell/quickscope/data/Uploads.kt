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

/**
 * Verkleinert ein Foto vor dem Upload: laengste Kante hoechstens 2560 px, JPEG 85, EXIF-Ausrichtung wird angewendet. Dokumente (PDF, Office, Text,
 * Audio, Video), GIFs und schon kleine Bilder bleiben unveraendert; bei jedem Problem wird das Original genommen. Die Kopie liegt im Cache.
 */
fun compressForUpload(context: Context, file: UploadFile): UploadFile {
    if (!file.mime.startsWith("image/") || file.mime == "image/gif") return file
    val resolver = context.contentResolver
    return try {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(file.uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0 || !com.jonasschnell.quickscope.logic.needsImageCompression(file.mime, file.size, w, h)) return file
        val orientation = resolver.openInputStream(file.uri)?.use {
            android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)
        } ?: android.media.ExifInterface.ORIENTATION_NORMAL
        var sample = 1
        while (maxOf(w, h) / (sample * 2) >= com.jonasschnell.quickscope.logic.UPLOAD_MAX_EDGE) sample *= 2
        val decoded = resolver.openInputStream(file.uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return file
        val (nw, nh) = com.jonasschnell.quickscope.logic.scaledSize(decoded.width, decoded.height)
        val scaled = if (nw != decoded.width || nh != decoded.height) android.graphics.Bitmap.createScaledBitmap(decoded, nw, nh, true) else decoded
        val matrix = android.graphics.Matrix().apply {
            when (orientation) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                android.media.ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
                android.media.ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(270f); postScale(-1f, 1f) }
            }
        }
        val oriented = if (matrix.isIdentity) scaled else android.graphics.Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, matrix, true)
        val dir = java.io.File(context.cacheDir, "uploads").apply { mkdirs() }
        val out = java.io.File(dir, "u_${System.nanoTime()}.jpg")
        out.outputStream().use { oriented.compress(android.graphics.Bitmap.CompressFormat.JPEG, com.jonasschnell.quickscope.logic.UPLOAD_JPEG_QUALITY, it) }
        val name = file.name.substringBeforeLast('.', file.name) + ".jpg"
        UploadFile(Uri.fromFile(out), name, "image/jpeg", out.length())
    } catch (_: Exception) {
        file
    } catch (_: OutOfMemoryError) {
        file
    }
}

/** Temporaere Kopien aus [compressForUpload] wieder loeschen (nur Dateien im eigenen Upload-Cache). */
fun cleanupUploadCopies(files: List<UploadFile>) {
    files.forEach { f -> if (f.uri.scheme == "file") f.uri.path?.let { p -> if (p.contains("/uploads/")) java.io.File(p).delete() } }
}
