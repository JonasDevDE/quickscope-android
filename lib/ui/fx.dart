import 'dart:async';
import 'dart:math' as math;
import 'package:flutter/material.dart';
import '../logic/feedback.dart';
import '../session_view_model.dart';
import 'theme.dart';

const _palette = [
  AppColors.accent,
  Colors.white,
  AppColors.warn,
  AppColors.accent,
];

/// Vorberechnete Partikel (Winkel, Tempo, Groesse, Farbe): pro Zeichnen keine Allokationen.
class _Particles {
  final List<double> angle;
  final List<double> speed;
  final List<double> size;
  final List<int> color;
  final List<double> phase;

  factory _Particles(int count, int seed, {required bool upward}) {
    final r = math.Random(seed);
    return _Particles._(
      angle: List<double>.generate(
        count,
        (_) => upward
            ? (-160.0 + r.nextDouble() * 140.0) * (math.pi / 180.0)
            : r.nextDouble() * 6.2832,
      ),
      speed: List<double>.generate(count, (_) => 0.45 + r.nextDouble() * 0.55),
      size: List<double>.generate(count, (_) => 2.5 + r.nextDouble() * 3.5),
      color: List<int>.generate(count, (_) => r.nextInt(_palette.length)),
      phase: List<double>.generate(count, (_) => r.nextDouble()),
    );
  }

  const _Particles._({
    required this.angle,
    required this.speed,
    required this.size,
    required this.color,
    required this.phase,
  });
}

class _BurstPainter extends CustomPainter {
  final double t;
  final _Particles particles;

  const _BurstPainter(this.t, this.particles);

  @override
  void paint(Canvas canvas, Size size) {
    final reach = size.shortestSide * 0.55;
    final cx = size.width / 2;
    final cy = size.height * 0.42;
    final ease = 1.0 - (1.0 - t) * (1.0 - t);
    final alpha = math.pow((1.0 - t).clamp(0.0, 1.0), 0.7).toDouble();
    final paint = Paint();
    for (var i = 0; i < particles.angle.length; i++) {
      final d = particles.speed[i] * reach * ease;
      final x = cx + math.cos(particles.angle[i]) * d;
      final y = cy + math.sin(particles.angle[i]) * d + t * t * reach * 0.25;
      paint.color = _palette[particles.color[i]].withValues(alpha: alpha);
      canvas.drawCircle(
        Offset(x, y),
        particles.size[i] * (1.0 - t * 0.4),
        paint,
      );
    }
  }

  @override
  bool shouldRepaint(covariant _BurstPainter oldDelegate) =>
      oldDelegate.t != t || oldDelegate.particles != particles;
}

/// Konfetti: fallende Plaettchen ueber die ganze Breite, kurz.
class Confetti extends StatefulWidget {
  final Object? trigger;

  const Confetti({super.key, this.trigger});

  @override
  State<Confetti> createState() => _ConfettiState();
}

class _ConfettiState extends State<Confetti>
    with SingleTickerProviderStateMixin {
  late final AnimationController _ctrl = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 2400),
  )..forward();
  final _particles = _Particles(FeedbackTuning.confetti, 7, upward: false);

  @override
  void didUpdateWidget(covariant Confetti oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.trigger != widget.trigger) {
      _ctrl.forward(from: 0);
    }
  }

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => IgnorePointer(
        child: AnimatedBuilder(
          animation: _ctrl,
          builder: (_, __) => CustomPaint(
            size: Size.infinite,
            painter: _ConfettiPainter(_ctrl.value, _particles),
          ),
        ),
      );
}

class _ConfettiPainter extends CustomPainter {
  final double t;
  final _Particles particles;

  const _ConfettiPainter(this.t, this.particles);

  @override
  void paint(Canvas canvas, Size size) {
    if (t >= 1.0) return;
    final paint = Paint();
    for (var i = 0; i < particles.angle.length; i++) {
      final x = particles.phase[i] * size.width +
          math.sin(t * 9.0 + particles.phase[i] * 20.0) * 18.0;
      final y =
          -20.0 + (t * (0.6 + particles.speed[i]) * (size.height + 40.0));
      final a = (1.0 - t * t).clamp(0.0, 1.0);
      final w = particles.size[i];
      paint.color = _palette[particles.color[i]].withValues(alpha: a);
      canvas.drawRRect(
        RRect.fromRectAndRadius(
          Rect.fromLTWH(x, y, w * 1.6, w),
          Radius.circular(w / 3),
        ),
        paint,
      );
    }
  }

  @override
  bool shouldRepaint(covariant _ConfettiPainter oldDelegate) =>
      oldDelegate.t != t;
}

/// Feier bei einer richtigen Antwort: gruener Puls, Haken mit Pop, Partikel-Burst, schwebende Punkte, bei Meilensteinen
/// zusaetzlich "N in Folge", beim Comeback ein leiser Zusatz. Beruehrungen gehen durch.
class CorrectFx extends StatefulWidget {
  final Fx fx;

  const CorrectFx(this.fx, {super.key});

  @override
  State<CorrectFx> createState() => _CorrectFxState();
}

class _CorrectFxState extends State<CorrectFx> with TickerProviderStateMixin {
  late final AnimationController _pCtrl = AnimationController(vsync: this);
  late final AnimationController _popCtrl = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 420),
  );
  late _Particles _particles;

  @override
  void initState() {
    super.initState();
    _start();
  }

  @override
  void didUpdateWidget(covariant CorrectFx oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.fx.id != widget.fx.id) _start();
  }

  void _start() {
    final outcome = widget.fx.outcome;
    final count = switch (outcome.celebration) {
      Celebration.small => FeedbackTuning.burstSmall,
      Celebration.medium => FeedbackTuning.burstMedium,
      Celebration.big => FeedbackTuning.burstBig,
    };
    _particles = _Particles(count, widget.fx.id, upward: true);
    final ms = switch (outcome.celebration) {
      Celebration.big => 1800,
      Celebration.medium => 1400,
      Celebration.small => 900,
    };
    _pCtrl
      ..duration = Duration(milliseconds: ms)
      ..forward(from: 0);
    _popCtrl.forward(from: 0);
  }

  @override
  void dispose() {
    _pCtrl.dispose();
    _popCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final outcome = widget.fx.outcome;
    final milestone = outcome.celebration != Celebration.small;
    final theme = Theme.of(context).textTheme;

    return IgnorePointer(
      child: AnimatedBuilder(
        animation: Listenable.merge([_pCtrl, _popCtrl]),
        builder: (_, __) {
          final p = _pCtrl.value;
          final pop = Curves.elasticOut.transform(_popCtrl.value);
          return Stack(
            fit: StackFit.expand,
            alignment: Alignment.center,
            children: [
              CustomPaint(painter: _BurstPainter(p, _particles)),
              Center(
                child: Opacity(
                  opacity: (1.0 - p * p).clamp(0.0, 1.0),
                  child: Transform.scale(
                    scale: pop,
                    child: const CustomPaint(
                      size: Size.square(72),
                      painter: _CheckPainter(),
                    ),
                  ),
                ),
              ),
              if (outcome.xp > 0)
                Center(
                  child: Transform.translate(
                    offset: Offset(0, -(48.0 + 110.0 * p)),
                    child: Opacity(
                      opacity: (1.0 - p * p * p).clamp(0.0, 1.0),
                      child: Text(
                        '+${outcome.xp}',
                        style: theme.headlineMedium
                            ?.copyWith(color: AppColors.accent),
                      ),
                    ),
                  ),
                ),
              if (milestone)
                Center(
                  child: Transform.translate(
                    offset: Offset(0, 90.0 - 30.0 * p),
                    child: Transform.scale(
                      scale: 0.7 + 0.3 * pop,
                      child: Opacity(
                        opacity: (1.0 - p * p).clamp(0.0, 1.0),
                        child: Text(
                          '${outcome.streak} in Folge',
                          style: theme.displaySmall
                              ?.copyWith(color: Colors.white),
                        ),
                      ),
                    ),
                  ),
                )
              else if (outcome.comeback)
                Center(
                  child: Transform.translate(
                    offset: const Offset(0, 64),
                    child: Opacity(
                      opacity: (1.0 - p).clamp(0.0, 1.0),
                      child: Text(
                        'Comeback',
                        style: theme.titleMedium
                            ?.copyWith(color: AppColors.accent),
                      ),
                    ),
                  ),
                ),
            ],
          );
        },
      ),
    );
  }
}

class _CheckPainter extends CustomPainter {
  const _CheckPainter();

  @override
  void paint(Canvas canvas, Size size) {
    final u = size.width / 24.0;
    canvas.drawCircle(
      Offset(size.width / 2, size.height / 2),
      11.0 * u,
      Paint()..color = AppColors.accent,
    );
    final check = Path()
      ..moveTo(7.0 * u, 12.5 * u)
      ..lineTo(10.5 * u, 16.0 * u)
      ..lineTo(17.0 * u, 8.5 * u);
    canvas.drawPath(
      check,
      Paint()
        ..color = Colors.black
        ..style = PaintingStyle.stroke
        ..strokeWidth = 2.2 * u
        ..strokeCap = StrokeCap.round
        ..strokeJoin = StrokeJoin.round,
    );
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}

/// Vollflaechiger Farbschimmer ueber dem ganzen Bildschirm, klickdurchlaessig:
/// gruen bei richtig (kurz, Alpha max 0.16), sehr sanftes Orange bei falsch. Beeinflusst das Layout nie.
class FullTint extends StatefulWidget {
  final Fx fx;

  const FullTint(this.fx, {super.key});

  @override
  State<FullTint> createState() => _FullTintState();
}

class _FullTintState extends State<FullTint>
    with SingleTickerProviderStateMixin {
  late final AnimationController _ctrl = AnimationController(vsync: this);

  @override
  void initState() {
    super.initState();
    _start();
  }

  @override
  void didUpdateWidget(covariant FullTint oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.fx.id != widget.fx.id) _start();
  }

  void _start() {
    _ctrl
      ..duration = Duration(
        milliseconds: widget.fx.outcome.correct ? 380 : 450,
      )
      ..forward(from: 0);
  }

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final correct = widget.fx.outcome.correct;
    final peak = correct ? 0.16 : 0.07;
    final color = correct ? AppColors.accent : AppColors.soft;
    return IgnorePointer(
      child: AnimatedBuilder(
        animation: _ctrl,
        builder: (_, __) {
          final alpha =
              (peak * math.sin(math.pi * _ctrl.value)).clamp(0.0, 1.0);
          return ColoredBox(
            color: color.withValues(alpha: alpha),
            child: const SizedBox.expand(),
          );
        },
      ),
    );
  }
}

/// Banner `"Thema sitzt: <Titel>"` mit kleinem Burst; verschwindet nach 2,5 s von selbst.
class SitsBanner extends StatefulWidget {
  final Sits sits;

  const SitsBanner(this.sits, {super.key});

  @override
  State<SitsBanner> createState() => _SitsBannerState();
}

class _SitsBannerState extends State<SitsBanner>
    with SingleTickerProviderStateMixin {
  late final AnimationController _pCtrl = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 1500),
  );
  late _Particles _particles;
  bool _visible = true;
  Timer? _hideTimer;

  @override
  void initState() {
    super.initState();
    _start();
  }

  @override
  void didUpdateWidget(covariant SitsBanner oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.sits.id != widget.sits.id) _start();
  }

  void _start() {
    _hideTimer?.cancel();
    _visible = true;
    _particles = _Particles(
      FeedbackTuning.burstSmall,
      widget.sits.id * 31,
      upward: true,
    );
    _pCtrl.forward(from: 0);
    _hideTimer = Timer(const Duration(milliseconds: 2500), () {
      if (mounted) setState(() => _visible = false);
    });
  }

  @override
  void dispose() {
    _hideTimer?.cancel();
    _pCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => IgnorePointer(
        child: TweenAnimationBuilder<double>(
          tween: Tween<double>(begin: 0, end: _visible ? 1 : 0),
          duration: const Duration(milliseconds: 280),
          builder: (context, slide, _) {
            if (slide <= 0.01) return const SizedBox.shrink();
            return SizedBox(
              width: double.infinity,
              height: 110,
              child: Stack(
                alignment: Alignment.topCenter,
                children: [
                  Positioned.fill(
                    child: AnimatedBuilder(
                      animation: _pCtrl,
                      builder: (_, __) => CustomPaint(
                        painter: _BurstPainter(_pCtrl.value, _particles),
                      ),
                    ),
                  ),
                  Transform.translate(
                    offset: Offset(0, -40.0 * (1.0 - slide)),
                    child: Opacity(
                      opacity: slide,
                      child: Container(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 18,
                          vertical: 10,
                        ),
                        decoration: BoxDecoration(
                          color: AppColors.card,
                          borderRadius: BorderRadius.circular(50),
                        ),
                        child: Text(
                          'Thema sitzt: ${widget.sits.title}',
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: Theme.of(context).textTheme.titleSmall,
                        ),
                      ),
                    ),
                  ),
                ],
              ),
            );
          },
        ),
      );
}

/// Serienzaehler "5 in Folge" mit Blitz, animiert hochzaehlend; erst ab 2 in Folge sichtbar.
class StreakChip extends StatelessWidget {
  final int streak;

  const StreakChip(this.streak, {super.key});

  @override
  Widget build(BuildContext context) {
    if (streak < FeedbackTuning.streakVisibleFrom) {
      return const SizedBox.shrink();
    }
    return TweenAnimationBuilder<double>(
      key: ValueKey(streak),
      tween: Tween<double>(begin: 1.3, end: 1.0),
      duration: const Duration(milliseconds: 350),
      curve: Curves.elasticOut,
      builder: (context, scale, _) => Transform.scale(
        scale: scale,
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            const CustomPaint(size: Size.square(14), painter: _BoltPainter()),
            const SizedBox(width: 4),
            Text(
              '$streak in Folge',
              style: Theme.of(context).textTheme.labelLarge?.copyWith(
                    fontSize: 14,
                    color: AppColors.accent,
                  ),
            ),
          ],
        ),
      ),
    );
  }
}

class _BoltPainter extends CustomPainter {
  const _BoltPainter();

  @override
  void paint(Canvas canvas, Size size) {
    final u = size.width / 14.0;
    final path = Path()
      ..moveTo(8.5 * u, 0)
      ..lineTo(2.0 * u, 8.0 * u)
      ..lineTo(6.5 * u, 8.0 * u)
      ..lineTo(5.5 * u, 14.0 * u)
      ..lineTo(12.0 * u, 5.5 * u)
      ..lineTo(7.5 * u, 5.5 * u)
      ..close();
    canvas.drawPath(path, Paint()..color = AppColors.accent);
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}

/// Fortschrittsbalken mit weichem Leuchten an der Fuellung.
class GlowProgress extends StatelessWidget {
  final double progress;

  const GlowProgress(this.progress, {super.key});

  @override
  Widget build(BuildContext context) => TweenAnimationBuilder<double>(
        tween: Tween<double>(begin: 0, end: progress.clamp(0.0, 1.0)),
        duration: const Duration(milliseconds: 350),
        builder: (_, shown, __) => SizedBox(
          height: 14,
          width: double.infinity,
          child: CustomPaint(painter: _GlowProgressPainter(shown)),
        ),
      );
}

class _GlowProgressPainter extends CustomPainter {
  final double shown;
  const _GlowProgressPainter(this.shown);

  @override
  void paint(Canvas canvas, Size size) {
    const h = 6.0;
    final top = (size.height - h) / 2;
    const r = Radius.circular(h / 2);
    canvas.drawRRect(
      RRect.fromRectAndRadius(Rect.fromLTWH(0, top, size.width, h), r),
      Paint()..color = AppColors.cardHigh,
    );
    final w = size.width * shown;
    if (w > 0) {
      canvas.drawRRect(
        RRect.fromRectAndRadius(
          Rect.fromLTWH(0, top - 4, w, h + 8),
          const Radius.circular((h + 8) / 2),
        ),
        Paint()..color = AppColors.accent.withValues(alpha: 0.22),
      );
      canvas.drawRRect(
        RRect.fromRectAndRadius(Rect.fromLTWH(0, top, w, h), r),
        Paint()..color = AppColors.accent,
      );
    }
  }

  @override
  bool shouldRepaint(covariant _GlowProgressPainter oldDelegate) =>
      oldDelegate.shown != shown;
}
