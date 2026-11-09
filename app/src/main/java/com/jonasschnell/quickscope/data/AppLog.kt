package com.jonasschnell.quickscope.data

import android.util.Log
import com.jonasschnell.quickscope.BuildConfig

/** Zentraler Logger: nur im Debug-Build, im Release ganz aus (keine Titel, Nutzertexte oder Fehlerdetails im Log). */
object AppLog {
    fun d(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.d(tag, msg)
    }

    fun i(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.i(tag, msg)
    }

    fun w(tag: String, msg: String, t: Throwable? = null) {
        if (BuildConfig.DEBUG) Log.w(tag, msg, t)
    }
}
