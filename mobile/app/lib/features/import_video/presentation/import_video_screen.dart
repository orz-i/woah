import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../app/theme.dart';
import '../../protection_editor/presentation/protection_editor_screen.dart';
import '../domain/video_import_state.dart';
import 'import_video_controller.dart';
import 'woah_easter_egg_screen.dart';

class ImportVideoScreen extends ConsumerStatefulWidget {
  const ImportVideoScreen({super.key});

  @override
  ConsumerState<ImportVideoScreen> createState() => _ImportVideoScreenState();
}

class _ImportVideoScreenState extends ConsumerState<ImportVideoScreen>
    with TickerProviderStateMixin {
  static const _buddyFrames = <String>[
    'assets/home/buddy_motion/buddy_idle.png',
    'assets/home/buddy_motion/buddy_step.png',
    'assets/home/buddy_motion/buddy_sway.png',
    'assets/home/buddy_motion/buddy_wave.png',
    'assets/home/buddy_motion/buddy_jump.png',
  ];

  late final AnimationController _ambientController;
  late final AnimationController _buddyController;
  bool _didPrecacheBuddyFrames = false;

  @override
  void initState() {
    super.initState();
    _ambientController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 3600),
    )..repeat(reverse: true);
    _buddyController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 4800),
    )..repeat();
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_didPrecacheBuddyFrames) return;
    _didPrecacheBuddyFrames = true;
    for (final asset in _buddyFrames) {
      precacheImage(AssetImage(asset), context);
    }
  }

  @override
  void dispose() {
    _ambientController.dispose();
    _buddyController.dispose();
    super.dispose();
  }

  Future<void> _pickVideoAndContinue() async {
    HapticFeedback.mediumImpact();
    final controller = ref.read(importVideoControllerProvider.notifier);
    await controller.pickAndProbeVideo();
    if (!mounted) return;

    final project = controller.createProject();
    if (project == null) return;

    await context.push(
      '/protection_editor',
      extra: ProtectionEditorArgs(project: project),
    );
    if (!mounted) return;
    controller.reset();
  }

  void _openEasterEgg() {
    HapticFeedback.lightImpact();
    Navigator.of(context).push(
      MaterialPageRoute<void>(
        fullscreenDialog: true,
        builder: (_) => const WoahEasterEggScreen(),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(importVideoControllerProvider);
    final isBusy =
        state.status == VideoImportStatus.picking ||
        state.status == VideoImportStatus.probing;

    final actionLabel = switch (state.status) {
      VideoImportStatus.picking => '正在导入舞段…',
      VideoImportStatus.probing => '正在读取舞段…',
      _ => '选择视频',
    };

    return AnnotatedRegion<SystemUiOverlayStyle>(
      value: const SystemUiOverlayStyle(
        statusBarColor: Colors.transparent,
        statusBarIconBrightness: Brightness.light,
        statusBarBrightness: Brightness.dark,
        systemNavigationBarColor: _HomePalette.background,
        systemNavigationBarIconBrightness: Brightness.light,
        systemNavigationBarDividerColor: Colors.transparent,
        systemStatusBarContrastEnforced: false,
        systemNavigationBarContrastEnforced: false,
      ),
      child: Scaffold(
        backgroundColor: _HomePalette.background,
        body: Stack(
          children: [
            Positioned.fill(
              child: _HomeNightBackground(controller: _buddyController),
            ),
            SafeArea(
              child: LayoutBuilder(
                builder: (context, constraints) {
                  final compact = constraints.maxHeight < 720;
                  return SingleChildScrollView(
                    physics: const ClampingScrollPhysics(),
                    padding: EdgeInsets.fromLTRB(
                      22,
                      compact ? 18 : 24,
                      22,
                      compact ? 18 : 24,
                    ),
                    child: ConstrainedBox(
                      constraints: BoxConstraints(
                        minHeight: constraints.maxHeight - (compact ? 36 : 48),
                      ),
                      child: Column(
                        children: [
                          _HomeBrand(
                            compact: compact,
                            onLongPress: _openEasterEgg,
                          ),
                          SizedBox(height: compact ? 10 : 16),
                          _FeatureOrbitStage(
                            compact: compact,
                            controller: _ambientController,
                            isBusy: isBusy,
                            actionLabel: actionLabel,
                            onPrimaryTap: isBusy ? null : _pickVideoAndContinue,
                          ),
                          if (state.errorMessage != null) ...[
                            SizedBox(height: compact ? 8 : 12),
                            _ErrorNotice(message: state.errorMessage!),
                          ],
                        ],
                      ),
                    ),
                  );
                },
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _HomePalette {
  static const background = Color(0xFF050A18);
  static const backgroundMid = Color(0xFF0A1638);
  static const textPrimary = Color(0xFFF7F7F8);
  static const textSecondary = Color(0xFFC2C7D3);
  static const gold = Color(0xFFFFD76A);
  static const indigo = Color(0xFF2A248B);
  static const orbit = Color(0x66F9D778);
}

class _HomeNightBackground extends StatelessWidget {
  final Animation<double> controller;

  const _HomeNightBackground({required this.controller});

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final sceneHeight = (constraints.maxHeight * 0.43).clamp(285.0, 380.0);
        return Stack(
          fit: StackFit.expand,
          children: [
            const DecoratedBox(
              decoration: BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.topCenter,
                  end: Alignment.bottomCenter,
                  colors: [
                    _HomePalette.background,
                    _HomePalette.backgroundMid,
                    Color(0xFF101A3A),
                    _HomePalette.background,
                  ],
                  stops: [0.0, 0.32, 0.68, 1.0],
                ),
              ),
            ),
            const CustomPaint(painter: _HomeStarFieldPainter()),
            Positioned(
              left: 12,
              right: 12,
              bottom: -2,
              height: sceneHeight,
              child: Stack(
                alignment: Alignment.bottomCenter,
                children: [
                  Positioned(
                    bottom: 24,
                    child: Container(
                      width: constraints.maxWidth * 0.72,
                      height: 66,
                      decoration: BoxDecoration(
                        borderRadius: BorderRadius.circular(999),
                        border: Border.all(
                          color: _HomePalette.gold.withAlpha(120),
                          width: 1.4,
                        ),
                        boxShadow: [
                          BoxShadow(
                            color: _HomePalette.gold.withAlpha(42),
                            blurRadius: 28,
                            spreadRadius: 4,
                          ),
                        ],
                      ),
                    ),
                  ),
                  _BuddyMotionSequence(
                    controller: controller,
                    extent: sceneHeight,
                  ),
                ],
              ),
            ),
          ],
        );
      },
    );
  }
}

class _BuddyMotionSequence extends StatelessWidget {
  final Animation<double> controller;
  final double extent;

  const _BuddyMotionSequence({required this.controller, required this.extent});

  int _frameIndex(double progress) {
    if (progress < 0.42) return 0;
    if (progress < 0.54) return 1;
    if (progress < 0.66) return 2;
    if (progress < 0.80) return 3;
    if (progress < 0.92) return 4;
    return 0;
  }

  Offset _offsetFor(int index) {
    return switch (index) {
      1 => const Offset(-5, -5),
      2 => const Offset(6, -6),
      3 => const Offset(3, -8),
      4 => const Offset(0, -18),
      _ => Offset.zero,
    };
  }

  double _turnFor(int index) {
    return switch (index) {
      1 => -0.025,
      2 => 0.032,
      3 => 0.018,
      4 => -0.012,
      _ => 0,
    };
  }

  double _scaleFor(int index) {
    return switch (index) {
      4 => 1.035,
      1 || 2 || 3 => 1.012,
      _ => 1,
    };
  }

  @override
  Widget build(BuildContext context) {
    final reduceMotion = MediaQuery.disableAnimationsOf(context);

    return SizedBox(
      key: const ValueKey('home-buddy'),
      width: extent,
      height: extent,
      child: AnimatedBuilder(
        animation: controller,
        builder: (context, _) {
          final frameIndex = reduceMotion ? 0 : _frameIndex(controller.value);
          final asset = _ImportVideoScreenState._buddyFrames[frameIndex];
          return Transform.translate(
            offset: reduceMotion ? Offset.zero : _offsetFor(frameIndex),
            child: Transform.rotate(
              angle: reduceMotion ? 0 : _turnFor(frameIndex),
              child: Transform.scale(
                scale: reduceMotion ? 1 : _scaleFor(frameIndex),
                child: AnimatedSwitcher(
                  duration: const Duration(milliseconds: 120),
                  reverseDuration: const Duration(milliseconds: 90),
                  switchInCurve: Curves.easeOut,
                  switchOutCurve: Curves.easeIn,
                  layoutBuilder: (currentChild, previousChildren) {
                    return Stack(
                      alignment: Alignment.center,
                      children: <Widget>[...previousChildren, ?currentChild],
                    );
                  },
                  child: Image.asset(
                    asset,
                    key: ValueKey('home-buddy-frame-$frameIndex'),
                    width: extent,
                    height: extent,
                    fit: BoxFit.contain,
                    filterQuality: FilterQuality.high,
                  ),
                ),
              ),
            ),
          );
        },
      ),
    );
  }
}

class _HomeBrand extends StatelessWidget {
  final bool compact;
  final VoidCallback onLongPress;

  const _HomeBrand({required this.compact, required this.onLongPress});

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      behavior: HitTestBehavior.translucent,
      onLongPress: onLongPress,
      child: Align(
        alignment: Alignment.centerLeft,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 4),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    'Woah',
                    style: TextStyle(
                      color: _HomePalette.textPrimary,
                      fontSize: compact ? 30 : 34,
                      height: 1,
                      fontWeight: FontWeight.w800,
                      letterSpacing: -1.1,
                    ),
                  ),
                  const SizedBox(width: 6),
                  const Icon(
                    Icons.auto_awesome_rounded,
                    color: _HomePalette.gold,
                    size: 19,
                  ),
                ],
              ),
              const SizedBox(height: 9),
              Text(
                '记录每一个闪闪发光的你',
                style: TextStyle(
                  color: _HomePalette.textSecondary,
                  fontSize: compact ? 12 : 13,
                  height: 1.25,
                  fontWeight: FontWeight.w400,
                  letterSpacing: 1.25,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _FeatureOrbitStage extends StatelessWidget {
  final bool compact;
  final Animation<double> controller;
  final bool isBusy;
  final String actionLabel;
  final VoidCallback? onPrimaryTap;

  const _FeatureOrbitStage({
    required this.compact,
    required this.controller,
    required this.isBusy,
    required this.actionLabel,
    required this.onPrimaryTap,
  });

  @override
  Widget build(BuildContext context) {
    final stageHeight = compact ? 430.0 : 500.0;
    final starExtent = compact ? 205.0 : 232.0;
    final reduceMotion = MediaQuery.disableAnimationsOf(context);

    return SizedBox(
      height: stageHeight,
      width: double.infinity,
      child: AnimatedBuilder(
        animation: controller,
        builder: (context, child) {
          final t = reduceMotion
              ? 0.5
              : Curves.easeInOut.transform(controller.value);
          return Stack(
            clipBehavior: Clip.none,
            children: [
              Positioned.fill(
                top: compact ? 8 : 16,
                bottom: compact ? 145 : 170,
                child: CustomPaint(painter: _OrbitPainter(progress: t)),
              ),
              Positioned(
                left: -5,
                top: compact ? 190 : 220,
                child: _FeatureSatellite(
                  key: const ValueKey('future-feature-face-sticker'),
                  icon: Icons.face_retouching_natural_rounded,
                  label: '人脸贴纸',
                  extent: compact ? 68 : 76,
                  drift: Offset(0, -2.5 * t),
                ),
              ),
              Positioned(
                right: -2,
                top: compact ? 116 : 134,
                child: _FeatureSatellite(
                  key: const ValueKey('future-feature-smart-crop'),
                  icon: Icons.crop_rounded,
                  label: '智能裁切',
                  extent: compact ? 72 : 80,
                  drift: Offset(0, 2.0 * t),
                ),
              ),
              Positioned(
                right: 7,
                top: compact ? 220 : 245,
                child: _FeatureSatellite(
                  key: const ValueKey('future-feature-more-tools'),
                  icon: Icons.more_horiz_rounded,
                  label: '更多工具',
                  extent: compact ? 66 : 74,
                  drift: Offset(0, -2.0 * t),
                ),
              ),
              Positioned(
                left: 0,
                right: 0,
                top: compact ? 18 : 28,
                child: Center(
                  child: Transform.scale(
                    scale: 0.99 + (0.016 * t),
                    child: _PrimaryFeatureStar(
                      extent: starExtent,
                      isBusy: isBusy,
                      actionLabel: actionLabel,
                      onTap: onPrimaryTap,
                    ),
                  ),
                ),
              ),
              Positioned(
                left: 36,
                right: 36,
                top: compact ? 255 : 280,
                child: const Text(
                  '智能识别，自动保护隐私，\n让舞蹈自由被记录',
                  textAlign: TextAlign.center,
                  style: TextStyle(
                    color: _HomePalette.textSecondary,
                    fontSize: 12.5,
                    height: 1.55,
                    fontWeight: FontWeight.w400,
                    letterSpacing: 0.25,
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

class _PrimaryFeatureStar extends StatelessWidget {
  final double extent;
  final bool isBusy;
  final String actionLabel;
  final VoidCallback? onTap;

  const _PrimaryFeatureStar({
    required this.extent,
    required this.isBusy,
    required this.actionLabel,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return Semantics(
      button: !isBusy,
      enabled: !isBusy,
      label: actionLabel,
      child: GestureDetector(
        key: const ValueKey('home-primary-star'),
        behavior: HitTestBehavior.opaque,
        onTap: onTap,
        child: SizedBox(
          width: extent,
          height: extent,
          child: Stack(
            alignment: Alignment.center,
            children: [
              Container(
                width: extent * 0.98,
                height: extent * 0.98,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  gradient: RadialGradient(
                    colors: [
                      _HomePalette.gold.withAlpha(80),
                      _HomePalette.gold.withAlpha(26),
                      Colors.transparent,
                    ],
                    stops: const [0.0, 0.55, 1.0],
                  ),
                ),
              ),
              Container(
                width: extent * 0.84,
                height: extent * 0.84,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  boxShadow: [
                    BoxShadow(
                      color: _HomePalette.gold.withAlpha(70),
                      blurRadius: 34,
                      spreadRadius: 7,
                    ),
                  ],
                ),
              ),
              Icon(
                Icons.star_rounded,
                size: extent * 0.88,
                color: _HomePalette.gold,
              ),
              Positioned(
                left: extent * 0.10,
                top: extent * 0.36,
                child: Icon(
                  Icons.auto_awesome_rounded,
                  size: extent * 0.09,
                  color: Colors.white.withAlpha(230),
                ),
              ),
              Positioned(
                right: extent * 0.10,
                top: extent * 0.20,
                child: Icon(
                  Icons.auto_awesome_rounded,
                  size: extent * 0.075,
                  color: Colors.white.withAlpha(210),
                ),
              ),
              Positioned.fill(
                child: Padding(
                  padding: EdgeInsets.only(
                    left: extent * 0.18,
                    right: extent * 0.18,
                    top: extent * 0.20,
                    bottom: extent * 0.18,
                  ),
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      if (isBusy)
                        SizedBox(
                          width: extent * 0.13,
                          height: extent * 0.13,
                          child: const CircularProgressIndicator(
                            strokeWidth: 2.3,
                            color: _HomePalette.indigo,
                          ),
                        )
                      else
                        Icon(
                          Icons.video_camera_back_rounded,
                          color: const Color(0xFF5A4020),
                          size: extent * 0.13,
                        ),
                      SizedBox(height: extent * 0.035),
                      Text(
                        '导入舞段',
                        textAlign: TextAlign.center,
                        style: TextStyle(
                          color: const Color(0xFF513718),
                          fontSize: extent * 0.115,
                          height: 1.05,
                          fontWeight: FontWeight.w800,
                          letterSpacing: -0.5,
                        ),
                      ),
                      SizedBox(height: extent * 0.045),
                      Container(
                        constraints: BoxConstraints(
                          minWidth: extent * 0.55,
                          minHeight: extent * 0.20,
                        ),
                        padding: EdgeInsets.symmetric(
                          horizontal: extent * 0.08,
                          vertical: extent * 0.035,
                        ),
                        decoration: BoxDecoration(
                          color: _HomePalette.indigo,
                          borderRadius: BorderRadius.circular(extent * 0.12),
                          boxShadow: [
                            BoxShadow(
                              color: _HomePalette.indigo.withAlpha(80),
                              blurRadius: 14,
                              spreadRadius: 1,
                            ),
                          ],
                        ),
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          mainAxisAlignment: MainAxisAlignment.center,
                          children: [
                            Flexible(
                              child: Text(
                                actionLabel,
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: TextStyle(
                                  color: Colors.white,
                                  fontSize: extent * 0.065,
                                  height: 1.15,
                                  fontWeight: FontWeight.w700,
                                ),
                              ),
                            ),
                            if (!isBusy) ...[
                              SizedBox(width: extent * 0.025),
                              Icon(
                                Icons.chevron_right_rounded,
                                color: Colors.white,
                                size: extent * 0.095,
                              ),
                            ],
                          ],
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _FeatureSatellite extends StatelessWidget {
  final IconData icon;
  final String label;
  final double extent;
  final Offset drift;

  const _FeatureSatellite({
    super.key,
    required this.icon,
    required this.label,
    required this.extent,
    required this.drift,
  });

  @override
  Widget build(BuildContext context) {
    return Transform.translate(
      offset: drift,
      child: SizedBox(
        width: extent + 24,
        child: Column(
          children: [
            SizedBox(
              width: extent,
              height: extent,
              child: Stack(
                alignment: Alignment.center,
                children: [
                  Container(
                    width: extent,
                    height: extent,
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      gradient: RadialGradient(
                        colors: [
                          _HomePalette.gold.withAlpha(64),
                          _HomePalette.gold.withAlpha(14),
                          Colors.transparent,
                        ],
                      ),
                    ),
                  ),
                  Icon(
                    Icons.star_rounded,
                    size: extent * 0.88,
                    color: _HomePalette.gold,
                  ),
                  Icon(
                    icon,
                    size: extent * 0.28,
                    color: const Color(0xFF5B431D),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 2),
            Text(
              label,
              textAlign: TextAlign.center,
              style: const TextStyle(
                color: _HomePalette.textSecondary,
                fontSize: 11.5,
                height: 1.2,
                fontWeight: FontWeight.w600,
                letterSpacing: 0.2,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _ErrorNotice extends StatelessWidget {
  final String message;

  const _ErrorNotice({required this.message});

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 11),
      decoration: BoxDecoration(
        color: AppTheme.flowSurfaceSoft.withAlpha(238),
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: AppTheme.error.withAlpha(88)),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Icon(
            Icons.error_outline_rounded,
            color: AppTheme.error,
            size: 18,
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              message,
              style: const TextStyle(
                color: AppTheme.flowTextSecondary,
                fontSize: 12,
                height: 1.4,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _OrbitPainter extends CustomPainter {
  final double progress;

  const _OrbitPainter({required this.progress});

  @override
  void paint(Canvas canvas, Size size) {
    final center = Offset(size.width * 0.50, size.height * 0.43);
    final paint = Paint()
      ..color = _HomePalette.orbit
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.0;

    final rect = Rect.fromCenter(
      center: center,
      width: size.width * 0.92,
      height: size.height * 0.50,
    );
    canvas.save();
    canvas.translate(center.dx, center.dy);
    canvas.rotate(-0.22);
    canvas.translate(-center.dx, -center.dy);
    canvas.drawOval(rect, paint);

    final secondary = Rect.fromCenter(
      center: center.translate(0, 10),
      width: size.width * 0.76,
      height: size.height * 0.68,
    );
    final secondaryPaint = Paint()
      ..color = _HomePalette.gold.withValues(alpha: 0.11 + (0.05 * progress))
      ..style = PaintingStyle.stroke
      ..strokeWidth = 0.8;
    canvas.drawOval(secondary, secondaryPaint);
    canvas.restore();

    final sparkPaint = Paint()
      ..color = _HomePalette.gold.withValues(alpha: 0.55 + (0.25 * progress));
    for (final point in <Offset>[
      Offset(size.width * 0.13, size.height * 0.40),
      Offset(size.width * 0.87, size.height * 0.30),
      Offset(size.width * 0.76, size.height * 0.62),
      Offset(size.width * 0.28, size.height * 0.66),
    ]) {
      canvas.drawCircle(point, 1.7, sparkPaint);
    }
  }

  @override
  bool shouldRepaint(covariant _OrbitPainter oldDelegate) =>
      oldDelegate.progress != progress;
}

class _HomeStarFieldPainter extends CustomPainter {
  const _HomeStarFieldPainter();

  static const _points = <_HomeStarPoint>[
    _HomeStarPoint(0.06, 0.06, 0.8, 0.30),
    _HomeStarPoint(0.16, 0.12, 1.0, 0.36),
    _HomeStarPoint(0.30, 0.05, 0.7, 0.22),
    _HomeStarPoint(0.47, 0.11, 0.9, 0.30),
    _HomeStarPoint(0.63, 0.07, 0.7, 0.26),
    _HomeStarPoint(0.86, 0.12, 0.9, 0.36),
    _HomeStarPoint(0.94, 0.23, 0.6, 0.22),
    _HomeStarPoint(0.11, 0.30, 0.6, 0.18),
    _HomeStarPoint(0.73, 0.25, 0.8, 0.22),
    _HomeStarPoint(0.35, 0.36, 0.6, 0.20),
    _HomeStarPoint(0.89, 0.42, 0.8, 0.20),
    _HomeStarPoint(0.08, 0.55, 0.7, 0.18),
    _HomeStarPoint(0.58, 0.53, 0.6, 0.14),
    _HomeStarPoint(0.18, 0.72, 0.7, 0.15),
    _HomeStarPoint(0.82, 0.76, 0.8, 0.18),
    _HomeStarPoint(0.42, 0.84, 0.6, 0.15),
    _HomeStarPoint(0.70, 0.91, 0.7, 0.17),
  ];

  @override
  void paint(Canvas canvas, Size size) {
    for (final point in _points) {
      canvas.drawCircle(
        Offset(size.width * point.x, size.height * point.y),
        point.radius,
        Paint()..color = Colors.white.withValues(alpha: point.opacity),
      );
    }
  }

  @override
  bool shouldRepaint(covariant _HomeStarFieldPainter oldDelegate) => false;
}

class _HomeStarPoint {
  final double x;
  final double y;
  final double radius;
  final double opacity;

  const _HomeStarPoint(this.x, this.y, this.radius, this.opacity);
}
