import 'dart:math' as math;
import '../data/models.dart';
import 'tasks.dart';

/// Vorgelesen wird nur die Frage.
enum VoicePart {
  question('question');

  final String query;
  const VoicePart(this.query);
}

/// Feste Wiedergabegeschwindigkeit der Stimme (Cloud-Stimme mit erhaltener Tonhoehe, Geraetestimme als Sprechrate). Nur hier festgelegt.
const double speechSpeed = 1.15;

/// Zustand der einen Wiedergabe der App.
enum PlayState { idle, loading, playing, paused }

/// Zentrale Zustandsmaschine des Vorlesens (idle, loading, playing, paused) mit Generationsnummer: [begin] startet eine neue
/// Wiedergabe und macht alle frueheren ungueltig, verspaetete Rueckmeldungen (prepared, completed) eines alten Elements
/// werden mit [isCurrent] verworfen. Es gibt dadurch nie zwei gleichzeitige Wiedergaben.
class PlaybackMachine {
  PlayState _state = PlayState.idle;
  int _generation = 0;

  PlayState get state => _state;
  int get generation => _generation;

  /// Neue Wiedergabe: laedt, alles Vorherige ist ungueltig.
  int begin() {
    _generation++;
    _state = PlayState.loading;
    return _generation;
  }

  /// Gilt die Wiedergabe [gen] noch (nicht ersetzt, nicht gestoppt, nicht beendet)?
  bool isCurrent(int gen) => gen == _generation && _state != PlayState.idle;

  /// Audio ist bereit und startet; false = veraltet, verwerfen.
  bool playing(int gen) {
    if (gen != _generation || _state != PlayState.loading) return false;
    _state = PlayState.playing;
    return true;
  }

  void pause(int gen) {
    if (gen == _generation && _state == PlayState.playing) _state = PlayState.paused;
  }

  void resume(int gen) {
    if (gen == _generation && _state == PlayState.paused) _state = PlayState.playing;
  }

  /// Ende der Wiedergabe [gen]; false = veraltet (eine neuere laeuft).
  bool finish(int gen) {
    if (gen != _generation) return false;
    _state = PlayState.idle;
    return true;
  }

  /// Alles stoppen (neue Karte, Stumm, Verlassen); spaete Rueckmeldungen werden damit ungueltig.
  void stop() {
    _generation++;
    _state = PlayState.idle;
  }
}

/// Soll die Frage jetzt vorgelesen werden? Immer wenn ein Item sichtbar wird und der Ton an ist (auch das erste, auch nach
/// Drehen/App-Wechsel), solange noch nicht geantwortet wurde. Lektionskarten spielen sich selbst ab.
bool shouldSpeakQuestion(bool audioOn, bool? feedback, bool isTeach) =>
    audioOn && feedback == null && !isTeach;

/// Darstellung des Play-/Pause-Knopfes: nur Play oder Pause (kein Ladesymbol; das Warten passiert vor dem Anzeigen der Karte).
enum PlayButtonKind { play, pause }

PlayButtonKind playButtonKind(PlayState state) =>
    state == PlayState.playing ? PlayButtonKind.pause : PlayButtonKind.play;

/// Was ein Tipp auf den Knopf bewirkt.
enum PlayTap { pause, resume, restart }

PlayTap playTap(PlayState state) => switch (state) {
      PlayState.playing => PlayTap.pause,
      PlayState.paused => PlayTap.resume,
      _ => PlayTap.restart,
    };

/// Wartezeit vor dem naechsten Versuch: 1 s, 2 s, 4 s, 8 s ... hoechstens 60 s; ein Retry-After des Servers wird beachtet.
int retryDelayMs(int attempt, [int? retryAfterMs]) {
  final backoff = math.min(1000 << attempt.clamp(0, 6), 60000);
  return math.min(math.max(backoff, retryAfterMs ?? 0), 120000);
}

/// Retry-After-Header (Sekunden) in Millisekunden; null bei fehlendem oder unlesbarem Wert.
int? parseRetryAfter(String? header) {
  final v = int.tryParse(header?.trim() ?? '');
  return (v != null && v >= 0) ? v * 1000 : null;
}

/// Fehlerpolitik im Hoerbuch: leise erneut versuchen, nach 3 Fehlern in Folge die Lektion ruhig ueberspringen (spaeter wiederholen).
sealed class FailureAction {
  const FailureAction();
}

class FailureRetry extends FailureAction {
  final int delayMs;
  const FailureRetry(this.delayMs);

  @override
  bool operator ==(Object other) => other is FailureRetry && other.delayMs == delayMs;

  @override
  int get hashCode => delayMs.hashCode;
}

class FailureSkip extends FailureAction {
  const FailureSkip();

  @override
  bool operator ==(Object other) => other is FailureSkip;

  @override
  int get hashCode => 0;
}

const int audiobookMaxFailures = 3;

FailureAction nextAfterFailure(int failuresInRow) =>
    failuresInRow >= audiobookMaxFailures
        ? const FailureSkip()
        : FailureRetry(retryDelayMs(failuresInRow - 1));

/// Wiedergabe der Runde nur im Vordergrund: Hintergrund (Home, App-Wechsel, Bildschirm aus) stoppt sofort.
bool shouldStopForLifecycle(bool inForeground, PlayState state) =>
    !inForeground && state != PlayState.idle;

/// Nach dem Stopp im Hintergrund startet die Runde nicht von selbst neu: Auto-Play erst wieder beim naechsten Item.
bool mayAutoplay(int? suppressedAt, int index) => suppressedAt != index;

/// Wie lange auf den Start des Streams gewartet wird (das Backend erzeugt das Audio erst): Fragen 12 s, Lektionen 35 s.
const int streamStartQuestionMs = 12000;
const int streamStartLessonMs = 35000;

/// Timeout fuer die Status-Abfrage (GET audio/status).
const int audioStatusTimeoutMs = 6000;

int streamStartTimeoutMs(bool lesson) =>
    lesson ? streamStartLessonMs : streamStartQuestionMs;

/// Was fuer die naechsten Karten schon angestossen wird (Backend-Cache warm halten).
class PrefetchTarget {
  final String id;
  final bool lesson;
  const PrefetchTarget(this.id, this.lesson);

  @override
  bool operator ==(Object other) =>
      other is PrefetchTarget && other.id == id && other.lesson == lesson;

  @override
  int get hashCode => Object.hash(id, lesson);
}

/// Naechste 2 Fragen bzw. die naechste Lektion (Loesungswege haben kein Audio); nie die aktuelle Karte, hoechstens [count] Ziele.
List<PrefetchTarget> prefetchTargets(
  List<Task> tasks,
  int index, {
  int count = 2,
}) {
  final seen = <PrefetchTarget>{};
  final out = <PrefetchTarget>[];
  for (final t in tasks.skip(index + 1).take(count)) {
    final target = switch (t) {
      TaskTeach(:final lesson) => PrefetchTarget(lesson.id, true),
      TaskLesson() => null,
      _ => PrefetchTarget(t.item.id, false),
    };
    if (target != null && seen.add(target)) out.add(target);
  }
  return out;
}

/// Der Audio-Status wird beim Start geprueft und danach hoechstens alle 60 s erneut.
const int audioRecheckMs = 60000;

/// Modus der Runde: mit Ton (Streaming) oder still (ohne Meldung, ohne Dialog, ohne Audio-Symbole).
enum AudioMode { on, silent }

/// Preflight: "Vorlesen aus" -> still (ohne jeden Audio-Aufruf); offline oder ausdruecklich nicht verfuegbar -> still;
/// eingeschaltet und online -> Ton, ausser der Status sagt ausdruecklich "nicht verfuegbar".
AudioMode audioModeFor(bool enabled, bool online, bool? available) =>
    (enabled && online && available != false) ? AudioMode.on : AudioMode.silent;

/// Status-Antwort -> verfuegbar? Nur eine ausdrueckliche Antwort mit available=false UND reason heisst nein; fehlende Antwort = versuchen.
bool statusAvailable(AudioStatus? status) =>
    !(status != null && !status.available && (status.reason?.trim().isNotEmpty ?? false));

/// Darf ueberhaupt eine Audio-Anfrage (Status, Stream, Wortzeiten) gestellt werden? Nur bei eingeschaltetem Vorlesen.
bool mayRequestAudio(bool enabled) => enabled;

/// Beim naechsten Item still erneut pruefen, wenn Ton gewuenscht ist, der Modus still ist und die letzte Pruefung lange her ist.
bool shouldRecheckAudio(AudioMode mode, bool enabled, int lastCheckMs, int nowMs) =>
    enabled && mode == AudioMode.silent && nowMs - lastCheckMs >= audioRecheckMs;

/// So viele Karten in Folge ohne Erfolg, bis das Audio fuer die ganze Runde als ausgefallen gilt (dann verschwindet der Knopf).
const int audioFailLimit = 3;

/// Nach dem Ausblenden fruehestens nach dieser Zeit wieder einblenden (Hysterese gegen Flackern).
const int audioHideHysteresisMs = 60000;

/// Sichtbarkeit des Play/Pause-Knopfs fuer die ganze Runde: nicht von kurzlebigen Zustaenden abhaengig.
class AudioHealth {
  final int limit;
  final int hysteresisMs;
  int _failures = 0;
  int? _hiddenAt;

  AudioHealth({
    this.limit = audioFailLimit,
    this.hysteresisMs = audioHideHysteresisMs,
  });

  void success(int nowMs) {
    _failures = 0;
    if (_hiddenAt != null && nowMs - _hiddenAt! >= hysteresisMs) {
      _hiddenAt = null;
    }
  }

  void failure(int nowMs) {
    _failures++;
    if (_failures >= limit && _hiddenAt == null) _hiddenAt = nowMs;
  }

  void reset() {
    _failures = 0;
    _hiddenAt = null;
  }

  bool get visible => _hiddenAt == null;
}

/// Fehler-Meldung ans Backend: hoechstens 1 je 10 s und 20 je Tag.
const int clientLogMinGapMs = 10000;
const int clientLogMaxPerDay = 20;

bool clientLogAllowed(int nowMs, int lastMs, int countToday) =>
    nowMs - lastMs >= clientLogMinGapMs && countToday < clientLogMaxPerDay;

/// Detailtext hoechstens 120 Zeichen, ohne Zeilenumbrueche.
String clientLogDetail(String text) {
  final clean = text.replaceAll('\n', ' ');
  return clean.length <= 120 ? clean : clean.substring(0, 120);
}

/// Kurzer Fehlercode fuer Log und Meldung: `http_503`, `player_error:1:-1004`, `timeout`, `header:<typ>`.
String audioErrorCode({
  int? httpCode,
  int? playerWhat,
  int? playerExtra,
  bool timeout = false,
  String? contentType,
}) {
  if (httpCode != null && (httpCode < 200 || httpCode > 299)) return 'http_$httpCode';
  if (contentType != null &&
      !contentType.startsWith('audio/') &&
      contentType != 'application/octet-stream') {
    final sub = contentType.length <= 40 ? contentType : contentType.substring(0, 40);
    return 'header:$sub';
  }
  if (playerWhat != null) return 'player_error:$playerWhat:${playerExtra ?? 0}';
  if (timeout) return 'timeout';
  return 'error';
}
