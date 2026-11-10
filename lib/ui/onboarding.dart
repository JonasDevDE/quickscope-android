import 'dart:async';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:path_provider/path_provider.dart';
import 'package:record/record.dart';
import '../app_view_model.dart';
import '../data/app_log.dart';
import '../logic/profile.dart';
import 'brand_mark.dart';
import 'components.dart';
import 'haptics.dart';
import 'theme.dart';

enum _VoiceStep { idle, recording, uploading, review }

/// Onboarding beim ersten Start (nur bei onboardingDone == false): Begruessung, Alter, Bildung, Sprachnachricht mit
/// Zusammenfassung zum Pruefen. Eine Sache pro Seite, grosse Antworten; "Fertig" speichert alles in einem PUT.
class OnboardingFlow extends StatefulWidget {
  final AppViewModel vm;
  final String? name;

  const OnboardingFlow({super.key, required this.vm, this.name});

  @override
  State<OnboardingFlow> createState() => _OnboardingFlowState();
}

class _OnboardingFlowState extends State<OnboardingFlow> {
  OnboardingPage _page = OnboardingPage.welcome;
  int _age = ageDefault;
  String? _education;
  String _about = '';
  _VoiceStep _step = _VoiceStep.idle;
  bool _busy = false;

  @override
  Widget build(BuildContext context) {
    final prev = previousPage(_page);
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop &&
            prev != null &&
            _step == _VoiceStep.idle) {
          setState(() => _page = prev);
        }
      },
      child: ColoredBox(
        color: AppColors.black,
        child: SafeArea(
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 24),
            child: Column(
              children: [
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 16),
                  child: Row(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      for (final p in OnboardingPage.values)
                        Container(
                          margin: const EdgeInsets.symmetric(horizontal: 4),
                          width: 8,
                          height: 8,
                          decoration: BoxDecoration(
                            color: p == _page
                                ? AppColors.accent
                                : AppColors.cardHigh,
                            shape: BoxShape.circle,
                          ),
                        ),
                    ],
                  ),
                ),
                Expanded(
                  child: SingleChildScrollView(
                    padding: const EdgeInsets.only(bottom: 24),
                    child: Column(
                      children: switch (_page) {
                        OnboardingPage.welcome => [
                            const SizedBox(height: 48),
                            const LearnMark(size: 72),
                            const SizedBox(height: 16),
                            Text(
                              greeting(widget.name),
                              textAlign: TextAlign.center,
                              style: Theme.of(context).textTheme.displaySmall,
                            ),
                            const SizedBox(height: 16),
                            Text(
                              'Damit Erklärungen zu dir passen.',
                              textAlign: TextAlign.center,
                              style: Theme.of(context)
                                  .textTheme
                                  .titleMedium
                                  ?.copyWith(color: AppColors.muted),
                            ),
                            const SizedBox(height: 40),
                            PrimaryButton(
                              'Los',
                              onPressed: () {
                                if (nextPage(_page) case final next?) {
                                  setState(() => _page = next);
                                }
                              },
                            ),
                          ],
                        OnboardingPage.age => [
                            const _Title('Wie alt bist du?'),
                            const SizedBox(height: 40),
                            Row(
                              mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                              children: [
                                _RoundButton(
                                  '–',
                                  () => setState(
                                    () => _age = clampAge(_age - 1),
                                  ),
                                ),
                                Text(
                                  '$_age',
                                  style: Theme.of(context)
                                      .textTheme
                                      .displaySmall
                                      ?.copyWith(
                                        fontSize: 64,
                                        color: AppColors.accent,
                                      ),
                                ),
                                _RoundButton(
                                  '+',
                                  () => setState(
                                    () => _age = clampAge(_age + 1),
                                  ),
                                ),
                              ],
                            ),
                            const SizedBox(height: 40),
                            PrimaryButton(
                              'Weiter',
                              onPressed: () {
                                if (nextPage(_page) case final next?) {
                                  setState(() => _page = next);
                                }
                              },
                            ),
                          ],
                        OnboardingPage.education => [
                            const _Title('Deine Bildung?'),
                            for (final (key, label) in education) ...[
                              const SizedBox(height: 16),
                              if (key == _education)
                                PrimaryButton(
                                  label,
                                  onPressed: () => setState(() {
                                    _education = key;
                                    if (nextPage(_page) case final next?) {
                                      _page = next;
                                    }
                                  }),
                                )
                              else
                                SecondaryButton(
                                  label,
                                  onPressed: () => setState(() {
                                    _education = key;
                                    if (nextPage(_page) case final next?) {
                                      _page = next;
                                    }
                                  }),
                                ),
                            ],
                          ],
                        OnboardingPage.voice => [
                            _VoicePage(
                              vm: widget.vm,
                              step: _step,
                              about: _about,
                              busy: _busy,
                              onStep: (s) => setState(() => _step = s),
                              onAbout: (a) =>
                                  setState(() => _about = clampAbout(a)),
                              onDone: () {
                                setState(() => _busy = true);
                                widget.vm.finishOnboarding(
                                  _age,
                                  _education,
                                  _about,
                                  onFailure: () {
                                    if (mounted) setState(() => _busy = false);
                                  },
                                );
                              },
                            ),
                          ],
                      },
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _Title extends StatelessWidget {
  final String text;
  const _Title(this.text);

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(top: 16),
        child: Text(
          text,
          textAlign: TextAlign.center,
          style: Theme.of(context).textTheme.headlineMedium,
        ),
      );
}

class _RoundButton extends StatelessWidget {
  final String label;
  final VoidCallback onTap;

  const _RoundButton(this.label, this.onTap);

  @override
  Widget build(BuildContext context) => GestureDetector(
        onTap: () {
          Haptics.instance.tick();
          onTap();
        },
        child: Container(
          width: 72,
          height: 72,
          decoration: const BoxDecoration(
            color: AppColors.cardHigh,
            shape: BoxShape.circle,
          ),
          alignment: Alignment.center,
          child: Text(label, style: Theme.of(context).textTheme.displaySmall),
        ),
      );
}

class _VoicePage extends StatefulWidget {
  final AppViewModel vm;
  final _VoiceStep step;
  final String about;
  final bool busy;
  final ValueChanged<_VoiceStep> onStep;
  final ValueChanged<String> onAbout;
  final VoidCallback onDone;

  const _VoicePage({
    required this.vm,
    required this.step,
    required this.about,
    required this.busy,
    required this.onStep,
    required this.onAbout,
    required this.onDone,
  });

  @override
  State<_VoicePage> createState() => _VoicePageState();
}

class _VoicePageState extends State<_VoicePage> {
  final _recorder = AudioRecorder();
  int _seconds = 0;
  int _startedAtMs = 0;
  String? _filePath;
  String? _hint;
  Timer? _ticker;

  Future<void> _startRecording() async {
    try {
      if (!await _recorder.hasPermission()) return;
      final temp = await getTemporaryDirectory();
      final path =
          '${temp.path}/voice_${DateTime.now().millisecondsSinceEpoch}.$voiceFileExt';
      await _recorder.start(
        const RecordConfig(
          encoder: AudioEncoder.aacLc,
          sampleRate: 44100,
          bitRate: 64000,
        ),
        path: path,
      );
      _filePath = path;
      _startedAtMs = DateTime.now().millisecondsSinceEpoch;
      setState(() => _seconds = 0);
      widget.onStep(_VoiceStep.recording);
      _ticker?.cancel();
      _ticker = Timer.periodic(const Duration(milliseconds: 250), (_) {
        final elapsed =
            ((DateTime.now().millisecondsSinceEpoch - _startedAtMs) ~/ 1000)
                .clamp(0, recordMaxSeconds);
        if (mounted) setState(() => _seconds = elapsed);
        if (elapsed >= recordMaxSeconds) _finishRecording();
      });
    } catch (e) {
      AppLog.w('VoiceRecorder', 'Aufnahme nicht gestartet', e);
    }
  }

  Future<void> _finishRecording() async {
    _ticker?.cancel();
    String? path;
    try {
      path = await _recorder.stop();
    } catch (_) {}
    final targetPath = path ?? _filePath;
    _filePath = null;
    if (targetPath == null || !File(targetPath).existsSync()) {
      widget.onStep(_VoiceStep.idle);
      return;
    }
    widget.onStep(_VoiceStep.uploading);
    widget.vm.uploadVoice(File(targetPath), (outcome) {
      if (!mounted) return;
      setState(() => _hint = voiceOutcomeMessage(outcome));
      switch (outcome) {
        case VoiceText(:final about):
          widget.onAbout(about);
          widget.onStep(_VoiceStep.review);
        case VoiceUnavailable():
          widget.onStep(_VoiceStep.review);
        default:
          widget.onStep(_VoiceStep.idle);
      }
    });
  }

  @override
  void dispose() {
    _ticker?.cancel();
    unawaited(_recorder.dispose());
    if (_filePath case final p?) {
      try {
        File(p).deleteSync();
      } catch (_) {}
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Column(
        children: [
          const _Title('Wer bist du?'),
          const SizedBox(height: 16),
          if (_hint != null) ...[
            Text(
              _hint!,
              textAlign: TextAlign.center,
              style: const TextStyle(color: AppColors.muted),
            ),
            const SizedBox(height: 16),
          ],
          switch (widget.step) {
            _VoiceStep.idle => Column(
                children: [
                  Text(
                    'Erzähl kurz, wer du bist: Hobbys, Beruf, was dir wichtig ist.',
                    textAlign: TextAlign.center,
                    style: Theme.of(context)
                        .textTheme
                        .titleMedium
                        ?.copyWith(color: AppColors.muted),
                  ),
                  const SizedBox(height: 32),
                  PrimaryButton('Aufnehmen', onPressed: _startRecording),
                  const SizedBox(height: 16),
                  SecondaryButton(
                    'Überspringen',
                    onPressed: () => widget.onStep(_VoiceStep.review),
                  ),
                ],
              ),
            _VoiceStep.recording => Column(
                children: [
                  const SizedBox(height: 24),
                  Text(
                    recordTime(_seconds),
                    textAlign: TextAlign.center,
                    style: Theme.of(context)
                        .textTheme
                        .displaySmall
                        ?.copyWith(color: AppColors.accent),
                  ),
                  const SizedBox(height: 8),
                  Text(
                    'von ${recordTime(recordMaxSeconds)}',
                    textAlign: TextAlign.center,
                    style: const TextStyle(color: AppColors.muted),
                  ),
                  const SizedBox(height: 32),
                  PrimaryButton('Stopp', onPressed: _finishRecording),
                ],
              ),
            _VoiceStep.uploading => const Column(
                children: [
                  Text(
                    'Einen Moment …',
                    textAlign: TextAlign.center,
                    style: TextStyle(color: AppColors.muted),
                  ),
                  SizedBox(height: 16),
                  SkeletonCard(120),
                ],
              ),
            _VoiceStep.review => Column(
                children: [
                  const Text(
                    'Stimmt das? Du kannst es ändern.',
                    textAlign: TextAlign.center,
                    style: TextStyle(color: AppColors.muted),
                  ),
                  const SizedBox(height: 16),
                  QuickscopeField(
                    value: widget.about,
                    onChanged: widget.onAbout,
                    label:
                        'Ich bin Snowboarder, arbeite im Marketing und mag Autos.',
                    singleLine: false,
                    minHeight: 160,
                  ),
                  const SizedBox(height: 8),
                  Align(
                    alignment: Alignment.centerRight,
                    child: Text(
                      aboutCounter(widget.about),
                      style: Theme.of(context)
                          .textTheme
                          .labelMedium
                          ?.copyWith(color: AppColors.muted),
                    ),
                  ),
                  const SizedBox(height: 16),
                  PrimaryButton(
                    widget.busy ? 'Einen Moment' : 'Fertig',
                    enabled: !widget.busy,
                    onPressed: widget.onDone,
                  ),
                ],
              ),
          },
        ],
      );
}
