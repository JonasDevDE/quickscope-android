package com.jonasschnell.quickscope.data

import android.app.Activity
import android.content.Context
import android.util.Base64
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.jonasschnell.quickscope.logic.needsSecretMigration
import java.lang.ref.WeakReference

sealed interface SignInResult {
    data object Success : SignInResult
    data object Cancelled : SignInResult
    data class Failure(val message: String) : SignInResult
}

class AuthManager(context: Context, private val serverClientId: String) {
    private val prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
    private val secrets = SecretStore(context.getSharedPreferences("auth_secure", Context.MODE_PRIVATE)).also { store ->
        // Migration: alter Klartext-Token verschluesselt ablegen, Klartext loeschen
        prefs.getString(KEY_TOKEN, null).takeIf { needsSecretMigration(it) }?.let { plain ->
            store.put(KEY_TOKEN, plain)
            prefs.edit().remove(KEY_TOKEN).apply()
        }
    }
    private val credentialManager = CredentialManager.create(context)
    private val mutex = Mutex()
    private var activityRef: WeakReference<Activity>? = null
    private val _sessionExpired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    @Volatile
    var token: String? = secrets.get(KEY_TOKEN)
        private set

    /** Profilbild aus dem Google-Credential (sofort verfuegbar, bevor /api/me geantwortet hat). */
    val pictureUrl: String? get() = prefs.getString(KEY_PICTURE, null)

    val sessionExpired = _sessionExpired.asSharedFlow()
    val isConfigured get() = serverClientId.isNotBlank()

    fun attach(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    /** Nur mit Activity im Vordergrund darf eine Login-Aufforderung entstehen; im Hintergrund scheitern Requests still. */
    private fun foregroundActivity(): Activity? =
        activityRef?.get()?.takeIf { ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }

    val canPrompt: Boolean get() = foregroundActivity() != null

    /** Endgueltiges 401 trotz frischem Token: Login anfordern, aber nur im Vordergrund. */
    fun notifyRejectedAfterRefresh() {
        if (canPrompt) _sessionExpired.tryEmit(Unit)
    }

    fun hasValidToken(): Boolean = token?.let { isSession(it) || !isExpired(it) } == true

    /** Das Token ist noch ein kurzlebiges Google-Token und sollte gegen eine Sitzung getauscht werden. */
    val needsSessionUpgrade: Boolean get() = token?.let { !isSession(it) } == true

    fun storeSession(sessionToken: String) = store(sessionToken)

    private fun isSession(t: String) = t.startsWith("bst_")

    suspend fun signIn(activity: Activity): SignInResult {
        if (!isConfigured) {
            return SignInResult.Failure("Google-Client-ID fehlt. Bitte GOOGLE_WEB_CLIENT_ID in local.properties setzen und neu bauen.")
        }
        attach(activity)
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(serverClientId).build())
            .build()
        return try {
            val credential = credentialManager.getCredential(activity, request).credential
            val google = (credential as? CustomCredential)
                ?.takeIf { it.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL }
                ?.let { GoogleIdTokenCredential.createFrom(it.data) }
                ?: return SignInResult.Failure("Anmeldung lieferte kein Google-Token.")
            store(google.idToken)
            prefs.edit().putString(KEY_PICTURE, google.profilePictureUri?.toString()).apply()
            SignInResult.Success
        } catch (e: GetCredentialCancellationException) {
            SignInResult.Cancelled
        } catch (e: GetCredentialException) {
            SignInResult.Failure(e.message ?: "Anmeldung fehlgeschlagen.")
        }
    }

    private suspend fun refreshSilently(): String? {
        if (!isConfigured) return null
        val activity = activityRef?.get() ?: return null
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(true)
            .setServerClientId(serverClientId)
            .setAutoSelectEnabled(true)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        return try {
            extractToken(credentialManager.getCredential(activity, request))?.also { store(it) }
        } catch (e: GetCredentialException) {
            if (e is NoCredentialException) throw e
            null
        }
    }

    /** After a 401: returns a token different from the rejected one, refreshing at most once at a time. */
    suspend fun refreshAfterRejection(rejected: String?): String? = mutex.withLock {
        val current = token
        if (current != null && current != rejected && (isSession(current) || !isExpired(current))) return@withLock current
        // Abgelehnte Sitzung (abgelaufen/widerrufen) verwerfen, damit wieder ein Google-Token geholt wird.
        if (rejected != null && isSession(rejected) && current == rejected) {
            token = null
            secrets.put(KEY_TOKEN, null)
        }
        if (foregroundActivity() == null) return@withLock null
        try {
            refreshSilently()
        } catch (e: NoCredentialException) {
            // Nur ein klar fehlendes Konto fuehrt zur Login-Aufforderung; transiente Fehler nicht.
            _sessionExpired.tryEmit(Unit)
            null
        }
    }

    suspend fun ensureToken(): Boolean {
        if (hasValidToken()) return true
        return try {
            mutex.withLock { refreshSilently() } != null
        } catch (e: NoCredentialException) {
            false
        }
    }

    suspend fun signOut() {
        token = null
        secrets.put(KEY_TOKEN, null)
        prefs.edit().remove(KEY_TOKEN).remove(KEY_PICTURE).apply()
        runCatching { credentialManager.clearCredentialState(ClearCredentialStateRequest()) }
    }

    private fun store(idToken: String) {
        token = idToken
        secrets.put(KEY_TOKEN, idToken)
    }

    private fun extractToken(response: GetCredentialResponse): String? {
        val credential = response.credential
        if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            return GoogleIdTokenCredential.createFrom(credential.data).idToken
        }
        return null
    }

    private fun isExpired(jwt: String): Boolean {
        val exp = runCatching {
            val payload = String(Base64.decode(jwt.split('.')[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
            Regex("\"exp\"\\s*:\\s*(\\d+)").find(payload)?.groupValues?.get(1)?.toLong()
        }.getOrNull() ?: return true
        return exp * 1000 - 60_000 < System.currentTimeMillis()
    }

    private companion object {
        const val KEY_TOKEN = "id_token"
        const val KEY_PICTURE = "picture_url"
    }
}
