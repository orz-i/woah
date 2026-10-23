import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../app/system_ui.dart';
import '../../../app/theme.dart';
import '../domain/video_import_state.dart';
import 'home_tool_catalog.dart';
import 'import_video_controller.dart';
import 'video_import_error_notice.dart';
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
                        child: _HomeToolPager(
                          isBusy: isBusy,
                          actionLabel: actionLabel,
                          onLaunch: (tool) =>
                              launchHomeTool(context, ref, tool),
                        ),
                      ),
                      if (state.errorMessage != null) ...[
                        const SizedBox(height: 14),
                        VideoImportErrorNotice(message: state.errorMessage!),
                      ],
                      const SizedBox(height: 20),
                      const _ToolLibraryLink(),
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
    required this.tool,
    required this.isBusy,
    required this.actionLabel,
    required this.onTap,
  });

  final HomeTool tool;
  final bool isBusy;
  final String actionLabel;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    const radius = BorderRadius.all(Radius.circular(28));

    return Semantics(
      button: true,
      enabled: !isBusy,
      label: isBusy ? actionLabel : '选择视频，开始${tool.title}',
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
                    Expanded(child: _HeroArtwork(tool: tool)),
                    const SizedBox(height: 8),
                    Text(
                      tool.title,
                      style: TextStyle(
                        color: AppTheme.flowTextPrimary,
                        fontSize: 25,
                        fontWeight: FontWeight.w700,
                        letterSpacing: -0.6,
                        height: 1.16,
                      ),
                    ),
                    const SizedBox(height: 8),
                    Text(
                      tool.description,
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
  const _HeroArtwork({required this.tool});

  final HomeTool tool;

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
                        tool.artworkIcon,
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
                    child: Icon(
                      tool.badgeIcon,
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

/// Future-ready: no fake slides or indicators while one action is available.
class _HomeToolPager extends StatefulWidget {
  const _HomeToolPager({
    required this.isBusy,
    required this.actionLabel,
    required this.onLaunch,
  });

  final bool isBusy;
  final String actionLabel;
  final ValueChanged<HomeTool> onLaunch;

  @override
  State<_HomeToolPager> createState() => _HomeToolPagerState();
}

class _HomeToolPagerState extends State<_HomeToolPager> {
  late final PageController _controller;
  int _selectedIndex = 0;

  @override
  void initState() {
    super.initState();
    _controller = PageController(viewportFraction: 0.91);
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final tools = HomeToolCatalog.standalone;
    assert(tools.isNotEmpty);
    if (tools.length == 1) {
      return _ProtectionHeroCard(
        tool: tools.single,
        isBusy: widget.isBusy,
        actionLabel: widget.actionLabel,
        onTap: widget.isBusy ? null : () => widget.onLaunch(tools.single),
      );
    }

    return Column(
      children: [
        Expanded(
          child: PageView.builder(
            controller: _controller,
            itemCount: tools.length,
            onPageChanged: (page) => setState(() => _selectedIndex = page),
            itemBuilder: (context, index) {
              final tool = tools[index];
              return Padding(
                padding: const EdgeInsets.symmetric(horizontal: 5),
                child: _ProtectionHeroCard(
                  tool: tool,
                  isBusy: widget.isBusy,
                  actionLabel: widget.actionLabel,
                  onTap: widget.isBusy ? null : () => widget.onLaunch(tool),
                ),
              );
            },
          ),
        ),
        const SizedBox(height: 16),
        Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            for (var i = 0; i < tools.length; i++)
              AnimatedContainer(
                duration: MediaQuery.disableAnimationsOf(context)
                    ? Duration.zero
                    : const Duration(milliseconds: 180),
                margin: const EdgeInsets.symmetric(horizontal: 4),
                width: i == _selectedIndex ? 21 : 5,
                height: 5,
                decoration: BoxDecoration(
                  color: i == _selectedIndex
                      ? AppTheme.gold
                      : AppTheme.flowTextMuted,
                  borderRadius: BorderRadius.circular(5),
                ),
              ),
          ],
        ),
      ],
    );
  }
}

class _ToolLibraryLink extends StatelessWidget {
  const _ToolLibraryLink();

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.transparent,
      child: InkWell(
        key: const ValueKey('home-tool-library-link'),
        borderRadius: BorderRadius.circular(12),
        onTap: () => context.push('/tools'),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 14),
          child: Row(
            children: [
              const Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      '全部工具',
                      style: TextStyle(
                        color: AppTheme.flowTextPrimary,
                        fontSize: 15,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                    SizedBox(height: 5),
                    Text(
                      '探索裁切、贴纸与人物效果',
                      style: TextStyle(
                        color: AppTheme.flowTextMuted,
                        fontSize: 12,
                        height: 1.35,
                      ),
                    ),
                  ],
                ),
              ),
              Icon(
                Icons.arrow_forward_rounded,
                color: AppTheme.flowTextSecondary,
                size: 22,
              ),
            ],
          ),
        ),
      ),
    );
  }
}
