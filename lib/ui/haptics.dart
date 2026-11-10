import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// Semantik der Haptik.
enum HapticKind {
  /// Leichter Tick: Taps auf Kacheln, Buttons, Tabs, Zeilen; Oeffnen von Sheets/Dialogen.
  tick,

  /// Erfolg (gespeichert, hochgeladen, beigetreten ...).
  confirm,

  /// Fehler / Abbruch.
  reject,

  /// Long-Press erkannt.
  longPress,

  /// Ende einer Geste (Wegwischen, Rueckgaengig, Loeschen).
  gestureEnd,

  /// Rasten beim Scrubben / Werte-Auswahl.
  segment,
}

/// Zentrale Haptik ueber Flutter HapticFeedback (funktioniert auf iOS Taptic Engine & Android).
class Haptics {
  static final Haptics instance = Haptics();

  bool enabled = true;
  int _lastReject = 0;

  void perform(HapticKind kind, {bool force = false}) {
    if (!enabled && !force) return;
    if (kind == HapticKind.reject) {
      final now = DateTime.now().millisecondsSinceEpoch;
      if (now - _lastReject < 600) return;
      _lastReject = now;
    }
    switch (kind) {
      case HapticKind.tick:
        unawaited(HapticFeedback.lightImpact());
      case HapticKind.confirm:
        unawaited(HapticFeedback.mediumImpact());
      case HapticKind.reject:
        unawaited(HapticFeedback.heavyImpact());
      case HapticKind.longPress:
        unawaited(HapticFeedback.heavyImpact());
      case HapticKind.gestureEnd:
        unawaited(HapticFeedback.lightImpact());
      case HapticKind.segment:
        unawaited(HapticFeedback.selectionClick());
    }
  }

  /// Haptik-Muster fuer richtige Antworten und Feiern: 0 = kurzer Doppelklick, 1 = Serie, 2 = grosse Serie, 3 = Rundenabschluss.
  Future<void> celebrate(int level) async {
    if (!enabled) return;
    final steps = switch (level) {
      0 => const [0, 50],
      1 => const [0, 50, 55],
      2 => const [0, 50, 55, 65],
      _ => const [0, 55, 60, 75],
    };
    for (var i = 0; i < steps.length; i++) {
      if (steps[i] > 0) {
        await Future<void>.delayed(Duration(milliseconds: steps[i]));
      }
      if (i == steps.length - 1) {
        await HapticFeedback.mediumImpact();
      } else {
        await HapticFeedback.lightImpact();
      }
    }
  }

  void tick() => perform(HapticKind.tick);
  void confirm() => perform(HapticKind.confirm);
  void reject() => perform(HapticKind.reject);
  void longPress() => perform(HapticKind.longPress);
  void gestureEnd() => perform(HapticKind.gestureEnd);
  void segment() => perform(HapticKind.segment);
}

class HapticTextButton extends StatelessWidget {
  final VoidCallback onPressed;
  final Widget child;

  const HapticTextButton({
    super.key,
    required this.onPressed,
    required this.child,
  });

  @override
  Widget build(BuildContext context) => TextButton(
        onPressed: () {
          Haptics.instance.tick();
          onPressed();
        },
        child: child,
      );
}
