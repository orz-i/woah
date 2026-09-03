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

  bool _isFaceMode(EffectEditorState state) {
    final project = state.project ?? widget.project;
    return project.faceOnlyPersonIds.isNotEmpty &&
        project.selectedPersonIds.isEmpty;
  }

  void _resetToDefault(
    EffectEditorController controller,
    EffectEditorState state,
  ) {
    HapticFeedback.mediumImpact();
    controller.updateProtectionStyle(
      _isFaceMode(state) ? FillMode.sticker : FillMode.solid,
    );
    if (_isFaceMode(state)) {
      controller.updateStickerAsset('builtin:sunglasses');
      controller.updateStickerScale(1.0);
    }
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
      child: AnnotatedRegion<SystemUiOverlayStyle>(
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
          resizeToAvoidBottomInset: false,
          body: Stack(
            children: [
              Positioned(
                top: 132,
                left: 18,
                right: 18,
                bottom: 48,
                child: ClipRRect(
                  borderRadius: BorderRadius.circular(28),
                  child: StageViewport(
                    transformationController: _stageTransformationController,
                    backgroundColor: const Color(0xFFF0E6E0),
                    child: _buildStagePreview(state),
                  ),
                ),
              ),
              Positioned(
                top: 0,
                left: 0,
                right: 0,
                child: SafeArea(
                  bottom: false,
                  child: _buildTopBar(state, controller),
                ),
              ),
              Positioned.fill(
                child: BottomControlDrawer(
                  controller: _drawerController,
                  minChildSize: 0.065,
                  initialChildSize: 0.38,
                  maxChildSize: 0.74,
                  snapSizes: const [0.065, 0.38, 0.74],
                  panelColor: AppTheme.warmSurface,
                  panelBorderColor: AppTheme.warmBorder,
                  handleColor: AppTheme.warmBorder,
                  panelRadius: 30,
                  panelShadow: const [
                    BoxShadow(
                      color: Color(0x18000000),
                      blurRadius: 28,
                      offset: Offset(0, -8),
                    ),
                  ],
                  bottomActionBorderColor: Colors.transparent,
                  peekHeader: _buildDrawerHeader(state),
                  bottomActionBar: _buildExportButton(state, controller),
                  child: _buildDrawerContent(state, controller),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildTopBar(
    EffectEditorState state,
    EffectEditorController controller,
  ) {
    final faceMode = _isFaceMode(state);
    return SizedBox(
      height: 118,
      child: Stack(
        children: [
          Positioned(
            top: 8,
            left: 18,
            child: _TopButton(
              icon: Icons.close_rounded,
              tooltip: '关闭',
              onPressed: () {
                HapticFeedback.lightImpact();
                context.pop(controller.buildConfiguredProject());
              },
            ),
          ),
          Positioned(
            top: 8,
            right: 18,
            child: _TopButton(
              icon: Icons.restart_alt_rounded,
              tooltip: '重置',
              outlined: true,
              onPressed: () => _resetToDefault(controller, state),
            ),
          ),
          Positioned(
            top: 54,
            left: 74,
            right: 74,
            child: Column(
              children: [
                const Text(
                  '编辑效果',
                  textAlign: TextAlign.center,
                  style: TextStyle(
                    color: AppTheme.warmTextPrimary,
                    fontSize: 22,
                    height: 1.15,
                    fontWeight: FontWeight.w700,
                    letterSpacing: -0.5,
                  ),
                ),
                const SizedBox(height: 8),
                Text(
                  faceMode ? '为选中的人脸设置遮挡保护效果' : '为选中的人物设置遮挡保护效果',
                  textAlign: TextAlign.center,
                  style: const TextStyle(
                    color: AppTheme.warmTextSecondary,
                    fontSize: 12,
                    height: 1.35,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildStagePreview(EffectEditorState state) {
    final faceMode = _isFaceMode(state);
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
                color: AppTheme.warmTextMuted,
              ),
              SizedBox(height: 12),
              Text(
                '正在准备效果预览',
                style: TextStyle(
                  color: AppTheme.warmTextSecondary,
                  fontSize: 13,
                ),
              ),
            ],
          ),
        Positioned(
          left: 14,
          bottom: 14,
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 11, vertical: 7),
            decoration: BoxDecoration(
              color: AppTheme.warmSurface.withAlpha(235),
              borderRadius: BorderRadius.circular(16),
              border: Border.all(color: AppTheme.warmBorder),
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(
                  faceMode
                      ? Icons.face_retouching_off_rounded
                      : Icons.accessibility_new_rounded,
                  size: 16,
                  color: AppTheme.coral,
                ),
                const SizedBox(width: 6),
                Text(
                  faceMode ? '人脸保护' : '全身保护',
                  style: const TextStyle(
                    color: AppTheme.warmTextPrimary,
                    fontSize: 11,
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ],
            ),
          ),
        ),
        if (state.previewLoading)
          Positioned(
            top: 14,
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
              decoration: BoxDecoration(
                color: AppTheme.warmSurface.withAlpha(240),
                borderRadius: BorderRadius.circular(18),
                border: Border.all(color: AppTheme.warmBorder),
              ),
              child: const Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  SizedBox(
                    width: 14,
                    height: 14,
                    child: CircularProgressIndicator(
                      strokeWidth: 1.8,
                      color: AppTheme.coral,
                    ),
                  ),
                  SizedBox(width: 8),
                  Text(
                    '更新预览…',
                    style: TextStyle(
                      color: AppTheme.warmTextSecondary,
                      fontSize: 12,
                    ),
                  ),
                ],
              ),
            ),
          ),
        if (state.previewError != null)
          Positioned(
            top: 14,
            left: 20,
            right: 20,
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 13, vertical: 10),
              decoration: BoxDecoration(
                color: const Color(0xFFFFF4F1),
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: AppTheme.coral.withAlpha(100)),
              ),
              child: const Row(
                children: [
                  Icon(
                    Icons.error_outline_rounded,
                    size: 18,
                    color: AppTheme.coral,
                  ),
                  SizedBox(width: 9),
                  Expanded(
                    child: Text(
                      '预览暂时无法更新，当前参数仍会保留。',
                      style: TextStyle(
                        color: AppTheme.warmTextSecondary,
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
    final modeLabel = state.effects.faceStickerEnabled
        ? '贴纸'
        : _fillModeLabel(state.effects.fillMode);
    return Padding(
      padding: const EdgeInsets.fromLTRB(18, 0, 18, 8),
      child: Row(
        children: [
          const Icon(Icons.tune_rounded, size: 18, color: AppTheme.coral),
          const SizedBox(width: 8),
          const Expanded(
            child: Text(
              '遮挡效果',
              style: TextStyle(
                color: AppTheme.warmTextPrimary,
                fontSize: 14,
                fontWeight: FontWeight.w700,
              ),
            ),
          ),
          Text(
            modeLabel,
            style: const TextStyle(
              color: AppTheme.warmTextSecondary,
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
    final faceMode = _isFaceMode(state);
    final activeMode = effects.faceStickerEnabled
        ? FillMode.sticker
        : effects.fillMode;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text(
          faceMode ? '人脸遮挡样式' : '全身遮挡样式',
          style: TextStyle(
            color: AppTheme.warmTextPrimary,
            fontSize: 13,
            fontWeight: FontWeight.w700,
          ),
        ),
        const SizedBox(height: 10),
        _buildModeChips(activeMode, controller, faceMode: faceMode),
        if (faceMode && effects.faceStickerEnabled) ...[
          const SizedBox(height: 20),
          const Text(
            '贴纸',
            style: TextStyle(
              color: AppTheme.warmTextPrimary,
              fontSize: 13,
              fontWeight: FontWeight.w700,
            ),
          ),
          const SizedBox(height: 10),
          _buildStickerPicker(effects, controller),
          const SizedBox(height: 16),
          _buildStepSlider(
            label: '贴纸大小',
            value: effects.stickerScale,
            min: 1.0,
            max: 2.0,
            step: 0.1,
            displayValue: '${(effects.stickerScale * 100).round()}%',
            onChanged: controller.updateStickerScale,
          ),
        ],
        if (!effects.faceStickerEnabled) ...[
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
        ],
        if (effects.fillMode == FillMode.solid ||
            effects.fillMode == FillMode.gradient &&
                !effects.faceStickerEnabled) ...[
          const SizedBox(height: 16),
          const Text(
            '颜色',
            style: TextStyle(
              color: AppTheme.warmTextPrimary,
              fontSize: 13,
              fontWeight: FontWeight.w600,
            ),
          ),
          const SizedBox(height: 10),
          _buildColorPalette(effects.fillColorArgb, controller.updateFillColor),
        ],
        if (effects.fillMode == FillMode.blur ||
            effects.fillMode == FillMode.mosaic &&
                !effects.faceStickerEnabled) ...[
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
              color: AppTheme.warmSurfaceSoft,
              borderRadius: BorderRadius.circular(12),
              border: Border.all(color: AppTheme.warmBorder),
            ),
            child: Row(
              children: [
                const Icon(Icons.tune_rounded, size: 18, color: AppTheme.coral),
                const SizedBox(width: 10),
                const Expanded(
                  child: Text(
                    '更多效果',
                    style: TextStyle(
                      color: AppTheme.warmTextPrimary,
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
                    color: AppTheme.warmTextSecondary,
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
              style: TextStyle(color: AppTheme.warmTextPrimary, fontSize: 13),
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
            decoration: BoxDecoration(
              color: AppTheme.warmSurfaceSoft,
              borderRadius: BorderRadius.circular(14),
              border: Border.all(color: AppTheme.warmBorder),
            ),
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
                              color: AppTheme.warmTextPrimary,
                              fontSize: 13,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                          SizedBox(height: 2),
                          Text(
                            '自动保持主角居中',
                            style: TextStyle(
                              color: AppTheme.warmTextSecondary,
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
    final enabled = state.project != null;
    return Opacity(
      opacity: enabled ? 1 : 0.45,
      child: Material(
        color: Colors.transparent,
        borderRadius: BorderRadius.circular(18),
        child: InkWell(
          onTap: !enabled
              ? null
              : () async {
                  HapticFeedback.mediumImpact();
                  final project = controller.buildConfiguredProject();
                  if (project == null) return;
                  await _showExportSettings(project);
                },
          borderRadius: BorderRadius.circular(18),
          child: Ink(
            height: 58,
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
            child: const Padding(
              padding: EdgeInsets.symmetric(horizontal: 22),
              child: Row(
                children: [
                  SizedBox(width: 28),
                  Expanded(
                    child: Text(
                      '导出',
                      textAlign: TextAlign.center,
                      style: TextStyle(
                        color: Colors.white,
                        fontSize: 17,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ),
                  Icon(Icons.ios_share_rounded, color: Colors.white, size: 24),
                ],
              ),
            ),
          ),
        ),
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
      backgroundColor: AppTheme.warmSurface,
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
                      color: AppTheme.warmTextPrimary,
                      fontSize: 20,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                  const SizedBox(height: 4),
                  const Text(
                    '选择效果与处理时间的平衡',
                    style: TextStyle(
                      color: AppTheme.warmTextSecondary,
                      fontSize: 13,
                    ),
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
                    height: 56,
                    child: Material(
                      color: Colors.transparent,
                      borderRadius: BorderRadius.circular(18),
                      child: InkWell(
                        onTap: () =>
                            Navigator.of(sheetContext).pop(selectedProfile),
                        borderRadius: BorderRadius.circular(18),
                        child: Ink(
                          decoration: BoxDecoration(
                            gradient: AppTheme.coralActionGradient,
                            borderRadius: BorderRadius.circular(18),
                          ),
                          child: const Center(
                            child: Text(
                              '开始导出',
                              style: TextStyle(
                                color: Colors.white,
                                fontSize: 16,
                                fontWeight: FontWeight.w600,
                              ),
                            ),
                          ),
                        ),
                      ),
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

  Widget _buildModeChips(
    FillMode current,
    EffectEditorController controller, {
    required bool faceMode,
  }) {
    final modes = <(FillMode, String, IconData)>[
      if (faceMode)
        (FillMode.sticker, '贴纸', Icons.sentiment_satisfied_alt_rounded),
      (FillMode.mosaic, '马赛克', Icons.grid_4x4_rounded),
      (FillMode.blur, '模糊', Icons.blur_on_rounded),
      (FillMode.solid, '色块', Icons.crop_square_rounded),
      (FillMode.gradient, '渐变', Icons.gradient_rounded),
    ];

    return SizedBox(
      height: 86,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        physics: const BouncingScrollPhysics(),
        itemCount: modes.length,
        separatorBuilder: (_, _) => const SizedBox(width: 9),
        itemBuilder: (context, index) {
          final item = modes[index];
          final selected = current == item.$1;
          return Semantics(
            button: true,
            selected: selected,
            label: item.$2,
            child: GestureDetector(
              onTap: () {
                HapticFeedback.selectionClick();
                controller.updateProtectionStyle(item.$1);
              },
              child: AnimatedContainer(
                duration: const Duration(milliseconds: 160),
                width: 76,
                padding: const EdgeInsets.symmetric(
                  horizontal: 8,
                  vertical: 10,
                ),
                decoration: BoxDecoration(
                  color: selected
                      ? AppTheme.coralPale
                      : AppTheme.warmSurfaceSoft,
                  borderRadius: BorderRadius.circular(16),
                  border: Border.all(
                    color: selected ? AppTheme.coral : AppTheme.warmBorder,
                    width: selected ? 1.5 : 1,
                  ),
                ),
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Icon(
                      item.$3,
                      size: 24,
                      color: selected
                          ? AppTheme.coral
                          : AppTheme.warmTextSecondary,
                    ),
                    const SizedBox(height: 7),
                    Text(
                      item.$2,
                      style: TextStyle(
                        color: selected
                            ? AppTheme.coralStrong
                            : AppTheme.warmTextPrimary,
                        fontSize: 12,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ],
                ),
              ),
            ),
          );
        },
      ),
    );
  }

  Widget _buildStickerPicker(
    EffectConfig effects,
    EffectEditorController controller,
  ) {
    const stickers = <(String, String, IconData, Color)>[
      ('builtin:sunglasses', '酷脸', Icons.dark_mode_rounded, Color(0xFFFFD84D)),
      ('builtin:blush', '微笑', Icons.favorite_rounded, Color(0xFFFFA3AA)),
      ('builtin:panda', '熊猫', Icons.circle_rounded, Color(0xFFF5F5F2)),
      ('builtin:cat', '猫咪', Icons.pets_rounded, Color(0xFFFFD7A1)),
      ('builtin:bear', '小熊', Icons.pets_outlined, Color(0xFFC58B62)),
    ];
    final current = effects.stickerAssetId ?? 'builtin:sunglasses';
    return SizedBox(
      height: 68,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        physics: const BouncingScrollPhysics(),
        itemCount: stickers.length,
        separatorBuilder: (_, _) => const SizedBox(width: 10),
        itemBuilder: (context, index) {
          final item = stickers[index];
          final selected = current == item.$1;
          return Semantics(
            button: true,
            selected: selected,
            label: '贴纸 ${item.$2}',
            child: GestureDetector(
              onTap: () {
                HapticFeedback.selectionClick();
                controller.updateStickerAsset(item.$1);
              },
              child: AnimatedContainer(
                duration: const Duration(milliseconds: 160),
                width: 58,
                decoration: BoxDecoration(
                  color: item.$4,
                  borderRadius: BorderRadius.circular(18),
                  border: Border.all(
                    color: selected ? AppTheme.coral : AppTheme.warmBorder,
                    width: selected ? 2 : 1,
                  ),
                ),
                child: Stack(
                  alignment: Alignment.center,
                  children: [
                    Icon(item.$3, color: AppTheme.warmTextPrimary, size: 24),
                    if (selected)
                      const Positioned(
                        right: 4,
                        bottom: 4,
                        child: Icon(
                          Icons.check_circle_rounded,
                          color: AppTheme.coral,
                          size: 18,
                        ),
                      ),
                  ],
                ),
              ),
            ),
          );
        },
      ),
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
                  color: AppTheme.warmTextPrimary,
                  fontSize: 13,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ),
            Text(
              displayValue,
              style: const TextStyle(
                color: AppTheme.coral,
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
                activeColor: AppTheme.coral,
                inactiveColor: AppTheme.coralPale,
                thumbColor: AppTheme.coral,
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
      0xFFFF5E5B,
      0xFFFF9EAA,
      0xFF7D9CFF,
      0xFF71C991,
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
                  color: selected ? AppTheme.coral : AppTheme.warmBorder,
                  width: selected ? 3 : 1,
                ),
              ),
              child: selected
                  ? Icon(
                      Icons.check_rounded,
                      size: 20,
                      color: argb == 0xFFFFFFFF
                          ? AppTheme.warmTextPrimary
                          : Colors.white,
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
      color: selected ? AppTheme.coralPale : AppTheme.warmSurfaceSoft,
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
              color: selected ? AppTheme.coral : AppTheme.warmBorder,
              width: selected ? 1.5 : 1,
            ),
          ),
          child: Row(
            children: [
              Icon(
                icon,
                color: selected ? AppTheme.coral : AppTheme.warmTextSecondary,
                size: 24,
              ),
              const SizedBox(width: 14),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
                      title,
                      style: const TextStyle(
                        color: AppTheme.warmTextPrimary,
                        fontSize: 15,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    const SizedBox(height: 3),
                    Text(
                      subtitle,
                      style: const TextStyle(
                        color: AppTheme.warmTextSecondary,
                        fontSize: 12,
                      ),
                    ),
                  ],
                ),
              ),
              if (selected)
                const Icon(
                  Icons.check_circle_rounded,
                  color: AppTheme.coral,
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
  final bool outlined;

  const _TopButton({
    required this.icon,
    required this.tooltip,
    required this.onPressed,
    this.outlined = false,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      width: AppTheme.minTouchTarget,
      height: AppTheme.minTouchTarget,
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
