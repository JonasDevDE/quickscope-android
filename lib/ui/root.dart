import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import '../app_view_model.dart';
import '../logic/exams.dart';
import '../logic/forecast_text.dart';
import '../logic/guard.dart';
import '../logic/listen.dart';
import '../logic/markdown.dart';
import '../logic/profile.dart';
import '../session_view_model.dart';
import 'about_screen.dart';
import 'brand_mark.dart';
import 'components.dart';
import 'exam_settings.dart';
import 'haptics.dart';
import 'home.dart';
import 'legal_screen.dart';
import 'new_exam_flow.dart';
import 'onboarding.dart';
import 'session_screen.dart';
import 'settings_screen.dart';
import 'sheets.dart';
import 'theme.dart';
import 'update_screen.dart';

sealed class _Overlay {
  const _Overlay();
}

class _OverlayMe extends _Overlay {
  const _OverlayMe();
}

class _OverlayAbout extends _Overlay {
  const _OverlayAbout();
}

class _OverlaySettingsMain extends _Overlay {
  const _OverlaySettingsMain();
}

class _OverlayExams extends _Overlay {
  const _OverlayExams();
}

class _OverlayNewExam extends _Overlay {
  const _OverlayNewExam();
}

class _OverlaySettings extends _Overlay {
  final String examId;
  final ExamSetting? start;
  const _OverlaySettings(this.examId, {this.start});
}

class _OverlayCheat extends _Overlay {
  final String examId;
  const _OverlayCheat(this.examId);
}

class _OverlayMore extends _Overlay {
  final String examId;
  const _OverlayMore(this.examId);
}

class QuickscopeRoot extends StatefulWidget {
  final AppViewModel vm;
  final SessionViewModel sessionVm;

  const QuickscopeRoot({
    super.key,
    required this.vm,
    required this.sessionVm,
  });

  @override
  State<QuickscopeRoot> createState() => _QuickscopeRootState();
}

class _QuickscopeRootState extends State<QuickscopeRoot> {
  final _scaffoldMessengerKey = GlobalKey<ScaffoldMessengerState>();
  _Overlay? _overlay;
  LegalPage? _legal;
  StreamSubscription<UiMessage>? _msgSub;
  Object? _lastForecastAfter;

  @override
  void initState() {
    super.initState();
    _msgSub = widget.vm.messages.listen((msg) {
      final messenger = _scaffoldMessengerKey.currentState;
      if (messenger == null) return;
      messenger.hideCurrentSnackBar();
      messenger.showSnackBar(
        SnackBar(
          content: Text(msg.text),
          duration: Duration(seconds: msg.actionLabel != null ? 5 : 3),
          action: msg.actionLabel != null && msg.onAction != null
              ? SnackBarAction(
                  label: msg.actionLabel!,
                  textColor: AppColors.accent,
                  onPressed: msg.onAction!,
                )
              : null,
        ),
      );
    });
    widget.sessionVm.addListener(_onSessionChanged);
  }

  void _onSessionChanged() {
    final fa = widget.sessionVm.state.forecastAfter;
    if (fa != null && !identical(fa, _lastForecastAfter)) {
      _lastForecastAfter = fa;
      widget.vm.applyForecast(widget.sessionVm.state.examId, fa);
    }
  }

  @override
  void dispose() {
    _msgSub?.cancel();
    widget.sessionVm.removeListener(_onSessionChanged);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
        listenable: Listenable.merge([
          widget.vm,
          widget.sessionVm,
          widget.vm.online,
        ]),
        builder: (context, _) {
          final state = widget.vm.state;
          final session = widget.sessionVm.state;
          final forced = state.updateForced;
          final online = widget.vm.online.value;

          if (forced && _overlay != null) {
            WidgetsBinding.instance.addPostFrameCallback((_) {
              if (mounted) setState(() => _overlay = null);
            });
          }

          final ready = state.phase == Phase.ready && !forced;
          final onboarding =
              ready && state.onboarding == OnboardingGate.needed;
          final profileCheckFailed =
              ready && state.onboarding == OnboardingGate.failed;
          final noExams = ready && state.examsLoaded && state.exams.isEmpty;

          void dismiss() => setState(() => _overlay = null);

          return ScaffoldMessenger(
            key: _scaffoldMessengerKey,
            child: Scaffold(
              backgroundColor: AppColors.black,
              body: Stack(
                fit: StackFit.expand,
                children: [
                  Column(
                    children: [
                      if (state.phase == Phase.failed)
                        Expanded(
                          child: _FailedView(
                            message: state.failure,
                            onRetry: widget.vm.bootstrap,
                          ),
                        )
                      else ...[
                        _Header(
                          state: state,
                          vm: widget.vm,
                          onExams: () =>
                              setState(() => _overlay = const _OverlayExams()),
                          onMe: () =>
                              setState(() => _overlay = const _OverlayMe()),
                        ),
                        if (state.serverStarting)
                          Padding(
                            padding: const EdgeInsets.symmetric(
                              horizontal: 20,
                              vertical: 8,
                            ),
                            child: SizedBox(
                              width: double.infinity,
                              child: Text(
                                'Der Server startet gerade neu. Das dauert meist ein bis zwei Minuten.',
                                style: Theme.of(context)
                                    .textTheme
                                    .bodyMedium
                                    ?.copyWith(color: AppColors.muted),
                              ),
                            ),
                          ),
                        Expanded(
                          child: noExams
                              ? NewExamFlow(vm: widget.vm)
                              : HomePage(
                                  state: state,
                                  loading: !ready || !state.examsLoaded,
                                  onStart: (exam, _) => widget.sessionVm.start(
                                    exam.id,
                                    scoreBefore:
                                        state.activeForecast?.scoreOrNull(),
                                  ),
                                  onOpenMore: (exam) => setState(
                                    () => _overlay = _OverlayMore(exam.id),
                                  ),
                                  onMore: (exam) => widget.sessionVm.start(
                                    exam.id,
                                    minutes: extraMinutes,
                                    scoreBefore:
                                        state.activeForecast?.scoreOrNull(),
                                  ),
                                  onMaterial: (exam) {
                                    if (online) {
                                      setState(
                                        () => _overlay = _OverlaySettings(
                                          exam.id,
                                          start: ExamSetting.material,
                                        ),
                                      );
                                    } else {
                                      widget.vm.needInternet();
                                    }
                                  },
                                  onCheatsheet: (exam) => setState(
                                    () => _overlay = _OverlayCheat(exam.id),
                                  ),
                                  onRetry: widget.vm.refreshExamsNow,
                                  onCancelUpload: widget.vm.cancelUpload,
                                  onRetryUpload: widget.vm.retryUpload,
                                ),
                        ),
                      ],
                    ],
                  ),
                  if (onboarding)
                    OnboardingFlow(vm: widget.vm, name: state.me?.name),
                  if (profileCheckFailed)
                    ColoredBox(
                      color: AppColors.black,
                      child: SafeArea(
                        child: Padding(
                          padding: const EdgeInsets.all(32),
                          child: Column(
                            mainAxisAlignment: MainAxisAlignment.center,
                            children: [
                              const LearnMark(size: 56),
                              const SizedBox(height: 16),
                              Text(
                                'Gerade nicht erreichbar',
                                textAlign: TextAlign.center,
                                style:
                                    Theme.of(context).textTheme.headlineSmall,
                              ),
                              const SizedBox(height: 24),
                              PrimaryButton(
                                'Nochmal',
                                onPressed: widget.vm.retryOnboardingCheck,
                              ),
                              const SizedBox(height: 10),
                              SecondaryButton(
                                'Später',
                                onPressed: widget.vm.skipOnboardingCheck,
                              ),
                            ],
                          ),
                        ),
                      ),
                    ),
                  if (!forced)
                    if (state.phase == Phase.needsLogin)
                      _LoginSheet(
                        state: state,
                        vm: widget.vm,
                        online: online,
                        onLegal: (lp) => setState(() => _legal = lp),
                      )
                    else ...[
                      switch (_overlay) {
                        null => const SizedBox.shrink(),
                        _OverlayMe() => MeSheet(
                            state: state,
                            vm: widget.vm,
                            onAbout: () {
                              if (online) {
                                setState(
                                  () => _overlay = const _OverlayAbout(),
                                );
                              } else {
                                widget.vm.needInternet();
                              }
                            },
                            onSettings: () => setState(
                              () => _overlay = const _OverlaySettingsMain(),
                            ),
                            onDismiss: dismiss,
                          ),
                        _OverlaySettingsMain() => SettingsMain(
                            state: state,
                            vm: widget.vm,
                            onLegal: (lp) => setState(() {
                              _overlay = null;
                              _legal = lp;
                            }),
                            onDismiss: dismiss,
                          ),
                        _OverlayAbout() => AboutScreen(
                            state: state,
                            vm: widget.vm,
                            onClose: dismiss,
                          ),
                        _OverlayExams() => ExamsSheet(
                            state: state,
                            vm: widget.vm,
                            onNew: () {
                              if (online) {
                                setState(
                                  () => _overlay = const _OverlayNewExam(),
                                );
                              } else {
                                widget.vm.needInternet();
                              }
                            },
                            onEdit: (id) =>
                                setState(() => _overlay = _OverlaySettings(id)),
                            onDismiss: dismiss,
                          ),
                        _OverlayNewExam() => NewExamFlow(
                            vm: widget.vm,
                            onClose: dismiss,
                          ),
                        _OverlaySettings(:final examId, :final start) =>
                          ExamSettingsSheet(
                            state: state,
                            vm: widget.vm,
                            examId: examId,
                            startAt: start,
                            onDismiss: dismiss,
                          ),
                        _OverlayCheat(:final examId) => CheatsheetSheet(
                            vm: widget.vm,
                            examId: examId,
                            onDismiss: dismiss,
                          ),
                        _OverlayMore(:final examId) => Builder(builder: (_) {
                            final exam = state.exams
                                .where((e) => e.id == examId)
                                .firstOrNull;
                            if (exam == null) return const SizedBox.shrink();
                            final hasLessons = online
                                ? (state.activeForecast?.plan?.chaptersTotal ??
                                        0) >
                                    0
                                : widget.vm.offlineHasLessons(examId);
                            return MoreSheet(
                              canUnderstand: understandVisible(hasLessons),
                              onTest: () => widget.sessionVm.start(
                                exam.id,
                                scoreBefore:
                                    state.activeForecast?.scoreOrNull(),
                                questionsOnly: true,
                              ),
                              onUnderstand: () => widget.sessionVm.start(
                                exam.id,
                                scoreBefore:
                                    state.activeForecast?.scoreOrNull(),
                                lessonsOnly: true,
                              ),
                              onDismiss: dismiss,
                            );
                          }),
                      },
                      if (session.phase != SessionPhase.closed)
                        SessionScreen(
                          vm: widget.sessionVm,
                          onRetry: () {
                            final exam = state.exams
                                .where((e) => e.id == session.examId)
                                .firstOrNull;
                            if (exam != null) {
                              widget.sessionVm.start(
                                exam.id,
                                scoreBefore: session.scoreBefore,
                              );
                            }
                          },
                          onMore: () {
                            final exam = state.exams
                                .where((e) => e.id == session.examId)
                                .firstOrNull;
                            if (exam != null) {
                              widget.sessionVm.start(
                                exam.id,
                                minutes: extraMinutes,
                                scoreBefore: session.scoreBefore,
                              );
                            }
                          },
                          onClose: () {
                            widget.sessionVm.close();
                            widget.vm.refreshExamsNow();
                          },
                        ),
                    ],
                  if (_legal case final lp?)
                    LegalScreen(
                      page: lp,
                      onClose: () => setState(() => _legal = null),
                    ),
                  UpdateScreen(state: state, vm: widget.vm),
                ],
              ),
            ),
          );
        },
      );
}

class _LoginSheet extends StatelessWidget {
  final UiState state;
  final AppViewModel vm;
  final bool online;
  final ValueChanged<LegalPage> onLegal;

  const _LoginSheet({
    required this.state,
    required this.vm,
    required this.online,
    required this.onLegal,
  });

  @override
  Widget build(BuildContext context) {
    final error = state.loginError ??
        (vm.clientIdMissing
            ? 'Google-Client-ID fehlt (GOOGLE_WEB_CLIENT_ID). Die Anmeldung ist erst nach dem nächsten Build mit ID möglich.'
            : null);

    return QuickscopeSheet(
      onDismiss: () {},
      dismissable: false,
      children: [
        const SizedBox(height: 12),
        const LearnMark(size: 56),
        Text(
          'Willkommen bei Quickscope',
          style: Theme.of(context).textTheme.displaySmall,
        ),
        const Text(
          'Melde dich an, um loszulegen.',
          style: TextStyle(color: AppColors.muted),
        ),
        const SizedBox(height: 12),
        if (error != null)
          Text(
            error,
            style: Theme.of(context)
                .textTheme
                .bodyMedium
                ?.copyWith(color: AppColors.danger),
          ),
        if (defaultTargetPlatform == TargetPlatform.iOS)
          PrimaryButton(
            state.loginBusy ? 'Einen Moment' : 'Mit Apple anmelden',
            enabled: !state.loginBusy,
            onPressed: () {
              if (online) {
                vm.signInWithApple();
              } else {
                vm.needInternet();
              }
            },
          ),
        PrimaryButton(
          state.loginBusy ? 'Einen Moment' : 'Mit Google anmelden',
          enabled: !state.loginBusy,
          onPressed: () {
            if (online) {
              vm.signIn();
            } else {
              vm.needInternet();
            }
          },
        ),
        for (final lp in LegalPage.values)
          RowCard(
            onTap: () => onLegal(lp),
            minHeight: 48,
            children: [
              Expanded(
                child: Text(
                  lp.title,
                  style: Theme.of(context).textTheme.labelLarge,
                ),
              ),
            ],
          ),
      ],
    );
  }
}

class _Header extends StatelessWidget {
  final UiState state;
  final AppViewModel vm;
  final VoidCallback onExams;
  final VoidCallback onMe;

  const _Header({
    required this.state,
    required this.vm,
    required this.onExams,
    required this.onMe,
  });

  @override
  Widget build(BuildContext context) {
    final me = state.me;
    return SafeArea(
      bottom: false,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            ExamPill(
              name: state.activeExam?.name ?? 'Neues Thema',
              loading: state.phase != Phase.ready || !state.examsLoaded,
              onTap: onExams,
              icon: state.activeExam?.icon,
            ),
            GestureDetector(
              onTap: () {
                Haptics.instance.tick();
                onMe();
              },
              child: UserAvatar(
                imageUrl: me?.pictureUrl ?? vm.signInPictureUrl,
                name: me?.name ?? me?.email ?? '?',
                size: 44,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _FailedView extends StatelessWidget {
  final String? message;
  final VoidCallback onRetry;

  const _FailedView({required this.message, required this.onRetry});

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const LearnMark(size: 56),
            const SizedBox(height: 16),
            Text(
              'Das hat nicht geklappt',
              textAlign: TextAlign.center,
              style: Theme.of(context).textTheme.headlineSmall,
            ),
            const SizedBox(height: 8),
            Text(
              message ?? 'Unbekannter Fehler',
              textAlign: TextAlign.center,
              style: const TextStyle(color: AppColors.muted),
            ),
            const SizedBox(height: 28),
            PrimaryButton('Erneut versuchen', onPressed: onRetry),
          ],
        ),
      );
}
