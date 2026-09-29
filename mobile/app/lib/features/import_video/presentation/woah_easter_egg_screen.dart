import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../../core/metadata/woah_build_info.dart';

class WoahEasterEggScreen extends StatefulWidget {
  static const contentKey = ValueKey('woah-easter-egg-content');
  static const buddyKey = ValueKey('woah-easter-egg-buddy');
  static const revealCardKey = ValueKey('woah-easter-egg-reveal-card');
  static const primaryActionKey = ValueKey('woah-easter-egg-primary-action');

  static const idleAsset = 'assets/easter/poses/buddy_idle.png';
  static const playfulAsset = 'assets/easter/poses/buddy_playful.png';
  static const happyAsset = 'assets/easter/poses/buddy_happy.png';
  static const waveAsset = 'assets/easter/poses/buddy_wave.png';

  final Future<WoahBuildInfo> Function()? buildInfoLoader;

  const WoahEasterEggScreen({super.key, this.buildInfoLoader});

  @override
  State<WoahEasterEggScreen> createState() => _WoahEasterEggScreenState();
}

enum _EasterStage { intro, revealing, shown, next }

class _EasterMessage {
  final String title;
  final String body;

  const _EasterMessage(this.title, this.body);
}

class _WoahEasterEggScreenState extends State<WoahEasterEggScreen>
    with SingleTickerProviderStateMixin {
  static const _background = Color(0xFF060B14);
  static const _backgroundMid = Color(0xFF0A1530);
  static const _textPrimary = Color(0xFFF8F5EE);
  static const _textSecondary = Color(0xFFDAD6D0);
  static const _textMuted = Color(0xFF8E94A5);
  static const _star = Color(0xFFFFD776);
  static const _paper = Color(0xFFFFF7E9);
  static const _paperInk = Color(0xFF2D2925);

  static const _messages = <_EasterMessage>[
    _EasterMessage('你已经很棒了', '继续做自己吧！'),
    _EasterMessage('愿每个舞动', '都被温柔保护。'),
    _EasterMessage('今天的你', '也很闪闪发光。'),
    _EasterMessage('慢一点也没关系', '你已经走在更好的路上了。'),
    _EasterMessage('谢谢你发现这里', '世界因此多一点可爱。'),
  ];

  late final Future<WoahBuildInfo> _buildInfoFuture;
  late final AnimationController _idleController;

  _EasterStage _stage = _EasterStage.intro;
  int _messageIndex = 0;
  int _transitionToken = 0;

  @override
  void initState() {
    super.initState();
    _buildInfoFuture = (widget.buildInfoLoader ?? WoahBuildInfo.load).call();
    _idleController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 3200),
    )..repeat(reverse: true);
  }

  @override
  void dispose() {
    _transitionToken++;
    _idleController.dispose();
    super.dispose();
  }

  String get _buddyAsset => switch (_stage) {
    _EasterStage.intro => WoahEasterEggScreen.idleAsset,
    _EasterStage.revealing => WoahEasterEggScreen.playfulAsset,
    _EasterStage.shown => WoahEasterEggScreen.happyAsset,
    _EasterStage.next => WoahEasterEggScreen.waveAsset,
  };

  Future<void> _reveal() async {
    if (_stage != _EasterStage.intro) return;
    HapticFeedback.lightImpact();
    final token = ++_transitionToken;
    setState(() => _stage = _EasterStage.revealing);

    await Future<void>.delayed(const Duration(milliseconds: 460));
    if (!mounted || token != _transitionToken) return;
    setState(() => _stage = _EasterStage.shown);
  }

  Future<void> _showNext() async {
    if (_stage != _EasterStage.shown) return;
    HapticFeedback.selectionClick();
    final token = ++_transitionToken;
    setState(() => _stage = _EasterStage.next);

    await Future<void>.delayed(const Duration(milliseconds: 360));
    if (!mounted || token != _transitionToken) return;
    setState(() {
      _messageIndex = (_messageIndex + 1) % _messages.length;
      _stage = _EasterStage.shown;
    });
  }

  @override
  Widget build(BuildContext context) {
    return AnnotatedRegion<SystemUiOverlayStyle>(
      value: const SystemUiOverlayStyle(
        statusBarColor: Colors.transparent,
        statusBarIconBrightness: Brightness.light,
        statusBarBrightness: Brightness.dark,
        systemNavigationBarColor: _background,
        systemNavigationBarIconBrightness: Brightness.light,
        systemNavigationBarDividerColor: Colors.transparent,
        systemStatusBarContrastEnforced: false,
        systemNavigationBarContrastEnforced: false,
      ),
      child: Scaffold(
        backgroundColor: _background,
        body: Stack(
          children: [
            const Positioned.fill(child: _NightBackground()),
            SafeArea(
              child: FutureBuilder<WoahBuildInfo>(
                future: _buildInfoFuture,
                builder: (context, snapshot) {
                  return _EasterEggContent(
                    key: WoahEasterEggScreen.contentKey,
                    stage: _stage,
                    message: _messages[_messageIndex],
                    buddyAsset: _buddyAsset,
                    idleController: _idleController,
                    info: snapshot.data,
                    onReveal: _reveal,
                    onNext: _showNext,
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

class _NightBackground extends StatelessWidget {
  const _NightBackground();

  @override
  Widget build(BuildContext context) {
    return const DecoratedBox(
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [
            _WoahEasterEggScreenState._background,
            _WoahEasterEggScreenState._backgroundMid,
            Color(0xFF111938),
            Color(0xFF090E1B),
            _WoahEasterEggScreenState._background,
          ],
          stops: [0, 0.24, 0.48, 0.72, 1],
        ),
      ),
      child: CustomPaint(painter: _StarFieldPainter()),
    );
  }
}

class _EasterEggContent extends StatelessWidget {
  final _EasterStage stage;
  final _EasterMessage message;
  final String buddyAsset;
  final Animation<double> idleController;
  final WoahBuildInfo? info;
  final VoidCallback onReveal;
  final VoidCallback onNext;

  const _EasterEggContent({
    super.key,
    required this.stage,
    required this.message,
    required this.buddyAsset,
    required this.idleController,
    required this.info,
    required this.onReveal,
    required this.onNext,
  });

  bool get _isShown =>
      stage == _EasterStage.shown || stage == _EasterStage.next;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final compact = constraints.maxHeight < 720;
        final heroExtent = compact ? 238.0 : 276.0;

        return SingleChildScrollView(
          physics: const ClampingScrollPhysics(),
          padding: EdgeInsets.fromLTRB(
            24,
            compact ? 22 : 34,
            24,
            compact ? 20 : 28,
          ),
          child: ConstrainedBox(
            constraints: BoxConstraints(
              minHeight: constraints.maxHeight - (compact ? 42 : 62),
            ),
            child: Column(
              children: [
                const _Header(),
                SizedBox(height: compact ? 16 : 22),
                _BuddyScene(
                  asset: buddyAsset,
                  extent: heroExtent,
                  idleController: idleController,
                  onTap: stage == _EasterStage.intro ? onReveal : null,
                ),
                SizedBox(height: compact ? 12 : 18),
                AnimatedSwitcher(
                  duration: const Duration(milliseconds: 260),
                  switchInCurve: Curves.easeOutCubic,
                  switchOutCurve: Curves.easeInCubic,
                  transitionBuilder: (child, animation) => FadeTransition(
                    opacity: animation,
                    child: SlideTransition(
                      position: Tween<Offset>(
                        begin: const Offset(0, 0.06),
                        end: Offset.zero,
                      ).animate(animation),
                      child: child,
                    ),
                  ),
                  child: _isShown
                      ? _RevealCard(
                          key: WoahEasterEggScreen.revealCardKey,
                          message: message,
                        )
                      : _IntroMessage(
                          key: const ValueKey('intro-message'),
                          revealing: stage == _EasterStage.revealing,
                        ),
                ),
                SizedBox(height: compact ? 16 : 22),
                _PrimaryAction(
                  stage: stage,
                  onReveal: onReveal,
                  onNext: onNext,
                ),
                SizedBox(height: compact ? 18 : 28),
                const _OpenSourceNotice(),
                SizedBox(height: compact ? 20 : 30),
                _BuildSignature(info: info),
              ],
            ),
          ),
        );
      },
    );
  }
}

class _Header extends StatelessWidget {
  const _Header();

  @override
  Widget build(BuildContext context) {
    return const Column(
      children: [
        Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(
              Icons.auto_awesome_rounded,
              color: _WoahEasterEggScreenState._star,
              size: 21,
            ),
            SizedBox(width: 11),
            Text(
              '彩蛋时间',
              textAlign: TextAlign.center,
              style: TextStyle(
                color: _WoahEasterEggScreenState._textPrimary,
                fontSize: 34,
                height: 1.08,
                fontWeight: FontWeight.w700,
                letterSpacing: 0.8,
              ),
            ),
            SizedBox(width: 11),
            Icon(
              Icons.auto_awesome_rounded,
              color: _WoahEasterEggScreenState._star,
              size: 21,
            ),
          ],
        ),
        SizedBox(height: 11),
        Text(
          '生活很认真，但也可以很有趣',
          textAlign: TextAlign.center,
          style: TextStyle(
            color: _WoahEasterEggScreenState._textSecondary,
            fontSize: 14,
            height: 1.45,
            fontWeight: FontWeight.w500,
            letterSpacing: 0.6,
          ),
        ),
      ],
    );
  }
}

class _BuddyScene extends StatelessWidget {
  final String asset;
  final double extent;
  final Animation<double> idleController;
  final VoidCallback? onTap;

  const _BuddyScene({
    required this.asset,
    required this.extent,
    required this.idleController,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final reduceMotion = MediaQuery.disableAnimationsOf(context);

    return Semantics(
      button: onTap != null,
      label: onTap == null ? 'Woah 小伙伴' : '点击星星揭晓彩蛋',
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: onTap,
        child: SizedBox(
          width: extent,
          height: extent,
          child: AnimatedBuilder(
            animation: idleController,
            builder: (context, child) {
              final t = reduceMotion
                  ? 0.5
                  : Curves.easeInOut.transform(idleController.value);
              return Transform.translate(
                offset: Offset(0, -4 * t),
                child: Transform.scale(scale: 1 + (0.012 * t), child: child),
              );
            },
            child: AnimatedSwitcher(
              duration: const Duration(milliseconds: 220),
              switchInCurve: Curves.easeOutCubic,
              switchOutCurve: Curves.easeInCubic,
              transitionBuilder: (child, animation) => FadeTransition(
                opacity: animation,
                child: ScaleTransition(
                  scale: Tween<double>(begin: 0.97, end: 1).animate(animation),
                  child: child,
                ),
              ),
              child: ShaderMask(
                key: ValueKey(asset),
                shaderCallback: (rect) {
                  return const RadialGradient(
                    center: Alignment.center,
                    radius: 0.74,
                    colors: [
                      Colors.white,
                      Colors.white,
                      Color(0xE6FFFFFF),
                      Color(0x00FFFFFF),
                    ],
                    stops: [0.0, 0.60, 0.82, 1.0],
                  ).createShader(rect);
                },
                blendMode: BlendMode.dstIn,
                child: Image.asset(
                  asset,
                  key: WoahEasterEggScreen.buddyKey,
                  width: extent,
                  height: extent,
                  fit: BoxFit.cover,
                  filterQuality: FilterQuality.high,
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _IntroMessage extends StatelessWidget {
  final bool revealing;

  const _IntroMessage({super.key, required this.revealing});

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        Text(
          revealing ? '小伙伴正在打开惊喜…' : '谢谢你，',
          textAlign: TextAlign.center,
          style: const TextStyle(
            color: _WoahEasterEggScreenState._textPrimary,
            fontSize: 17,
            height: 1.45,
            fontWeight: FontWeight.w600,
            letterSpacing: 0.7,
          ),
        ),
        const SizedBox(height: 2),
        Text(
          revealing ? '星星亮起来了' : '让这个世界多一点可爱',
          textAlign: TextAlign.center,
          style: const TextStyle(
            color: _WoahEasterEggScreenState._textPrimary,
            fontSize: 17,
            height: 1.45,
            fontWeight: FontWeight.w600,
            letterSpacing: 0.7,
          ),
        ),
      ],
    );
  }
}

class _RevealCard extends StatelessWidget {
  final _EasterMessage message;

  const _RevealCard({super.key, required this.message});

  @override
  Widget build(BuildContext context) {
    return Transform.rotate(
      angle: -math.pi / 90,
      child: Container(
        width: double.infinity,
        constraints: const BoxConstraints(maxWidth: 310),
        padding: const EdgeInsets.fromLTRB(24, 19, 24, 18),
        decoration: BoxDecoration(
          color: _WoahEasterEggScreenState._paper,
          borderRadius: BorderRadius.circular(8),
          boxShadow: [
            BoxShadow(
              color: _WoahEasterEggScreenState._star.withAlpha(40),
              blurRadius: 28,
              spreadRadius: 4,
            ),
            const BoxShadow(
              color: Color(0x42000000),
              blurRadius: 16,
              offset: Offset(0, 8),
            ),
          ],
        ),
        child: Column(
          children: [
            Text(
              message.title,
              textAlign: TextAlign.center,
              style: const TextStyle(
                color: _WoahEasterEggScreenState._paperInk,
                fontSize: 17,
                height: 1.35,
                fontWeight: FontWeight.w700,
                letterSpacing: 0.4,
              ),
            ),
            const SizedBox(height: 4),
            Text(
              message.body,
              textAlign: TextAlign.center,
              style: const TextStyle(
                color: _WoahEasterEggScreenState._paperInk,
                fontSize: 15,
                height: 1.4,
                fontWeight: FontWeight.w600,
                letterSpacing: 0.2,
              ),
            ),
            const SizedBox(height: 10),
            const Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Icon(
                  Icons.favorite_rounded,
                  color: Color(0xFFE9655D),
                  size: 15,
                ),
                SizedBox(width: 6),
                Text(
                  'Woah',
                  style: TextStyle(
                    color: _WoahEasterEggScreenState._paperInk,
                    fontSize: 12,
                    fontWeight: FontWeight.w600,
                    letterSpacing: 0.5,
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _PrimaryAction extends StatelessWidget {
  final _EasterStage stage;
  final VoidCallback onReveal;
  final VoidCallback onNext;

  const _PrimaryAction({
    required this.stage,
    required this.onReveal,
    required this.onNext,
  });

  @override
  Widget build(BuildContext context) {
    final (label, enabled, callback) = switch (stage) {
      _EasterStage.intro => ('点一点星星', true, onReveal),
      _EasterStage.revealing => ('正在打开惊喜…', false, onReveal),
      _EasterStage.shown => ('再看一个彩蛋', true, onNext),
      _EasterStage.next => ('正在寻找下一颗星星…', false, onNext),
    };

    return Semantics(
      button: true,
      enabled: enabled,
      child: AnimatedOpacity(
        duration: const Duration(milliseconds: 180),
        opacity: enabled ? 1 : 0.62,
        child: GestureDetector(
          key: WoahEasterEggScreen.primaryActionKey,
          behavior: HitTestBehavior.opaque,
          onTap: enabled ? callback : null,
          child: Container(
            constraints: const BoxConstraints(minWidth: 202, minHeight: 50),
            padding: const EdgeInsets.symmetric(horizontal: 26, vertical: 14),
            decoration: BoxDecoration(
              color: _WoahEasterEggScreenState._paper,
              borderRadius: BorderRadius.circular(28),
              boxShadow: const [
                BoxShadow(
                  color: Color(0x24000000),
                  blurRadius: 16,
                  offset: Offset(0, 8),
                ),
              ],
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Text(
                  label,
                  style: const TextStyle(
                    color: _WoahEasterEggScreenState._paperInk,
                    fontSize: 14,
                    height: 1.2,
                    fontWeight: FontWeight.w700,
                    letterSpacing: 0.2,
                  ),
                ),
                if (stage == _EasterStage.intro ||
                    stage == _EasterStage.shown) ...[
                  const SizedBox(width: 8),
                  const Icon(
                    Icons.auto_awesome_rounded,
                    color: Color(0xFFE6A93F),
                    size: 17,
                  ),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _OpenSourceNotice extends StatelessWidget {
  const _OpenSourceNotice();

  @override
  Widget build(BuildContext context) {
    return const Column(
      children: [
        Text(
          '本程序免费开源',
          textAlign: TextAlign.center,
          style: TextStyle(
            color: _WoahEasterEggScreenState._textSecondary,
            fontSize: 11.5,
            height: 1.4,
            fontWeight: FontWeight.w500,
            letterSpacing: 1.1,
          ),
        ),
        SizedBox(height: 3),
        Text(
          '谨防上当受骗',
          textAlign: TextAlign.center,
          style: TextStyle(
            color: _WoahEasterEggScreenState._textMuted,
            fontSize: 10,
            height: 1.4,
            fontWeight: FontWeight.w500,
            letterSpacing: 1.3,
          ),
        ),
      ],
    );
  }
}

class _BuildSignature extends StatelessWidget {
  final WoahBuildInfo? info;

  const _BuildSignature({required this.info});

  @override
  Widget build(BuildContext context) {
    final value = info;
    final version = value == null
        ? 'Woah  ·  CJ'
        : 'Woah  ·  CJ  ·  v${value.versionName}  ·  #${value.buildNumber}';
    final commit = value == null
        ? 'art.gaoge.dance'
        : '${value.shortCommit}  ·  art.gaoge.dance';

    return Column(
      children: [
        Text(
          version,
          textAlign: TextAlign.center,
          style: const TextStyle(
            color: _WoahEasterEggScreenState._textMuted,
            fontSize: 9,
            height: 1.35,
            fontWeight: FontWeight.w500,
            letterSpacing: 0.7,
          ),
        ),
        const SizedBox(height: 3),
        Text(
          commit,
          textAlign: TextAlign.center,
          style: TextStyle(
            color: _WoahEasterEggScreenState._textMuted.withAlpha(145),
            fontSize: 8,
            height: 1.35,
            fontWeight: FontWeight.w400,
            letterSpacing: 0.45,
          ),
        ),
      ],
    );
  }
}

class _StarFieldPainter extends CustomPainter {
  const _StarFieldPainter();

  static const _stars = <_StarPoint>[
    _StarPoint(0.07, 0.07, 0.9, 0.28),
    _StarPoint(0.17, 0.13, 0.6, 0.24),
    _StarPoint(0.31, 0.06, 0.8, 0.34),
    _StarPoint(0.50, 0.11, 0.7, 0.30),
    _StarPoint(0.69, 0.05, 0.9, 0.24),
    _StarPoint(0.90, 0.12, 0.7, 0.35),
    _StarPoint(0.10, 0.22, 0.7, 0.22),
    _StarPoint(0.84, 0.25, 0.8, 0.26),
    _StarPoint(0.04, 0.42, 0.7, 0.18),
    _StarPoint(0.96, 0.45, 0.9, 0.18),
    _StarPoint(0.13, 0.68, 0.7, 0.16),
    _StarPoint(0.89, 0.71, 0.7, 0.18),
    _StarPoint(0.27, 0.39, 0.6, 0.18),
    _StarPoint(0.73, 0.56, 0.7, 0.20),
  ];

  @override
  void paint(Canvas canvas, Size size) {
    for (final star in _stars) {
      canvas.drawCircle(
        Offset(size.width * star.x, size.height * star.y),
        star.radius,
        Paint()
          ..color = _WoahEasterEggScreenState._textPrimary.withValues(
            alpha: star.opacity,
          ),
      );
    }
  }

  @override
  bool shouldRepaint(covariant _StarFieldPainter oldDelegate) => false;
}

class _StarPoint {
  final double x;
  final double y;
  final double radius;
  final double opacity;

  const _StarPoint(this.x, this.y, this.radius, this.opacity);
}
