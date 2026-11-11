import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';
import '../app_view_model.dart';
import '../config.dart';
import '../data/updater.dart';
import 'brand_mark.dart';
import 'components.dart';
import 'haptics.dart';
import 'theme.dart';

/// Pflicht-Update: Vollbild, opak, nicht schliessbar und fuer Touches undurchlaessig, solange ein neueres Release erzwungen/bekannt ist.
class UpdateScreen extends StatefulWidget {
  final UiState state;
  final AppViewModel vm;

  const UpdateScreen({super.key, required this.state, required this.vm});

  @override
  State<UpdateScreen> createState() => _UpdateScreenState();
}

class _UpdateScreenState extends State<UpdateScreen> {
  bool _latched = false;

  @override
  Widget build(BuildContext context) {
    final update = widget.state.update;
    final wants = widget.state.updateForced ||
        update is UpdatePreparing ||
        update is UpdateReady ||
        update is UpdateInstalling ||
        (update is UpdateError && update.blocking);

    if (wants) {
      _latched = true;
    } else if (update is UpdateIdle || update is UpdateUpToDate) {
      _latched = false;
    }
    if (!_latched && !wants) return const SizedBox.shrink();

    final (name, notes) = switch (update) {
      UpdatePreparing(:final versionName, :final notes) => (versionName, notes),
      UpdateReady(:final versionName, :final notes) => (versionName, notes),
      _ => (null, null),
    };

    final theme = Theme.of(context).textTheme;

    return PopScope(
      canPop: false,
      child: ColoredBox(
        color: AppColors.black,
        child: SafeArea(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(28, 24, 28, 16),
            child: Column(
              children: [
                Expanded(
                  child: Center(
                    child: SingleChildScrollView(
                      child: Column(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          const LearnMark(size: 140),
                          const SizedBox(height: 16),
                          Text(
                            'Update erforderlich',
                            textAlign: TextAlign.center,
                            style: theme.headlineMedium,
                          ),
                          if (name != null) ...[
                            const SizedBox(height: 8),
                            Text(
                              'Version $name',
                              style: theme.titleMedium
                                  ?.copyWith(color: AppColors.accent),
                            ),
                          ],
                          if (update is UpdateError) ...[
                            const SizedBox(height: 12),
                            Text(
                              update.message,
                              textAlign: TextAlign.center,
                              style: theme.bodyMedium
                                  ?.copyWith(color: AppColors.danger),
                            ),
                          ],
                          if (notes != null && notes.trim().isNotEmpty) ...[
                            const SizedBox(height: 24),
                            Container(
                              width: double.infinity,
                              padding: const EdgeInsets.all(16),
                              decoration: BoxDecoration(
                                color: AppColors.cardHigh,
                                borderRadius: BorderRadius.circular(20),
                              ),
                              child: Text(
                                notes.trim(),
                                style: theme.bodyMedium
                                    ?.copyWith(color: AppColors.muted),
                              ),
                            ),
                          ],
                        ],
                      ),
                    ),
                  ),
                ),
                if (update is! UpdateReady && update is! UpdateError) ...[
                  Padding(
                    padding: const EdgeInsets.only(bottom: 16),
                    child: LinearProgressIndicator(
                      value: update is UpdatePreparing &&
                              update.progress != null &&
                              update.progress! > 0
                          ? update.progress
                          : null,
                      color: AppColors.accent,
                      backgroundColor: AppColors.cardHigh,
                    ),
                  ),
                ],
                switch (update) {
                  UpdateError() => Column(
                      children: [
                        PrimaryButton(
                          'Erneut versuchen',
                          onPressed: widget.vm.retryUpdate,
                        ),
                        const SizedBox(height: 8),
                        GestureDetector(
                          onTap: () {
                            Haptics.instance.tick();
                            launchUrl(
                              Uri.parse(
                                '${AppConfig.baseUrl}api/app/latest/apk',
                              ),
                              mode: LaunchMode.externalApplication,
                            );
                          },
                          child: Padding(
                            padding: const EdgeInsets.symmetric(
                              horizontal: 24,
                              vertical: 12,
                            ),
                            child: Text(
                              'Im Browser herunterladen',
                              style: theme.labelLarge
                                  ?.copyWith(color: AppColors.muted),
                            ),
                          ),
                        ),
                      ],
                    ),
                  UpdateInstalling() => PrimaryButton(
                      'Wird installiert',
                      enabled: false,
                      onPressed: () {},
                    ),
                  UpdateIdle() || UpdateUpToDate() => PrimaryButton(
                      'Update wird gesucht…',
                      enabled: false,
                      onPressed: () {},
                    ),
                  UpdateReady() => PrimaryButton(
                      'Jetzt aktualisieren',
                      onPressed: widget.vm.installUpdate,
                    ),
                  UpdatePreparing() => PrimaryButton(
                      'Wird geladen',
                      enabled: false,
                      onPressed: () {},
                    ),
                },
              ],
            ),
          ),
        ),
      ),
    );
  }
}
