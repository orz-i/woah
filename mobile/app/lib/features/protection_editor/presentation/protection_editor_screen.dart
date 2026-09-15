import 'dart:async';
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
import '../../../core/widgets/video_trim_control.dart';
import '../../../repositories/native_processing_repository.dart';
import '../../effect_editor/domain/effect_editor_state.dart';
import '../../effect_editor/presentation/effect_editor_controller.dart';
import '../../export/presentation/export_screen.dart';
import '../../person_selection/domain/person_selection_state.dart';
import '../../person_selection/presentation/person_selection_controller.dart';

class ProtectionEditorArgs {
  final DanceProject project;
  final EffectConfig? fullBodyDraft;
  final EffectConfig? faceOnlyDraft;

  const ProtectionEditorArgs({
    required this.project,
    this.fullBodyDraft,
    this.faceOnlyDraft,
  });
}

class ProtectionEditorResult {
  final DanceProject project;
  final EffectConfig fullBodyDraft;
  final EffectConfig faceOnlyDraft;

  const ProtectionEditorResult({
    required this.project,
    required this.fullBodyDraft,
    required this.faceOnlyDraft,
  });
}

class ProtectionEditorScreen extends ConsumerStatefulWidget {
  final DanceProject project;
  final EffectConfig? fullBodyDraft;
  final EffectConfig? faceOnlyDraft;

  const ProtectionEditorScreen({
    super.key,
    required this.project,
    this.fullBodyDraft,
    this.faceOnlyDraft,
  });

  @override
  ConsumerState<ProtectionEditorScreen> createState() =>
      _ProtectionEditorScreenState();
}

class _ProtectionEditorScreenState
    extends ConsumerState<ProtectionEditorScreen> {
  static const int _minimumClipMs = 1000;
  static const int _thumbnailCount = 10;

  final ScrollController _scrollController = ScrollController();

  EffectConfig? _fullBodyDraft;
  EffectConfig? _faceOnlyDraft;
  List<String> _trimThumbnailPaths = const [];
  late int _trimStartMs;
  late int _trimEndMs;
  int _committedTrimStartMs = 0;
  int _committedTrimEndMs = 0;
  bool _trimApplying = false;
  bool _allowRoutePop = false;
  bool _returnRequested = false;
  bool _advancedEffectExpanded = false;
  bool _selectingFollowTarget = false;

  @override
  void initState() {
    super.initState();
    _fullBodyDraft = widget.fullBodyDraft;
    _faceOnlyDraft = widget.faceOnlyDraft;
    final durationMs = math.max(widget.project.videoInfo.durationMs, 1);
    _trimStartMs = widget.project.trimStartMs.clamp(0, durationMs);
    _trimEndMs = widget.project.effectiveTrimEndMs.clamp(
      _trimStartMs,
      durationMs,
    );
    if (_trimEndMs - _trimStartMs < _minimumClipMs &&
        durationMs >= _minimumClipMs) {
      _trimEndMs = (_trimStartMs + _minimumClipMs).clamp(0, durationMs);
      if (_trimEndMs - _trimStartMs < _minimumClipMs) {
        _trimStartMs = (_trimEndMs - _minimumClipMs).clamp(0, durationMs);
      }
    }
    _committedTrimStartMs = _trimStartMs;
    _committedTrimEndMs = _trimEndMs;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      unawaited(_initializeEditor());
      unawaited(_loadTrimThumbnails());
    });
  }

  Future<void> _initializeEditor({DanceProject? project}) async {
    final selectionController = ref.read(
      personSelectionControllerProvider.notifier,
    );
    await selectionController.prepareProject(
      project ?? widget.project,
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

  int get _sourceDurationMs => math.max(widget.project.videoInfo.durationMs, 1);

  Future<void> _loadTrimThumbnails() async {
    try {
      final timestamps = List<int>.generate(_thumbnailCount, (index) {
        if (_thumbnailCount == 1) return 0;
        return ((_sourceDurationMs * index) / (_thumbnailCount - 1)).round();
      });
      final thumbnails = await ref
          .read(nativeRepositoryProvider)
          .getVideoFrameThumbnails(
            videoUri: widget.project.sourceUri,
            timestampsMs: timestamps,
          );
      if (!mounted) return;
      setState(() => _trimThumbnailPaths = thumbnails);
    } catch (_) {
      // Thumbnail extraction is optional; the trim track remains usable with
      // lightweight placeholders when a platform cannot provide thumbnails.
    }
  }

  void _setTrimStart(int valueMs) {
    final maxStart = (_trimEndMs - _minimumClipMs).clamp(0, _sourceDurationMs);
    final value = valueMs.clamp(0, maxStart);
    if (value == _trimStartMs) return;
    setState(() => _trimStartMs = value);
  }

  void _setTrimEnd(int valueMs) {
    final minEnd = (_trimStartMs + _minimumClipMs).clamp(0, _sourceDurationMs);
    final value = valueMs.clamp(minEnd, _sourceDurationMs);
    if (value == _trimEndMs) return;
    setState(() => _trimEndMs = value);
  }

  Future<void> _applyTrimChange() async {
    if (_trimApplying ||
        (_trimStartMs == _committedTrimStartMs &&
            _trimEndMs == _committedTrimEndMs)) {
      return;
    }

    _captureActiveDraft();
    final currentProject = _buildCurrentProject();
    final trimmedProject = currentProject.copyWith(
      trimStartMs: _trimStartMs,
      trimEndMs: _trimEndMs,
      persons: const [],
      selectedPersonIds: const {},
      faceOnlyPersonIds: const {},
      analysisCacheId: '',
      // Person IDs are scoped to the analyzed first frame. A temporal trim can
      // move that frame, so subject-follow must be explicitly reselected.
      follow: const FollowConfig(),
      updatedAt: DateTime.now(),
    );

    setState(() {
      _trimApplying = true;
      _selectingFollowTarget = false;
    });

    final selectionController = ref.read(
      personSelectionControllerProvider.notifier,
    );
    await selectionController.analyzeProject(
      trimmedProject,
      selectionPreviewEnabled: false,
    );
    if (!mounted) return;

    final selectionState = ref.read(personSelectionControllerProvider);
    final configured = selectionController.buildConfiguredProject();
    if (selectionState.status == PersonSelectionStatus.ready &&
        configured != null &&
        selectionState.persons.isNotEmpty) {
      final faceMode =
          selectionState.privacyMode == ProjectPrivacyMode.faceOnly;
      final activeEffects = faceMode
          ? (_faceOnlyDraft ?? _normalizeFaceDraft(configured.effects))
          : (_fullBodyDraft ?? _normalizeFullBodyDraft(configured.effects));
      ref
          .read(effectEditorControllerProvider.notifier)
          .init(configured.copyWith(effects: activeEffects));
      setState(() {
        _committedTrimStartMs = _trimStartMs;
        _committedTrimEndMs = _trimEndMs;
        _trimApplying = false;
      });
      return;
    }

    setState(() => _trimApplying = false);
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
        showControls &&
        !_selectingFollowTarget &&
        (selectionState.privacyTargetIds.isNotEmpty ||
            (effectState.project?.hasFollowTarget ?? false));
    final project =
        effectState.project ?? selectionState.project ?? widget.project;
    final desiredAspect = effectState.showSourcePreview
        ? project.videoInfo.aspectRatio
        : project.outputAspectRatio;
    final aspectRatio = desiredAspect > 0 ? desiredAspect : 9 / 16;

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
                final stageFraction = !showControls
                    ? 0.64
                    : aspectRatio < 0.75
                    ? 0.56
                    : aspectRatio < 1.25
                    ? 0.46
                    : 0.38;
                final stageMaxHeight = availableHeight * stageFraction;
                return Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 16),
                      child: SizedBox(
                        height: AppTheme.minTouchTarget,
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
            if (effectState.project?.follow.enabled != true ||
                effectState.showSourcePreview)
              for (final person in selectionState.persons)
                _buildPersonTarget(
                  person,
                  stageWidth,
                  stageHeight,
                  selectionState.isPersonSelected(person.id),
                  () {
                    HapticFeedback.selectionClick();
                    if (_selectingFollowTarget) {
                      setState(() => _selectingFollowTarget = false);
                      effectController.updateFollowConfig(
                        enabled: true,
                        targetPersonId: person.id,
                        outputAspectRatio: 9 / 16,
                        zoom: 1,
                      );
                    } else {
                      selectionController.togglePerson(person.id);
                      _syncSelectionToEffect(effectController);
                    }
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
        label: _selectingFollowTarget
            ? '选择人物 ${person.id + 1} 为主角'
            : selected
            ? '已保护人物'
            : '未保护人物',
        child: GestureDetector(
          key: ValueKey('protection-person-target-${person.id}'),
          behavior: HitTestBehavior.opaque,
          onTap: onTap,
          // Selection state is already communicated by the rendered mask.
          // Keep this overlay visually transparent so small/distant dancers are
          // not obscured by bounding boxes or selection badges.
          child: const SizedBox.expand(),
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
                  _buildReframeControls(effectState, effectController),
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
                  const SizedBox(height: 16),
                  _buildAdvancedEffectSection(effects, effectController),
                  const SizedBox(height: 16),
                  _buildTrimSection(),
                ],
              ),
            ),
          ),
          Container(
            height: 82,
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
            child: OverflowBox(
              alignment: Alignment.bottomCenter,
              minHeight: 0,
              maxHeight: 176,
              child: Center(
                child: ImmersiveFlowAction(
                  enabled: nextEnabled,
                  onNext: _continueToExport,
                  onReturn: _requestReturn,
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildTrimSection() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _buildSectionLabel('舞段裁切', subdued: true),
        const SizedBox(height: 4),
        const Text(
          '拖动两侧边缘选择保留舞段；调整完成后会按新的起点重新识别人。',
          style: TextStyle(
            color: AppTheme.warmTextSecondary,
            fontSize: 11.5,
            height: 1.35,
          ),
        ),
        const SizedBox(height: 8),
        VideoTrimControl(
          durationMs: _sourceDurationMs,
          trimStartMs: _trimStartMs,
          trimEndMs: _trimEndMs,
          thumbnailPaths: _trimThumbnailPaths,
          onStartChanged: _setTrimStart,
          onEndChanged: _setTrimEnd,
          onTrimChangeEnd: () => unawaited(_applyTrimChange()),
        ),
        if (_trimApplying) ...[
          const SizedBox(height: 8),
          const Row(
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
                '正在按新舞段重新识别人…',
                style: TextStyle(
                  color: AppTheme.warmTextSecondary,
                  fontSize: 11.5,
                ),
              ),
            ],
          ),
        ],
      ],
    );
  }

  Widget _buildReframeControls(
    EffectEditorState state,
    EffectEditorController controller,
  ) {
    final follow = state.project?.follow ?? const FollowConfig();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _buildSectionLabel('画面裁切'),
        const SizedBox(height: 8),
        SegmentedButton<bool>(
          key: const ValueKey('reframe-mode'),
          showSelectedIcon: false,
          segments: const [
            ButtonSegment(
              value: false,
              label: Text('原画'),
              icon: Icon(Icons.crop_original),
            ),
            ButtonSegment(
              value: true,
              label: Text('竖屏 9:16'),
              icon: Icon(Icons.crop_portrait),
            ),
          ],
          selected: {follow.enabled || _selectingFollowTarget},
          onSelectionChanged: (values) {
            if (values.single) {
              setState(() => _selectingFollowTarget = true);
              controller.showSourceFrame(true);
            } else {
              setState(() => _selectingFollowTarget = false);
              controller.updateFollowConfig(enabled: false);
            }
          },
        ),
        if (_selectingFollowTarget) ...[
          const SizedBox(height: 8),
          const Text(
            '轻触画面中的主角，不会改变保护对象。',
            style: TextStyle(fontSize: 12, color: AppTheme.warmTextSecondary),
          ),
          Align(
            alignment: Alignment.centerLeft,
            child: TextButton(
              key: const ValueKey('reframe-cancel-selection'),
              onPressed: () {
                setState(() => _selectingFollowTarget = false);
                controller.showSourceFrame(false);
              },
              child: const Text('取消选主角'),
            ),
          ),
        ] else if (follow.enabled) ...[
          const SizedBox(height: 8),
          Text(
            '主角：人物 ${(follow.targetPersonId ?? 0) + 1} · 自动平滑跟随',
            style: const TextStyle(
              fontSize: 12,
              color: AppTheme.warmTextSecondary,
            ),
          ),
          Wrap(
            spacing: 8,
            children: [
              TextButton.icon(
                key: const ValueKey('reframe-change-subject'),
                onPressed: () {
                  setState(() => _selectingFollowTarget = true);
                  controller.showSourceFrame(true);
                },
                icon: const Icon(Icons.person_search_outlined, size: 18),
                label: const Text('更换主角'),
              ),
              TextButton.icon(
                key: const ValueKey('reframe-source-toggle'),
                onPressed: () =>
                    controller.showSourceFrame(!state.showSourcePreview),
                icon: Icon(
                  state.showSourcePreview
                      ? Icons.crop_portrait
                      : Icons.people_outline,
                  size: 18,
                ),
                label: Text(state.showSourcePreview ? '裁切预览' : '原画选保护对象'),
              ),
            ],
          ),
          const Text(
            '当前为首帧构图；导出时跟随主角。清空保护对象可仅裁切。',
            style: TextStyle(fontSize: 11, color: AppTheme.warmTextSecondary),
          ),
        ],
      ],
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
            _TargetTextAction(
              icon: Icons.done_all_rounded,
              label: '全选',
              enabled: selected < total,
              onPressed: () {
                controller.selectAll();
                _syncSelectionToEffect(
                  ref.read(effectEditorControllerProvider.notifier),
                );
              },
            ),
            const SizedBox(width: 2),
            _TargetTextAction(
              icon: Icons.refresh_rounded,
              label: '恢复',
              onPressed: () {
                controller.resetSelection();
                _syncSelectionToEffect(
                  ref.read(effectEditorControllerProvider.notifier),
                );
              },
            ),
            const SizedBox(width: 2),
            _TargetTextAction(
              icon: Icons.remove_done_rounded,
              label: '清空',
              enabled: selected > 0,
              onPressed: () {
                controller.deselectAll();
                _syncSelectionToEffect(
                  ref.read(effectEditorControllerProvider.notifier),
                );
              },
            ),
          ],
        ),
        const SizedBox(height: 4),
        Text(
          selected == 0 ? '选择保护对象，或开启竖屏跟随以仅裁切视频' : '轻触原画中的人物可调整保护对象',
          style: TextStyle(
            color: selected == 0
                ? AppTheme.coralStrong
                : AppTheme.warmTextSecondary,
            fontSize: 11.5,
            fontWeight: selected == 0 ? FontWeight.w600 : FontWeight.w400,
          ),
        ),
      ],
    );
  }

  Widget _buildAdvancedEffectSection(
    EffectConfig effects,
    EffectEditorController effectController,
  ) {
    return Container(
      decoration: BoxDecoration(
        color: AppTheme.warmSurfaceSoft.withValues(alpha: 0.72),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: AppTheme.warmBorder.withValues(alpha: 0.72)),
      ),
      child: Column(
        children: [
          Semantics(
            button: true,
            expanded: _advancedEffectExpanded,
            label: _advancedEffectExpanded ? '收起高级效果' : '展开高级效果',
            child: InkWell(
              borderRadius: BorderRadius.circular(16),
              onTap: () {
                HapticFeedback.selectionClick();
                setState(
                  () => _advancedEffectExpanded = !_advancedEffectExpanded,
                );
              },
              child: ConstrainedBox(
                constraints: const BoxConstraints(
                  minHeight: AppTheme.minTouchTarget,
                ),
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 12),
                  child: Row(
                    children: [
                      const Icon(
                        Icons.tune_rounded,
                        size: 17,
                        color: AppTheme.warmTextSecondary,
                      ),
                      const SizedBox(width: 8),
                      const Expanded(
                        child: Text(
                          '高级效果',
                          style: TextStyle(
                            color: AppTheme.warmTextSecondary,
                            fontSize: 12.5,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                      ),
                      if (effects.borderWidth > 0)
                        Padding(
                          padding: const EdgeInsets.only(right: 8),
                          child: Text(
                            '描边 ${effects.borderWidth.round()} px',
                            style: const TextStyle(
                              color: AppTheme.coral,
                              fontSize: 11.5,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                        ),
                      AnimatedRotation(
                        turns: _advancedEffectExpanded ? 0.5 : 0,
                        duration: const Duration(milliseconds: 160),
                        child: const Icon(
                          Icons.keyboard_arrow_down_rounded,
                          size: 20,
                          color: AppTheme.warmTextMuted,
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
          AnimatedCrossFade(
            duration: const Duration(milliseconds: 180),
            crossFadeState: _advancedEffectExpanded
                ? CrossFadeState.showSecond
                : CrossFadeState.showFirst,
            firstChild: const SizedBox(width: double.infinity),
            secondChild: Padding(
              padding: const EdgeInsets.fromLTRB(12, 0, 12, 12),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  const Divider(height: 1, color: AppTheme.warmBorder),
                  const SizedBox(height: 12),
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
                ],
              ),
            ),
          ),
        ],
      ),
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
      height: AppTheme.minTouchTarget,
      padding: const EdgeInsets.all(3),
      decoration: BoxDecoration(
        color: const Color(0xFFECE3DE),
        borderRadius: BorderRadius.circular(24),
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
                    borderRadius: BorderRadius.circular(21),
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
      spacing: 6,
      runSpacing: 6,
      children: colors.map((argb) {
        final selected = currentArgb == argb;
        return Semantics(
          button: true,
          selected: selected,
          label: '颜色选项',
          child: GestureDetector(
            behavior: HitTestBehavior.opaque,
            onTap: () {
              HapticFeedback.selectionClick();
              onSelect(argb);
            },
            child: SizedBox(
              width: AppTheme.minTouchTarget,
              height: AppTheme.minTouchTarget,
              child: Center(
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
            ),
          ),
        );
      }).toList(),
    );
  }

  Future<void> _reanalyze(PersonSelectionController selectionController) async {
    final project = ref.read(personSelectionControllerProvider).project;
    await selectionController.analyzeProject(
      project ?? widget.project,
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
    return base.copyWith(
      effects: effects,
      follow: effectState.project?.follow ?? base.follow,
      updatedAt: DateTime.now(),
    );
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
    await _applyTrimChange();
    if (!mounted ||
        ref.read(personSelectionControllerProvider).status !=
            PersonSelectionStatus.ready) {
      return;
    }
    _captureActiveDraft();
    final project = _buildCurrentProject();
    final effectState = ref.read(effectEditorControllerProvider);
    final initialPreviewPath =
        effectState.showSourcePreview && project.follow.enabled
        ? null
        : effectState.previewPath ?? effectState.previewThumbnailPath;
    await context.push(
      '/export',
      extra: ExportArgs(
        project: project,
        initialPreviewPath: initialPreviewPath,
      ),
    );
  }
}

class _TargetTextAction extends StatelessWidget {
  final IconData icon;
  final String label;
  final VoidCallback onPressed;
  final bool enabled;

  const _TargetTextAction({
    required this.icon,
    required this.label,
    required this.onPressed,
    this.enabled = true,
  });

  @override
  Widget build(BuildContext context) {
    return Semantics(
      button: true,
      enabled: enabled,
      label: label,
      child: InkWell(
        borderRadius: BorderRadius.circular(12),
        onTap: enabled
            ? () {
                HapticFeedback.selectionClick();
                onPressed();
              }
            : null,
        child: AnimatedOpacity(
          duration: const Duration(milliseconds: 120),
          opacity: enabled ? 1 : 0.38,
          child: ConstrainedBox(
            constraints: const BoxConstraints(
              minHeight: AppTheme.minTouchTarget,
            ),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 6),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(icon, size: 15, color: AppTheme.warmTextSecondary),
                  const SizedBox(width: 3),
                  Text(
                    label,
                    style: const TextStyle(
                      color: AppTheme.warmTextSecondary,
                      fontSize: 11.5,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
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
