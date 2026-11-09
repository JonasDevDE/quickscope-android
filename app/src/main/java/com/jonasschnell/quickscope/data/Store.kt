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

    /** Vorlesen an/aus (Standard an). Bei aus gibt es keinerlei Audio-Aufrufe. */
    var audioEnabled: Boolean
        get() = prefs.getBoolean("audioEnabled", true)
        set(value) = prefs.edit().putBoolean("audioEnabled", value).apply()

    /** Drosselung der Fehlermeldungen ans Backend: Zeitpunkt der letzten, Tag und Anzahl heute. */
    var logLastMs: Long
        get() = prefs.getLong("logLast", 0L)
        set(value) = prefs.edit().putLong("logLast", value).apply()
    var logDay: String?
        get() = prefs.getString("logDay", null)
        set(value) = prefs.edit().putString("logDay", value).apply()
    var logCount: Int
        get() = prefs.getInt("logCount", 0)
        set(value) = prefs.edit().putInt("logCount", value).apply()

    /** "Vorlesen" wurde offline geaendert und muss noch ins Profil geschrieben werden. */
    var audioDirty: Boolean
        get() = prefs.getBoolean("audioDirty", false)
        set(value) = prefs.edit().putBoolean("audioDirty", value).apply()

    /** Tagesserie (lokal abgeleitet): letzter Tag mit abgeschlossener Runde (ISO) und Tage in Folge. */
    var lastSessionDay: String?
        get() = prefs.getString("lastSessionDay", null)
        set(value) = prefs.edit().putString("lastSessionDay", value).apply()

    var dayStreak: Int
        get() = prefs.getInt("dayStreak", 0)
        set(value) = prefs.edit().putInt("dayStreak", value).apply()

    /** Alles lokal Gemerkte loeschen (Abmelden, Konto loeschen): Einstellungen und die Antwort-Warteschlange. */
    fun clearAll() {
        prefs.edit().clear().apply()
    }

    // Warteschlange fuer Antworten (siehe AttemptQueue)
    override fun read(key: String): String? = prefs.getString("q_$key", null)

    override fun write(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove("q_$key") else putString("q_$key", value) }.apply()
    }
}
