import 'dart:async';

import '../data/models.dart';

/// Stand der Hervorhebung: [spoken] Woerter sind fertig gesprochen, [active] ist das aktuelle Wort (-1 = gerade keins).
class WordPos {
  final int spoken;
  final int active;
  const WordPos(this.spoken, this.active);

  @override
  bool operator ==(Object other) =>
      other is WordPos && other.spoken == spoken && other.active == active;

  @override
  int get hashCode => Object.hash(spoken, active);
}

/// Wort zur Zeit [tMs] (Inhaltszeit der Audiodatei). Binaersuche nach dem letzten Wort, das schon begonnen hat:
/// liegt [tMs] darin, ist es das aktuelle; in Luecken, vor dem Start und nach dem Ende gibt es kein aktuelles Wort.
WordPos wordPosAt(List<WordTiming> words, int tMs) {
  var lo = 0;
  var hi = words.length - 1;
  var last = -1;
  while (lo <= hi) {
    final mid = (lo + hi) >>> 1;
    if (words[mid].fromMs <= tMs) {
      last = mid;
      lo = mid + 1;
    } else {
      hi = mid - 1;
    }
  }
  if (last < 0) return const WordPos(0, -1);
  return tMs < words[last].toMs ? WordPos(last, last) : WordPos(last + 1, -1);
}

/// Passt die Zeitung zum angezeigten Text? Nur dann darf hervorgehoben werden (nie falsch markieren).
bool timingsMatch(String display, AudioTimings t) {
  if (t.words.isEmpty || t.text != display) return false;
  var prevEnd = 0;
  for (final w in t.words) {
    if (w.start < prevEnd ||
        w.end <= w.start ||
        w.end > display.length ||
        w.toMs < w.fromMs) {
      return false;
    }
    prevEnd = w.end;
  }
  return true;
}

/// Holt die Wortzeiten (Einzelabruf): bei Fehler, Leere oder Abweichung bis zu [maxAttempts] Versuche,
/// Wartezeit 700 ms * Versuch. Jeder Fehlversuch geht an [onFail]. [cancelled] bricht ab (Wiedergabe beendet).
Future<AudioTimings?> fetchMatchingTimings(
  Future<AudioTimings?> Function() fetch,
  String displayText, {
  int maxAttempts = 3,
  Duration unit = const Duration(milliseconds: 700),
  bool Function()? cancelled,
  void Function(String msg)? onFail,
}) async {
  for (var attempt = 1; attempt <= maxAttempts; attempt++) {
    if (cancelled?.call() ?? false) return null;
    AudioTimings? got;
    try {
      got = await fetch();
    } catch (_) {
      got = null;
    }
    if (got == null) {
      onFail?.call('Wortzeiten Versuch $attempt: keine Antwort');
    } else if (timingsMatch(displayText, got)) {
      return got;
    } else {
      onFail?.call(
        'Wortzeiten Versuch $attempt: passen nicht (Text ${got.text.length} vs ${displayText.length} Zeichen, ${got.words.length} Woerter)',
      );
    }
    await Future<void>.delayed(unit * attempt);
  }
  return null;
}

/// Woerter eines Textes ohne Zeiten (fuer die Geraetestimme, die nur Zeichenpositionen meldet).
List<WordTiming> splitWords(String text) => [
      for (final m in RegExp(r'\S+').allMatches(text))
        WordTiming(start: m.start, end: m.end),
    ];

/// Index des Wortes, das Zeichen [charIndex] enthaelt oder als naechstes folgt; -1, wenn keins.
int wordAtChar(List<WordTiming> words, int charIndex) {
  var lo = 0;
  var hi = words.length - 1;
  var found = -1;
  while (lo <= hi) {
    final mid = (lo + hi) >>> 1;
    if (words[mid].end > charIndex) {
      found = mid;
      hi = mid - 1;
    } else {
      lo = mid + 1;
    }
  }
  return found;
}
