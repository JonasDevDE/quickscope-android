import 'package:flutter/material.dart' hide CloseButton;
import '../app_view_model.dart';
import '../data/models.dart';
import '../data/uploads.dart';
import '../logic/exams.dart';
import 'brand_mark.dart';
import 'components.dart';
import 'haptics.dart';
import 'material_add.dart';
import 'theme.dart';

/// Neue Pruefung als Seiten mit Fortschrittspunkten: Name, Datum und Uhrzeit, Material, Beschreibung.
/// Material und Beschreibung sind optional. [onClose] ist null, solange es noch keine Pruefung gibt (kein Zurueck).
class NewExamFlow extends StatefulWidget {
  final AppViewModel vm;
  final VoidCallback? onClose;

  const NewExamFlow({super.key, required this.vm, this.onClose});

  @override
  State<NewExamFlow> createState() => _NewExamFlowState();
}

class _NewExamFlowState extends State<NewExamFlow> {
  int _page = 0;
  bool _isOpen = false;
  String _name = '';
  DateTime? _date;
  TimeOfDay _time = const TimeOfDay(hour: 8, minute: 0);
  List<UploadJob> _jobs = const [];
  String _description = '';
  bool _busy = false;
  String _prior = 'some';
  int _daily = 20;

  List<NewExamPage> get _pages => newExamPages(_isOpen);

  String _formatDate(DateTime d) =>
      '${d.year.toString().padLeft(4, '0')}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';

  String _formatTime(TimeOfDay t) =>
      '${t.hour.toString().padLeft(2, '0')}:${t.minute.toString().padLeft(2, '0')}';

  String _jobLabel(UploadJob job) {
    final what = job.scan
        ? 'Scan, ${job.files.length} ${job.files.length == 1 ? 'Seite' : 'Seiten'}'
        : job.files.first.name;
    return '$what · ${job.role == 'exam' ? 'Aufgaben' : 'Skript'}';
  }

  void _back() {
    if (_page > 0) {
      setState(() => _page--);
    } else {
      widget.onClose?.call();
    }
  }

  void _finish(int minutes) {
    setState(() {
      _daily = minutes;
      _busy = true;
    });
    final desc = _description.trim();
    widget.vm.createExam(
      ExamCreate(
        name: _name.trim(),
        date: _isOpen || _date == null ? null : _formatDate(_date!),
        time: _isOpen ? null : _formatTime(_time),
        mode: _isOpen ? 'open' : null,
        description: desc.isEmpty ? null : desc,
        dailyMinutes: minutes,
        priorKnowledge: _prior,
      ),
      onDone: (exam) {
        widget.vm.upload(exam.id, _jobs);
        widget.onClose?.call();
      },
      onFailure: () {
        if (mounted) setState(() => _busy = false);
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    final pages = _pages;
    final p = _page.clamp(0, pages.length - 1);
    final currentPage = pages[p];
    final past = _date != null &&
        isInPast(_date!, (_time.hour, _time.minute), DateTime.now());

    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop && (_page > 0 || widget.onClose != null)) _back();
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
                      SizedBox(
                        width: 88,
                        height: 48,
                        child: Align(
                          alignment: Alignment.centerLeft,
                          child: (_page > 0 || widget.onClose != null)
                              ? CloseButton(
                                  onPressed: _back,
                                  label: _page > 0 ? 'Zurück' : '✕',
                                )
                              : null,
                        ),
                      ),
                      Expanded(
                        child: Row(
                          mainAxisAlignment: MainAxisAlignment.center,
                          children: [
                            for (var i = 0; i < pages.length; i++)
                              Container(
                                margin:
                                    const EdgeInsets.symmetric(horizontal: 4),
                                width: 8,
                                height: 8,
                                decoration: BoxDecoration(
                                  color: i == p
                                      ? AppColors.accent
                                      : AppColors.cardHigh,
                                  shape: BoxShape.circle,
                                ),
                              ),
                          ],
                        ),
                      ),
                      const SizedBox(width: 88),
                    ],
                  ),
                ),
                Expanded(
                  child: AnimatedSwitcher(
                    duration: const Duration(milliseconds: 220),
                    child: SingleChildScrollView(
                      key: ValueKey(currentPage),
                      padding: const EdgeInsets.only(bottom: 24),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: switch (currentPage) {
                          NewExamPage.kind => [
                              const _PageTitle('Gibt es ein Prüfungsdatum?'),
                              const SizedBox(height: 16),
                              PrimaryButton(
                                'Mit Prüfungsdatum',
                                onPressed: () => setState(() {
                                  _isOpen = false;
                                  _page = p + 1;
                                }),
                              ),
                              const SizedBox(height: 16),
                              SecondaryButton(
                                'Kein Prüfungsdatum',
                                onPressed: () => setState(() {
                                  _isOpen = true;
                                  _page = p + 1;
                                }),
                              ),
                            ],
                          NewExamPage.name => [
                              const _PageTitle('Wie heißt das Thema?'),
                              const SizedBox(height: 16),
                              QuickscopeField(
                                value: _name,
                                onChanged: (v) => setState(
                                  () => _name =
                                      v.length > 80 ? v.substring(0, 80) : v,
                                ),
                                label: 'Name',
                              ),
                              const SizedBox(height: 24),
                              PrimaryButton(
                                'Weiter',
                                enabled: _name.trim().isNotEmpty,
                                onPressed: () => setState(() => _page = p + 1),
                              ),
                            ],
                          NewExamPage.when => [
                              const _PageTitle('Wann ist der Prüfungstermin?'),
                              const SizedBox(height: 16),
                              WhenPicker(
                                date: _date,
                                time: _time,
                                onDate: (d) => setState(() => _date = d),
                                onTime: (t) => setState(() => _time = t),
                              ),
                              if (past) ...[
                                const SizedBox(height: 16),
                                Text(
                                  'Dieser Zeitpunkt ist schon vorbei.',
                                  style: Theme.of(context)
                                      .textTheme
                                      .bodyMedium
                                      ?.copyWith(color: AppColors.danger),
                                ),
                              ],
                              const SizedBox(height: 24),
                              PrimaryButton(
                                'Weiter',
                                enabled: _date != null && !past,
                                onPressed: () => setState(() => _page = p + 1),
                              ),
                            ],
                          NewExamPage.material => [
                              const _PageTitle('Material hinzufügen'),
                              const SizedBox(height: 16),
                              MaterialAddButtons(
                                onJobs: (j) =>
                                    setState(() => _jobs = [..._jobs, ...j]),
                              ),
                              for (var i = 0; i < _jobs.length; i++) ...[
                                const SizedBox(height: 12),
                                Row(
                                  children: [
                                    Expanded(
                                      child: Text(
                                        _jobLabel(_jobs[i]),
                                        maxLines: 1,
                                        overflow: TextOverflow.ellipsis,
                                        style: Theme.of(context)
                                            .textTheme
                                            .bodyMedium,
                                      ),
                                    ),
                                    GestureDetector(
                                      onTap: () {
                                        Haptics.instance.tick();
                                        setState(() {
                                          _jobs = [
                                            for (var j = 0;
                                                j < _jobs.length;
                                                j++)
                                              if (j != i) _jobs[j],
                                          ];
                                        });
                                      },
                                      child: const Padding(
                                        padding: EdgeInsets.all(8),
                                        child: Text(
                                          '✕',
                                          style:
                                              TextStyle(color: AppColors.muted),
                                        ),
                                      ),
                                    ),
                                  ],
                                ),
                              ],
                              const SizedBox(height: 24),
                              PrimaryButton(
                                _jobs.isEmpty ? 'Überspringen' : 'Weiter',
                                onPressed: () => setState(() => _page = p + 1),
                              ),
                            ],
                          NewExamPage.description => [
                              const _PageTitle('Kurz beschreiben?'),
                              const SizedBox(height: 16),
                              QuickscopeField(
                                value: _description,
                                onChanged: (v) => setState(
                                  () => _description = v.length > 2000
                                      ? v.substring(0, 2000)
                                      : v,
                                ),
                                label:
                                    'z. B. IHK AP2, Schwerpunkt Kalkulation',
                                singleLine: false,
                              ),
                              const SizedBox(height: 8),
                              Align(
                                alignment: Alignment.centerRight,
                                child: Text(
                                  '${_description.length} / 2000',
                                  style: Theme.of(context)
                                      .textTheme
                                      .bodySmall
                                      ?.copyWith(color: AppColors.muted),
                                ),
                              ),
                              const SizedBox(height: 24),
                              PrimaryButton(
                                'Weiter',
                                onPressed: () => setState(() => _page = p + 1),
                              ),
                            ],
                          NewExamPage.prior => [
                              const _PageTitle('Vorwissen?'),
                              for (final key in priorKeys) ...[
                                const SizedBox(height: 16),
                                if (key == _prior)
                                  PrimaryButton(
                                    priorLabel(key),
                                    onPressed: () => setState(() {
                                      _prior = key;
                                      _page = p + 1;
                                    }),
                                  )
                                else
                                  SecondaryButton(
                                    priorLabel(key),
                                    onPressed: () => setState(() {
                                      _prior = key;
                                      _page = p + 1;
                                    }),
                                  ),
                              ],
                            ],
                          NewExamPage.daily => [
                              const _PageTitle('Wie viel Zeit pro Tag?'),
                              for (final m in dailyMinuteChoices) ...[
                                const SizedBox(height: 16),
                                if (m == _daily)
                                  PrimaryButton(
                                    _busy ? 'Einen Moment' : '$m min',
                                    onPressed: () {
                                      if (!_busy) _finish(m);
                                    },
                                  )
                                else
                                  SecondaryButton(
                                    '$m min',
                                    onPressed: () {
                                      if (!_busy) _finish(m);
                                    },
                                  ),
                              ],
                            ],
                        },
                      ),
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

class _PageTitle extends StatelessWidget {
  final String text;
  const _PageTitle(this.text);

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(top: 8),
        child: Text(text, style: Theme.of(context).textTheme.headlineMedium),
      );
}
