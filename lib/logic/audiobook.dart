import 'dart:math' as math;
import '../data/models.dart';

/// Ab diesem Anteil gilt eine Lektion als gehoert (wie im Backend).
const double heardThreshold = 0.8;

/// Wie viele Lektionen vorab angefordert werden.
const int audiobookAhead = 2;

/// Stichwort, das zur Zeit [positionMs] hervorgehoben wird: gleichmaessig ueber die Lektionsdauer verteilt; -1 ohne Stichworte/Dauer.
int activeKeyPoint(int count, int positionMs, int durationMs) {
  if (count <= 0 || durationMs <= 0) return -1;
  final f = positionMs / durationMs;
  return math.min(count - 1, math.max(0, (f * count).toInt()));
}

/// Anteil gehoert (0..1) fuer die Meldung an das Backend.
double heardFraction(int positionMs, int durationMs, bool finished) {
  if (finished) return 1.0;
  if (durationMs <= 0) return 0.0;
  return (positionMs / durationMs).clamp(0.0, 1.0);
}

/// "Kapitel 2 von 6" (Reihenfolge ab 1); leer, wenn die Gesamtzahl unbekannt ist.
String chapterLabel(int order, int total) =>
    (order > 0 && total > 0) ? 'Kapitel $order von $total' : '';

/// Eselsbruecke erst gegen Ende der Lektion zeigen (letztes Zehntel).
bool showMnemonic(int positionMs, int durationMs) =>
    durationMs > 0 && positionMs >= durationMs * 0.9;

/// Warteschlange des Hoerbuchs: zeigt auf die aktuelle Lektion; Weiter/Zurueck bleiben in den Grenzen, am Ende ist es fertig.
/// Liefert zusaetzlich, welche Lektionen als naechstes vorab angefordert werden sollen.
class AudiobookQueue {
  final List<AudiobookItem> items;
  int _index = 0;

  AudiobookQueue(this.items);

  int get index => _index;

  AudiobookItem? get current =>
      (_index >= 0 && _index < items.length) ? items[_index] : null;

  bool get finished => _index >= items.length;

  bool next() {
    if (_index >= items.length) return false;
    _index++;
    return true;
  }

  bool previous() {
    if (_index <= 0) return false;
    _index--;
    return true;
  }

  void jumpTo(int i) {
    _index = i.clamp(0, items.length);
  }

  /// Ids der naechsten [count] Lektionen ab der aktuellen (inklusive) fuer den Prefetch.
  List<String> ahead({int count = audiobookAhead + 1}) {
    return items.skip(_index).take(count).map((e) => e.lessonId).toList();
  }

  /// Restzeit in Sekunden (nach Schaetzung), fuer eine ruhige Anzeige.
  int remainingSeconds() {
    return items.skip(_index).fold<int>(0, (sum, e) => sum + e.estSeconds);
  }
}

/// Wie eine Runde gestartet wird: "Los" (Lektionen, dann Fragen), nur Fragen oder nur Zuhoeren (Hoerbuch).
enum StartMode { all, questionsOnly, listenOnly }

/// Zusaetzliche kleine Aktionen unter "Los": nur wenn es Lektionen gibt; "Nur Fragen" ist immer nutzbar, auch ohne gehoerte Lektion.
List<StartMode> secondaryModes(bool hasLessons) => hasLessons
    ? const [StartMode.questionsOnly, StartMode.listenOnly]
    : const [];

/// Eintraege der Session je Modus: "Nur Fragen" laesst alle Lektionen weg (bis das Backend einen Parameter dafuer hat).
List<T> filterForMode<T>(
  List<T> entries,
  bool Function(T) isLesson,
  StartMode mode,
) {
  if (mode == StartMode.questionsOnly) {
    return entries.where((e) => !isLesson(e)).toList();
  }
  return entries;
}
