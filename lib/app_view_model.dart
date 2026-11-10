import 'dart:async';
import 'dart:io';
import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;
import 'package:http_parser/http_parser.dart';
import 'package:path_provider/path_provider.dart';
import 'package:share_plus/share_plus.dart';
import 'app_services.dart';
import 'config.dart';
import 'data/app_log.dart';
import 'data/auth.dart';
import 'data/live_sync.dart';
import 'data/models.dart';
import 'data/network.dart';
import 'data/updater.dart';
import 'data/uploads.dart';
import 'logic/exams.dart';
import 'logic/export.dart';
import 'logic/feedback.dart';
import 'logic/offline.dart';
import 'logic/profile.dart';
import 'logic/upload_queue.dart';
import 'ui/haptics.dart';

enum Phase { loading, needsLogin, ready, failed }

class UiMessage {
  final String text;
  final String? actionLabel;
  final VoidCallback? onAction;

  const UiMessage(this.text, {this.actionLabel, this.onAction});
}

class UploadState {
  final String examId;
  final double? progress;
  final String? error;
  final bool done;
  final int current;
  final int total;
  final List<UploadItem> failedItems;

  const UploadState(
    this.examId, {
    this.progress,
    this.error,
    this.done = false,
    this.current = 0,
    this.total = 0,
    this.failedItems = const [],
  });
}

class UiState {
  final Phase phase;
  final bool loginBusy;
  final String? loginError;
  final String? failure;
  final Me? me;
  final bool serverStarting;
  final UpdateStatus update;
  final bool canInstallUpdates;
  final bool updateForced;
  final List<Exam> exams;
  final bool examsLoaded;
  final String? chosenExamId;
  final Forecast? forecast;
  final String? forecastExamId;
  final List<MaterialItem> materials;
  final String? materialsExamId;
  final UploadState? upload;
  final OnboardingGate onboarding;
  final bool offline;

  const UiState({
    this.phase = Phase.loading,
    this.loginBusy = false,
    this.loginError,
    this.failure,
    this.me,
    this.serverStarting = false,
    this.update = const UpdateIdle(),
    this.canInstallUpdates = true,
    this.updateForced = false,
    this.exams = const [],
    this.examsLoaded = false,
    this.chosenExamId,
    this.forecast,
    this.forecastExamId,
    this.materials = const [],
    this.materialsExamId,
    this.upload,
    this.onboarding = OnboardingGate.unknown,
    this.offline = false,
  });

  Exam? get activeExam => pickActiveExam(exams, chosenExamId, DateTime.now());
  Forecast? get activeForecast =>
      forecastExamId == activeExam?.id ? forecast : null;

  UiState copyWith({
    Phase? phase,
    bool? loginBusy,
    String? loginError,
    bool clearLoginError = false,
    String? failure,
    bool clearFailure = false,
    Me? me,
    bool? serverStarting,
    UpdateStatus? update,
    bool? canInstallUpdates,
    bool? updateForced,
    List<Exam>? exams,
    bool? examsLoaded,
    String? chosenExamId,
    bool clearChosenExamId = false,
    Forecast? forecast,
    bool clearForecast = false,
    String? forecastExamId,
    bool clearForecastExamId = false,
    List<MaterialItem>? materials,
    String? materialsExamId,
    UploadState? upload,
    bool clearUpload = false,
    OnboardingGate? onboarding,
    bool? offline,
  }) =>
      UiState(
        phase: phase ?? this.phase,
        loginBusy: loginBusy ?? this.loginBusy,
        loginError: clearLoginError ? null : (loginError ?? this.loginError),
        failure: clearFailure ? null : (failure ?? this.failure),
        me: me ?? this.me,
        serverStarting: serverStarting ?? this.serverStarting,
        update: update ?? this.update,
        canInstallUpdates: canInstallUpdates ?? this.canInstallUpdates,
        updateForced: updateForced ?? this.updateForced,
        exams: exams ?? this.exams,
        examsLoaded: examsLoaded ?? this.examsLoaded,
        chosenExamId:
            clearChosenExamId ? null : (chosenExamId ?? this.chosenExamId),
        forecast: clearForecast ? null : (forecast ?? this.forecast),
        forecastExamId: clearForecastExamId
            ? null
            : (forecastExamId ?? this.forecastExamId),
        materials: materials ?? this.materials,
        materialsExamId: materialsExamId ?? this.materialsExamId,
        upload: clearUpload ? null : (upload ?? this.upload),
        onboarding: onboarding ?? this.onboarding,
        offline: offline ?? this.offline,
      );
}

class _UploadUnit {
  final String examId;
  final UploadJob job;
  UploadItem item;
  _UploadUnit(this.examId, this.job, this.item);
}

class AppViewModel extends ChangeNotifier {
  static const int _absoluteMaxBytes = 100 * 1024 * 1024;
  static const int _retryDelayMs = 5000;
  static const int _updatePollMs = 2 * 60 * 1000;

  final AppServices _app;

  UiState _state = const UiState();
  UiState get state => _state;

  final _messages = StreamController<UiMessage>.broadcast();
  Stream<UiMessage> get messages => _messages.stream;

  int _loadGeneration = 0;
  int _quietGeneration = 0;
  bool _updateChecked = false;
  bool _foreground = true;
  bool _hasBeenBackground = false;
  int _lastFullRefresh = 0;
  Future<void>? _writeLock;
  Timer? _updatePollTimer;
  StreamSubscription<void>? _sessionExpiredSub;

  late final LiveSync _live = LiveSync(
    network: _app.network,
    onChange: (c) => refreshAllQuiet(force: c.kind == 'exam'),
    onConnected: () => refreshAllQuiet(),
    onPoll: () => refreshAllQuiet(),
    onForbidden: () => refreshAllQuiet(),
    onUpdate: (u) {
      if (u.versionCode > AppConfig.versionCode) {
        _app.updater.markForced();
      }
    },
  );

  final List<_UploadUnit> _units = [];
  bool _uploadRunning = false;
  bool _uploadCancelled = false;

  AppViewModel(this._app) {
    _updateState(_state.copyWith(canInstallUpdates: _app.updater.canInstall()));
    _app.updater.status.addListener(_onUpdaterChanged);
    _app.updater.forced.addListener(_onUpdaterChanged);
    _app.connectivity.online.addListener(_onConnectivityChanged);
    _sessionExpiredSub = _app.auth.sessionExpired.listen((_) {
      _buzz(HapticKind.reject);
      _updateState(_state.copyWith(phase: Phase.needsLogin, loginBusy: false));
      _syncLiveState();
    });
    bootstrap();
    _startLiveSync();
  }

  ValueListenable<bool> get online => _app.connectivity.online;

  bool offlineHasLessons(String examId) =>
      _app.local.pack(examId)?.entries.any((e) => e.lesson != null) ?? false;

  void needInternet() {
    _buzz(HapticKind.reject);
    _say(needsInternet);
  }

  int? offlineDaysCount(String examId) => _app.local.packDays(examId);

  void _updateState(UiState next) {
    _state = next;
    notifyListeners();
  }

  void _onUpdaterChanged() {
    _updateState(
      _state.copyWith(
        update: _app.updater.status.value,
        updateForced: _app.updater.forced.value,
      ),
    );
  }

  bool _wasOnline = true;
  void _onConnectivityChanged() {
    final now = _app.connectivity.isOnline();
    if (now && !_wasOnline) _onBackOnline();
    _wasOnline = now;
    notifyListeners();
  }

  void onAppLifecycleState(bool inForeground) {
    _foreground = inForeground;
    _app.auth.inForeground = inForeground;
    if (inForeground) {
      if (_hasBeenBackground && _state.phase == Phase.ready) {
        refreshAllQuiet();
        _app.updater.checkInBackground();
      }
    } else {
      _hasBeenBackground = true;
      _app.updater.installIfReady();
    }
    _syncLiveState();
  }

  void _startLiveSync() {
    _syncLiveState();
    _updatePollTimer = Timer.periodic(
      const Duration(milliseconds: _updatePollMs),
      (_) {
        if (_foreground && _state.phase == Phase.ready) {
          _app.updater.checkInBackground(ignoreThrottle: true);
        }
      },
    );
  }

  void _syncLiveState() {
    if (_state.phase == Phase.ready && _foreground) {
      _live.start();
    } else {
      _live.stop();
    }
  }

  @override
  void dispose() {
    _live.stop();
    _updatePollTimer?.cancel();
    _sessionExpiredSub?.cancel();
    _app.updater.status.removeListener(_onUpdaterChanged);
    _app.updater.forced.removeListener(_onUpdaterChanged);
    _app.connectivity.online.removeListener(_onConnectivityChanged);
    _messages.close();
    super.dispose();
  }

  void refreshAllQuiet({bool force = false}) {
    final now = DateTime.now().millisecondsSinceEpoch;
    if (_state.phase != Phase.ready ||
        (!force && now - _lastFullRefresh < 1500)) {
      return;
    }
    _lastFullRefresh = now;
    final gen = ++_quietGeneration;
    unawaited(() async {
      try {
        final me = await _app.api.me();
        if (gen != _quietGeneration) return;
        _updateState(_state.copyWith(me: me));
        await _loadExams();
      } catch (_) {
        AppLog.w('Quickscope', 'Stilles Neuladen fehlgeschlagen');
      }
    }());
  }

  void _onBackOnline() {
    if (_state.offline) {
      bootstrap();
      return;
    }
    if (_state.phase != Phase.ready) return;
    unawaited(() async {
      for (final e in _state.exams) {
        await _app.attemptSync.flush(e.id);
      }
      try {
        await _app.offlineSync.syncAll();
      } catch (_) {}
    }());
  }

  bool _tryOffline() {
    final exams = _app.local.exams();
    if (exams == null) return false;
    final chosen = _app.store.chosenExamId;
    final active = pickActiveExam(exams, chosen, DateTime.now());
    _updateState(
      _state.copyWith(
        phase: Phase.ready,
        me: _app.local.me(),
        clearFailure: true,
        serverStarting: false,
        exams: exams,
        examsLoaded: true,
        chosenExamId: chosen,
        onboarding: OnboardingGate.done,
        offline: true,
        forecast: active != null ? _app.local.forecast(active.id) : null,
        forecastExamId: active?.id,
      ),
    );
    _syncLiveState();
    return true;
  }

  void _buzz(HapticKind kind) => Haptics.instance.perform(kind);

  void _say(String text, {String? actionLabel, VoidCallback? onAction}) {
    _messages.add(UiMessage(text, actionLabel: actionLabel, onAction: onAction));
  }

  void checkUpdatesManually() => _app.updater.checkManually();
  void installUpdate() => _app.updater.installNow();
  void retryUpdate() => _app.updater.retry();

  void bootstrap() {
    final gen = ++_loadGeneration;
    unawaited(() async {
      _updateState(_state.copyWith(phase: Phase.loading, clearFailure: true));
      _syncLiveState();
      if (!_app.connectivity.isOnline() && _tryOffline()) return;
      final hasToken = await _app.auth.ensureToken();
      if (gen != _loadGeneration) return;
      if (hasToken) {
        await _loadSession(gen);
      } else {
        _updateState(_state.copyWith(phase: Phase.needsLogin));
        _syncLiveState();
      }
    }());
  }

  void signIn() => _performSignIn(() => _app.auth.signIn());

  void signInWithApple() => _performSignIn(() => _app.auth.signInWithApple());

  void _performSignIn(Future<SignInResult> Function() action) {
    final gen = ++_loadGeneration;
    unawaited(() async {
      _updateState(_state.copyWith(loginBusy: true, clearLoginError: true));
      final result = await action();
      if (gen != _loadGeneration) return;
      switch (result) {
        case SignInSuccess():
          _buzz(HapticKind.confirm);
          _updateState(
            _state.copyWith(loginBusy: false, phase: Phase.loading),
          );
          await _loadSession(gen);
        case SignInCancelled():
          _updateState(_state.copyWith(loginBusy: false));
        case SignInFailure(:final message):
          _buzz(HapticKind.reject);
          _updateState(
            _state.copyWith(loginBusy: false, loginError: message),
          );
      }
    }());
  }

  void signOut() {
    ++_loadGeneration;
    unawaited(() async {
      try {
        await _app.api.logout().timeout(const Duration(seconds: 4));
      } catch (_) {}
      await _app.auth.signOut();
      await _app.wipeLocalData();
      _updateState(const UiState(phase: Phase.needsLogin));
      _syncLiveState();
    }());
  }

  /// Datenexport: laedt den Export-Stream und teilt/speichert die Datei ueber den nativen System-Sheet.
  void exportData() {
    unawaited(() async {
      int? bytes;
      File? outFile;
      try {
        final stream = await _app.api.exportData();
        final temp = await getTemporaryDirectory();
        final dir = Directory('${temp.path}/exports')..createSync(recursive: true);
        outFile = File('${dir.path}/quickscope-daten.json');
        final sink = outFile.openWrite();
        try {
          bytes = await copyExport(stream, sink);
        } finally {
          await sink.close();
        }
      } catch (e) {
        AppLog.w('Export', exportFailureReason(e), e);
      }
      switch (exportOutcome(bytes)) {
        case ExportOutcome.saved:
          if (outFile != null) {
            await Share.shareXFiles([XFile(outFile.path)]);
          }
        case ExportOutcome.failed:
          _say('Gerade nicht verfügbar.');
      }
    }());
  }

  void setAudioEnabled(bool on, {VoidCallback? onFailure}) {
    _app.store.audioEnabled = on;
    if (!on) _app.voice.stop();
    _app.store.audioDirty = true;
    notifyListeners();
    if (!_app.connectivity.isOnline()) return;
    _run('Speichern fehlgeschlagen', onFailure: onFailure, () async {
      await _app.api.putProfile(ProfileUpdate(audioEnabled: on));
      if (_app.store.audioEnabled == on) _app.store.audioDirty = false;
    });
  }

  bool get audioEnabled => _app.store.audioEnabled;

  void deleteAccount() {
    _run('Konto löschen fehlgeschlagen', () async {
      await _app.api.deleteMe();
      ++_loadGeneration;
      await _app.auth.signOut();
      await _app.wipeLocalData();
      _updateState(const UiState(phase: Phase.needsLogin));
      _syncLiveState();
    });
  }

  String? get signInPictureUrl => _app.auth.pictureUrl;
  bool get clientIdMissing => !_app.auth.isConfigured;

  bool _isServerDown(Object e) =>
      e is ApiHttpException &&
      (e.code == 502 || e.code == 503 || e.code == 504);

  Future<void> _upgradeSession() async {
    if (!_app.auth.needsSessionUpgrade) return;
    try {
      final s = await _app.api.createSession();
      await _app.auth.storeSession(s.token);
    } catch (_) {}
  }

  Future<void> _loadSession(int gen) async {
    await _upgradeSession();
    while (gen == _loadGeneration) {
      try {
        final me = await _app.api.me();
        final exams = await _app.api.exams();
        Profile? profile;
        try {
          profile = await _app.api.profile();
        } catch (_) {
          profile = null;
        }
        if (gen != _loadGeneration) return;
        if (_app.store.audioDirty) {
          setAudioEnabled(_app.store.audioEnabled);
        } else if (profile?.audioEnabled != null) {
          _app.store.audioEnabled = profile!.audioEnabled!;
        }
        final gate = gateFor(profile);
        _updateState(
          _state.copyWith(
            phase: Phase.ready,
            me: me,
            clearFailure: true,
            serverStarting: false,
            exams: exams,
            examsLoaded: true,
            chosenExamId: _app.store.chosenExamId,
            onboarding: gate,
            offline: false,
          ),
        );
        _syncLiveState();
        try {
          _app.local.saveMe(me);
          _app.local.saveExams(exams);
        } catch (_) {}
        unawaited(() async {
          try {
            await _app.offlineSync.syncAll();
          } catch (_) {}
        }());
        unawaited(() async {
          await _loadForecast();
          for (final e in exams) {
            await _app.attemptSync.flush(e.id);
          }
        }());
        if (!_updateChecked) {
          _updateChecked = true;
          _app.updater.checkInBackground(ignoreThrottle: true);
        }
        return;
      } catch (e) {
        if (gen != _loadGeneration) return;
        if (e is ApiHttpException && e.code == 401) {
          if (_app.auth.canPrompt) {
            _updateState(
              _state.copyWith(
                phase: Phase.needsLogin,
                loginBusy: false,
                serverStarting: false,
              ),
            );
          } else {
            _updateState(
              _state.copyWith(
                phase: Phase.failed,
                failure: _errorText(e),
                serverStarting: false,
              ),
            );
          }
          _syncLiveState();
          return;
        }
        if (_isServerDown(e)) {
          _updateState(
            _state.copyWith(
              phase: Phase.loading,
              clearFailure: true,
              serverStarting: true,
            ),
          );
          await Future<void>.delayed(
            const Duration(milliseconds: _retryDelayMs),
          );
          continue;
        }
        if ((e is IOException || e is TimeoutException) && _tryOffline()) {
          return;
        }
        _buzz(HapticKind.reject);
        _updateState(
          _state.copyWith(
            phase: Phase.failed,
            failure: _errorText(e),
            serverStarting: false,
          ),
        );
        _syncLiveState();
        return;
      }
    }
  }

  void _run(
    String failure,
    Future<void> Function() block, {
    VoidCallback? onFailure,
  }) {
    if (!_app.connectivity.isOnline()) {
      onFailure?.call();
      needInternet();
      return;
    }
    unawaited(() async {
      while (_writeLock != null) {
        try {
          await _writeLock;
        } catch (_) {}
      }
      final completer = Completer<void>();
      _writeLock = completer.future;
      try {
        await block();
      } catch (e) {
        onFailure?.call();
        _buzz(HapticKind.reject);
        _say('$failure. ${_errorText(e)}');
      } finally {
        completer.complete();
        _writeLock = null;
      }
    }());
  }

  String _errorText(Object e) {
    if (e is ApiHttpException) {
      return backendMessage(e) ??
          switch (e.code) {
            401 => 'Nicht angemeldet.',
            426 => 'Update erforderlich.',
            403 => 'Kein Zugriff.',
            404 => 'Nicht gefunden.',
            409 => 'Das ist gerade nicht möglich.',
            _ => 'Serverfehler (${e.code}).',
          };
    }
    if (e is IOException || e is TimeoutException || e is http.ClientException) {
      return 'Keine Verbindung zum Server.';
    }
    return 'Unbekannter Fehler.';
  }

  // ---------------------------------------------------------------- Pruefungen

  Future<void> _loadExams() async {
    final exams = await _app.api.exams();
    try {
      _app.local.saveExams(exams);
    } catch (_) {}
    _updateState(
      _state.copyWith(exams: exams, examsLoaded: true, offline: false),
    );
    await _loadForecast();
    final id = _state.materialsExamId;
    if (id != null && exams.any((e) => e.id == id)) {
      await loadMaterials(id);
    }
  }

  Future<void> _loadForecast() async {
    final exam = _state.activeExam;
    if (exam == null) return;
    try {
      final f = await _app.api.forecast(exam.id);
      try {
        _app.local.saveForecast(exam.id, f);
      } catch (_) {}
      _updateState(_state.copyWith(forecast: f, forecastExamId: exam.id));
    } catch (_) {
      final localF = _app.local.forecast(exam.id);
      if (localF != null) {
        _updateState(
          _state.copyWith(forecast: localF, forecastExamId: exam.id),
        );
      }
    }
  }

  Future<void> loadMaterials(String examId) async {
    try {
      final list = await _app.api.materials(examId);
      _updateState(
        _state.copyWith(materials: list, materialsExamId: examId),
      );
    } catch (_) {}
  }

  int? dayStreak() =>
      switch (_state.activeForecast?.streakDays) {
        final int d when d > 0 => d,
        _ => visibleDayStreak(
            _app.store.lastSessionDay != null
                ? DateTime.tryParse(_app.store.lastSessionDay!)
                : null,
            _app.store.dayStreak,
            DateTime.now(),
          ),
      };

  Future<Profile?> loadProfile() async {
    try {
      return await _app.api.profile();
    } catch (_) {
      return null;
    }
  }

  void saveProfile(
    ProfileDraft draft, {
    VoidCallback? onDone,
    VoidCallback? onFailure,
  }) {
    _run('Speichern fehlgeschlagen', onFailure: onFailure, () async {
      await _app.api.putProfile(
        ProfileUpdate(
          about: normalizeAbout(draft.about),
          age: draft.age,
          educationLevel: draft.education,
        ),
      );
      onDone?.call();
    });
  }

  void retryOnboardingCheck() {
    unawaited(() async {
      final gate = gateFor(await loadProfile());
      _updateState(_state.copyWith(onboarding: gate));
    }());
  }

  void skipOnboardingCheck() {
    _updateState(_state.copyWith(onboarding: OnboardingGate.done));
  }

  void finishOnboarding(
    int age,
    String? education,
    String about, {
    VoidCallback? onFailure,
  }) {
    _run('Speichern fehlgeschlagen', onFailure: onFailure, () async {
      final norm = normalizeAbout(about);
      await _app.api.putProfile(
        ProfileUpdate(
          about: norm.isEmpty ? null : norm,
          age: clampAge(age),
          educationLevel: education,
          onboardingDone: true,
        ),
      );
      _updateState(_state.copyWith(onboarding: OnboardingGate.done));
      _buzz(HapticKind.confirm);
    });
  }

  void uploadVoice(File file, void Function(VoiceOutcome) onResult) {
    unawaited(() async {
      VoiceOutcome result;
      try {
        final mp = await http.MultipartFile.fromPath(
          'file',
          file.path,
          filename: 'voice.$voiceFileExt',
          contentType: MediaType.parse(voiceMime),
        );
        final summary = await _app.api.profileVoice(mp);
        result = VoiceText(summary.about);
      } catch (e) {
        result = voiceOutcomeForStatus(
          e is ApiHttpException ? e.code : null,
        );
      } finally {
        try {
          if (file.existsSync()) file.deleteSync();
        } catch (_) {}
      }
      onResult(result);
    }());
  }

  void openMaterials(String examId) {
    unawaited(loadMaterials(examId));
  }

  void selectExam(String id) {
    _app.store.chosenExamId = id;
    _updateState(_state.copyWith(chosenExamId: id));
    unawaited(_loadForecast());
  }

  void createExam(
    ExamCreate body, {
    void Function(Exam)? onDone,
    VoidCallback? onFailure,
  }) {
    _run('Thema anlegen fehlgeschlagen', onFailure: onFailure, () async {
      final exam = await _app.api.createExam(body);
      _app.store.chosenExamId = exam.id;
      _updateState(
        _state.copyWith(
          exams: [..._state.exams, exam],
          chosenExamId: exam.id,
          clearForecast: true,
          clearForecastExamId: true,
        ),
      );
      _buzz(HapticKind.confirm);
      onDone?.call(exam);
      await _loadForecast();
    });
  }

  void updateExam(String id, ExamUpdate body, {VoidCallback? onFailure}) {
    _run('Speichern fehlgeschlagen', onFailure: onFailure, () async {
      final exam = await _app.api.updateExam(id, body);
      _updateState(
        _state.copyWith(
          exams: [for (final e in _state.exams) e.id == id ? exam : e],
        ),
      );
      await _loadForecast();
    });
  }

  void deleteExam(String id) {
    _run('Löschen fehlgeschlagen', () async {
      await _app.api.deleteExam(id);
      final wasChosen = _state.chosenExamId == id;
      if (wasChosen) _app.store.chosenExamId = null;
      _updateState(
        _state.copyWith(
          exams: [for (final e in _state.exams) if (e.id != id) e],
          clearChosenExamId: wasChosen,
          clearForecast: true,
          clearForecastExamId: true,
        ),
      );
      _buzz(HapticKind.gestureEnd);
      await _loadForecast();
    });
  }

  void regenerateItems(String id, {bool resetProgress = false}) {
    _run('Neu erzeugen fehlgeschlagen', () async {
      await _app.api.regenerate(id, resetProgress: resetProgress ? true : null);
      _buzz(HapticKind.confirm);
      await _loadExams();
    });
  }

  void deleteMaterial(String examId, String materialId) {
    _run('Entfernen fehlgeschlagen', () async {
      await _app.api.deleteMaterial(examId, materialId);
      _buzz(HapticKind.gestureEnd);
      await loadMaterials(examId);
      await _loadExams();
    });
  }

  Future<CheatSheet?> cheatsheetNow(String examId) async {
    try {
      return await _app.api.cheatsheet(examId, minutes: 5);
    } catch (_) {
      _say('Merkzettel gerade nicht erreichbar.');
      return null;
    }
  }

  void applyForecast(String examId, Forecast forecast) {
    try {
      _app.local.saveForecast(examId, forecast);
    } catch (_) {}
    _updateState(_state.copyWith(forecast: forecast, forecastExamId: examId));
  }

  void refreshExamsNow() {
    unawaited(() async {
      try {
        await _loadExams();
      } catch (_) {}
    }());
  }

  void openPreview(String examId, MaterialItem material) {
    unawaited(() async {
      try {
        await _app.preview.open(examId, material);
      } catch (e) {
        _say(
          e is IOException
              ? 'Vorschau gerade nicht möglich.'
              : 'Keine App zum Öffnen gefunden.',
        );
      }
    }());
  }

  // ---------------------------------------------------------------- Upload

  String _unitName(UploadJob job) => job.files.length == 1
      ? job.files.first.name
      : 'Scan (${job.files.length} Seiten)';

  void upload(String examId, List<UploadJob> jobs) {
    if (jobs.isEmpty) return;
    if (!_app.connectivity.isOnline()) {
      needInternet();
      return;
    }
    if (!_uploadRunning) _units.clear();
    _units.addAll([
      for (final j in jobs)
        _UploadUnit(examId, j, UploadItem(name: _unitName(j))),
    ]);
    _publishUpload(examId);
    if (!_uploadRunning) {
      unawaited(_processQueue());
    }
  }

  void _publishUpload(
    String examId, {
    double? progress,
    bool finished = false,
  }) {
    final total = _units.length;
    final currentIdx = _units.indexWhere(
      (u) => u.item.state == UploadItemState.uploading,
    );
    final failed = [
      for (final u in _units)
        if (u.item.state == UploadItemState.failed) u.item,
    ];
    final done = _units
        .where((u) => u.item.state == UploadItemState.done)
        .length;
    _updateState(
      _state.copyWith(
        upload: UploadState(
          examId,
          progress: progress,
          current: currentIdx >= 0 ? currentIdx + 1 : total,
          total: total,
          error: (finished && failed.isNotEmpty)
              ? uploadSummaryText(done, total)
              : null,
          failedItems: finished ? failed : const [],
        ),
      ),
    );
  }

  Future<void> _processQueue() async {
    if (_units.isEmpty) return;
    _uploadRunning = true;
    _uploadCancelled = false;
    final examId = _units.first.examId;
    try {
      final summary = await runUploadQueue<_UploadUnit>(
        size: () => _units.length,
        itemAt: (i) => _units[i],
        onState: (i, st, err) {
          if (i < _units.length) {
            _units[i].item = UploadItem(
              name: _units[i].item.name,
              state: st,
              error: err != null ? _uploadErrorText(err) : null,
            );
          }
          if (st == UploadItemState.failed) _buzz(HapticKind.reject);
          _publishUpload(examId);
        },
        upload: (_, unit) async {
          if (_uploadCancelled) throw const UploadCancelledException();
          var prepared = <UploadFile>[];
          try {
            for (final f in unit.job.files) {
              if (f.size > _absoluteMaxBytes) {
                throw FormatException('„${f.name}“ ist zu groß.');
              }
            }
            prepared = [
              for (final f in unit.job.files) await compressForUpload(f),
            ];
            if (_uploadCancelled) throw const UploadCancelledException();
            final job = UploadJob(
              files: prepared,
              role: unit.job.role,
              scan: unit.job.scan,
            );
            final total = totalSize(job);
            var sent = 0;
            await uploadMaterialJob(
              network: _app.network,
              examId: unit.examId,
              job: job,
              onBytes: (n) {
                sent += n;
                if (total > 0) {
                  _publishUpload(
                    unit.examId,
                    progress: (sent / total).clamp(0.0, 1.0),
                  );
                }
              },
            );
          } finally {
            cleanupUploadCopies([
              for (final p in prepared)
                if (!unit.job.files.any((orig) => orig.path == p.path)) p,
            ]);
          }
          await loadMaterials(unit.examId);
        },
      );

      if (summary.failed.isEmpty) {
        _buzz(HapticKind.confirm);
        _updateState(
          _state.copyWith(
            upload: UploadState(examId, done: true, total: summary.total),
          ),
        );
        _units.clear();
      } else {
        _publishUpload(examId, finished: true);
      }
      await loadMaterials(examId);
      try {
        await _loadExams();
      } catch (_) {}
    } on UploadCancelledException {
      for (var i = 0; i < _units.length; i++) {
        final st = _units[i].item.state;
        if (st == UploadItemState.waiting || st == UploadItemState.uploading) {
          _units[i].item = _units[i].item.copyWith(
                state: UploadItemState.cancelled,
              );
        }
      }
    } finally {
      _uploadRunning = false;
    }
  }

  void retryUpload() {
    if (_uploadRunning) return;
    var any = false;
    for (final u in _units) {
      if (u.item.state == UploadItemState.failed) {
        u.item = UploadItem(name: u.item.name);
        any = true;
      }
    }
    final examId = _units.firstOrNull?.examId;
    if (examId == null || !any) return;
    if (!_app.connectivity.isOnline()) {
      needInternet();
      return;
    }
    _publishUpload(examId);
    unawaited(_processQueue());
  }

  void cancelUpload() {
    _uploadCancelled = true;
    _units.clear();
    _updateState(_state.copyWith(clearUpload: true));
  }

  void clearUpload() {
    _updateState(_state.copyWith(clearUpload: true));
  }

  String _uploadErrorText(Object e) => switch (e) {
        UploadHttpException(:final code, :final serverMessage) =>
          uploadFailureText(serverMessage, code, false),
        FormatException(:final message) => message,
        SocketException() || TimeoutException() || HttpException() =>
          uploadFailureText(null, null, true),
        _ => uploadFailureText(null, null, false),
      };
}
