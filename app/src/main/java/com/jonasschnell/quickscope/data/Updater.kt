package com.jonasschnell.quickscope.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.os.Build
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.jonasschnell.quickscope.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.HttpException
import java.io.File
import java.io.IOException
import java.security.MessageDigest

sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object UpToDate : UpdateStatus
    data class Preparing(val versionName: String, val progress: Float?, val notes: String? = null) : UpdateStatus
    data class Ready(val versionName: String, val needsConfirm: Boolean, val notes: String? = null) : UpdateStatus
    data object Installing : UpdateStatus
    /** blocking: ein neueres Release ist sicher bekannt, der Update-Vollbildschirm bleibt dann stehen. */
    data class Error(val message: String, val blocking: Boolean = false) : UpdateStatus
}

private class UpdateException(message: String) : Exception(message)

/**
 * Selbst-Update ohne Play Store, moeglichst unsichtbar: leise im Hintergrund laden (nur ungemeterte Netze),
 * SHA-256 pruefen, installieren erst wenn die App in den Hintergrund geht. Fehler werden nur geloggt, ausser
 * bei manueller Suche aus dem Profil.
 */
class Updater(private val context: Context, private val client: OkHttpClient, private val api: QuickscopeApi) {
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val dir = File(context.cacheDir, "updates")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _status = MutableStateFlow(initialStatus())
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    /** "Update erzwungen": Server meldet neuere Version (SSE `update` oder HTTP 426). Haelt bis zum Neustart nach dem Update. */
    private val _forced = MutableStateFlow(prefs.getInt(KEY_FORCED_AT, -1) == BuildConfig.VERSION_CODE)
    val forced: StateFlow<Boolean> = _forced.asStateFlow()

    /** Sofort sperren und die Update-Pruefung ohne Drosselung anstossen. */
    fun markForced() {
        if (!_forced.value) {
            prefs.edit().putInt(KEY_FORCED_AT, BuildConfig.VERSION_CODE).apply()
            _forced.value = true
        }
        checkInBackground(ignoreThrottle = true)
    }

    private fun clearForced() {
        prefs.edit().remove(KEY_FORCED_AT).apply()
        _forced.value = false
    }

    /** Gesetzt, wenn der Nutzer die Installation selbst angestossen hat (dann werden Fehler gezeigt, Bestaetigung gestartet). */
    @Volatile private var userInitiated = false

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    private fun initialStatus(): UpdateStatus {
        val code = prefs.getInt(KEY_CODE, 0)
        if (code > BuildConfig.VERSION_CODE && apkFile(code).exists()) {
            return UpdateStatus.Ready(prefs.getString(KEY_NAME, "").orEmpty(), prefs.getBoolean(KEY_CONFIRM, false), prefs.getString(KEY_NOTES, null))
        }
        clearReady()
        return UpdateStatus.Idle
    }

    private fun apkFile(code: Int) = File(dir, "quickscope-$code.apk")

    private fun clearReady() {
        prefs.edit().remove(KEY_CODE).remove(KEY_NAME).remove(KEY_CONFIRM).remove(KEY_NOTES).apply()
        dir.listFiles()?.forEach { it.delete() }
    }

    /** Automatische Pruefung beim App-Start; hoechstens alle paar Stunden. */
    fun checkInBackground(ignoreThrottle: Boolean = false) {
        scope.launch { mutex.withLock { runCheck(manual = false, ignoreThrottle = ignoreThrottle) } }
    }

    /** Manuelle Suche aus dem Profil: zeigt Fortschritt und Fehler. */
    fun checkManually() {
        scope.launch { mutex.withLock { runCheck(manual = true, ignoreThrottle = true) } }
    }

    private suspend fun runCheck(manual: Boolean, ignoreThrottle: Boolean) {
        val before = _status.value
        var newerKnown = false
        if (before is UpdateStatus.Installing || before is UpdateStatus.Preparing) return
        try {
            if (!manual) {
                if (before is UpdateStatus.Ready) return
                if (!ignoreThrottle && System.currentTimeMillis() - prefs.getLong(KEY_LAST_CHECK, 0) < CHECK_INTERVAL_MS) return
            }
            val release = api.latestRelease()
            prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
            if (release.versionCode <= BuildConfig.VERSION_CODE) {
                clearReady()
                clearForced() // Server kennt keine neuere Version: niemanden aussperren
                _status.value = UpdateStatus.UpToDate
                return
            }
            newerKnown = true
            if (!manual && release.versionCode <= prefs.getInt(KEY_FAILED, 0)) return
            val have = prefs.getInt(KEY_CODE, 0) == release.versionCode && apkFile(release.versionCode).exists()
            if (!have) {
                _status.value = UpdateStatus.Preparing(release.versionName, if (manual) 0f else null, release.notes)
                download(release, manual)
                prefs.edit().putInt(KEY_CODE, release.versionCode).putString(KEY_NAME, release.versionName).putString(KEY_NOTES, release.notes)
                    .putBoolean(KEY_CONFIRM, false).apply()
            }
            _status.value = UpdateStatus.Ready(release.versionName, prefs.getBoolean(KEY_CONFIRM, false), release.notes)
            if (manual && canInstall()) {
                userInitiated = true
                startInstall()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Update-Pruefung fehlgeschlagen", e)
            val blocking = newerKnown || _forced.value || (before is UpdateStatus.Error && before.blocking) || before is UpdateStatus.Ready
            val text = when (e) {
                is UpdateException -> e.message.orEmpty()
                is IOException -> if (newerKnown) "Der Download ist fehlgeschlagen. Bitte Verbindung prüfen." else "Keine Verbindung zum Server."
                else -> "Update-Suche fehlgeschlagen."
            }
            _status.value = when {
                e is HttpException && e.code() == 404 -> { clearForced(); UpdateStatus.UpToDate }
                manual || newerKnown || _forced.value -> UpdateStatus.Error(text, blocking)
                before is UpdateStatus.Preparing -> UpdateStatus.Idle
                else -> before
            }
        }
    }

    /** "Erneut versuchen" im Pflicht-Update-Screen: Fehlerzaehler zuruecksetzen und neu laden/installieren. */
    fun retry() {
        prefs.edit().remove(KEY_FAILED).apply()
        checkManually()
    }

    private fun isMetered(): Boolean =
        (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered

    private suspend fun download(release: AppRelease, withProgress: Boolean) = withContext(Dispatchers.IO) {
        clearReady()
        dir.mkdirs()
        val part = File(dir, "quickscope-${release.versionCode}.apk.part")
        val target = apkFile(release.versionCode)
        try {
            val request = Request.Builder().url("${BuildConfig.BASE_URL}api/app/latest/apk").build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val body = resp.body
                val maxBytes = if (release.size > 0) minOf(release.size, MAX_APK_BYTES) else MAX_APK_BYTES
                if (body.contentLength() > maxBytes) throw UpdateException("Update ist zu groß. Abgebrochen.")
                val total = body.contentLength().takeIf { it > 0 } ?: release.size
                val digest = MessageDigest.getInstance("SHA-256")
                var done = 0L
                var lastPct = -1
                body.byteStream().use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            done += n
                            if (done > maxBytes) throw UpdateException("Update ist zu groß. Abgebrochen.")
                            if (withProgress && total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    _status.value = UpdateStatus.Preparing(release.versionName, pct / 100f, release.notes)
                                }
                            }
                        }
                    }
                }
                val hex = digest.digest().joinToString("") { "%02x".format(it) }
                if (!hex.equals(release.sha256, ignoreCase = true)) {
                    throw UpdateException("Prüfsumme stimmt nicht überein. Update abgebrochen.")
                }
            }
            if (!part.renameTo(target)) throw IOException("Datei konnte nicht abgelegt werden.")
        } finally {
            part.delete()
        }
    }

    /** Aufruf, wenn die App in den Hintergrund geht: installiert ein bereitgestelltes Update leise. */
    fun installIfReady() {
        scope.launch {
            mutex.withLock {
                val s = _status.value as? UpdateStatus.Ready ?: return@withLock
                if (s.needsConfirm || !canInstall()) return@withLock
                userInitiated = false
                startInstall()
            }
        }
    }

    /** Nutzer tippt im Profil auf Installieren. */
    fun installNow() {
        scope.launch {
            mutex.withLock {
                if (_status.value !is UpdateStatus.Ready || !canInstall()) return@withLock
                userInitiated = true
                prefs.edit().putBoolean(KEY_CONFIRM, false).apply()
                startInstall()
            }
        }
    }

    private fun startInstall() {
        val code = prefs.getInt(KEY_CODE, 0)
        val name = prefs.getString(KEY_NAME, "").orEmpty()
        val notes = prefs.getString(KEY_NOTES, null)
        val file = apkFile(code)
        if (!file.exists()) {
            clearReady()
            _status.value = UpdateStatus.Idle
            return
        }
        _status.value = UpdateStatus.Installing
        var sessionId = -1
        try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            val id = installer.createSession(params)
            sessionId = id
            installer.openSession(id).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("quickscope.apk", 0, file.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val intent = Intent(context, InstallReceiver::class.java).setAction(InstallReceiver.ACTION)
                val pending = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Installation konnte nicht gestartet werden", e)
            if (sessionId >= 0) runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
            _status.value = if (userInitiated) UpdateStatus.Error("Installation fehlgeschlagen.", blocking = true) else UpdateStatus.Ready(name, false, notes)
        }
    }

    /** Ergebnis der Installation vom BroadcastReceiver. */
    fun onInstallResult(status: Int, message: String?, sessionId: Int, confirmIntent: Intent?) {
        val name = prefs.getString(KEY_NAME, "").orEmpty()
        val notes = prefs.getString(KEY_NOTES, null)
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> Log.i(TAG, "Update installiert")
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val foreground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                if (foreground && confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirmIntent)
                } else {
                    runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
                    prefs.edit().putBoolean(KEY_CONFIRM, true).apply()
                    _status.value = UpdateStatus.Ready(name, true, notes)
                }
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                prefs.edit().putBoolean(KEY_CONFIRM, true).apply()
                _status.value = UpdateStatus.Ready(name, true, notes)
            }
            else -> {
                Log.w(TAG, "Installation fehlgeschlagen: status=$status $message")
                // Diese Version nicht automatisch erneut laden; der manuelle Knopf darf es weiter versuchen.
                prefs.edit().putInt(KEY_FAILED, prefs.getInt(KEY_CODE, 0)).apply()
                clearReady()
                _status.value = UpdateStatus.Error("Installation fehlgeschlagen${message?.let { ": $it" }.orEmpty()}", blocking = true)
            }
        }
    }

    private companion object {
        const val TAG = "Updater"
        const val KEY_CODE = "readyCode"
        const val KEY_NAME = "readyName"
        const val KEY_NOTES = "readyNotes"
        const val KEY_CONFIRM = "needsConfirm"
        const val KEY_LAST_CHECK = "lastCheck"
        const val KEY_FAILED = "failedCode"
        const val KEY_FORCED_AT = "forcedAtVersion"
        const val MAX_APK_BYTES = 100L * 1024 * 1024
        const val CHECK_INTERVAL_MS = 2 * 60 * 1000L
    }
}
