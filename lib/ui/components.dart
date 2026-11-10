import 'package:flutter/material.dart' hide CloseButton;
import 'package:intl/intl.dart';
import '../logic/exams.dart';
import 'brand_mark.dart';
import 'haptics.dart';
import 'theme.dart';

/// Rundes Nutzerbild (z. B. Google-Profilbild) mit Initiale als Fallback.
class UserAvatar extends StatelessWidget {
  final String? imageUrl;
  final String name;
  final double size;

  const UserAvatar({
    super.key,
    required this.imageUrl,
    required this.name,
    required this.size,
  });

  @override
  Widget build(BuildContext context) {
    final initial = name.isNotEmpty ? name.substring(0, 1).toUpperCase() : '?';
    return Container(
      width: size,
      height: size,
      clipBehavior: Clip.antiAlias,
      decoration: const BoxDecoration(
        color: AppColors.cardHigh,
        shape: BoxShape.circle,
      ),
      alignment: Alignment.center,
      child: imageUrl != null && imageUrl!.isNotEmpty
          ? Image.network(
              imageUrl!,
              width: size,
              height: size,
              fit: BoxFit.cover,
              errorBuilder: (_, __, ___) => _initialText(context, initial),
            )
          : _initialText(context, initial),
    );
  }

  Widget _initialText(BuildContext context, String initial) => Text(
        initial,
        style: Theme.of(context).textTheme.titleMedium?.copyWith(
              color: AppColors.accent,
              fontSize: size * 0.4,
            ),
      );
}

/// Gemeinsame erhoehte Flaeche: SurfaceRaised mit Rand und sichtbarem Pressed-State. Grundlage fuer Zeilen, Karten, Sekundaerknoepfe.
class RowCard extends StatefulWidget {
  final VoidCallback? onTap;
  final double minHeight;
  final Color borderColor;
  final BorderRadius borderRadius;
  final List<Widget> children;
  final MainAxisAlignment mainAxisAlignment;

  const RowCard({
    super.key,
    required this.onTap,
    this.minHeight = 64,
    this.borderColor = AppColors.outline,
    this.borderRadius = const BorderRadius.all(Radius.circular(24)),
    this.mainAxisAlignment = MainAxisAlignment.start,
    required this.children,
  });

  @override
  State<RowCard> createState() => _RowCardState();
}

class _RowCardState extends State<RowCard> {
  bool _pressed = false;

  @override
  Widget build(BuildContext context) => GestureDetector(
        onTapDown: widget.onTap != null
            ? (_) => setState(() => _pressed = true)
            : null,
        onTapUp: widget.onTap != null
            ? (_) => setState(() => _pressed = false)
            : null,
        onTapCancel: widget.onTap != null
            ? () => setState(() => _pressed = false)
            : null,
        onTap: widget.onTap != null
            ? () {
                Haptics.instance.tick();
                widget.onTap!();
              }
            : null,
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 120),
          constraints: BoxConstraints(minHeight: widget.minHeight),
          width: double.infinity,
          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 14),
          decoration: BoxDecoration(
            color: _pressed ? AppColors.pressed : AppColors.cardHigh,
            borderRadius: widget.borderRadius,
            border: Border.all(color: widget.borderColor),
          ),
          child: Row(
            mainAxisAlignment: widget.mainAxisAlignment,
            children: widget.children,
          ),
        ),
      );
}

/// Gefahrenbereich: rot abgesetzte Ueberschrift und Zeilen, am Ende einer Seite.
class DangerZone extends StatelessWidget {
  final List<Widget> children;

  const DangerZone({super.key, required this.children});

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(top: 16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Gefahrenbereich',
              style: Theme.of(context)
                  .textTheme
                  .titleSmall
                  ?.copyWith(color: AppColors.danger),
            ),
            for (final child in children) ...[
              const SizedBox(height: 10),
              child,
            ],
          ],
        ),
      );
}

class DangerRow extends StatelessWidget {
  final String title;
  final VoidCallback onTap;

  const DangerRow(this.title, {super.key, required this.onTap});

  @override
  Widget build(BuildContext context) => RowCard(
        onTap: onTap,
        borderColor: AppColors.dangerOutline,
        children: [
          Text(
            title,
            style: Theme.of(context)
                .textTheme
                .titleMedium
                ?.copyWith(color: AppColors.danger),
          ),
        ],
      );
}

class PrimaryButton extends StatelessWidget {
  final String text;
  final VoidCallback onPressed;
  final bool enabled;

  const PrimaryButton(
    this.text, {
    super.key,
    required this.onPressed,
    this.enabled = true,
  });

  @override
  Widget build(BuildContext context) => GestureDetector(
        onTap: enabled
            ? () {
                Haptics.instance.tick();
                onPressed();
              }
            : null,
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 150),
          width: double.infinity,
          height: 56,
          alignment: Alignment.center,
          decoration: BoxDecoration(
            color: enabled ? AppColors.accent : AppColors.cardHigh,
            borderRadius: BorderRadius.circular(28),
            border: enabled ? null : Border.all(color: AppColors.outline),
          ),
          child: Text(
            text,
            style: Theme.of(context).textTheme.labelLarge?.copyWith(
                  color: enabled ? AppColors.onAccent : AppColors.muted,
                ),
          ),
        ),
      );
}

class SecondaryButton extends StatelessWidget {
  final String text;
  final VoidCallback onPressed;
  final Color color;

  const SecondaryButton(
    this.text, {
    super.key,
    required this.onPressed,
    this.color = Colors.white,
  });

  @override
  Widget build(BuildContext context) => RowCard(
        onTap: onPressed,
        minHeight: 56,
        borderRadius: BorderRadius.circular(28),
        children: [
          Expanded(
            child: Text(
              text,
              textAlign: TextAlign.center,
              style: Theme.of(context)
                  .textTheme
                  .labelLarge
                  ?.copyWith(color: color),
            ),
          ),
        ],
      );
}

class Pill extends StatelessWidget {
  final String text;
  final bool selected;
  final VoidCallback onTap;

  const Pill(
    this.text, {
    super.key,
    required this.selected,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) => GestureDetector(
        onTap: () {
          Haptics.instance.tick();
          onTap();
        },
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 150),
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
          decoration: BoxDecoration(
            color: selected ? AppColors.accent : AppColors.cardHigh,
            borderRadius: BorderRadius.circular(50),
            border: Border.all(
              color: selected ? AppColors.accent : AppColors.outline,
            ),
          ),
          child: Text(
            text,
            style: Theme.of(context).textTheme.labelMedium?.copyWith(
                  color: selected ? AppColors.onAccent : Colors.white,
                ),
          ),
        ),
      );
}

class QuickscopeField extends StatefulWidget {
  final String value;
  final ValueChanged<String> onChanged;
  final String label;
  final TextInputType keyboardType;
  final bool singleLine;
  final String? suffix;
  final double? minHeight;

  const QuickscopeField({
    super.key,
    required this.value,
    required this.onChanged,
    required this.label,
    this.keyboardType = TextInputType.text,
    this.singleLine = true,
    this.suffix,
    this.minHeight,
  });

  @override
  State<QuickscopeField> createState() => _QuickscopeFieldState();
}

class _QuickscopeFieldState extends State<QuickscopeField> {
  late final TextEditingController _ctrl =
      TextEditingController(text: widget.value);

  @override
  void didUpdateWidget(covariant QuickscopeField oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.value != _ctrl.text) {
      _ctrl.value = TextEditingValue(
        text: widget.value,
        selection: TextSelection.collapsed(offset: widget.value.length),
      );
    }
  }

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Container(
        constraints: widget.minHeight != null
            ? BoxConstraints(minHeight: widget.minHeight!)
            : null,
        decoration: BoxDecoration(
          color: AppColors.cardHigh,
          borderRadius: BorderRadius.circular(20),
          border: Border.all(color: AppColors.outline),
        ),
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
        child: TextField(
          controller: _ctrl,
          onChanged: widget.onChanged,
          keyboardType: widget.keyboardType,
          maxLines: widget.singleLine ? 1 : null,
          cursorColor: AppColors.accent,
          style: const TextStyle(color: Colors.white, fontSize: 16),
          decoration: InputDecoration(
            labelText: widget.label,
            labelStyle: const TextStyle(color: AppColors.muted),
            suffixText: widget.suffix,
            suffixStyle: const TextStyle(color: AppColors.muted),
            border: InputBorder.none,
          ),
        ),
      );
}

class SectionTitle extends StatelessWidget {
  final String text;
  final Color color;
  final EdgeInsetsGeometry padding;

  const SectionTitle(
    this.text, {
    super.key,
    this.color = AppColors.muted,
    this.padding = EdgeInsets.zero,
  });

  @override
  Widget build(BuildContext context) => Padding(
        padding: padding,
        child: Text(
          text,
          style:
              Theme.of(context).textTheme.titleSmall?.copyWith(color: color),
        ),
      );
}

class CardColumn extends StatelessWidget {
  final EdgeInsetsGeometry padding;
  final double spacing;
  final List<Widget> children;

  const CardColumn({
    super.key,
    this.padding = const EdgeInsets.all(20),
    this.spacing = 0,
    required this.children,
  });

  @override
  Widget build(BuildContext context) => Container(
        width: double.infinity,
        padding: padding,
        decoration: BoxDecoration(
          color: AppColors.card,
          borderRadius: BorderRadius.circular(28),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            for (var i = 0; i < children.length; i++) ...[
              if (i > 0 && spacing > 0) SizedBox(height: spacing),
              children[i],
            ],
          ],
        ),
      );
}

class EmptyHint extends StatelessWidget {
  final String text;

  const EmptyHint(this.text, {super.key});

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 32),
        child: SizedBox(
          width: double.infinity,
          child: Text(
            text,
            textAlign: TextAlign.center,
            style: Theme.of(context)
                .textTheme
                .bodyMedium
                ?.copyWith(color: AppColors.muted),
          ),
        ),
      );
}

/// Sheet-Rahmen im Quickscope-Stil: abgerundete Ecken oben, kleiner Griff, scrollbarer Inhalt.
class QuickscopeSheet extends StatelessWidget {
  final VoidCallback onDismiss;
  final String? title;
  final bool dismissable;
  final String? status;
  final List<Widget> children;

  const QuickscopeSheet({
    super.key,
    required this.onDismiss,
    this.title,
    this.dismissable = true,
    this.status,
    required this.children,
  });

  @override
  Widget build(BuildContext context) {
    final media = MediaQuery.of(context);
    return GestureDetector(
      onTap: dismissable
          ? () {
              Haptics.instance.gestureEnd();
              onDismiss();
            }
          : null,
      behavior: HitTestBehavior.opaque,
      child: ColoredBox(
        color: Colors.black54,
        child: Align(
          alignment: Alignment.bottomCenter,
          child: GestureDetector(
            onTap: () {},
            child: Container(
              width: double.infinity,
              constraints: BoxConstraints(maxHeight: media.size.height * 0.88),
              decoration: const BoxDecoration(
                color: AppColors.sheet,
                borderRadius: BorderRadius.vertical(top: Radius.circular(36)),
              ),
              child: SafeArea(
                top: false,
                child: SingleChildScrollView(
                  padding: EdgeInsets.fromLTRB(
                    24,
                    0,
                    24,
                    24 + media.viewInsets.bottom,
                  ),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Center(
                        child: Container(
                          margin: const EdgeInsets.only(top: 12, bottom: 4),
                          width: 40,
                          height: 4,
                          decoration: BoxDecoration(
                            color: const Color(0xFF3A3A3A),
                            borderRadius: BorderRadius.circular(2),
                          ),
                        ),
                      ),
                      if (title != null)
                        Padding(
                          padding: const EdgeInsets.only(top: 8, bottom: 4),
                          child: Row(
                            children: [
                              Expanded(
                                child: Text(
                                  title!,
                                  style:
                                      Theme.of(context).textTheme.headlineSmall,
                                ),
                              ),
                              if (status != null)
                                Text(
                                  status!,
                                  style: Theme.of(context)
                                      .textTheme
                                      .labelMedium
                                      ?.copyWith(color: AppColors.muted),
                                ),
                            ],
                          ),
                        ),
                      for (final child in children) ...[
                        const SizedBox(height: 14),
                        child,
                      ],
                      const SizedBox(height: 4),
                    ],
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// Ruhige Vollbildseite im Stil der Profilseite: Zurueck oben, grosser Titel, darunter wenige grosse Felder mit festen Abstaenden.
class PageScaffold extends StatelessWidget {
  final String title;
  final VoidCallback onClose;
  final String? status;
  final List<Widget> children;

  const PageScaffold({
    super.key,
    required this.title,
    required this.onClose,
    this.status,
    required this.children,
  });

  @override
  Widget build(BuildContext context) => PopScope(
        canPop: false,
        onPopInvokedWithResult: (didPop, _) {
          if (!didPop) onClose();
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
                        CloseButton(onPressed: onClose, label: 'Zurück'),
                      ],
                    ),
                  ),
                  Padding(
                    padding: const EdgeInsets.only(bottom: 8),
                    child: Text(
                      title,
                      style: Theme.of(context).textTheme.headlineMedium,
                    ),
                  ),
                  Expanded(
                    child: SingleChildScrollView(
                      padding: const EdgeInsets.only(bottom: 24),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          for (var i = 0; i < children.length; i++) ...[
                            if (i > 0) const SizedBox(height: 14),
                            children[i],
                          ],
                          if (status != null && status!.isNotEmpty) ...[
                            const SizedBox(height: 14),
                            Text(
                              status!,
                              style: Theme.of(context)
                                  .textTheme
                                  .labelMedium
                                  ?.copyWith(color: AppColors.muted),
                            ),
                          ],
                        ],
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

/// Auswahlfeld mit Dropdown (Zahlen oder Begriffe), im dunklen Stil.
class DropdownField<T> extends StatelessWidget {
  final String label;
  final String value;
  final List<(T, String)> options;
  final ValueChanged<T> onSelect;

  const DropdownField({
    super.key,
    required this.label,
    required this.value,
    required this.options,
    required this.onSelect,
  });

  @override
  Widget build(BuildContext context) => PopupMenuButton<T>(
        color: AppColors.cardHigh,
        constraints: const BoxConstraints(maxHeight: 320),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(12),
          side: const BorderSide(color: AppColors.outline),
        ),
        onSelected: (v) {
          Haptics.instance.tick();
          onSelect(v);
        },
        itemBuilder: (_) => [
          for (final (key, text) in options)
            PopupMenuItem<T>(
              value: key,
              child: Text(text, style: const TextStyle(color: Colors.white)),
            ),
        ],
        child: Container(
          width: double.infinity,
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
          decoration: BoxDecoration(
            color: AppColors.cardHigh,
            borderRadius: BorderRadius.circular(20),
            border: Border.all(color: AppColors.outline),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                label,
                style: Theme.of(context)
                    .textTheme
                    .labelMedium
                    ?.copyWith(color: AppColors.muted),
              ),
              Text(
                value,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: Theme.of(context).textTheme.titleMedium,
              ),
            ],
          ),
        ),
      );
}

/// Datum und Uhrzeit der Pruefung: ein Datumsknopf, Schnellwahl-Chips fuer die Uhrzeit, "Andere" oeffnet den Zeitwaehler.
class WhenPicker extends StatelessWidget {
  final DateTime? date;
  final TimeOfDay time;
  final ValueChanged<DateTime> onDate;
  final ValueChanged<TimeOfDay> onTime;

  const WhenPicker({
    super.key,
    required this.date,
    required this.time,
    required this.onDate,
    required this.onTime,
  });

  String _formatTime(TimeOfDay t) =>
      '${t.hour.toString().padLeft(2, '0')}:${t.minute.toString().padLeft(2, '0')}';

  TimeOfDay _parseTime(String s) {
    final parts = s.split(':');
    return TimeOfDay(
      hour: int.tryParse(parts[0]) ?? 8,
      minute: parts.length > 1 ? (int.tryParse(parts[1]) ?? 0) : 0,
    );
  }

  @override
  Widget build(BuildContext context) {
    final dateFmt = DateFormat('EEEE, d. MMMM yyyy', 'de_DE');
    final timeStr = _formatTime(time);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        PrimaryButton(
          date != null ? dateFmt.format(date!) : 'Datum wählen',
          onPressed: () async {
            final today = todayBerlin(DateTime.now());
            final picked = await showDatePicker(
              context: context,
              initialDate: date != null && !date!.isBefore(today)
                  ? date!
                  : today,
              firstDate: today,
              lastDate: DateTime(today.year + 10, 12, 31),
            );
            if (picked != null) onDate(picked);
          },
        ),
        const SizedBox(height: 14),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            for (final t in quickTimes)
              Pill(
                t,
                selected: timeStr == t,
                onTap: () => onTime(_parseTime(t)),
              ),
            Pill(
              quickTimes.contains(timeStr) ? 'Andere' : timeStr,
              selected: !quickTimes.contains(timeStr),
              onTap: () async {
                final picked = await showTimePicker(
                  context: context,
                  initialTime: time,
                  builder: (context, child) => MediaQuery(
                    data: MediaQuery.of(context)
                        .copyWith(alwaysUse24HourFormat: true),
                    child: child!,
                  ),
                );
                if (picked != null) onTime(picked);
              },
            ),
          ],
        ),
      ],
    );
  }
}
