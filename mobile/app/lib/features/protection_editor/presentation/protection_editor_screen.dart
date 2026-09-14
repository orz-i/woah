import 'dart:io';
import 'dart:math' as math;

import 'package:dance_domain/dance_domain.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../app/theme.dart';
import '../../../core/widgets/flow_back_button.dart';
import '../../../core/widgets/immersive_flow_action.dart';
import '../../../core/widgets/stage_viewport.dart';
import '../../effect_editor/domain/effect_editor_state.dart';
import '../../effect_editor/presentation/effect_editor_controller.dart';
import '../../export/presentation/export_screen.dart';
import '../../person_selection/domain/person_selection_state.dart';
import '../../person_selection/presentation/person_selection_controller.dart';

class ProtectionEditorArgs {
  final DanceProject project;
  final EffectConfig? fullBodyDraft;
  final EffectConfig? faceOnlyDraft;
  final String processingProfile;

  const ProtectionEditorArgs({
    required this.project,
    this.fullBodyDraft,
    this.faceOnlyDraft,
    this.processingProfile = 'quality',
  });
}

class ProtectionEditorResult {
  final DanceProject project;
  final EffectConfig fullBodyDraft;
  final EffectConfig faceOnlyDraft;
  final String processingProfile;

  const ProtectionEditorResult({
    required this.project,
    required this.fullBodyDraft,
    required this.faceOnlyDraft,
    required this.processingProfile,
  });
}

class ProtectionEditorScreen extends ConsumerStatefulWidget {
  final DanceProject project;
  final EffectConfig? fullBodyDraft;
  final EffectConfig? faceOnlyDraft;
  final String processingProfile;

  const ProtectionEditorScreen({
    super.key,
    required this.project,
    this.fullBodyDraft,
    this.faceOnlyDraft,
    this.processingProfile = 'quality',
  });

  @override
  ConsumerState<ProtectionEditorScreen> createState() =>
      _ProtectionEditorScreenState();
}

class _ProtectionEditorScreenState
    extends ConsumerState<ProtectionEditorScreen> {
  final ScrollController _scrollController = ScrollController();

  late String _processingProfile;
  EffectConfig? _fullBodyDraft;
  EffectConfig? _faceOnlyDraft;
  bool _allowRoutePop = false;
  bool _returnRequested = false;

  @override
  void initState() {
    super.initState();
    _processingProfile = widget.processingProfile;
    _fullBodyDraft = widget.fullBodyDraft;
    _faceOnlyDraft = widget.faceOnlyDraft;
    WidgetsBinding.instance.addPostFrameCallback((_) => _initializeEditor());
  }

  Future<void> _initializeEditor() async {
    final selectionController = ref.read(
      personSelectionControllerProvider.notifier,
    );
    await selectionController.prepareProject(
      widget.project,
      selectionPreviewEnabled: false,
    );
    if (!mounted) return;

    final selectionState = ref.read(personSelectionControllerProvider);
    final configured = selectionController.buildConfiguredProject();
    if (configured == null || selectionState.persons.isEmpty) return;

    final isFaceMode =
        selectionState.privacyMode == ProjectPrivacyMode.faceOnly;
    if (isFaceMode) {
      _faceOnlyDraft ??= _normalizeFaceDraft(configured.effects);
      _fullBodyDraft ??= _defaultFullBodyDraft(configured.effects);
    } else {
      _fullBodyDraft ??= _normalizeFullBodyDraft(configured.effects);
      _faceOnlyDraft ??= _defaultFaceDraft(configured.effects);
    }

    final activeEffects = isFaceMode ? _faceOnlyDraft! : _fullBodyDraft!;
    ref
        .read(effectEditorControllerProvider.notifier)
        .init(configured.copyWith(effects: activeEffects));
  }

  @override
  void dispose() {
    _scrollController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final selectionState = ref.watch(personSelectionControllerProvider);
    final selectionController = ref.read(
      personSelectionControllerProvider.notifier,
    );
    final effectState = ref.watch(effectEditorControllerProvider);
    final effectController = ref.read(effectEditorControllerProvider.notifier);

    final showControls =
        selectionState.status == PersonSelectionStatus.ready &&
        selectionState.persons.isNotEmpty &&
        effectState.project != null;
    final nextEnabled =
        showControls && selectionState.privacyTargetIds.isNotEmpty;
    final project =
        effectState.project ?? selectionState.project ?? widget.project;
    final aspectRatio = project.videoInfo.aspectRatio > 0
        ? project.videoInfo.aspectRatio
        : 9 / 16;

    return PopScope(
      canPop: _allowRoutePop,
      onPopInvokedWithResult: (didPop, result) {
        if (didPop) return;
        _requestReturn();
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
            child: LayoutBuilder(
              builder: (context, constraints) {
                final availableHeight = (constraints.maxHeight - 48).clamp(
                  0.0,
                  double.infinity,
                );
                final stageMaxHeight =
                    availableHeight * (showControls ? 0.38 : 0.64);
                return Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Padding(
                      padding: const EdgeInsets.fromLTRB(16, 4, 16, 6),
                      child: SizedBox(
                        height: 38,
                        child: Align(
                          alignment: Alignment.centerLeft,
                          child: FlowBackButton(onPressed: _requestReturn),
                        ),
                      ),
                    ),
                    Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 16),
                      child: _buildStage(
                        selectionState,
                        selectionController,
                        effectState,
                        effectController,
                        aspectRatio: aspectRatio,
                        maxHeight: stageMaxHeight,
                      ),
                    ),
                    if (showControls) ...[
                      const SizedBox(height: 10),
                      Expanded(
                        child: _buildToolDeck(
                          selectionState,
                          selectionController,
                          effectState,
                          effectController,
                          nextEnabled: nextEnabled,
                        ),
                      ),
                    ] else
                      const Spacer(),
                  ],
                );
              },
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildStage(
    PersonSelectionState selectionState,
    PersonSelectionController selectionController,
    EffectEditorState effectState,
    EffectEditorController effectController, {
    required double aspectRatio,
    required double maxHeight,
  }) {
    if (selectionState.isAnalyzing) {
      return SizedBox(
        height: maxHeight,
        width: double.infinity,
        child: const _EditorStatus(
          icon: Icons.person_search_rounded,
          title: '正在识别人…',
          subtitle: '完成后即可直接在画面中选择保护对象',
          loading: true,
        ),
      );
    }

    if (selectionState.status == PersonSelectionStatus.error) {
      return SizedBox(
        height: maxHeight,
        width: double.infinity,
        child: _EditorStatus(
          icon: Icons.error_outline_rounded,
          title: '人物识别失败',
          subtitle: selectionState.errorMessage ?? '请稍后重试',
          actionLabel: '重新识别',
          onAction: () => _reanalyze(selectionController),
        ),
      );
    }

    if (selectionState.persons.isEmpty) {
      return SizedBox(
        height: maxHeight,
        width: double.infinity,
        child: const _EditorStatus(
          icon: Icons.person_off_outlined,
          title: '没有找到可保护的人物',
          subtitle: '请返回裁剪或尝试其他视频',
        ),
      );
    }

    return Align(
      alignment: Alignment.topCenter,
      child: ConstrainedBox(
        constraints: BoxConstraints(maxHeight: maxHeight),
        child: AspectRatio(
          aspectRatio: aspectRatio,
          child: MediaStageFrame(
            key: const ValueKey('protection-editor-media-stage'),
            child: _buildInteractivePreview(
              selectionState,
              selectionController,
              effectState,
              effectController,
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildInteractivePreview(
    PersonSelectionState selectionState,
    PersonSelectionController selectionController,
    EffectEditorState effectState,
    EffectEditorController effectController,
  ) {
    final displayPath =
        effectState.previewPath ?? effectState.previewThumbnailPath;
    final hasImage = displayPath != null && displayPath.isNotEmpty;

    return LayoutBuilder(
      builder: (context, constraints) {
        final stageWidth = constraints.maxWidth;
        final stageHeight = constraints.maxHeight;
        return Stack(
          fit: StackFit.expand,
          children: [
            Container(color: const Color(0xFFF1E7E1)),
            if (hasImage)
              Image.file(
                File(displayPath),
                fit: BoxFit.fill,
                gaplessPlayback: true,
                filterQuality: FilterQuality.low,
                errorBuilder: (context, error, stackTrace) =>
                    _buildPreviewPlaceholder(),
              )
            else
              _buildPreviewPlaceholder(),
            for (final person in selectionState.persons)
              _buildPersonTarget(
                person,
                stageWidth,
                stageHeight,
                selectionState.isPersonSelected(person.id),
                () {
                  HapticFeedback.selectionClick();
                  selectionController.togglePerson(person.id);
                  _syncSelectionToEffect(effectController);
                },
              ),
            if (effectState.previewLoading)
              Positioned(
                top: 12,
                child: IgnorePointer(
                  child: Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 11,
                      vertical: 7,
                    ),
                    decoration: BoxDecoration(
                      color: AppTheme.warmSurface.withValues(alpha: 0.94),
                      borderRadius: BorderRadius.circular(18),
                      border: Border.all(color: AppTheme.warmBorder),
                    ),
                    child: const Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        SizedBox(
                          width: 13,
                          height: 13,
                          child: CircularProgressIndicator(
                            strokeWidth: 1.7,
                            color: AppTheme.coral,
                          ),
                        ),
                        SizedBox(width: 7),
                        Text(
                          '更新预览…',
                          style: TextStyle(
                            color: AppTheme.warmTextSecondary,
                            fontSize: 11.5,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            if (effectState.previewError != null)
              Positioned(
                left: 14,
                right: 14,
                top: 12,
                child: IgnorePointer(
                  child: Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 12,
                      vertical: 8,
                    ),
                    decoration: BoxDecoration(
                      color: const Color(0xFFFFF4F1).withValues(alpha: 0.94),
                      borderRadius: BorderRadius.circular(12),
                      border: Border.all(color: AppTheme.coral.withAlpha(100)),
                    ),
                    child: const Text(
                      '预览暂时无法更新，选择与参数仍会保留。',
                      textAlign: TextAlign.center,
                      style: TextStyle(
                        color: AppTheme.warmTextSecondary,
                        fontSize: 11.5,
                      ),
                    ),
                  ),
                ),
              ),
          ],
        );
      },
    );
  }

  Widget _buildPreviewPlaceholder() {
    return const Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(
            Icons.movie_filter_outlined,
            size: 36,
            color: AppTheme.warmTextMuted,
          ),
          SizedBox(height: 10),
          Text(
            '保护效果预览',
            style: TextStyle(color: AppTheme.warmTextSecondary, fontSize: 12),
          ),
        ],
      ),
    );
  }

  Widget _buildPersonTarget(
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

    const minHitSize = 48.0;
    final hitWidth = math.max(width, minHitSize);
    final hitHeight = math.max(height, minHitSize);
    final hitLeft = (left - (hitWidth - width) / 2)
        .clamp(0.0, math.max(0.0, stageWidth - hitWidth))
        .toDouble();
    final hitTop = (top - (hitHeight - height) / 2)
        .clamp(0.0, math.max(0.0, stageHeight - hitHeight))
        .toDouble();

    return Positioned(
      left: hitLeft,
      top: hitTop,
      width: hitWidth,
      height: hitHeight,
      child: Semantics(
        button: true,
        selected: selected,
        label: selected ? '已保护人物' : '未保护人物',
        child: GestureDetector(
          behavior: HitTestBehavior.opaque,
          onTap: onTap,
          child: Stack(
            children: [
              Positioned(
                left: math.max(0, (hitWidth - width) / 2),
                top: math.max(0, (hitHeight - height) / 2),
                width: width,
                height: height,
                child: AnimatedContainer(
                  duration: const Duration(milliseconds: 140),
                  decoration: BoxDecoration(
                    borderRadius: BorderRadius.circular(10),
                    border: Border.all(
                      color: selected
                          ? AppTheme.coral.withValues(alpha: 0.62)
                          : Colors.transparent,
                      width: 1.5,
                    ),
                  ),
                ),
              ),
              if (selected)
                Positioned(
                  right: math.max(0, (hitWidth - width) / 2 - 5),
                  top: math.max(0, (hitHeight - height) / 2 - 5),
                  child: Container(
                    width: 22,
                    height: 22,
                    decoration: const BoxDecoration(
                      color: AppTheme.coral,
                      shape: BoxShape.circle,
                    ),
                    child: const Icon(
                      Icons.check_rounded,
                      color: Colors.white,
                      size: 15,
                    ),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildToolDeck(
    PersonSelectionState selectionState,
    PersonSelectionController selectionController,
    EffectEditorState effectState,
    EffectEditorController effectController, {
    required bool nextEnabled,
  }) {
    final effects = effectState.effects;
    final faceMode = selectionState.privacyMode == ProjectPrivacyMode.faceOnly;
    final activeMode = effects.faceStickerEnabled
        ? FillMode.sticker
        : effects.fillMode;

    return Container(
      key: const ValueKey('protection-editor-tool-deck'),
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
              padding: const EdgeInsets.fromLTRB(18, 14, 18, 18),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  _buildTargetSection(selectionState, selectionController),
                  const SizedBox(height: 14),
                  _buildSectionLabel('保护方式'),
                  const SizedBox(height: 8),
                  _buildPrivacyModeSwitch(selectionState, effectController),
                  const SizedBox(height: 14),
                  Row(
                    children: [
                      Expanded(child: _buildSectionLabel('特效')),
                      InkWell(
                        onTap: () => _resetCurrentEffect(
                          selectionState,
                          effectController,
                        ),
                        borderRadius: BorderRadius.circular(12),
                        child: const Padding(
                          padding: EdgeInsets.symmetric(
                            horizontal: 6,
                            vertical: 4,
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
                    ],
                  ),
                  const SizedBox(height: 8),
                  _buildModeChips(
                    activeMode,
                    effectController,
                    faceMode: faceMode,
                  ),
                  const SizedBox(height: 12),
                  if (faceMode && effects.faceStickerEnabled) ...[
                    _buildStickerPicker(effects, effectController),
                    const SizedBox(height: 10),
                    _buildStepSlider(
                      label: '贴纸大小',
                      value: effects.stickerScale,
                      min: 1.0,
                      max: 2.0,
                      step: 0.1,
                      displayValue: '${(effects.stickerScale * 100).round()}%',
                      onChanged: effectController.updateStickerScale,
                    ),
                  ] else ...[
                    _buildStepSlider(
                      label: '强度',
                      value: effects.opacity,
                      min: 0.1,
                      max: 1.0,
                      step: 0.05,
                      displayValue: '${(effects.opacity * 100).round()}%',
                      onChanged: effectController.updateOpacity,
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
                      effectController.updateFillColor,
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
                      onChanged: effectController.updateBlurStrength,
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
                    onChanged: effectController.updateBorderWidth,
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
                      effectController.updateBorderColor,
                    ),
                  ],
                  const SizedBox(height: 16),
                  _buildSectionLabel('处理策略', subdued: true),
                  const SizedBox(height: 8),
                  _buildProcessingProfileSwitch(),
                ],
              ),
            ),
          ),
          Container(
            padding: const EdgeInsets.fromLTRB(16, 6, 16, 12),
            decoration: BoxDecoration(
              color: AppTheme.warmSurface,
              border: Border(
                top: BorderSide(
                  color: AppTheme.warmBorder.withValues(alpha: 0.5),
                  width: 0.8,
                ),
              ),
            ),
            child: Center(
              child: ImmersiveFlowAction(
                enabled: nextEnabled,
                onNext: _continueToExport,
                onReturn: _requestReturn,
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildTargetSection(
    PersonSelectionState state,
    PersonSelectionController controller,
  ) {
    final selected = state.privacyTargetIds.length;
    final total = state.persons.length;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Row(
          children: [
            Expanded(
              child: Row(
                children: [
                  _buildSectionLabel('保护对象'),
                  const SizedBox(width: 7),
                  Text(
                    '$selected / $total',
                    style: const TextStyle(
                      color: AppTheme.warmTextMuted,
                      fontSize: 11.5,
                    ),
                  ),
                ],
              ),
            ),
            _TargetAction(
              icon: Icons.done_all_rounded,
              tooltip: '全选',
              onPressed: () {
                controller.selectAll();
                _syncSelectionToEffect(
                  ref.read(effectEditorControllerProvider.notifier),
                );
              },
            ),
            _TargetAction(
              icon: Icons.refresh_rounded,
              tooltip: '重置',
              onPressed: () {
                controller.resetSelection();
                _syncSelectionToEffect(
                  ref.read(effectEditorControllerProvider.notifier),
                );
              },
            ),
            _TargetAction(
              icon: Icons.remove_done_rounded,
              tooltip: '清空',
              onPressed: () {
                controller.deselectAll();
                _syncSelectionToEffect(
                  ref.read(effectEditorControllerProvider.notifier),
                );
              },
            ),
          ],
        ),
        const SizedBox(height: 5),
        const Text(
          '直接轻触上方画面中的人物即可增减保护对象',
          style: TextStyle(color: AppTheme.warmTextSecondary, fontSize: 11.5),
        ),
      ],
    );
  }

  Widget _buildSectionLabel(String text, {bool subdued = false}) {
    return Text(
      text,
      style: TextStyle(
        color: subdued ? AppTheme.warmTextSecondary : AppTheme.warmTextPrimary,
        fontSize: subdued ? 12.5 : 13.5,
        fontWeight: FontWeight.w700,
      ),
    );
  }

  Widget _buildPrivacyModeSwitch(
    PersonSelectionState state,
    EffectEditorController effectController,
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
              Row(
                children: [
                  Expanded(
                    child: _PrivacyModeOption(
                      label: '全身保护',
                      icon: Icons.accessibility_new_rounded,
                      selected: !isFaceOnly,
                      onTap: () => _switchPrivacyMode(
                        ProjectPrivacyMode.fullBody,
                        effectController,
                      ),
                    ),
                  ),
                  Expanded(
                    child: _PrivacyModeOption(
                      label: '人脸保护',
                      icon: Icons.face_retouching_off_rounded,
                      selected: isFaceOnly,
                      onTap: () => _switchPrivacyMode(
                        ProjectPrivacyMode.faceOnly,
                        effectController,
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
      height: 70,
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
                padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 7),
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
                      size: 21,
                      color: selected
                          ? AppTheme.coral
                          : AppTheme.warmTextSecondary,
                    ),
                    const SizedBox(height: 4),
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

  Widget _buildProcessingProfileSwitch() {
    const options = <(String, String, IconData)>[
      ('quality', '质量', Icons.diamond_outlined),
      ('balanced', '均衡', Icons.balance_rounded),
      ('speed', '快速', Icons.bolt_rounded),
    ];

    return Container(
      height: 42,
      padding: const EdgeInsets.all(3),
      decoration: BoxDecoration(
        color: const Color(0xFFF2ECE7),
        borderRadius: BorderRadius.circular(21),
        border: Border.all(color: AppTheme.warmBorder.withValues(alpha: 0.6)),
      ),
      child: LayoutBuilder(
        builder: (context, constraints) {
          final itemWidth = (constraints.maxWidth - 4) / options.length;
          final selectedIndex = options.indexWhere(
            (option) => option.$1 == _processingProfile,
          );
          final safeIndex = selectedIndex < 0 ? 0 : selectedIndex;
          final alignment = switch (safeIndex) {
            0 => Alignment.centerLeft,
            1 => Alignment.center,
            _ => Alignment.centerRight,
          };
          return Stack(
            children: [
              AnimatedAlign(
                duration: const Duration(milliseconds: 180),
                curve: Curves.easeOutCubic,
                alignment: alignment,
                child: Container(
                  width: itemWidth,
                  height: double.infinity,
                  decoration: BoxDecoration(
                    color: AppTheme.warmSurface,
                    borderRadius: BorderRadius.circular(18),
                    boxShadow: const [
                      BoxShadow(
                        color: Color(0x12000000),
                        blurRadius: 7,
                        offset: Offset(0, 2),
                      ),
                    ],
                  ),
                ),
              ),
              Row(
                children: options.map((option) {
                  final selected = _processingProfile == option.$1;
                  return Expanded(
                    child: Semantics(
                      button: true,
                      selected: selected,
                      label: '${option.$2}处理',
                      child: GestureDetector(
                        behavior: HitTestBehavior.opaque,
                        onTap: () {
                          if (selected) return;
                          HapticFeedback.selectionClick();
                          setState(() => _processingProfile = option.$1);
                        },
                        child: Center(
                          child: Row(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              Icon(
                                option.$3,
                                size: 15,
                                color: selected
                                    ? AppTheme.coral
                                    : AppTheme.warmTextMuted,
                              ),
                              const SizedBox(width: 4),
                              Text(
                                option.$2,
                                style: TextStyle(
                                  color: selected
                                      ? AppTheme.warmTextPrimary
                                      : AppTheme.warmTextSecondary,
                                  fontSize: 12.5,
                                  fontWeight: selected
                                      ? FontWeight.w700
                                      : FontWeight.w500,
                                ),
                              ),
                            ],
                          ),
                        ),
                      ),
                    ),
                  );
                }).toList(),
              ),
            ],
          );
        },
      ),
    );
  }

  Future<void> _reanalyze(PersonSelectionController selectionController) async {
    await selectionController.analyzeProject(
      widget.project,
      selectionPreviewEnabled: false,
    );
    if (!mounted) return;
    final configured = selectionController.buildConfiguredProject();
    if (configured == null) return;
    final state = ref.read(personSelectionControllerProvider);
    final active = state.privacyMode == ProjectPrivacyMode.faceOnly
        ? (_faceOnlyDraft ?? _defaultFaceDraft(configured.effects))
        : (_fullBodyDraft ?? _defaultFullBodyDraft(configured.effects));
    ref
        .read(effectEditorControllerProvider.notifier)
        .init(configured.copyWith(effects: active));
  }

  void _syncSelectionToEffect(EffectEditorController effectController) {
    final selectionController = ref.read(
      personSelectionControllerProvider.notifier,
    );
    final configured = selectionController.buildConfiguredProject();
    if (configured == null) return;
    final effectState = ref.read(effectEditorControllerProvider);
    if (effectState.project == null) return;
    effectController.updateEditingContext(
      project: configured,
      effects: effectState.effects,
      debounce: true,
    );
  }

  void _switchPrivacyMode(
    ProjectPrivacyMode mode,
    EffectEditorController effectController,
  ) {
    final selectionState = ref.read(personSelectionControllerProvider);
    if (selectionState.privacyMode == mode) return;

    HapticFeedback.selectionClick();
    _captureActiveDraft();

    final selectionController = ref.read(
      personSelectionControllerProvider.notifier,
    );
    selectionController.setProjectPrivacyMode(mode);
    final configured = selectionController.buildConfiguredProject();
    if (configured == null) return;

    final nextEffects = mode == ProjectPrivacyMode.faceOnly
        ? (_faceOnlyDraft ??= _defaultFaceDraft(configured.effects))
        : (_fullBodyDraft ??= _defaultFullBodyDraft(configured.effects));
    effectController.updateEditingContext(
      project: configured,
      effects: nextEffects,
      debounce: false,
    );
  }

  void _captureActiveDraft() {
    final selectionState = ref.read(personSelectionControllerProvider);
    final effectState = ref.read(effectEditorControllerProvider);
    if (effectState.project == null) return;
    if (selectionState.privacyMode == ProjectPrivacyMode.faceOnly) {
      _faceOnlyDraft = effectState.effects;
    } else {
      _fullBodyDraft = effectState.effects;
    }
  }

  void _resetCurrentEffect(
    PersonSelectionState selectionState,
    EffectEditorController effectController,
  ) {
    HapticFeedback.mediumImpact();
    final configured = ref
        .read(personSelectionControllerProvider.notifier)
        .buildConfiguredProject();
    if (configured == null) return;
    final effects = selectionState.privacyMode == ProjectPrivacyMode.faceOnly
        ? _defaultFaceDraft(configured.effects)
        : _defaultFullBodyDraft(configured.effects);
    if (selectionState.privacyMode == ProjectPrivacyMode.faceOnly) {
      _faceOnlyDraft = effects;
    } else {
      _fullBodyDraft = effects;
    }
    effectController.updateEditingContext(
      project: configured,
      effects: effects,
      debounce: false,
    );
  }

  EffectConfig _normalizeFullBodyDraft(EffectConfig effects) {
    if (!effects.faceStickerEnabled && effects.fillMode != FillMode.sticker) {
      return effects;
    }
    return effects.copyWith(
      fillMode: FillMode.solid,
      faceStickerEnabled: false,
      stickerAssetId: 'disabled',
    );
  }

  EffectConfig _normalizeFaceDraft(EffectConfig effects) {
    if (effects.fillMode == FillMode.sticker || effects.faceStickerEnabled) {
      return effects.copyWith(
        fillMode: FillMode.sticker,
        faceStickerEnabled: true,
        stickerAssetId:
            effects.stickerAssetId == null ||
                effects.stickerAssetId == 'disabled'
            ? 'builtin:sunglasses'
            : effects.stickerAssetId,
      );
    }
    return effects;
  }

  EffectConfig _defaultFullBodyDraft(EffectConfig seed) {
    return seed.copyWith(
      fillMode: FillMode.solid,
      fillColorArgb: 0xFF000000,
      opacity: 1.0,
      borderWidth: 0.0,
      borderColorArgb: 0xFFFFFFFF,
      blurStrength: 15.0,
      faceStickerEnabled: false,
      stickerAssetId: 'disabled',
    );
  }

  EffectConfig _defaultFaceDraft(EffectConfig seed) {
    return seed.copyWith(
      fillMode: FillMode.sticker,
      opacity: 1.0,
      borderWidth: 0.0,
      borderColorArgb: 0xFFFFFFFF,
      blurStrength: 15.0,
      faceStickerEnabled: true,
      stickerAssetId: 'builtin:sunglasses',
      stickerScale: 1.0,
    );
  }

  DanceProject _buildCurrentProject() {
    final selectionController = ref.read(
      personSelectionControllerProvider.notifier,
    );
    final selectionProject = selectionController.buildConfiguredProject();
    final effectState = ref.read(effectEditorControllerProvider);
    final base = selectionProject ?? effectState.project ?? widget.project;
    final effects = effectState.project == null
        ? base.effects
        : effectState.effects;
    return base.copyWith(effects: effects, updatedAt: DateTime.now());
  }

  ProtectionEditorResult _buildResult() {
    _captureActiveDraft();
    final project = _buildCurrentProject();
    final selectionState = ref.read(personSelectionControllerProvider);
    final faceMode = selectionState.privacyMode == ProjectPrivacyMode.faceOnly;
    final activeEffects = project.effects;
    final fullBodyDraft =
        _fullBodyDraft ??
        (faceMode
            ? _defaultFullBodyDraft(activeEffects)
            : _normalizeFullBodyDraft(activeEffects));
    final faceOnlyDraft =
        _faceOnlyDraft ??
        (faceMode
            ? _normalizeFaceDraft(activeEffects)
            : _defaultFaceDraft(activeEffects));
    return ProtectionEditorResult(
      project: project,
      fullBodyDraft: fullBodyDraft,
      faceOnlyDraft: faceOnlyDraft,
      processingProfile: _processingProfile,
    );
  }

  void _requestReturn() {
    if (_returnRequested || !mounted) return;
    _returnRequested = true;
    final result = _buildResult();
    setState(() => _allowRoutePop = true);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      context.pop(result);
    });
  }

  Future<void> _continueToExport() async {
    _captureActiveDraft();
    final project = _buildCurrentProject();
    final effectState = ref.read(effectEditorControllerProvider);
    final initialPreviewPath =
        effectState.previewPath ?? effectState.previewThumbnailPath;
    await context.push(
      '/export',
      extra: ExportArgs(
        project: project,
        processingProfile: _processingProfile,
        initialPreviewPath: initialPreviewPath,
      ),
    );
  }
}

class _TargetAction extends StatelessWidget {
  final IconData icon;
  final String tooltip;
  final VoidCallback onPressed;

  const _TargetAction({
    required this.icon,
    required this.tooltip,
    required this.onPressed,
  });

  @override
  Widget build(BuildContext context) {
    return Tooltip(
      message: tooltip,
      child: IconButton(
        visualDensity: VisualDensity.compact,
        onPressed: () {
          HapticFeedback.selectionClick();
          onPressed();
        },
        icon: Icon(icon, size: 18, color: AppTheme.warmTextSecondary),
      ),
    );
  }
}

class _PrivacyModeOption extends StatelessWidget {
  final String label;
  final IconData icon;
  final bool selected;
  final VoidCallback onTap;

  const _PrivacyModeOption({
    required this.label,
    required this.icon,
    required this.selected,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return Semantics(
      button: true,
      selected: selected,
      label: label,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: onTap,
        child: Center(
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(
                icon,
                size: 18,
                color: selected ? Colors.white : AppTheme.warmTextSecondary,
              ),
              const SizedBox(width: 6),
              Text(
                label,
                style: TextStyle(
                  fontSize: 13.5,
                  fontWeight: selected ? FontWeight.w600 : FontWeight.w500,
                  color: selected ? Colors.white : AppTheme.warmTextSecondary,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _EditorStatus extends StatelessWidget {
  final IconData icon;
  final String title;
  final String subtitle;
  final bool loading;
  final String? actionLabel;
  final VoidCallback? onAction;

  const _EditorStatus({
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
