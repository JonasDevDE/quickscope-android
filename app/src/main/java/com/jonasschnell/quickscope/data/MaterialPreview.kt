package com.jonasschnell.quickscope.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.jonasschnell.quickscope.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Original eines Materials fuer die Vorschau: wird mit dem Anmelde-Header in den Cache geladen
 * (der System-Viewer kennt unser Token nicht) und ueber den FileProvider geoeffnet.
 */
class MaterialPreview(private val context: Context, private val client: OkHttpClient) {
    private val dir get() = File(context.cacheDir, "previews")

    suspend fun open(examId: String, material: Material) {
        val (file, mime) = download(examId, material)
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private suspend fun download(examId: String, material: Material): Pair<File, String> = withContext(Dispatchers.IO) {
        dir.deleteRecursively()
        dir.mkdirs()
        val request = Request.Builder().url("${BuildConfig.BASE_URL}api/exams/$examId/materials/${material.id}/file").build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val mime = resp.header("Content-Type")?.substringBefore(';')?.trim() ?: "application/octet-stream"
            val file = File(dir, safeName(material.name, mime))
            resp.body.byteStream().use { input -> file.outputStream().use { input.copyTo(it) } }
            file to mime
        }
    }

    private fun safeName(name: String, mime: String): String {
        val base = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "material" }.take(60)
        val ext = when {
            mime.startsWith("image/jpeg") -> ".jpg"
            mime.startsWith("image/png") -> ".png"
            mime == "application/pdf" -> ".pdf"
            else -> ""
        }
        return if (ext.isNotEmpty() && !base.lowercase().endsWith(ext)) base + ext else base
    }
}
