import 'dart:io';

import 'package:dance_domain/dance_domain.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../app/theme.dart';
import '../../../core/widgets/bottom_control_drawer.dart';
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
  final DraggableScrollableController _drawerController =
      DraggableScrollableController();

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
  void dispose() {
    _drawerController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(personSelectionControllerProvider);
    final controller = ref.read(personSelectionControllerProvider.notifier);

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
        body: Stack(
          children: [
            Positioned.fill(child: _buildStage(state, controller)),
            Positioned(
              top: 0,
              left: 0,
              right: 0,
              child: SafeArea(bottom: false, child: _buildHeader(controller)),
            ),
            if (state.status == PersonSelectionStatus.ready &&
                state.persons.isNotEmpty)
              Positioned.fill(
                child: BottomControlDrawer(
                  controller: _drawerController,
                  minChildSize: 0.28,
                  initialChildSize: 0.30,
                  maxChildSize: 0.40,
                  snapSizes: const [0.28, 0.30, 0.40],
                  panelColor: AppTheme.warmSurface,
                  panelBorderColor: AppTheme.warmBorder,
                  handleColor: AppTheme.warmBorder,
                  panelRadius: 30,
                  allowHandleOnlyCollapse: false,
                  panelShadow: const [
                    BoxShadow(
                      color: Color(0x16000000),
                      blurRadius: 28,
                      offset: Offset(0, -8),
                    ),
                  ],
                  bottomActionBorderColor: Colors.transparent,
                  bottomActionBar: _buildContinueButton(state, controller),
                  child: _buildDrawerContent(state, controller),
                ),
              ),
          ],
        ),
      ),
    );
  }

  Widget _buildHeader(PersonSelectionController controller) {
    return SizedBox(
      height: 78,
      child: Stack(
        alignment: Alignment.center,
        children: [
          Positioned(
            left: 18,
            child: _HeaderIconButton(
              icon: Icons.close_rounded,
              tooltip: '关闭',
              onPressed: () {
                HapticFeedback.lightImpact();
                context.pop();
              },
            ),
          ),
          Positioned(
            right: 18,
            child: _HeaderIconButton(
              icon: Icons.refresh_rounded,
              tooltip: '重新选择',
              outlined: true,
              onPressed: () {
                HapticFeedback.lightImpact();
                controller.selectAll();
              },
            ),
          ),
          const Text(
            '选择要保护的人',
            textAlign: TextAlign.center,
            style: TextStyle(
              color: AppTheme.warmTextPrimary,
              fontSize: 19,
              height: 1.1,
              fontWeight: FontWeight.w700,
              letterSpacing: -0.3,
            ),
          ),
        ],
      ),
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

    return Padding(
      padding: const EdgeInsets.fromLTRB(18, 132, 18, 48),
      child: LayoutBuilder(
        builder: (context, constraints) {
          var stageWidth = constraints.maxWidth;
          var stageHeight = stageWidth / aspectRatio;
          if (stageHeight > constraints.maxHeight) {
            stageHeight = constraints.maxHeight;
            stageWidth = stageHeight * aspectRatio;
          }

          return Align(
            alignment: const Alignment(0, -0.18),
            child: SizedBox(
              width: stageWidth,
              height: stageHeight,
              child: _buildSelectableFrame(state, controller),
            ),
          );
        },
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
          child: AnimatedContainer(
            duration: const Duration(milliseconds: 160),
            decoration: BoxDecoration(
              color: selected
                  ? AppTheme.coral.withAlpha(12)
                  : Colors.transparent,
              borderRadius: BorderRadius.circular(14),
              border: Border.all(
                color: selected ? AppTheme.coralSoft : AppTheme.warmTextMuted,
                width: selected ? 2.2 : 1.2,
              ),
              boxShadow: selected
                  ? const [BoxShadow(color: Color(0x18F44848), blurRadius: 10)]
                  : null,
            ),
            child: Align(
              alignment: Alignment.topRight,
              child: Transform.translate(
                offset: const Offset(8, -8),
                child: AnimatedContainer(
                  duration: const Duration(milliseconds: 160),
                  width: 30,
                  height: 30,
                  decoration: BoxDecoration(
                    color: selected ? AppTheme.coral : AppTheme.warmSurface,
                    shape: BoxShape.circle,
                    border: Border.all(
                      color: selected ? Colors.white : AppTheme.warmTextMuted,
                      width: selected ? 1.5 : 1.2,
                    ),
                  ),
                  child: selected
                      ? const Icon(
                          Icons.check_rounded,
                          color: Colors.white,
                          size: 19,
                        )
                      : null,
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildDrawerContent(
    PersonSelectionState state,
    PersonSelectionController controller,
  ) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _buildPrivacyModeSelector(state, controller),
        const SizedBox(height: 22),
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            _SelectionActionButton(
              icon: Icons.check_circle_outline_rounded,
              label: '全选',
              onPressed: controller.selectAll,
            ),
            _SelectionActionButton(
              icon: Icons.remove_circle_outline_rounded,
              label: '清空',
              onPressed: controller.deselectAll,
            ),
          ],
        ),
        const SizedBox(height: 8),
      ],
    );
  }

  Widget _buildPrivacyModeSelector(
    PersonSelectionState state,
    PersonSelectionController controller,
  ) {
    return Container(
      height: 58,
      padding: const EdgeInsets.all(5),
      decoration: BoxDecoration(
        color: AppTheme.warmSurfaceSoft,
        borderRadius: BorderRadius.circular(24),
        border: Border.all(color: AppTheme.coralPale),
      ),
      child: Row(
        children: [
          Expanded(
            child: _ModeButton(
              label: '全身保护',
              icon: Icons.accessibility_new_rounded,
              selected: state.privacyMode == ProjectPrivacyMode.fullBody,
              onTap: () {
                HapticFeedback.selectionClick();
                controller.setProjectPrivacyMode(ProjectPrivacyMode.fullBody);
              },
            ),
          ),
          const SizedBox(width: 5),
          Expanded(
            child: _ModeButton(
              label: '人脸保护',
              icon: Icons.face_retouching_off_rounded,
              selected: state.privacyMode == ProjectPrivacyMode.faceOnly,
              onTap: () {
                HapticFeedback.selectionClick();
                controller.setProjectPrivacyMode(ProjectPrivacyMode.faceOnly);
              },
            ),
          ),
        ],
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

class _ModeButton extends StatelessWidget {
  final String label;
  final IconData icon;
  final bool selected;
  final VoidCallback onTap;

  const _ModeButton({
    required this.label,
    required this.icon,
    required this.selected,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.transparent,
      borderRadius: BorderRadius.circular(20),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(20),
        child: Ink(
          decoration: BoxDecoration(
            gradient: selected ? AppTheme.coralActionGradient : null,
            borderRadius: BorderRadius.circular(20),
          ),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(
                icon,
                size: 20,
                color: selected ? Colors.white : AppTheme.warmTextSecondary,
              ),
              const SizedBox(width: 8),
              Text(
                label,
                style: TextStyle(
                  color: selected ? Colors.white : AppTheme.warmTextSecondary,
                  fontSize: 14,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _HeaderIconButton extends StatelessWidget {
  final IconData icon;
  final String tooltip;
  final VoidCallback onPressed;
  final bool outlined;

  const _HeaderIconButton({
    required this.icon,
    required this.tooltip,
    required this.onPressed,
    this.outlined = false,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 48,
      height: 48,
      decoration: BoxDecoration(
        color: outlined ? AppTheme.warmSurfaceSoft : Colors.transparent,
        borderRadius: BorderRadius.circular(16),
        border: outlined ? Border.all(color: AppTheme.coralPale) : null,
      ),
      child: IconButton(
        tooltip: tooltip,
        onPressed: onPressed,
        padding: EdgeInsets.zero,
        icon: Icon(icon, size: 30, color: AppTheme.warmTextPrimary),
      ),
    );
  }
}

class _SelectionActionButton extends StatelessWidget {
  final IconData icon;
  final String label;
  final VoidCallback onPressed;

  const _SelectionActionButton({
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
        borderRadius: BorderRadius.circular(18),
        child: InkWell(
          onTap: () {
            HapticFeedback.selectionClick();
            onPressed();
          },
          borderRadius: BorderRadius.circular(18),
          child: Container(
            constraints: const BoxConstraints(minWidth: 106, minHeight: 52),
            padding: const EdgeInsets.symmetric(horizontal: 18),
            decoration: BoxDecoration(
              color: AppTheme.warmSurface,
              borderRadius: BorderRadius.circular(18),
              border: Border.all(color: AppTheme.warmBorder),
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Icon(icon, size: 22, color: AppTheme.coral),
                const SizedBox(width: 8),
                Text(
                  label,
                  style: const TextStyle(
                    color: AppTheme.coral,
                    fontSize: 14,
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
