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

/// All tools: one launchable flow plus honest discovery of in-editor features.
class ToolLibraryScreen extends ConsumerWidget {
  const ToolLibraryScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final importState = ref.watch(importVideoControllerProvider);
    final isBusy =
        importState.status == VideoImportStatus.picking ||
        importState.status == VideoImportStatus.probing;
    final activeLabel = switch (importState.status) {
      VideoImportStatus.picking => '正在导入视频…',
      VideoImportStatus.probing => '正在读取视频…',
      _ => '选择视频',
    };

    return AnnotatedRegion<SystemUiOverlayStyle>(
      value: appSystemUiStyle,
      child: Scaffold(
        backgroundColor: AppTheme.flowBackground,
        body: SafeArea(
          child: SingleChildScrollView(
            physics: const ClampingScrollPhysics(),
            padding: const EdgeInsets.fromLTRB(22, 16, 22, 36),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                IconButton(
                  key: const ValueKey('tools-back'),
                  onPressed: () =>
                      context.canPop() ? context.pop() : context.go('/'),
                  tooltip: '返回首页',
                  style: IconButton.styleFrom(
                    minimumSize: const Size(48, 48),
                    backgroundColor: AppTheme.flowSurfaceSoft,
                    foregroundColor: AppTheme.flowTextPrimary,
                  ),
                  icon: const Icon(Icons.arrow_back_rounded),
                ),
                const SizedBox(height: 28),
                const Text(
                  '全部工具',
                  style: TextStyle(
                    color: AppTheme.flowTextPrimary,
                    fontSize: 27,
                    height: 1.14,
                    letterSpacing: -0.5,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                const SizedBox(height: 10),
                const Text(
                  '从视频开始，挑选适合的保护与画面效果',
                  style: TextStyle(
                    color: AppTheme.flowTextSecondary,
                    fontSize: 14,
                    height: 1.4,
                  ),
                ),
                const SizedBox(height: 38),
                const Text(
                  '开始创作',
                  style: TextStyle(
                    color: AppTheme.flowTextPrimary,
                    fontWeight: FontWeight.w600,
                    fontSize: 17,
                  ),
                ),
                const SizedBox(height: 14),
                ...HomeToolCatalog.standalone.map(
                  (tool) => Padding(
                    padding: const EdgeInsets.only(bottom: 12),
                    child: _LaunchableToolTile(
                      tool: tool,
                      isBusy: isBusy,
                      actionLabel: activeLabel,
                      onTap: isBusy || !tool.canLaunch
                          ? null
                          : () => launchHomeTool(context, ref, tool),
                    ),
                  ),
                ),
                if (importState.errorMessage != null) ...[
                  const SizedBox(height: 3),
                  VideoImportErrorNotice(message: importState.errorMessage!),
                ],
                const SizedBox(height: 34),
                const Text(
                  '编辑器内功能',
                  style: TextStyle(
                    color: AppTheme.flowTextPrimary,
                    fontWeight: FontWeight.w600,
                    fontSize: 17,
                  ),
                ),
                const SizedBox(height: 8),
                const Text(
                  '导入视频后，在统一编辑器中使用',
                  style: TextStyle(
                    color: AppTheme.flowTextSecondary,
                    fontSize: 13,
                    height: 1.35,
                  ),
                ),
                const SizedBox(height: 18),
                LayoutBuilder(
                  builder: (context, constraints) {
                    const gap = 12.0;
                    final columns = constraints.maxWidth >= 520 ? 3 : 2;
                    final tileWidth = math.max(
                      0.0,
                      (constraints.maxWidth - (columns - 1) * gap) / columns,
                    );
                    return Wrap(
                      spacing: gap,
                      runSpacing: gap,
                      children: [
                        for (final tool in HomeToolCatalog.editorFeatures)
                          SizedBox(
                            width: tileWidth,
                            child: _EditorFeatureTile(tool: tool),
                          ),
                      ],
                    );
                  },
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _LaunchableToolTile extends StatelessWidget {
  const _LaunchableToolTile({
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
    return Semantics(
      button: true,
      enabled: !isBusy,
      label: isBusy ? actionLabel : '选择视频，使用${tool.title}',
      child: ExcludeSemantics(
        child: Material(
          color: Colors.transparent,
          borderRadius: BorderRadius.circular(20),
          clipBehavior: Clip.antiAlias,
          child: Ink(
            key: ValueKey('tool-launch-${tool.id}'),
            decoration: BoxDecoration(
              color: AppTheme.flowSurfaceSoft,
              border: Border.all(color: AppTheme.flowBorder),
              borderRadius: BorderRadius.circular(20),
            ),
            child: InkWell(
              onTap: onTap,
              child: Padding(
                padding: const EdgeInsets.all(14),
                child: Row(
                  children: [
                    Container(
                      width: 68,
                      height: 76,
                      decoration: BoxDecoration(
                        gradient: const LinearGradient(
                          colors: [Color(0xFF604831), Color(0xFF29282B)],
                          begin: Alignment.topLeft,
                          end: Alignment.bottomRight,
                        ),
                        borderRadius: BorderRadius.circular(14),
                      ),
                      child: const Icon(
                        Icons.shield_outlined,
                        color: AppTheme.goldLight,
                        size: 31,
                      ),
                    ),
                    const SizedBox(width: 15),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            tool.title,
                            style: const TextStyle(
                              color: AppTheme.flowTextPrimary,
                              fontSize: 16,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                          const SizedBox(height: 7),
                          Text(
                            isBusy ? actionLabel : '导入视频开始编辑',
                            style: const TextStyle(
                              color: AppTheme.flowTextSecondary,
                              fontSize: 12,
                              height: 1.4,
                            ),
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(width: 6),
                    if (isBusy)
                      const SizedBox(
                        width: 20,
                        height: 20,
                        child: CircularProgressIndicator(
                          strokeWidth: 2,
                          color: AppTheme.gold,
                        ),
                      )
                    else
                      const Icon(
                        Icons.arrow_forward_rounded,
                        color: AppTheme.goldLight,
                        size: 21,
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

/// Read-only by design: these features do not have standalone launch routes.
class _EditorFeatureTile extends StatelessWidget {
  const _EditorFeatureTile({required this.tool});

  final HomeTool tool;

  @override
  Widget build(BuildContext context) {
    return Container(
      key: ValueKey('tool-editor-${tool.id}'),
      constraints: const BoxConstraints(minHeight: 148),
      padding: const EdgeInsets.all(15),
      decoration: BoxDecoration(
        color: AppTheme.flowSurface,
        borderRadius: BorderRadius.circular(19),
        border: Border.all(color: AppTheme.flowBorder),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(tool.artworkIcon, color: AppTheme.flowTextSecondary, size: 26),
          const SizedBox(height: 19),
          Text(
            tool.title,
            style: const TextStyle(
              color: AppTheme.flowTextPrimary,
              fontSize: 14,
              fontWeight: FontWeight.w600,
            ),
          ),
          const SizedBox(height: 5),
          Text(
            tool.description,
            style: const TextStyle(
              color: AppTheme.flowTextMuted,
              fontSize: 11,
              height: 1.35,
            ),
          ),
        ],
      ),
    );
  }
}
