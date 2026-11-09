package com.jonasschnell.quickscope.logic

import com.jonasschnell.quickscope.data.Forecast

/** Ehrliche Sicherheitszeile: nie eine Garantie. */
fun confidenceLine(confidence: String): String = when (confidence) {
    "high" -> "Schätzung, keine Garantie"
    "medium" -> "Schätzung, ziemlich sicher"
    else -> "Schätzung, noch wenig Daten"
}

/** "mit deiner Lernzeit ca. 78 % bis zur Prüfung" */
fun projectionLine(f: Forecast): String = "mit deiner Lernzeit ca. ${f.scoreAtExamPercent} % bis zur Prüfung"

/** Kurzer Hinweis, wenn das Ziel bei gleichbleibender Lernzeit nicht erreicht wird; sonst null. */
fun extraTimeLine(f: Forecast): String? =
    if (f.onTrack) null else f.extraMinutesPerDayNeeded?.let { "Für das Ziel: etwa $it min mehr pro Tag" }

/** Pfeil zum Vergleich jetzt gegen Pruefung: aufwaerts, gleich oder abwaerts. */
fun projectionArrow(f: Forecast): String = when {
    f.scoreAtExamPercent > f.scoreNowPercent -> "↗"
    f.scoreAtExamPercent < f.scoreNowPercent -> "↘"
    else -> "→"
}

/** Lernzeit fuer "Los": Tagesziel minus heute schon Gelerntes, mindestens 3 Minuten. */
fun remainingMinutes(dailyMinutes: Int, doneSecondsToday: Int): Int =
    maxOf(3, dailyMinutes - doneSecondsToday / 60)

fun startLabel(minutes: Int) = "Los ($minutes min)"

/** Abschlusszeile: "Prognose 62 % → 66 %". */
fun beforeAfterLine(before: Int, after: Int): String = if (before == after) "Prognose $after %" else "Prognose $before % → $after %"

/** Fuer die Schwache-Themen-Zeile: Mastery 0..1 als ganze Prozent. */
fun masteryPercent(mastery: Double): Int = (mastery * 100).toInt().coerceIn(0, 100)
