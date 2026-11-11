import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';
import '../logic/audio.dart';
import '../logic/guard.dart';
import '../logic/topic_icons.dart';
import 'haptics.dart';
import 'theme.dart';

/// Abschlusskappe der App in Akzentfarbe (flache Raute, Sockel-Bogen, Quaste); mit [spinning] pulsiert sie als Ladeanzeige.
class LearnMark extends StatefulWidget {
  final double size;
  final bool spinning;

  const LearnMark({super.key, this.size = 64, this.spinning = false});

  @override
  State<LearnMark> createState() => _LearnMarkState();
}

class _LearnMarkState extends State<LearnMark>
    with SingleTickerProviderStateMixin {
  late final AnimationController _ctrl = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 900),
  );
  late final Animation<double> _pulse = Tween<double>(begin: 0.9, end: 1.08)
      .animate(CurvedAnimation(parent: _ctrl, curve: Curves.fastOutSlowIn));

  @override
  void initState() {
    super.initState();
    if (widget.spinning) _ctrl.repeat(reverse: true);
  }

  @override
  void didUpdateWidget(covariant LearnMark oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.spinning && !_ctrl.isAnimating) {
      _ctrl.repeat(reverse: true);
    } else if (!widget.spinning && _ctrl.isAnimating) {
      _ctrl.stop();
    }
  }

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
        animation: _ctrl,
        builder: (_, __) => CustomPaint(
          size: Size.square(widget.size),
          painter: _LearnMarkPainter(widget.spinning ? _pulse.value : 1.0),
        ),
      );
}

class _LearnMarkPainter extends CustomPainter {
  final double pulse;
  const _LearnMarkPainter(this.pulse);

  @override
  void paint(Canvas canvas, Size size) {
    final u = size.width / 24.0;
    final paint = Paint()
      ..color = AppColors.accent
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.4 * u
      ..strokeCap = StrokeCap.round
      ..strokeJoin = StrokeJoin.round;

    canvas.save();
    canvas.translate(12 * u, 12 * u);
    canvas.scale(pulse, pulse);
    canvas.translate(-12 * u, -12 * u);

    // Kappe: flache Raute
    final cap = Path()
      ..moveTo(12 * u, 4.5 * u)
      ..lineTo(22 * u, 9 * u)
      ..lineTo(12 * u, 13.5 * u)
      ..lineTo(2 * u, 9 * u)
      ..close();
    canvas.drawPath(cap, paint);

    // Sockel: Bogen unter der Raute
    final base = Path()
      ..moveTo(6 * u, 11.5 * u)
      ..lineTo(6 * u, 15 * u)
      ..cubicTo(8.5 * u, 17.5 * u, 15.5 * u, 17.5 * u, 18 * u, 15 * u)
      ..lineTo(18 * u, 11.5 * u);
    canvas.drawPath(base, paint);

    // Quaste: Linie mit kleinem Kreis
    canvas.drawLine(Offset(22 * u, 9 * u), Offset(22 * u, 15 * u), paint);
    canvas.drawCircle(
      Offset(22 * u, 16.5 * u),
      1.3 * u,
      Paint()..color = AppColors.accent,
    );
    canvas.restore();
  }

  @override
  bool shouldRepaint(covariant _LearnMarkPainter oldDelegate) =>
      oldDelegate.pulse != pulse;
}

/// Thema-Icon, nackt: kein Kreis, kein Rand, kein Hintergrund; Farbe wie der Text daneben.
/// Vektor-SVG `assets/icons/ic_topic_<schluessel>.svg` (Material Icons, Apache 2.0); unbekannte oder fehlende Schluessel ergeben graduation.
class TopicIcon extends StatelessWidget {
  final String? iconKey;
  final double size;
  final Color? color;

  const TopicIcon(
    this.iconKey, {
    super.key,
    required this.size,
    this.color,
  });

  @override
  Widget build(BuildContext context) {
    final k = normalizeIcon(iconKey);
    final tint = color ?? IconTheme.of(context).color ?? Colors.white;
    return SvgPicture.asset(
      'assets/icons/ic_topic_$k.svg',
      width: size,
      height: size,
      colorFilter: ColorFilter.mode(tint, BlendMode.srcIn),
      placeholderBuilder: (_) => LearnMark(size: size),
    );
  }
}

/// Zentraler Schliessen-Knopf ("✕" oder "Zurück"): Treffflaeche mindestens 48 dp, mit Mehrfach-Tap-Schutz.
class CloseButton extends StatefulWidget {
  final VoidCallback onPressed;
  final String label;

  const CloseButton({
    super.key,
    required this.onPressed,
    this.label = '✕',
  });

  @override
  State<CloseButton> createState() => _CloseButtonState();
}

class _CloseButtonState extends State<CloseButton> {
  int _last = 0;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context).textTheme;
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: () {
        final now = DateTime.now().millisecondsSinceEpoch;
        if (acceptClick(_last, now)) {
          _last = now;
          Haptics.instance.tick();
          widget.onPressed();
        }
      },
      child: ConstrainedBox(
        constraints: const BoxConstraints(minWidth: 48, minHeight: 48),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12),
          child: Center(
            child: Text(
              widget.label,
              style: (widget.label == '✕' ? theme.titleLarge : theme.titleSmall)
                  ?.copyWith(color: AppColors.muted),
            ),
          ),
        ),
      ),
    );
  }
}

/// Einfacher Play-/Pause-Knopf oben rechts: Pause beim Spielen, Play sonst. Kein Spinner, kein Stumm-Konzept.
class PlayPauseButton extends StatelessWidget {
  final PlayState state;
  final VoidCallback onPressed;

  const PlayPauseButton({
    super.key,
    required this.state,
    required this.onPressed,
  });

  @override
  Widget build(BuildContext context) {
    final kind = playButtonKind(state);
    return GestureDetector(
      onTap: () {
        Haptics.instance.tick();
        onPressed();
      },
      child: Container(
        width: 40,
        height: 40,
        decoration: const BoxDecoration(
          color: AppColors.cardHigh,
          shape: BoxShape.circle,
        ),
        alignment: Alignment.center,
        child: CustomPaint(
          size: const Size.square(16),
          painter: _PlayPausePainter(kind),
        ),
      ),
    );
  }
}

class _PlayPausePainter extends CustomPainter {
  final PlayButtonKind kind;
  const _PlayPausePainter(this.kind);

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()..color = Colors.white;
    switch (kind) {
      case PlayButtonKind.pause:
        final w = size.width * 0.3;
        const r = Radius.circular(2);
        canvas.drawRRect(
          RRect.fromRectAndRadius(
            Rect.fromLTWH(size.width * 0.12, 0, w, size.height),
            r,
          ),
          paint,
        );
        canvas.drawRRect(
          RRect.fromRectAndRadius(
            Rect.fromLTWH(size.width * 0.58, 0, w, size.height),
            r,
          ),
          paint,
        );
      case PlayButtonKind.play:
        final path = Path()
          ..moveTo(size.width * 0.15, 0)
          ..lineTo(size.width, size.height / 2)
          ..lineTo(size.width * 0.15, size.height)
          ..close();
        canvas.drawPath(path, paint);
    }
  }

  @override
  bool shouldRepaint(covariant _PlayPausePainter oldDelegate) =>
      oldDelegate.kind != kind;
}

const _shimmerBase = Color(0xFF161616);
const _shimmerHighlight = Color(0xFF262626);

/// Soft gradient sweep used as a loading placeholder fill.
class SkeletonBox extends StatefulWidget {
  final double? width;
  final double? height;
  final BorderRadius borderRadius;
  final BoxShape shape;

  const SkeletonBox({
    super.key,
    this.width,
    this.height,
    this.borderRadius = const BorderRadius.all(Radius.circular(12)),
    this.shape = BoxShape.rectangle,
  });

  @override
  State<SkeletonBox> createState() => _SkeletonBoxState();
}

class _SkeletonBoxState extends State<SkeletonBox>
    with SingleTickerProviderStateMixin {
  late final AnimationController _ctrl = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 1500),
  )..repeat();

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
        animation: _ctrl,
        builder: (_, __) => Container(
          width: widget.width,
          height: widget.height,
          clipBehavior: Clip.antiAlias,
          decoration: BoxDecoration(
            shape: widget.shape,
            borderRadius:
                widget.shape == BoxShape.circle ? null : widget.borderRadius,
          ),
          child: CustomPaint(painter: _ShimmerPainter(_ctrl.value)),
        ),
      );
}

class _ShimmerPainter extends CustomPainter {
  final double progress;
  const _ShimmerPainter(this.progress);

  @override
  void paint(Canvas canvas, Size size) {
    final w = size.width;
    final start = -w + progress * w * 2.2;
    final rect = Offset.zero & size;
    final paint = Paint()
      ..shader = const LinearGradient(
        colors: [_shimmerBase, _shimmerHighlight, _shimmerBase],
      ).createShader(
        Rect.fromPoints(
          Offset(start, 0),
          Offset(start + w, size.height * 0.4),
        ),
      );
    canvas.drawRect(rect, paint);
  }

  @override
  bool shouldRepaint(covariant _ShimmerPainter oldDelegate) =>
      oldDelegate.progress != progress;
}

class SkeletonLine extends StatelessWidget {
  final double width;
  final double height;

  const SkeletonLine(this.width, {super.key, this.height = 14});

  @override
  Widget build(BuildContext context) => SkeletonBox(
        width: width,
        height: height,
        borderRadius: BorderRadius.circular(8),
      );
}

class SkeletonCard extends StatelessWidget {
  final double height;

  const SkeletonCard(this.height, {super.key});

  @override
  Widget build(BuildContext context) => SkeletonBox(
        width: double.infinity,
        height: height,
        borderRadius: BorderRadius.circular(28),
      );
}
