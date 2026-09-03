import 'dart:io';

import 'package:dance_domain/dance_domain.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../app/theme.dart';
import '../../../core/widgets/bottom_control_drawer.dart';
import '../../../core/widgets/stage_viewport.dart';
import '../../export/presentation/export_screen.dart';
import '../domain/effect_editor_state.dart';
import 'effect_editor_controller.dart';

class EffectEditorScreen extends ConsumerStatefulWidget {
  final DanceProject project;

  const EffectEditorScreen({super.key, required this.project});

  @override
  ConsumerState<EffectEditorScreen> createState() => _EffectEditorScreenState();
}

class _EffectEditorScreenState extends ConsumerState<EffectEditorScreen> {
  final TransformationController _stageTransformationController =
      TransformationController();
  final DraggableScrollableController _drawerController =
      DraggableScrollableController();
  bool _showAdvancedEffects = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      ref.read(effectEditorControllerProvider.notifier).init(widget.project);
    });
  }

  @override
  void dispose() {
    _stageTransformationController.dispose();
    _drawerController.dispose();
    super.dispose();
  }

  void _resetToDefault(EffectEditorController controller) {
    HapticFeedback.mediumImpact();
    controller.updateFillMode(FillMode.solid);
    controller.updateOpacity(1.0);
    controller.updateFillColor(0xFF000000);
    controller.updateBorderWidth(0.0);
    controller.updateBorderColor(0xFFFFFFFF);
    controller.updateBlurStrength(15.0);
    controller.updateSkinWhiten(0.0);
    controller.updateLegStretch(enabled: false, stretch: 0.15);
    controller.updateFollowConfig(enabled: false);
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(const SnackBar(content: Text('已恢复默认效果')));
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(effectEditorControllerProvider);
    final controller = ref.read(effectEditorControllerProvider.notifier);

    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, result) {
        if (didPop) return;
        context.pop(controller.buildConfiguredProject());
      },
      child: Scaffold(
        backgroundColor: AppTheme.background,
        resizeToAvoidBottomInset: false,
        body: Stack(
          children: [
            Positioned.fill(
              child: StageViewport(
                transformationController: _stageTransformationController,
                child: _buildStagePreview(state),
              ),
            ),
            Positioned(
              top: 0,
              left: 0,
              right: 0,
              child: SafeArea(
                bottom: false,
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(14, 8, 14, 0),
                  child: _buildTopBar(controller),
                ),
              ),
            ),
            Positioned.fill(
              child: BottomControlDrawer(
                controller: _drawerController,
                minChildSize: 0.065,
                initialChildSize: 0.38,
                maxChildSize: 0.80,
                snapSizes: const [0.065, 0.38, 0.80],
                peekHeader: _buildDrawerHeader(state),
                bottomActionBar: _buildExportButton(state, controller),
                child: _buildDrawerContent(state, controller),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildTopBar(EffectEditorController controller) {
    return Row(
      children: [
        _TopButton(
          icon: Icons.arrow_back_rounded,
          tooltip: '返回',
          onPressed: () {
            HapticFeedback.lightImpact();
            context.pop(controller.buildConfiguredProject());
          },
        ),
        const Expanded(
          child: Text(
            '编辑效果',
            textAlign: TextAlign.center,
            style: TextStyle(
              color: AppTheme.textPrimary,
              fontSize: 16,
              fontWeight: FontWeight.w700,
            ),
          ),
        ),
        _TopButton(
          icon: Icons.restart_alt_rounded,
          tooltip: '重置',
          onPressed: () => _resetToDefault(controller),
        ),
      ],
    );
  }

  Widget _buildStagePreview(EffectEditorState state) {
    final displayPath = state.previewPath ?? state.previewThumbnailPath;
    final hasImage =
        displayPath != null &&
        displayPath.isNotEmpty &&
        File(displayPath).existsSync();

    return Stack(
      alignment: Alignment.center,
      children: [
        if (hasImage)
          Image.file(
            File(displayPath),
            key: ValueKey('${displayPath}_${state.previewRequestId}'),
            fit: BoxFit.contain,
            gaplessPlayback: true,
          )
        else
          const Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(
                Icons.movie_filter_outlined,
                size: 52,
                color: AppTheme.metalLow,
              ),
              SizedBox(height: 12),
              Text(
                '正在准备效果预览',
                style: TextStyle(color: AppTheme.textMuted, fontSize: 13),
              ),
            ],
          ),
        if (state.previewLoading)
          Positioned(
            top: 76,
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
              decoration: BoxDecoration(
                color: AppTheme.surface.withAlpha(235),
                borderRadius: BorderRadius.circular(18),
                border: Border.all(color: AppTheme.surfaceBorder),
              ),
              child: const Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  SizedBox(
                    width: 14,
                    height: 14,
                    child: CircularProgressIndicator(strokeWidth: 1.8),
                  ),
                  SizedBox(width: 8),
                  Text(
                    '更新预览…',
                    style: TextStyle(
                      color: AppTheme.textSecondary,
                      fontSize: 12,
                    ),
                  ),
                ],
              ),
            ),
          ),
        if (state.previewError != null)
          Positioned(
            top: 76,
            left: 20,
            right: 20,
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 13, vertical: 10),
              decoration: BoxDecoration(
                color: AppTheme.surfaceElevated,
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: AppTheme.error.withAlpha(120)),
              ),
              child: const Row(
                children: [
                  Icon(
                    Icons.error_outline_rounded,
                    size: 18,
                    color: AppTheme.error,
                  ),
                  SizedBox(width: 9),
                  Expanded(
                    child: Text(
                      '预览暂时无法更新，当前参数仍会保留。',
                      style: TextStyle(
                        color: AppTheme.textSecondary,
                        fontSize: 12,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
      ],
    );
  }

  Widget _buildDrawerHeader(EffectEditorState state) {
    final modeLabel = _fillModeLabel(state.effects.fillMode);
    return Padding(
      padding: const EdgeInsets.fromLTRB(18, 0, 18, 8),
      child: Row(
        children: [
          const Icon(Icons.tune_rounded, size: 18, color: AppTheme.metalMid),
          const SizedBox(width: 8),
          const Expanded(
            child: Text(
              '效果调节',
              style: TextStyle(
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
    EffectEditorState state,
    EffectEditorController controller,
  ) {
    final effects = state.effects;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const Text(
          '遮挡样式',
          style: TextStyle(
            color: AppTheme.textSecondary,
            fontSize: 13,
            fontWeight: FontWeight.w700,
          ),
        ),
        const SizedBox(height: 10),
        _buildModeChips(effects.fillMode, controller),
        const SizedBox(height: 20),
        _buildStepSlider(
          label: '强度',
          value: effects.opacity,
          min: 0.1,
          max: 1.0,
          step: 0.05,
          displayValue: '${(effects.opacity * 100).round()}%',
          onChanged: controller.updateOpacity,
        ),
        if (effects.fillMode == FillMode.solid ||
            effects.fillMode == FillMode.gradient) ...[
          const SizedBox(height: 16),
          const Text(
            '颜色',
            style: TextStyle(color: AppTheme.textSecondary, fontSize: 13),
          ),
          const SizedBox(height: 10),
          _buildColorPalette(effects.fillColorArgb, controller.updateFillColor),
        ],
        if (effects.fillMode == FillMode.blur ||
            effects.fillMode == FillMode.mosaic) ...[
          const SizedBox(height: 16),
          _buildStepSlider(
            label: effects.fillMode == FillMode.mosaic ? '马赛克颗粒' : '模糊程度',
            value: effects.blurStrength,
            min: 1,
            max: 30,
            step: 1,
            displayValue: '${effects.blurStrength.round()}',
            onChanged: controller.updateBlurStrength,
          ),
        ],
        const SizedBox(height: 18),
        InkWell(
          onTap: () {
            HapticFeedback.lightImpact();
            setState(() => _showAdvancedEffects = !_showAdvancedEffects);
          },
          borderRadius: BorderRadius.circular(12),
          child: Container(
            constraints: const BoxConstraints(
              minHeight: AppTheme.minTouchTarget,
            ),
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
            decoration: BoxDecoration(
              color: AppTheme.canvas,
              borderRadius: BorderRadius.circular(12),
              border: Border.all(color: AppTheme.surfaceBorder),
            ),
            child: Row(
              children: [
                const Icon(
                  Icons.tune_rounded,
                  size: 18,
                  color: AppTheme.metalMid,
                ),
                const SizedBox(width: 10),
                const Expanded(
                  child: Text(
                    '更多效果',
                    style: TextStyle(
                      color: AppTheme.textPrimary,
                      fontSize: 13,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ),
                AnimatedRotation(
                  turns: _showAdvancedEffects ? 0.5 : 0,
                  duration: const Duration(milliseconds: 180),
                  child: const Icon(
                    Icons.keyboard_arrow_down_rounded,
                    color: AppTheme.metalMid,
                  ),
                ),
              ],
            ),
          ),
        ),
        AnimatedSize(
          duration: const Duration(milliseconds: 220),
          curve: Curves.easeOutCubic,
          child: _showAdvancedEffects
              ? _buildAdvancedEffects(state, controller)
              : const SizedBox.shrink(),
        ),
        const SizedBox(height: 18),
      ],
    );
  }

  Widget _buildAdvancedEffects(
    EffectEditorState state,
    EffectEditorController controller,
  ) {
    final effects = state.effects;
    final followEnabled = state.project?.follow.enabled ?? false;
    return Padding(
      padding: const EdgeInsets.only(top: 18),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _buildStepSlider(
            label: '描边',
            value: effects.borderWidth,
            min: 0,
            max: 20,
            step: 1,
            displayValue: '${effects.borderWidth.round()} px',
            onChanged: controller.updateBorderWidth,
          ),
          if (effects.borderWidth > 0) ...[
            const SizedBox(height: 14),
            const Text(
              '描边颜色',
              style: TextStyle(color: AppTheme.textSecondary, fontSize: 13),
            ),
            const SizedBox(height: 10),
            _buildColorPalette(
              effects.borderColorArgb,
              controller.updateBorderColor,
            ),
          ],
          const SizedBox(height: 18),
          _buildStepSlider(
            label: '人像提亮',
            value: effects.skinWhiten,
            min: 0,
            max: 1,
            step: 0.05,
            displayValue: '${(effects.skinWhiten * 100).round()}%',
            onChanged: controller.updateSkinWhiten,
          ),
          const SizedBox(height: 16),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
            decoration: AppTheme.panelDecoration(radius: 14),
            child: Column(
              children: [
                Row(
                  children: [
                    const Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            '主角跟随',
                            style: TextStyle(
                              color: AppTheme.textPrimary,
                              fontSize: 13,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                          SizedBox(height: 2),
                          Text(
                            '自动保持主角居中',
                            style: TextStyle(
                              color: AppTheme.textMuted,
                              fontSize: 12,
                            ),
                          ),
                        ],
                      ),
                    ),
                    Switch(
                      value: followEnabled,
                      onChanged: (value) {
                        HapticFeedback.lightImpact();
                        controller.updateFollowConfig(enabled: value);
                      },
                    ),
                  ],
                ),
                if (followEnabled) ...[
                  const SizedBox(height: 8),
                  _buildStepSlider(
                    label: '放大',
                    value: state.project!.follow.zoom,
                    min: 1,
                    max: 2.5,
                    step: 0.1,
                    displayValue:
                        '${state.project!.follow.zoom.toStringAsFixed(1)}×',
                    onChanged: (value) =>
                        controller.updateFollowConfig(zoom: value),
                  ),
                ],
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildExportButton(
    EffectEditorState state,
    EffectEditorController controller,
  ) {
    return SizedBox(
      width: double.infinity,
      height: 52,
      child: ElevatedButton.icon(
        onPressed: state.project == null
            ? null
            : () async {
                HapticFeedback.mediumImpact();
                final project = controller.buildConfiguredProject();
                if (project == null) return;
                await _showExportSettings(project);
              },
        icon: const Icon(Icons.ios_share_rounded, size: 19),
        label: const Text('导出'),
      ),
    );
  }

  Future<void> _showExportSettings(DanceProject project) async {
    var selectedProfile = 'quality';
    final profile = await showModalBottomSheet<String>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      showDragHandle: true,
      backgroundColor: AppTheme.surface,
      builder: (sheetContext) {
        return StatefulBuilder(
          builder: (context, setSheetState) {
            return Padding(
              padding: const EdgeInsets.fromLTRB(20, 4, 20, 20),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  const Text(
                    '导出设置',
                    style: TextStyle(
                      color: AppTheme.textPrimary,
                      fontSize: 20,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                  const SizedBox(height: 4),
                  const Text(
                    '选择效果与处理时间的平衡',
                    style: TextStyle(color: AppTheme.textMuted, fontSize: 13),
                  ),
                  const SizedBox(height: 18),
                  _ExportProfileCard(
                    icon: Icons.diamond_outlined,
                    title: '最佳效果',
                    subtitle: '效果优先，耗时较长',
                    selected: selectedProfile == 'quality',
                    onTap: () =>
                        setSheetState(() => selectedProfile = 'quality'),
                  ),
                  const SizedBox(height: 10),
                  _ExportProfileCard(
                    icon: Icons.balance_rounded,
                    title: '均衡',
                    subtitle: '效果与速度平衡',
                    selected: selectedProfile == 'balanced',
                    onTap: () =>
                        setSheetState(() => selectedProfile = 'balanced'),
                  ),
                  const SizedBox(height: 10),
                  _ExportProfileCard(
                    icon: Icons.bolt_rounded,
                    title: '最快',
                    subtitle: '缩短等待时间',
                    selected: selectedProfile == 'speed',
                    onTap: () => setSheetState(() => selectedProfile = 'speed'),
                  ),
                  const SizedBox(height: 22),
                  SizedBox(
                    height: 52,
                    child: ElevatedButton(
                      onPressed: () =>
                          Navigator.of(sheetContext).pop(selectedProfile),
                      child: const Text('开始导出'),
                    ),
                  ),
                ],
              ),
            );
          },
        );
      },
    );

    if (!mounted || profile == null) return;
    await context.push(
      '/export',
      extra: ExportArgs(project: project, processingProfile: profile),
    );
  }

  Widget _buildModeChips(FillMode current, EffectEditorController controller) {
    final modes = [
      (FillMode.mosaic, '马赛克', Icons.grid_4x4_rounded),
      (FillMode.blur, '模糊', Icons.blur_on_rounded),
      (FillMode.solid, '色块', Icons.crop_square_rounded),
      (FillMode.gradient, '渐变', Icons.gradient_rounded),
    ];

    return Wrap(
      spacing: 8,
      runSpacing: 8,
      children: modes.map((item) {
        final selected = current == item.$1;
        return ChoiceChip(
          showCheckmark: false,
          avatar: Icon(
            item.$3,
            size: 17,
            color: selected ? AppTheme.canvas : AppTheme.metalMid,
          ),
          label: Text(item.$2),
          selected: selected,
          onSelected: (_) {
            HapticFeedback.selectionClick();
            controller.updateFillMode(item.$1);
          },
          labelStyle: TextStyle(
            color: selected ? AppTheme.canvas : AppTheme.textSecondary,
            fontSize: 12,
            fontWeight: FontWeight.w600,
          ),
          selectedColor: AppTheme.primaryWhite,
          backgroundColor: AppTheme.surfaceHigh,
          side: BorderSide(
            color: selected ? AppTheme.primaryWhite : AppTheme.surfaceBorder,
          ),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(10),
          ),
          padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 7),
        );
      }).toList(),
    );
  }

  Widget _buildStepSlider({
    required String label,
    required double value,
    required double min,
    required double max,
    required double step,
    required String displayValue,
    required ValueChanged<double> onChanged,
  }) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Expanded(
              child: Text(
                label,
                style: const TextStyle(
                  color: AppTheme.textSecondary,
                  fontSize: 13,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ),
            Text(
              displayValue,
              style: const TextStyle(
                color: AppTheme.textPrimary,
                fontSize: 13,
                fontWeight: FontWeight.w600,
              ),
            ),
          ],
        ),
        const SizedBox(height: 4),
        Row(
          children: [
            SizedBox(
              width: AppTheme.minTouchTarget,
              height: AppTheme.minTouchTarget,
              child: IconButton(
                tooltip: '减少 $label',
                onPressed: value <= min
                    ? null
                    : () {
                        HapticFeedback.selectionClick();
                        onChanged((value - step).clamp(min, max));
                      },
                icon: const Icon(Icons.remove_rounded, size: 20),
              ),
            ),
            Expanded(
              child: Slider(
                value: value.clamp(min, max),
                min: min,
                max: max,
                onChanged: onChanged,
              ),
            ),
            SizedBox(
              width: AppTheme.minTouchTarget,
              height: AppTheme.minTouchTarget,
              child: IconButton(
                tooltip: '增加 $label',
                onPressed: value >= max
                    ? null
                    : () {
                        HapticFeedback.selectionClick();
                        onChanged((value + step).clamp(min, max));
                      },
                icon: const Icon(Icons.add_rounded, size: 20),
              ),
            ),
          ],
        ),
      ],
    );
  }

  Widget _buildColorPalette(int currentArgb, ValueChanged<int> onSelect) {
    const colors = [
      0xFF000000,
      0xFF1A1A1A,
      0xFF3A3A3A,
      0xFF707070,
      0xFFB8B8B8,
      0xFFFFFFFF,
    ];

    return Wrap(
      spacing: 12,
      runSpacing: 10,
      children: colors.map((argb) {
        final selected = currentArgb == argb;
        return Semantics(
          button: true,
          selected: selected,
          label: '颜色选项',
          child: GestureDetector(
            onTap: () {
              HapticFeedback.selectionClick();
              onSelect(argb);
            },
            child: AnimatedContainer(
              duration: const Duration(milliseconds: 160),
              width: 40,
              height: 40,
              decoration: BoxDecoration(
                color: Color(argb),
                shape: BoxShape.circle,
                border: Border.all(
                  color: selected ? AppTheme.primaryWhite : AppTheme.metalLow,
                  width: selected ? 3 : 1,
                ),
              ),
              child: selected
                  ? Icon(
                      Icons.check_rounded,
                      size: 20,
                      color: argb == 0xFFFFFFFF || argb == 0xFFB8B8B8
                          ? AppTheme.canvas
                          : AppTheme.primaryWhite,
                    )
                  : null,
            ),
          ),
        );
      }).toList(),
    );
  }

  String _fillModeLabel(FillMode mode) {
    return switch (mode) {
      FillMode.solid => '色块',
      FillMode.blur => '模糊',
      FillMode.gradient => '渐变',
      FillMode.mosaic => '马赛克',
      FillMode.sticker => '贴纸',
    };
  }
}

class _ExportProfileCard extends StatelessWidget {
  final IconData icon;
  final String title;
  final String subtitle;
  final bool selected;
  final VoidCallback onTap;

  const _ExportProfileCard({
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.selected,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return Material(
      color: selected ? AppTheme.surfaceHigh : AppTheme.canvas,
      borderRadius: BorderRadius.circular(14),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(14),
        child: Container(
          constraints: const BoxConstraints(minHeight: 78),
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 13),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(14),
            border: Border.all(
              color: selected ? AppTheme.metalHigh : AppTheme.surfaceBorder,
              width: selected ? 1.5 : 1,
            ),
          ),
          child: Row(
            children: [
              Icon(icon, color: AppTheme.metalHigh, size: 24),
              const SizedBox(width: 14),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
                      title,
                      style: const TextStyle(
                        color: AppTheme.textPrimary,
                        fontSize: 15,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    const SizedBox(height: 3),
                    Text(
                      subtitle,
                      style: const TextStyle(
                        color: AppTheme.textMuted,
                        fontSize: 12,
                      ),
                    ),
                  ],
                ),
              ),
              if (selected)
                const Icon(
                  Icons.check_circle_rounded,
                  color: AppTheme.primaryWhite,
                  size: 21,
                ),
            ],
          ),
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
