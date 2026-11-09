package com.jonasschnell.quickscope.data

import android.content.Context

/** Kleine lokale Einstellungen der App. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("state", Context.MODE_PRIVATE)

    /** Wurde der Nutzer schon einmal zur Erlaubnis "Apps installieren" geleitet? */
    var askedInstallPermission: Boolean
        get() = prefs.getBoolean("askedInstall", false)
        set(value) = prefs.edit().putBoolean("askedInstall", value).apply()
}
