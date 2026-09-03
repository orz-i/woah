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

    return Scaffold(
      backgroundColor: AppTheme.background,
      body: Stack(
        children: [
          Positioned.fill(child: _buildStage(state, controller)),
          Positioned(
            top: 0,
            left: 0,
            right: 0,
            child: SafeArea(
              bottom: false,
              child: Padding(
                padding: const EdgeInsets.fromLTRB(14, 8, 14, 0),
                child: _buildTopBar(state),
              ),
            ),
          ),
          if (state.status == PersonSelectionStatus.ready &&
              state.persons.isNotEmpty)
            Positioned.fill(
              child: BottomControlDrawer(
                controller: _drawerController,
                minChildSize: 0.065,
                initialChildSize: 0.31,
                maxChildSize: 0.58,
                snapSizes: const [0.065, 0.31, 0.58],
                peekHeader: _buildDrawerHeader(state),
                bottomActionBar: _buildContinueButton(state, controller),
                child: _buildDrawerContent(state, controller),
              ),
            ),
        ],
      ),
    );
  }

  Widget _buildTopBar(PersonSelectionState state) {
    return Row(
      children: [
        _TopButton(
          icon: Icons.arrow_back_rounded,
          tooltip: '返回',
          onPressed: () {
            HapticFeedback.lightImpact();
            context.pop();
          },
        ),
        const Expanded(
          child: Text(
            '选择人物',
            textAlign: TextAlign.center,
            style: TextStyle(
              color: AppTheme.textPrimary,
              fontSize: 16,
              fontWeight: FontWeight.w700,
            ),
          ),
        ),
        _TopButton(
          icon: Icons.help_outline_rounded,
          tooltip: '选择说明',
          onPressed: () => _showSelectionHelp(),
        ),
      ],
    );
  }

  Future<void> _showSelectionHelp() async {
    HapticFeedback.lightImpact();
    await showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      backgroundColor: AppTheme.surface,
      builder: (context) => const SafeArea(
        top: false,
        child: Padding(
          padding: EdgeInsets.fromLTRB(22, 4, 22, 28),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                '如何选择',
                style: TextStyle(
                  color: AppTheme.textPrimary,
                  fontSize: 18,
                  fontWeight: FontWeight.w700,
                ),
              ),
              SizedBox(height: 14),
              Text(
                '选择“全身保护”或“人脸保护”后，点击画面中的人物框或下方头像即可选择目标。一个视频只使用一种保护模式。',
                style: TextStyle(
                  color: AppTheme.textSecondary,
                  fontSize: 14,
                  height: 1.55,
                ),
              ),
            ],
          ),
        ),
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
      padding: const EdgeInsets.fromLTRB(12, 72, 12, 50),
      child: LayoutBuilder(
        builder: (context, constraints) {
          var stageWidth = constraints.maxWidth;
          var stageHeight = stageWidth / aspectRatio;
          if (stageHeight > constraints.maxHeight) {
            stageHeight = constraints.maxHeight;
            stageWidth = stageHeight * aspectRatio;
          }

          return Center(
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
          borderRadius: BorderRadius.circular(AppTheme.radiusLarge),
          child: Stack(
            fit: StackFit.expand,
            children: [
              Container(color: Colors.black),
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
                          color: AppTheme.metalMid,
                        ),
                      ),
                      SizedBox(height: 12),
                      Text(
                        '正在准备画面预览',
                        style: TextStyle(
                          color: AppTheme.textMuted,
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
                          color: AppTheme.metalLow,
                        ),
                        SizedBox(height: 10),
                        Text(
                          '画面预览暂不可用\n可从下方头像继续选择人物',
                          textAlign: TextAlign.center,
                          style: TextStyle(
                            color: AppTheme.textMuted,
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
                      border: Border.all(color: Colors.white.withAlpha(22)),
                      borderRadius: BorderRadius.circular(AppTheme.radiusLarge),
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
              color: selected ? Colors.white.withAlpha(12) : Colors.transparent,
              borderRadius: BorderRadius.circular(10),
              border: Border.all(
                color: selected ? AppTheme.primaryWhite : AppTheme.metalLow,
                width: selected ? 2 : 1,
              ),
              boxShadow: selected
                  ? const [BoxShadow(color: Color(0x30000000), blurRadius: 8)]
                  : null,
            ),
            child: Align(
              alignment: Alignment.topLeft,
              child: Transform.translate(
                offset: const Offset(-8, -8),
                child: AnimatedContainer(
                  duration: const Duration(milliseconds: 160),
                  width: 28,
                  height: 28,
                  decoration: BoxDecoration(
                    color: selected
                        ? AppTheme.primaryWhite
                        : AppTheme.surfaceHigh,
                    shape: BoxShape.circle,
                    border: Border.all(
                      color: selected
                          ? AppTheme.primaryWhite
                          : AppTheme.metalMid,
                    ),
                  ),
                  child: selected
                      ? const Icon(
                          Icons.check_rounded,
                          color: AppTheme.canvas,
                          size: 18,
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

  Widget _buildDrawerHeader(PersonSelectionState state) {
    final modeLabel = state.privacyMode == ProjectPrivacyMode.fullBody
        ? '全身保护'
        : '人脸保护';
    return Padding(
      padding: const EdgeInsets.fromLTRB(18, 0, 18, 8),
      child: Row(
        children: [
          const Icon(Icons.shield_outlined, size: 18, color: AppTheme.metalMid),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              '已选择 ${state.privacyTargetIds.length} 人',
              style: const TextStyle(
                color: AppTheme.textPrimary,
                fontSize: 14,
                fontWeight: FontWeight.w700,
              ),
            ),
          ),
          Text(
            modeLabel,
            style: const TextStyle(
              color: AppTheme.textMuted,
              fontSize: 12,
              fontWeight: FontWeight.w600,
            ),
          ),
        ],
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
        const Text(
          '保护方式',
          style: TextStyle(
            color: AppTheme.textSecondary,
            fontSize: 13,
            fontWeight: FontWeight.w700,
          ),
        ),
        const SizedBox(height: 10),
        _buildPrivacyModeSelector(state, controller),
        const SizedBox(height: 18),
        const Text(
          '点击画面中的人物或下方头像进行选择',
          style: TextStyle(color: AppTheme.textMuted, fontSize: 12),
        ),
        const SizedBox(height: 12),
        SizedBox(
          height: 86,
          child: ListView.separated(
            scrollDirection: Axis.horizontal,
            physics: const BouncingScrollPhysics(),
            itemCount: state.persons.length,
            separatorBuilder: (_, _) => const SizedBox(width: 10),
            itemBuilder: (context, index) {
              final person = state.persons[index];
              return _buildPersonThumbnail(
                person,
                selected: state.isPersonSelected(person.id),
                onTap: () {
                  HapticFeedback.selectionClick();
                  controller.togglePerson(person.id);
                },
              );
            },
          ),
        ),
        const SizedBox(height: 12),
        Row(
          children: [
            Expanded(
              child: OutlinedButton.icon(
                onPressed: controller.selectAll,
                icon: const Icon(Icons.done_all_rounded, size: 18),
                label: const Text('全选'),
              ),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: OutlinedButton.icon(
                onPressed: controller.deselectAll,
                icon: const Icon(Icons.remove_done_rounded, size: 18),
                label: const Text('清空'),
              ),
            ),
          ],
        ),
        const SizedBox(height: 12),
      ],
    );
  }

  Widget _buildPrivacyModeSelector(
    PersonSelectionState state,
    PersonSelectionController controller,
  ) {
    return Container(
      height: 52,
      padding: const EdgeInsets.all(4),
      decoration: BoxDecoration(
        color: AppTheme.canvas,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: AppTheme.surfaceBorder),
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
          const SizedBox(width: 4),
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

  Widget _buildPersonThumbnail(
    PersonTrack person, {
    required bool selected,
    required VoidCallback onTap,
  }) {
    final hasThumb =
        person.thumbnailPath.isNotEmpty &&
        File(person.thumbnailPath).existsSync();
    return Semantics(
      button: true,
      selected: selected,
      label: selected ? '已选择人物头像' : '未选择人物头像',
      child: GestureDetector(
        onTap: onTap,
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 160),
          width: 68,
          decoration: BoxDecoration(
            color: AppTheme.surfaceHigh,
            borderRadius: BorderRadius.circular(12),
            border: Border.all(
              color: selected ? AppTheme.primaryWhite : AppTheme.surfaceBorder,
              width: selected ? 2 : 1,
            ),
          ),
          clipBehavior: Clip.antiAlias,
          child: Stack(
            fit: StackFit.expand,
            children: [
              if (hasThumb)
                Image.file(File(person.thumbnailPath), fit: BoxFit.cover)
              else
                const Icon(
                  Icons.person_rounded,
                  color: AppTheme.metalLow,
                  size: 34,
                ),
              if (selected)
                Positioned(
                  right: 5,
                  bottom: 5,
                  child: Container(
                    width: 24,
                    height: 24,
                    decoration: const BoxDecoration(
                      color: AppTheme.primaryWhite,
                      shape: BoxShape.circle,
                    ),
                    child: const Icon(
                      Icons.check_rounded,
                      size: 16,
                      color: AppTheme.canvas,
                    ),
                  ),
                ),
            ],
          ),
        ),
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
      height: 52,
      child: ElevatedButton(
        onPressed: selectedCount == 0
            ? null
            : () async {
                HapticFeedback.mediumImpact();
                final configured = controller.buildConfiguredProject();
                if (configured == null) return;
                final updated = await context.push<DanceProject>(
                  '/effect_editor',
                  extra: configured,
                );
                if (updated != null) controller.updateProject(updated);
              },
        child: Text(selectedCount == 0 ? '请选择人物' : '继续 · 已选择 $selectedCount 人'),
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
      color: selected ? AppTheme.primaryWhite : Colors.transparent,
      borderRadius: BorderRadius.circular(10),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(10),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(
              icon,
              size: 18,
              color: selected ? AppTheme.canvas : AppTheme.metalMid,
            ),
            const SizedBox(width: 7),
            Text(
              label,
              style: TextStyle(
                color: selected ? AppTheme.canvas : AppTheme.textSecondary,
                fontSize: 13,
                fontWeight: FontWeight.w700,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _TopButton extends StatelessWidget {
  final IconData icon;
  final String tooltip;
  final VoidCallback onPressed;

  const _TopButton({
    required this.icon,
    required this.tooltip,
    required this.onPressed,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      width: AppTheme.minTouchTarget,
      height: AppTheme.minTouchTarget,
      decoration: BoxDecoration(
        color: AppTheme.surface.withAlpha(235),
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: AppTheme.surfaceBorder),
      ),
      child: IconButton(
        tooltip: tooltip,
        onPressed: onPressed,
        icon: Icon(icon, size: 21),
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
              Icon(icon, size: 42, color: AppTheme.metalMid),
            const SizedBox(height: 18),
            Text(
              title,
              textAlign: TextAlign.center,
              style: const TextStyle(
                color: AppTheme.textPrimary,
                fontSize: 17,
                fontWeight: FontWeight.w700,
              ),
            ),
            const SizedBox(height: 7),
            Text(
              subtitle,
              textAlign: TextAlign.center,
              style: const TextStyle(color: AppTheme.textMuted, fontSize: 13),
            ),
            if (actionLabel != null && onAction != null) ...[
              const SizedBox(height: 20),
              OutlinedButton(onPressed: onAction, child: Text(actionLabel!)),
            ],
          ],
        ),
      ),
    );
  }
}
