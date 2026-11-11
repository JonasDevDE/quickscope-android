import 'dart:math' as math;
import 'package:flutter/material.dart';
import '../app_view_model.dart';
import '../data/models.dart';
import '../data/network.dart';
import '../logic/exams.dart';
import '../logic/forecast_text.dart';
import 'brand_mark.dart';
import 'components.dart';
import 'haptics.dart';
import 'material_add.dart';
import 'theme.dart';

/// Die Startseite der aktiven Pruefung: EIN Bildschirm, ein grosser Knopf.
class HomePage extends StatelessWidget {
  final UiState state;
  final bool loading;
  final void Function(Exam exam, int minutes) onStart;
  final ValueChanged<Exam> onOpenMore;
  final ValueChanged<Exam> onMore;
  final ValueChanged<Exam> onMaterial;
  final ValueChanged<Exam> onCheatsheet;
  final VoidCallback onRetry;
  final VoidCallback onCancelUpload;
  final VoidCallback onRetryUpload;

  const HomePage({
    super.key,
    required this.state,
    required this.loading,
    required this.onStart,
    required this.onOpenMore,
    required this.onMore,
    required this.onMaterial,
    required this.onCheatsheet,
    required this.onRetry,
    required this.onCancelUpload,
    required this.onRetryUpload,
  });

  @override
  Widget build(BuildContext context) {
    final exam = state.activeExam;

    return SafeArea(
      top: false,
      child: LayoutBuilder(
        builder: (context, constraints) => SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(20, 0, 20, 24),
          child: ConstrainedBox(
            constraints: BoxConstraints(
              minHeight: math.max(0, constraints.maxHeight - 24),
            ),
            child: Center(
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 480),
                child: (loading || exam == null)
                    ? const Column(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          SkeletonBox(
                            width: 220,
                            height: 220,
                            shape: BoxShape.circle,
                          ),
                          SizedBox(height: 16),
                          SkeletonLine(180),
                          SizedBox(height: 16),
                          SkeletonCard(56),
                        ],
                      )
                    : Column(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          UploadCard(
                            state: state,
                            examId: exam.id,
                            onCancel: onCancelUpload,
                            onRetry: onRetryUpload,
                          ),
                          const SizedBox(height: 16),
                          if (isOver(exam, DateTime.now()))
                            _ReviewView(
                              exam: exam,
                              forecast: state.activeForecast,
                            )
                          else if (exam.status == 'processing')
                            const _ProcessingView()
                          else if (exam.status == 'failed')
                            _FailedExamView(
                              exam: exam,
                              onMaterial: onMaterial,
                              onRetry: onRetry,
                            )
                          else if (exam.status == 'empty')
                            _EmptyExamView(
                              exam: exam,
                              onMaterial: onMaterial,
                            )
                          else
                            _ReadyView(
                              exam: exam,
                              forecast: state.activeForecast,
                              onStart: onStart,
                              onOpenMore: onOpenMore,
                              onMore: onMore,
                              onCheatsheet: onCheatsheet,
                            ),
                        ],
                      ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _ProcessingView extends StatelessWidget {
  const _ProcessingView();

  @override
  Widget build(BuildContext context) => Column(
        children: [
          Padding(
            padding: const EdgeInsets.only(top: 24),
            child: Text(
              'Liest Material …',
              textAlign: TextAlign.center,
              style: Theme.of(context).textTheme.titleMedium,
            ),
          ),
          const SizedBox(height: 16),
          const SkeletonCard(140),
          const SizedBox(height: 16),
          const SkeletonCard(70),
          const SizedBox(height: 16),
          const SkeletonCard(70),
        ],
      );
}

class _FailedExamView extends StatelessWidget {
  final Exam exam;
  final ValueChanged<Exam> onMaterial;
  final VoidCallback onRetry;

  const _FailedExamView({
    required this.exam,
    required this.onMaterial,
    required this.onRetry,
  });

  @override
  Widget build(BuildContext context) {
    final err = exam.error?.toLowerCase() ?? '';
    final text = err.contains('limit')
        ? 'Gerade ausgelastet. Später nochmal.'
        : err.contains('nicht eingerichtet')
            ? 'Noch nicht eingerichtet.'
            : exam.error != null
                ? neutralMessage(exam.error!)
                : 'Material nicht lesbar.';

    return Column(
      children: [
        const SizedBox(height: 24),
        const LearnMark(size: 56),
        const SizedBox(height: 16),
        Text(
          'Das hat nicht geklappt',
          textAlign: TextAlign.center,
          style: Theme.of(context).textTheme.headlineSmall,
        ),
        const SizedBox(height: 16),
        Text(
          text,
          textAlign: TextAlign.center,
          style: const TextStyle(color: AppColors.muted),
        ),
        const SizedBox(height: 16),
        PrimaryButton('Material ansehen', onPressed: () => onMaterial(exam)),
        const SizedBox(height: 16),
        SecondaryButton('Später nochmal', onPressed: onRetry),
      ],
    );
  }
}

class _EmptyExamView extends StatelessWidget {
  final Exam exam;
  final ValueChanged<Exam> onMaterial;

  const _EmptyExamView({required this.exam, required this.onMaterial});

  @override
  Widget build(BuildContext context) => Column(
        children: [
          const SizedBox(height: 24),
          const LearnMark(size: 56),
          const SizedBox(height: 16),
          Text(
            'Noch kein Material',
            style: Theme.of(context).textTheme.headlineSmall,
          ),
          const SizedBox(height: 16),
          PrimaryButton(
            'Material hinzufügen',
            onPressed: () => onMaterial(exam),
          ),
        ],
      );
}

class _ReviewView extends StatelessWidget {
  final Exam exam;
  final Forecast? forecast;

  const _ReviewView({required this.exam, required this.forecast});

  @override
  Widget build(BuildContext context) => Column(
        children: [
          const SizedBox(height: 8),
          if (forecast == null)
            const SkeletonBox(width: 220, height: 220, shape: BoxShape.circle)
          else ...[
            ForecastRing(
              forecast!.coveragePercent > 0 ? forecast!.scoreNowPercent : 0,
            ),
            const SizedBox(height: 16),
            Text(
              'So warst du zuletzt vorbereitet (Schätzung)',
              textAlign: TextAlign.center,
              style: Theme.of(context)
                  .textTheme
                  .bodyMedium
                  ?.copyWith(color: AppColors.muted),
            ),
          ],
          const SizedBox(height: 16),
          Text(
            'Prüfung vorbei',
            style: Theme.of(context).textTheme.titleMedium,
          ),
        ],
      );
}

class _ReadyView extends StatelessWidget {
  final Exam exam;
  final Forecast? forecast;
  final void Function(Exam exam, int minutes) onStart;
  final ValueChanged<Exam> onOpenMore;
  final ValueChanged<Exam> onMore;
  final ValueChanged<Exam> onCheatsheet;

  const _ReadyView({
    required this.exam,
    required this.forecast,
    required this.onStart,
    required this.onOpenMore,
    required this.onMore,
    required this.onCheatsheet,
  });

  @override
  Widget build(BuildContext context) {
    final open = isOpen(exam);
    final minutes =
        remainingMinutes(exam.dailyMinutes, forecast?.doneSecondsToday ?? 0);
    final theme = Theme.of(context).textTheme;

    return Column(
      children: [
        if (!open) ...[
          Text(
            relativeDayLabel(
              examStart(exam),
              DateTime.now(),
              startOfSentence: true,
            ),
            textAlign: TextAlign.center,
            style: theme.titleSmall?.copyWith(color: AppColors.muted),
          ),
          const SizedBox(height: 16),
        ],
        if (forecast == null)
          const SkeletonBox(width: 220, height: 220, shape: BoxShape.circle)
        else ...[
          ForecastRing(ringPercentOrZero(exam, forecast)),
          if (open || forecastReliable(exam, forecast!)) ...[
            const SizedBox(height: 16),
            Text(
              open
                  ? 'Wissensstand'
                  : projectionLine(forecast!, exam.dailyMinutes),
              textAlign: TextAlign.center,
              style: theme.titleSmall?.copyWith(color: AppColors.muted),
            ),
          ],
        ],
        const SizedBox(height: 24),
        if (forecast?.showCheatsheetSuggestion == true && !open) ...[
          GestureDetector(
            onTap: () {
              Haptics.instance.tick();
              onCheatsheet(exam);
            },
            behavior: HitTestBehavior.opaque,
            child: ConstrainedBox(
              constraints: const BoxConstraints(
                minHeight: 48,
                minWidth: double.infinity,
              ),
              child: Padding(
                padding: const EdgeInsets.symmetric(vertical: 12),
                child: Text(
                  'Letzte Runde: Merkzettel lesen',
                  textAlign: TextAlign.center,
                  style: theme.labelLarge?.copyWith(color: AppColors.accent),
                ),
              ),
            ),
          ),
          const SizedBox(height: 16),
        ],
        if (forecast != null &&
            dayGoalReached(exam.dailyMinutes, forecast!.doneSecondsToday)) ...[
          ConstrainedBox(
            constraints: const BoxConstraints(
              minHeight: 56,
              minWidth: double.infinity,
            ),
            child: Padding(
              padding: const EdgeInsets.only(top: 14),
              child: Text(
                '✓ Tagesziel geschafft',
                textAlign: TextAlign.center,
                style: theme.titleMedium?.copyWith(color: AppColors.accent),
              ),
            ),
          ),
          const SizedBox(height: 16),
          ConstrainedBox(
            constraints: const BoxConstraints(minHeight: 48),
            child: HapticTextButton(
              onPressed: () => onMore(exam),
              child: Text(
                'Noch eine Runde',
                style: theme.labelLarge?.copyWith(color: AppColors.muted),
              ),
            ),
          ),
        ] else ...[
          PrimaryButton(startLabel, onPressed: () => onStart(exam, minutes)),
          const SizedBox(height: 16),
          ConstrainedBox(
            constraints: const BoxConstraints(minHeight: 48),
            child: HapticTextButton(
              onPressed: () => onOpenMore(exam),
              child: Text(
                'Mehr',
                style: theme.labelLarge?.copyWith(color: AppColors.muted),
              ),
            ),
          ),
        ],
      ],
    );
  }
}

/// Grosser Prognose-Ring: Fuellung = Stand heute (stufenlos), die Zahl daneben in 5er-Schritten.
class ForecastRing extends StatelessWidget {
  final int percent;

  const ForecastRing(this.percent, {super.key});

  @override
  Widget build(BuildContext context) => TweenAnimationBuilder<double>(
        tween: Tween<double>(
          begin: 0,
          end: percent.clamp(0, 100) / 100.0,
        ),
        duration: const Duration(milliseconds: 700),
        builder: (context, sweep, _) => SizedBox(
          width: 220,
          height: 220,
          child: Stack(
            alignment: Alignment.center,
            children: [
              Positioned.fill(
                child: CustomPaint(painter: _RingPainter(sweep)),
              ),
              Text(
                percentText(percent),
                style: Theme.of(context).textTheme.displaySmall?.copyWith(
                      fontSize: 52,
                      height: 56 / 52,
                    ),
              ),
            ],
          ),
        ),
      );
}

class _RingPainter extends CustomPainter {
  final double sweep;
  const _RingPainter(this.sweep);

  @override
  void paint(Canvas canvas, Size size) {
    const w = 16.0;
    const inset = w / 2;
    final rect =
        Rect.fromLTWH(inset, inset, size.width - w, size.height - w);
    final bgPaint = Paint()
      ..color = AppColors.cardHigh
      ..style = PaintingStyle.stroke
      ..strokeWidth = w
      ..strokeCap = StrokeCap.round;
    canvas.drawArc(rect, -math.pi / 2, math.pi * 2, false, bgPaint);

    if (sweep > 0) {
      final fgPaint = Paint()
        ..color = AppColors.accent
        ..style = PaintingStyle.stroke
        ..strokeWidth = w
        ..strokeCap = StrokeCap.round;
      canvas.drawArc(rect, -math.pi / 2, math.pi * 2 * sweep, false, fgPaint);
    }
  }

  @override
  bool shouldRepaint(covariant _RingPainter oldDelegate) =>
      oldDelegate.sweep != sweep;
}
