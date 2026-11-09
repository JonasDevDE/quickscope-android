package com.jonasschnell.quickscope.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import com.jonasschnell.quickscope.UiState
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

@Composable
fun QuickscopeRoot(vm: AppViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    LaunchedEffect(haptics) { vm.hapticEvents.collect { haptics.perform(it) } }
    CompositionLocalProvider(LocalHaptics provides haptics) { QuickscopeRootContent(vm, state) }
}

@Composable
private fun QuickscopeRootContent(vm: AppViewModel, state: UiState) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showMe by remember { mutableStateOf(false) }
    val forced = state.updateForced

    LaunchedEffect(forced) {
        if (forced) {
            showMe = false
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

    val ready = state.phase == Phase.Ready && !forced

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
                Header(state, vm, onMe = { showMe = true })
                if (state.serverStarting) {
                    Text(
                        "Der Server startet gerade neu. Das dauert meist ein bis zwei Minuten.",
                        color = Muted, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
                HomePage(loading = !ready)
            }
        }
    }

    if (forced) {
        // Alle Dialoge/Sheets sind weg; nur der Update-Screen bleibt (unten).
    } else if (state.phase == Phase.NeedsLogin) {
        LoginSheet(state, vm)
    } else if (showMe) {
        MeSheet(state, vm, onDismiss = { showMe = false })
    }
    UpdateScreen(state, vm)
}

@Composable
private fun LoginSheet(state: UiState, vm: AppViewModel) {
    val activity = LocalContext.current.findActivity()
    val error = state.loginError ?: if (vm.clientIdMissing) "Google-Client-ID fehlt (GOOGLE_WEB_CLIENT_ID in local.properties). Die Anmeldung ist erst nach dem nächsten Build mit ID möglich." else null
    QuickscopeSheet(onDismiss = {}, dismissable = false) {
        Spacer(Modifier.height(12.dp))
        ScopeMark(56.dp)
        Text("Willkommen bei Quickscope", style = MaterialTheme.typography.displaySmall)
        Text("Melde dich an, um loszulegen.", color = Muted)
        Spacer(Modifier.height(12.dp))
        if (error != null) Text(error, color = Danger, style = MaterialTheme.typography.bodyMedium)
        PrimaryButton(
            if (state.loginBusy) "Einen Moment" else "Mit Google anmelden",
            { activity?.let(vm::signIn) },
            enabled = !state.loginBusy && activity != null,
        )
    }
}

/** Kopf: Titel links, eigenes Profilbild rechts (oeffnet das Sheet "Privat"). */
@Composable
private fun Header(state: UiState, vm: AppViewModel, onMe: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Quickscope", style = MaterialTheme.typography.headlineMedium)
        val me = state.me
        Box(Modifier.size(44.dp).clip(CircleShape).hapticClickable(onClick = onMe)) {
            UserAvatar(me?.pictureUrl ?: vm.signInPictureUrl, me?.name ?: me?.email ?: "?", 44.dp)
        }
    }
}

@Composable
private fun FailedView(message: String?, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        ScopeMark(56.dp)
        Spacer(Modifier.size(16.dp))
        Text("Das hat nicht geklappt", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.size(8.dp))
        Text(message ?: "Unbekannter Fehler", color = Muted, textAlign = TextAlign.Center)
        Spacer(Modifier.size(28.dp))
        PrimaryButton("Erneut versuchen", onRetry)
    }
}
