import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:just_audio/just_audio.dart';
import '../data/app_log.dart';
import '../data/audio_client.dart';
import '../data/models.dart';
import '../data/network.dart';
import '../logic/audio.dart';
import '../logic/highlight.dart';

/// Zustand der Live-Hervorhebung fuer [text]; [spoken] Woerter sind fertig, [active] ist das aktuelle Wort (-1 = keins).
class Highlight {
  final String? text;
  final List<WordTiming> words;
  final int spoken;
  final int active;

  const Highlight({
    this.text,
    this.words = const [],
    this.spoken = 0,
    this.active = -1,
  });

  Highlight copyWith({
    String? text,
    List<WordTiming>? words,
    int? spoken,
    int? active,
  }) =>
      Highlight(
        text: text ?? this.text,
        words: words ?? this.words,
        spoken: spoken ?? this.spoken,
        active: active ?? this.active,
      );
}

sealed class _Attempt {
  const _Attempt();
}

class _AttemptPlayed extends _Attempt {
  const _AttemptPlayed();
}

class _AttemptTimeout extends _Attempt {
  const _AttemptTimeout();
}

class _AttemptError extends _Attempt {
  final String code;
  final bool started;
  const _AttemptError(this.code, this.started);
}

/// Vorlesen: EINE zentrale Instanz fuer die ganze App.
/// Eine Zustandsmaschine ([PlaybackMachine]: idle, loading, playing, paused) mit Generationsnummer sorgt dafuer, dass nie
/// zwei Wiedergaben gleichzeitig laufen und verspaetete Rueckmeldungen alter Elemente verworfen werden.
class Voice extends ChangeNotifier {
  final AudioClient _client;
  final Network _network;

  final PlaybackMachine machine = PlaybackMachine();
  final AudioHealth _health = AudioHealth();

  PlayState _playState = PlayState.idle;
  bool _buttonVisible = true;
  Highlight _highlight = const Highlight();

  AudioPlayer? _player;
  VoidCallback? _cancelHook;

  Voice(this._client, this._network);

  PlayState get playState => _playState;
  bool get buttonVisible => _buttonVisible;
  Highlight get highlight => _highlight;

  void _sync() {
    if (_playState != machine.state) {
      _playState = machine.state;
      notifyListeners();
    }
  }

  /// Pause nur fuer die aktuelle Karte; die naechste Karte startet wieder von selbst.
  void pause() {
    final g = machine.generation;
    final p = _player;
    if (p != null && p.playing) {
      unawaited(p.pause());
      machine.pause(g);
      _sync();
    }
  }

  void resume() {
    final g = machine.generation;
    final p = _player;
    if (p != null && machine.state == PlayState.paused) {
      unawaited(p.play());
      machine.resume(g);
      _sync();
    }
  }

  /// Neue Runde: Knopf wieder sichtbar, Fehlerzaehler zurueck.
  void resetHealth() {
    _health.reset();
    _buttonVisible = true;
    notifyListeners();
  }

  /// Liest die Frage per Live-Streaming vor. [displayText]: nur wenn er exakt zu den Wortzeiten passt, wird hervorgehoben.
  Future<bool> speak(
    String examId,
    String itemId,
    String? displayText,
  ) =>
      _play(
        label: 'Frage $itemId',
        uri: _client.itemUri(examId, itemId),
        timings: displayText != null
            ? () => _client.timings(examId, itemId)
            : null,
        displayText: displayText,
        lesson: false,
      );

  /// Streamt eine Lektion (Session-Lektionskarte).
  Future<bool> speakLesson(String examId, String lessonId) => _play(
        label: 'Lektion $lessonId',
        uri: _client.lessonUri(examId, lessonId),
        timings: null,
        displayText: null,
        lesson: true,
      );

  String _codeOf(Object e) {
    final msg = e.toString();
    if (msg.startsWith('player_error')) return msg;
    return 'error:${e.runtimeType}';
  }

  Future<bool> _play({
    required String label,
    required Uri uri,
    required Future<AudioTimings?> Function()? timings,
    required String? displayText,
    required bool lesson,
  }) async {
    final gen = machine.begin();
    _sync();
    await _releasePlayer();
    _highlight = const Highlight();
    notifyListeners();

    final t0 = DateTime.now().millisecondsSinceEpoch;
    final total = streamStartTimeoutMs(lesson);
    _Attempt outcome;

    try {
      var attempt = 0;
      while (true) {
        final left = total - (DateTime.now().millisecondsSinceEpoch - t0);
        outcome = left <= 0
            ? const _AttemptTimeout()
            : await _attemptOnce(
                gen: gen,
                uri: uri,
                timings: timings,
                displayText: displayText,
                label: label,
                timeoutMs: left,
                t0: t0,
              );
        if (outcome is _AttemptError &&
            !outcome.started &&
            attempt < 2 &&
            machine.isCurrent(gen)) {
          final wait = retryDelayMs(attempt);
          attempt++;
          if (total - (DateTime.now().millisecondsSinceEpoch - t0) >
              wait + 1000) {
            unawaited(
              _client.report(
                outcome.code,
                'retry $attempt after ${DateTime.now().millisecondsSinceEpoch - t0} ms',
                lesson: lesson,
              ),
            );
            await Future<void>.delayed(Duration(milliseconds: wait));
            if (!machine.isCurrent(gen)) break;
            continue;
          }
        }
        break;
      }

      final stillCurrent = machine.isCurrent(gen);
      await _releasePlayer();
      final ended = machine.finish(gen);
      _sync();

      if (stillCurrent) {
        final now = DateTime.now().millisecondsSinceEpoch;
        switch (outcome) {
          case _AttemptPlayed():
            _health.success(now);
          case _AttemptTimeout():
            _health.failure(now);
            _reportFailure(
              audioErrorCode(timeout: true),
              uri,
              lesson,
              now - t0,
            );
          case _AttemptError(:final code):
            _health.failure(now);
            _reportFailure(code, uri, lesson, now - t0);
        }
        _buttonVisible = _health.visible;
      }
      if (outcome is _AttemptPlayed &&
          ended &&
          _highlight.words.isNotEmpty) {
        _highlight = _highlight.copyWith(
          spoken: _highlight.words.length,
          active: -1,
        );
      }
      notifyListeners();
      return outcome is _AttemptPlayed && stillCurrent && ended;
    } catch (_) {
      if (machine.isCurrent(gen)) {
        machine.stop();
        await _releasePlayer();
      }
      _sync();
      return false;
    }
  }

  void _reportFailure(String code, Uri uri, bool lesson, int elapsedMs) {
    unawaited(() async {
      final diagnosed = await _client.diagnose(_network, uri);
      await _client.report(
        diagnosed ?? code,
        '$code after $elapsedMs ms',
        lesson: lesson,
      );
    }());
  }

  Future<_Attempt> _attemptOnce({
    required int gen,
    required Uri uri,
    required Future<AudioTimings?> Function()? timings,
    required String? displayText,
    required String label,
    required int timeoutMs,
    required int t0,
  }) async {
    final started = Completer<void>();
    final finished = Completer<Object?>();

    unawaited(() async {
      try {
        await _playStream(gen, uri, started);
        if (!finished.isCompleted) finished.complete(null);
      } catch (e) {
        if (!started.isCompleted) started.completeError(e);
        if (!finished.isCompleted) finished.complete(e);
      }
    }());

    Object? startError;
    var ok = false;
    try {
      await started.future.timeout(Duration(milliseconds: timeoutMs));
      ok = true;
    } on TimeoutException {
      ok = false;
    } catch (e) {
      startError = e;
      ok = false;
    }

    if (!ok) {
      _cancelHook?.call();
      AppLog.d(
        'Voice',
        '$label: Stream nicht gestartet (${startError != null ? _codeOf(startError) : 'timeout'})',
      );
      return startError != null
          ? _AttemptError(_codeOf(startError), false)
          : const _AttemptTimeout();
    }

    Timer? ticker;
    if (timings != null && displayText != null) {
      unawaited(() async {
        final t = await fetchMatchingTimings(
          timings,
          displayText,
          cancelled: () => !machine.isCurrent(gen),
          onFail: (m) => AppLog.d('Voice', '$label: $m'),
        );
        if (t == null || !machine.isCurrent(gen)) return;
        _highlight = Highlight(text: t.text, words: t.words, spoken: 0, active: -1);
        notifyListeners();
        ticker = Timer.periodic(const Duration(milliseconds: 40), (timer) {
          final p = _player;
          if (p == null || !machine.isCurrent(gen)) {
            timer.cancel();
            return;
          }
          if (p.playing) {
            final pos = wordPosAt(t.words, p.position.inMilliseconds);
            if (pos.spoken != _highlight.spoken ||
                pos.active != _highlight.active) {
              _highlight = _highlight.copyWith(
                spoken: pos.spoken,
                active: pos.active,
              );
              notifyListeners();
            }
          }
        });
      }());
    }

    try {
      final err = await finished.future;
      if (err == null) return const _AttemptPlayed();
      AppLog.w('Voice', '$label: Stream abgebrochen', err);
      return _AttemptError(_codeOf(err), true);
    } finally {
      ticker?.cancel();
    }
  }

  /// Stoppt alles sofort (neue Karte, Stumm, Verlassen).
  void stop() {
    machine.stop();
    _cancelHook?.call();
    _cancelHook = null;
    _sync();
    _highlight = const Highlight();
    unawaited(_releasePlayer());
    notifyListeners();
  }

  Future<void> _releasePlayer() async {
    final p = _player;
    _player = null;
    if (p != null) {
      try {
        await p.stop();
      } catch (_) {}
      try {
        await p.dispose();
      } catch (_) {}
    }
  }

  Future<void> _playStream(
    int gen,
    Uri uri,
    Completer<void> started,
  ) async {
    final p = AudioPlayer();
    _player = p;
    final done = Completer<void>();
    StreamSubscription<PlayerState>? sub;

    void cleanup() {
      sub?.cancel();
      if (identical(_player, p)) {
        _player = null;
      }
      unawaited(p.dispose());
    }

    _cancelHook = () {
      cleanup();
      if (!done.isCompleted) done.complete();
    };

    try {
      sub = p.playerStateStream.listen(
        (state) {
          if (state.processingState == ProcessingState.completed) {
            cleanup();
            if (!done.isCompleted) done.complete();
          }
        },
        onError: (Object e) {
          cleanup();
          if (!started.isCompleted) started.completeError(e);
          if (!done.isCompleted) done.completeError(e);
        },
      );

      await p.setAudioSource(
        AudioSource.uri(uri, headers: _client.headers(uri)),
      );
      if (!machine.playing(gen)) {
        cleanup();
        if (!started.isCompleted) started.complete();
        if (!done.isCompleted) done.complete();
        return;
      }
      _sync();
      await p.setSpeed(speechSpeed);
      unawaited(p.play());
      if (!started.isCompleted) started.complete();
      await done.future;
    } catch (e) {
      cleanup();
      if (!started.isCompleted) started.completeError(e);
      rethrow;
    } finally {
      if (_cancelHook != null) _cancelHook = null;
    }
  }
}
