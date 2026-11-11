import 'package:flutter/material.dart';
import '../app_view_model.dart';
import '../config.dart';
import '../data/updater.dart';
import '../logic/markdown.dart';
import 'components.dart';
import 'haptics.dart';
import 'theme.dart';

/// Avatar-Sheet, radikal einfach: Avatar, Name, E-Mail klein und hoechstens drei grosse Zeilen (Über mich, Einstellungen, Abmelden).
class MeSheet extends StatelessWidget {
  final UiState state;
  final AppViewModel vm;
  final VoidCallback onAbout;
  final VoidCallback onSettings;
  final VoidCallback onDismiss;

  const MeSheet({
    super.key,
    required this.state,
    required this.vm,
    required this.onAbout,
    required this.onSettings,
    required this.onDismiss,
  });

  @override
  Widget build(BuildContext context) {
    final me = state.me;
    return QuickscopeSheet(
      onDismiss: onDismiss,
      children: [
        Row(
          children: [
            UserAvatar(
              imageUrl: me?.pictureUrl ?? vm.signInPictureUrl,
              name: me?.name ?? me?.email ?? '?',
              size: 56,
            ),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    me?.name ?? me?.email ?? ' ',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                  if (me?.name != null && me?.email != null)
                    Text(
                      me!.email!,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: Theme.of(context)
                          .textTheme
                          .bodySmall
                          ?.copyWith(color: AppColors.muted),
                    ),
                ],
              ),
            ),
          ],
        ),
        _BigSettingsRow('Über mich', onAbout),
        _BigSettingsRow('Einstellungen', onSettings),
        _BigSettingsRow('Abmelden', vm.signOut),
      ],
    );
  }
}

class _BigSettingsRow extends StatelessWidget {
  final String title;
  final VoidCallback onTap;

  const _BigSettingsRow(this.title, this.onTap);

  @override
  Widget build(BuildContext context) => RowCard(
        onTap: onTap,
        children: [
          Expanded(
            child: Text(title, style: Theme.of(context).textTheme.titleMedium),
          ),
        ],
      );
}

enum _SettingsPage { sound, account }

/// Einstellungen (app-weit): nur Ton und Konto.
class SettingsMain extends StatefulWidget {
  final UiState state;
  final AppViewModel vm;
  final ValueChanged<LegalPage> onLegal;
  final VoidCallback onDismiss;

  const SettingsMain({
    super.key,
    required this.state,
    required this.vm,
    required this.onLegal,
    required this.onDismiss,
  });

  @override
  State<SettingsMain> createState() => _SettingsMainState();
}

class _SettingsMainState extends State<SettingsMain> {
  _SettingsPage? _page;

  @override
  Widget build(BuildContext context) => switch (_page) {
        null => QuickscopeSheet(
            onDismiss: widget.onDismiss,
            title: 'Einstellungen',
            children: [
              SettingsRow(
                title: 'Ton',
                onTap: () => setState(() => _page = _SettingsPage.sound),
                trailing: _Chevron(widget.vm.audioEnabled ? 'An' : 'Aus'),
              ),
              SettingsRow(
                title: 'Konto',
                onTap: () => setState(() => _page = _SettingsPage.account),
                trailing: const _Chevron(null),
              ),
            ],
          ),
        _SettingsPage.sound => _SoundPage(
            vm: widget.vm,
            onDismiss: () => setState(() => _page = null),
          ),
        _SettingsPage.account => _AccountPage(
            state: widget.state,
            vm: widget.vm,
            onLegal: widget.onLegal,
            onDismiss: () => setState(() => _page = null),
          ),
      };
}

class _Chevron extends StatelessWidget {
  final String? value;
  const _Chevron(this.value);

  @override
  Widget build(BuildContext context) => Text(
        '${value != null ? '$value  ' : ''}›',
        style: Theme.of(context)
            .textTheme
            .titleMedium
            ?.copyWith(color: AppColors.muted),
      );
}

class _SoundPage extends StatefulWidget {
  final AppViewModel vm;
  final VoidCallback onDismiss;

  const _SoundPage({required this.vm, required this.onDismiss});

  @override
  State<_SoundPage> createState() => _SoundPageState();
}

class _SoundPageState extends State<_SoundPage> {
  late bool _on = widget.vm.audioEnabled;

  void _toggle(bool v) {
    final prev = _on;
    setState(() => _on = v);
    Haptics.instance.tick();
    widget.vm.setAudioEnabled(
      v,
      onFailure: () {
        if (mounted) setState(() => _on = prev);
      },
    );
  }

  @override
  Widget build(BuildContext context) => QuickscopeSheet(
        onDismiss: widget.onDismiss,
        title: 'Ton',
        children: [
          SettingsRow(
            title: 'Vorlesen',
            onTap: () => _toggle(!_on),
            trailing: Switch(
              value: _on,
              onChanged: _toggle,
              activeThumbColor: Colors.black,
              activeTrackColor: AppColors.accent,
              inactiveThumbColor: const Color(0xFFD0D0D4),
              inactiveTrackColor: const Color(0xFF3E3E42),
            ),
          ),
        ],
      );
}

class _AccountPage extends StatefulWidget {
  final UiState state;
  final AppViewModel vm;
  final ValueChanged<LegalPage> onLegal;
  final VoidCallback onDismiss;

  const _AccountPage({
    required this.state,
    required this.vm,
    required this.onLegal,
    required this.onDismiss,
  });

  @override
  State<_AccountPage> createState() => _AccountPageState();
}

class _AccountPageState extends State<_AccountPage> {
  int _accountStep = 0;

  @override
  Widget build(BuildContext context) {
    final online = widget.vm.online.value;
    final activeId = widget.state.activeExam?.id;
    final days = activeId != null ? widget.vm.offlineDaysCount(activeId) : null;

    return Stack(
      children: [
        QuickscopeSheet(
          onDismiss: widget.onDismiss,
          title: 'Konto',
          children: [
            if (days != null)
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 4),
                child: Text(
                  'Offline bereit: $days ${days == 1 ? 'Tag' : 'Tage'}',
                  style: Theme.of(context)
                      .textTheme
                      .bodyMedium
                      ?.copyWith(color: AppColors.muted),
                ),
              ),
            SettingsRow(
              title: 'Meine Daten exportieren',
              onTap: () {
                if (online) {
                  widget.vm.exportData();
                } else {
                  widget.vm.needInternet();
                }
              },
            ),
            UpdateRow(state: widget.state, vm: widget.vm),
            for (final lp in LegalPage.values)
              SettingsRow(
                title: lp.title,
                onTap: () => widget.onLegal(lp),
              ),
            DangerZone(
              children: [
                DangerRow(
                  'Konto löschen',
                  onTap: () {
                    if (online) {
                      setState(() => _accountStep = 1);
                    } else {
                      widget.vm.needInternet();
                    }
                  },
                ),
              ],
            ),
          ],
        ),
        if (_accountStep == 1)
          AlertDialog(
            backgroundColor: AppColors.cardHigh,
            title: const Text(
              'Konto löschen?',
              style: TextStyle(color: Colors.white),
            ),
            content: const Text(
              'Alle Daten werden endgültig gelöscht. Das kann nicht rückgängig gemacht werden.',
              style: TextStyle(color: Color(0xFFCFCFD4)),
            ),
            actions: [
              HapticTextButton(
                onPressed: () => setState(() => _accountStep = 0),
                child: const Text(
                  'Abbrechen',
                  style: TextStyle(color: Colors.white),
                ),
              ),
              HapticTextButton(
                onPressed: () => setState(() => _accountStep = 2),
                child: const Text(
                  'Weiter',
                  style: TextStyle(color: AppColors.danger),
                ),
              ),
            ],
          ),
        if (_accountStep == 2)
          AlertDialog(
            backgroundColor: AppColors.cardHigh,
            title: const Text(
              'Wirklich endgültig löschen?',
              style: TextStyle(color: Colors.white),
            ),
            content: const Text(
              'Letzte Bestätigung: Konto und alle zugehörigen Daten werden jetzt unwiderruflich gelöscht.',
              style: TextStyle(color: Color(0xFFCFCFD4)),
            ),
            actions: [
              HapticTextButton(
                onPressed: () => setState(() => _accountStep = 0),
                child: const Text(
                  'Abbrechen',
                  style: TextStyle(color: Colors.white),
                ),
              ),
              HapticTextButton(
                onPressed: () {
                  setState(() => _accountStep = 0);
                  widget.vm.deleteAccount();
                },
                child: const Text(
                  'Konto endgültig löschen',
                  style: TextStyle(color: AppColors.danger),
                ),
              ),
            ],
          ),
      ],
    );
  }
}

/// Einstellungszeile: Titel, optionaler Untertitel, Aktion rechts; gemeinsame erhoehte Flaeche mit Rand und Pressed-State.
class SettingsRow extends StatelessWidget {
  final String title;
  final String? subtitle;
  final VoidCallback onTap;
  final Widget? trailing;

  const SettingsRow({
    super.key,
    required this.title,
    this.subtitle,
    required this.onTap,
    this.trailing,
  });

  @override
  Widget build(BuildContext context) => RowCard(
        onTap: onTap,
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(title, style: Theme.of(context).textTheme.titleMedium),
                if (subtitle != null)
                  Text(
                    subtitle!,
                    style: Theme.of(context)
                        .textTheme
                        .bodySmall
                        ?.copyWith(color: AppColors.muted),
                  ),
              ],
            ),
          ),
          if (trailing != null) trailing!,
        ],
      );
}

class UpdateRow extends StatelessWidget {
  final UiState state;
  final AppViewModel vm;

  const UpdateRow({super.key, required this.state, required this.vm});

  @override
  Widget build(BuildContext context) {
    final update = state.update;
    final status = switch (update) {
      UpdatePreparing(:final progress) =>
        'Wird vorbereitet${progress != null ? ' · ${(progress * 100).toInt()} %' : ''}',
      UpdateReady(:final versionName, :final needsConfirm) => needsConfirm
          ? 'Update $versionName bereit'
          : 'Update $versionName wird beim Schließen installiert',
      UpdateInstalling() => 'Wird installiert',
      UpdateError(:final message) => message,
      _ => 'Aktuell',
    };
    final busy = update is UpdatePreparing || update is UpdateInstalling;
    final ready = update is UpdateReady && state.canInstallUpdates;

    return Column(
      children: [
        SettingsRow(
          title: 'Version ${AppConfig.versionName}',
          subtitle: status,
          onTap: () {
            if (ready) {
              vm.installUpdate();
            } else if (!busy) {
              vm.checkUpdatesManually();
            }
          },
          trailing: Text(
            ready
                ? 'Installieren'
                : busy
                    ? ''
                    : 'Suchen',
            style: Theme.of(context)
                .textTheme
                .labelMedium
                ?.copyWith(color: AppColors.accent),
          ),
        ),
        if (update is UpdatePreparing && update.progress != null)
          Padding(
            padding: const EdgeInsets.only(top: 8),
            child: LinearProgressIndicator(
              value: update.progress,
              color: AppColors.accent,
              backgroundColor: AppColors.cardHigh,
            ),
          ),
      ],
    );
  }
}
