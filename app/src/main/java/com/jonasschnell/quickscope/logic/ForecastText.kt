package com.jonasschnell.quickscope.logic

import com.jonasschnell.quickscope.data.Forecast

/** Alle angezeigten Prozentwerte: auf das naechste kleinere Vielfache von 5 abrunden (nie aufrunden), 0..100. */
fun floor5(percent: Int): Int = ((percent.coerceIn(0, 100)) / 5) * 5

fun percentText(percent: Int): String = "${floor5(percent)} %"

/** "Bei 20 min täglich: ca. 75 % in der Prüfung", bei wenig Daten mit ", noch grob". */
fun projectionLine(f: Forecast, dailyMinutes: Int): String =
    "Bei $dailyMinutes min täglich: ca. ${percentText(f.scoreAtExamPercent)} in der Prüfung" + if (f.confidence == "low") ", noch grob" else ""

/** Beschriftung einer Lernzeit-Stufe in den Einstellungen: "20 min · ca. 70 %", ohne Projektion nur "20 min". */
fun minutesChoiceLabel(f: Forecast?, minutes: Int): String =
    f?.projections?.firstOrNull { it.minutes == minutes }?.let { "$minutes min · ca. ${percentText(it.scoreAtExamPercent)}" } ?: "$minutes min"

/** Lernzeit fuer "Los": Tagesziel minus heute schon Gelerntes, mindestens 3 Minuten. */
fun remainingMinutes(dailyMinutes: Int, doneSecondsToday: Int): Int =
    maxOf(3, dailyMinutes - doneSecondsToday / 60)

fun startLabel(minutes: Int) = "Los ($minutes min)"

/** Abschlusszeile: "Prognose 60 % → 65 %". */
fun beforeAfterLine(before: Int, after: Int): String =
    if (floor5(before) == floor5(after)) "Prognose ${percentText(after)}" else "Prognose ${percentText(before)} → ${percentText(after)}"

/** Fuer die Schwache-Themen-Zeile: Kenntnisstand 0..1 als Prozent (abgerundet auf 5er-Schritte). */
fun masteryPercent(mastery: Double): Int = floor5((mastery * 100).toInt())

/** Weniger als 45 Sekunden Rest: das Tagesziel gilt als geschafft (wie im Backend). */
fun dayGoalReached(dailyMinutes: Int, doneSeconds: Int): Boolean = dailyMinutes * 60 - doneSeconds < 45

/** Kleine Zeile "Heute: 12 von 30 min" (gehoerte plus Fragezeit, abgerundet). */
fun budgetLine(dailyMinutes: Int, doneSeconds: Int): String = "Heute: ${doneSeconds / 60} von $dailyMinutes min"

/** Freiwillige Zusatzrunde nach dem Tagesziel: ausdruecklich diese Minuten. */
const val EXTRA_MINUTES = 10
