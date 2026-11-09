package com.jonasschnell.quickscope.ui

import android.graphics.drawable.ColorDrawable
import android.view.WindowManager
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jonasschnell.quickscope.AppViewModel
import com.jonasschnell.quickscope.BuildConfig
import com.jonasschnell.quickscope.UiState
import com.jonasschnell.quickscope.data.UpdateStatus
import com.jonasschnell.quickscope.ui.theme.Accent
import com.jonasschnell.quickscope.ui.theme.Black
import com.jonasschnell.quickscope.ui.theme.CardHigh
import com.jonasschnell.quickscope.ui.theme.Danger
import com.jonasschnell.quickscope.ui.theme.Muted

/** Pflicht-Update: Vollbild, opak, nicht schliessbar und fuer Touches undurchlaessig, solange ein neueres Release erzwungen/bekannt ist. */
@Composable
fun UpdateScreen(state: UiState, vm: AppViewModel) {
    val update = state.update
    val wants = state.updateForced || update is UpdateStatus.Preparing || update is UpdateStatus.Ready ||
        update == UpdateStatus.Installing || (update is UpdateStatus.Error && update.blocking)
    // Einmal sichtbar, bleibt der Dialog bestehen (kein Neuaufbau/Flackern bei Statuswechseln);
    // nur wenn der Server sicher kein Update mehr verlangt (Idle/UpToDate ohne Zwang), faellt er weg.
    var latched by remember { mutableStateOf(false) }
    if (wants) latched = true
    else if (update == UpdateStatus.Idle || update == UpdateStatus.UpToDate) latched = false
    if (!latched && !wants) return
    val context = LocalContext.current
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        TickOnAppear()
        LightSystemIcons()
        BackHandler {}
        val view = LocalView.current
        SideEffect {
            val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
            window.setWindowAnimations(0)
            window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.BLACK))
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.setDimAmount(0f)
        }
        Box(
            Modifier.fillMaxSize().background(Black).pointerInput(Unit) {
                awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
            },
        ) {
            val kind = when (update) {
                is UpdateStatus.Error -> "error"
                is UpdateStatus.Ready -> "ready"
                else -> "busy"
            }
            Crossfade(kind, animationSpec = tween(150), label = "update") { _ -> UpdateContent(state, vm, context) }
        }
    }
}

@Composable
private fun UpdateContent(state: UiState, vm: AppViewModel, context: android.content.Context) {
    val update = state.update
    val name: String?
    val notes: String?
    when (update) {
        is UpdateStatus.Preparing -> { name = update.versionName; notes = update.notes }
        is UpdateStatus.Ready -> { name = update.versionName; notes = update.notes }
        else -> { name = null; notes = null }
    }
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 28.dp).padding(top = 24.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            LearnMark(140.dp)
            Spacer(Modifier.height(16.dp))
            Text("Update erforderlich", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            if (name != null) Text("Version $name", color = Accent, style = MaterialTheme.typography.titleMedium)
            if (update is UpdateStatus.Error) {
                Spacer(Modifier.height(12.dp))
                Text(update.message, color = Danger, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
            if (!notes.isNullOrBlank()) {
                Spacer(Modifier.height(24.dp))
                Text(
                    notes.trim(),
                    color = Muted,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(CardHigh).padding(16.dp),
                )
            }
        }
        if (update !is UpdateStatus.Ready && update !is UpdateStatus.Error) {
            val progress = (update as? UpdateStatus.Preparing)?.progress
            if (progress != null && progress > 0f) {
                LinearProgressIndicator(progress = { progress }, color = Accent, trackColor = CardHigh, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp))
            } else {
                LinearProgressIndicator(color = Accent, trackColor = CardHigh, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp))
            }
        }
        when {
            update is UpdateStatus.Error -> {
                PrimaryButton("Erneut versuchen", { vm.retryUpdate() })
                Text(
                    "Im Browser herunterladen",
                    color = Muted,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(20.dp)).hapticClickable {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("${BuildConfig.BASE_URL}api/app/latest/apk")))
                        }
                    }.padding(horizontal = 24.dp, vertical = 12.dp),
                )
            }
            update == UpdateStatus.Installing -> PrimaryButton("Wird installiert", {}, enabled = false)
            update == UpdateStatus.Idle || update == UpdateStatus.UpToDate -> PrimaryButton("Update wird gesucht…", {}, enabled = false)
            !state.canInstallUpdates -> PrimaryButton("Erlauben", {
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            })
            else -> {
                val ready = update is UpdateStatus.Ready
                PrimaryButton(if (ready) "Jetzt aktualisieren" else "Wird geladen", { vm.installUpdate() }, enabled = ready)
            }
        }
    }
}
