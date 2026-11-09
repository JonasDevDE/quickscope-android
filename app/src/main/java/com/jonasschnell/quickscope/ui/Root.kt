package com.jonasschnell.quickscope.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jonasschnell.quickscope.AppViewModel
import com.jonasschnell.quickscope.Phase
import com.jonasschnell.quickscope.SessionPhase
import com.jonasschnell.quickscope.SessionViewModel
import com.jonasschnell.quickscope.UiState
import com.jonasschnell.quickscope.logic.EXTRA_MINUTES
import com.jonasschnell.quickscope.logic.scoreOrNull
import com.jonasschnell.quickscope.ui.theme.Accent
import com.jonasschnell.quickscope.ui.theme.Black
import com.jonasschnell.quickscope.ui.theme.CardHigh
import com.jonasschnell.quickscope.ui.theme.Danger
import com.jonasschnell.quickscope.ui.theme.Muted
import kotlinx.coroutines.launch

fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/** Was gerade ueber der Startseite liegt. */
private sealed interface Overlay {
    data object Me : Overlay
    data object About : Overlay
    data object SettingsMain : Overlay
    data object Exams : Overlay
    data object NewExam : Overlay
    data class Settings(val examId: String, val start: ExamSetting? = null) : Overlay
    data class Cheat(val examId: String) : Overlay
    data class More(val examId: String) : Overlay
}

@Composable
fun QuickscopeRoot(vm: AppViewModel, sessionVm: SessionViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    LaunchedEffect(haptics) { vm.hapticEvents.collect { haptics.perform(it) } }
    CompositionLocalProvider(LocalHaptics provides haptics) { QuickscopeRootContent(vm, sessionVm, state) }
}

@Composable
private fun QuickscopeRootContent(vm: AppViewModel, sessionVm: SessionViewModel, state: UiState) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    var legal by remember { mutableStateOf<com.jonasschnell.quickscope.logic.LegalPage?>(null) }
    val session by sessionVm.state.collectAsStateWithLifecycle()
    val forced = state.updateForced
    val online by vm.online.collectAsStateWithLifecycle()

    LaunchedEffect(forced) {
        if (forced) {
            overlay = null
            snackbar.currentSnackbarData?.dismiss()
        }
    }
    // Einmalig nach dem Login: Erlaubnis "Apps installieren" anfragen, damit Updates unbemerkt laufen.
    val appContext = LocalContext.current
    LaunchedEffect(state.phase) {
        if (state.phase == Phase.Ready && vm.shouldAskInstallPermission()) {
            appContext.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${appContext.packageName}")))
        }
    }
    LaunchedEffect(Unit) {
        vm.messages.collect { message ->
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                val result = snackbar.showSnackbar(
                    message.text, actionLabel = message.actionLabel,
                    duration = if (message.actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short,
                )
                if (result == SnackbarResult.ActionPerformed) message.onAction?.invoke()
            }
        }
    }

    // Prognose nach der Runde uebernehmen
    LaunchedEffect(session.forecastAfter) {
        session.forecastAfter?.let { vm.applyForecast(session.examId, it) }
    }
    val ready = state.phase == Phase.Ready && !forced
    val onboarding = ready && state.onboarding == com.jonasschnell.quickscope.logic.OnboardingGate.Needed
    val profileCheckFailed = ready && state.onboarding == com.jonasschnell.quickscope.logic.OnboardingGate.Failed
    val noExams = ready && state.examsLoaded && state.exams.isEmpty()

    Scaffold(
        containerColor = Black,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = {
            SnackbarHost(snackbar, Modifier.navigationBarsPadding()) { data ->
                Snackbar(data, containerColor = CardHigh, contentColor = Color.White, actionColor = Accent, shape = MaterialTheme.shapes.large)
            }
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            if (state.phase == Phase.Failed) {
                FailedView(state.failure, vm::bootstrap)
            } else {
                Header(state, vm, onExams = { overlay = Overlay.Exams }, onMe = { overlay = Overlay.Me })
                if (state.serverStarting) {
                    Text(
                        "Der Server startet gerade neu. Das dauert meist ein bis zwei Minuten.",
                        color = Muted, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
                if (noExams) {
                    NewExamFlow(vm, onClose = null)
                } else {
                    HomePage(
                        state, loading = !ready || !state.examsLoaded,
                        onStart = { exam, _ -> sessionVm.start(exam.id, null, state.activeForecast?.scoreOrNull()) },
                        onOpenMore = { overlay = Overlay.More(it.id) },
                        onMore = { exam -> sessionVm.start(exam.id, EXTRA_MINUTES, state.activeForecast?.scoreOrNull()) },
                        onMaterial = { if (online) overlay = Overlay.Settings(it.id, ExamSetting.Material) else vm.needInternet() },
                        onCheatsheet = { overlay = Overlay.Cheat(it.id) },
                        onRetry = vm::refreshExamsNow,
                        onCancelUpload = vm::cancelUpload,
                        onRetryUpload = vm::retryUpload,
                    )
                }
            }
        }
    }

    if (onboarding) OnboardingFlow(vm, state.me?.name)
    if (profileCheckFailed) {
        // Netzfehler beim Profil: nicht blockieren, nochmal versuchen oder spaeter
        Column(Modifier.fillMaxSize().background(Black).blockTouches().statusBarsPadding().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            LearnMark(56.dp)
            Spacer(Modifier.size(16.dp))
            Text("Gerade nicht erreichbar", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.size(24.dp))
            PrimaryButton("Nochmal", vm::retryOnboardingCheck)
            Spacer(Modifier.size(10.dp))
            SecondaryButton("Später", vm::skipOnboardingCheck)
        }
    }
    if (forced) {
        // Alle Dialoge/Sheets sind weg; nur der Update-Screen bleibt (unten).
    } else if (state.phase == Phase.NeedsLogin) {
        LoginSheet(state, vm, online, onLegal = { legal = it })
    } else {
        val dismiss = { overlay = null }
        when (val o = overlay) {
            null -> Unit
            Overlay.Me -> MeSheet(state, vm, onAbout = { if (online) overlay = Overlay.About else vm.needInternet() }, onSettings = { overlay = Overlay.SettingsMain }, onDismiss = dismiss)
            Overlay.SettingsMain -> SettingsMain(state, vm, onLegal = { overlay = null; legal = it }, onDismiss = dismiss)
            Overlay.About -> AboutScreen(state, vm, onClose = dismiss)
            Overlay.Exams -> ExamsSheet(state, vm, onNew = { if (online) overlay = Overlay.NewExam else vm.needInternet() }, onEdit = { overlay = Overlay.Settings(it) },
                onMaterial = { if (online) overlay = Overlay.Settings(it, ExamSetting.Material) else vm.needInternet() },
                onDelete = { overlay = Overlay.Settings(it, ExamSetting.Delete) }, onDismiss = dismiss)
            Overlay.NewExam -> Box(Modifier.fillMaxSize().statusBarsPadding()) { NewExamFlow(vm, onClose = dismiss) }
            is Overlay.Settings -> ExamSettingsSheet(state, vm, o.examId, o.start, dismiss)
            is Overlay.Cheat -> CheatsheetSheet(vm, o.examId, dismiss)
            is Overlay.More -> {
                val exam = state.exams.firstOrNull { it.id == o.examId }
                val hasLessons = remember(o.examId, online, state.forecast) {
                    if (online) (state.activeForecast?.plan?.chaptersTotal ?: 0) > 0 else vm.offlineHasLessons(o.examId)
                }
                if (exam != null) MoreSheet(
                    canUnderstand = com.jonasschnell.quickscope.logic.understandVisible(hasLessons),
                    onTest = { sessionVm.start(exam.id, null, state.activeForecast?.scoreOrNull(), questionsOnly = true) },
                    onUnderstand = { sessionVm.start(exam.id, null, state.activeForecast?.scoreOrNull(), lessonsOnly = true) },
                    onDismiss = dismiss,
                )
            }
        }
        if (session.phase != SessionPhase.Closed) {
            val exam = state.exams.firstOrNull { it.id == session.examId }
            SessionScreen(
                sessionVm,
                onRetry = { exam?.let { e -> sessionVm.start(e.id, null, session.scoreBefore) } },
                onMore = { exam?.let { e -> sessionVm.start(e.id, EXTRA_MINUTES, session.scoreBefore) } },
                onClose = { sessionVm.close(); vm.refreshExamsNow() },
            )
        }
    }
    legal?.let { LegalScreen(it) { legal = null } }
    UpdateScreen(state, vm)
}

@Composable
private fun LoginSheet(state: UiState, vm: AppViewModel, online: Boolean, onLegal: (com.jonasschnell.quickscope.logic.LegalPage) -> Unit) {
    val activity = LocalContext.current.findActivity()
    val error = state.loginError ?: if (vm.clientIdMissing) "Google-Client-ID fehlt (GOOGLE_WEB_CLIENT_ID in local.properties). Die Anmeldung ist erst nach dem nächsten Build mit ID möglich." else null
    QuickscopeSheet(onDismiss = {}, dismissable = false) {
        Spacer(Modifier.height(12.dp))
        LearnMark(56.dp)
        Text("Willkommen bei Quickscope", style = MaterialTheme.typography.displaySmall)
        Text("Melde dich an, um loszulegen.", color = Muted)
        Spacer(Modifier.height(12.dp))
        if (error != null) Text(error, color = Danger, style = MaterialTheme.typography.bodyMedium)
        PrimaryButton(
            if (state.loginBusy) "Einen Moment" else "Mit Google anmelden",
            { if (online) activity?.let(vm::signIn) else vm.needInternet() },
            enabled = !state.loginBusy && activity != null,
        )
        Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            com.jonasschnell.quickscope.logic.LegalPage.entries.forEach { lp ->
                RowCard({ onLegal(lp) }, minHeight = 48.dp) { Text(lp.title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge) }
            }
        }
    }
}

/** Kopf: Pruefungs-Pille links (oeffnet die Liste), eigenes Profilbild rechts (oeffnet das Sheet "Privat"). */
@Composable
private fun Header(state: UiState, vm: AppViewModel, onExams: () -> Unit, onMe: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExamPill(state.activeExam?.name ?: "Neues Thema", loading = state.phase != Phase.Ready || !state.examsLoaded, onClick = onExams, icon = state.activeExam?.icon)
        val me = state.me
        Box(Modifier.size(44.dp).clip(CircleShape).hapticClickable(onClick = onMe)) {
            UserAvatar(me?.pictureUrl ?: vm.signInPictureUrl, me?.name ?: me?.email ?: "?", 44.dp)
        }
    }
}

@Composable
private fun FailedView(message: String?, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        LearnMark(56.dp)
        Spacer(Modifier.size(16.dp))
        Text("Das hat nicht geklappt", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.size(8.dp))
        Text(message ?: "Unbekannter Fehler", color = Muted, textAlign = TextAlign.Center)
        Spacer(Modifier.size(28.dp))
        PrimaryButton("Erneut versuchen", onRetry)
    }
}
