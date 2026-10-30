import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../app/system_ui.dart';
import '../../../app/brand.dart';
import '../../../app/theme.dart';
import '../domain/video_import_state.dart';
import 'home_tool_catalog.dart';
import 'import_video_controller.dart';
import 'video_import_error_notice.dart';
import 'video_import_flow.dart';
import 'woah_easter_egg_screen.dart';

/// The two actual standalone workflows are visible without horizontal paging.
/// Their imagery is decorative: text and navigation remain native Flutter UI.
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
    final tools = HomeToolCatalog.forPlatform(Theme.of(context).platform);
    final isBusy = state.isLoading;
    final actionLabel = switch (state.status) {
      VideoImportStatus.picking => '正在导入视频…',
      VideoImportStatus.probing => '正在读取视频…',
      _ => '导入视频',
    };

    return AnnotatedRegion<SystemUiOverlayStyle>(
      value: appSystemUiStyleFor(Theme.of(context).brightness),
      child: Scaffold(
        backgroundColor: AppTheme.of(context).flowBackground,
        body: SafeArea(
          child: LayoutBuilder(
            builder: (context, viewport) {
              final horizontalPadding = viewport.maxWidth < 360 ? 20.0 : 24.0;
              final verticalPadding = viewport.maxHeight < 650 ? 18.0 : 26.0;
              final availableWidth = viewport.maxWidth - 2 * horizontalPadding;
              final cardWidth = math.min(440.0, availableWidth);
              final estimatedHeight =
                  tools.length *
                      (cardWidth / _HomeActionCard.coverAspect + 92) +
                  math.max(0, tools.length - 1) * 16;
              // Keep the brand near the top and the cards near the middle.
              // Short screens or large type use the surrounding scroll view.
              final gapAboveCards =
                  ((viewport.maxHeight -
                              verticalPadding * 2 -
                              estimatedHeight -
                              40) *
                          0.45)
                      .clamp(24.0, 120.0);

              return SingleChildScrollView(
                physics: const ClampingScrollPhysics(),
                padding: EdgeInsets.symmetric(
                  horizontal: horizontalPadding,
                  vertical: verticalPadding,
                ),
                child: ConstrainedBox(
                  constraints: BoxConstraints(
                    minHeight: math.max(
                      0.0,
                      viewport.maxHeight - 2 * verticalPadding,
                    ),
                  ),
                  child: Align(
                    alignment: Alignment.topCenter,
                    child: ConstrainedBox(
                      constraints: const BoxConstraints(maxWidth: 440),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          Align(
                            alignment: Alignment.centerLeft,
                            child: _HomeBrand(onLongPress: _openEasterEgg),
                          ),
                          SizedBox(height: gapAboveCards),
                          _HomeToolDeck(
                            tools: tools,
                            isBusy: isBusy,
                            actionLabel: actionLabel,
                            onLaunch: (tool) =>
                                launchHomeTool(context, ref, tool),
                          ),
                          if (state.errorMessage != null) ...[
                            const SizedBox(height: 18),
                            VideoImportErrorNotice(
                              message: state.errorMessage!,
                            ),
                          ],
                        ],
                      ),
                    ),
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
      child: Align(
        alignment: Alignment.centerLeft,
        child: Semantics(
          label: AppBrand.english,
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 2, vertical: 2),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                ColorFiltered(
                  colorFilter: ColorFilter.mode(
                    AppTheme.of(context).brandAction,
                    BlendMode.srcIn,
                  ),
                  child: const Image(
                    image: AssetImage('assets/brand/woah-symbol-256.png'),
                    width: 32,
                    height: 20,
                    filterQuality: FilterQuality.high,
                    excludeFromSemantics: true,
                  ),
                ),
                const SizedBox(width: 10),
                Text(
                  AppBrand.english,
                  style: TextStyle(
                    color: AppTheme.of(context).flowTextPrimary,
                    fontSize: 28,
                    fontWeight: FontWeight.w700,
                    letterSpacing: -1.0,
                    height: 1.12,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _HomeActionCard extends StatelessWidget {
  const _HomeActionCard({
    required this.tool,
    required this.isBusy,
    required this.actionLabel,
    required this.onTap,
  });

  static const double coverAspect = 2.30;

  final HomeTool tool;
  final bool isBusy;
  final String actionLabel;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    const radius = BorderRadius.all(Radius.circular(20));
    final palette = AppTheme.of(context);
    final isProtection = tool.launch == HomeToolLaunch.videoProtection;
    final visibleDescription = isBusy && isProtection
        ? actionLabel
        : tool.description;

    return Semantics(
      button: true,
      enabled: !isBusy,
      label: '${tool.title}，$visibleDescription',
      child: ExcludeSemantics(
        child: DecoratedBox(
          key: ValueKey(
            isProtection ? 'home-card-elevation' : 'home-comparison-elevation',
          ),
          decoration: BoxDecoration(
            borderRadius: radius,
            boxShadow: [
              BoxShadow(
                color: palette.homeCardShadow,
                offset: const Offset(0, 10),
                blurRadius: 24,
                spreadRadius: -6,
              ),
              BoxShadow(
                color: Theme.of(context).brightness == Brightness.dark
                    ? const Color(0x24000000)
                    : const Color(0x100B1415),
                offset: const Offset(0, 18),
                blurRadius: 22,
                spreadRadius: -8,
              ),
            ],
          ),
          child: Material(
            color: palette.flowSurface,
            borderRadius: radius,
            clipBehavior: Clip.antiAlias,
            child: Stack(
              children: [
                Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    AspectRatio(
                      aspectRatio: coverAspect,
                      child: Image.asset(
                        tool.coverAsset,
                        key: ValueKey(
                          isProtection
                              ? 'home-privacy-cover'
                              : 'home-comparison-cover',
                        ),
                        fit: BoxFit.cover,
                        alignment: isProtection
                            ? const Alignment(0, -0.30)
                            : Alignment.center,
                        filterQuality: FilterQuality.medium,
                        excludeFromSemantics: true,
                        errorBuilder: (context, error, stackTrace) =>
                            ColoredBox(
                              color: palette.flowSurfaceSoft,
                              child: Center(
                                child: Icon(
                                  isProtection
                                      ? Icons.movie_outlined
                                      : Icons.compare_outlined,
                                  color: palette.brandAction,
                                  size: 36,
                                ),
                              ),
                            ),
                      ),
                    ),
                    Padding(
                      padding: const EdgeInsets.fromLTRB(18, 14, 18, 17),
                      child: Row(
                        crossAxisAlignment: CrossAxisAlignment.center,
                        children: [
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                Text(
                                  tool.title,
                                  style: TextStyle(
                                    color: palette.flowTextPrimary,
                                    fontSize: 19,
                                    height: 1.25,
                                    letterSpacing: -0.25,
                                    fontWeight: FontWeight.w600,
                                  ),
                                ),
                                const SizedBox(height: 5),
                                Text(
                                  visibleDescription,
                                  style: TextStyle(
                                    color: palette.flowTextSecondary,
                                    fontSize: 13.5,
                                    height: 1.4,
                                  ),
                                ),
                              ],
                            ),
                          ),
                          const SizedBox(width: 14),
                          if (isBusy && isProtection)
                            SizedBox(
                              width: 21,
                              height: 21,
                              child: CircularProgressIndicator(
                                color: palette.brandAction,
                                strokeWidth: 2,
                              ),
                            )
                          else
                            Icon(
                              Icons.arrow_outward_rounded,
                              color: palette.brandAction,
                              size: 22,
                            ),
                        ],
                      ),
                    ),
                  ],
                ),
                // Transparent overlay keeps the full cover and footer tappable
                // and paints the press feedback above the image.
                Positioned.fill(
                  child: Material(
                    type: MaterialType.transparency,
                    child: InkWell(
                      key: ValueKey(
                        isProtection
                            ? 'home-import-card'
                            : 'home-comparison-card',
                      ),
                      onTap: onTap,
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
}

class _HomeToolDeck extends StatelessWidget {
  const _HomeToolDeck({
    required this.tools,
    required this.isBusy,
    required this.actionLabel,
    required this.onLaunch,
  });

  final List<HomeTool> tools;
  final bool isBusy;
  final String actionLabel;
  final ValueChanged<HomeTool> onLaunch;

  @override
  Widget build(BuildContext context) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        for (var index = 0; index < tools.length; index++) ...[
          if (index > 0) const SizedBox(height: 16),
          _HomeActionCard(
            tool: tools[index],
            isBusy: isBusy,
            actionLabel: actionLabel,
            onTap: isBusy ? null : () => onLaunch(tools[index]),
          ),
        ],
      ],
    );
  }
}
