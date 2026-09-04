import 'dart:io';

import 'package:dance_domain/dance_domain.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../app/theme.dart';
import '../../../core/widgets/main_flow_header.dart';
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
  final ScrollController _scrollController = ScrollController();

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      ref.read(effectEditorControllerProvider.notifier).init(widget.project);
    });
  }

  @override
  void dispose() {
    _scrollController.dispose();
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

    ScaffoldMessenger.of(context).clearSnackBars();
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: const Text(
          '已恢复默认效果',
          textAlign: TextAlign.center,
          style: TextStyle(color: Colors.white, fontSize: 13),
        ),
        behavior: SnackBarBehavior.floating,
        backgroundColor: AppTheme.warmTextPrimary.withValues(alpha: 0.9),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
        duration: const Duration(milliseconds: 1400),
        width: 150,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(effectEditorControllerProvider);
    final controller = ref.read(effectEditorControllerProvider.notifier);
    final project = state.project ?? widget.project;
    final aspectRatio = project.videoInfo.aspectRatio > 0
        ? project.videoInfo.aspectRatio
        : 16 / 9;

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
          body: SafeArea(
            child: Column(
              children: [
                _buildTopBar(state, controller),
                // 主舞台视口：占据上半部稳定视口，等比居中自适应，绝不再被遮挡或挤压
                Expanded(
                  flex: 11,
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(16, 4, 16, 8),
                    child: Center(
                      child: AspectRatio(
                        aspectRatio: aspectRatio,
                        child: DecoratedBox(
                          decoration: BoxDecoration(
                            color: const Color(0xFFF1E7E1),
                            borderRadius: BorderRadius.circular(24),
                            boxShadow: const [
                              BoxShadow(
                                color: Color(0x14000000),
                                blurRadius: 20,
                                offset: Offset(0, 6),
                              ),
                            ],
                          ),
                          child: ClipRRect(
                            borderRadius: BorderRadius.circular(24),
                            child: _buildStagePreview(state),
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
                // 底部常驻控制工作区：占据下半部，独立内部滚动与底栏固定导出
                Expanded(
                  flex: 10,
                  child: _buildBottomControlPanel(state, controller),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildTopBar(
    EffectEditorState state,
    EffectEditorController controller,
  ) {
    return MainFlowHeader(
      title: '编辑效果',
      onClose: () {
        HapticFeedback.lightImpact();
        context.pop(controller.buildConfiguredProject());
      },
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
            fit: BoxFit.cover,
            width: double.infinity,
            height: double.infinity,
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

  Widget _buildBottomControlPanel(
    EffectEditorState state,
    EffectEditorController controller,
  ) {
    final effects = state.effects;
    final faceMode = _isFaceMode(state);
    final activeMode = effects.faceStickerEnabled
        ? FillMode.sticker
        : effects.fillMode;
    return Container(
      decoration: const BoxDecoration(
        color: AppTheme.warmSurface,
        borderRadius: BorderRadius.vertical(top: Radius.circular(28)),
        boxShadow: [
          BoxShadow(
            color: Color(0x14000000),
            blurRadius: 24,
            offset: Offset(0, -6),
          ),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Expanded(
            child: SingleChildScrollView(
              controller: _scrollController,
              physics: const BouncingScrollPhysics(),
              padding: const EdgeInsets.fromLTRB(18, 14, 18, 6),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  // 1. 顶部标题与一键恢复默认
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      Text(
                        faceMode
                            ? '人脸遮挡 · ${_fillModeLabel(activeMode)}'
                            : '全身遮挡 · ${_fillModeLabel(activeMode)}',
                        style: const TextStyle(
                          color: AppTheme.warmTextPrimary,
                          fontSize: 14,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                      Semantics(
                        button: true,
                        label: '恢复默认效果',
                        child: InkWell(
                          onTap: () => _resetToDefault(controller, state),
                          borderRadius: BorderRadius.circular(12),
                          child: const Padding(
                            padding: EdgeInsets.symmetric(
                              horizontal: 6,
                              vertical: 3,
                            ),
                            child: Row(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                Icon(
                                  Icons.restart_alt_rounded,
                                  size: 16,
                                  color: AppTheme.warmTextSecondary,
                                ),
                                SizedBox(width: 4),
                                Text(
                                  '恢复默认',
                                  style: TextStyle(
                                    color: AppTheme.warmTextSecondary,
                                    fontSize: 12,
                                    fontWeight: FontWeight.w500,
                                  ),
                                ),
                              ],
                            ),
                          ),
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 10),
                  // 2. 模式切换 Chips
                  _buildModeChips(activeMode, controller, faceMode: faceMode),
                  const SizedBox(height: 12),
                  // 3. 当前模式专属调节项
                  if (faceMode && effects.faceStickerEnabled) ...[
                    _buildStickerPicker(effects, controller),
                    const SizedBox(height: 10),
                    _buildStepSlider(
                      label: '贴纸大小',
                      value: effects.stickerScale,
                      min: 1.0,
                      max: 2.0,
                      step: 0.1,
                      displayValue: '${(effects.stickerScale * 100).round()}%',
                      onChanged: controller.updateStickerScale,
                    ),
                  ] else ...[
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
                      (effects.fillMode == FillMode.gradient &&
                          !effects.faceStickerEnabled)) ...[
                    const SizedBox(height: 10),
                    const Text(
                      '颜色',
                      style: TextStyle(
                        color: AppTheme.warmTextPrimary,
                        fontSize: 12.5,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                    const SizedBox(height: 8),
                    _buildColorPalette(
                      effects.fillColorArgb,
                      controller.updateFillColor,
                    ),
                  ],
                  if ((effects.fillMode == FillMode.blur ||
                          effects.fillMode == FillMode.mosaic) &&
                      !effects.faceStickerEnabled) ...[
                    const SizedBox(height: 10),
                    _buildStepSlider(
                      label: effects.fillMode == FillMode.mosaic
                          ? '马赛克颗粒'
                          : '模糊程度',
                      value: effects.blurStrength,
                      min: 1,
                      max: 30,
                      step: 1,
                      displayValue: '${effects.blurStrength.round()}',
                      onChanged: controller.updateBlurStrength,
                    ),
                  ],
                  const SizedBox(height: 10),
                  _buildStepSlider(
                    label: '描边宽度',
                    value: effects.borderWidth,
                    min: 0,
                    max: 20,
                    step: 1,
                    displayValue: '${effects.borderWidth.round()} px',
                    onChanged: controller.updateBorderWidth,
                  ),
                  if (effects.borderWidth > 0) ...[
                    const SizedBox(height: 8),
                    const Text(
                      '描边颜色',
                      style: TextStyle(
                        color: AppTheme.warmTextPrimary,
                        fontSize: 12.5,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                    const SizedBox(height: 6),
                    _buildColorPalette(
                      effects.borderColorArgb,
                      controller.updateBorderColor,
                    ),
                  ],
                  const SizedBox(height: 8),
                ],
              ),
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(18, 6, 18, 14),
            child: _buildExportButton(state, controller),
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
            height: 52,
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
                  SizedBox(width: 24),
                  Expanded(
                    child: Text(
                      '下一步: 导出',
                      textAlign: TextAlign.center,
                      style: TextStyle(
                        color: Colors.white,
                        fontSize: 16,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ),
                  Icon(
                    Icons.arrow_forward_rounded,
                    color: Colors.white,
                    size: 20,
                  ),
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
    final editorState = ref.read(effectEditorControllerProvider);
    final initialPreviewPath =
        editorState.previewPath ?? editorState.previewThumbnailPath;
    await context.push(
      '/export',
      extra: ExportArgs(
        project: project,
        processingProfile: profile,
        initialPreviewPath: initialPreviewPath,
      ),
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
      height: 72,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        physics: const BouncingScrollPhysics(),
        itemCount: modes.length,
        separatorBuilder: (_, _) => const SizedBox(width: 8),
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
                width: 68,
                padding: const EdgeInsets.symmetric(
                  horizontal: 6,
                  vertical: 8,
                ),
                decoration: BoxDecoration(
                  color: selected
                      ? AppTheme.coralPale
                      : AppTheme.warmSurfaceSoft,
                  borderRadius: BorderRadius.circular(14),
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
                      size: 22,
                      color: selected
                          ? AppTheme.coral
                          : AppTheme.warmTextSecondary,
                    ),
                    const SizedBox(height: 5),
                    Text(
                      item.$2,
                      style: TextStyle(
                        color: selected
                            ? AppTheme.coralStrong
                            : AppTheme.warmTextPrimary,
                        fontSize: 11.5,
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
