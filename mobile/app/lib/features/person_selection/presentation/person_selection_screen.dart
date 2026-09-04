import 'dart:io';

import 'package:dance_domain/dance_domain.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../app/theme.dart';
import '../../../core/widgets/main_flow_header.dart';
import '../domain/person_selection_state.dart';
import 'person_selection_controller.dart';

class PersonSelectionScreen extends ConsumerStatefulWidget {
  final DanceProject project;

  const PersonSelectionScreen({super.key, required this.project});

  @override
  ConsumerState<PersonSelectionScreen> createState() =>
      _PersonSelectionScreenState();
}

class _PersonSelectionScreenState extends ConsumerState<PersonSelectionScreen> {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      ref
          .read(personSelectionControllerProvider.notifier)
          .analyzeProject(widget.project);
    });
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(personSelectionControllerProvider);
    final controller = ref.read(personSelectionControllerProvider.notifier);

    final showControls = state.status == PersonSelectionStatus.ready &&
        state.persons.isNotEmpty;

    return AnnotatedRegion<SystemUiOverlayStyle>(
      value: const SystemUiOverlayStyle(
        statusBarColor: Colors.transparent,
        statusBarIconBrightness: Brightness.dark,
        statusBarBrightness: Brightness.light,
        systemNavigationBarColor: AppTheme.warmBackground,
        systemNavigationBarIconBrightness: Brightness.dark,
        systemNavigationBarDividerColor: Colors.transparent,
        systemStatusBarContrastEnforced: false,
        systemNavigationBarContrastEnforced: false,
      ),
      child: Scaffold(
        backgroundColor: AppTheme.warmBackground,
        body: SafeArea(
          child: Column(
            children: [
              _buildHeader(),
              Expanded(
                child: Padding(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 16,
                    vertical: 6,
                  ),
                  child: _buildStage(state, controller),
                ),
              ),
              if (showControls)
                _buildBottomControlPanel(state, controller),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildHeader() {
    return MainFlowHeader(
      title: '选择要保护的人',
      onClose: () {
        HapticFeedback.lightImpact();
        context.pop();
      },
    );
  }

  Widget _buildStage(
    PersonSelectionState state,
    PersonSelectionController controller,
  ) {
    if (state.isAnalyzing) {
      return const _CenteredStatus(
        icon: Icons.person_search_rounded,
        title: '正在识别人…',
        subtitle: '分析首帧人物位置',
        loading: true,
      );
    }

    if (state.status == PersonSelectionStatus.error) {
      return _CenteredStatus(
        icon: Icons.error_outline_rounded,
        title: '人物识别失败',
        subtitle: state.errorMessage ?? '请稍后重试',
        actionLabel: '重新识别',
        onAction: () => controller.analyzeProject(widget.project),
      );
    }

    if (state.persons.isEmpty) {
      return const _CenteredStatus(
        icon: Icons.person_off_outlined,
        title: '没有找到可选择的人物',
        subtitle: '请返回并尝试其他视频',
      );
    }

    final videoInfo = state.project?.videoInfo ?? widget.project.videoInfo;
    final aspectRatio = videoInfo.aspectRatio > 0
        ? videoInfo.aspectRatio
        : 9 / 16;

    return Center(
      child: AspectRatio(
        aspectRatio: aspectRatio,
        child: _buildSelectableFrame(state, controller),
      ),
    );
  }

  Widget _buildSelectableFrame(
    PersonSelectionState state,
    PersonSelectionController controller,
  ) {
    final previewPath = state.selectionPreviewPath;
    final hasPreview =
        previewPath != null &&
        previewPath.isNotEmpty &&
        File(previewPath).existsSync();

    return LayoutBuilder(
      builder: (context, constraints) {
        final width = constraints.maxWidth;
        final height = constraints.maxHeight;

        return ClipRRect(
          borderRadius: BorderRadius.circular(28),
          child: Stack(
            fit: StackFit.expand,
            children: [
              Container(color: const Color(0xFFF1E7E1)),
              if (hasPreview)
                Image.file(
                  File(previewPath),
                  fit: BoxFit.fill,
                  gaplessPlayback: true,
                )
              else if (state.selectionPreviewLoading)
                const Center(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      SizedBox(
                        width: 28,
                        height: 28,
                        child: CircularProgressIndicator(
                          strokeWidth: 2,
                          color: AppTheme.coral,
                        ),
                      ),
                      SizedBox(height: 12),
                      Text(
                        '正在准备画面预览',
                        style: TextStyle(
                          color: AppTheme.warmTextSecondary,
                          fontSize: 12,
                        ),
                      ),
                    ],
                  ),
                )
              else
                const Center(
                  child: Padding(
                    padding: EdgeInsets.symmetric(horizontal: 24),
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(
                          Icons.image_not_supported_outlined,
                          size: 34,
                          color: AppTheme.warmTextMuted,
                        ),
                        SizedBox(height: 10),
                        Text(
                          '画面预览暂不可用\n请稍后重试',
                          textAlign: TextAlign.center,
                          style: TextStyle(
                            color: AppTheme.warmTextSecondary,
                            fontSize: 12,
                            height: 1.5,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              for (final person in state.persons)
                _buildPersonBox(
                  person,
                  width,
                  height,
                  state.isPersonSelected(person.id),
                  () {
                    HapticFeedback.selectionClick();
                    controller.togglePerson(person.id);
                  },
                ),
              Positioned.fill(
                child: IgnorePointer(
                  child: DecoratedBox(
                    decoration: BoxDecoration(
                      border: Border.all(color: AppTheme.warmBorder),
                      borderRadius: BorderRadius.circular(28),
                    ),
                  ),
                ),
              ),
            ],
          ),
        );
      },
    );
  }

  Widget _buildPersonBox(
    PersonTrack person,
    double stageWidth,
    double stageHeight,
    bool selected,
    VoidCallback onTap,
  ) {
    final box = person.normalizedInitialBox;
    final left = (box.left * stageWidth).clamp(0.0, stageWidth).toDouble();
    final top = (box.top * stageHeight).clamp(0.0, stageHeight).toDouble();
    final width = (box.width * stageWidth)
        .clamp(0.0, stageWidth - left)
        .toDouble();
    final height = (box.height * stageHeight)
        .clamp(0.0, stageHeight - top)
        .toDouble();

    return Positioned(
      left: left,
      top: top,
      width: width,
      height: height,
      child: Semantics(
        button: true,
        selected: selected,
        label: selected ? '已选择人物' : '未选择人物',
        child: GestureDetector(
          behavior: HitTestBehavior.opaque,
          onTap: onTap,
          child: const SizedBox.expand(),
        ),
      ),
    );
  }

  Widget _buildBottomControlPanel(
    PersonSelectionState state,
    PersonSelectionController controller,
  ) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 16),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _buildPrivacyModeSwitch(state, controller),
          const SizedBox(height: 10),
          Row(
            children: [
              Expanded(
                child: _SelectionBarButton(
                  icon: Icons.done_all_rounded,
                  label: '全选',
                  onPressed: controller.selectAll,
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: _SelectionBarButton(
                  icon: Icons.refresh_rounded,
                  label: '重置',
                  onPressed: controller.resetSelection,
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: _SelectionBarButton(
                  icon: Icons.remove_done_rounded,
                  label: '清空',
                  onPressed: controller.deselectAll,
                ),
              ),
            ],
          ),
          const SizedBox(height: 14),
          _buildContinueButton(state, controller),
        ],
      ),
    );
  }

  Widget _buildPrivacyModeSwitch(
    PersonSelectionState state,
    PersonSelectionController controller,
  ) {
    final isFaceOnly = state.privacyMode == ProjectPrivacyMode.faceOnly;
    return Container(
      height: 44,
      padding: const EdgeInsets.all(3),
      decoration: BoxDecoration(
        color: const Color(0xFFECE3DE),
        borderRadius: BorderRadius.circular(22),
      ),
      child: LayoutBuilder(
        builder: (context, constraints) {
          final itemWidth = (constraints.maxWidth - 2) / 2;
          return Stack(
            children: [
              // 平滑滑动背景指示块
              AnimatedAlign(
                duration: const Duration(milliseconds: 220),
                curve: Curves.easeInOutCubic,
                alignment: isFaceOnly
                    ? Alignment.centerRight
                    : Alignment.centerLeft,
                child: Container(
                  width: itemWidth,
                  height: double.infinity,
                  decoration: BoxDecoration(
                    gradient: AppTheme.coralActionGradient,
                    borderRadius: BorderRadius.circular(19),
                    boxShadow: const [
                      BoxShadow(
                        color: Color(0x33F44848),
                        blurRadius: 8,
                        offset: Offset(0, 2),
                      ),
                    ],
                  ),
                ),
              ),
              // 全身 / 人脸选项
              Row(
                children: [
                  Expanded(
                    child: GestureDetector(
                      behavior: HitTestBehavior.opaque,
                      onTap: () {
                        HapticFeedback.selectionClick();
                        controller.setProjectPrivacyMode(
                          ProjectPrivacyMode.fullBody,
                        );
                      },
                      child: Center(
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Icon(
                              Icons.accessibility_new_rounded,
                              size: 18,
                              color: !isFaceOnly
                                  ? Colors.white
                                  : AppTheme.warmTextSecondary,
                            ),
                            const SizedBox(width: 6),
                            Text(
                              '全身保护',
                              style: TextStyle(
                                fontSize: 13.5,
                                fontWeight: !isFaceOnly
                                    ? FontWeight.w600
                                    : FontWeight.w500,
                                color: !isFaceOnly
                                    ? Colors.white
                                    : AppTheme.warmTextSecondary,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
                  Expanded(
                    child: GestureDetector(
                      behavior: HitTestBehavior.opaque,
                      onTap: () {
                        HapticFeedback.selectionClick();
                        controller.setProjectPrivacyMode(
                          ProjectPrivacyMode.faceOnly,
                        );
                      },
                      child: Center(
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Icon(
                              Icons.face_retouching_off_rounded,
                              size: 18,
                              color: isFaceOnly
                                  ? Colors.white
                                  : AppTheme.warmTextSecondary,
                            ),
                            const SizedBox(width: 6),
                            Text(
                              '人脸保护',
                              style: TextStyle(
                                fontSize: 13.5,
                                fontWeight: isFaceOnly
                                    ? FontWeight.w600
                                    : FontWeight.w500,
                                color: isFaceOnly
                                    ? Colors.white
                                    : AppTheme.warmTextSecondary,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
                ],
              ),
            ],
          );
        },
      ),
    );
  }

  Widget _buildContinueButton(
    PersonSelectionState state,
    PersonSelectionController controller,
  ) {
    final selectedCount = state.privacyTargetIds.length;
    return SizedBox(
      width: double.infinity,
      height: 58,
      child: _CoralContinueButton(
        enabled: selectedCount > 0,
        label: selectedCount == 0 ? '请选择人物' : '继续',
        onPressed: () async {
          HapticFeedback.mediumImpact();
          final configured = controller.buildConfiguredProject();
          if (configured == null) return;
          final updated = await context.push<DanceProject>(
            '/effect_editor',
            extra: configured,
          );
          if (updated != null) controller.updateProject(updated);
        },
      ),
    );
  }
}

class _SelectionBarButton extends StatelessWidget {
  final IconData icon;
  final String label;
  final VoidCallback onPressed;

  const _SelectionBarButton({
    required this.icon,
    required this.label,
    required this.onPressed,
  });

  @override
  Widget build(BuildContext context) {
    return Semantics(
      button: true,
      label: label,
      child: Material(
        color: Colors.transparent,
        borderRadius: BorderRadius.circular(14),
        child: InkWell(
          onTap: () {
            HapticFeedback.selectionClick();
            onPressed();
          },
          borderRadius: BorderRadius.circular(14),
          child: Container(
            height: 38,
            decoration: BoxDecoration(
              color: const Color(0xFFF2ECE7),
              borderRadius: BorderRadius.circular(14),
              border: Border.all(
                color: AppTheme.warmBorder.withValues(alpha: 0.6),
                width: 1.0,
              ),
            ),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Icon(icon, size: 16, color: AppTheme.coral),
                const SizedBox(width: 5),
                Text(
                  label,
                  style: const TextStyle(
                    color: AppTheme.warmTextPrimary,
                    fontSize: 12.5,
                    fontWeight: FontWeight.w600,
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

class _CoralContinueButton extends StatelessWidget {
  final bool enabled;
  final String label;
  final Future<void> Function() onPressed;

  const _CoralContinueButton({
    required this.enabled,
    required this.label,
    required this.onPressed,
  });

  @override
  Widget build(BuildContext context) {
    return Semantics(
      button: true,
      enabled: enabled,
      label: label,
      child: Opacity(
        opacity: enabled ? 1 : 0.45,
        child: Material(
          color: Colors.transparent,
          borderRadius: BorderRadius.circular(18),
          child: InkWell(
            onTap: enabled ? () => onPressed() : null,
            borderRadius: BorderRadius.circular(18),
            child: Ink(
              decoration: BoxDecoration(
                gradient: AppTheme.coralActionGradient,
                borderRadius: BorderRadius.circular(18),
                boxShadow: enabled
                    ? const [
                        BoxShadow(
                          color: Color(0x20F44848),
                          blurRadius: 14,
                          offset: Offset(0, 6),
                        ),
                      ]
                    : null,
              ),
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 22),
                child: Row(
                  children: [
                    const SizedBox(width: 30),
                    Expanded(
                      child: Text(
                        label,
                        textAlign: TextAlign.center,
                        style: const TextStyle(
                          color: Colors.white,
                          fontSize: 17,
                          fontWeight: FontWeight.w600,
                          letterSpacing: 0.4,
                        ),
                      ),
                    ),
                    const Icon(
                      Icons.arrow_forward_rounded,
                      color: Colors.white,
                      size: 28,
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

class _CenteredStatus extends StatelessWidget {
  final IconData icon;
  final String title;
  final String subtitle;
  final bool loading;
  final String? actionLabel;
  final VoidCallback? onAction;

  const _CenteredStatus({
    required this.icon,
    required this.title,
    required this.subtitle,
    this.loading = false,
    this.actionLabel,
    this.onAction,
  });

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 36),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            if (loading)
              const SizedBox(
                width: 40,
                height: 40,
                child: CircularProgressIndicator(strokeWidth: 2.5),
              )
            else
              Icon(icon, size: 42, color: AppTheme.coral),
            const SizedBox(height: 18),
            Text(
              title,
              textAlign: TextAlign.center,
              style: const TextStyle(
                color: AppTheme.warmTextPrimary,
                fontSize: 17,
                fontWeight: FontWeight.w700,
              ),
            ),
            const SizedBox(height: 7),
            Text(
              subtitle,
              textAlign: TextAlign.center,
              style: const TextStyle(
                color: AppTheme.warmTextSecondary,
                fontSize: 13,
              ),
            ),
            if (actionLabel != null && onAction != null) ...[
              const SizedBox(height: 20),
              OutlinedButton(
                style: OutlinedButton.styleFrom(
                  foregroundColor: AppTheme.coral,
                  side: const BorderSide(color: AppTheme.warmBorder),
                ),
                onPressed: onAction,
                child: Text(actionLabel!),
              ),
            ],
          ],
        ),
      ),
    );
  }
}
