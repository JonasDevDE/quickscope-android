package com.jonasschnell.quickscope.data

import android.content.Context
import com.jonasschnell.quickscope.logic.QueueStorage

/** Kleine lokale Einstellungen der App. */
class Store(context: Context) : QueueStorage {
    private val prefs = context.getSharedPreferences("state", Context.MODE_PRIVATE)

    /** Wurde der Nutzer schon einmal zur Erlaubnis "Apps installieren" geleitet? */
    var askedInstallPermission: Boolean
        get() = prefs.getBoolean("askedInstall", false)
        set(value) = prefs.edit().putBoolean("askedInstall", value).apply()

    /** Zuletzt gewaehlte Pruefung. */
    var chosenExamId: String?
        get() = prefs.getString("chosenExam", null)
        set(value) = prefs.edit().putString("chosenExam", value).apply()

    /** Audio-Modus (Sprachausgabe) in der Lernrunde: Zustand wird gemerkt. */
    var audioMode: Boolean
        get() = prefs.getBoolean("audioMode", false)
        set(value) = prefs.edit().putBoolean("audioMode", value).apply()

    /** Tagesserie (lokal abgeleitet): letzter Tag mit abgeschlossener Runde (ISO) und Tage in Folge. */
    var lastSessionDay: String?
        get() = prefs.getString("lastSessionDay", null)
        set(value) = prefs.edit().putString("lastSessionDay", value).apply()

    var dayStreak: Int
        get() = prefs.getInt("dayStreak", 0)
        set(value) = prefs.edit().putInt("dayStreak", value).apply()

    // Warteschlange fuer Antworten (siehe AttemptQueue)
    override fun read(key: String): String? = prefs.getString("q_$key", null)

    override fun write(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove("q_$key") else putString("q_$key", value) }.apply()
    }
}
