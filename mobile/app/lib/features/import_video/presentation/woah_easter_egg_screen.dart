import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../../app/theme.dart';
import '../../../core/metadata/woah_build_info.dart';

/// A hidden, nonessential moment reached by long-pressing the home brand.
/// Wavi is a playful IP character, not a privacy badge or a replacement logo.
class WoahEasterEggScreen extends StatefulWidget {
  const WoahEasterEggScreen({super.key, this.buildInfoLoader});

  static const contentKey = ValueKey('woah-easter-egg-content');
  static const mascotKey = ValueKey('woah-easter-egg-mascot');
  static const mascotTapKey = ValueKey('woah-easter-egg-mascot-tap');
  static const messageKey = ValueKey('woah-easter-egg-message');

  static const idleAsset = 'assets/ip/wavi/wavi_idle.png';
  static const peekAsset = 'assets/ip/wavi/wavi_peek.png';
  static const joyAsset = 'assets/ip/wavi/wavi_joy.png';
  static const waveAsset = 'assets/ip/wavi/wavi_wave.png';

  final Future<WoahBuildInfo> Function()? buildInfoLoader;

  @override
  State<WoahEasterEggScreen> createState() => _WoahEasterEggScreenState();
}

class _Surprise {
  const _Surprise(this.title, this.message);

  final String title;
  final String message;
}

enum _Moment { idle, turning, revealed }

class _WoahEasterEggScreenState extends State<WoahEasterEggScreen> {
  static const _surprises = <_Surprise>[
    _Surprise('不用每一步都完美', '跟着自己的节奏就好。'),
    _Surprise('今天也有新的舞步', '哪怕只是一小段，也值得记录。'),
    _Surprise('把舞台留给自己', '谁被看见，由你决定。'),
    _Surprise('偶尔慢一点', '也可以跳得很自在。'),
    _Surprise('谢谢你找到这里', '愿每次舞动都有好心情。'),
  ];

  late final Future<WoahBuildInfo> _buildInfo;
  _Moment _moment = _Moment.idle;
  int _surpriseIndex = 0;
  int _transitionToken = 0;

  @override
  void initState() {
    super.initState();
    _buildInfo = (widget.buildInfoLoader ?? WoahBuildInfo.load)();
  }

  @override
  void dispose() {
    _transitionToken++;
    super.dispose();
  }

  String get _pose => switch (_moment) {
    _Moment.idle => WoahEasterEggScreen.idleAsset,
    _Moment.turning => WoahEasterEggScreen.peekAsset,
    _Moment.revealed =>
      _surpriseIndex.isEven
          ? WoahEasterEggScreen.joyAsset
          : WoahEasterEggScreen.waveAsset,
  };

  Future<void> _tapWavi() async {
    if (_moment == _Moment.turning) return;
    HapticFeedback.selectionClick();
    final token = ++_transitionToken;
    final wasRevealed = _moment == _Moment.revealed;
    final reducedMotion = MediaQuery.disableAnimationsOf(context);

    if (reducedMotion) {
      setState(() {
        if (wasRevealed) {
          _surpriseIndex = (_surpriseIndex + 1) % _surprises.length;
        }
        _moment = _Moment.revealed;
      });
      return;
    }

    setState(() => _moment = _Moment.turning);
    await Future<void>.delayed(const Duration(milliseconds: 240));
    if (!mounted || token != _transitionToken) return;
    setState(() {
      if (wasRevealed) {
        _surpriseIndex = (_surpriseIndex + 1) % _surprises.length;
      }
      _moment = _Moment.revealed;
    });
  }

  @override
  Widget build(BuildContext context) {
    return AnnotatedRegion<SystemUiOverlayStyle>(
      value: const SystemUiOverlayStyle(
        statusBarColor: Colors.transparent,
        statusBarIconBrightness: Brightness.light,
        statusBarBrightness: Brightness.dark,
        systemNavigationBarColor: AppTheme.brandInk,
        systemNavigationBarIconBrightness: Brightness.light,
        systemNavigationBarDividerColor: Colors.transparent,
        systemStatusBarContrastEnforced: false,
        systemNavigationBarContrastEnforced: false,
      ),
      child: Scaffold(
        backgroundColor: AppTheme.brandInk,
        body: Stack(
          children: [
            const Positioned.fill(child: _AmbientBackground()),
            SafeArea(
              child: FutureBuilder<WoahBuildInfo>(
                future: _buildInfo,
                builder: (context, snapshot) => _MomentContent(
                  key: WoahEasterEggScreen.contentKey,
                  moment: _moment,
                  pose: _pose,
                  message: _surprises[_surpriseIndex],
                  messageNumber: _surpriseIndex + 1,
                  messageCount: _surprises.length,
                  info: snapshot.data,
                  onTapMascot: _tapWavi,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _AmbientBackground extends StatelessWidget {
  const _AmbientBackground();

  @override
  Widget build(BuildContext context) {
    return const DecoratedBox(
      decoration: BoxDecoration(
        gradient: RadialGradient(
          center: Alignment(0, -0.19),
          radius: 1.13,
          colors: [Color(0xFF18322F), Color(0xFF102321), AppTheme.brandInk],
          stops: [0, 0.48, 1],
        ),
      ),
    );
  }
}

class _MomentContent extends StatelessWidget {
  const _MomentContent({
    super.key,
    required this.moment,
    required this.pose,
    required this.message,
    required this.messageNumber,
    required this.messageCount,
    required this.info,
    required this.onTapMascot,
  });

  final _Moment moment;
  final String pose;
  final _Surprise message;
  final int messageNumber;
  final int messageCount;
  final WoahBuildInfo? info;
  final VoidCallback onTapMascot;

  @override
  Widget build(BuildContext context) {
    final reduceMotion = MediaQuery.disableAnimationsOf(context);
    return LayoutBuilder(
      builder: (context, bounds) {
        final compact = bounds.maxHeight < 720;
        final stageSize = bounds.maxHeight < 590
            ? 208.0
            : compact
            ? 235.0
            : 288.0;
        final verticalPadding = compact ? 14.0 : 24.0;
        return SingleChildScrollView(
          physics: const ClampingScrollPhysics(),
          padding: EdgeInsets.symmetric(
            horizontal: 24,
            vertical: verticalPadding,
          ),
          child: ConstrainedBox(
            constraints: BoxConstraints(
              minHeight: (bounds.maxHeight - verticalPadding * 2).clamp(
                0.0,
                double.infinity,
              ),
            ),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                const _MomentHeader(),
                Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    SizedBox(height: compact ? 18 : 26),
                    const Text(
                      '嘿，你找到这里了。',
                      textAlign: TextAlign.center,
                      style: TextStyle(
                        fontSize: 25,
                        height: 1.28,
                        fontWeight: FontWeight.w600,
                        color: AppTheme.brandPearl,
                      ),
                    ),
                    const SizedBox(height: 8),
                    const Text(
                      '给自己的舞步，留一点小小的自由。',
                      textAlign: TextAlign.center,
                      style: TextStyle(
                        fontSize: 13,
                        height: 1.5,
                        color: AppTheme.brandSecondary,
                      ),
                    ),
                    SizedBox(height: compact ? 8 : 18),
                    _WaviStage(
                      pose: pose,
                      extent: stageSize,
                      reduceMotion: reduceMotion,
                      enabled: moment != _Moment.turning,
                      onTap: onTapMascot,
                    ),
                    SizedBox(height: compact ? 6 : 14),
                    AnimatedSwitcher(
                      duration: reduceMotion
                          ? Duration.zero
                          : const Duration(milliseconds: 220),
                      child: _MessageDisplay(
                        key: ValueKey(
                          moment == _Moment.revealed
                              ? 'note-$messageNumber'
                              : 'intro-${moment.name}',
                        ),
                        moment: moment,
                        message: message,
                      ),
                    ),
                    SizedBox(height: compact ? 12 : 20),
                    Text(
                      moment == _Moment.idle
                          ? '轻触小精灵，开启一份小惊喜'
                          : moment == _Moment.turning
                          ? 'Wavi 正在转身…'
                          : '再轻触一次，换一段心情  ·  '
                                '${messageNumber.toString().padLeft(2, '0')}/'
                                '${messageCount.toString().padLeft(2, '0')}',
                      textAlign: TextAlign.center,
                      style: const TextStyle(
                        fontSize: 12,
                        height: 1.5,
                        color: AppTheme.brandMint,
                      ),
                    ),
                    SizedBox(height: compact ? 18 : 28),
                  ],
                ),
                _Signature(info: info),
              ],
            ),
          ),
        );
      },
    );
  }
}

class _MomentHeader extends StatelessWidget {
  const _MomentHeader();

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        const Expanded(
          child: Text(
            'WOAH  /  A LITTLE MOMENT',
            style: TextStyle(
              fontSize: 10,
              height: 1.3,
              fontWeight: FontWeight.w600,
              letterSpacing: 1.6,
              color: AppTheme.brandSecondary,
            ),
          ),
        ),
        IconButton(
          tooltip: '返回首页',
          onPressed: Navigator.of(context).canPop()
              ? () => Navigator.of(context).pop()
              : null,
          icon: const Icon(Icons.close_rounded, size: 22),
          color: AppTheme.brandPearl,
          style: IconButton.styleFrom(minimumSize: const Size(48, 48)),
        ),
      ],
    );
  }
}

class _WaviStage extends StatelessWidget {
  const _WaviStage({
    required this.pose,
    required this.extent,
    required this.reduceMotion,
    required this.enabled,
    required this.onTap,
  });

  final String pose;
  final double extent;
  final bool reduceMotion;
  final bool enabled;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      label: 'Wavi 节奏小精灵。轻触查看小惊喜。',
      button: true,
      enabled: enabled,
      child: GestureDetector(
        key: WoahEasterEggScreen.mascotTapKey,
        behavior: HitTestBehavior.opaque,
        onTap: enabled ? onTap : null,
        child: SizedBox(
          width: extent,
          height: extent,
          child: Stack(
            fit: StackFit.expand,
            children: [
              const RepaintBoundary(child: CustomPaint(painter: _RhythmArcs())),
              AnimatedSwitcher(
                duration: reduceMotion
                    ? Duration.zero
                    : const Duration(milliseconds: 200),
                switchInCurve: Curves.easeOutCubic,
                switchOutCurve: Curves.easeIn,
                transitionBuilder: (child, animation) => FadeTransition(
                  opacity: animation,
                  child: ScaleTransition(
                    scale: Tween<double>(
                      begin: 0.94,
                      end: 1.0,
                    ).animate(animation),
                    child: child,
                  ),
                ),
                child: Image.asset(
                  pose,
                  key: WoahEasterEggScreen.mascotKey,
                  width: extent,
                  height: extent,
                  fit: BoxFit.contain,
                  filterQuality: FilterQuality.medium,
                  excludeFromSemantics: true,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _RhythmArcs extends CustomPainter {
  const _RhythmArcs();

  @override
  void paint(Canvas canvas, Size size) {
    final width = size.width;
    final bounds = Rect.fromCenter(
      center: Offset(width / 2, size.height / 2),
      width: width * .9,
      height: size.height * .9,
    );
    final paint = Paint()
      ..color = AppTheme.brandMint.withAlpha(27)
      ..strokeWidth = 1.1
      ..style = PaintingStyle.stroke
      ..strokeCap = StrokeCap.round;
    canvas.drawArc(bounds, -2.55, 0.9, false, paint);
    canvas.drawArc(bounds, 0.52, 0.83, false, paint);
    canvas.drawArc(bounds.deflate(width * .075), -0.6, 0.54, false, paint);
  }

  @override
  bool shouldRepaint(covariant _RhythmArcs oldDelegate) => false;
}

class _MessageDisplay extends StatelessWidget {
  const _MessageDisplay({
    super.key,
    required this.moment,
    required this.message,
  });

  final _Moment moment;
  final _Surprise message;

  @override
  Widget build(BuildContext context) {
    if (moment != _Moment.revealed) {
      return const SizedBox(
        height: 76,
        child: Center(
          child: Text(
            '每一次开始，都值得开心。',
            textAlign: TextAlign.center,
            style: TextStyle(
              fontSize: 17,
              height: 1.5,
              color: AppTheme.brandPearl,
              fontWeight: FontWeight.w500,
            ),
          ),
        ),
      );
    }

    return Column(
      key: WoahEasterEggScreen.messageKey,
      mainAxisSize: MainAxisSize.min,
      children: [
        Container(height: 2, width: 28, color: AppTheme.brandMint),
        const SizedBox(height: 14),
        Text(
          message.title,
          textAlign: TextAlign.center,
          style: const TextStyle(
            fontSize: 21,
            height: 1.4,
            color: AppTheme.brandPearl,
            fontWeight: FontWeight.w600,
          ),
        ),
        const SizedBox(height: 6),
        Text(
          message.message,
          textAlign: TextAlign.center,
          style: const TextStyle(
            fontSize: 14,
            height: 1.5,
            color: AppTheme.brandSecondary,
          ),
        ),
      ],
    );
  }
}

class _Signature extends StatelessWidget {
  const _Signature({required this.info});

  final WoahBuildInfo? info;

  @override
  Widget build(BuildContext context) {
    final v = info;
    final release = v == null
        ? 'WOAH  ·  CJ'
        : 'WOAH  ·  CJ  ·  v${v.versionName}  ·  #${v.buildNumber}';
    final revision = v == null
        ? 'art.gaoge.dance'
        : '${v.shortCommit}  ·  art.gaoge.dance';

    return Column(
      children: [
        const Text(
          '免费开源 · 请从可信来源获取',
          textAlign: TextAlign.center,
          style: TextStyle(
            color: AppTheme.brandSecondary,
            fontSize: 11,
            height: 1.45,
          ),
        ),
        const SizedBox(height: 9),
        Text(
          release,
          textAlign: TextAlign.center,
          style: TextStyle(
            color: AppTheme.brandSecondary.withAlpha(150),
            fontSize: 9,
            letterSpacing: 0.6,
          ),
        ),
        const SizedBox(height: 3),
        Text(
          revision,
          textAlign: TextAlign.center,
          style: TextStyle(
            color: AppTheme.brandSecondary.withAlpha(130),
            fontSize: 8,
            letterSpacing: 0.3,
          ),
        ),
      ],
    );
  }
}
