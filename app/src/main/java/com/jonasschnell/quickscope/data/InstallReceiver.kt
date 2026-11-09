package com.jonasschnell.quickscope.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.jonasschnell.quickscope.QuickscopeApp

/** Empfaengt das Ergebnis der PackageInstaller-Session. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? QuickscopeApp ?: return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        @Suppress("DEPRECATION")
        val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
        app.updater.onInstallResult(status, message, sessionId, confirm)
    }

    companion object {
        const val ACTION = "com.jonasschnell.quickscope.INSTALL_RESULT"
    }
}
