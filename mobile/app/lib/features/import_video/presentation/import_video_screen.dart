import 'dart:math' as math;
import 'dart:ui' as ui;

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

/// Two standalone workflows share the original swipeable card deck.
/// Covers are decorative; labels and interactions stay native Flutter UI.
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
              final cardWidth = math.min(300.0, availableWidth * 0.88);
              final cardHeight = (cardWidth * 1.12).clamp(250.0, 336.0);
              final gapAboveCard = math.max(
                46.0,
                (viewport.maxHeight -
                        2 * verticalPadding -
                        cardHeight -
                        36.0 -
                        76.0) /
                    2,
              );

              return SingleChildScrollView(
                physics: const ClampingScrollPhysics(),
                padding: EdgeInsets.symmetric(vertical: verticalPadding),
                child: ConstrainedBox(
                  constraints: BoxConstraints(
                    minHeight: math.max(
                      0.0,
                      viewport.maxHeight - 2 * verticalPadding,
                    ),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Padding(
                        padding: EdgeInsets.symmetric(
                          horizontal: horizontalPadding,
                        ),
                        child: _HomeBrand(onLongPress: _openEasterEgg),
                      ),
                      SizedBox(height: gapAboveCard),
                      SizedBox(
                        height: cardHeight,
                        child: _HomeToolDeck(
                          cardWidth: cardWidth,
                          viewportFraction:
                              cardWidth * 0.90 / viewport.maxWidth,
                          isBusy: isBusy,
                          actionLabel: actionLabel,
                          onLaunch: (tool) =>
                              launchHomeTool(context, ref, tool),
                        ),
                      ),
                      if (state.errorMessage != null) ...[
                        const SizedBox(height: 18),
                        Center(
                          child: ConstrainedBox(
                            constraints: BoxConstraints(maxWidth: cardWidth),
                            child: VideoImportErrorNotice(
                              message: state.errorMessage!,
                            ),
                          ),
                        ),
                      ],
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

  final HomeTool tool;
  final bool isBusy;
  final String actionLabel;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    const radius = BorderRadius.all(Radius.circular(22));
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
        // Retain the original bottom-biased, borderless floating-card shadow.
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
              fit: StackFit.expand,
              children: [
                Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Expanded(
                      child: ClipRect(
                        child: isProtection
                            ? Image.asset(
                                tool.coverAsset,
                                key: const ValueKey('home-privacy-cover'),
                                fit: BoxFit.cover,
                                alignment: const Alignment(0, -0.3),
                                filterQuality: FilterQuality.medium,
                                excludeFromSemantics: true,
                                errorBuilder: (context, error, stackTrace) =>
                                    _HomeCoverFallback(isProtection: true),
                              )
                            : Stack(
                                fit: StackFit.expand,
                                children: [
                                  // A softened background fills the taller card
                                  // while retaining the entire two-video image.
                                  ImageFiltered(
                                    imageFilter: ui.ImageFilter.blur(
                                      sigmaX: 16,
                                      sigmaY: 16,
                                    ),
                                    child: ColorFiltered(
                                      colorFilter: const ColorFilter.mode(
                                        Color(0x660B1415),
                                        BlendMode.darken,
                                      ),
                                      child: Image.asset(
                                        tool.coverAsset,
                                        fit: BoxFit.cover,
                                        excludeFromSemantics: true,
                                      ),
                                    ),
                                  ),
                                  Center(
                                    child: AspectRatio(
                                      aspectRatio: 2.30,
                                      child: Image.asset(
                                        tool.coverAsset,
                                        key: const ValueKey(
                                          'home-comparison-cover',
                                        ),
                                        fit: BoxFit.cover,
                                        excludeFromSemantics: true,
                                        errorBuilder:
                                            (context, error, stackTrace) =>
                                                _HomeCoverFallback(
                                                  isProtection: false,
                                                ),
                                      ),
                                    ),
                                  ),
                                ],
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
                // Entire card is a single action, including its cover.
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

class _HomeCoverFallback extends StatelessWidget {
  const _HomeCoverFallback({required this.isProtection});

  final bool isProtection;

  @override
  Widget build(BuildContext context) {
    final palette = AppTheme.of(context);
    return ColoredBox(
      color: palette.flowSurfaceSoft,
      child: Center(
        child: Icon(
          isProtection ? Icons.movie_outlined : Icons.compare_outlined,
          color: palette.brandAction,
          size: 36,
        ),
      ),
    );
  }
}

/// Preserve the previous swipeable card stack and its visible page indicator.
/// On a non-mobile platform with only one supported task, paging is dormant.
class _HomeToolDeck extends StatefulWidget {
  const _HomeToolDeck({
    required this.cardWidth,
    required this.viewportFraction,
    required this.isBusy,
    required this.actionLabel,
    required this.onLaunch,
  });

  final double cardWidth;
  final double viewportFraction;
  final bool isBusy;
  final String actionLabel;
  final ValueChanged<HomeTool> onLaunch;

  @override
  State<_HomeToolDeck> createState() => _HomeToolDeckState();
}

class _HomeToolDeckState extends State<_HomeToolDeck> {
  late PageController _pageController;
  int _selectedIndex = 0;

  @override
  void initState() {
    super.initState();
    _pageController = PageController(viewportFraction: widget.viewportFraction);
  }

  @override
  void didUpdateWidget(covariant _HomeToolDeck oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.viewportFraction != widget.viewportFraction) {
      final previous = _pageController;
      _pageController = PageController(
        initialPage: _selectedIndex,
        viewportFraction: widget.viewportFraction,
      );
      previous.dispose();
    }
  }

  @override
  void dispose() {
    _pageController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final tools = HomeToolCatalog.forPlatform(Theme.of(context).platform);
    assert(tools.isNotEmpty);

    if (tools.length == 1) {
      return Center(
        child: SizedBox(
          width: widget.cardWidth,
          height: double.infinity,
          child: _HomeActionCard(
            tool: tools.single,
            isBusy: widget.isBusy,
            actionLabel: widget.actionLabel,
            onTap: widget.isBusy ? null : () => widget.onLaunch(tools.single),
          ),
        ),
      );
    }

    return Column(
      children: [
        Expanded(
          child: PageView.builder(
            key: const ValueKey('home-card-pager'),
            // Do not clip the next-card peek or the bottom-biased shadows.
            clipBehavior: Clip.none,
            controller: _pageController,
            itemCount: tools.length,
            onPageChanged: (index) => setState(() => _selectedIndex = index),
            itemBuilder: (context, index) {
              final tool = tools[index];
              return Padding(
                padding: const EdgeInsets.symmetric(horizontal: 4),
                child: _HomeActionCard(
                  tool: tool,
                  isBusy: widget.isBusy,
                  actionLabel: widget.actionLabel,
                  onTap: widget.isBusy ? null : () => widget.onLaunch(tool),
                ),
              );
            },
          ),
        ),
        const SizedBox(height: 12),
        Row(
          key: const ValueKey('home-card-page-indicator'),
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            for (var i = 0; i < tools.length; i++)
              Container(
                key: ValueKey('home-card-page-dot-$i'),
                width: i == _selectedIndex ? 17 : 5,
                height: 5,
                margin: const EdgeInsets.symmetric(horizontal: 3),
                decoration: BoxDecoration(
                  color: i == _selectedIndex
                      ? AppTheme.of(context).brandAction
                      : AppTheme.of(context).flowTextMuted,
                  borderRadius: BorderRadius.circular(5),
                ),
              ),
          ],
        ),
      ],
    );
  }
}
