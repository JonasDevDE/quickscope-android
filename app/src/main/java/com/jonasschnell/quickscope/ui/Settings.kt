package com.jonasschnell.quickscope.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jonasschnell.quickscope.AppViewModel
import com.jonasschnell.quickscope.BuildConfig
import com.jonasschnell.quickscope.UiState
import com.jonasschnell.quickscope.data.UpdateStatus
import com.jonasschnell.quickscope.ui.theme.Accent
import com.jonasschnell.quickscope.ui.theme.Card
import com.jonasschnell.quickscope.ui.theme.CardHigh
import com.jonasschnell.quickscope.ui.theme.Danger
import com.jonasschnell.quickscope.ui.theme.Muted

private enum class SettingsPage { Sound, Account }

/**
 * Einstellungen, radikal einfach: fuenf grosse Zeilen (Lernen, Ton, Profil, Prüfung, Konto), jede oeffnet eine kleine Seite.
 * Lernen/Prüfung/Profil nutzen die bestehenden Seiten; Ton und Konto liegen hier. Gespeichert wird automatisch.
 */
@Composable
fun SettingsMain(
    state: UiState, vm: AppViewModel, onProfile: () -> Unit, onExam: (String, ExamPage) -> Unit, onDismiss: () -> Unit,
) {
    var page by remember { mutableStateOf<SettingsPage?>(null) }
    val me = state.me
    val exam = state.activeExam
    when (page) {
        null -> QuickscopeSheet(onDismiss, "Einstellungen") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                UserAvatar(me?.pictureUrl ?: vm.signInPictureUrl, me?.name ?: me?.email ?: "?", 48.dp)
                Column(Modifier.padding(start = 14.dp)) {
                    Text(me?.name ?: me?.email ?: " ", maxLines = 1, style = MaterialTheme.typography.titleMedium)
                    if (me?.name != null && me.email != null) Text(me.email, maxLines = 1, color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (exam != null) SettingsRow("Lernen", null, { onExam(exam.id, ExamPage.Learn) }) { Chevron("${exam.dailyMinutes} min") }
            SettingsRow("Ton", null, { page = SettingsPage.Sound }) { Chevron(if (vm.audioEnabled) "An" else "Aus") }
            SettingsRow("Profil", null, onProfile) { Chevron(null) }
            if (exam != null) SettingsRow("Prüfung", null, { onExam(exam.id, ExamPage.Exam) }) { Chevron(null) }
            SettingsRow("Konto", null, { page = SettingsPage.Account }) { Chevron(null) }
        }
        SettingsPage.Sound -> SoundPage(vm) { page = null }
        SettingsPage.Account -> AccountPage(state, vm) { page = null }
    }
}

@Composable
private fun Chevron(value: String?) {
    Text((value?.let { "$it  " } ?: "") + "›", color = Muted, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun SoundPage(vm: AppViewModel, onDismiss: () -> Unit) {
    var on by remember { mutableStateOf(vm.audioEnabled) }
    val saved = AutoSave(on, true, instantKey = on) { v, fail -> vm.setAudioEnabled(v, fail) }
    QuickscopeSheet(onDismiss, "Ton", status = if (saved) "Gespeichert" else null) {
        SettingsRow("Vorlesen", null, { on = !on }) {
            Switch(
                checked = on, onCheckedChange = { on = it },
                colors = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = Color.White, uncheckedTrackColor = CardHigh, uncheckedThumbColor = Muted, uncheckedBorderColor = CardHigh),
            )
        }
    }
}

@Composable
private fun AccountPage(state: UiState, vm: AppViewModel, onDismiss: () -> Unit) {
    var accountStep by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportTo(uri) { u ->
            val send = Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    QuickscopeSheet(onDismiss, "Konto") {
        SettingsRow("Meine Daten exportieren", null, { exportLauncher.launch("quickscope-export.json") }) {}
        SettingsRow("Datenschutzerklärung", null, { openLegal(context, privacyUrl()) }) {}
        SettingsRow("AGB", null, { openLegal(context, termsUrl()) }) {}
        UpdateRow(state, vm)
        Column(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SecondaryButton("Abmelden", vm::signOut)
            HapticTextButton(onClick = { accountStep = 1 }) {
                Text("Konto löschen", color = Danger, style = MaterialTheme.typography.labelMedium)
            }
        }
    }

    if (accountStep == 1) {
        TickOnAppear()
        AlertDialog(
            onDismissRequest = { accountStep = 0 },
            containerColor = Card,
            titleContentColor = Color.White,
            textContentColor = Color(0xFFCFCFD4),
            title = { Text("Konto löschen?") },
            text = {
                LightSystemIcons()
                Text("Alle Daten werden endgültig gelöscht. Das kann nicht rückgängig gemacht werden.")
            },
            confirmButton = { HapticTextButton(onClick = { accountStep = 2 }) { Text("Weiter", color = Danger) } },
            dismissButton = { HapticTextButton(onClick = { accountStep = 0 }) { Text("Abbrechen", color = Color.White) } },
        )
    }
    if (accountStep == 2) {
        TickOnAppear()
        AlertDialog(
            onDismissRequest = { accountStep = 0 },
            containerColor = Card,
            titleContentColor = Color.White,
            textContentColor = Color(0xFFCFCFD4),
            title = { Text("Wirklich endgültig löschen?") },
            text = { LightSystemIcons(); Text("Letzte Bestätigung: Konto und alle zugehörigen Daten werden jetzt unwiderruflich gelöscht.") },
            confirmButton = { HapticTextButton(onClick = { accountStep = 0; vm.deleteAccount() }) { Text("Konto endgültig löschen", color = Danger) } },
            dismissButton = { HapticTextButton(onClick = { accountStep = 0 }) { Text("Abbrechen", color = Color.White) } },
        )
    }
}

internal fun privacyUrl() = com.jonasschnell.quickscope.logic.legalUrl(BuildConfig.BASE_URL, com.jonasschnell.quickscope.logic.LEGAL_PRIVACY, BuildConfig.LEGAL_PRIVACY_URL)
internal fun termsUrl() = com.jonasschnell.quickscope.logic.legalUrl(BuildConfig.BASE_URL, com.jonasschnell.quickscope.logic.LEGAL_TERMS, BuildConfig.LEGAL_TERMS_URL)

/** Rechtliche Seite im Browser oeffnen (kein WebView). */
internal fun openLegal(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Ruhige Einstellungszeile: Titel, optionaler Untertitel, Aktion rechts. */
@Composable
internal fun SettingsRow(title: String, subtitle: String?, onClick: () -> Unit, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Card).hapticClickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        trailing()
    }
}

@Composable
internal fun UpdateRow(state: UiState, vm: AppViewModel) {
    val context = LocalContext.current
    val update = state.update
    val status = when (update) {
        is UpdateStatus.Preparing -> "Wird vorbereitet" + (update.progress?.let { " · ${(it * 100).toInt()} %" } ?: "")
        is UpdateStatus.Ready -> if (update.needsConfirm) "Update ${update.versionName} bereit" else "Update ${update.versionName} wird beim Schließen installiert"
        UpdateStatus.Installing -> "Wird installiert"
        is UpdateStatus.Error -> update.message
        else -> "Aktuell"
    }
    val busy = update is UpdateStatus.Preparing || update == UpdateStatus.Installing
    val ready = update is UpdateStatus.Ready && state.canInstallUpdates
    val needsPermission = !state.canInstallUpdates
    SettingsRow(
        title = "Version ${BuildConfig.VERSION_NAME}",
        subtitle = if (needsPermission) "$status · „Apps installieren“ erlauben, damit Updates im Hintergrund laufen" else status,
        onClick = {
            when {
                needsPermission -> context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
                )
                ready -> vm.installUpdate()
                !busy -> vm.checkUpdatesManually()
            }
        },
    ) {
        Text(
            when {
                needsPermission -> "Erlauben"
                ready -> "Installieren"
                busy -> ""
                else -> "Suchen"
            },
            color = Accent,
            style = MaterialTheme.typography.labelMedium,
        )
    }
    if (update is UpdateStatus.Preparing && update.progress != null) {
        LinearProgressIndicator(
            progress = { update.progress },
            color = Accent,
            trackColor = CardHigh,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }
}
