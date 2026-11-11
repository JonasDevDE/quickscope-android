import 'dart:async';
import 'dart:io';
import 'package:flutter/foundation.dart';
import 'app_services.dart';
import 'data/models.dart';
import 'data/network.dart';
import 'logic/audio.dart';
import 'logic/audiobook.dart';
import 'logic/exams.dart';
import 'logic/feedback.dart';
import 'logic/forecast_text.dart';
import 'logic/guard.dart';
import 'logic/lesson_header.dart';
import 'logic/listen.dart';
import 'logic/offline.dart';
import 'logic/tasks.dart';
import 'logic/understand.dart';

class Fx {
  final int id;
  final Outcome outcome;
  const Fx(this.id, this.outcome);
}

class Sits {
  final int id;
  final String title;
  const Sits(this.id, this.title);
}

/// Zustand der Lernrunde. [feedback] ist gesetzt, solange die Antwort zur aktuellen Aufgabe gezeigt wird.
class SessionUi {
  final SessionPhase phase;
  final String examId;
  final List<Task> tasks;
  final int index;
  final String reason;
  final int? finalRoundStart;
  final int? scoreBefore;
  final int? scoreAfter;
  final Forecast? forecastAfter;
  final String? message;
  final bool? feedback;
  final Map<String, String> topics;
  final List<bool> isRetry;
  final Fx? fx;
  final int streak;
  final int bestStreak;
  final int xp;
  final int correctCount;
  final int answerCount;
  final int elapsedSeconds;
  final int dayStreak;
  final Sits? sits;
  final bool neutral;
  final int? autoplaySuppressedAt;
  final bool audio;
  final bool offline;
  final bool lessonsOnly;
  final List<Chapter> chapters;
  final Set<String> heardIds;

  const SessionUi({
    this.phase = SessionPhase.closed,
    this.examId = '',
    this.tasks = const [],
    this.index = 0,
    this.reason = '',
    this.finalRoundStart,
    this.scoreBefore,
    this.scoreAfter,
    this.forecastAfter,
    this.message,
    this.feedback,
    this.topics = const {},
    this.isRetry = const [],
    this.fx,
    this.streak = 0,
    this.bestStreak = 0,
    this.xp = 0,
    this.correctCount = 0,
    this.answerCount = 0,
    this.elapsedSeconds = 0,
    this.dayStreak = 0,
    this.sits,
    this.neutral = false,
    this.autoplaySuppressedAt,
    this.audio = false,
    this.offline = false,
    this.lessonsOnly = false,
    this.chapters = const [],
    this.heardIds = const {},
  });

  Task? get current =>
      (index >= 0 && index < tasks.length) ? tasks[index] : null;
  bool get currentIsRetry =>
      (index >= 0 && index < isRetry.length) ? isRetry[index] : false;
  bool get inFinalRound =>
      finalRoundStart != null && index >= finalRoundStart!;

  SessionUi copyWith({
    SessionPhase? phase,
    String? examId,
    List<Task>? tasks,
    int? index,
    String? reason,
    int? finalRoundStart,
    bool clearFinalRoundStart = false,
    int? scoreBefore,
    int? scoreAfter,
    Forecast? forecastAfter,
    String? message,
    bool? feedback,
    bool clearFeedback = false,
    Map<String, String>? topics,
    List<bool>? isRetry,
    Fx? fx,
    int? streak,
    int? bestStreak,
    int? xp,
    int? correctCount,
    int? answerCount,
    int? elapsedSeconds,
    int? dayStreak,
    Sits? sits,
    bool? neutral,
    int? autoplaySuppressedAt,
    bool clearAutoplaySuppressedAt = false,
    bool? audio,
    bool? offline,
    bool? lessonsOnly,
    List<Chapter>? chapters,
    Set<String>? heardIds,
  }) =>
      SessionUi(
        phase: phase ?? this.phase,
        examId: examId ?? this.examId,
        tasks: tasks ?? this.tasks,
        index: index ?? this.index,
        reason: reason ?? this.reason,
        finalRoundStart: clearFinalRoundStart
            ? null
            : (finalRoundStart ?? this.finalRoundStart),
        scoreBefore: scoreBefore ?? this.scoreBefore,
        scoreAfter: scoreAfter ?? this.scoreAfter,
        forecastAfter: forecastAfter ?? this.forecastAfter,
        message: message ?? this.message,
        feedback: clearFeedback ? null : (feedback ?? this.feedback),
        topics: topics ?? this.topics,
        isRetry: isRetry ?? this.isRetry,
        fx: fx ?? this.fx,
        streak: streak ?? this.streak,
        bestStreak: bestStreak ?? this.bestStreak,
        xp: xp ?? this.xp,
        correctCount: correctCount ?? this.correctCount,
        answerCount: answerCount ?? this.answerCount,
        elapsedSeconds: elapsedSeconds ?? this.elapsedSeconds,
        dayStreak: dayStreak ?? this.dayStreak,
        sits: sits ?? this.sits,
        neutral: neutral ?? this.neutral,
        autoplaySuppressedAt: clearAutoplaySuppressedAt
            ? null
            : (autoplaySuppressedAt ?? this.autoplaySuppressedAt),
        audio: audio ?? this.audio,
        offline: offline ?? this.offline,
        lessonsOnly: lessonsOnly ?? this.lessonsOnly,
        chapters: chapters ?? this.chapters,
        heardIds: heardIds ?? this.heardIds,
      );
}

/// Lernrunde: laedt die Session, merkt Antworten lokal vor (offline-fest) und sendet sie batchweise.
class SessionViewModel extends ChangeNotifier {
  final AppServices _app;

  SessionUi _state = const SessionUi();
  SessionUi get state => _state;

  int _shownAt = 0;
  int _startedAt = 0;
  FeedbackTracker _tracker = FeedbackTracker();
  int _fxId = 0;
  int _sitsId = 0;
  Map<String, double> _prevMastery = const {};
  Set<String> _leafIds = const {};
  Map<String, String> _topicTitles = const {};
  int _lastSitsCheck = 0;

  int _startGeneration = 0;
  int _recheckGeneration = 0;
  int _lastAudioCheck = 0;
  bool lastMoveForward = true;

  SessionViewModel(this._app);

  void _updateState(SessionUi next) {
    _state = next;
    notifyListeners();
  }

  int _nowMs() => DateTime.now().millisecondsSinceEpoch;

  /// [minutes] null = Rest des Tagesbudgets; sonst der ausdrueckliche Wunsch.
  void start(
    String examId, {
    int? minutes,
    int? scoreBefore,
    bool questionsOnly = false,
    bool lessonsOnly = false,
  }) {
    if (!canStartSession(_state.phase)) return;
    final gen = ++_startGeneration;
    ++_recheckGeneration;
    _app.voice.resetHealth();
    _updateState(
      SessionUi(
        phase: SessionPhase.loading,
        examId: examId,
        scoreBefore: scoreBefore,
      ),
    );

    unawaited(() async {
      try {
        if (!_app.connectivity.isOnline()) {
          _startOffline(examId, minutes, questionsOnly, lessonsOnly);
          return;
        }
        if (lessonsOnly) {
          try {
            await _startUnderstand(gen, examId);
          } on IOException {
            if (_app.local.pack(examId) == null) rethrow;
            _app.connectivity.markOffline();
            _startOffline(examId, minutes, questionsOnly, true);
          }
          return;
        }
        await _app.attemptSync.flush(examId);
        LearnSession s;
        try {
          s = await _app.api.session(
            examId,
            minutes: minutes,
            lessons: questionsOnly ? false : null,
          );
        } on IOException {
          if (_app.local.pack(examId) == null) rethrow;
          _app.connectivity.markOffline();
          _startOffline(examId, minutes, questionsOnly, lessonsOnly);
          return;
        }
        if (gen != _startGeneration) return;

        List<Topic> topicList;
        try {
          topicList = await _app.api.topics(examId);
        } catch (_) {
          topicList = const [];
        }
        final labels = topicLabels(topicList);
        final parents = {
          for (final t in topicList)
            if (t.parentId != null) t.parentId!,
        };
        _leafIds = {
          for (final t in topicList)
            if (!parents.contains(t.id)) t.id,
        };
        _topicTitles = {for (final t in topicList) t.id: t.title};
        _prevMastery = {
          for (final t in topicList)
            if (_leafIds.contains(t.id)) t.id: t.mastery,
        };
        _tracker = FeedbackTracker();

        final mode =
            questionsOnly ? StartMode.questionsOnly : StartMode.all;
        final rawTasks = [
          for (final e
              in filterForMode(s.items, (x) => x.lesson != null, mode))
            if (taskOf(e) case final t?) t,
        ];

        var chapters = <Chapter>[];
        List<Task> tasks;
        if (rawTasks.any((t) => t is TaskTeach)) {
          try {
            chapters = await _app.api.chapters(examId);
          } catch (_) {
            chapters = const [];
          }
          final needsMap = rawTasks.any(
            (t) => t is TaskTeach && t.lesson.chapterId == null,
          );
          var byLesson = <String, String>{};
          if (needsMap) {
            try {
              final ls = await _app.api.lessons(examId);
              byLesson = {for (final l in ls) l.id: l.chapterId};
            } catch (_) {}
          }
          tasks = _withChapters(rawTasks, byLesson, chapters);
        } else {
          tasks = rawTasks;
        }
        if (gen != _startGeneration) return;

        if (tasks.isEmpty) {
          _updateState(
            _state.copyWith(phase: SessionPhase.empty, reason: s.reason),
          );
        } else {
          _lastAudioCheck = _nowMs();
          _shownAt = _lastAudioCheck;
          _startedAt = _shownAt;
          int? finalStart;
          if (s.finalRoundStart != null) {
            final f = s.finalRoundStart!;
            final beforeSlice = s.items.take(f).toList();
            final filteredSlice = filterForMode(
              beforeSlice,
              (x) => x.lesson != null,
              mode,
            );
            finalStart = f - (beforeSlice.length - filteredSlice.length);
          }
          _updateState(
            _state.copyWith(
              phase: SessionPhase.running,
              tasks: tasks,
              isRetry: List<bool>.filled(tasks.length, false),
              reason: s.reason,
              audio: false,
              lessonsOnly: lessonsOnly,
              chapters: chapters,
              finalRoundStart: finalStart,
              topics: labels,
            ),
          );
          _checkAudioLater();
        }
      } catch (e) {
        if (gen != _startGeneration) return;
        _updateState(
          _state.copyWith(phase: SessionPhase.error, message: _errorText(e)),
        );
      }
    }());
  }

  Future<void> _startUnderstand(int gen, String examId) async {
    var chapters = <Chapter>[];
    try {
      chapters = await _app.api.chapters(examId);
    } catch (_) {}
    final lessons = await _app.api.lessons(examId);
    if (gen != _startGeneration) return;
    final heard = {
      for (final l in lessons)
        if (l.heard) l.id,
    };
    final list = buildUnderstandList(
      chapters,
      [
        for (final l in lessons)
          SessionLesson(
            id: l.id,
            title: l.title,
            keyPoints: l.keyPoints,
            ttsText: l.ttsText,
            mnemonic: l.mnemonic,
            estSeconds: l.estSeconds,
            chapterId: l.chapterId,
          ),
      ],
      heard,
    );
    _finishUnderstandStart(examId, list, chapters, heard, offline: false);
  }

  void _finishUnderstandStart(
    String examId,
    UnderstandList list,
    List<Chapter> chapters,
    Set<String> heard, {
    required bool offline,
  }) {
    final tasks = [
      for (final l in list.lessons) TaskTeach(teachItem(l), l),
    ];
    if (tasks.isEmpty) {
      _updateState(
        _state.copyWith(phase: SessionPhase.empty, offline: offline),
      );
      return;
    }
    _shownAt = _nowMs();
    _startedAt = _shownAt;
    _lastAudioCheck = _shownAt;
    _updateState(
      _state.copyWith(
        phase: SessionPhase.running,
        tasks: tasks,
        index: list.start,
        isRetry: List<bool>.filled(tasks.length, false),
        audio: false,
        offline: offline,
        lessonsOnly: true,
        chapters: chapters,
        heardIds: heard,
      ),
    );
    if (!offline) _checkAudioLater();
  }

  List<Task> _withChapters(
    List<Task> tasks,
    Map<String, String> byLesson,
    List<Chapter> chapters,
  ) =>
      [
        for (final t in tasks)
          t is TaskTeach
              ? TaskTeach(t.item, enrichLesson(t.lesson, byLesson, chapters))
              : t,
      ];

  void _startOffline(
    String examId,
    int? minutes,
    bool questionsOnly,
    bool lessonsOnly,
  ) {
    final pack = _app.local.pack(examId);
    if (pack == null) {
      _updateState(
        _state.copyWith(phase: SessionPhase.error, message: needsInternet),
      );
      return;
    }
    if (lessonsOnly) {
      final seen = <String>{};
      final lessons = <SessionLesson>[];
      for (final e in [...pack.entries, ...pack.reserve]) {
        if (e.lesson != null && seen.add(e.lesson!.id)) {
          lessons.add(e.lesson!);
        }
      }
      final doneIds = {
        for (final k in _app.local.doneKeys(examId))
          if (k.startsWith('l:')) k.substring(2),
      };
      _finishUnderstandStart(
        examId,
        buildUnderstandList(pack.chapters, lessons, doneIds),
        pack.chapters,
        doneIds,
        offline: true,
      );
      return;
    }

    Exam? exam;
    for (final e in _app.local.exams() ?? const <Exam>[]) {
      if (e.id == examId) {
        exam = e;
        break;
      }
    }
    final doneSeconds = _app.local.forecast(examId)?.doneSecondsToday ?? 0;
    final m = minutes ?? remainingMinutes(exam?.dailyMinutes ?? 20, doneSeconds);
    final entries = offlineRound(
      pack,
      _app.local.doneKeys(examId),
      m * 60,
      questionsOnly: questionsOnly,
    );
    final tasks = _withChapters(
      [for (final e in entries) if (taskOf(e) case final t?) t],
      const {},
      pack.chapters,
    );
    if (tasks.isEmpty) {
      _updateState(_state.copyWith(phase: SessionPhase.empty, offline: true));
      return;
    }
    final topicList = _app.local.topics(examId);
    final parents = {
      for (final t in topicList)
        if (t.parentId != null) t.parentId!,
    };
    _leafIds = {
      for (final t in topicList)
        if (!parents.contains(t.id)) t.id,
    };
    _topicTitles = {for (final t in topicList) t.id: t.title};
    _prevMastery = {
      for (final t in topicList)
        if (_leafIds.contains(t.id)) t.id: t.mastery,
    };
    _tracker = FeedbackTracker();
    _shownAt = _nowMs();
    _startedAt = _shownAt;
    _updateState(
      _state.copyWith(
        phase: SessionPhase.running,
        tasks: tasks,
        isRetry: List<bool>.filled(tasks.length, false),
        audio: false,
        offline: true,
        lessonsOnly: lessonsOnly,
        chapters: pack.chapters,
        topics: topicLabels(topicList),
      ),
    );
  }

  /// Antwort zur aktuellen Aufgabe: sofort vormerken und Feedback zeigen; gesendet wird im Hintergrund.
  void answer(bool correct, {Object? payload}) {
    final s = _state;
    final task = s.current;
    if (task == null || s.feedback != null || task is TaskTeach) return;
    final ms = (_nowMs() - _shownAt).clamp(0, 10 * 60 * 1000);
    if (task is TaskLesson) {
      _app.attemptSync.record(
        s.examId,
        AttemptRequest(
          itemId: task.item.id,
          outcome: outcomeFor(task, true),
          ms: ms,
          at: DateTime.now().toUtc().toIso8601String(),
        ),
      );
      if (s.offline) _app.local.markDone(s.examId, 'q:${task.item.id}');
      _updateState(s.copyWith(feedback: true));
      unawaited(_app.attemptSync.flush(s.examId));
      return;
    }

    final retry = s.currentIsRetry;
    final outcome = _tracker.record(correct, task.item.difficulty, retry: retry);
    var tasks = s.tasks;
    var flags = s.isRetry;
    var finalStart = s.finalRoundStart;

    if (!retry) {
      _app.attemptSync.record(
        s.examId,
        AttemptRequest(
          itemId: task.item.id,
          outcome: outcomeFor(task, correct),
          ms: ms,
          at: DateTime.now().toUtc().toIso8601String(),
          answer: payload,
        ),
      );
      unawaited(() async {
        final f = await _app.attemptSync.flush(s.examId);
        if (f != null) await _checkSits(s.examId);
      }());
      if (s.offline && correct) {
        _app.local.markDone(s.examId, 'q:${task.item.id}');
      }
      if (!correct) {
        final rawAt = s.offline
            ? offlineRetryIndex(s.index, tasks.length, task.item.id)
            : retryInsertIndex(s.index, tasks.length, task.item.id);
        final at = afterUpcomingLessons(tasks, s.index, rawAt);
        tasks = [...tasks]..insert(at, task);
        flags = [...flags]..insert(at, true);
        if (finalStart != null && at <= finalStart) finalStart += 1;
      }
    }

    _fxId++;
    _updateState(
      s.copyWith(
        feedback: correct,
        tasks: tasks,
        isRetry: flags,
        finalRoundStart: finalStart,
        fx: Fx(_fxId, outcome),
        streak: _tracker.streak,
        bestStreak: _tracker.best,
        xp: _tracker.xp,
        correctCount: _tracker.correct,
        answerCount: _tracker.answers,
      ),
    );
  }

  /// "Weiss nicht": neutral. Sendet outcome "dontknow", markiert nur die richtige Antwort.
  void dontKnow() {
    final s = _state;
    final task = s.current;
    if (task == null ||
        s.feedback != null ||
        task is TaskCard ||
        task is TaskLesson ||
        task is TaskTeach) {
      return;
    }
    final ms = (_nowMs() - _shownAt).clamp(0, 10 * 60 * 1000);
    _updateState(s.copyWith(feedback: false, neutral: true));
    if (s.currentIsRetry) return;
    _app.attemptSync.record(
      s.examId,
      AttemptRequest(
        itemId: task.item.id,
        outcome: dontKnowOutcome(),
        ms: ms,
        at: DateTime.now().toUtc().toIso8601String(),
      ),
    );
    if (s.offline) {
      final pack = _app.local.pack(s.examId);
      if (pack == null) return;
      final present = {
        for (final t in s.tasks)
          if (t is TaskTeach) t.lesson.id,
      };
      final nextLesson = nextOfflineLesson(
        pack,
        present,
        _app.local.doneKeys(s.examId),
      );
      if (nextLesson != null) _insertLessonCard(nextLesson);
      return;
    }
    unawaited(() async {
      final r = await _app.attemptSync.flush(s.examId);
      if (r?.nextLessonId != null) {
        await _insertLesson(s.examId, r!.nextLessonId!);
      }
    }());
  }

  Future<void> _insertLesson(String examId, String lessonId) async {
    try {
      final list = await _app.api.lessons(examId);
      for (final l in list) {
        if (l.id == lessonId) {
          _insertLessonCard(
            SessionLesson(
              id: l.id,
              title: l.title,
              keyPoints: l.keyPoints,
              ttsText: l.ttsText,
              mnemonic: l.mnemonic,
              estSeconds: l.estSeconds,
            ),
          );
          return;
        }
      }
    } catch (_) {}
  }

  void _insertLessonCard(SessionLesson card) {
    final st = _state;
    final at = lessonInsertIndex(st.index, st.tasks, card.id);
    if (at == null) return;
    final teach = TaskTeach(teachItem(card), card);
    _updateState(
      st.copyWith(
        tasks: [...st.tasks]..insert(at, teach),
        isRetry: [...st.isRetry]..insert(at, false),
        finalRoundStart: st.finalRoundStart != null
            ? (at <= st.finalRoundStart!
                ? st.finalRoundStart! + 1
                : st.finalRoundStart)
            : null,
      ),
    );
  }

  void prefetch() {
    final st = _state;
    if (!st.audio || st.phase != SessionPhase.running) return;
    final targets = prefetchTargets(st.tasks, st.index);
    if (targets.isEmpty) return;
    unawaited(() async {
      for (final t in targets) {
        await _app.audio.prefetch(_app.network, st.examId, t);
      }
    }());
  }

  void heard(String lessonId, double fraction) {
    final examId = _state.examId;
    if (examId.isEmpty) return;
    final f = fraction.clamp(0.0, 1.0);
    if (f >= 0.8) {
      _updateState(
        _state.copyWith(heardIds: {..._state.heardIds, lessonId}),
      );
      if (_state.offline) _app.local.markDone(examId, 'l:$lessonId');
    }
    if (_state.offline) return;
    unawaited(() async {
      try {
        await _app.api.heard(examId, lessonId, HeardRequest(fraction: f));
      } catch (_) {}
    }());
  }

  void previous() {
    final s = _state;
    if (s.phase != SessionPhase.running || !s.lessonsOnly) return;
    final to = stepIndex(s.index, s.tasks.length, -1);
    if (to == null) return;
    lastMoveForward = false;
    _shownAt = _nowMs();
    _updateState(
      s.copyWith(
        index: to,
        clearFeedback: true,
        neutral: false,
        clearAutoplaySuppressedAt: true,
      ),
    );
  }

  void jumpTo(int index) {
    final s = _state;
    if (s.phase != SessionPhase.running ||
        !s.lessonsOnly ||
        index < 0 ||
        index >= s.tasks.length ||
        index == s.index) {
      return;
    }
    lastMoveForward = index > s.index;
    _shownAt = _nowMs();
    _updateState(
      s.copyWith(
        index: index,
        clearFeedback: true,
        neutral: false,
        clearAutoplaySuppressedAt: true,
      ),
    );
  }

  Future<void> _checkSits(String examId) async {
    final now = _nowMs();
    if (now - _lastSitsCheck < 2500) return;
    _lastSitsCheck = now;
    try {
      final list = await _app.api.topics(examId);
      final current = {
        for (final t in list)
          if (_leafIds.contains(t.id)) t.id: t.mastery,
      };
      final ids = newlySitting(_prevMastery, current);
      _prevMastery = current;
      final id = ids.firstOrNull;
      if (id == null) return;
      _sitsId++;
      _updateState(
        _state.copyWith(sits: Sits(_sitsId, _topicTitles[id] ?? '')),
      );
    } catch (_) {}
  }

  void next() {
    final s = _state;
    if (s.phase != SessionPhase.running) return;
    final nextIndex = s.index + 1;
    lastMoveForward = true;
    if (nextIndex >= s.tasks.length) {
      final today = DateTime.now();
      final streakDays = nextDayStreak(
        _app.store.lastSessionDay != null
            ? DateTime.tryParse(_app.store.lastSessionDay!)
            : null,
        _app.store.dayStreak,
        today,
      );
      _app.store.lastSessionDay = today.toIso8601String().split('T').first;
      _app.store.dayStreak = streakDays;
      final seconds = (_nowMs() - _startedAt) ~/ 1000;
      _updateState(
        s.copyWith(
          index: nextIndex,
          clearFeedback: true,
          neutral: false,
          phase: SessionPhase.finished,
          scoreAfter: s.scoreBefore,
          elapsedSeconds: seconds,
          dayStreak: streakDays,
        ),
      );
      unawaited(() async {
        final f = await _app.attemptSync.flush(s.examId);
        if (f != null) {
          _updateState(
            _state.copyWith(
              forecastAfter: f.forecast,
              scoreAfter: f.forecast.scoreOrNull(),
            ),
          );
        }
        try {
          await _app.offlineSync.syncExam(s.examId);
        } catch (_) {}
      }());
    } else {
      _shownAt = _nowMs();
      _updateState(
        s.copyWith(
          index: nextIndex,
          clearFeedback: true,
          neutral: false,
          clearAutoplaySuppressedAt: true,
        ),
      );
      _maybeRecheckAudio();
    }
  }

  void _checkAudioLater() {
    final gen = ++_recheckGeneration;
    unawaited(() async {
      bool ok;
      try {
        ok = await _app.audio
            .available(force: true)
            .timeout(const Duration(milliseconds: audioStatusTimeoutMs));
      } catch (_) {
        ok = true;
      }
      if (gen != _recheckGeneration) return;
      _lastAudioCheck = _nowMs();
      if (_state.phase == SessionPhase.running) {
        _updateState(
          _state.copyWith(
            audio: audioAfterCheck(
              _app.store.audioEnabled,
              _app.connectivity.isOnline(),
              ok,
            ),
          ),
        );
      }
    }());
  }

  void _maybeRecheckAudio() {
    final now = _nowMs();
    final mode = _state.audio ? AudioMode.on : AudioMode.silent;
    if (!shouldRecheckAudio(
      mode,
      _app.store.audioEnabled,
      _lastAudioCheck,
      now,
    )) {
      return;
    }
    _lastAudioCheck = now;
    final gen = ++_recheckGeneration;
    unawaited(() async {
      final ok = await _app.audio.available(force: true);
      if (gen != _recheckGeneration) return;
      _updateState(_state.copyWith(audio: ok));
    }());
  }

  void suppressAutoplay() {
    _updateState(_state.copyWith(autoplaySuppressedAt: _state.index));
  }

  void allowAutoplay() {
    _updateState(_state.copyWith(clearAutoplaySuppressedAt: true));
  }

  void close() {
    final id = _state.examId;
    ++_startGeneration;
    ++_recheckGeneration;
    _updateState(const SessionUi());
    if (id.isNotEmpty) {
      unawaited(() async {
        await _app.attemptSync.flush(id);
        try {
          await _app.offlineSync.syncExam(id);
        } catch (_) {}
      }());
    }
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
