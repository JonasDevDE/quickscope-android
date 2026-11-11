import 'dart:async';
import 'dart:io';
import 'package:flutter/foundation.dart';
import 'package:just_audio/just_audio.dart';
import '../app_services.dart';
import '../data/models.dart';
import '../data/network.dart';
import '../logic/audio.dart';
import '../logic/audiobook.dart';
import '../logic/guard.dart';

enum BookPhase {
  closed('Closed'),
  loading('Loading'),
  playing('Playing'),
  empty('Empty'),
  finished('Finished'),
  error('Error');

  final String wireName;
  const BookPhase(this.wireName);
}

/// Anzeige des Hoerbuchs: aktuelle Lektion mit Stichworten, Fortschritt und Wiedergabestand.
class BookUi {
  final BookPhase phase;
  final int index;
  final int count;
  final String title;
  final String chapterTitle;
  final String chapterLabel;
  final List<String> keyPoints;
  final String? mnemonic;
  final bool playing;
  final int positionMs;
  final int durationMs;
  final String? message;

  const BookUi({
    this.phase = BookPhase.closed,
    this.index = 0,
    this.count = 0,
    this.title = '',
    this.chapterTitle = '',
    this.chapterLabel = '',
    this.keyPoints = const [],
    this.mnemonic,
    this.playing = false,
    this.positionMs = 0,
    this.durationMs = 0,
    this.message,
  });

  BookUi copyWith({
    BookPhase? phase,
    int? index,
    int? count,
    String? title,
    String? chapterTitle,
    String? chapterLabel,
    List<String>? keyPoints,
    String? mnemonic,
    bool clearMnemonic = false,
    bool? playing,
    int? positionMs,
    int? durationMs,
    String? message,
  }) =>
      BookUi(
        phase: phase ?? this.phase,
        index: index ?? this.index,
        count: count ?? this.count,
        title: title ?? this.title,
        chapterTitle: chapterTitle ?? this.chapterTitle,
        chapterLabel: chapterLabel ?? this.chapterLabel,
        keyPoints: keyPoints ?? this.keyPoints,
        mnemonic: clearMnemonic ? null : (mnemonic ?? this.mnemonic),
        playing: playing ?? this.playing,
        positionMs: positionMs ?? this.positionMs,
        durationMs: durationMs ?? this.durationMs,
        message: message ?? this.message,
      );
}

class _TrackMeta {
  final AudiobookItem item;
  final Lesson? lesson;
  final int chapterOrder;
  final int chapterTotal;

  const _TrackMeta({
    required this.item,
    required this.lesson,
    required this.chapterOrder,
    required this.chapterTotal,
  });
}

/// Steuert das Hoerbuch: Warteschlange laden, über just_audio mit Speed 1.15x abspielen,
/// gehörten Anteil pro Lektion ans Backend melden und Zustand für die UI bereitstellen.
class AudiobookViewModel extends ChangeNotifier {
  final AppServices _app;

  BookUi _state = const BookUi();
  BookUi get state => _state;

  AudioPlayer? _player;
  Timer? _ticker;
  Timer? _watchdog;
  StreamSubscription<PlayerState>? _playerStateSub;
  StreamSubscription<int?>? _indexSub;

  String _examId = '';
  AudiobookQueue _queue = AudiobookQueue(const []);
  List<_TrackMeta> _tracks = const [];
  final Map<String, double> _reported = {};
  final Map<String, int> _durations = {};
  final Map<String, int> _failures = {};
  int _playerErrors = 0;
  bool _everPlayed = false;
  int _lastIndex = 0;
  int _lastPosMs = 0;

  AudiobookViewModel(this._app);

  void _updateState(BookUi next) {
    _state = next;
    notifyListeners();
  }

  void start(String examId, int? minutes, {bool repeat = false}) {
    if (!canStartBook(_state.phase.wireName)) return;
    _examId = examId;
    _updateState(const BookUi(phase: BookPhase.loading));
    _playerErrors = 0;
    _everPlayed = false;
    _reported.clear();
    _durations.clear();
    _failures.clear();

    unawaited(() async {
      try {
        final book = await _app.api.audiobook(
          examId,
          minutes: minutes,
          repeat: repeat ? true : null,
        );
        if (book.items.isEmpty) {
          _updateState(
            _state.copyWith(
              phase: BookPhase.empty,
              message: book.reason.trim().isEmpty
                  ? 'Nichts Neues zu hören.'
                  : book.reason,
            ),
          );
          return;
        }
        var chapters = <Chapter>[];
        try {
          chapters = await _app.api.chapters(examId);
        } catch (_) {}
        var lessonsById = <String, Lesson>{};
        try {
          final ls = await _app.api.lessons(examId);
          lessonsById = {for (final l in ls) l.id: l};
        } catch (_) {}
        final order = {for (final c in chapters) c.chapterId: c.order};
        _queue = AudiobookQueue(book.items);

        if (!await _app.audio.available(force: true)) {
          _updateState(const BookUi(phase: BookPhase.error));
          return;
        }

        _tracks = [
          for (final it in book.items)
            _TrackMeta(
              item: it,
              lesson: lessonsById[it.lessonId],
              chapterOrder: order[it.chapterId] ?? 0,
              chapterTotal: chapters.length,
            ),
        ];

        await _startPlayer();
        _updateState(
          _state.copyWith(phase: BookPhase.playing, count: book.items.length),
        );
        _refresh();

        _watchdog?.cancel();
        _watchdog = Timer(const Duration(seconds: 8), () {
          if (_player?.playing != true &&
              _state.phase == BookPhase.playing &&
              !_everPlayed) {
            _failQuietly();
          }
        });
      } catch (e) {
        _updateState(
          _state.copyWith(phase: BookPhase.error, message: _errorText(e)),
        );
      }
    }());
  }

  Future<void> _startPlayer() async {
    await _disposePlayer();
    final p = AudioPlayer();
    _player = p;
    _lastIndex = 0;
    _lastPosMs = 0;

    final sources = [
      for (final t in _tracks)
        AudioSource.uri(
          _app.audio.lessonUri(_examId, t.item.lessonId),
          headers: _app.audio.headers(
            _app.audio.lessonUri(_examId, t.item.lessonId),
          ),
        ),
    ];

    _playerStateSub = p.playerStateStream.listen(
      (st) {
        if (st.playing) _everPlayed = true;
        if (st.processingState == ProcessingState.ready) {
          final idx = p.currentIndex ?? 0;
          if (idx >= 0 && idx < _tracks.length) {
            final id = _tracks[idx].item.lessonId;
            final dur = p.duration?.inMilliseconds ?? 0;
            if (dur > 0) _durations[id] = dur;
            _failures.remove(id);
          }
        } else if (st.processingState == ProcessingState.completed) {
          if (_tracks.isNotEmpty &&
              _lastIndex >= 0 &&
              _lastIndex < _tracks.length) {
            _report(_tracks[_lastIndex].item.lessonId, 1.0);
          }
          if (_playerErrors > 0 && !_everPlayed) {
            _failQuietly();
          } else {
            _updateState(
              _state.copyWith(phase: BookPhase.finished, playing: false),
            );
          }
          return;
        }
        _refresh();
      },
      onError: (Object _) {
        _playerErrors++;
        _onTrackError();
      },
    );

    _indexSub = p.currentIndexStream.listen((newIdx) {
      if (newIdx == null) return;
      if (newIdx != _lastIndex &&
          _lastIndex >= 0 &&
          _lastIndex < _tracks.length) {
        final oldId = _tracks[_lastIndex].item.lessonId;
        final dur =
            _durations[oldId] ?? (_tracks[_lastIndex].item.estSeconds * 1000);
        final finished = newIdx == _lastIndex + 1 && _lastPosMs >= dur * 0.85;
        _report(oldId, heardFraction(_lastPosMs, dur, finished));
      }
      _lastIndex = newIdx;
      _lastPosMs = 0;
      _refresh();
    });

    await p.setAudioSource(ConcatenatingAudioSource(children: sources));
    await p.setSpeed(speechSpeed);
    unawaited(p.play());
    _startTicker();
  }

  void _onTrackError() {
    final p = _player;
    if (p == null || _tracks.isEmpty) return;
    final idx = p.currentIndex ?? 0;
    if (idx < 0 || idx >= _tracks.length) return;
    final id = _tracks[idx].item.lessonId;
    final n = (_failures[id] ?? 0) + 1;
    _failures[id] = n;
    switch (nextAfterFailure(n)) {
      case FailureRetry(:final delayMs):
        unawaited(() async {
          await Future<void>.delayed(Duration(milliseconds: delayMs));
          if (_player?.currentIndex == idx) {
            try {
              await _player?.load();
              unawaited(_player?.play());
            } catch (_) {}
          }
        }());
      case FailureSkip():
        if (idx + 1 < _tracks.length) {
          unawaited(p.seek(Duration.zero, index: idx + 1));
          unawaited(p.play());
        } else {
          _failQuietly();
        }
    }
  }

  void _refresh() {
    final p = _player;
    if (p == null || _tracks.isEmpty) return;
    final idx = (p.currentIndex ?? 0).clamp(0, _tracks.length - 1);
    if (idx != _queue.index) _queue.jumpTo(idx);
    final meta = _tracks[idx];
    final dur = (p.duration?.inMilliseconds ?? 0) > 0
        ? p.duration!.inMilliseconds
        : meta.item.estSeconds * 1000;
    if (_state.phase == BookPhase.playing ||
        _state.phase == BookPhase.loading) {
      _updateState(
        _state.copyWith(
          phase: BookPhase.playing,
          index: idx,
          count: _tracks.length,
          title: meta.item.title,
          chapterTitle: meta.item.chapterTitle,
          chapterLabel: chapterLabel(meta.chapterOrder, meta.chapterTotal),
          keyPoints: meta.lesson?.keyPoints ?? const [],
          mnemonic: meta.lesson?.mnemonic,
          clearMnemonic: meta.lesson?.mnemonic == null,
          playing: p.playing,
          durationMs: dur,
        ),
      );
    }
  }

  void _startTicker() {
    _ticker?.cancel();
    _ticker = Timer.periodic(const Duration(milliseconds: 250), (_) {
      final p = _player;
      if (p != null && _state.phase == BookPhase.playing) {
        _lastPosMs = p.position.inMilliseconds;
        _updateState(
          _state.copyWith(positionMs: _lastPosMs, playing: p.playing),
        );
      }
    });
  }

  void _report(String lessonId, double fraction) {
    if (fraction <= (_reported[lessonId] ?? -1.0)) return;
    _reported[lessonId] = fraction;
    if (_examId.isEmpty) return;
    unawaited(() async {
      try {
        await _app.api.heard(
          _examId,
          lessonId,
          HeardRequest(fraction: fraction),
        );
      } catch (_) {}
    }());
  }

  void _reportCurrentPartial() {
    final p = _player;
    if (p == null || _tracks.isEmpty) return;
    final idx = (p.currentIndex ?? 0).clamp(0, _tracks.length - 1);
    final id = _tracks[idx].item.lessonId;
    final dur = _durations[id] ?? (_tracks[idx].item.estSeconds * 1000);
    final f = heardFraction(p.position.inMilliseconds, dur, false);
    if (f > 0.0) _report(id, f);
  }

  void playPause() {
    final p = _player;
    if (p == null) return;
    if (p.playing) {
      unawaited(p.pause());
    } else {
      unawaited(p.play());
    }
  }

  void next() {
    final p = _player;
    if (p == null) return;
    if (p.hasNext) {
      unawaited(p.seekToNext());
    } else {
      _reportCurrentPartial();
      unawaited(p.stop());
      _updateState(
        _state.copyWith(phase: BookPhase.finished, playing: false),
      );
    }
  }

  void previous() {
    final p = _player;
    if (p == null) return;
    if (p.hasPrevious) {
      unawaited(p.seekToPrevious());
    } else {
      unawaited(p.seek(Duration.zero));
    }
  }

  void _failQuietly() {
    _watchdog?.cancel();
    _ticker?.cancel();
    unawaited(_disposePlayer());
    _updateState(const BookUi(phase: BookPhase.error));
  }

  void close() {
    _watchdog?.cancel();
    _ticker?.cancel();
    _reportCurrentPartial();
    unawaited(_disposePlayer());
    _updateState(const BookUi());
  }

  Future<void> _disposePlayer() async {
    await _playerStateSub?.cancel();
    await _indexSub?.cancel();
    _playerStateSub = null;
    _indexSub = null;
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

  @override
  void dispose() {
    _watchdog?.cancel();
    _ticker?.cancel();
    unawaited(_disposePlayer());
    super.dispose();
  }

  String _errorText(Object e) {
    if (e is ApiHttpException) {
      return backendMessage(e) ?? 'Serverfehler (${e.code}).';
    }
    if (e is IOException || e is TimeoutException) {
      return 'Keine Verbindung zum Server.';
    }
    return 'Unbekannter Fehler.';
  }
}
