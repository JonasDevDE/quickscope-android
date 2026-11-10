import 'dart:async';
import 'dart:math' as math;
import 'package:flutter/material.dart' hide CloseButton;
import '../app_services.dart';
import '../data/models.dart';
import '../logic/audio.dart';
import '../logic/feedback.dart';
import '../logic/forecast_text.dart';
import '../logic/guard.dart';
import '../logic/lesson_header.dart';
import '../logic/tasks.dart';
import '../logic/understand.dart';
import '../session_view_model.dart';
import 'brand_mark.dart';
import 'components.dart';
import 'fx.dart';
import 'haptics.dart';
import 'sheets.dart';
import 'task_views.dart';
import 'teach_card.dart';
import 'theme.dart';
import 'voice.dart';

/// Lernrunde im Vollbild: eine Aufgabe pro Bildschirm, Zeitbalken oben, Abbrechen jederzeit.
class SessionScreen extends StatelessWidget {
  final SessionViewModel vm;
  final VoidCallback onRetry;
  final VoidCallback onMore;
  final VoidCallback onClose;

  const SessionScreen({
    super.key,
    required this.vm,
    required this.onRetry,
    required this.onMore,
    required this.onClose,
  });

  @override
  Widget build(BuildContext context) => ListenableBuilder(
        listenable: vm,
        builder: (context, _) {
          final s = vm.state;
          return PopScope(
            canPop: false,
            onPopInvokedWithResult: (didPop, _) {
              if (!didPop) onClose();
            },
            child: ColoredBox(
              color: AppColors.black,
              child: Stack(
                fit: StackFit.expand,
                children: [
                  SafeArea(
                    child: Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 20),
                      child: switch (s.phase) {
                        SessionPhase.running => _RunningView(
                            s: s,
                            vm: vm,
                            onClose: onClose,
                          ),
                        SessionPhase.finished => _DoneView(
                            s: s,
                            onClose: onClose,
                          ),
                        SessionPhase.loading || SessionPhase.closed => Column(
                            children: [
                              _TopRow(
                                progress: 0,
                                streak: 0,
                                onClose: onClose,
                              ),
                              Padding(
                                padding: const EdgeInsets.only(top: 16),
                                child: Text(
                                  "Gleich geht's los",
                                  textAlign: TextAlign.center,
                                  style: Theme.of(context)
                                      .textTheme
                                      .titleMedium
                                      ?.copyWith(color: AppColors.muted),
                                ),
                              ),
                              const SizedBox(height: 24),
                              const SkeletonCard(80),
                              const SizedBox(height: 12),
                              const SkeletonCard(56),
                              const SizedBox(height: 12),
                              const SkeletonCard(56),
                              const SizedBox(height: 12),
                              const SkeletonCard(56),
                            ],
                          ),
                        SessionPhase.empty => s.reason.contains('Alles gelernt')
                            ? _MessageView(
                                title: 'Alles gelernt',
                                text: s.reason,
                                primary: 'Fertig',
                                onPrimary: onClose,
                              )
                            : _MessageView(
                                title: s.reason.contains('Tagesziel')
                                    ? 'Tagesziel geschafft'
                                    : 'Gerade nichts zu lernen',
                                text: s.reason.trim().isEmpty
                                    ? 'Komm später wieder.'
                                    : s.reason,
                                primary: 'Noch eine Runde',
                                onPrimary: onMore,
                                secondary: 'Fertig',
                                onSecondary: onClose,
                              ),
                        SessionPhase.error => _MessageView(
                            title: 'Das hat nicht geklappt',
                            text: s.message ?? 'Unbekannter Fehler',
                            primary: 'Nochmal',
                            onPrimary: onRetry,
                            secondary: 'Schließen',
                            onSecondary: onClose,
                          ),
                      },
                    ),
                  ),
                  if (s.phase == SessionPhase.running &&
                      s.feedback != null &&
                      s.fx != null)
                    FullTint(s.fx!),
                ],
              ),
            ),
          );
        },
      );
}

class _TopRow extends StatelessWidget {
  final double progress;
  final int streak;
  final Widget? play;
  final VoidCallback onClose;

  const _TopRow({
    required this.progress,
    required this.streak,
    this.play,
    required this.onClose,
  });

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 12),
        child: Row(
          children: [
            CloseButton(onPressed: onClose),
            const SizedBox(width: 12),
            Expanded(child: GlowProgress(progress)),
            const SizedBox(width: 12),
            StreakChip(streak),
            if (play != null) ...[
              const SizedBox(width: 12),
              play!,
            ],
          ],
        ),
      );
}

class _MessageView extends StatelessWidget {
  final String title;
  final String text;
  final String primary;
  final VoidCallback onPrimary;
  final String? secondary;
  final VoidCallback? onSecondary;

  const _MessageView({
    required this.title,
    required this.text,
    required this.primary,
    required this.onPrimary,
    this.secondary,
    this.onSecondary,
  });

  @override
  Widget build(BuildContext context) => Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          const LearnMark(size: 56),
          const SizedBox(height: 16),
          Text(
            title,
            textAlign: TextAlign.center,
            style: Theme.of(context).textTheme.headlineSmall,
          ),
          const SizedBox(height: 8),
          Text(
            text,
            textAlign: TextAlign.center,
            style: const TextStyle(color: AppColors.muted),
          ),
          const SizedBox(height: 28),
          PrimaryButton(primary, onPressed: onPrimary),
          if (secondary != null && onSecondary != null) ...[
            const SizedBox(height: 10),
            SecondaryButton(secondary!, onPressed: onSecondary!),
          ],
        ],
      );
}

class _RunningView extends StatefulWidget {
  final SessionUi s;
  final SessionViewModel vm;
  final VoidCallback onClose;

  const _RunningView({
    required this.s,
    required this.vm,
    required this.onClose,
  });

  @override
  State<_RunningView> createState() => _RunningViewState();
}

class _RunningViewState extends State<_RunningView>
    with SingleTickerProviderStateMixin {
  late final AnimationController _shakeCtrl = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 340),
  );
  int _retry = 0;
  bool _showChapters = false;
  int? _lastFxId;
  int? _lastSitsId;
  Timer? _autoNextTimer;

  @override
  void initState() {
    super.initState();
    _syncEffects(null);
  }

  @override
  void didUpdateWidget(covariant _RunningView oldWidget) {
    super.didUpdateWidget(oldWidget);
    _syncEffects(oldWidget.s);
  }

  void _syncEffects(SessionUi? prev) {
    final s = widget.s;
    final task = s.current;
    final voice = AppServices.instance.voice;

    // Audio-Modus: Frage vorlesen
    final speechChanged = prev == null ||
        prev.index != s.index ||
        (prev.feedback != null) != (s.feedback != null) ||
        prev.audio != s.audio;
    if (speechChanged && task != null && task is! TaskTeach) {
      if (shouldSpeakQuestion(s.audio, s.feedback, false) &&
          mayAutoplay(s.autoplaySuppressedAt, s.index)) {
        unawaited(voice.speak(s.examId, task.item.id, task.item.question));
      } else {
        voice.stop();
      }
    }

    // Prefetch
    if ((prev == null || prev.index != s.index || prev.audio != s.audio) &&
        s.audio) {
      widget.vm.prefetch();
    }

    // FX & SFX
    if (s.fx != null && s.fx!.id != _lastFxId) {
      _lastFxId = s.fx!.id;
      final o = s.fx!.outcome;
      if (o.correct) {
        if (soundFor(o) case final tone?) {
          AppServices.instance.sfx.play(tone);
        }
        switch (o.celebration) {
          case Celebration.small:
            unawaited(Haptics.instance.celebrate(0));
          case Celebration.medium:
            unawaited(Haptics.instance.celebrate(1));
          case Celebration.big:
            unawaited(Haptics.instance.celebrate(2));
        }
      } else {
        Haptics.instance.tick();
        _shakeCtrl.forward(from: 0);
      }
    }

    // Sits Banner
    if (s.sits != null && s.sits!.id != _lastSitsId) {
      _lastSitsId = s.sits!.id;
      AppServices.instance.sfx.play(Tones.medium);
      unawaited(Haptics.instance.celebrate(1));
    }

    // Auto-advance after feedback
    if (prev == null ||
        prev.feedback != s.feedback ||
        prev.index != s.index) {
      _autoNextTimer?.cancel();
      final fb = s.feedback;
      if (fb != null && task != null) {
        final delayMs = task is TaskCard
            ? (fb ? 1000 : 1500)
            : s.neutral
                ? 2200
                : lessonSteps(task.item).isNotEmpty
                    ? 4500
                    : fb
                        ? 1400
                        : 2200;
        _autoNextTimer = Timer(Duration(milliseconds: delayMs), widget.vm.next);
      }
    }
  }

  @override
  void dispose() {
    _autoNextTimer?.cancel();
    _shakeCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final s = widget.s;
    final task = s.current;
    if (task == null) return const SizedBox.shrink();
    final voice = AppServices.instance.voice;

    final shownItems = [
      for (var i = 0; i < s.tasks.length; i++)
        if (!(i < s.isRetry.length && s.isRetry[i])) s.tasks[i].item,
    ];
    var done = 0;
    for (var i = 0; i < s.index && i < s.tasks.length; i++) {
      if (!(i < s.isRetry.length && s.isRetry[i])) done++;
    }

    return ListenableBuilder(
      listenable: voice,
      builder: (context, _) {
        final showPlayBtn = s.audio && voice.buttonVisible && !s.lessonsOnly;
        return Stack(
          children: [
            Column(
              children: [
                _TopRow(
                  progress: sessionProgress(shownItems, done),
                  streak: s.streak,
                  onClose: widget.onClose,
                  play: showPlayBtn
                      ? PlayPauseButton(
                          state: voice.playState,
                          onPressed: () {
                            switch (playTap(voice.playState)) {
                              case PlayTap.pause:
                                voice.pause();
                              case PlayTap.resume:
                                voice.resume();
                              case PlayTap.restart:
                                widget.vm.allowAutoplay();
                                setState(() => _retry++);
                                if (task is! TaskTeach) {
                                  unawaited(
                                    voice.speak(
                                      s.examId,
                                      task.item.id,
                                      task.item.question,
                                    ),
                                  );
                                }
                            }
                          },
                        )
                      : null,
                ),
                if (s.inFinalRound)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 4),
                    child: Align(
                      alignment: Alignment.centerLeft,
                      child: Text(
                        'Abschlussrunde',
                        style: Theme.of(context)
                            .textTheme
                            .labelMedium
                            ?.copyWith(color: AppColors.accent),
                      ),
                    ),
                  ),
                Expanded(
                  child: Stack(
                    children: [
                      AnimatedBuilder(
                        animation: _shakeCtrl,
                        builder: (_, child) {
                          final dx = math.sin(_shakeCtrl.value * math.pi * 4) *
                              14.0 *
                              (1.0 - _shakeCtrl.value);
                          return Transform.translate(
                            offset: Offset(dx, 0),
                            child: child,
                          );
                        },
                        child: SingleChildScrollView(
                          key: ValueKey(s.index),
                          padding: const EdgeInsets.only(top: 16, bottom: 16),
                          child: Column(
                            children: [
                              if (s.topics[task.item.topicId]
                                  case final topicTitle?)
                                Padding(
                                  padding: const EdgeInsets.only(bottom: 8),
                                  child: Text(
                                    topicTitle,
                                    maxLines: 1,
                                    overflow: TextOverflow.ellipsis,
                                    textAlign: TextAlign.center,
                                    style: Theme.of(context)
                                        .textTheme
                                        .labelMedium
                                        ?.copyWith(color: AppColors.muted),
                                  ),
                                ),
                              _TaskBody(
                                task: task,
                                feedback: s.feedback,
                                neutral: s.neutral,
                                examId: s.examId,
                                voice: voice,
                                replayKey: _retry,
                                autoplay: mayAutoplay(
                                  s.autoplaySuppressedAt,
                                  s.index,
                                ),
                                silent: !s.audio,
                                vm: widget.vm,
                                lessonsOnly: s.lessonsOnly,
                                runLessons: [
                                  for (final t in s.tasks)
                                    if (t is TaskTeach) t.lesson,
                                ],
                                onChapterClick: s.lessonsOnly
                                    ? () => setState(() => _showChapters = true)
                                    : null,
                                nextLabel: teachButtonLabel(
                                  s.index + 1 < s.tasks.length
                                      ? s.tasks[s.index + 1]
                                      : null,
                                ),
                              ),
                            ],
                          ),
                        ),
                      ),
                      if (s.feedback != null &&
                          s.fx != null &&
                          s.fx!.outcome.correct)
                        CorrectFx(s.fx!),
                      if (s.sits != null)
                        Align(
                          alignment: Alignment.topCenter,
                          child: SitsBanner(s.sits!),
                        ),
                    ],
                  ),
                ),
                if (s.lessonsOnly && task is TaskTeach)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 16),
                    child: Row(
                      children: [
                        Expanded(
                          flex: 10,
                          child: SecondaryButton(
                            'Zurück',
                            onPressed: widget.vm.previous,
                            color: s.index == 0
                                ? AppColors.muted
                                : Colors.white,
                          ),
                        ),
                        if (s.audio && voice.buttonVisible) ...[
                          const SizedBox(width: 10),
                          Expanded(
                            flex: 13,
                            child: PrimaryButton(
                              voice.playState == PlayState.playing
                                  ? 'Pause'
                                  : 'Abspielen',
                              onPressed: () {
                                switch (playTap(voice.playState)) {
                                  case PlayTap.pause:
                                    voice.pause();
                                  case PlayTap.resume:
                                    voice.resume();
                                  case PlayTap.restart:
                                    widget.vm.allowAutoplay();
                                    setState(() => _retry++);
                                }
                              },
                            ),
                          ),
                        ],
                        const SizedBox(width: 10),
                        Expanded(
                          flex: 10,
                          child: SecondaryButton(
                            s.index >= s.tasks.length - 1 ? 'Fertig' : 'Weiter',
                            onPressed: widget.vm.next,
                          ),
                        ),
                      ],
                    ),
                  ),
                if (task is TaskChoice ||
                    task is TaskCloze ||
                    task is TaskSpot ||
                    task is TaskMatch ||
                    task is TaskOrder ||
                    task is TaskBucket ||
                    task is TaskBuild)
                  SizedBox(
                    width: double.infinity,
                    height: 76,
                    child: Padding(
                      padding: const EdgeInsets.only(bottom: 8),
                      child: Center(
                        child: s.feedback == null
                            ? HapticTextButton(
                                onPressed: widget.vm.dontKnow,
                                child: Text(
                                  'Weiß nicht',
                                  style: Theme.of(context)
                                      .textTheme
                                      .labelLarge
                                      ?.copyWith(color: AppColors.muted),
                                ),
                              )
                            : PrimaryButton('Weiter', onPressed: widget.vm.next),
                      ),
                    ),
                  ),
              ],
            ),
            if (_showChapters && task is TaskTeach)
              ChapterSheet(
                entries: chapterEntries(
                  [
                    for (final t in s.tasks)
                      if (t is TaskTeach) t.lesson,
                  ],
                  s.chapters,
                  s.heardIds,
                ),
                currentChapterId: task.lesson.chapterId,
                onPick: (e) => widget.vm.jumpTo(e.jumpIndex),
                onDismiss: () => setState(() => _showChapters = false),
              ),
          ],
        );
      },
    );
  }
}

class _TaskBody extends StatelessWidget {
  final Task task;
  final bool? feedback;
  final bool neutral;
  final String examId;
  final Voice voice;
  final int replayKey;
  final bool autoplay;
  final bool silent;
  final SessionViewModel vm;
  final bool lessonsOnly;
  final List<SessionLesson> runLessons;
  final VoidCallback? onChapterClick;
  final String nextLabel;

  const _TaskBody({
    required this.task,
    required this.feedback,
    required this.neutral,
    required this.examId,
    required this.voice,
    required this.replayKey,
    required this.autoplay,
    required this.silent,
    required this.vm,
    required this.lessonsOnly,
    required this.runLessons,
    required this.onChapterClick,
    required this.nextLabel,
  });

  @override
  Widget build(BuildContext context) {
    final t = task;
    final answered = feedback != null;
    final revealed = neutral;
    final hl = voice.highlight;

    return switch (t) {
      TaskCard() => CardTask(
          task: t,
          highlight: hl,
          onAnswer: (ok) => vm.answer(ok),
        ),
      TaskLesson() => LessonTask(
          task: t,
          onDone: () {
            vm.answer(true);
            vm.next();
          },
        ),
      TaskTeach() => TeachCard(
          task: t,
          examId: examId,
          voice: voice,
          replayKey: replayKey,
          autoplay: autoplay,
          silent: silent,
          onHeard: vm.heard,
          onNext: vm.next,
          lessonsOnly: lessonsOnly,
          header: lessonHeader(t.lesson, runLessons, lessonsOnly: lessonsOnly),
          onChapterClick: onChapterClick,
          wasForward: () => vm.lastMoveForward,
          buttonLabel: nextLabel,
        ),
      TaskChoice() => ChoiceTask(
          task: t,
          answered: answered,
          revealed: revealed,
          highlight: hl,
          onAnswer: (ok) => vm.answer(ok),
        ),
      TaskCloze() => ClozeTask(
          task: t,
          revealed: revealed,
          highlight: hl,
          onAnswer: (ok) => vm.answer(ok),
        ),
      TaskSpot() => SpotTask(
          task: t,
          revealed: revealed,
          highlight: hl,
          onAnswer: (ok) => vm.answer(ok),
        ),
      TaskMatch() => MatchTask(
          task: t,
          revealed: revealed,
          highlight: hl,
          onAnswer: (ok, payload) => vm.answer(ok, payload: payload),
        ),
      TaskOrder() => OrderTask(
          task: t,
          revealed: revealed,
          highlight: hl,
          onAnswer: (ok, payload) => vm.answer(ok, payload: payload),
        ),
      TaskBucket() => BucketTask(
          task: t,
          revealed: revealed,
          highlight: hl,
          onAnswer: (ok, payload) => vm.answer(ok, payload: payload),
        ),
      TaskBuild() => BuildTask(
          task: t,
          revealed: revealed,
          highlight: hl,
          onAnswer: (ok, payload) => vm.answer(ok, payload: payload),
        ),
    };
  }
}

class _DoneView extends StatefulWidget {
  final SessionUi s;
  final VoidCallback onClose;

  const _DoneView({required this.s, required this.onClose});

  @override
  State<_DoneView> createState() => _DoneViewState();
}

class _DoneViewState extends State<_DoneView> {
  @override
  void initState() {
    super.initState();
    AppServices.instance.sfx.play(Tones.done);
    unawaited(Haptics.instance.celebrate(3));
  }

  @override
  Widget build(BuildContext context) {
    final s = widget.s;
    final before = s.scoreBefore;
    final after = s.scoreAfter;

    return TweenAnimationBuilder<double>(
      tween: Tween<double>(begin: 0, end: 1),
      duration: const Duration(milliseconds: 1100),
      builder: (context, frac, _) {
        final correct = (s.correctCount * frac).round();
        final xp = (s.xp * frac).round();
        final best = (s.bestStreak * frac).round();
        final seconds = (s.elapsedSeconds * frac).round();
        final timeStr =
            '${seconds ~/ 60}:${(seconds % 60).toString().padLeft(2, '0')}';

        return Stack(
          fit: StackFit.expand,
          children: [
            const Confetti(),
            Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                const LearnMark(size: 56),
                const SizedBox(height: 16),
                Text(
                  'Heute geschafft',
                  textAlign: TextAlign.center,
                  style: Theme.of(context).textTheme.displaySmall,
                ),
                const SizedBox(height: 20),
                Row(
                  children: [
                    Expanded(
                      child: _StatCell(
                        value: '$correct / ${s.answerCount}',
                        label: 'richtig',
                      ),
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: _StatCell(value: timeStr, label: 'Zeit'),
                    ),
                  ],
                ),
                const SizedBox(height: 10),
                Row(
                  children: [
                    Expanded(
                      child: _StatCell(value: '$best', label: 'Serie'),
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: _StatCell(value: '+$xp', label: 'Punkte'),
                    ),
                  ],
                ),
                if (after != null &&
                    (before == null || floor5(before) != floor5(after))) ...[
                  const SizedBox(height: 16),
                  Text(
                    beforeAfterLine(before ?? after, after),
                    style: Theme.of(context)
                        .textTheme
                        .titleMedium
                        ?.copyWith(color: AppColors.accent),
                  ),
                ],
                if (s.dayStreak >= 2) ...[
                  const SizedBox(height: 8),
                  Text(
                    '${s.dayStreak} Tage in Folge',
                    style: Theme.of(context)
                        .textTheme
                        .bodyMedium
                        ?.copyWith(color: AppColors.muted),
                  ),
                ],
                const SizedBox(height: 32),
                PrimaryButton('Fertig', onPressed: widget.onClose),
              ],
            ),
          ],
        );
      },
    );
  }
}

class _StatCell extends StatelessWidget {
  final String value;
  final String label;

  const _StatCell({required this.value, required this.label});

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.symmetric(vertical: 14),
        decoration: BoxDecoration(
          color: AppColors.cardHigh,
          borderRadius: BorderRadius.circular(20),
        ),
        child: Column(
          children: [
            Text(
              value,
              style: Theme.of(context)
                  .textTheme
                  .headlineSmall
                  ?.copyWith(color: AppColors.accent),
            ),
            Text(
              label,
              style: Theme.of(context)
                  .textTheme
                  .labelMedium
                  ?.copyWith(color: AppColors.muted),
            ),
          ],
        ),
      );
}
