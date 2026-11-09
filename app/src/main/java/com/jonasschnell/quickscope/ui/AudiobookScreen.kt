package com.jonasschnell.quickscope.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jonasschnell.quickscope.audio.AudiobookViewModel
import com.jonasschnell.quickscope.audio.BookPhase
import com.jonasschnell.quickscope.audio.BookUi
import com.jonasschnell.quickscope.logic.EXTRA_MINUTES
import com.jonasschnell.quickscope.logic.activeKeyPoint
import com.jonasschnell.quickscope.logic.showMnemonic
import com.jonasschnell.quickscope.ui.theme.Accent
import com.jonasschnell.quickscope.ui.theme.Black
import com.jonasschnell.quickscope.ui.theme.Muted

/**
 * Hoerbuch im Vollbild, ein Zweck: Kapitel klein, Lektion gross, die Stichworte werden der Reihe nach hervorgehoben.
 * Es laeuft von selbst weiter (auch bei ausgeschaltetem Bildschirm, ueber den Dienst); drei grosse Knoepfe: Zurueck, Pause, Weiter.
 */
@Composable
fun AudiobookScreen(examId: String, onQuestions: () -> Unit, onClose: () -> Unit) {
    val vm: AudiobookViewModel = viewModel()
    val s by vm.state.collectAsStateWithLifecycle()
    // Ab Android 13 fragt die App einmal nach der Benachrichtigung (Steuerung auf dem Sperrbildschirm); Abbruch blockiert nichts.
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(examId) {
        if (Build.VERSION.SDK_INT >= 33) notify.launch(Manifest.permission.POST_NOTIFICATIONS)
        vm.start(examId, null) // Standard: Rest des heutigen Budgets
    }
    val close = { vm.close(); onClose() }
    BackHandler { close() }
    Column(Modifier.fillMaxSize().background(Black).statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("✕", style = MaterialTheme.typography.titleLarge, color = Muted, modifier = Modifier.hapticClickable(onClick = close).padding(horizontal = 8.dp, vertical = 4.dp))
            Text(s.chapterLabel, color = Muted, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }
        when (s.phase) {
            BookPhase.Playing -> PlayingView(s, vm)
            BookPhase.Finished -> EndView("Fertig. Jetzt Fragen dazu?", "Zu den Fragen", { vm.close(); onQuestions() }, "Schließen", close)
            BookPhase.Empty -> EndView(s.message ?: "Nichts Neues zu hören.", "Mehr zuhören", { vm.start(examId, EXTRA_MINUTES, repeat = true) }, "Schließen", close)
            BookPhase.Error -> EndView(s.message ?: "Das hat nicht geklappt", "Nochmal", { vm.start(examId, null) }, "Schließen", close)
            else -> {
                SkeletonCard(70.dp)
                Spacer(Modifier.height(12.dp))
                SkeletonCard(110.dp)
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.PlayingView(s: BookUi, vm: AudiobookViewModel) {
    val active = activeKeyPoint(s.keyPoints.size, s.positionMs, s.durationMs)
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Spacer(Modifier.height(16.dp))
        Text(s.chapterTitle, color = Muted, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        Text(s.title, style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold))
        s.keyPoints.forEachIndexed { i, point ->
            Text(
                "• $point", style = MaterialTheme.typography.titleMedium,
                color = if (i == active) Accent else Color.White.copy(alpha = 0.7f),
            )
        }
        // Platz fuer die Eselsbruecke ist immer reserviert; sie erscheint erst gegen Ende der Lektion
        Text(
            if (showMnemonic(s.positionMs, s.durationMs)) s.mnemonic.orEmpty() else "", color = Accent, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth().height(24.dp), maxLines = 1,
        )
        if (s.unavailable) {
            Text("Audio gerade nicht verfügbar", color = Muted, style = MaterialTheme.typography.bodySmall)
            HapticTextButton(onClick = vm::retryPlayback) { Text("Nochmal", color = Accent, style = MaterialTheme.typography.labelLarge) }
        }
    }
    GlowProgress(if (s.count > 0) (s.index + (if (s.durationMs > 0) (s.positionMs.toFloat() / s.durationMs).coerceIn(0f, 1f) else 0f)) / s.count else 0f, Modifier.fillMaxWidth())
    Text("Lektion ${s.index + 1} von ${s.count}", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) { SecondaryButton("Zurück", vm::previous) }
        Column(Modifier.weight(1.3f)) { PrimaryButton(if (s.playing) "Pause" else "Abspielen", vm::playPause) }
        Column(Modifier.weight(1f)) { SecondaryButton("Weiter", vm::next) }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.EndView(text: String, primary: String, onPrimary: () -> Unit, secondary: String, onSecondary: () -> Unit) {
    Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        LearnMark(56.dp)
        Spacer(Modifier.height(16.dp))
        Text(text, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        PrimaryButton(primary, onPrimary)
        Spacer(Modifier.height(10.dp))
        SecondaryButton(secondary, onSecondary)
    }
}
