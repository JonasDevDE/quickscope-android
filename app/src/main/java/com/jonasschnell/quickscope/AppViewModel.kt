package com.jonasschnell.quickscope

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.jonasschnell.quickscope.data.CheatSheet
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import com.jonasschnell.quickscope.data.Exam
import com.jonasschnell.quickscope.data.ExamCreate
import com.jonasschnell.quickscope.data.ExamUpdate
import com.jonasschnell.quickscope.data.Forecast
import com.jonasschnell.quickscope.data.LiveSync
import com.jonasschnell.quickscope.data.Material
import com.jonasschnell.quickscope.data.UploadJob
import com.jonasschnell.quickscope.data.buildParts
import com.jonasschnell.quickscope.data.totalSize
import com.jonasschnell.quickscope.logic.pickActiveExam
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
    val exams: List<Exam> = emptyList(),
    val examsLoaded: Boolean = false,
    val chosenExamId: String? = null,
    /** Prognose der aktiven Pruefung (null, solange sie noch nicht geladen ist). */
    val forecast: Forecast? = null,
    val forecastExamId: String? = null,
    /** Material der zuletzt angesehenen Pruefung (Pruefungs-Einstellungen). */
    val materials: List<Material> = emptyList(),
    val materialsExamId: String? = null,
    val upload: UploadState? = null,
    /** Onboarding: laeuft nur bei onboardingDone == false (nach dem Login geholt). */
    val onboarding: com.jonasschnell.quickscope.logic.OnboardingGate = com.jonasschnell.quickscope.logic.OnboardingGate.Unknown,
    /** Start ohne Netz: Pruefungen und Prognose stammen aus dem lokalen Speicher. */
    val offline: Boolean = false,
) {
    val activeExam: Exam? get() = pickActiveExam(exams, chosenExamId, java.time.Instant.now())
    val activeForecast: Forecast? get() = forecast?.takeIf { forecastExamId == activeExam?.id }
}

/** Stand eines laufenden Uploads: [progress] 0..1 oder null (unbekannt); [error] bei Abbruch/Fehler. */
data class UploadState(
    val examId: String,
    /** Fortschritt der laufenden Datei (0..1) oder null. */
    val progress: Float? = null,
    /** Zusammenfassung bei Fehlern, z. B. "8 von 10 hochgeladen". */
    val error: String? = null,
    val done: Boolean = false,
    /** Laufende Datei (1-basiert) und Gesamtzahl der Queue: "3 von 10". */
    val current: Int = 0,
    val total: Int = 0,
    /** Fehlgeschlagene Dateien mit ruhigem Klartext (nur am Ende der Queue). */
    val failedItems: List<com.jonasschnell.quickscope.logic.UploadItem> = emptyList(),
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as QuickscopeApp
    private val auth = app.auth
    private val store = app.store
    private val api = app.network.api
    private val sync = app.attemptSync

    private val _state = MutableStateFlow(UiState())

    /** Gibt es wirklich Internet? (Connectivity, validiertes Netz) */
    val online get() = app.connectivity.online

    /** Hat das Offline-Paket Lektionen? ("Verstehen" funktioniert offline als Lesemodus.) */
    fun offlineHasLessons(examId: String): Boolean = app.local.pack(examId)?.entries?.any { it.lesson != null } == true

    /** Ruhiger Hinweis fuer Aktionen, die Internet brauchen. */
    fun needInternet() {
        buzz(HapticKind.Reject)
        say(com.jonasschnell.quickscope.logic.NEEDS_INTERNET)
    }

    /** Tage des bereitliegenden Offline-Pakets der Pruefung (null: noch nichts geladen). */
    fun offlineDays(examId: String): Int? = app.local.packDays(examId)
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
        onChange = { refreshAllQuiet(force = it.kind == "exam") },
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
    private fun refreshAllQuiet(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (_state.value.phase != Phase.Ready || (!force && now - lastFullRefresh < 1_500)) return
        lastFullRefresh = now
        quietJob?.cancel()
        quietJob = viewModelScope.launch {
            try {
                val me = api.me()
                _state.update { it.copy(me = me) }
                loadExams()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                com.jonasschnell.quickscope.data.AppLog.w("Quickscope", "Stilles Neuladen fehlgeschlagen")
            }
        }
    }

    /** Netz ist wieder da: Offline-Start neu laden, sonst offene Antworten senden und das Paket auffrischen. */
    private fun onBackOnline() {
        if (_state.value.offline) {
            bootstrap()
            return
        }
        if (_state.value.phase != Phase.Ready) return
        viewModelScope.launch {
            _state.value.exams.forEach { sync.flush(it.id) }
            com.jonasschnell.quickscope.data.PackSyncScheduler.schedule(app, replace = true)
        }
    }

    /** Offline starten, wenn Pruefungen lokal liegen: Heute mit der gespeicherten Prognose, ohne Onboarding. */
    private fun tryOffline(): Boolean {
        val exams = app.local.exams() ?: return false
        val chosen = store.chosenExamId
        val active = com.jonasschnell.quickscope.logic.pickActiveExam(exams, chosen, java.time.Instant.now())
        _state.update {
            it.copy(
                phase = Phase.Ready, me = app.local.me(), failure = null, serverStarting = false, exams = exams, examsLoaded = true,
                chosenExamId = chosen, onboarding = com.jonasschnell.quickscope.logic.OnboardingGate.Done, offline = true,
                forecast = active?.let { a -> app.local.forecast(a.id) }, forecastExamId = active?.id,
            )
        }
        return true
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
            var was = app.connectivity.isOnline()
            app.connectivity.online.collect { now ->
                if (now && !was) onBackOnline()
                was = now
            }
        }
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
            if (!app.connectivity.isOnline() && tryOffline()) return@launch
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

    /** Abmelden: Sitzung auf dem Server beenden (Fehler egal), danach ALLES lokal loeschen. */
    fun signOut() {
        viewModelScope.launch {
            loadJob?.cancel()
            runCatching { kotlinx.coroutines.withTimeoutOrNull(4_000) { api.logout() } }
            auth.signOut()
            app.wipeLocalData()
            _state.value = UiState(phase = Phase.NeedsLogin)
        }
    }

    /** Datenexport direkt in die vom Nutzer gewaehlte Datei (Speichern-Dialog); Erfolg bleibt ohne Hinweis, nur ein Fehler wird gemeldet. */
    fun exportTo(uri: android.net.Uri) {
        viewModelScope.launch {
            var bytes: Long? = null
            try {
                val body = api.export()
                bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    body.use { b ->
                        val out = getApplication<Application>().contentResolver.openOutputStream(uri, "w") ?: throw IOException("Kein Ziel")
                        out.use { com.jonasschnell.quickscope.logic.copyExport(b.byteStream(), it) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                com.jonasschnell.quickscope.data.AppLog.w("Export", com.jonasschnell.quickscope.logic.exportFailureReason(e), e)
            }
            when (com.jonasschnell.quickscope.logic.exportOutcome(bytes)) {
                com.jonasschnell.quickscope.logic.ExportOutcome.Saved -> Unit // der Speichern-Dialog des Systems bestaetigt es schon
                com.jonasschnell.quickscope.logic.ExportOutcome.Failed -> say("Gerade nicht verfügbar.")
            }
        }
    }

    /** Vorlesen an/aus: lokal sofort wirksam (bei aus keine Audio-Aufrufe mehr), im Profil gespeichert. */
    fun setAudioEnabled(on: Boolean, onFailure: () -> Unit = {}) {
        store.audioEnabled = on
        if (!on) app.voice.stop()
        store.audioDirty = true // bis das Profil den Wert kennt (auch offline: wird spaeter nachgeschrieben)
        if (!app.connectivity.isOnline()) return
        run("Speichern fehlgeschlagen", onFailure) {
            api.putProfile(com.jonasschnell.quickscope.data.ProfileUpdate(audioEnabled = on))
            if (store.audioEnabled == on) store.audioDirty = false
        }
    }

    val audioEnabled: Boolean get() = store.audioEnabled

    /** Loescht das Konto auf dem Server, danach wie Abmelden. Fehler erscheinen als Snackbar. */
    fun deleteAccount() {
        run("Konto löschen fehlgeschlagen") {
            api.deleteMe()
            loadJob?.cancel()
            auth.signOut()
            app.wipeLocalData()
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
                val exams = api.exams()
                val profile = try { api.profile() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                if (store.audioDirty) setAudioEnabled(store.audioEnabled) else profile?.audioEnabled?.let { store.audioEnabled = it }
                val gate = com.jonasschnell.quickscope.logic.gateFor(profile)
                _state.update { it.copy(phase = Phase.Ready, me = me, failure = null, serverStarting = false, exams = exams, examsLoaded = true, chosenExamId = store.chosenExamId, onboarding = gate, offline = false) }
                runCatching { app.local.saveMe(me); app.local.saveExams(exams) }
                com.jonasschnell.quickscope.data.PackSyncScheduler.schedule(app)
                viewModelScope.launch {
                    loadForecast()
                    exams.forEach { sync.flush(it.id) } // offene Antworten aus frueheren Runden nachsenden
                }
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
                if (e is IOException && tryOffline()) return
                buzz(HapticKind.Reject)
                _state.update { it.copy(phase = Phase.Failed, failure = errorText(e), serverStarting = false) }
                return
            }
        }
    }

    /** Fuehrt eine Schreibaktion aus; bei Fehlern Haptik und Snackbar mit [failure] und der Fehlerursache. */
    private fun run(failure: String, onFailure: () -> Unit = {}, block: suspend () -> Unit) {
        if (!app.connectivity.isOnline()) {
            onFailure()
            needInternet()
            return
        }
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


    // ---------------------------------------------------------------- Pruefungen

    /** Pruefungen und die Prognose der aktiven Pruefung still neu laden. */
    private suspend fun loadExams() {
        val exams = api.exams()
        runCatching { app.local.saveExams(exams) }
        _state.update { it.copy(exams = exams, examsLoaded = true, offline = false) }
        loadForecast()
        val id = _state.value.materialsExamId
        if (id != null && exams.any { it.id == id }) loadMaterials(id)
    }

    private suspend fun loadForecast() {
        val exam = _state.value.activeExam ?: return
        try {
            val f = api.forecast(exam.id)
            runCatching { app.local.saveForecast(exam.id, f) }
            _state.update { it.copy(forecast = f, forecastExamId = exam.id) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // ohne Netz: die zuletzt gespeicherte Prognose zeigen
            app.local.forecast(exam.id)?.let { f -> _state.update { it.copy(forecast = f, forecastExamId = exam.id) } }
        }
    }

    suspend fun loadMaterials(examId: String) {
        try {
            val list = api.materials(examId)
            _state.update { it.copy(materials = list, materialsExamId = examId) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** Tagesserie fuer die Heute-Ansicht: vom Backend, sonst lokal abgeleitet; null = ausblenden. */
    fun dayStreak(): Int? = _state.value.activeForecast?.streakDays?.takeIf { it > 0 }
        ?: com.jonasschnell.quickscope.logic.visibleDayStreak(
            store.lastSessionDay?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }, store.dayStreak, java.time.LocalDate.now(),
        )

    /** Profil laden; null bei Fehler (ruhig, ohne Meldung). */
    suspend fun loadProfile(): com.jonasschnell.quickscope.data.Profile? = try {
        api.profile()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** Profil in den Einstellungen speichern (Teil-Aenderung); Fehler erscheinen als Snackbar. */
    fun saveProfile(draft: com.jonasschnell.quickscope.logic.ProfileDraft, onDone: () -> Unit = {}, onFailure: () -> Unit = {}) {
        run("Speichern fehlgeschlagen", onFailure) {
            api.putProfile(
                com.jonasschnell.quickscope.data.ProfileUpdate(
                    about = com.jonasschnell.quickscope.logic.normalizeAbout(draft.about), age = draft.age, educationLevel = draft.education,
                ),
            )
            onDone()
        }
    }

    /** Onboarding erneut pruefen (nach Netzfehler). */
    fun retryOnboardingCheck() {
        viewModelScope.launch {
            val gate = com.jonasschnell.quickscope.logic.gateFor(loadProfile())
            _state.update { it.copy(onboarding = gate) }
        }
    }

    /** Netzfehler beim Profil: spaeter nochmal, die App blockiert nicht. */
    fun skipOnboardingCheck() {
        _state.update { it.copy(onboarding = com.jonasschnell.quickscope.logic.OnboardingGate.Done) }
    }

    /** Onboarding abschliessen: alles in einem PUT, danach nie wieder erreichbar. */
    fun finishOnboarding(age: Int, education: String?, about: String, onFailure: () -> Unit = {}) {
        run("Speichern fehlgeschlagen", onFailure) {
            api.putProfile(
                com.jonasschnell.quickscope.data.ProfileUpdate(
                    about = com.jonasschnell.quickscope.logic.normalizeAbout(about).ifEmpty { null },
                    age = com.jonasschnell.quickscope.logic.clampAge(age), educationLevel = education, onboardingDone = true,
                ),
            )
            _state.update { it.copy(onboarding = com.jonasschnell.quickscope.logic.OnboardingGate.Done) }
            buzz(HapticKind.Confirm)
        }
    }

    /** Sprachnachricht (AAC/ADTS) hochladen; die Datei wird danach immer geloescht. [onResult] bekommt Text oder den ruhigen Fehlerausgang. */
    fun uploadVoice(file: java.io.File, onResult: (com.jonasschnell.quickscope.logic.VoiceOutcome) -> Unit) {
        viewModelScope.launch {
            val result = try {
                val part = okhttp3.MultipartBody.Part.createFormData(
                    "file", "voice.${com.jonasschnell.quickscope.logic.VOICE_FILE_EXT}",
                    okhttp3.RequestBody.create(com.jonasschnell.quickscope.logic.VOICE_MIME.toMediaTypeOrNull(), file),
                )
                com.jonasschnell.quickscope.logic.VoiceOutcome.Text(api.profileVoice(part).about)
            } catch (e: CancellationException) {
                file.delete()
                throw e
            } catch (e: Exception) {
                com.jonasschnell.quickscope.logic.voiceOutcomeForStatus((e as? HttpException)?.code())
            }
            file.delete() // Aufnahme nie lokal behalten (auch bei Fehlern und Abbruch)
            onResult(result)
        }
    }

    fun openMaterials(examId: String) {
        viewModelScope.launch { loadMaterials(examId) }
    }

    fun selectExam(id: String) {
        store.chosenExamId = id
        _state.update { it.copy(chosenExamId = id) }
        viewModelScope.launch { loadForecast() }
    }

    /** Neue Pruefung anlegen und sofort auswaehlen; [onDone] bekommt die angelegte Pruefung. */
    fun createExam(body: ExamCreate, onDone: (Exam) -> Unit = {}, onFailure: () -> Unit = {}) {
        run("Thema anlegen fehlgeschlagen", onFailure) {
            val exam = api.createExam(body)
            store.chosenExamId = exam.id
            _state.update { it.copy(exams = it.exams + exam, chosenExamId = exam.id, forecast = null, forecastExamId = null) }
            buzz(HapticKind.Confirm)
            onDone(exam)
            loadForecast()
        }
    }

    fun updateExam(id: String, body: ExamUpdate, onFailure: () -> Unit = {}) {
        run("Speichern fehlgeschlagen", onFailure) {
            val exam = api.updateExam(id, body)
            _state.update { s -> s.copy(exams = s.exams.map { if (it.id == id) exam else it }) }
            loadForecast()
        }
    }

    fun deleteExam(id: String) {
        run("Löschen fehlgeschlagen") {
            api.deleteExam(id)
            val wasChosen = _state.value.chosenExamId == id
            if (wasChosen) store.chosenExamId = null
            _state.update { s ->
                s.copy(exams = s.exams.filter { it.id != id }, chosenExamId = if (wasChosen) null else s.chosenExamId, forecast = null, forecastExamId = null)
            }
            buzz(HapticKind.GestureEnd)
            loadForecast()
        }
    }

    /** Aufgaben neu erzeugen: der Lernstand bleibt erhalten; [resetProgress] ist der harte Neustart ("Von vorn beginnen"). */
    fun regenerateItems(id: String, resetProgress: Boolean = false) {
        run("Neu erzeugen fehlgeschlagen") {
            api.regenerate(id, if (resetProgress) true else null)
            buzz(HapticKind.Confirm)
            loadExams()
        }
    }

    fun deleteMaterial(examId: String, materialId: String) {
        run("Entfernen fehlgeschlagen") {
            api.deleteMaterial(examId, materialId)
            buzz(HapticKind.GestureEnd)
            loadMaterials(examId)
            loadExams()
        }
    }

    /** Merkzettel fuer die letzte Runde; null bei Fehler. */
    suspend fun cheatsheetNow(examId: String): CheatSheet? = try {
        api.cheatsheet(examId, 5)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        say("Merkzettel gerade nicht erreichbar.")
        null
    }

    /** Prognose aus einer beendeten Runde uebernehmen. */
    fun applyForecast(examId: String, forecast: Forecast) {
        runCatching { app.local.saveForecast(examId, forecast) }
        _state.update { it.copy(forecast = forecast, forecastExamId = examId) }
    }

    /** Pruefungen und Prognose neu laden (z. B. nach einer Lernrunde oder bei "Erneut versuchen"). */
    fun refreshExamsNow() {
        viewModelScope.launch {
            try {
                loadExams()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    fun openPreview(examId: String, material: Material) {
        viewModelScope.launch {
            try {
                app.preview.open(examId, material)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say(if (e is IOException) "Vorschau gerade nicht möglich." else "Keine App zum Öffnen gefunden.")
            }
        }
    }

    // ---------------------------------------------------------------- Upload

    // --- Upload-Warteschlange: strikt eine Datei nach der anderen, Fehler stoppen die anderen nicht ---

    private class UploadUnit(val examId: String, val job: UploadJob, var item: com.jonasschnell.quickscope.logic.UploadItem)

    private val units = mutableListOf<UploadUnit>()
    private var queueJob: Job? = null

    private fun unitName(job: UploadJob) = if (job.files.size == 1) job.files.first().name else "Scan (${job.files.size} Seiten)"

    /** Neue Dateien an die Queue haengen (laeuft schon eine, kommen sie danach dran). */
    fun upload(examId: String, jobs: List<UploadJob>) {
        if (jobs.isEmpty()) return
        if (!app.connectivity.isOnline()) { needInternet(); return }
        if (queueJob?.isActive != true) units.clear()
        units += jobs.map { UploadUnit(examId, it, com.jonasschnell.quickscope.logic.UploadItem(unitName(it))) }
        publishUpload(examId)
        if (queueJob?.isActive != true) queueJob = viewModelScope.launch { processQueue() }
    }

    private fun publishUpload(examId: String, progress: Float? = null, finished: Boolean = false) {
        val total = units.size
        val currentIdx = units.indexOfFirst { it.item.state == com.jonasschnell.quickscope.logic.UploadItemState.Uploading }
        val failed = units.filter { it.item.state == com.jonasschnell.quickscope.logic.UploadItemState.Failed }.map { it.item }
        val done = units.count { it.item.state == com.jonasschnell.quickscope.logic.UploadItemState.Done }
        _state.update {
            it.copy(
                upload = UploadState(
                    examId, progress = progress, current = if (currentIdx >= 0) currentIdx + 1 else total, total = total,
                    error = if (finished && failed.isNotEmpty()) com.jonasschnell.quickscope.logic.uploadSummaryText(done, total) else null,
                    failedItems = if (finished) failed else emptyList(),
                ),
            )
        }
    }

    private suspend fun processQueue() {
        val examId = units.firstOrNull()?.examId ?: return
        val ctx = getApplication<Application>()
        val summary = try {
            com.jonasschnell.quickscope.logic.runUploadQueue(
                size = { units.size }, itemAt = { units[it] },
                onState = { i, st, err ->
                    units[i].item = com.jonasschnell.quickscope.logic.UploadItem(units[i].item.name, st, err?.let { e -> if (e is Exception) uploadErrorText(e) else "Hochladen fehlgeschlagen." })
                    if (st == com.jonasschnell.quickscope.logic.UploadItemState.Failed) buzz(HapticKind.Reject)
                    publishUpload(examId)
                },
                upload = { _, unit ->
                    var prepared: List<com.jonasschnell.quickscope.data.UploadFile> = emptyList()
                    try {
                        unit.job.files.firstOrNull { it.size > ABSOLUTE_MAX_BYTES }?.let { throw IllegalArgumentException("„${it.name}“ ist zu groß.") }
                        // Fotos klein rechnen (nicht im Hauptthread); Dokumente bleiben unberuehrt
                        prepared = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { unit.job.files.map { f -> com.jonasschnell.quickscope.data.compressForUpload(ctx, f) } }
                        val job = unit.job.copy(files = prepared)
                        val total = totalSize(job)
                        var sent = 0L
                        val parts = buildParts(ctx, job) { n ->
                            sent += n
                            if (total > 0) publishUpload(unit.examId, progress = (sent.toFloat() / total).coerceIn(0f, 1f))
                        }
                        api.uploadMaterial(unit.examId, parts)
                    } finally {
                        com.jonasschnell.quickscope.data.cleanupUploadCopies(prepared.filter { p -> unit.job.files.none { it.uri == p.uri } })
                    }
                    // schon fertige Dateien sofort in der Materialliste zeigen; die Verarbeitung startet das Backend je Material selbst
                    loadMaterials(unit.examId)
                },
            )
        } catch (e: CancellationException) {
            units.forEachIndexed { i, u ->
                if (u.item.state == com.jonasschnell.quickscope.logic.UploadItemState.Waiting || u.item.state == com.jonasschnell.quickscope.logic.UploadItemState.Uploading) {
                    units[i].item = u.item.copy(state = com.jonasschnell.quickscope.logic.UploadItemState.Cancelled)
                }
            }
            throw e
        }
        if (summary.failed.isEmpty()) {
            buzz(HapticKind.Confirm)
            _state.update { it.copy(upload = UploadState(examId, done = true, total = summary.total)) }
            units.clear()
        } else {
            publishUpload(examId, finished = true)
        }
        loadMaterials(examId)
        try {
            loadExams()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** "Nochmal": nur die fehlgeschlagenen Dateien erneut, die erledigten bleiben. */
    fun retryUpload() {
        if (queueJob?.isActive == true) return
        var any = false
        units.forEach { u ->
            if (u.item.state == com.jonasschnell.quickscope.logic.UploadItemState.Failed) {
                u.item = com.jonasschnell.quickscope.logic.UploadItem(u.item.name)
                any = true
            }
        }
        val examId = units.firstOrNull()?.examId ?: return
        if (!any) return
        if (!app.connectivity.isOnline()) { needInternet(); return }
        publishUpload(examId)
        queueJob = viewModelScope.launch { processQueue() }
    }

    /** Abbrechen: die laufende und alle wartenden Dateien; schon erledigte bleiben im Material. */
    fun cancelUpload() {
        queueJob?.cancel()
        units.clear()
        _state.update { it.copy(upload = null) }
    }

    fun clearUpload() {
        _state.update { it.copy(upload = null) }
    }

    /** Servermeldung im Klartext (z. B. "Datei X ist groesser als 40 MB"), sonst ein neutraler Satz. */
    private fun uploadErrorText(e: Exception): String = when (e) {
        is HttpException -> com.jonasschnell.quickscope.logic.uploadFailureText(backendMessage(e), e.code(), false)
        is IOException -> com.jonasschnell.quickscope.logic.uploadFailureText(null, null, true)
        else -> e.message ?: com.jonasschnell.quickscope.logic.uploadFailureText(null, null, false)
    }

    private companion object {
        /** Absolute Obergrenze vor dem Senden (das Backend nennt seine Grenzen im Klartext). */
        const val ABSOLUTE_MAX_BYTES = 100L * 1024 * 1024
        const val RETRY_DELAY_MS = 5_000L
        const val UPDATE_POLL_MS = 2 * 60 * 1000L
    }
}
