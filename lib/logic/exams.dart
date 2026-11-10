import 'dart:math' as math;
import '../data/models.dart';

const _weekdaysDe = ['Mo.', 'Di.', 'Mi.', 'Do.', 'Fr.', 'Sa.', 'So.'];
const _monthsDe = [
  'Jan.',
  'Feb.',
  'März',
  'Apr.',
  'Mai',
  'Juni',
  'Juli',
  'Aug.',
  'Sep.',
  'Okt.',
  'Nov.',
  'Dez.',
];

/// Berechnet den UTC-Offset für Europe/Berlin (+1 im Winter / CET, +2 im Sommer / CEST).
int _berlinOffsetHours(DateTime utc) {
  final y = utc.year;
  int lastSunday(int month) {
    final lastDay = DateTime.utc(y, month + 1, 0);
    return lastDay.day - (lastDay.weekday % 7);
  }

  final dstStart = DateTime.utc(y, 3, lastSunday(3), 1);
  final dstEnd = DateTime.utc(y, 10, lastSunday(10), 1);
  return (!utc.isBefore(dstStart) && utc.isBefore(dstEnd)) ? 2 : 1;
}

/// Konvertiert einen UTC-Zeitpunkt in die lokale Berliner Wanduhrzeit (als UTC-DateTime repräsentiert).
DateTime toBerlinWall(DateTime instant) {
  final utc = instant.toUtc();
  return utc.add(Duration(hours: _berlinOffsetHours(utc)));
}

/// Konvertiert eine Berliner Wanduhrzeit (Jahr, Monat, Tag, Stunde, Minute) in einen echten UTC-Zeitpunkt.
DateTime fromBerlinWall(int year, int month, int day, [int hour = 0, int minute = 0]) {
  final approxUtc = DateTime.utc(year, month, day, hour - 1, minute);
  final offset = _berlinOffsetHours(approxUtc);
  return DateTime.utc(year, month, day, hour - offset, minute);
}

DateTime? examStart(Exam e) =>
    e.startsAt.isEmpty ? null : DateTime.tryParse(e.startsAt)?.toUtc();

/// Dauerhaftes Lernen ohne Termin.
bool isOpen(Exam e) => e.kind == 'open';

bool isOver(Exam e, DateTime now) {
  if (e.status == 'over') return true;
  final start = examStart(e);
  return start != null && !start.isAfter(now.toUtc());
}

/// Aktive Pruefung: die zuletzt gewaehlte (falls es sie noch gibt), sonst die naechste anstehende,
/// sonst die zuletzt vergangene. Ohne Pruefungen null.
Exam? pickActiveExam(List<Exam> exams, String? chosenId, DateTime now) {
  for (final e in exams) {
    if (e.id == chosenId) return e;
  }
  final maxFuture = DateTime.utc(9999);
  final minPast = DateTime.utc(1970);
  final upcoming = exams.where((e) => !isOver(e, now)).toList()
    ..sort((a, b) => (examStart(a) ?? maxFuture).compareTo(examStart(b) ?? maxFuture));
  if (upcoming.isNotEmpty) return upcoming.first;
  if (exams.isEmpty) return null;
  final past = [...exams]
    ..sort((a, b) => (examStart(b) ?? minPast).compareTo(examStart(a) ?? minPast));
  return past.first;
}

/// Pruefungen fuer das Popup: anstehende zuerst (naechste oben), dann vergangene (juengste oben).
List<Exam> sortExams(List<Exam> exams, DateTime now) {
  final maxFuture = DateTime.utc(9999);
  final minPast = DateTime.utc(1970);
  final upcoming = <Exam>[];
  final past = <Exam>[];
  for (final e in exams) {
    (isOver(e, now) ? past : upcoming).add(e);
  }
  upcoming.sort((a, b) => (examStart(a) ?? maxFuture).compareTo(examStart(b) ?? maxFuture));
  past.sort((a, b) => (examStart(b) ?? minPast).compareTo(examStart(a) ?? minPast));
  return [...upcoming, ...past];
}

/// "Fr., 13. Nov., 09:00" aus Datum und Uhrzeit; bei unlesbaren Werten die Rohwerte.
String formatExamWhen(String date, String time) {
  final dParts = date.split('-');
  final tParts = time.split(':');
  if (dParts.length != 3 || tParts.length < 2) return '$date $time';
  final y = int.tryParse(dParts[0]);
  final m = int.tryParse(dParts[1]);
  final d = int.tryParse(dParts[2]);
  final hh = int.tryParse(tParts[0]);
  final mm = int.tryParse(tParts[1]);
  if (y == null || m == null || d == null || hh == null || mm == null) {
    return '$date $time';
  }
  if (m < 1 || m > 12 || d < 1 || d > 31 || hh < 0 || hh > 23 || mm < 0 || mm > 59) {
    return '$date $time';
  }
  final dt = DateTime.utc(y, m, d, hh, mm);
  if (dt.year != y || dt.month != m || dt.day != d) return '$date $time';
  final wd = _weekdaysDe[dt.weekday - 1];
  final mo = _monthsDe[m - 1];
  final hhStr = hh.toString().padLeft(2, '0');
  final mmStr = mm.toString().padLeft(2, '0');
  return '$wd, $d. $mo, $hhStr:$mmStr';
}

/// Liegt der Zeitpunkt (Europe/Berlin) nicht mehr in der Zukunft?
bool isInPast(DateTime date, (int hour, int minute) time, DateTime now) {
  final instant = fromBerlinWall(date.year, date.month, date.day, time.$1, time.$2);
  return !instant.isAfter(now.toUtc());
}

/// Heutiges Datum in Berlin als Untergrenze fuer den Datumswaehler.
DateTime todayBerlin(DateTime now) {
  final b = toBerlinWall(now);
  return DateTime.utc(b.year, b.month, b.day);
}

const List<String> quickTimes = ['08:00', '09:00', '10:00', '13:00'];
const List<int> dailyMinuteChoices = [10, 20, 30, 45, 60];

/// Thementitel je Themen-Id fuer die Zeile ueber der Frage; bei Untergruppen "Gruppe › Titel".
Map<String, String> topicLabels(List<Topic> topics) {
  final byId = {for (final t in topics) t.id: t};
  return {
    for (final t in topics)
      t.id: switch (byId[t.parentId]?.title.trim()) {
        final String p when p.isNotEmpty => '$p › ${t.title}',
        _ => t.title,
      },
  };
}

/// Vorwissen: Auswahlwerte fuer die Prüfung (Schluessel des Backends).
const List<String> priorKeys = ['none', 'some', 'much'];

String priorLabel(String key) => switch (key) {
      'none' => 'Nichts',
      'much' => 'Viel',
      _ => 'Etwas',
    };

/// Kurzzeile zum Lernplan: "Heute: Rechnen und Vertragsrecht" bzw. "3 von 8 Kapiteln gehört"; null ohne Plan.
String? planLine(Plan? plan) {
  if (plan == null || plan.chaptersTotal <= 0) return null;
  return switch (plan.todayChapters) {
    [final a] => 'Heute: $a',
    [final a, final b] => 'Heute: $a und $b',
    [final a, final b, ...final rest] => 'Heute: $a, $b und ${rest.length} weitere',
    _ => '${plan.chaptersHeard} von ${plan.chaptersTotal} Kapiteln gehört',
  };
}

/// Soll eine Prognose-Zahl gezeigt werden? Nur bei fertiger Pruefung und wenn schon etwas beantwortet wurde.
bool forecastReliable(Exam? exam, Forecast? f) =>
    exam != null &&
    f != null &&
    exam.status == 'ready' &&
    (exam.kind == 'open'
        ? f.knowledgePercent != null
        : f.coveragePercent > 0 && f.confidence != 'none');

/// Stand jetzt, oder null ohne belastbare Prognose (confidence "none": die Zahlen fehlen im JSON).
extension ForecastScoreExt on Forecast {
  int? scoreOrNull() => mode == 'open'
      ? knowledgePercent
      : (confidence == 'none' || coveragePercent <= 0)
          ? null
          : scoreNowPercent;
}

/// Zahl im Ring: bei offenem Lernen der Wissensstand, sonst der Stand fuer heute.
int ringPercent(Exam exam, Forecast f) =>
    isOpen(exam) ? (f.knowledgePercent ?? 0) : f.scoreNowPercent;

/// Zeile in der Liste: Termin oder "Kein Prüfungsdatum" (bei offenem Lernen), dahinter "vorbei".
String examSubtitle(Exam e, DateTime now) => isOpen(e)
    ? 'Kein Prüfungsdatum'
    : '${formatExamWhen(e.date, e.time)}${isOver(e, now) ? ' · vorbei' : ''}';

/// Seiten der Neu-Pruefung-Folge: bei "Dauerhaft lernen" entfaellt die Datum-/Uhrzeitseite.
enum NewExamPage { kind, name, when, material, description, prior, daily }

List<NewExamPage> newExamPages(bool open) => [
      for (final p in NewExamPage.values)
        if (!open || p != NewExamPage.when) p,
    ];

/// Erster Buchstabe gross (Satz- oder Zeilenanfang), sonst unveraendert.
String capFirst(String text) =>
    text.isEmpty ? text : '${text[0].toUpperCase()}${text.substring(1)}';

/// Relative Angabe bis zum Prüfungstermin, aus KALENDERTAGEN in Berlin (oder [useUtc]):
/// heute, morgen, übermorgen, "in N Tagen"; am Termintag zusätzlich "in N Std" bzw. "in N Min".
String relativeDayLabel(
  DateTime? startsAt,
  DateTime now, {
  bool useBerlinZone = true,
  int? utcOffsetHours,
  bool startOfSentence = false,
}) {
  final nowUtc = now.toUtc();
  final startUtc = startsAt?.toUtc();
  final String text;
  if (startUtc == null || !startUtc.isAfter(nowUtc)) {
    text = 'vorbei';
  } else {
    DateTime wall(DateTime utc) => utcOffsetHours != null
        ? utc.add(Duration(hours: utcOffsetHours))
        : (useBerlinZone ? toBerlinWall(utc) : utc.toLocal());
    final nowWall = wall(nowUtc);
    final startWall = wall(startUtc);
    final nowDate = DateTime.utc(nowWall.year, nowWall.month, nowWall.day);
    final startDate = DateTime.utc(startWall.year, startWall.month, startWall.day);
    final days = startDate.difference(nowDate).inDays;
    final total = startUtc.difference(nowUtc);
    text = switch (days) {
      <= 0 => total.inHours >= 1
          ? 'heute, in ${total.inHours} Std'
          : 'in ${math.max(1, total.inMinutes)} Min',
      1 => 'morgen',
      2 => 'übermorgen',
      _ => 'in $days Tagen',
    };
  }
  return startOfSentence ? capFirst(text) : text;
}

/// Eintraege hinter "Mehr" auf Heute, in der Reihenfolge ihrer Gruppen (Ueben, Heute, Pruefung).
enum MoreEntry { questionsOnly, listenOnly, plan, used, streak, material, settings }

List<MoreEntry> moreEntries({
  required bool hasLessons,
  required bool hasPlan,
  required int? streak,
}) =>
    [
      if (hasLessons) ...[MoreEntry.questionsOnly, MoreEntry.listenOnly],
      if (hasPlan) MoreEntry.plan,
      MoreEntry.used,
      if (streak != null && streak > 0) MoreEntry.streak,
      MoreEntry.material,
      MoreEntry.settings,
    ];
