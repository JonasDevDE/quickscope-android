import 'dart:async';
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
import 'settings_screen.dart';
import 'theme.dart';

/// Einstellungen, die ein eigenes kleines Sheet oeffnen.
enum ExamSetting {
  material,
  description,
  name,
  kind,
  daily,
  prior,
  reset,
  delete,
}

/// Thema-Seite im Stil der Profilseite: Name, Termin, Material, Beschreibung, Lernzeit, Vorwissen und am Ende der Gefahrenbereich.
class ExamSettingsSheet extends StatefulWidget {
  final UiState state;
  final AppViewModel vm;
  final String examId;
  final ExamSetting? startAt;
  final VoidCallback onDismiss;

  const ExamSettingsSheet({
    super.key,
    required this.state,
    required this.vm,
    required this.examId,
    this.startAt,
    required this.onDismiss,
  });

  @override
  State<ExamSettingsSheet> createState() => _ExamSettingsSheetState();
}

class _ExamSettingsSheetState extends State<ExamSettingsSheet> {
  late ExamSetting? _sub = widget.startAt;
  String _name = '';
  String _savedName = '';
  Timer? _nameDebounce;

  Exam? get _exam {
    for (final e in widget.state.exams) {
      if (e.id == widget.examId) return e;
    }
    return null;
  }

  @override
  void initState() {
    super.initState();
    final e = _exam;
    if (e != null) {
      _name = e.name;
      _savedName = e.name;
    }
  }

  void _commitName() {
    final trimmed = _name.trim();
    if (trimmed.isEmpty || trimmed == _savedName) return;
    final prev = _savedName;
    _savedName = trimmed;
    Haptics.instance.tick();
    widget.vm.updateExam(
      widget.examId,
      ExamUpdate(name: trimmed),
      onFailure: () => _savedName = prev,
    );
  }

  @override
  void dispose() {
    _nameDebounce?.cancel();
    _commitName();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final exam = _exam;
    if (exam == null) {
      WidgetsBinding.instance.addPostFrameCallback((_) => widget.onDismiss());
      return const SizedBox.shrink();
    }

    return Stack(
      children: [
        PageScaffold(
          title: 'Thema',
          onClose: widget.onDismiss,
          children: [
            QuickscopeField(
              value: _name,
              onChanged: (v) {
                final clamped = v.length > 80 ? v.substring(0, 80) : v;
                setState(() => _name = clamped);
                _nameDebounce?.cancel();
                _nameDebounce =
                    Timer(const Duration(milliseconds: 800), _commitName);
              },
              label: 'Name',
            ),
            SettingsRow(
              title: 'Prüfungsdatum',
              onTap: () => setState(() => _sub = ExamSetting.kind),
              trailing: _Value(
                isOpen(exam)
                    ? 'Kein Prüfungsdatum'
                    : formatExamWhen(exam.date, exam.time),
              ),
            ),
            SettingsRow(
              title: 'Material',
              onTap: () => setState(() => _sub = ExamSetting.material),
              trailing: _Value(
                exam.materialCount == 1
                    ? '1 Datei'
                    : '${exam.materialCount} Dateien',
              ),
            ),
            SettingsRow(
              title: 'Beschreibung',
              onTap: () => setState(() => _sub = ExamSetting.description),
              trailing: _Value(
                exam.description != null && exam.description!.trim().isNotEmpty
                    ? exam.description!
                    : '–',
              ),
            ),
            SettingsRow(
              title: 'Lernzeit pro Tag',
              onTap: () => setState(() => _sub = ExamSetting.daily),
              trailing: _Value('${exam.dailyMinutes} min'),
            ),
            SettingsRow(
              title: 'Vorwissen',
              onTap: () => setState(() => _sub = ExamSetting.prior),
              trailing: _Value(priorLabel(exam.priorKnowledge)),
            ),
            DangerZone(
              children: [
                DangerRow(
                  'Von vorn beginnen',
                  onTap: () => setState(() => _sub = ExamSetting.reset),
                ),
                DangerRow(
                  'Thema löschen',
                  onTap: () => setState(() => _sub = ExamSetting.delete),
                ),
              ],
            ),
          ],
        ),
        switch (_sub) {
          null || ExamSetting.name => const SizedBox.shrink(),
          ExamSetting.material => _MaterialSheet(
              state: widget.state,
              vm: widget.vm,
              exam: exam,
              onDismiss: () => setState(() => _sub = null),
            ),
          ExamSetting.description => _DescriptionSheet(
              vm: widget.vm,
              exam: exam,
              onDismiss: () => setState(() => _sub = null),
            ),
          ExamSetting.kind => _KindSheet(
              vm: widget.vm,
              exam: exam,
              onDismiss: () => setState(() => _sub = null),
            ),
          ExamSetting.daily => _DailySheet(
              state: widget.state,
              vm: widget.vm,
              exam: exam,
              onDismiss: () => setState(() => _sub = null),
            ),
          ExamSetting.prior => _PriorSheet(
              vm: widget.vm,
              exam: exam,
              onDismiss: () => setState(() => _sub = null),
            ),
          ExamSetting.reset => TwoStepConfirm(
              title1: 'Von vorn beginnen?',
              text1:
                  'Der Lernstand dieses Themas wird zurückgesetzt und alle Aufgaben werden neu aufgebaut.',
              title2: 'Wirklich von vorn beginnen?',
              text2: 'Letzte Bestätigung: Der Lernstand wird jetzt zurückgesetzt.',
              confirmLabel: 'Von vorn beginnen',
              onConfirm: () {
                widget.vm.regenerateItems(exam.id, resetProgress: true);
                widget.onDismiss();
              },
              onCancel: () => setState(() => _sub = null),
            ),
          ExamSetting.delete => TwoStepConfirm(
              title1: 'Thema löschen?',
              text1:
                  'Material, Aufgaben und Fortschritt dieses Themas werden gelöscht.',
              title2: 'Wirklich endgültig löschen?',
              text2:
                  'Letzte Bestätigung: „${exam.name}“ wird unwiderruflich gelöscht.',
              confirmLabel: 'Endgültig löschen',
              onConfirm: () {
                widget.vm.deleteExam(exam.id);
                widget.onDismiss();
              },
              onCancel: () => setState(() => _sub = null),
            ),
        },
      ],
    );
  }
}

class _Value extends StatelessWidget {
  final String text;
  const _Value(this.text);

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(left: 12),
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 160),
          child: Text(
            text,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: Theme.of(context)
                .textTheme
                .labelLarge
                ?.copyWith(color: AppColors.muted),
          ),
        ),
      );
}

/// Zweistufige Bestaetigung wie beim Konto loeschen.
class TwoStepConfirm extends StatefulWidget {
  final String title1;
  final String text1;
  final String title2;
  final String text2;
  final String confirmLabel;
  final VoidCallback onConfirm;
  final VoidCallback onCancel;

  const TwoStepConfirm({
    super.key,
    required this.title1,
    required this.text1,
    required this.title2,
    required this.text2,
    required this.confirmLabel,
    required this.onConfirm,
    required this.onCancel,
  });

  @override
  State<TwoStepConfirm> createState() => _TwoStepConfirmState();
}

class _TwoStepConfirmState extends State<TwoStepConfirm> {
  int _step = 1;

  @override
  Widget build(BuildContext context) => AlertDialog(
        backgroundColor: AppColors.cardHigh,
        title: Text(
          _step == 1 ? widget.title1 : widget.title2,
          style: const TextStyle(color: Colors.white),
        ),
        content: Text(
          _step == 1 ? widget.text1 : widget.text2,
          style: const TextStyle(color: Color(0xFFCFCFD4)),
        ),
        actions: [
          HapticTextButton(
            onPressed: widget.onCancel,
            child: const Text(
              'Abbrechen',
              style: TextStyle(color: Colors.white),
            ),
          ),
          HapticTextButton(
            onPressed: () {
              if (_step == 1) {
                setState(() => _step = 2);
              } else {
                widget.onConfirm();
              }
            },
            child: Text(
              _step == 1 ? 'Weiter' : widget.confirmLabel,
              style: const TextStyle(color: AppColors.danger),
            ),
          ),
        ],
      );
}

class _DescriptionSheet extends StatefulWidget {
  final AppViewModel vm;
  final Exam exam;
  final VoidCallback onDismiss;

  const _DescriptionSheet({
    required this.vm,
    required this.exam,
    required this.onDismiss,
  });

  @override
  State<_DescriptionSheet> createState() => _DescriptionSheetState();
}

class _DescriptionSheetState extends State<_DescriptionSheet> {
  late String _text = widget.exam.description ?? '';
  late String _saved = (_text).trim();
  Timer? _debounce;

  void _commit() {
    final trimmed = _text.trim();
    if (trimmed == _saved) return;
    final prev = _saved;
    _saved = trimmed;
    Haptics.instance.tick();
    widget.vm.updateExam(
      widget.exam.id,
      ExamUpdate(description: trimmed),
      onFailure: () => _saved = prev,
    );
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _commit();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => PageScaffold(
        title: 'Beschreibung',
        onClose: widget.onDismiss,
        children: [
          QuickscopeField(
            value: _text,
            onChanged: (v) {
              setState(
                () => _text = v.length > 2000 ? v.substring(0, 2000) : v,
              );
              _debounce?.cancel();
              _debounce = Timer(const Duration(milliseconds: 800), _commit);
            },
            label: 'z. B. IHK AP2, Schwerpunkt Kalkulation',
            singleLine: false,
            minHeight: 180,
          ),
          Text(
            '${_text.length} / 2000',
            style: Theme.of(context)
                .textTheme
                .bodySmall
                ?.copyWith(color: AppColors.muted),
          ),
        ],
      );
}

class _KindSheet extends StatefulWidget {
  final AppViewModel vm;
  final Exam exam;
  final VoidCallback onDismiss;

  const _KindSheet({
    required this.vm,
    required this.exam,
    required this.onDismiss,
  });

  @override
  State<_KindSheet> createState() => _KindSheetState();
}

class _KindSheetState extends State<_KindSheet> {
  late final bool _wasExam = !isOpen(widget.exam);
  late bool _wantsExam = _wasExam;
  late DateTime? _date = DateTime.tryParse(widget.exam.date);
  late TimeOfDay _time = _parseTime(widget.exam.time);

  TimeOfDay _parseTime(String? s) {
    if (s == null) return const TimeOfDay(hour: 8, minute: 0);
    final parts = s.split(':');
    return TimeOfDay(
      hour: int.tryParse(parts[0]) ?? 8,
      minute: parts.length > 1 ? (int.tryParse(parts[1]) ?? 0) : 0,
    );
  }

  String _formatDate(DateTime d) =>
      '${d.year.toString().padLeft(4, '0')}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';

  String _formatTime(TimeOfDay t) =>
      '${t.hour.toString().padLeft(2, '0')}:${t.minute.toString().padLeft(2, '0')}';

  bool get _past =>
      _date != null &&
      isInPast(_date!, (_time.hour, _time.minute), DateTime.now());

  void _autoSaveIfExam() {
    if (_wasExam && _wantsExam && _date != null && !_past) {
      Haptics.instance.tick();
      widget.vm.updateExam(
        widget.exam.id,
        ExamUpdate(date: _formatDate(_date!), time: _formatTime(_time)),
      );
    }
  }

  @override
  Widget build(BuildContext context) => QuickscopeSheet(
        onDismiss: widget.onDismiss,
        title: 'Prüfungsdatum',
        children: [
          if (_wantsExam)
            PrimaryButton('Mit Prüfungsdatum', onPressed: () {})
          else
            SecondaryButton(
              'Mit Prüfungsdatum',
              onPressed: () => setState(() => _wantsExam = true),
            ),
          if (!_wantsExam)
            PrimaryButton('Kein Prüfungsdatum', onPressed: () {})
          else
            SecondaryButton(
              'Kein Prüfungsdatum',
              onPressed: () {
                widget.vm.updateExam(
                  widget.exam.id,
                  const ExamUpdate(mode: 'open'),
                );
                widget.onDismiss();
              },
            ),
          if (_wantsExam) ...[
            WhenPicker(
              date: _date,
              time: _time,
              onDate: (d) {
                setState(() => _date = d);
                _autoSaveIfExam();
              },
              onTime: (t) {
                setState(() => _time = t);
                _autoSaveIfExam();
              },
            ),
            if (_past)
              Text(
                'Dieser Zeitpunkt ist schon vorbei.',
                style: Theme.of(context)
                    .textTheme
                    .bodyMedium
                    ?.copyWith(color: AppColors.danger),
              ),
            if (!_wasExam)
              PrimaryButton(
                'Speichern',
                enabled: _date != null && !_past,
                onPressed: () {
                  widget.vm.updateExam(
                    widget.exam.id,
                    ExamUpdate(
                      mode: 'exam',
                      date: _formatDate(_date!),
                      time: _formatTime(_time),
                    ),
                  );
                  widget.onDismiss();
                },
              ),
          ],
        ],
      );
}

class _PriorSheet extends StatefulWidget {
  final AppViewModel vm;
  final Exam exam;
  final VoidCallback onDismiss;

  const _PriorSheet({
    required this.vm,
    required this.exam,
    required this.onDismiss,
  });

  @override
  State<_PriorSheet> createState() => _PriorSheetState();
}

class _PriorSheetState extends State<_PriorSheet> {
  late String _value = widget.exam.priorKnowledge;

  @override
  Widget build(BuildContext context) => QuickscopeSheet(
        onDismiss: widget.onDismiss,
        title: 'Vorwissen',
        children: [
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              for (final k in priorKeys)
                Pill(
                  priorLabel(k),
                  selected: _value == k,
                  onTap: () {
                    setState(() => _value = k);
                    widget.vm.updateExam(
                      widget.exam.id,
                      ExamUpdate(priorKnowledge: k),
                    );
                  },
                ),
            ],
          ),
        ],
      );
}

class _DailySheet extends StatefulWidget {
  final UiState state;
  final AppViewModel vm;
  final Exam exam;
  final VoidCallback onDismiss;

  const _DailySheet({
    required this.state,
    required this.vm,
    required this.exam,
    required this.onDismiss,
  });

  @override
  State<_DailySheet> createState() => _DailySheetState();
}

class _DailySheetState extends State<_DailySheet> {
  late int _value = widget.exam.dailyMinutes;

  @override
  Widget build(BuildContext context) {
    final forecast = widget.state.forecastExamId == widget.exam.id
        ? widget.state.forecast
        : null;
    return QuickscopeSheet(
      onDismiss: widget.onDismiss,
      title: 'Lernzeit pro Tag',
      children: [
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            for (final c in dailyMinuteChoices)
              Pill(
                minutesChoiceLabel(forecast, c),
                selected: _value == c,
                onTap: () {
                  setState(() => _value = c);
                  widget.vm.updateExam(
                    widget.exam.id,
                    ExamUpdate(dailyMinutes: c),
                  );
                },
              ),
          ],
        ),
      ],
    );
  }
}

class _MaterialSheet extends StatefulWidget {
  final UiState state;
  final AppViewModel vm;
  final Exam exam;
  final VoidCallback onDismiss;

  const _MaterialSheet({
    required this.state,
    required this.vm,
    required this.exam,
    required this.onDismiss,
  });

  @override
  State<_MaterialSheet> createState() => _MaterialSheetState();
}

class _MaterialSheetState extends State<_MaterialSheet> {
  MaterialItem? _removing;

  @override
  void initState() {
    super.initState();
    widget.vm.loadMaterials(widget.exam.id);
  }

  String _roleLabel(MaterialItem m) => m.role == 'exam' ? 'Aufgaben' : 'Skript';

  String _statusLabel(MaterialItem m) => switch (m.status) {
        'queued' => 'Wartet',
        'processing' => 'Wird gelesen',
        'done' => 'Fertig',
        'failed' => 'Fehler',
        _ => m.status,
      };

  @override
  Widget build(BuildContext context) {
    final list = widget.state.materialsExamId == widget.exam.id
        ? widget.state.materials
        : null;

    return Stack(
      children: [
        PageScaffold(
          title: 'Material',
          onClose: widget.onDismiss,
          children: [
            UploadCard(
              state: widget.state,
              examId: widget.exam.id,
              onCancel: widget.vm.cancelUpload,
              onRetry: widget.vm.retryUpload,
            ),
            if (list == null) ...const [
              SkeletonCard(70),
              SkeletonCard(70),
            ] else if (list.isEmpty)
              const EmptyHint('Noch kein Material')
            else
              for (final m in list)
                GestureDetector(
                  onTap: () {
                    Haptics.instance.tick();
                    widget.vm.openPreview(widget.exam.id, m);
                  },
                  child: Container(
                    width: double.infinity,
                    padding: const EdgeInsets.all(16),
                    decoration: BoxDecoration(
                      color: AppColors.cardHigh,
                      borderRadius: BorderRadius.circular(24),
                      border: Border.all(color: AppColors.outline),
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            Expanded(
                              child: Text(
                                m.name,
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: Theme.of(context).textTheme.titleMedium,
                              ),
                            ),
                            GestureDetector(
                              onTap: () {
                                Haptics.instance.tick();
                                setState(() => _removing = m);
                              },
                              child: const Padding(
                                padding: EdgeInsets.only(
                                  left: 12,
                                  top: 4,
                                  bottom: 4,
                                ),
                                child: Text(
                                  '✕',
                                  style: TextStyle(color: AppColors.muted),
                                ),
                              ),
                            ),
                          ],
                        ),
                        const SizedBox(height: 8),
                        Wrap(
                          spacing: 8,
                          runSpacing: 8,
                          children: [
                            _Chip(_roleLabel(m)),
                            _Chip(
                              _statusLabel(m),
                              color: m.status == 'failed'
                                  ? AppColors.danger
                                  : m.status == 'done'
                                      ? AppColors.accent
                                      : AppColors.muted,
                            ),
                            if (m.pages != null)
                              _Chip(
                                '${m.pages} ${m.pages == 1 ? 'Seite' : 'Seiten'}',
                              ),
                          ],
                        ),
                        if (m.warning != null) ...[
                          const SizedBox(height: 8),
                          Text(
                            '${m.warning}. Am besten neu aufnehmen.',
                            style: Theme.of(context)
                                .textTheme
                                .bodySmall
                                ?.copyWith(color: AppColors.warn),
                          ),
                        ],
                        if (m.status == 'failed' && m.error != null) ...[
                          const SizedBox(height: 8),
                          Text(
                            neutralMessage(m.error!),
                            style: Theme.of(context)
                                .textTheme
                                .bodySmall
                                ?.copyWith(color: AppColors.danger),
                          ),
                        ],
                      ],
                    ),
                  ),
                ),
            MaterialAddButtons(
              onJobs: (jobs) => widget.vm.upload(widget.exam.id, jobs),
            ),
          ],
        ),
        if (_removing case final m?)
          AlertDialog(
            backgroundColor: AppColors.cardHigh,
            title: const Text(
              'Material entfernen?',
              style: TextStyle(color: Colors.white),
            ),
            content: Text(
              '„${m.name}“ und die daraus erzeugten Aufgaben werden entfernt.',
              style: const TextStyle(color: Color(0xFFCFCFD4)),
            ),
            actions: [
              HapticTextButton(
                onPressed: () => setState(() => _removing = null),
                child: const Text(
                  'Abbrechen',
                  style: TextStyle(color: Colors.white),
                ),
              ),
              HapticTextButton(
                onPressed: () {
                  setState(() => _removing = null);
                  widget.vm.deleteMaterial(widget.exam.id, m.id);
                },
                child: const Text(
                  'Entfernen',
                  style: TextStyle(color: AppColors.danger),
                ),
              ),
            ],
          ),
      ],
    );
  }
}

class _Chip extends StatelessWidget {
  final String text;
  final Color color;

  const _Chip(this.text, {this.color = AppColors.muted});

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
        decoration: BoxDecoration(
          color: AppColors.black,
          borderRadius: BorderRadius.circular(50),
          border: Border.all(color: AppColors.outline),
        ),
        child: Text(
          text,
          style: Theme.of(context).textTheme.labelMedium?.copyWith(color: color),
        ),
      );
}
