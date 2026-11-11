import 'dart:async';
import 'package:flutter/material.dart' hide CloseButton;
import '../app_view_model.dart';
import '../data/models.dart';
import '../logic/profile.dart';
import 'brand_mark.dart';
import 'components.dart';
import 'haptics.dart';
import 'theme.dart';

/// Profil in den Einstellungen: Alter, Bildung und "Ueber mich". Dient nur dazu, Beispiele und Erklaerungen passender zu machen.
/// Kein Pflichtfeld; gespeichert wird automatisch und ohne Hinweis (nur Fehler erscheinen).
class AboutScreen extends StatefulWidget {
  final UiState state;
  final AppViewModel vm;
  final VoidCallback onClose;

  const AboutScreen({
    super.key,
    required this.state,
    required this.vm,
    required this.onClose,
  });

  @override
  State<AboutScreen> createState() => _AboutScreenState();
}

class _AboutScreenState extends State<AboutScreen> {
  Profile? _initial;
  bool _failed = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final loaded = await widget.vm.loadProfile();
    if (!mounted) return;
    setState(() {
      if (loaded == null) {
        _failed = true;
      } else {
        _initial = loaded;
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final p = _initial;
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) widget.onClose();
      },
      child: ColoredBox(
        color: AppColors.black,
        child: SafeArea(
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 24),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 12),
                  child: Row(
                    children: [
                      CloseButton(onPressed: widget.onClose, label: 'Zurück'),
                    ],
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.only(bottom: 8),
                  child: Text(
                    'Profil',
                    style: Theme.of(context).textTheme.headlineMedium,
                  ),
                ),
                if (p != null)
                  Expanded(
                    child: _ProfileEditor(
                      vm: widget.vm,
                      initial: p,
                      onClose: widget.onClose,
                    ),
                  )
                else if (_failed) ...[
                  const Text(
                    'Gerade nicht erreichbar.',
                    style: TextStyle(color: AppColors.muted),
                  ),
                  const SizedBox(height: 16),
                  SecondaryButton('Schließen', onPressed: widget.onClose),
                ] else
                  const SkeletonCard(140),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _ProfileEditor extends StatefulWidget {
  final AppViewModel vm;
  final Profile initial;
  final VoidCallback onClose;

  const _ProfileEditor({
    required this.vm,
    required this.initial,
    required this.onClose,
  });

  @override
  State<_ProfileEditor> createState() => _ProfileEditorState();
}

class _ProfileEditorState extends State<_ProfileEditor> {
  late ProfileDraft _draft = draftOf(widget.initial);
  late ProfileDraft _saved = draftOf(widget.initial);
  Timer? _debounce;

  void _scheduleSave({required bool instant}) {
    _debounce?.cancel();
    if (instant) {
      _commit();
    } else {
      _debounce = Timer(const Duration(milliseconds: 800), _commit);
    }
  }

  void _commit() {
    if (!draftValid(_draft) || !draftNeedsSave(_saved, _draft)) return;
    final current = _draft;
    final previous = _saved;
    _saved = current;
    Haptics.instance.tick();
    widget.vm.saveProfile(
      current,
      onDone: () => _saved = current,
      onFailure: () => _saved = previous,
    );
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _commit();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final ageOptions = [
      for (var a = ageMin; a <= ageMax; a++) (a, a.toString()),
    ];
    return SingleChildScrollView(
      padding: const EdgeInsets.only(bottom: 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                flex: 10,
                child: DropdownField<int>(
                  label: 'Alter',
                  value: _draft.age?.toString() ?? '–',
                  options: ageOptions,
                  onSelect: (age) {
                    setState(() => _draft = _draft.copyWith(age: age));
                    _scheduleSave(instant: true);
                  },
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                flex: 16,
                child: DropdownField<String>(
                  label: 'Bildung',
                  value: educationLabel(_draft.education),
                  options: education,
                  onSelect: (edu) {
                    setState(() => _draft = _draft.copyWith(education: edu));
                    _scheduleSave(instant: true);
                  },
                ),
              ),
            ],
          ),
          const SizedBox(height: 14),
          Text('Über mich', style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 14),
          QuickscopeField(
            value: _draft.about,
            onChanged: (v) {
              setState(() => _draft = _draft.copyWith(about: clampAbout(v)));
              _scheduleSave(instant: false);
            },
            label: 'Ich bin Snowboarder, arbeite im Marketing und mag Autos.',
            singleLine: false,
            minHeight: 180,
          ),
          const SizedBox(height: 14),
          Align(
            alignment: Alignment.centerRight,
            child: Text(
              aboutCounter(_draft.about),
              style: Theme.of(context)
                  .textTheme
                  .labelMedium
                  ?.copyWith(color: AppColors.muted),
            ),
          ),
          const SizedBox(height: 14),
          PrimaryButton('Fertig', onPressed: widget.onClose),
        ],
      ),
    );
  }
}
