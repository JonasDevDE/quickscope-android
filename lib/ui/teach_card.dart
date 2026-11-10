import 'dart:async';
import 'package:flutter/material.dart';
import '../logic/audiobook.dart';
import '../logic/lesson_header.dart';
import '../logic/listen.dart';
import '../logic/tasks.dart';
import '../logic/understand.dart';
import 'components.dart';
import 'haptics.dart';
import 'theme.dart';
import 'voice.dart';

/// Lektionskarte der Lernrunde: Titel und Stichworte, die Lektion wird sofort abgespielt, ein Knopf "Weiter"
/// springt direkt zu den Fragen. Gemeldet wird, wie viel gehoert wurde.
class TeachCard extends StatefulWidget {
  final TaskTeach task;
  final String examId;
  final Voice voice;
  final int replayKey;
  final bool autoplay;
  final bool silent;
  final void Function(String lessonId, double fraction) onHeard;
  final VoidCallback onNext;
  final bool lessonsOnly;
  final LessonHeader? header;
  final VoidCallback? onChapterClick;
  final bool Function()? wasForward;
  final String buttonLabel;

  const TeachCard({
    super.key,
    required this.task,
    required this.examId,
    required this.voice,
    required this.replayKey,
    required this.autoplay,
    required this.silent,
    required this.onHeard,
    required this.onNext,
    this.lessonsOnly = false,
    this.header,
    this.onChapterClick,
    this.wasForward,
    this.buttonLabel = 'Weiter zum Test',
  });

  @override
  State<TeachCard> createState() => _TeachCardState();
}

class _TeachCardState extends State<TeachCard> {
  late int _shownAtMs = DateTime.now().millisecondsSinceEpoch;
  int _elapsedMs = 0;
  bool _finished = false;
  late bool _highlightChapter = widget.header?.newChapter == true;
  Timer? _ticker;
  Timer? _highlightTimer;
  int _playGen = 0;

  int get _durationMs => widget.task.lesson.estSeconds * 1000;

  @override
  void initState() {
    super.initState();
    _initLesson();
  }

  @override
  void didUpdateWidget(covariant TeachCard oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.task.lesson.id != widget.task.lesson.id) {
      _reportOnDispose(oldWidget);
      _shownAtMs = DateTime.now().millisecondsSinceEpoch;
      _elapsedMs = 0;
      _finished = false;
      _highlightChapter = widget.header?.newChapter == true;
      _initLesson();
    } else if (oldWidget.replayKey != widget.replayKey ||
        oldWidget.silent != widget.silent) {
      _startAudio();
    }
  }

  void _initLesson() {
    _highlightTimer?.cancel();
    if (_highlightChapter) {
      _highlightTimer = Timer(const Duration(milliseconds: 2500), () {
        if (mounted) setState(() => _highlightChapter = false);
      });
    }
    _startAudio();
  }

  void _startAudio() {
    _ticker?.cancel();
    final gen = ++_playGen;
    if (widget.silent) return;
    if (!widget.autoplay && widget.replayKey == 0) return;

    final t0 = DateTime.now().millisecondsSinceEpoch;
    setState(() {
      _finished = false;
      _elapsedMs = 0;
    });

    _ticker = Timer.periodic(const Duration(milliseconds: 250), (_) {
      if (mounted && gen == _playGen) {
        setState(
          () => _elapsedMs = DateTime.now().millisecondsSinceEpoch - t0,
        );
      }
    });

    unawaited(() async {
      var played = false;
      try {
        played = await widget.voice.speakLesson(
          widget.examId,
          widget.task.lesson.id,
        );
      } finally {
        if (gen == _playGen) _ticker?.cancel();
      }
      if (!mounted || gen != _playGen) return;
      setState(() => _finished = played);
      if (autoAdvance(widget.lessonsOnly, played)) {
        widget.onNext();
      }
    }());
  }

  void _reportOnDispose(TeachCard w) {
    if (!w.lessonsOnly) return;
    final f = heardToReport(
      audioPlayed: _elapsedMs > 0,
      positionMs: _elapsedMs,
      durationMs: w.task.lesson.estSeconds * 1000,
      finished: _finished,
      shownMs: DateTime.now().millisecondsSinceEpoch - _shownAtMs,
      forward: w.wasForward?.call() ?? true,
    );
    if (f != null) w.onHeard(w.task.lesson.id, f);
  }

  @override
  void dispose() {
    _ticker?.cancel();
    _highlightTimer?.cancel();
    _reportOnDispose(widget);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final lesson = widget.task.lesson;
    final header = widget.header;
    final theme = Theme.of(context).textTheme;
    final active = _finished
        ? lesson.keyPoints.length - 1
        : activeKeyPoint(lesson.keyPoints.length, _elapsedMs, _durationMs);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (header?.chapterLine case final line?) ...[
          GestureDetector(
            onTap: widget.onChapterClick != null
                ? () {
                    Haptics.instance.tick();
                    widget.onChapterClick!();
                  }
                : null,
            behavior: HitTestBehavior.opaque,
            child: ConstrainedBox(
              constraints: BoxConstraints(
                minHeight: widget.onChapterClick != null ? 48 : 0,
                minWidth: double.infinity,
              ),
              child: Padding(
                padding: EdgeInsets.symmetric(
                  vertical: widget.onChapterClick != null ? 12 : 0,
                ),
                child: Text(
                  line,
                  textAlign: TextAlign.center,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: (widget.lessonsOnly
                          ? theme.labelLarge
                          : theme.labelMedium)
                      ?.copyWith(
                    color: _highlightChapter
                        ? AppColors.accent
                        : AppColors.muted,
                  ),
                ),
              ),
            ),
          ),
          const SizedBox(height: 14),
        ],
        SizedBox(
          width: double.infinity,
          child: Padding(
            padding: const EdgeInsets.symmetric(vertical: 4),
            child: Text(
              lesson.title,
              textAlign: TextAlign.center,
              style: (widget.lessonsOnly
                      ? theme.headlineMedium
                      : theme.headlineSmall)
                  ?.copyWith(fontWeight: FontWeight.bold),
            ),
          ),
        ),
        if (header?.progress case (final int cur, final int total)) ...[
          const SizedBox(height: 14),
          SizedBox(
            width: double.infinity,
            child: Column(
              children: [
                if (total >= 2 && total <= 12) ...[
                  Row(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      for (var i = 0; i < total; i++)
                        Container(
                          margin: const EdgeInsets.symmetric(horizontal: 3),
                          width: i + 1 == cur ? 8 : 6,
                          height: i + 1 == cur ? 8 : 6,
                          decoration: BoxDecoration(
                            color: i + 1 == cur
                                ? AppColors.accent
                                : AppColors.cardHigh,
                            shape: BoxShape.circle,
                          ),
                        ),
                    ],
                  ),
                  const SizedBox(height: 6),
                ],
                Text(
                  header?.progressText ?? '',
                  style: theme.bodySmall?.copyWith(color: AppColors.muted),
                ),
              ],
            ),
          ),
        ],
        const SizedBox(height: 14),
        for (var i = 0; i < lesson.keyPoints.length; i++) ...[
          if (i > 0) const SizedBox(height: 14),
          Row(
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              Padding(
                padding: const EdgeInsets.only(right: 12),
                child: Text(
                  '•',
                  style: theme.titleMedium?.copyWith(
                    color: i == active ? AppColors.accent : AppColors.muted,
                  ),
                ),
              ),
              Expanded(
                child: Text(
                  lesson.keyPoints[i],
                  style: theme.titleMedium?.copyWith(
                    color: i == active
                        ? AppColors.accent
                        : Colors.white.withValues(alpha: 0.75),
                  ),
                ),
              ),
            ],
          ),
        ],
        const SizedBox(height: 14),
        SizedBox(
          width: double.infinity,
          height: 24,
          child: Text(
            (_finished || showMnemonic(_elapsedMs, _durationMs))
                ? (lesson.mnemonic ?? '')
                : '',
            textAlign: TextAlign.center,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: theme.bodyMedium?.copyWith(color: AppColors.accent),
          ),
        ),
        const SizedBox(height: 18),
        if (!widget.lessonsOnly)
          PrimaryButton(
            widget.buttonLabel,
            onPressed: () {
              widget.voice.stop();
              final fraction =
                  heardFraction(_elapsedMs, _durationMs, _finished);
              if (fraction > 0.05) {
                widget.onHeard(lesson.id, fraction);
              }
              widget.onNext();
            },
          ),
      ],
    );
  }
}
