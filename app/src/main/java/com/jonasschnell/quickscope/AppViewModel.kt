package com.jonasschnell.quickscope

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.jonasschnell.quickscope.data.LiveSync
import com.jonasschnell.quickscope.data.Me
import com.jonasschnell.quickscope.data.SignInResult
import com.jonasschnell.quickscope.data.UpdateStatus
import com.jonasschnell.quickscope.data.backendMessage
import com.jonasschnell.quickscope.ui.HapticKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import java.io.IOException

enum class Phase { Loading, NeedsLogin, Ready, Failed }

data class UiMessage(val text: String, val actionLabel: String? = null, val onAction: (() -> Unit)? = null)

data class UiState(
    val phase: Phase = Phase.Loading,
    val loginBusy: Boolean = false,
    val loginError: String? = null,
    val failure: String? = null,
    val me: Me? = null,
    /** Backend startet gerade neu (502/503/504): Hinweis zeigen, automatisch erneut versuchen. */
    val serverStarting: Boolean = false,
    val update: UpdateStatus = UpdateStatus.Idle,
    val canInstallUpdates: Boolean = true,
    /** Pflicht-Update: Server meldet ein neueres Release (SSE `update` oder HTTP 426); der Update-Vollbildschirm sperrt die App. */
    val updateForced: Boolean = false,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as QuickscopeApp
    private val auth = app.auth
    private val store = app.store
    private val api = app.network.api

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val messageChannel = Channel<UiMessage>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()

    private val hapticChannel = Channel<HapticKind>(Channel.BUFFERED)
    /** Haptik-Ereignisse (Erfolg/Fehler), werden im UI abgespielt. */
    val hapticEvents = hapticChannel.receiveAsFlow()

    private var loadJob: Job? = null
    private var quietJob: Job? = null
    private var updateChecked = false
    private val updater get() = app.updater

    // --- Live-Sync ---
    private val foreground = MutableStateFlow(ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    private var hasBeenBackground = false
    private var lastFullRefresh = 0L
    private val writeMutex = Mutex()

    private val live = LiveSync(
        streamClient = app.network.streamClient,
        scope = viewModelScope,
        onChange = { refreshAllQuiet() },
        onConnected = { refreshAllQuiet() },
        onPoll = { refreshAllQuiet() },
        onForbidden = { refreshAllQuiet() },
        onUpdate = { if (it.versionCode > BuildConfig.VERSION_CODE) updater.markForced() },
    )

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            foreground.value = true
            if (hasBeenBackground && _state.value.phase == Phase.Ready) {
                refreshAllQuiet()
                updater.checkInBackground() // gedrosselt (2 min), bei jeder Rueckkehr in die App
            }
        }

        override fun onStop(owner: LifecycleOwner) {
            hasBeenBackground = true
            foreground.value = false
        }
    }

    private fun startLiveSync() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
        viewModelScope.launch {
            combine(_state.map { it.phase == Phase.Ready }.distinctUntilChanged(), foreground) { ready, fg -> ready && fg }
                .distinctUntilChanged()
                .collect { on -> if (on) live.start() else live.stop() }
        }
        // Fallback, falls SSE nicht durchkommt: waehrend die App offen ist alle 2 Minuten pruefen.
        viewModelScope.launch {
            while (true) {
                delay(UPDATE_POLL_MS)
                if (foreground.value && _state.value.phase == Phase.Ready) updater.checkInBackground(ignoreThrottle = true)
            }
        }
    }

    override fun onCleared() {
        live.stop()
        ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
    }

    /** Alles Sichtbare still neu laden (Aenderung, Reconnect, Vordergrund, Polling). */
    private fun refreshAllQuiet() {
        val now = System.currentTimeMillis()
        if (_state.value.phase != Phase.Ready || now - lastFullRefresh < 1_500) return
        lastFullRefresh = now
        quietJob?.cancel()
        quietJob = viewModelScope.launch {
            try {
                val me = api.me()
                _state.update { it.copy(me = me) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("Quickscope", "Stilles Neuladen fehlgeschlagen: ${e.message}")
            }
        }
    }

    private fun buzz(kind: HapticKind) {
        hapticChannel.trySend(kind)
    }

    init {
        _state.update { it.copy(canInstallUpdates = updater.canInstall()) }
        viewModelScope.launch { updater.status.collect { s -> _state.update { it.copy(update = s) } } }
        viewModelScope.launch { updater.forced.collect { f -> _state.update { it.copy(updateForced = f) } } }
        bootstrap()
        startLiveSync()
        viewModelScope.launch {
            auth.sessionExpired.collect {
                buzz(HapticKind.Reject)
                _state.update { it.copy(phase = Phase.NeedsLogin, loginBusy = false) }
            }
        }
    }

    fun refreshInstallPermission() {
        val can = updater.canInstall()
        val gained = can && !_state.value.canInstallUpdates
        _state.update { it.copy(canInstallUpdates = can) }
        if (gained && _state.value.phase == Phase.Ready) updater.checkInBackground(ignoreThrottle = true)
    }

    /** Einmalig true, wenn die Installationserlaubnis fehlt und noch nie danach gefragt wurde. */
    fun shouldAskInstallPermission(): Boolean {
        if (updater.canInstall() || store.askedInstallPermission) return false
        store.askedInstallPermission = true
        return true
    }

    fun checkUpdatesManually() = updater.checkManually()

    fun installUpdate() = updater.installNow()

    fun retryUpdate() = updater.retry()

    fun bootstrap() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(phase = Phase.Loading, failure = null) }
            if (auth.ensureToken()) loadSession() else _state.update { it.copy(phase = Phase.NeedsLogin) }
        }
    }

    fun signIn(activity: Activity) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loginBusy = true, loginError = null) }
            when (val result = auth.signIn(activity)) {
                SignInResult.Success -> {
                    buzz(HapticKind.Confirm)
                    _state.update { it.copy(loginBusy = false, phase = Phase.Loading) }
                    loadSession()
                }
                SignInResult.Cancelled -> _state.update { it.copy(loginBusy = false) }
                is SignInResult.Failure -> {
                    buzz(HapticKind.Reject)
                    _state.update { it.copy(loginBusy = false, loginError = result.message) }
                }
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            loadJob?.cancel()
            auth.signOut()
            _state.value = UiState(phase = Phase.NeedsLogin)
        }
    }

    /** Loescht das Konto auf dem Server, danach wie Abmelden. Fehler erscheinen als Snackbar. */
    fun deleteAccount() {
        run("Konto löschen fehlgeschlagen") {
            api.deleteMe()
            loadJob?.cancel()
            auth.signOut()
            _state.value = UiState(phase = Phase.NeedsLogin)
        }
    }

    val signInPictureUrl: String? get() = auth.pictureUrl

    val clientIdMissing get() = !auth.isConfigured

    private fun isServerDown(e: Exception) = e is HttpException && e.code() in setOf(502, 503, 504)

    /** Backend startet gerade neu: Skeleton bleibt sichtbar, Hinweis anzeigen und nach ~5 s automatisch erneut versuchen. */
    private suspend fun waitForServer() {
        _state.update { it.copy(phase = Phase.Loading, failure = null, serverStarting = true) }
        delay(RETRY_DELAY_MS)
    }

    /** Tauscht das kurzlebige Google-Token gegen die langlebige Sitzung; Fehler sind unkritisch (naechster Start versucht es erneut). */
    private suspend fun upgradeSession() {
        if (!auth.needsSessionUpgrade) return
        try {
            auth.storeSession(api.createSession().token)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private suspend fun loadSession() {
        upgradeSession()
        while (true) {
            try {
                val me = api.me()
                _state.update { it.copy(phase = Phase.Ready, me = me, failure = null, serverStarting = false) }
                if (!updateChecked) {
                    updateChecked = true
                    updater.checkInBackground(ignoreThrottle = true) // Kaltstart prueft immer
                }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is HttpException && e.code() == 401) {
                    if (auth.canPrompt) {
                        _state.update { it.copy(phase = Phase.NeedsLogin, loginBusy = false, serverStarting = false) }
                    } else {
                        _state.update { it.copy(phase = Phase.Failed, failure = errorText(e), serverStarting = false) }
                    }
                    return
                }
                if (isServerDown(e)) {
                    waitForServer()
                    continue
                }
                buzz(HapticKind.Reject)
                _state.update { it.copy(phase = Phase.Failed, failure = errorText(e), serverStarting = false) }
                return
            }
        }
    }

    /** Fuehrt eine Schreibaktion aus; bei Fehlern Haptik und Snackbar mit [failure] und der Fehlerursache. */
    private fun run(failure: String, onFailure: () -> Unit = {}, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                writeMutex.withLock { block() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onFailure()
                buzz(HapticKind.Reject)
                say("$failure. ${errorText(e)}")
            }
        }
    }

    private fun say(text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
        messageChannel.trySend(UiMessage(text, actionLabel, onAction))
    }

    private fun errorText(e: Exception): String = when (e) {
        is HttpException -> backendMessage(e) ?: when (e.code()) {
            401 -> "Nicht angemeldet."
            426 -> "Update erforderlich."
            403 -> "Kein Zugriff."
            404 -> "Nicht gefunden."
            409 -> "Das ist gerade nicht möglich."
            else -> "Serverfehler (${e.code()})."
        }
        is IOException -> "Keine Verbindung zum Server."
        else -> e.message ?: "Unbekannter Fehler."
    }

    private companion object {
        const val RETRY_DELAY_MS = 5_000L
        const val UPDATE_POLL_MS = 2 * 60 * 1000L
    }
}
