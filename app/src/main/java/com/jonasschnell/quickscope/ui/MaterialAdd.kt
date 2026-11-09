package com.jonasschnell.quickscope.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.jonasschnell.quickscope.data.UploadFile
import com.jonasschnell.quickscope.data.UploadJob
import com.jonasschnell.quickscope.data.describeUri
import com.jonasschnell.quickscope.ui.theme.Card
import com.jonasschnell.quickscope.ui.theme.Muted
import java.io.File

/** Dateitypen der System-Dateiauswahl (Backend liest sie oder lehnt klar ab). */
private val FILE_TYPES = arrayOf(
    "application/pdf",
    "application/msword",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.ms-powerpoint",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "application/vnd.ms-excel",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.oasis.opendocument.text",
    "application/vnd.oasis.opendocument.presentation",
    "application/vnd.oasis.opendocument.spreadsheet",
    "application/rtf", "application/epub+zip",
    "text/*", "image/*", "audio/*", "video/*",
)

private class Pending(val files: List<UploadFile>, val scan: Boolean)

/**
 * Drei grosse Knoepfe zum Hinzufuegen von Material: Foto aufnehmen (mehrere Seiten, ein Scan),
 * Aus Galerie (mehrere Bilder, ein Scan), Dateien waehlen (jede Datei ein Material). Pro Auswahl wird einmal die Rolle gefragt.
 */
@Composable
fun MaterialAddButtons(onJobs: (List<UploadJob>) -> Unit) {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<Pending?>(null) }
    var pages by remember { mutableStateOf(listOf<UploadFile>()) }
    var askMore by remember { mutableStateOf(false) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri
        if (ok && uri != null) {
            pages = pages + describeUri(context, uri)
            askMore = true
        } else if (pages.isNotEmpty()) {
            askMore = true
        }
    }
    fun shoot() {
        val uri = newCameraUri(context)
        cameraUri = uri
        takePicture.launch(uri)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) shoot() }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        // jedes Bild einzeln hochladen (nicht als ein Scan buendeln)
        if (uris.isNotEmpty()) pending = Pending(uris.map { describeUri(context, it) }, scan = false)
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) pending = Pending(uris.map { describeUri(context, it) }, scan = false)
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PrimaryButton("Foto aufnehmen", {
            pages = emptyList()
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) shoot()
            else permission.launch(Manifest.permission.CAMERA)
        })
        SecondaryButton("Aus Galerie", { gallery.launch("image/*") })
        SecondaryButton("Dateien wählen", { files.launch(FILE_TYPES) })
    }

    if (askMore) {
        AlertDialog(
            onDismissRequest = { askMore = false; pages = emptyList() },
            containerColor = com.jonasschnell.quickscope.ui.theme.CardHigh,
            titleContentColor = Color.White,
            textContentColor = Muted,
            title = { Text("${pages.size} ${if (pages.size == 1) "Seite" else "Seiten"} aufgenommen") },
            text = { Text("Noch eine Seite für denselben Scan?") },
            confirmButton = { HapticTextButton(onClick = { askMore = false; shoot() }) { Text("Noch eine Seite", color = com.jonasschnell.quickscope.ui.theme.Accent) } },
            dismissButton = {
                HapticTextButton(onClick = { askMore = false; if (pages.isNotEmpty()) pending = Pending(pages, scan = true); pages = emptyList() }) {
                    Text("Fertig", color = Color.White)
                }
            },
        )
    }
    pending?.let { p ->
        RoleDialog(
            onPick = { role ->
                pending = null
                onJobs(if (p.scan) listOf(UploadJob(p.files, role, true)) else p.files.map { UploadJob(listOf(it), role, false) })
            },
            onDismiss = { pending = null },
        )
    }
}

/** Rolle des Materials abfragen: zwei grosse Knoepfe, ein Tipp. */
@Composable
private fun RoleDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    TickOnAppear()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = com.jonasschnell.quickscope.ui.theme.CardHigh,
        titleContentColor = Color.White,
        title = { Text("Was ist das?") },
        text = {
            LightSystemIcons()
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier) {
                PrimaryButton("Skript/Folien", { onPick("script") })
                PrimaryButton("Aufgaben/Altklausur", { onPick("exam") })
            }
        },
        confirmButton = {},
        dismissButton = { HapticTextButton(onClick = onDismiss) { Text("Abbrechen", color = Color.White) } },
    )
}

private fun newCameraUri(context: Context): Uri {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    val file = File(dir, "page_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
