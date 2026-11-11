import 'dart:math' as math;
import '../data/models.dart';
import 'exams.dart';

/// Alle angezeigten Prozentwerte: auf das naechste kleinere Vielfache von 5 abrunden (nie aufrunden), 0..100.
int floor5(int percent) => (percent.clamp(0, 100) ~/ 5) * 5;

String percentText(int percent) => '${floor5(percent)} %';

/// "Bei 20 min täglich: ca. 75 % in der Prüfung", bei wenig Daten mit ", noch grob".
String projectionLine(Forecast f, int dailyMinutes) =>
    'Bei $dailyMinutes min täglich: ca. ${percentText(f.scoreAtExamPercent)} in der Prüfung${f.confidence == 'low' ? ', noch grob' : ''}';

/// Beschriftung einer Lernzeit-Stufe in den Einstellungen: "20 min · ca. 70 %", ohne Projektion nur "20 min".
String minutesChoiceLabel(Forecast? f, int minutes) {
  if (f != null) {
    for (final p in f.projections) {
      if (p.minutes == minutes) {
        return '$minutes min · ca. ${percentText(p.scoreAtExamPercent)}';
      }
    }
  }
  return '$minutes min';
}

/// Lernzeit fuer "Los": Tagesziel minus heute schon Gelerntes, mindestens 3 Minuten.
int remainingMinutes(int dailyMinutes, int doneSecondsToday) =>
    math.max(3, dailyMinutes - doneSecondsToday ~/ 60);

/// Hauptknopf auf Heute: ohne Zeitangabe.
const String startLabel = 'Heute vorbereiten';

/// Sekunden -> grobe Minuten (aufgerundet auf 5 ab 10 Min, sonst auf ganze Minuten, mind. 1).
int roundNeedMinutes(int seconds) {
  final m = (seconds + 59) ~/ 60;
  if (m <= 0) return 1;
  return m < 10 ? m : ((m + 4) ~/ 5) * 5;
}

/// "Noch ca. 25 Min bis zur Prüfung nötig, heute empfohlen: 29 Min". Null, wenn Daten fehlen.
String? formatiereZeitbedarf(TimeNeed? t) {
  if (t == null) return null;
  final need = t.needSeconds;
  final rec = t.recommendedMinutesToday;
  if (need == null || need <= 0 || rec == null || rec <= 0) return null;
  return 'Noch ca. ${roundNeedMinutes(need)} Min bis zur Prüfung nötig, heute empfohlen: $rec Min';
}

const String zeitKnappHinweis =
    'Zeit ist knapp – wir üben die Kernthemen zuerst';

String? zeitKnappText(TimeNeed? t) => t?.tight == true ? zeitKnappHinweis : null;

/// "Abruf: 40 % – selbst gewusst, nicht nur erkannt". Null ohne Wert.
String? abrufText(int? recallPercent) => recallPercent == null
    ? null
    : 'Abruf: ${percentText(recallPercent)} – selbst gewusst, nicht nur erkannt';

/// Zahl im Ring: ohne belastbare Prognose/Wissensstand "0 %" (nie ein Strich), sonst die Zahl in 5er-Schritten.
int ringPercentOrZero(Exam? exam, Forecast? f) =>
    (exam != null && f != null && forecastReliable(exam, f))
        ? ringPercent(exam, f)
        : 0;

String ringLabel(Exam? exam, Forecast? f) =>
    percentText(ringPercentOrZero(exam, f));

/// Abschlusszeile: "Prognose 60 % → 65 %".
String beforeAfterLine(int before, int after) => floor5(before) == floor5(after)
    ? 'Prognose ${percentText(after)}'
    : 'Prognose ${percentText(before)} → ${percentText(after)}';

/// Fuer die Schwache-Themen-Zeile: Kenntnisstand 0..1 als Prozent (abgerundet auf 5er-Schritte).
int masteryPercent(double mastery) => floor5((mastery * 100).toInt());

/// Weniger als 45 Sekunden Rest: das Tagesziel gilt als geschafft (wie im Backend).
bool dayGoalReached(int dailyMinutes, int doneSeconds) =>
    dailyMinutes * 60 - doneSeconds < 45;

/// Kleine Zeile "Heute: 12 von 30 min" (gehoerte plus Fragezeit, abgerundet).
String budgetLine(int dailyMinutes, int doneSeconds) =>
    'Heute: ${doneSeconds ~/ 60} von $dailyMinutes min';

/// Freiwillige Zusatzrunde nach dem Tagesziel: ausdruecklich diese Minuten.
const int extraMinutes = 10;
