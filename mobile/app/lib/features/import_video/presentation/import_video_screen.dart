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

/// Standalone workflow launcher. Editor-internal tools stay inside their editors.
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
            padding: EdgeInsets.symmetric(horizontal: 2, vertical: 2),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                ColorFiltered(
                  colorFilter: ColorFilter.mode(
                    AppTheme.of(context).brandAction,
                    BlendMode.srcIn,
                  ),
                  child: Image(
                    image: const AssetImage('assets/brand/woah-symbol-256.png'),
                    width: 32,
                    height: 20,
                    filterQuality: FilterQuality.high,
                    excludeFromSemantics: true,
                  ),
                ),
                SizedBox(width: 10),
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

class _ImportActionCard extends StatelessWidget {
  const _ImportActionCard({
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

    return Semantics(
      button: true,
      enabled: !isBusy,
      label: isBusy ? actionLabel : '${tool.title}，${tool.description}',
      child: ExcludeSemantics(
        // Keep the shadow outside Material's rounded clipping boundary.
        child: DecoratedBox(
          key: ValueKey(
            tool.launch == HomeToolLaunch.videoProtection
                ? 'home-card-elevation'
                : 'home-comparison-elevation',
          ),
          decoration: BoxDecoration(
            borderRadius: radius,
            boxShadow: [
              BoxShadow(
                color: AppTheme.of(context).homeCardShadow,
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
            borderRadius: radius,
            clipBehavior: Clip.antiAlias,
            color: AppTheme.of(context).flowSurface,
            child: Ink(
              key: ValueKey(
                tool.launch == HomeToolLaunch.videoProtection
                    ? 'home-import-card'
                    : 'home-comparison-card',
              ),
              decoration: BoxDecoration(
                borderRadius: radius,
                gradient: LinearGradient(
                  begin: Alignment.topLeft,
                  end: Alignment.bottomRight,
                  colors: Theme.of(context).brightness == Brightness.dark
                      ? const [
                          Color(0xFF243638),
                          Color(0xFF1B2B2D),
                          Color(0xFF142123),
                        ]
                      : [
                          AppTheme.of(context).flowSurface,
                          AppTheme.of(context).flowSurfaceSoft,
                          AppTheme.of(context).flowSurface,
                        ],
                ),
              ),
              child: InkWell(
                onTap: onTap,
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 20),
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      if (isBusy)
                        SizedBox(
                          height: 34,
                          width: 34,
                          child: CircularProgressIndicator(
                            color: AppTheme.of(context).brandAction,
                            strokeWidth: 2.2,
                          ),
                        )
                      else
                        Icon(
                          tool.launch == HomeToolLaunch.videoProtection
                              ? Icons.add_rounded
                              : Icons.compare_rounded,
                          size: 38,
                          color: AppTheme.of(context).brandAction,
                        ),
                      const SizedBox(height: 20),
                      Text(
                        actionLabel,
                        textAlign: TextAlign.center,
                        style: TextStyle(
                          color: AppTheme.of(context).flowTextPrimary,
                          fontSize: 20,
                          height: 1.3,
                          letterSpacing: -0.25,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                      const SizedBox(height: 7),
                      Text(
                        isBusy ? '请稍候' : tool.description,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        textAlign: TextAlign.center,
                        style: TextStyle(
                          color: AppTheme.of(context).flowTextSecondary,
                          fontSize: 13,
                          height: 1.4,
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// One real action produces one visible card. Paging stays dormant until a
/// second independent workflow is shipped; editor-only features are excluded.
class _HomeToolDeck extends StatefulWidget {
  const _HomeToolDeck({
    required this.cardWidth,
    required this.viewportFraction,
    required this.isBusy,
    required this.actionLabel,
    required this.onLaunch,
  });

  final bool isBusy;
  final double cardWidth;
  final double viewportFraction;
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
      final previousController = _pageController;
      _pageController = PageController(
        initialPage: _selectedIndex,
        viewportFraction: widget.viewportFraction,
      );
      previousController.dispose();
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
          child: _ImportActionCard(
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
            // Paint shadows through the page edges, not a narrow card window.
            clipBehavior: Clip.none,
            controller: _pageController,
            itemCount: tools.length,
            onPageChanged: (index) => setState(() => _selectedIndex = index),
            itemBuilder: (context, index) {
              final tool = tools[index];
              return Padding(
                padding: const EdgeInsets.symmetric(horizontal: 4),
                child: _ImportActionCard(
                  tool: tool,
                  isBusy: widget.isBusy,
                  actionLabel:
                      widget.isBusy &&
                          tool.launch == HomeToolLaunch.videoProtection
                      ? widget.actionLabel
                      : tool.title,
                  onTap: widget.isBusy ? null : () => widget.onLaunch(tool),
                ),
              );
            },
          ),
        ),
        const SizedBox(height: 12),
        Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            for (var i = 0; i < tools.length; i++)
              Container(
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
