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
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

/**
 * Avatar-Sheet, radikal einfach: Avatar, Name, E-Mail klein und hoechstens drei grosse Zeilen (Über mich, Einstellungen, Abmelden).
 * Alles andere liegt unter Einstellungen.
 */
@Composable
fun MeSheet(state: UiState, vm: AppViewModel, onAbout: () -> Unit, onSettings: () -> Unit, onDismiss: () -> Unit) {
    val me = state.me
    QuickscopeSheet(onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            UserAvatar(me?.pictureUrl ?: vm.signInPictureUrl, me?.name ?: me?.email ?: "?", 56.dp)
            Column(Modifier.padding(start = 14.dp)) {
                Text(me?.name ?: me?.email ?: " ", maxLines = 1, style = MaterialTheme.typography.titleMedium)
                if (me?.name != null && me.email != null) Text(me.email, maxLines = 1, color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        BigSettingsRow("Über mich", onAbout)
        BigSettingsRow("Einstellungen", onSettings)
        BigSettingsRow("Abmelden") { vm.signOut() }
    }
}

@Composable
private fun BigSettingsRow(title: String, onClick: () -> Unit) {
    RowCard(onClick) { Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium) }
}

private enum class SettingsPage { Sound, Account }

/** Einstellungen (app-weit): nur Ton und Konto. Einstellungen eines Themas gibt es nur ueber das Drei-Punkte-Menue in "Meine Themen". */
@Composable
fun SettingsMain(state: UiState, vm: AppViewModel, onLegal: (com.jonasschnell.quickscope.logic.LegalPage) -> Unit, onDismiss: () -> Unit) {
    var page by remember { mutableStateOf<SettingsPage?>(null) }
    when (page) {
        null -> QuickscopeSheet(onDismiss, "Einstellungen") {
            SettingsRow("Ton", null, { page = SettingsPage.Sound }) { Chevron(if (vm.audioEnabled) "An" else "Aus") }
            SettingsRow("Konto", null, { page = SettingsPage.Account }) { Chevron(null) }
        }
        SettingsPage.Sound -> SoundPage(vm) { page = null }
        SettingsPage.Account -> AccountPage(state, vm, onLegal) { page = null }
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
    QuickscopeSheet(onDismiss, "Ton", status = null) {
        SettingsRow("Vorlesen", null, { on = !on }) {
            Switch(
                checked = on, onCheckedChange = { on = it },
                colors = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = Color.Black, checkedBorderColor = Accent, uncheckedTrackColor = Color(0xFF3E3E42), uncheckedThumbColor = Color(0xFFD0D0D4), uncheckedBorderColor = Color(0xFF8A8A90)),
            )
        }
    }
}

@Composable
private fun AccountPage(state: UiState, vm: AppViewModel, onLegal: (com.jonasschnell.quickscope.logic.LegalPage) -> Unit, onDismiss: () -> Unit) {
    var accountStep by remember { mutableIntStateOf(0) }
    val online by vm.online.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportTo(uri)
    }
    QuickscopeSheet(onDismiss, "Konto") {
        state.activeExam?.let { e -> vm.offlineDays(e.id) }?.let { d ->
            Text("Offline bereit: $d ${if (d == 1) "Tag" else "Tage"}", color = Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 4.dp))
        }
        SettingsRow("Meine Daten exportieren", null, { if (online) exportLauncher.launch("quickscope-export.json") else vm.needInternet() }) {}
        UpdateRow(state, vm)
        com.jonasschnell.quickscope.logic.LegalPage.entries.forEach { lp -> SettingsRow(lp.title, null, { onLegal(lp) }) {} }
        DangerZone {
            DangerRow("Konto löschen") { if (online) accountStep = 1 else vm.needInternet() }
        }
    }

    if (accountStep == 1) {
        TickOnAppear()
        AlertDialog(
            onDismissRequest = { accountStep = 0 },
            containerColor = com.jonasschnell.quickscope.ui.theme.CardHigh,
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
            containerColor = com.jonasschnell.quickscope.ui.theme.CardHigh,
            titleContentColor = Color.White,
            textContentColor = Color(0xFFCFCFD4),
            title = { Text("Wirklich endgültig löschen?") },
            text = { LightSystemIcons(); Text("Letzte Bestätigung: Konto und alle zugehörigen Daten werden jetzt unwiderruflich gelöscht.") },
            confirmButton = { HapticTextButton(onClick = { accountStep = 0; vm.deleteAccount() }) { Text("Konto endgültig löschen", color = Danger) } },
            dismissButton = { HapticTextButton(onClick = { accountStep = 0 }) { Text("Abbrechen", color = Color.White) } },
        )
    }
}

/** Einstellungszeile: Titel, optionaler Untertitel, Aktion rechts; gemeinsame erhoehte Flaeche mit Rand und Pressed-State. */
@Composable
internal fun SettingsRow(title: String, subtitle: String?, onClick: () -> Unit, trailing: @Composable () -> Unit) {
    RowCard(onClick) {
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
