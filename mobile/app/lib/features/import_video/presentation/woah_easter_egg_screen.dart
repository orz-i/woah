import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../../app/theme.dart';
import '../../../core/metadata/woah_build_info.dart';

class WoahEasterEggScreen extends StatefulWidget {
  static const closeButtonKey = ValueKey('woah-easter-egg-close');
  static const creditsRollKey = ValueKey('woah-easter-egg-credits-roll');

  final Future<WoahBuildInfo> Function()? buildInfoLoader;

  const WoahEasterEggScreen({super.key, this.buildInfoLoader});

  @override
  State<WoahEasterEggScreen> createState() => _WoahEasterEggScreenState();
}

class _WoahEasterEggScreenState extends State<WoahEasterEggScreen>
    with SingleTickerProviderStateMixin {
  static const _creditsDuration = Duration(seconds: 28);

  late final Future<WoahBuildInfo> _buildInfoFuture;
  late final AnimationController _creditsController;

  @override
  void initState() {
    super.initState();
    _buildInfoFuture = (widget.buildInfoLoader ?? WoahBuildInfo.load).call();
    _creditsController = AnimationController(
      vsync: this,
      duration: _creditsDuration,
    )..repeat();
  }

  @override
  void dispose() {
    _creditsController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AnnotatedRegion<SystemUiOverlayStyle>(
      value: const SystemUiOverlayStyle(
        statusBarColor: Colors.transparent,
        statusBarIconBrightness: Brightness.dark,
        statusBarBrightness: Brightness.light,
        systemNavigationBarColor: Colors.white,
        systemNavigationBarIconBrightness: Brightness.dark,
        systemNavigationBarDividerColor: Colors.transparent,
        systemStatusBarContrastEnforced: false,
        systemNavigationBarContrastEnforced: false,
      ),
      child: Scaffold(
        backgroundColor: Colors.white,
        body: Stack(
          children: [
            Positioned.fill(
              child: FutureBuilder<WoahBuildInfo>(
                future: _buildInfoFuture,
                builder: (context, snapshot) {
                  return _RollingCredits(
                    controller: _creditsController,
                    info: snapshot.data,
                  );
                },
              ),
            ),
            SafeArea(
              child: Align(
                alignment: Alignment.bottomCenter,
                child: Padding(
                  padding: const EdgeInsets.only(bottom: 18),
                  child: Semantics(
                    button: true,
                    label: '关闭彩蛋',
                    child: Material(
                      color: Colors.transparent,
                      shape: const CircleBorder(),
                      child: InkWell(
                        key: WoahEasterEggScreen.closeButtonKey,
                        customBorder: const CircleBorder(),
                        onTap: () {
                          HapticFeedback.lightImpact();
                          Navigator.of(context).pop();
                        },
                        child: Ink(
                          width: 62,
                          height: 62,
                          decoration: const BoxDecoration(
                            gradient: AppTheme.coralActionGradient,
                            shape: BoxShape.circle,
                            boxShadow: [
                              BoxShadow(
                                color: Color(0x38F44848),
                                blurRadius: 20,
                                offset: Offset(0, 8),
                              ),
                            ],
                          ),
                          child: const Icon(
                            Icons.close_rounded,
                            color: Colors.white,
                            size: 30,
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _RollingCredits extends StatelessWidget {
  static const _lineExtent = 68.0;
  static const _coralPink = AppTheme.coralSoft;

  final Animation<double> controller;
  final WoahBuildInfo? info;

  const _RollingCredits({required this.controller, required this.info});

  @override
  Widget build(BuildContext context) {
    final value = info;
    final lines = <_CreditLineData>[
      const _CreditLineData('作者  CJ', emphasis: _CreditEmphasis.author),
      const _CreditLineData('本程序免费开源', emphasis: _CreditEmphasis.notice),
      const _CreditLineData('谨防上当受骗', emphasis: _CreditEmphasis.warning),
      const _CreditLineData('程序 · Woah'),
      const _CreditLineData('包名 · art.gaoge.dance'),
      _CreditLineData(value == null ? '版本 · —' : '版本 · v${value.versionName}'),
      _CreditLineData(value == null ? '构建 · —' : '构建 · #${value.buildNumber}'),
      _CreditLineData(
        value == null ? '构建类型 · —' : '构建类型 · ${value.buildType.toUpperCase()}',
      ),
      _CreditLineData(
        value == null ? '提交 · loading' : '提交 · ${value.shortCommit}',
      ),
      const _CreditLineData('处理 · LOCAL FIRST'),
      const _CreditLineData('隐私 · PRIVATE BY DESIGN'),
      const _CreditLineData('界面 · Flutter'),
      const _CreditLineData('推理 · LiteRT'),
      const _CreditLineData('分割 · YOLO'),
      const _CreditLineData('追踪 · DETERMINISTIC'),
      const _CreditLineData('视频 · H.264'),
      const _CreditLineData('记录舞动，也保护舞动的人'),
    ];
    final creditsHeight = lines.length * _lineExtent;

    return ClipRect(
      key: WoahEasterEggScreen.creditsRollKey,
      child: LayoutBuilder(
        builder: (context, constraints) {
          return AnimatedBuilder(
            animation: controller,
            child: SizedBox(
              height: creditsHeight,
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  for (final line in lines)
                    SizedBox(
                      height: _lineExtent,
                      child: Center(child: _CreditLine(data: line)),
                    ),
                ],
              ),
            ),
            builder: (context, child) {
              final travel = constraints.maxHeight + creditsHeight;
              final y = constraints.maxHeight - (travel * controller.value);
              return Transform.translate(
                offset: Offset(0, y),
                child: OverflowBox(
                  alignment: Alignment.topCenter,
                  minHeight: 0,
                  maxHeight: double.infinity,
                  child: child,
                ),
              );
            },
          );
        },
      ),
    );
  }
}

enum _CreditEmphasis { normal, author, notice, warning }

class _CreditLineData {
  final String text;
  final _CreditEmphasis emphasis;

  const _CreditLineData(this.text, {this.emphasis = _CreditEmphasis.normal});
}

class _CreditLine extends StatelessWidget {
  final _CreditLineData data;

  const _CreditLine({required this.data});

  @override
  Widget build(BuildContext context) {
    final (fontSize, fontWeight, letterSpacing) = switch (data.emphasis) {
      _CreditEmphasis.author => (28.0, FontWeight.w800, 3.2),
      _CreditEmphasis.notice => (23.0, FontWeight.w700, 2.0),
      _CreditEmphasis.warning => (23.0, FontWeight.w800, 2.0),
      _CreditEmphasis.normal => (16.0, FontWeight.w600, 1.25),
    };

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 28),
      child: Text(
        data.text,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        textAlign: TextAlign.center,
        style: TextStyle(
          color: _RollingCredits._coralPink,
          fontSize: fontSize,
          height: 1.1,
          fontWeight: fontWeight,
          letterSpacing: letterSpacing,
        ),
      ),
    );
  }
}
