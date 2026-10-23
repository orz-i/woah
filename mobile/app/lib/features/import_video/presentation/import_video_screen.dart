import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../app/system_ui.dart';
import '../../../app/theme.dart';
import '../domain/video_import_state.dart';
import 'import_video_controller.dart';
import 'video_import_flow.dart';
import 'woah_easter_egg_screen.dart';

/// Minimal task-first Home. Independent tools and a card pager will be
/// registered here only when those tools have real navigation actions.
class ImportVideoScreen extends ConsumerStatefulWidget {
  const ImportVideoScreen({super.key});

  @override
  ConsumerState<ImportVideoScreen> createState() => _ImportVideoScreenState();
}

class _ImportVideoScreenState extends ConsumerState<ImportVideoScreen> {
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
      VideoImportStatus.picking => '正在导入视频…',
      VideoImportStatus.probing => '正在读取视频…',
      _ => '选择视频',
    };

    return AnnotatedRegion<SystemUiOverlayStyle>(
      value: appSystemUiStyle,
      child: Scaffold(
        backgroundColor: AppTheme.flowBackground,
        body: SafeArea(
          child: LayoutBuilder(
            builder: (context, viewport) {
              final compact = viewport.maxHeight < 700;
              final paddingY = compact ? 16.0 : 24.0;
              final cardHeight = (viewport.maxHeight * (compact ? 0.61 : 0.55))
                  .clamp(330.0, 460.0);
              final upperGap = compact
                  ? 26.0
                  : (viewport.maxHeight - cardHeight - 200.0).clamp(40.0, 74.0);

              return SingleChildScrollView(
                physics: const ClampingScrollPhysics(),
                padding: EdgeInsets.fromLTRB(22, paddingY, 22, paddingY),
                child: ConstrainedBox(
                  constraints: BoxConstraints(
                    minHeight: math.max(0, viewport.maxHeight - paddingY * 2),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      _HomeBrand(onLongPress: _openEasterEgg),
                      SizedBox(height: upperGap),
                      SizedBox(
                        height: cardHeight,
                        child: _ProtectionHeroCard(
                          isBusy: isBusy,
                          actionLabel: actionLabel,
                          onTap: isBusy
                              ? null
                              : () => startVideoProtectionFlow(context, ref),
                        ),
                      ),
                      if (state.errorMessage != null) ...[
                        const SizedBox(height: 14),
                        _ErrorNotice(message: state.errorMessage!),
                      ],
                      const SizedBox(height: 22),
                      const _HomeHint(),
                    ],
                  ),
                ),
              );
            },
          ),
        ),
      ),
    );
  }
}

class _HomeBrand extends StatelessWidget {
  const _HomeBrand({required this.onLongPress});

  final VoidCallback onLongPress;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      key: const ValueKey('home-brand'),
      behavior: HitTestBehavior.translucent,
      onLongPress: onLongPress,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 2),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  'Woah',
                  style: TextStyle(
                    color: AppTheme.flowTextPrimary,
                    fontSize: 30,
                    fontWeight: FontWeight.w800,
                    letterSpacing: -1.3,
                    height: 1.1,
                  ),
                ),
                SizedBox(width: 8),
                Icon(
                  Icons.auto_awesome_rounded,
                  size: 18,
                  color: AppTheme.gold,
                ),
              ],
            ),
            const SizedBox(height: 8),
            const Text(
              '记录每一个闪闪发光的你',
              style: TextStyle(
                color: AppTheme.flowTextSecondary,
                fontSize: 13,
                height: 1.3,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _ProtectionHeroCard extends StatelessWidget {
  const _ProtectionHeroCard({
    required this.isBusy,
    required this.actionLabel,
    required this.onTap,
  });

  final bool isBusy;
  final String actionLabel;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    const radius = BorderRadius.all(Radius.circular(28));

    return Semantics(
      button: true,
      enabled: !isBusy,
      label: isBusy ? actionLabel : '选择视频，开始视频隐私保护',
      child: ExcludeSemantics(
        child: Material(
          color: Colors.transparent,
          borderRadius: radius,
          clipBehavior: Clip.antiAlias,
          child: Ink(
            key: const ValueKey('home-hero-card'),
            decoration: BoxDecoration(
              borderRadius: radius,
              border: Border.all(color: const Color(0xFF444044)),
              gradient: const LinearGradient(
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
                colors: [
                  Color(0xFF303034),
                  Color(0xFF292524),
                  Color(0xFF171719),
                ],
                stops: [0.0, 0.52, 1.0],
              ),
            ),
            child: InkWell(
              onTap: onTap,
              child: Padding(
                padding: const EdgeInsets.fromLTRB(24, 20, 24, 22),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    const Expanded(child: _HeroArtwork()),
                    const SizedBox(height: 8),
                    const Text(
                      '视频隐私保护',
                      style: TextStyle(
                        color: AppTheme.flowTextPrimary,
                        fontSize: 25,
                        fontWeight: FontWeight.w700,
                        letterSpacing: -0.6,
                        height: 1.16,
                      ),
                    ),
                    const SizedBox(height: 8),
                    const Text(
                      '智能识别人物，专注记录舞台上的你',
                      style: TextStyle(
                        color: AppTheme.flowTextSecondary,
                        fontSize: 13,
                        height: 1.35,
                      ),
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                    ),
                    const SizedBox(height: 22),
                    Container(height: 1, color: Colors.white.withAlpha(24)),
                    const SizedBox(height: 15),
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Flexible(
                          child: Text(
                            actionLabel,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(
                              color: AppTheme.goldLight,
                              fontSize: 16,
                              fontWeight: FontWeight.w700,
                            ),
                          ),
                        ),
                        if (isBusy)
                          const SizedBox(
                            width: 21,
                            height: 21,
                            child: CircularProgressIndicator(
                              strokeWidth: 2,
                              color: AppTheme.gold,
                            ),
                          )
                        else
                          const Icon(
                            Icons.arrow_forward_rounded,
                            color: AppTheme.goldLight,
                            size: 23,
                          ),
                      ],
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// Media-stage abstraction; no real user's image is baked into the launcher.
class _HeroArtwork extends StatelessWidget {
  const _HeroArtwork();

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, area) {
        final width = math.min(area.maxWidth * 0.64, 180.0);
        final height = math.min(area.maxHeight * 0.92, 200.0);
        return Center(
          child: SizedBox(
            width: width,
            height: height,
            child: Stack(
              clipBehavior: Clip.none,
              children: [
                Positioned.fill(
                  child: DecoratedBox(
                    decoration: BoxDecoration(
                      borderRadius: BorderRadius.circular(21),
                      border: Border.all(color: Colors.white.withAlpha(36)),
                      gradient: const LinearGradient(
                        begin: Alignment.topLeft,
                        end: Alignment.bottomRight,
                        colors: [
                          Color(0xFF6B523E),
                          Color(0xFF453D39),
                          Color(0xFF27272A),
                        ],
                      ),
                    ),
                    child: Center(
                      child: Icon(
                        Icons.person_outline_rounded,
                        color: const Color(0xFFE7D3B5).withAlpha(190),
                        size: math.min(height * 0.64, 122.0),
                      ),
                    ),
                  ),
                ),
                Positioned(
                  right: -14,
                  bottom: -10,
                  child: Container(
                    width: 52,
                    height: 52,
                    decoration: BoxDecoration(
                      color: AppTheme.gold,
                      borderRadius: BorderRadius.circular(18),
                      border: Border.all(
                        color: const Color(0xFFEFCE92),
                        width: 1.4,
                      ),
                    ),
                    child: const Icon(
                      Icons.shield_outlined,
                      color: AppTheme.textOnAccent,
                      size: 28,
                    ),
                  ),
                ),
              ],
            ),
          ),
        );
      },
    );
  }
}

class _HomeHint extends StatelessWidget {
  const _HomeHint();

  @override
  Widget build(BuildContext context) {
    return const Padding(
      padding: EdgeInsets.symmetric(horizontal: 10),
      child: Text(
        '导入视频后，可选择保护对象和编辑效果',
        textAlign: TextAlign.center,
        style: TextStyle(
          color: AppTheme.flowTextMuted,
          fontSize: 12,
          height: 1.4,
        ),
      ),
    );
  }
}

class _ErrorNotice extends StatelessWidget {
  const _ErrorNotice({required this.message});

  final String message;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 11),
      decoration: BoxDecoration(
        color: AppTheme.flowSurfaceSoft,
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
