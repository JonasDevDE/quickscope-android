import 'dart:convert';
import 'dart:math' as math;
import 'dart:typed_data';
import 'tasks.dart';

/// Alle Intensitaets-Konstanten fuer Feedback und Feiern an einer Stelle.
abstract final class FeedbackTuning {
  static const int xpBase = 10;

  /// Zusatz-XP je Schwierigkeitsstufe ueber 3.
  static const int xpPerExtraDifficulty = 3;

  /// Alle so viele richtige in Folge gibt es Serienbonus-XP (und eine Mini-Feier).
  static const int mediumEvery = 5;
  static const int bigEvery = 10;
  static const int xpStreakBonus = 5;
  static const int xpStreakCap = 15;

  /// Serienzaehler erst ab so vielen in Folge zeigen.
  static const int streakVisibleFrom = 2;

  /// "Gleich nochmal": falsch beantwortete Aufgaben kommen nach 3 bis 5 Aufgaben wieder.
  static const int retryMinGap = 3;
  static const int retryMaxGap = 5;

  /// Ab dieser Kenntnis (Themen-Mastery) "sitzt" ein Thema.
  static const double sitsThreshold = 0.8;

  /// Partikel je Feier.
  static const int burstSmall = 14;
  static const int burstMedium = 26;
  static const int burstBig = 40;
  static const int confetti = 60;

  /// Toene: kurz und leise (Anteil der Vollaussteuerung).
  static const double sfxVolume = 0.4;
  static const int sampleRate = 22050;
}

enum Celebration { small, medium, big }

/// Welche Feier bei welcher Serie: alle 10 gross, alle 5 mittel, sonst klein.
Celebration celebrationFor(int streak) {
  if (streak > 0 && streak % FeedbackTuning.bigEvery == 0) return Celebration.big;
  if (streak > 0 && streak % FeedbackTuning.mediumEvery == 0) return Celebration.medium;
  return Celebration.small;
}

/// XP einer richtigen Antwort: Grundwert, mehr bei schwierigen Aufgaben (Stufe 4/5) und bei Serie (je 5 in Folge).
int xpFor(int difficulty, int streakAfter) {
  final diff = math.max(0, difficulty - 3) * FeedbackTuning.xpPerExtraDifficulty;
  final streak = math.min(
    (streakAfter ~/ FeedbackTuning.mediumEvery) * FeedbackTuning.xpStreakBonus,
    FeedbackTuning.xpStreakCap,
  );
  return FeedbackTuning.xpBase + diff + streak;
}

/// Ergebnis einer Antwort fuer Anzeige, Ton und Haptik.
class Outcome {
  final bool correct;
  final bool retry;
  final int streak;
  final int bestStreak;
  final int xp;
  final Celebration celebration;

  /// Erste richtige Antwort nach einem Fehler.
  final bool comeback;

  const Outcome({
    required this.correct,
    required this.retry,
    required this.streak,
    required this.bestStreak,
    required this.xp,
    required this.celebration,
    required this.comeback,
  });
}

/// Zaehlt Serie, Bestserie, XP und Treffer einer Runde. Wiederholungen ("Gleich nochmal") geben keine XP.
class FeedbackTracker {
  int _streak = 0;
  int _best = 0;
  int _xp = 0;
  int _correct = 0;
  int _answers = 0;
  bool _lastWasWrong = false;

  int get streak => _streak;
  int get best => _best;
  int get xp => _xp;
  int get correct => _correct;
  int get answers => _answers;

  Outcome record(bool isCorrect, int difficulty, {bool retry = false}) {
    if (!retry) _answers++;
    if (!isCorrect) {
      _streak = 0;
      _lastWasWrong = true;
      return Outcome(
        correct: false,
        retry: retry,
        streak: 0,
        bestStreak: _best,
        xp: 0,
        celebration: Celebration.small,
        comeback: false,
      );
    }
    if (!retry) _correct++;
    _streak++;
    _best = math.max(_best, _streak);
    final gained = retry ? 0 : xpFor(difficulty, _streak);
    _xp += gained;
    final comeback = _lastWasWrong;
    _lastWasWrong = false;
    return Outcome(
      correct: true,
      retry: retry,
      streak: _streak,
      bestStreak: _best,
      xp: gained,
      celebration: celebrationFor(_streak),
      comeback: comeback,
    );
  }
}

/// Einfuegeposition fuer eine falsch beantwortete Aufgabe: nach 3 bis 5 weiteren Aufgaben, hoechstens ans Ende.
int retryInsertIndex(int current, int size, String key) {
  const span = FeedbackTuning.retryMaxGap - FeedbackTuning.retryMinGap + 1;
  final gap = FeedbackTuning.retryMinGap + (key.hashCode.abs() % span);
  return math.min(current + 1 + gap, size);
}

/// Themen, die zwischen zwei Staenden ueber die Schwelle gekommen sind (vorher darunter, jetzt darueber).
List<String> newlySitting(
  Map<String, double> prev,
  Map<String, double> now, {
  double threshold = FeedbackTuning.sitsThreshold,
}) =>
    [
      for (final entry in now.entries)
        if (entry.value >= threshold && (prev[entry.key] ?? 0.0) < threshold) entry.key,
    ];

int _daysBetween(DateTime a, DateTime b) =>
    DateTime.utc(b.year, b.month, b.day)
        .difference(DateTime.utc(a.year, a.month, a.day))
        .inDays;

/// Neue Tagesserie, wenn heute eine Runde abgeschlossen wurde.
int nextDayStreak(DateTime? last, int streak, DateTime today) {
  if (last == null) return 1;
  final diff = _daysBetween(last, today);
  if (diff == 0) return math.max(1, streak);
  if (diff == 1) return streak + 1;
  return 1;
}

/// Anzuzeigende Tagesserie: nur wenn sie noch laeuft (zuletzt heute oder gestern) und positiv ist; sonst null (ausblenden).
int? visibleDayStreak(DateTime? last, int streak, DateTime today) {
  if (last == null || streak <= 0) return null;
  final diff = _daysBetween(last, today);
  return (diff >= 0 && diff <= 1) ? streak : null;
}

// ---------------------------------------------------------------- Toene (synthetisch, ohne Dateien)

/// Ein Ton: Frequenz in Hz, Dauer in ms, optional gleitend zu [glideTo].
class Note {
  final double hz;
  final int ms;
  final double? glideTo;

  const Note(this.hz, this.ms, [this.glideTo]);

  @override
  bool operator ==(Object other) =>
      other is Note && other.hz == hz && other.ms == ms && other.glideTo == glideTo;

  @override
  int get hashCode => Object.hash(hz, ms, glideTo);
}

abstract final class Tones {
  static const double _c5 = 523.25;
  static const double _e5 = 659.25;
  static const double _g5 = 783.99;
  static const double _c6 = 1046.5;
  static const double _e6 = 1318.5;
  static const double _g6 = 1568.0;

  /// Richtig: kurzes helles "Pling", zwei aufsteigende Toene (grosse Terz), ca. 200 ms.
  static const List<Note> correct = [Note(_c6, 80), Note(_e6, 120)];

  /// Serie von 3: etwas hoeher (Quinte).
  static const List<Note> streak3 = [Note(_e6, 80), Note(_g6, 120)];

  /// 5 in Folge: kleine Fanfare.
  static const List<Note> medium = [
    Note(_e5, 70),
    Note(_g5, 70),
    Note(_c6, 70),
    Note(_e6, 190),
  ];

  /// 10 in Folge: noch hoeher, mit Schluss-Glanz.
  static const List<Note> big = [
    Note(_g5, 70),
    Note(_c6, 70),
    Note(_e6, 70),
    Note(_g6, 110),
    Note(_e6, 60),
    Note(_g6, 230),
  ];

  /// Abschlussklang: aufsteigende Tonleiter-Fanfare.
  static const List<Note> done = [
    Note(_c5, 90),
    Note(_e5, 90),
    Note(_g5, 90),
    Note(_c6, 110),
    Note(_g5, 70),
    Note(_c6, 120),
    Note(_e6, 320),
  ];

  static const List<List<Note>> all = [correct, streak3, medium, big, done];
}

/// Welcher Ton zu welchem Ergebnis: jede richtige Antwort bekommt sofort ein Pling (bei 3er-Serien etwas hoeher,
/// bei 5er/10er eine kleine Fanfare); falsche Antworten und "Weiss nicht" bleiben ohne Ton.
List<Note>? soundFor(Outcome outcome) {
  if (!outcome.correct) return null;
  if (outcome.streak > 0 && outcome.streak % FeedbackTuning.bigEvery == 0) return Tones.big;
  if (outcome.streak > 0 && outcome.streak % FeedbackTuning.mediumEvery == 0) return Tones.medium;
  if (outcome.streak > 0 && outcome.streak % 3 == 0) return Tones.streak3;
  return Tones.correct;
}

/// Erzeugt 16-Bit-PCM (mono): Sinus mit kurzem Anschlag und weichem Abklingen, [volume] 0..1 der Vollaussteuerung.
Int16List synth(
  List<Note> notes, {
  double volume = FeedbackTuning.sfxVolume,
  int sampleRate = FeedbackTuning.sampleRate,
}) {
  final total = notes.fold<int>(0, (sum, n) => sum + (n.ms * sampleRate ~/ 1000));
  final out = Int16List(total);
  var pos = 0;
  var phase = 0.0;
  for (final n in notes) {
    final len = n.ms * sampleRate ~/ 1000;
    final attack = math.max(1, sampleRate * 5 ~/ 1000);
    for (var i = 0; i < len; i++) {
      final t = i / len;
      final hz = n.glideTo != null ? n.hz + (n.glideTo! - n.hz) * t : n.hz;
      phase += 2 * math.pi * hz / sampleRate;
      final env = math.min(1.0, i / attack) * math.exp(-3.0 * t) * (1 - t * t * t * t);
      out[pos++] = (math.sin(phase) * env * volume * 32767).toInt();
    }
  }
  return out;
}

/// 16-Bit-PCM (mono, little endian) als WAV-Datei mit 44-Byte-Kopf.
Uint8List wavBytes(
  Int16List pcm, {
  int sampleRate = FeedbackTuning.sampleRate,
}) {
  final dataLen = pcm.length * 2;
  final out = ByteData(44 + dataLen);
  var offset = 0;
  void writeAscii(String s) {
    for (final b in ascii.encode(s)) {
      out.setUint8(offset++, b);
    }
  }

  void writeInt32(int v) {
    out.setUint32(offset, v, Endian.little);
    offset += 4;
  }

  void writeInt16(int v) {
    out.setInt16(offset, v, Endian.little);
    offset += 2;
  }

  writeAscii('RIFF');
  writeInt32(36 + dataLen);
  writeAscii('WAVE');
  writeAscii('fmt ');
  writeInt32(16);
  writeInt16(1);
  writeInt16(1);
  writeInt32(sampleRate);
  writeInt32(sampleRate * 2);
  writeInt16(2);
  writeInt16(16);
  writeAscii('data');
  writeInt32(dataLen);
  for (final s in pcm) {
    writeInt16(s);
  }
  return out.buffer.asUint8List();
}

/// Ein richtig verbundenes Paar bei "Zuordnen" bekommt sofort ein Pling; falsch und aufgedeckt bleiben ohne Ton.
List<Note>? soundForMatch(MatchResult result) =>
    result == MatchResult.right ? Tones.correct : null;

/// Wiederholung einer falsch beantworteten Frage nie vor eine anstehende Lektion: liegt eine Lektionskarte im Fenster bis [at], kommt sie direkt danach.
int afterUpcomingLessons(List<Task> tasks, int current, int at) {
  final end = math.min(at + 1, tasks.length);
  int? lastTeach;
  for (var i = current + 1; i < end; i++) {
    if (tasks[i] is TaskTeach) lastTeach = i;
  }
  if (lastTeach == null) return at;
  return math.min(math.max(at, lastTeach + 1), tasks.length);
}

/// Knopf der Lektionskarte in der Runde: "Weiter zum Test" nur, wenn danach eine Frage folgt, sonst "Weiter".
String teachButtonLabel(Task? next) =>
    (next != null && next is! TaskTeach && next is! TaskLesson)
        ? 'Weiter zum Test'
        : 'Weiter';

/// Einsortieren: ein richtig zugeordnetes Element bekommt sofort ein Pling; falsch und aufgedeckt bleiben ohne Ton.
List<Note>? soundForBucket(BucketResult? result) =>
    result == BucketResult.right ? Tones.correct : null;
