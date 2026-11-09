package com.jonasschnell.quickscope.logic

import com.jonasschnell.quickscope.data.AudioStatus

/** Hoechstens so lange wartet der Vorab-Test auf den Stream der ersten Lektion. */
const val LISTEN_PROBE_TIMEOUT_MS = 8_000L

/** Nach einem gescheiterten Vorab-Test verschwindet "Verstehen" fuer diese Zeit, danach wird neu geprueft. */
const val LISTEN_BLOCK_MS = 120_000L

/** Bestaetigt nur eine echte Antwort mit available=true; Fehler, 404 oder fehlende Antwort heissen "nicht bestaetigt". */
fun listenStatusConfirmed(status: AudioStatus?): Boolean = status != null && status.available

/**
 * "Verstehen" ist nur sichtbar, wenn ALLES gilt: online, Vorlesen an, Lektionen vorhanden, Status bestaetigt available=true
 * und keine Sperre nach einem gescheiterten Vorab-Test.
 */
fun listenVisible(online: Boolean, audioEnabled: Boolean, hasLessons: Boolean, statusConfirmed: Boolean, blockedUntilMs: Long, nowMs: Long): Boolean =
    online && audioEnabled && hasLessons && statusConfirmed && nowMs >= blockedUntilMs

/** Zeitpunkt, bis zu dem "Verstehen" nach einem Fehlschlag verborgen bleibt. */
fun listenBlockedUntil(nowMs: Long): Long = nowMs + LISTEN_BLOCK_MS

/** Vorab-Test bestanden: erfolgreiche Antwort mit mindestens einem Byte Audio innerhalb der Zeit. */
fun probePassed(httpOk: Boolean, gotBytes: Boolean, elapsedMs: Long): Boolean = httpOk && gotBytes && elapsedMs <= LISTEN_PROBE_TIMEOUT_MS
