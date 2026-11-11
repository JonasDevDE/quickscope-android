import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';
import '../app_view_model.dart';
import '../data/models.dart';
import '../logic/exams.dart';
import '../logic/understand.dart';
import 'brand_mark.dart';
import 'components.dart';
import 'haptics.dart';
import 'theme.dart';

/// Kapitelliste in "Verstehen": Titel und Status (neu, gehört, sitzt); Tippen springt zur ersten ungehörten Lektion des Kapitels.
class ChapterSheet extends StatelessWidget {
  final List<ChapterEntry> entries;
  final String? currentChapterId;
  final ValueChanged<ChapterEntry> onPick;
  final VoidCallback onDismiss;

  const ChapterSheet({
    super.key,
    required this.entries,
    required this.currentChapterId,
    required this.onPick,
    required this.onDismiss,
  });

  @override
  Widget build(BuildContext context) => QuickscopeSheet(
        onDismiss: onDismiss,
        title: 'Kapitel',
        children: [
          for (final e in entries)
            RowCard(
              onTap: () {
                onDismiss();
                onPick(e);
              },
              children: [
                Expanded(
                  child: Text(
                    '${e.order}. ${e.title}',
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: Theme.of(context).textTheme.titleMedium?.copyWith(
                          fontWeight: e.chapterId == currentChapterId
                              ? FontWeight.bold
                              : FontWeight.normal,
                        ),
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.only(left: 12),
                  child: Text(
                    e.status,
                    style: Theme.of(context).textTheme.labelLarge?.copyWith(
                          color: e.status == 'neu'
                              ? AppColors.muted
                              : AppColors.accent,
                        ),
                  ),
                ),
              ],
            ),
        ],
      );
}

/// "Mehr" auf Heute: "Verstehen" (nur wenn es Lektionen gibt) und "Testen".
class MoreSheet extends StatelessWidget {
  final bool canUnderstand;
  final VoidCallback onTest;
  final VoidCallback onUnderstand;
  final VoidCallback onDismiss;

  const MoreSheet({
    super.key,
    required this.canUnderstand,
    required this.onTest,
    required this.onUnderstand,
    required this.onDismiss,
  });

  @override
  Widget build(BuildContext context) => QuickscopeSheet(
        onDismiss: onDismiss,
        children: [
          if (canUnderstand)
            _BigRow(
              iconAsset: 'assets/icons/ic_headphones.svg',
              title: 'Verstehen',
              subtitle: 'Erst Erklärungen hören',
              onTap: () {
                onDismiss();
                onUnderstand();
              },
            ),
          _BigRow(
            iconAsset: 'assets/icons/ic_quiz.svg',
            title: 'Testen',
            subtitle: 'Nur Fragen',
            onTap: () {
              onDismiss();
              onTest();
            },
          ),
        ],
      );
}

class _BigRow extends StatelessWidget {
  final String iconAsset;
  final String title;
  final String subtitle;
  final VoidCallback onTap;

  const _BigRow({
    required this.iconAsset,
    required this.title,
    required this.subtitle,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) => RowCard(
        onTap: onTap,
        minHeight: 76,
        children: [
          Container(
            width: 44,
            height: 44,
            decoration: BoxDecoration(
              color: AppColors.black,
              shape: BoxShape.circle,
              border: Border.all(color: AppColors.outline),
            ),
            alignment: Alignment.center,
            child: SvgPicture.asset(
              iconAsset,
              width: 24,
              height: 24,
              colorFilter: const ColorFilter.mode(
                AppColors.accent,
                BlendMode.srcIn,
              ),
            ),
          ),
          const SizedBox(width: 16),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(title, style: Theme.of(context).textTheme.titleMedium),
                Text(
                  subtitle,
                  style: Theme.of(context)
                      .textTheme
                      .bodySmall
                      ?.copyWith(color: AppColors.muted),
                ),
              ],
            ),
          ),
        ],
      );
}

/// Pille im Kopf (ohne Pfeil): Name der aktiven Pruefung, Tippen oeffnet die Liste.
class ExamPill extends StatelessWidget {
  final String name;
  final bool loading;
  final VoidCallback onTap;
  final String? icon;

  const ExamPill({
    super.key,
    required this.name,
    required this.loading,
    required this.onTap,
    this.icon,
  });

  @override
  Widget build(BuildContext context) => GestureDetector(
        onTap: !loading
            ? () {
                Haptics.instance.tick();
                onTap();
              }
            : null,
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 10),
          decoration: BoxDecoration(
            color: AppColors.cardHigh,
            borderRadius: BorderRadius.circular(50),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              if (!loading) ...[
                TopicIcon(icon, size: 20),
                const SizedBox(width: 8),
              ],
              ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 220),
                child: Text(
                  name,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: Theme.of(context).textTheme.titleMedium,
                ),
              ),
            ],
          ),
        ),
      );
}

/// "Meine Themen": Zeile waehlt, Drei-Punkte oeffnen direkt die Thema-Seite; unten "Neues Thema".
class ExamsSheet extends StatelessWidget {
  final UiState state;
  final AppViewModel vm;
  final VoidCallback onNew;
  final ValueChanged<String> onEdit;
  final VoidCallback onDismiss;

  const ExamsSheet({
    super.key,
    required this.state,
    required this.vm,
    required this.onNew,
    required this.onEdit,
    required this.onDismiss,
  });

  @override
  Widget build(BuildContext context) {
    final now = DateTime.now();
    final active = state.activeExam?.id;
    final sorted = sortExams(state.exams, now);

    return QuickscopeSheet(
      onDismiss: onDismiss,
      title: 'Meine Themen',
      children: [
        for (final e in sorted)
          RowCard(
            key: ValueKey(e.id),
            onTap: () {
              vm.selectExam(e.id);
              onDismiss();
            },
            children: [
              TopicIcon(e.icon, size: 24),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      e.name,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: Theme.of(context).textTheme.titleMedium?.copyWith(
                            fontWeight: e.id == active
                                ? FontWeight.bold
                                : FontWeight.normal,
                          ),
                    ),
                    Text(
                      examSubtitle(e, now),
                      style: Theme.of(context)
                          .textTheme
                          .bodySmall
                          ?.copyWith(color: AppColors.muted),
                    ),
                  ],
                ),
              ),
              GestureDetector(
                behavior: HitTestBehavior.opaque,
                onTap: () {
                  Haptics.instance.tick();
                  onDismiss();
                  onEdit(e.id);
                },
                child: Padding(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 16,
                    vertical: 8,
                  ),
                  child: Text(
                    '⋮',
                    style: Theme.of(context)
                        .textTheme
                        .titleLarge
                        ?.copyWith(color: Colors.white),
                  ),
                ),
              ),
            ],
          ),
        PrimaryButton('Neues Thema', onPressed: onNew),
      ],
    );
  }
}

/// Merkzettel (Letzte Runde): die wichtigsten Fakten als kurze Stichpunkte, nach Themen gruppiert.
class CheatsheetSheet extends StatefulWidget {
  final AppViewModel vm;
  final String examId;
  final VoidCallback onDismiss;

  const CheatsheetSheet({
    super.key,
    required this.vm,
    required this.examId,
    required this.onDismiss,
  });

  @override
  State<CheatsheetSheet> createState() => _CheatsheetSheetState();
}

class _CheatsheetSheetState extends State<CheatsheetSheet> {
  CheatSheet? _sheet;
  bool _failed = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final s = await widget.vm.cheatsheetNow(widget.examId);
    if (!mounted) return;
    setState(() {
      _sheet = s;
      _failed = s == null;
    });
  }

  @override
  Widget build(BuildContext context) {
    final s = _sheet;
    final children = <Widget>[];

    if (s != null) {
      if (s.bullets.isEmpty) {
        children.add(const EmptyHint('Noch nichts zum Lesen'));
      } else {
        String? lastTopic;
        for (final b in s.bullets) {
          if (b.topic != lastTopic) {
            lastTopic = b.topic;
            children.add(
              SectionTitle(
                b.topic,
                color: AppColors.accent,
                padding: const EdgeInsets.only(top: 8),
              ),
            );
          }
          children.add(
            Text('• ${b.text}', style: Theme.of(context).textTheme.bodyLarge),
          );
        }
      }
      if (s.note.trim().isNotEmpty) {
        children.add(
          Padding(
            padding: const EdgeInsets.only(top: 8),
            child: Text(
              s.note,
              style: Theme.of(context)
                  .textTheme
                  .bodySmall
                  ?.copyWith(color: AppColors.muted),
            ),
          ),
        );
      }
    } else if (_failed) {
      children.add(const EmptyHint('Gerade nicht erreichbar'));
    } else {
      children.addAll(const [
        SkeletonCard(80),
        SkeletonCard(80),
        SkeletonCard(80),
      ]);
    }

    return QuickscopeSheet(
      onDismiss: widget.onDismiss,
      title: 'Merkzettel',
      children: children,
    );
  }
}
