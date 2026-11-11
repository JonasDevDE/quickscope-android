import 'package:flutter/material.dart' hide CloseButton;
import '../app_services.dart';
import '../audio/audiobook_view_model.dart';
import '../logic/audiobook.dart';
import '../logic/forecast_text.dart';
import 'brand_mark.dart';
import 'components.dart';
import 'fx.dart';
import 'theme.dart';

/// Hoerbuch im Vollbild, ein Zweck: Kapitel klein, Lektion gross, die Stichworte werden der Reihe nach hervorgehoben.
class AudiobookScreen extends StatefulWidget {
  final String examId;
  final VoidCallback onQuestions;
  final VoidCallback onClose;

  const AudiobookScreen({
    super.key,
    required this.examId,
    required this.onQuestions,
    required this.onClose,
  });

  @override
  State<AudiobookScreen> createState() => _AudiobookScreenState();
}

class _AudiobookScreenState extends State<AudiobookScreen> {
  late final AudiobookViewModel _vm =
      AudiobookViewModel(AppServices.instance);

  @override
  void initState() {
    super.initState();
    _vm.start(widget.examId, null);
  }

  void _close() {
    _vm.close();
    widget.onClose();
  }

  @override
  void dispose() {
    _vm.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
        listenable: _vm,
        builder: (context, _) {
          final s = _vm.state;
          if (s.phase == BookPhase.error) {
            WidgetsBinding.instance.addPostFrameCallback((_) => _close());
          }
          return PopScope(
            canPop: false,
            onPopInvokedWithResult: (didPop, _) {
              if (!didPop) _close();
            },
            child: ColoredBox(
              color: AppColors.black,
              child: SafeArea(
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 24),
                  child: Column(
                    children: [
                      Padding(
                        padding: const EdgeInsets.symmetric(vertical: 12),
                        child: Row(
                          children: [
                            CloseButton(onPressed: _close),
                            Expanded(
                              child: Text(
                                s.chapterLabel,
                                textAlign: TextAlign.end,
                                style: Theme.of(context)
                                    .textTheme
                                    .labelLarge
                                    ?.copyWith(color: AppColors.muted),
                              ),
                            ),
                          ],
                        ),
                      ),
                      Expanded(
                        child: switch (s.phase) {
                          BookPhase.playing => _PlayingView(s: s, vm: _vm),
                          BookPhase.finished => _EndView(
                              text: 'Fertig. Jetzt testen?',
                              primary: 'Zum Test',
                              onPrimary: () {
                                _vm.close();
                                widget.onQuestions();
                              },
                              secondary: 'Schließen',
                              onSecondary: _close,
                            ),
                          BookPhase.empty => _EndView(
                              text: s.message ?? 'Nichts Neues zu hören.',
                              primary: 'Mehr zuhören',
                              onPrimary: () => _vm.start(
                                widget.examId,
                                extraMinutes,
                                repeat: true,
                              ),
                              secondary: 'Schließen',
                              onSecondary: _close,
                            ),
                          _ => const Column(
                              children: [
                                SkeletonCard(70),
                                SizedBox(height: 12),
                                SkeletonCard(110),
                              ],
                            ),
                        },
                      ),
                    ],
                  ),
                ),
              ),
            ),
          );
        },
      );
}

class _PlayingView extends StatelessWidget {
  final BookUi s;
  final AudiobookViewModel vm;

  const _PlayingView({required this.s, required this.vm});

  @override
  Widget build(BuildContext context) {
    final active =
        activeKeyPoint(s.keyPoints.length, s.positionMs, s.durationMs);
    final theme = Theme.of(context).textTheme;
    final progress = s.count > 0
        ? (s.index +
                (s.durationMs > 0
                    ? (s.positionMs / s.durationMs).clamp(0.0, 1.0)
                    : 0.0)) /
            s.count
        : 0.0;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Expanded(
          child: SingleChildScrollView(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const SizedBox(height: 16),
                Text(
                  s.chapterTitle,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: theme.labelLarge?.copyWith(color: AppColors.muted),
                ),
                const SizedBox(height: 14),
                Text(
                  s.title,
                  style:
                      theme.displaySmall?.copyWith(fontWeight: FontWeight.bold),
                ),
                for (var i = 0; i < s.keyPoints.length; i++) ...[
                  const SizedBox(height: 14),
                  Text(
                    '• ${s.keyPoints[i]}',
                    style: theme.titleMedium?.copyWith(
                      color: i == active
                          ? AppColors.accent
                          : Colors.white.withValues(alpha: 0.7),
                    ),
                  ),
                ],
                const SizedBox(height: 14),
                SizedBox(
                  width: double.infinity,
                  height: 24,
                  child: Text(
                    showMnemonic(s.positionMs, s.durationMs)
                        ? (s.mnemonic ?? '')
                        : '',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: theme.bodyMedium?.copyWith(color: AppColors.accent),
                  ),
                ),
              ],
            ),
          ),
        ),
        GlowProgress(progress),
        Padding(
          padding: const EdgeInsets.only(top: 6, bottom: 12),
          child: Text(
            'Lektion ${s.index + 1} von ${s.count}',
            style: theme.bodySmall?.copyWith(color: AppColors.muted),
          ),
        ),
        Padding(
          padding: const EdgeInsets.only(bottom: 16),
          child: Row(
            children: [
              Expanded(
                flex: 10,
                child: SecondaryButton('Zurück', onPressed: vm.previous),
              ),
              const SizedBox(width: 10),
              Expanded(
                flex: 13,
                child: PrimaryButton(
                  s.playing ? 'Pause' : 'Abspielen',
                  onPressed: vm.playPause,
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                flex: 10,
                child: SecondaryButton('Weiter', onPressed: vm.next),
              ),
            ],
          ),
        ),
      ],
    );
  }
}

class _EndView extends StatelessWidget {
  final String text;
  final String primary;
  final VoidCallback onPrimary;
  final String secondary;
  final VoidCallback onSecondary;

  const _EndView({
    required this.text,
    required this.primary,
    required this.onPrimary,
    required this.secondary,
    required this.onSecondary,
  });

  @override
  Widget build(BuildContext context) => Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          const LearnMark(size: 56),
          const SizedBox(height: 16),
          Text(
            text,
            textAlign: TextAlign.center,
            style: Theme.of(context).textTheme.headlineSmall,
          ),
          const SizedBox(height: 28),
          PrimaryButton(primary, onPressed: onPrimary),
          const SizedBox(height: 10),
          SecondaryButton(secondary, onPressed: onSecondary),
        ],
      );
}
