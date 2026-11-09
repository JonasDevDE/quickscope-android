package com.jonasschnell.quickscope.logic

import com.jonasschnell.quickscope.SessionPhase

/** Eine Runde darf nur starten, wenn gerade keine laeuft oder laedt (Doppeltipp auf "Los" startet nicht zweimal). */
fun canStartSession(phase: SessionPhase): Boolean = phase == SessionPhase.Closed || phase == SessionPhase.Empty || phase == SessionPhase.Error

/** Gleiches fuer das Hoerbuch (Phasen als Namen, damit die Logik ohne Android-Klassen testbar bleibt). */
fun canStartBook(phaseName: String): Boolean = phaseName == "Closed" || phaseName == "Empty" || phaseName == "Error" || phaseName == "Finished"

/** Mehrfach-Tap-Schutz: Klicks innerhalb von [windowMs] nach dem letzten angenommenen Klick werden verworfen. */
fun acceptClick(lastMs: Long, nowMs: Long, windowMs: Long = 500): Boolean = lastMs == 0L || nowMs - lastMs >= windowMs
