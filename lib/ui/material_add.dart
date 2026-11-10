import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';
import '../app_view_model.dart';
import '../data/uploads.dart';
import '../logic/upload_queue.dart';
import 'components.dart';
import 'haptics.dart';
import 'theme.dart';

class _Pending {
  final List<UploadFile> files;
  final bool scan;
  const _Pending(this.files, {required this.scan});
}

/// Drei grosse Knoepfe zum Hinzufuegen von Material: Foto aufnehmen (mehrere Seiten, ein Scan),
/// Aus Galerie (mehrere Bilder), Dateien waehlen (jede Datei ein Material). Pro Auswahl wird einmal die Rolle gefragt.
class MaterialAddButtons extends StatefulWidget {
  final ValueChanged<List<UploadJob>> onJobs;

  const MaterialAddButtons({super.key, required this.onJobs});

  @override
  State<MaterialAddButtons> createState() => _MaterialAddButtonsState();
}

class _MaterialAddButtonsState extends State<MaterialAddButtons> {
  final _picker = ImagePicker();
  _Pending? _pending;
  List<UploadFile> _pages = const [];
  bool _askMore = false;

  Future<void> _shoot() async {
    final xfile = await _picker.pickImage(source: ImageSource.camera);
    if (!mounted) return;
    if (xfile != null) {
      final desc = await describeFile(xfile.path, displayName: xfile.name);
      if (!mounted) return;
      setState(() {
        _pages = [..._pages, desc];
        _askMore = true;
      });
    } else if (_pages.isNotEmpty) {
      setState(() => _askMore = true);
    }
  }

  Future<void> _pickGallery() async {
    final picked = await _picker.pickMultiImage();
    if (!mounted || picked.isEmpty) return;
    final files = [
      for (final x in picked)
        await describeFile(x.path, displayName: x.name),
    ];
    if (!mounted) return;
    setState(() => _pending = _Pending(files, scan: false));
  }

  Future<void> _pickFiles() async {
    final result = await FilePicker.platform.pickFiles(allowMultiple: true);
    if (!mounted || result == null || result.files.isEmpty) return;
    final files = [
      for (final f in result.files)
        if (f.path != null)
          await describeFile(f.path!, displayName: f.name),
    ];
    if (!mounted || files.isEmpty) return;
    setState(() => _pending = _Pending(files, scan: false));
  }

  @override
  Widget build(BuildContext context) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          PrimaryButton(
            'Foto aufnehmen',
            onPressed: () {
              setState(() => _pages = const []);
              _shoot();
            },
          ),
          const SizedBox(height: 12),
          SecondaryButton('Aus Galerie', onPressed: _pickGallery),
          const SizedBox(height: 12),
          SecondaryButton('Dateien wählen', onPressed: _pickFiles),
          if (_askMore) ...[
            const SizedBox(height: 12),
            CardColumn(
              spacing: 12,
              children: [
                Text(
                  '${_pages.length} ${_pages.length == 1 ? 'Seite' : 'Seiten'} aufgenommen',
                  style: Theme.of(context).textTheme.titleMedium,
                ),
                const Text(
                  'Noch eine Seite für denselben Scan?',
                  style: TextStyle(color: AppColors.muted),
                ),
                Row(
                  mainAxisAlignment: MainAxisAlignment.end,
                  children: [
                    HapticTextButton(
                      onPressed: () => setState(() {
                        _askMore = false;
                        if (_pages.isNotEmpty) {
                          _pending = _Pending(_pages, scan: true);
                        }
                        _pages = const [];
                      }),
                      child: const Text(
                        'Fertig',
                        style: TextStyle(color: Colors.white),
                      ),
                    ),
                    HapticTextButton(
                      onPressed: () {
                        setState(() => _askMore = false);
                        _shoot();
                      },
                      child: const Text(
                        'Noch eine Seite',
                        style: TextStyle(color: AppColors.accent),
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ],
          if (_pending case final p?) ...[
            const SizedBox(height: 12),
            _RoleCard(
              onPick: (role) {
                setState(() => _pending = null);
                widget.onJobs(
                  p.scan
                      ? [UploadJob(files: p.files, role: role, scan: true)]
                      : [
                          for (final f in p.files)
                            UploadJob(files: [f], role: role, scan: false),
                        ],
                );
              },
              onDismiss: () => setState(() => _pending = null),
            ),
          ],
        ],
      );
}

class _RoleCard extends StatelessWidget {
  final ValueChanged<String> onPick;
  final VoidCallback onDismiss;

  const _RoleCard({required this.onPick, required this.onDismiss});

  @override
  Widget build(BuildContext context) => CardColumn(
        spacing: 12,
        children: [
          Text('Was ist das?', style: Theme.of(context).textTheme.titleMedium),
          PrimaryButton('Skript/Folien', onPressed: () => onPick('script')),
          PrimaryButton(
            'Aufgaben/Altklausur',
            onPressed: () => onPick('exam'),
          ),
          Align(
            alignment: Alignment.centerRight,
            child: HapticTextButton(
              onPressed: onDismiss,
              child: const Text(
                'Abbrechen',
                style: TextStyle(color: Colors.white),
              ),
            ),
          ),
        ],
      );
}

/// Fortschritt der Upload-Queue ("3 von 10") bzw. ruhige Zusammenfassung mit den fehlgeschlagenen Dateien, "Nochmal" nur fuer diese.
class UploadCard extends StatelessWidget {
  final UiState state;
  final String examId;
  final VoidCallback onCancel;
  final VoidCallback onRetry;

  const UploadCard({
    super.key,
    required this.state,
    required this.examId,
    required this.onCancel,
    required this.onRetry,
  });

  @override
  Widget build(BuildContext context) {
    final up = state.upload;
    if (up == null || up.examId != examId || up.done) {
      return const SizedBox.shrink();
    }
    return CardColumn(
      spacing: 10,
      children: [
        if (up.error != null) ...[
          Text(up.error!, style: Theme.of(context).textTheme.titleSmall),
          for (final f in up.failedItems)
            Text(
              '${f.name}: ${f.error ?? ''}',
              style: Theme.of(context)
                  .textTheme
                  .bodySmall
                  ?.copyWith(color: AppColors.danger),
            ),
          Row(
            children: [
              Pill('Nochmal', selected: true, onTap: onRetry),
              const SizedBox(width: 10),
              Pill('Verwerfen', selected: false, onTap: onCancel),
            ],
          ),
        ] else ...[
          Text(
            up.total > 1
                ? 'Wird hochgeladen · ${uploadProgressText(up.current, up.total)}'
                : 'Wird hochgeladen',
            style: Theme.of(context).textTheme.titleSmall,
          ),
          LinearProgressIndicator(
            value: up.progress != null && up.progress! > 0 ? up.progress : null,
            color: AppColors.accent,
            backgroundColor: AppColors.cardHigh,
          ),
          Pill('Abbrechen', selected: false, onTap: onCancel),
        ],
      ],
    );
  }
}
