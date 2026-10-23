import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../app/system_ui.dart';
import '../../../app/theme.dart';
import '../domain/video_import_state.dart';
import 'home_tool_catalog.dart';
import 'import_video_controller.dart';
import 'video_import_error_notice.dart';
import 'video_import_flow.dart';
import 'woah_easter_egg_screen.dart';

/// Single-task launcher. Internal editor tools never appear on Home.
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
      value: appSystemUiStyle,
      child: Scaffold(
        backgroundColor: AppTheme.flowBackground,
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
                padding: EdgeInsets.fromLTRB(
                  horizontalPadding,
                  verticalPadding,
                  horizontalPadding,
                  verticalPadding,
                ),
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
                      _HomeBrand(onLongPress: _openEasterEgg),
                      SizedBox(height: gapAboveCard),
                      Center(
                        child: SizedBox(
                          width: cardWidth,
                          height: cardHeight,
                          child: _HomeToolDeck(
                            isBusy: isBusy,
                            actionLabel: actionLabel,
                            onLaunch: (tool) =>
                                launchHomeTool(context, ref, tool),
                          ),
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
      child: const Align(
        alignment: Alignment.centerLeft,
        child: Padding(
          padding: EdgeInsets.symmetric(horizontal: 2, vertical: 2),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                'Woah',
                style: TextStyle(
                  color: AppTheme.flowTextPrimary,
                  fontSize: 28,
                  fontWeight: FontWeight.w700,
                  letterSpacing: -1.0,
                  height: 1.12,
                ),
              ),
              SizedBox(width: 7),
              DecoratedBox(
                decoration: BoxDecoration(
                  color: AppTheme.gold,
                  shape: BoxShape.circle,
                ),
                child: SizedBox(width: 5, height: 5),
              ),
            ],
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
      label: isBusy ? actionLabel : '导入视频，从设备中选择视频',
      child: ExcludeSemantics(
        // Keep the shadow outside Material's rounded clipping boundary.
        child: DecoratedBox(
          key: const ValueKey('home-card-elevation'),
          decoration: const BoxDecoration(
            borderRadius: radius,
            boxShadow: [
              // Low-key ambient separation from the near-black canvas.
              BoxShadow(
                color: Color(0x29454750),
                offset: Offset(0, 15),
                blurRadius: 34,
                spreadRadius: -8,
              ),
              // Weighted toward the lower edge for a restrained lift.
              BoxShadow(
                color: Color(0xAA000000),
                offset: Offset(0, 22),
                blurRadius: 30,
                spreadRadius: -9,
              ),
            ],
          ),
          child: Material(
            borderRadius: radius,
            clipBehavior: Clip.antiAlias,
            color: AppTheme.flowSurface,
            child: Ink(
              key: const ValueKey('home-import-card'),
              decoration: BoxDecoration(
                borderRadius: radius,
                gradient: const LinearGradient(
                  begin: Alignment.topLeft,
                  end: Alignment.bottomRight,
                  colors: [
                    Color(0xFF242427),
                    Color(0xFF18181B),
                    Color(0xFF141416),
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
                        const SizedBox(
                          height: 34,
                          width: 34,
                          child: CircularProgressIndicator(
                            color: AppTheme.gold,
                            strokeWidth: 2.2,
                          ),
                        )
                      else
                        const Icon(
                          Icons.add_rounded,
                          size: 38,
                          color: AppTheme.goldLight,
                        ),
                      const SizedBox(height: 20),
                      Text(
                        actionLabel,
                        textAlign: TextAlign.center,
                        style: const TextStyle(
                          color: AppTheme.flowTextPrimary,
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
                        style: const TextStyle(
                          color: AppTheme.flowTextSecondary,
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
    required this.isBusy,
    required this.actionLabel,
    required this.onLaunch,
  });

  final bool isBusy;
  final String actionLabel;
  final ValueChanged<HomeTool> onLaunch;

  @override
  State<_HomeToolDeck> createState() => _HomeToolDeckState();
}

class _HomeToolDeckState extends State<_HomeToolDeck> {
  late final PageController _pageController;
  int _selectedIndex = 0;

  @override
  void initState() {
    super.initState();
    _pageController = PageController(viewportFraction: 0.90);
  }

  @override
  void dispose() {
    _pageController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final tools = HomeToolCatalog.standalone;
    assert(tools.isNotEmpty);

    if (tools.length == 1) {
      return _ImportActionCard(
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
                  actionLabel: tool.title,
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
