import 'dart:math' as math;
import 'package:flutter/material.dart';
import '../app_services.dart';
import '../logic/feedback.dart';
import '../logic/tasks.dart';
import 'components.dart';
import 'haptics.dart';
import 'theme.dart';
import 'voice.dart';

class QuestionText extends StatelessWidget {
  final String text;
  final Highlight highlight;

  const QuestionText(
    this.text, {
    super.key,
    this.highlight = const Highlight(),
  });

  @override
  Widget build(BuildContext context) {
    final baseStyle = Theme.of(context)
        .textTheme
        .headlineMedium
        ?.copyWith(fontWeight: FontWeight.bold);

    if (highlight.text == text && highlight.words.isNotEmpty) {
      final spans = <TextSpan>[];
      var cursor = 0;
      for (var i = 0; i < highlight.words.length; i++) {
        final w = highlight.words[i];
        final start = w.start.clamp(0, text.length);
        final end = w.end.clamp(start, text.length);
        if (start > cursor) {
          spans.add(TextSpan(text: text.substring(cursor, start)));
        }
        TextStyle? wordStyle;
        if (i == highlight.active) {
          wordStyle = TextStyle(
            color: AppColors.accent,
            backgroundColor: AppColors.accent.withValues(alpha: 0.18),
          );
        } else if (i < highlight.spoken) {
          wordStyle = TextStyle(color: Colors.white.withValues(alpha: 0.55));
        }
        spans.add(TextSpan(text: text.substring(start, end), style: wordStyle));
        cursor = end;
      }
      if (cursor < text.length) {
        spans.add(TextSpan(text: text.substring(cursor)));
      }
      return Padding(
        padding: const EdgeInsets.symmetric(vertical: 8),
        child: SizedBox(
          width: double.infinity,
          child: Text.rich(
            TextSpan(style: baseStyle, children: spans),
            textAlign: TextAlign.center,
          ),
        ),
      );
    }

    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: SizedBox(
        width: double.infinity,
        child: Text(
          text,
          textAlign: TextAlign.center,
          style: baseStyle,
        ),
      ),
    );
  }
}

/// Eine grosse Antwortflaeche; nach der Antwort gruen (richtig) bzw. rot (gewaehlt, falsch) eingefaerbt.
class _AnswerBox extends StatelessWidget {
  final String text;
  final int state; // 0 neutral, 1 richtig, 2 falsch, 3 gedimmt
  final VoidCallback onTap;

  const _AnswerBox({
    required this.text,
    required this.state,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final border = switch (state) {
      1 => Border.all(color: AppColors.accent, width: 2),
      2 => Border.all(color: AppColors.soft, width: 2),
      _ => null,
    };
    return GestureDetector(
      onTap: () {
        Haptics.instance.tick();
        onTap();
      },
      child: Container(
        width: double.infinity,
        constraints: const BoxConstraints(minHeight: 56),
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 14),
        alignment: Alignment.center,
        decoration: BoxDecoration(
          color: state == 3 ? AppColors.card : AppColors.cardHigh,
          borderRadius: BorderRadius.circular(24),
          border: border,
        ),
        child: Text(
          text,
          textAlign: TextAlign.center,
          style: Theme.of(context).textTheme.titleMedium?.copyWith(
                color: state == 3 ? AppColors.muted : Colors.white,
              ),
        ),
      ),
    );
  }
}

/// Karte: Tipp zum Aufdecken, danach zwei grosse Knoepfe "Gewusst" / "Nicht gewusst".
class CardTask extends StatefulWidget {
  final TaskCard task;
  final Highlight highlight;
  final ValueChanged<bool> onAnswer;

  const CardTask({
    super.key,
    required this.task,
    required this.highlight,
    required this.onAnswer,
  });

  @override
  State<CardTask> createState() => _CardTaskState();
}

class _CardTaskState extends State<CardTask> {
  final _flow = CardFlow();
  bool _shown = false;

  @override
  Widget build(BuildContext context) {
    final item = widget.task.item;
    final exp = shortExplanation(item.explanation);
    final theme = Theme.of(context).textTheme;

    return Column(
      children: [
        QuestionText(item.question, highlight: widget.highlight),
        const SizedBox(height: 14),
        if (!_shown)
          _AnswerBox(
            text: 'Aufdecken',
            state: 0,
            onTap: () {
              _flow.reveal();
              setState(() => _shown = true);
            },
          )
        else ...[
          Text(
            widget.task.answer,
            textAlign: TextAlign.center,
            style: theme.titleLarge?.copyWith(color: AppColors.accent),
          ),
          if (item.mnemonic != null) ...[
            const SizedBox(height: 14),
            Text(
              item.mnemonic!,
              textAlign: TextAlign.center,
              style: theme.bodySmall?.copyWith(color: AppColors.muted),
            ),
          ],
          if (exp != null) ...[
            const SizedBox(height: 14),
            Text(
              exp,
              textAlign: TextAlign.center,
              style: theme.bodySmall?.copyWith(color: AppColors.muted),
            ),
          ],
          const SizedBox(height: 14),
          Row(
            children: [
              Expanded(
                child: SecondaryButton(
                  'Nicht gewusst',
                  color: AppColors.soft,
                  onPressed: () {
                    if (_flow.answer(false) != null) widget.onAnswer(false);
                  },
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: PrimaryButton(
                  'Gewusst',
                  onPressed: () {
                    if (_flow.answer(true) != null) widget.onAnswer(true);
                  },
                ),
              ),
            ],
          ),
        ],
      ],
    );
  }
}

/// Loesungsweg: EINE ruhige Karte mit hoechstens 3 Schritten und optionaler Merkregel.
class LessonTask extends StatelessWidget {
  final TaskLesson task;
  final VoidCallback onDone;

  const LessonTask({super.key, required this.task, required this.onDone});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context).textTheme;
    return Column(
      children: [
        Text(
          task.item.question,
          textAlign: TextAlign.center,
          style: theme.headlineSmall,
        ),
        const SizedBox(height: 14),
        CardColumn(
          spacing: 12,
          children: [
            for (var i = 0; i < task.steps.length; i++)
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Padding(
                    padding: const EdgeInsets.only(right: 14),
                    child: Text(
                      '${i + 1}',
                      style:
                          theme.titleMedium?.copyWith(color: AppColors.accent),
                    ),
                  ),
                  Expanded(
                    child: Text(task.steps[i], style: theme.bodyLarge),
                  ),
                ],
              ),
            if (task.trick != null)
              Text(
                'Merke: ${task.trick}',
                style: theme.bodyMedium?.copyWith(color: AppColors.accent),
              ),
          ],
        ),
        const SizedBox(height: 14),
        PrimaryButton('Weiter', onPressed: onDone),
      ],
    );
  }
}

/// mc, choice2, number, truefalse: Optionen als grosse Flaechen; ein Tipp antwortet.
class ChoiceTask extends StatefulWidget {
  final TaskChoice task;
  final bool answered;
  final bool revealed;
  final Highlight highlight;
  final ValueChanged<bool> onAnswer;

  const ChoiceTask({
    super.key,
    required this.task,
    required this.answered,
    required this.revealed,
    required this.highlight,
    required this.onAnswer,
  });

  @override
  State<ChoiceTask> createState() => _ChoiceTaskState();
}

class _ChoiceTaskState extends State<ChoiceTask> {
  int _chosen = -1;

  void _pick(int i) {
    if (widget.answered || _chosen >= 0) return;
    setState(() => _chosen = i);
    widget.onAnswer(i == widget.task.correct);
  }

  @override
  Widget build(BuildContext context) => Column(
        children: [
          QuestionText(widget.task.item.question, highlight: widget.highlight),
          for (var i = 0; i < widget.task.options.length; i++) ...[
            const SizedBox(height: 10),
            _AnswerBox(
              text: widget.task.options[i],
              state: (_chosen < 0 && !widget.revealed)
                  ? 0
                  : i == widget.task.correct
                      ? 1
                      : i == _chosen
                          ? 2
                          : 3,
              onTap: () => _pick(i),
            ),
          ],
        ],
      );
}

/// Lueckentext: der Satz mit Luecke, darunter die Wortbank als Chips.
class ClozeTask extends StatefulWidget {
  final TaskCloze task;
  final bool revealed;
  final Highlight highlight;
  final ValueChanged<bool> onAnswer;

  const ClozeTask({
    super.key,
    required this.task,
    required this.revealed,
    required this.highlight,
    required this.onAnswer,
  });

  @override
  State<ClozeTask> createState() => _ClozeTaskState();
}

class _ClozeTaskState extends State<ClozeTask> {
  String? _chosen;

  @override
  Widget build(BuildContext context) {
    final filled = _chosen ?? '_____';
    final text = widget.task.text.contains('___')
        ? widget.task.text.replaceFirst(RegExp(r'_{3,}'), filled)
        : '${widget.task.text} $filled';

    return Column(
      children: [
        QuestionText(text, highlight: widget.highlight),
        for (final word in widget.task.bank) ...[
          const SizedBox(height: 14),
          _AnswerBox(
            text: word,
            state: (_chosen == null && !widget.revealed)
                ? 0
                : word == widget.task.answer
                    ? 1
                    : word == _chosen
                        ? 2
                        : 3,
            onTap: () {
              if (_chosen == null) {
                setState(() => _chosen = word);
                widget.onAnswer(word == widget.task.answer);
              }
            },
          ),
        ],
      ],
    );
  }
}

/// Fehler finden: Aussagen als Flaechen, Tipp auf die falsche.
class SpotTask extends StatefulWidget {
  final TaskSpot task;
  final bool revealed;
  final Highlight highlight;
  final ValueChanged<bool> onAnswer;

  const SpotTask({
    super.key,
    required this.task,
    required this.revealed,
    required this.highlight,
    required this.onAnswer,
  });

  @override
  State<SpotTask> createState() => _SpotTaskState();
}

class _SpotTaskState extends State<SpotTask> {
  int _chosen = -1;

  @override
  Widget build(BuildContext context) {
    final q = widget.task.item.question.trim().isEmpty
        ? 'Was ist falsch?'
        : widget.task.item.question;
    return Column(
      children: [
        QuestionText(q, highlight: widget.highlight),
        for (var i = 0; i < widget.task.statements.length; i++) ...[
          const SizedBox(height: 10),
          _AnswerBox(
            text: widget.task.statements[i],
            state: (_chosen < 0 && !widget.revealed)
                ? 0
                : i == widget.task.falseIndex
                    ? 1
                    : i == _chosen
                        ? 2
                        : 3,
            onTap: () {
              if (_chosen < 0) {
                setState(() => _chosen = i);
                widget.onAnswer(i == widget.task.falseIndex);
              }
            },
          ),
        ],
      ],
    );
  }
}

const _pairColors = [
  Color(0xFFD4FF3A),
  Color(0xFF5EC8FF),
  Color(0xFFFF9F5A),
  Color(0xFFC59BFF),
  Color(0xFFFF7EB6),
  Color(0xFF7CFFB2),
];

/// Zuordnen per Tippen: links waehlen, dann rechts; jedes Paar wird sofort ausgewertet.
class MatchTask extends StatefulWidget {
  final TaskMatch task;
  final bool revealed;
  final Highlight highlight;
  final void Function(bool correct, Object? payload) onAnswer;

  const MatchTask({
    super.key,
    required this.task,
    required this.revealed,
    required this.highlight,
    required this.onAnswer,
  });

  @override
  State<MatchTask> createState() => _MatchTaskState();
}

class _MatchTaskState extends State<MatchTask>
    with SingleTickerProviderStateMixin {
  late final MatchState _state = MatchState.of(widget.task);
  late final AnimationController _shakeCtrl = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 300),
  );
  String? _wrongLeft;
  String? _wrongRight;

  @override
  void didUpdateWidget(covariant MatchTask oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.revealed && !oldWidget.revealed) {
      setState(() => _state.revealAll());
    }
  }

  @override
  void dispose() {
    _shakeCtrl.dispose();
    super.dispose();
  }

  void _triggerWrong(String? left, String right) {
    setState(() {
      _wrongLeft = left;
      _wrongRight = right;
    });
    _shakeCtrl.forward(from: 0).then((_) {
      if (mounted) {
        setState(() {
          _wrongLeft = null;
          _wrongRight = null;
        });
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final q = widget.task.item.question.trim().isEmpty
        ? 'Zuordnen'
        : widget.task.item.question;

    return Column(
      children: [
        QuestionText(q, highlight: widget.highlight),
        const SizedBox(height: 10),
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: Column(
                children: [
                  for (var i = 0; i < _state.lefts.length; i++) ...[
                    if (i > 0) const SizedBox(height: 10),
                    Builder(builder: (_) {
                      final left = _state.lefts[i];
                      final c = _state.colorOf(left);
                      return _MatchCell(
                        text: left,
                        pairColor: c >= 0
                            ? _pairColors[c % _pairColors.length]
                            : null,
                        selected: _state.selectedLeft == left,
                        wrong: _wrongLeft == left,
                        shake: _shakeCtrl,
                        onTap: () {
                          if (!_state.done) {
                            setState(() => _state.tapLeft(left));
                          }
                        },
                      );
                    }),
                  ],
                ],
              ),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: Column(
                children: [
                  for (var i = 0; i < _state.rights.length; i++) ...[
                    if (i > 0) const SizedBox(height: 10),
                    Builder(builder: (_) {
                      final right = _state.rights[i];
                      final c = _state.colorOfRight(right);
                      return _MatchCell(
                        text: right,
                        pairColor: c >= 0
                            ? _pairColors[c % _pairColors.length]
                            : null,
                        selected: false,
                        wrong: _wrongRight == right,
                        shake: _shakeCtrl,
                        onTap: () {
                          if (_state.done) return;
                          final left = _state.selectedLeft;
                          final result = _state.tapRight(right);
                          if (!_state.done) {
                            if (soundForMatch(result) case final tone?) {
                              AppServices.instance.sfx.play(tone);
                            }
                          }
                          switch (result) {
                            case MatchResult.right:
                              Haptics.instance.confirm();
                              setState(() {});
                            case MatchResult.revealed:
                              Haptics.instance.tick();
                              setState(() {});
                            case MatchResult.wrong:
                              Haptics.instance.tick();
                              _triggerWrong(left, right);
                            case MatchResult.none:
                              setState(() {});
                          }
                          if (_state.done) {
                            widget.onAnswer(
                              _state.known,
                              matchAnswer(widget.task, _state),
                            );
                          }
                        },
                      );
                    }),
                  ],
                ],
              ),
            ),
          ],
        ),
      ],
    );
  }
}

class _MatchCell extends StatelessWidget {
  final String text;
  final Color? pairColor;
  final bool selected;
  final bool wrong;
  final Animation<double> shake;
  final VoidCallback onTap;

  const _MatchCell({
    required this.text,
    required this.pairColor,
    required this.selected,
    required this.wrong,
    required this.shake,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final bg = pairColor ??
        (wrong ? AppColors.soft.withValues(alpha: 0.35) : AppColors.cardHigh);
    return AnimatedBuilder(
      animation: shake,
      builder: (_, child) {
        final dx = wrong
            ? math.sin(shake.value * math.pi * 4) *
                10.0 *
                (1.0 - shake.value)
            : 0.0;
        return Transform.translate(offset: Offset(dx, 0), child: child);
      },
      child: GestureDetector(
        onTap: pairColor == null
            ? () {
                Haptics.instance.tick();
                onTap();
              }
            : null,
        child: Container(
          width: double.infinity,
          constraints: const BoxConstraints(minHeight: 56),
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
          alignment: Alignment.center,
          decoration: BoxDecoration(
            color: bg,
            borderRadius: BorderRadius.circular(20),
            border: selected
                ? Border.all(color: AppColors.accent, width: 2)
                : null,
          ),
          child: Text(
            text,
            textAlign: TextAlign.center,
            style: Theme.of(context).textTheme.titleSmall?.copyWith(
                  color: pairColor != null ? Colors.black : Colors.white,
                ),
          ),
        ),
      ),
    );
  }
}

/// Reihenfolge per Tippen: die Schritte der Reihe nach antippen.
class OrderTask extends StatefulWidget {
  final TaskOrder task;
  final bool revealed;
  final Highlight highlight;
  final void Function(bool correct, Object? payload) onAnswer;

  const OrderTask({
    super.key,
    required this.task,
    required this.revealed,
    required this.highlight,
    required this.onAnswer,
  });

  @override
  State<OrderTask> createState() => _OrderTaskState();
}

class _OrderTaskState extends State<OrderTask> {
  late final OrderState _state = OrderState.of(widget.task);

  @override
  void didUpdateWidget(covariant OrderTask oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.revealed && !oldWidget.revealed) {
      setState(() => _state.revealAll());
    }
  }

  @override
  Widget build(BuildContext context) {
    final q = widget.task.item.question.trim().isEmpty
        ? 'Reihenfolge'
        : widget.task.item.question;

    return Column(
      children: [
        QuestionText(q, highlight: widget.highlight),
        for (final step in _state.shuffled) ...[
          const SizedBox(height: 10),
          Builder(builder: (_) {
            final taken = _state.isTaken(step);
            final number = taken ? _state.steps.indexOf(step) + 1 : null;
            return GestureDetector(
              onTap: (!taken && !_state.done)
                  ? () {
                      final ok = _state.tap(step);
                      if (ok) {
                        Haptics.instance.tick();
                      } else {
                        Haptics.instance.reject();
                      }
                      setState(() {});
                      if (_state.done) {
                        widget.onAnswer(
                          _state.mistakes == 0,
                          orderAnswer(widget.task, _state),
                        );
                      }
                    }
                  : null,
              child: Container(
                width: double.infinity,
                constraints: const BoxConstraints(minHeight: 56),
                padding:
                    const EdgeInsets.symmetric(horizontal: 20, vertical: 14),
                decoration: BoxDecoration(
                  color: taken ? AppColors.accent : AppColors.cardHigh,
                  borderRadius: BorderRadius.circular(24),
                ),
                child: Row(
                  children: [
                    if (number != null)
                      Padding(
                        padding: const EdgeInsets.only(right: 14),
                        child: Text(
                          '$number',
                          style: Theme.of(context)
                              .textTheme
                              .titleMedium
                              ?.copyWith(color: AppColors.onAccent),
                        ),
                      ),
                    Expanded(
                      child: Text(
                        step,
                        style: Theme.of(context)
                            .textTheme
                            .titleMedium
                            ?.copyWith(
                              color:
                                  taken ? AppColors.onAccent : Colors.white,
                            ),
                      ),
                    ),
                  ],
                ),
              ),
            );
          }),
        ],
      ],
    );
  }
}

/// Einsortieren: das aktuelle Element gross, darunter 2 bis 4 grosse Kategorie-Knoepfe.
class BucketTask extends StatefulWidget {
  final TaskBucket task;
  final bool revealed;
  final Highlight highlight;
  final void Function(bool correct, Object? payload) onAnswer;

  const BucketTask({
    super.key,
    required this.task,
    required this.revealed,
    required this.highlight,
    required this.onAnswer,
  });

  @override
  State<BucketTask> createState() => _BucketTaskState();
}

class _BucketTaskState extends State<BucketTask>
    with SingleTickerProviderStateMixin {
  late final BucketState _state = BucketState.of(widget.task);
  late final AnimationController _shakeCtrl = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 300),
  );
  String? _wrongCategory;

  @override
  void didUpdateWidget(covariant BucketTask oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.revealed && !oldWidget.revealed) {
      setState(() => _state.revealAll());
    }
  }

  @override
  void dispose() {
    _shakeCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final q = widget.task.item.question.trim().isEmpty
        ? 'Einsortieren'
        : widget.task.item.question;
    final theme = Theme.of(context).textTheme;

    return Column(
      children: [
        QuestionText(q, highlight: widget.highlight),
        const SizedBox(height: 10),
        Container(
          width: double.infinity,
          constraints: const BoxConstraints(minHeight: 84),
          padding: const EdgeInsets.all(16),
          alignment: Alignment.center,
          decoration: BoxDecoration(
            color: Colors.black,
            borderRadius: BorderRadius.circular(24),
            border: Border.all(color: AppColors.outline),
          ),
          child: Text(
            _state.current?.text ?? 'Geschafft',
            textAlign: TextAlign.center,
            style: theme.titleLarge?.copyWith(
              color: _state.done ? AppColors.accent : Colors.white,
            ),
          ),
        ),
        const SizedBox(height: 10),
        Text(
          '${math.min(_state.index + 1, _state.entries.length)} von ${_state.entries.length}',
          textAlign: TextAlign.center,
          style: theme.bodySmall?.copyWith(color: AppColors.muted),
        ),
        for (final (id, title) in widget.task.categories) ...[
          const SizedBox(height: 10),
          _MatchCell(
            text: title,
            pairColor: null,
            selected: false,
            wrong: _wrongCategory == id,
            shake: _shakeCtrl,
            onTap: () {
              if (_state.done) return;
              final result = _state.tap(id);
              if (!_state.done) {
                if (soundForBucket(result) case final tone?) {
                  AppServices.instance.sfx.play(tone);
                }
              }
              switch (result) {
                case BucketResult.right:
                  Haptics.instance.confirm();
                  setState(() {});
                case BucketResult.revealed:
                  Haptics.instance.tick();
                  setState(() {});
                case BucketResult.wrong:
                  Haptics.instance.tick();
                  setState(() => _wrongCategory = id);
                  _shakeCtrl.forward(from: 0).then((_) {
                    if (mounted) setState(() => _wrongCategory = null);
                  });
                case null:
                  break;
              }
              if (_state.done) {
                widget.onAnswer(_state.known, bucketAnswer(_state));
              }
            },
          ),
          if (_state.placedIn(id) case final inside when inside.isNotEmpty)
            Padding(
              padding: const EdgeInsets.only(top: 4, left: 12, right: 12),
              child: SizedBox(
                width: double.infinity,
                child: Text(
                  inside.map((e) => e.text).join(' · '),
                  style: theme.bodySmall?.copyWith(color: AppColors.muted),
                ),
              ),
            ),
        ],
      ],
    );
  }
}

/// Satz bauen: Antwortzeile oben, gemischte Bausteine unten.
class BuildTask extends StatefulWidget {
  final TaskBuild task;
  final bool revealed;
  final Highlight highlight;
  final void Function(bool correct, Object? payload) onAnswer;

  const BuildTask({
    super.key,
    required this.task,
    required this.revealed,
    required this.highlight,
    required this.onAnswer,
  });

  @override
  State<BuildTask> createState() => _BuildTaskState();
}

class _BuildTaskState extends State<BuildTask>
    with SingleTickerProviderStateMixin {
  late final BuildState _state = BuildState.of(widget.task);
  late final AnimationController _shakeCtrl = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 300),
  );

  @override
  void didUpdateWidget(covariant BuildTask oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.revealed && !oldWidget.revealed) {
      setState(() => _state.revealAll());
    }
  }

  @override
  void dispose() {
    _shakeCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final q = widget.task.item.question.trim().isEmpty
        ? 'Baue die Antwort'
        : widget.task.item.question;
    final good = _state.done && _state.known;

    return Column(
      children: [
        QuestionText(q, highlight: widget.highlight),
        const SizedBox(height: 14),
        AnimatedBuilder(
          animation: _shakeCtrl,
          builder: (_, child) {
            final dx = math.sin(_shakeCtrl.value * math.pi * 4) *
                10.0 *
                (1.0 - _shakeCtrl.value);
            return Transform.translate(offset: Offset(dx, 0), child: child);
          },
          child: Container(
            width: double.infinity,
            constraints: const BoxConstraints(minHeight: 72),
            padding: const EdgeInsets.all(10),
            decoration: BoxDecoration(
              color: Colors.black,
              borderRadius: BorderRadius.circular(24),
              border: Border.all(
                color: good ? AppColors.accent : AppColors.outline,
                width: good ? 2 : 1,
              ),
            ),
            child: Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                for (var pos = 0; pos < _state.row.length; pos++)
                  _BuildChip(
                    text: _state.textOf(_state.row[pos]),
                    bg: good ? AppColors.accent : AppColors.pressed,
                    fg: good ? AppColors.onAccent : Colors.white,
                    enabled: !_state.done,
                    onTap: () {
                      Haptics.instance.tick();
                      setState(() => _state.tapRow(pos));
                    },
                  ),
              ],
            ),
          ),
        ),
        const SizedBox(height: 14),
        SizedBox(
          width: double.infinity,
          child: Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              for (final (id, text) in _state.shuffled)
                Builder(builder: (_) {
                  final used = _state.inRow(id);
                  return _BuildChip(
                    text: text,
                    bg: used ? AppColors.card : AppColors.cardHigh,
                    fg: used ? AppColors.muted : Colors.white,
                    enabled: !used && !_state.done,
                    onTap: () {
                      Haptics.instance.tick();
                      setState(() => _state.tapChip(id));
                    },
                  );
                }),
            ],
          ),
        ),
        const SizedBox(height: 14),
        PrimaryButton(
          'Prüfen',
          enabled: _state.complete && !_state.done,
          onPressed: () {
            final result = _state.check();
            if (result == null) return;
            switch (result) {
              case BuildResult.right:
                Haptics.instance.confirm();
                setState(() {});
              case BuildResult.revealed:
                Haptics.instance.tick();
                setState(() {});
              case BuildResult.wrong:
                Haptics.instance.tick();
                setState(() {});
                _shakeCtrl.forward(from: 0);
            }
            if (_state.done) {
              widget.onAnswer(_state.known, buildAnswer(_state));
            }
          },
        ),
      ],
    );
  }
}

class _BuildChip extends StatelessWidget {
  final String text;
  final Color bg;
  final Color fg;
  final bool enabled;
  final VoidCallback onTap;

  const _BuildChip({
    required this.text,
    required this.bg,
    required this.fg,
    required this.enabled,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) => GestureDetector(
        onTap: enabled ? onTap : null,
        child: Container(
          constraints: const BoxConstraints(minHeight: 48),
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
          decoration: BoxDecoration(
            color: bg,
            borderRadius: BorderRadius.circular(24),
            border: Border.all(color: AppColors.outline),
          ),
          child: Text(
            text,
            style: Theme.of(context).textTheme.titleSmall?.copyWith(color: fg),
          ),
        ),
      );
}
