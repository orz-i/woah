import 'dart:async';
import 'dart:io';
import 'dart:math' as math;

import 'package:dance_domain/dance_domain.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../app/theme.dart';
import '../../../core/widgets/bottom_control_drawer.dart';
import '../../../core/widgets/flow_back_button.dart';
import '../../../core/widgets/immersive_flow_action.dart';
import '../../../core/widgets/video_trim_control.dart';
import '../../../repositories/native_processing_repository.dart';
import '../../effect_editor/domain/effect_editor_state.dart';
import '../../effect_editor/presentation/effect_editor_controller.dart';
import '../../export/presentation/export_screen.dart';
import '../../person_selection/domain/person_selection_state.dart';
import '../../person_selection/presentation/person_selection_controller.dart';
import '../data/protection_profile_store.dart';
import '../domain/protection_profile.dart';

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
  bool _profileExpanded = false;
  bool _trimExpanded = false;
  bool _selectingFollowTarget = false;
  bool _profileReady = false;
  Timer? _profileSaveDebounce;

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
    final baseProject = project ?? widget.project;
    final shouldApplySavedProfile = _isFreshProject(baseProject);
    final savedProfile = shouldApplySavedProfile
        ? await ref.read(protectionProfileStoreProvider).load()
        : null;
    if (!mounted) return;

    final initialProject = savedProfile == null
        ? baseProject
        : baseProject.copyWith(
            effects: savedProfile.fullBodyEffects,
            outputResolutionPreset: savedProfile.outputResolutionPreset,
          );

    final selectionController = ref.read(
      personSelectionControllerProvider.notifier,
    );
    await selectionController.prepareProject(
      initialProject,
      selectionPreviewEnabled: false,
    );
    if (!mounted) return;

    if (savedProfile != null) {
      selectionController.setProjectPrivacyMode(savedProfile.privacyMode);
    }

    final selectionState = ref.read(personSelectionControllerProvider);
    final configured = selectionController.buildConfiguredProject();
    if (configured == null || selectionState.persons.isEmpty) return;

    final isFaceMode =
        selectionState.privacyMode == ProjectPrivacyMode.faceOnly;
    if (savedProfile != null) {
      _fullBodyDraft = _normalizeFullBodyDraft(savedProfile.fullBodyEffects);
      _faceOnlyDraft = _normalizeFaceDraft(savedProfile.faceOnlyEffects);
    } else if (isFaceMode) {
      _faceOnlyDraft ??= _normalizeFaceDraft(configured.effects);
      _fullBodyDraft ??= _defaultFullBodyDraft(configured.effects);
    } else {
      _fullBodyDraft ??= _normalizeFullBodyDraft(configured.effects);
      _faceOnlyDraft ??= _defaultFaceDraft(configured.effects);
    }

    final activeEffects = isFaceMode ? _faceOnlyDraft! : _fullBodyDraft!;
    final effectController = ref.read(effectEditorControllerProvider.notifier);
    effectController.init(configured.copyWith(effects: activeEffects));
    if (savedProfile?.portraitReframe ?? false) {
      setState(() => _selectingFollowTarget = true);
      effectController.showSourceFrame(true);
    }
    _profileReady = true;
  }

  bool _isFreshProject(DanceProject project) {
    return project.analysisCacheId == null &&
        project.persons.isEmpty &&
        project.selectedPersonIds.isEmpty &&
        project.faceOnlyPersonIds.isEmpty &&
        !project.follow.enabled;
  }

  void _scheduleProfilePersist() {
    if (!_profileReady) return;
    _profileSaveDebounce?.cancel();
    _profileSaveDebounce = Timer(
      const Duration(milliseconds: 450),
      () => unawaited(_persistProfileNow()),
    );
  }

  Future<void> _persistProfileNow() async {
    if (!_profileReady || !mounted) return;
    _profileSaveDebounce?.cancel();
    _profileSaveDebounce = null;

    final selectionState = ref.read(personSelectionControllerProvider);
    final effectState = ref.read(effectEditorControllerProvider);
    final project = effectState.project;
    if (project == null) return;

    if (selectionState.privacyMode == ProjectPrivacyMode.faceOnly) {
      _faceOnlyDraft = _normalizeFaceDraft(effectState.effects);
    } else {
      _fullBodyDraft = _normalizeFullBodyDraft(effectState.effects);
    }

    final profile = ProtectionProfile(
      privacyMode: selectionState.privacyMode,
      fullBodyEffects:
          _fullBodyDraft ?? _defaultFullBodyDraft(effectState.effects),
      faceOnlyEffects: _faceOnlyDraft ?? _defaultFaceDraft(effectState.effects),
      outputResolutionPreset: project.outputResolutionPreset,
      portraitReframe: project.follow.enabled || _selectingFollowTarget,
    );
    await ref.read(protectionProfileStoreProvider).save(profile);
  }

  bool _sameEffects(EffectConfig a, EffectConfig b) {
    return a.fillMode == b.fillMode &&
        a.fillColorArgb == b.fillColorArgb &&
        a.borderColorArgb == b.borderColorArgb &&
        a.opacity == b.opacity &&
        a.borderWidth == b.borderWidth &&
        a.blurStrength == b.blurStrength &&
        a.faceStickerEnabled == b.faceStickerEnabled &&
        a.stickerAssetId == b.stickerAssetId &&
        a.stickerScale == b.stickerScale &&
        a.skinWhiten == b.skinWhiten &&
        a.legStretchEnabled == b.legStretchEnabled &&
        a.legStretch == b.legStretch &&
        a.legZoneTop == b.legZoneTop &&
        a.legZoneBottom == b.legZoneBottom;
  }

  @override
  void dispose() {
    _profileSaveDebounce?.cancel();
    super.dispose();
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
  Widget build(BuildContext context) {
    ref.listen<PersonSelectionState>(personSelectionControllerProvider, (
      previous,
      next,
    ) {
      if (previous != null && previous.privacyMode != next.privacyMode) {
        _scheduleProfilePersist();
      }
    });
    ref.listen<EffectEditorState>(effectEditorControllerProvider, (
      previous,
      next,
    ) {
      if (previous == null || !_profileReady) return;
      final effectsChanged = !_sameEffects(previous.effects, next.effects);
      final resolutionChanged =
          previous.project?.outputResolutionPreset !=
          next.project?.outputResolutionPreset;
      final reframeChanged =
          previous.project?.follow.enabled != next.project?.follow.enabled;
      if (effectsChanged || resolutionChanged || reframeChanged) {
        _scheduleProfilePersist();
      }
    });

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
          statusBarIconBrightness: Brightness.light,
          statusBarBrightness: Brightness.dark,
          systemNavigationBarColor: Color(0xFF050506),
          systemNavigationBarIconBrightness: Brightness.light,
          systemNavigationBarDividerColor: Colors.transparent,
          systemStatusBarContrastEnforced: false,
          systemNavigationBarContrastEnforced: false,
        ),
        child: Scaffold(
          backgroundColor: const Color(0xFF050506),
          resizeToAvoidBottomInset: false,
          body: LayoutBuilder(
            builder: (context, constraints) {
              final drawerInitialSize = aspectRatio >= 1 ? 0.40 : 0.38;

              return Stack(
                fit: StackFit.expand,
                children: [
                  const ColoredBox(
                    key: ValueKey('protection-editor-fullscreen-canvas'),
                    color: Color(0xFF050506),
                  ),
                  Center(
                    child: _buildStage(
                      selectionState,
                      selectionController,
                      effectState,
                      effectController,
                      aspectRatio: aspectRatio,
                      viewportSize: constraints.biggest,
                    ),
                  ),
                  const IgnorePointer(
                    child: Align(
                      alignment: Alignment.topCenter,
                      child: SizedBox(
                        height: 132,
                        child: DecoratedBox(
                          decoration: BoxDecoration(
                            gradient: LinearGradient(
                              begin: Alignment.topCenter,
                              end: Alignment.bottomCenter,
                              colors: [Color(0xA6000000), Color(0x00000000)],
                            ),
                          ),
                        ),
                      ),
                    ),
                  ),
                  SafeArea(
                    child: Align(
                      alignment: Alignment.topLeft,
                      child: Padding(
                        padding: const EdgeInsets.fromLTRB(12, 8, 0, 0),
                        child: FlowBackButton(
                          onPressed: _requestReturn,
                          foregroundColor: Colors.white,
                          backgroundColor: Color(0x66000000),
                        ),
                      ),
                    ),
                  ),
                  if (showControls)
                    BottomControlDrawer(
                      key: const ValueKey('protection-editor-control-drawer'),
                      minChildSize: 0.11,
                      initialChildSize: drawerInitialSize,
                      maxChildSize: 0.84,
                      snapSizes: [0.11, drawerInitialSize, 0.84],
                      allowHandleOnlyCollapse: false,
                      panelRadius: 28,
                      panelColor: AppTheme.surface.withValues(alpha: 0.97),
                      panelBorderColor: AppTheme.surfaceBorder,
                      handleColor: AppTheme.textMuted,
                      bottomActionBorderColor: AppTheme.surfaceBorder,
                      panelShadow: const [
                        BoxShadow(
                          color: Color(0x44000000),
                          blurRadius: 32,
                          offset: Offset(0, -10),
                        ),
                      ],
                      peekHeader: _buildDrawerSummary(
                        selectionState,
                        effectState,
                      ),
                      bottomActionBar: _buildExportAction(nextEnabled),
                      child: _buildToolDeck(
                        selectionState,
                        selectionController,
                        effectState,
                        effectController,
                      ),
                    ),
                ],
              );
            },
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
    required Size viewportSize,
  }) {
    if (selectionState.isAnalyzing) {
      return SizedBox(
        width: viewportSize.width,
        height: viewportSize.height,
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
        width: viewportSize.width,
        height: viewportSize.height,
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
        width: viewportSize.width,
        height: viewportSize.height,
        child: const _EditorStatus(
          icon: Icons.person_off_outlined,
          title: '没有找到可保护的人物',
          subtitle: '请返回裁剪或尝试其他视频',
        ),
      );
    }

    final viewportAspect = viewportSize.width / viewportSize.height;
    final stageWidth = viewportAspect > aspectRatio
        ? viewportSize.height * aspectRatio
        : viewportSize.width;
    final stageHeight = stageWidth / aspectRatio;

    return SizedBox(
      key: const ValueKey('protection-editor-media-stage'),
      width: stageWidth,
      height: stageHeight,
      child: ColoredBox(
        color: Colors.black,
        child: _buildInteractivePreview(
          selectionState,
          selectionController,
          effectState,
          effectController,
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
            Container(color: const Color(0xFF0A0A0C)),
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
                      // The camera subject should stay visible. If the chosen
                      // person is currently protected, remove only that person
                      // from the active FULL_BODY/FACE_ONLY target set before
                      // enabling follow. Unprotected subjects are left alone.
                      if (selectionState.isPersonSelected(person.id)) {
                        selectionController.deselectPerson(person.id);
                        _syncSelectionToEffect(effectController);
                      }
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
            if (_selectingFollowTarget)
              Positioned(
                key: const ValueKey('reframe-subject-prompt'),
                left: 14,
                right: 14,
                top: 12,
                child: IgnorePointer(
                  child: Center(
                    child: Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 14,
                        vertical: 9,
                      ),
                      decoration: BoxDecoration(
                        color: AppTheme.surfaceElevated.withValues(alpha: 0.96),
                        borderRadius: BorderRadius.circular(18),
                        border: Border.all(
                          color: AppTheme.coral.withAlpha(110),
                        ),
                        boxShadow: const [
                          BoxShadow(
                            color: Color(0x18000000),
                            blurRadius: 12,
                            offset: Offset(0, 4),
                          ),
                        ],
                      ),
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          const Icon(
                            Icons.person_search_outlined,
                            size: 18,
                            color: AppTheme.coral,
                          ),
                          const SizedBox(width: 6),
                          Flexible(
                            child: Text(
                              stageWidth < 220 ? '轻触选主角' : '轻触人物选择主角',
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: const TextStyle(
                                color: AppTheme.textPrimary,
                                fontSize: 12,
                                fontWeight: FontWeight.w600,
                              ),
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
            if (effectState.previewLoading)
              Positioned(
                bottom: 12,
                child: IgnorePointer(
                  child: Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 11,
                      vertical: 7,
                    ),
                    decoration: BoxDecoration(
                      color: AppTheme.surfaceElevated.withValues(alpha: 0.94),
                      borderRadius: BorderRadius.circular(18),
                      border: Border.all(color: AppTheme.surfaceBorder),
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
                            color: AppTheme.textSecondary,
                            fontSize: 12,
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
                bottom: 12,
                child: IgnorePointer(
                  child: Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 12,
                      vertical: 8,
                    ),
                    decoration: BoxDecoration(
                      color: const Color(0xFF211719).withValues(alpha: 0.96),
                      borderRadius: BorderRadius.circular(12),
                      border: Border.all(color: AppTheme.coral.withAlpha(100)),
                    ),
                    child: const Text(
                      '预览暂时无法更新，选择与参数仍会保留。',
                      textAlign: TextAlign.center,
                      style: TextStyle(
                        color: AppTheme.textSecondary,
                        fontSize: 12,
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
            color: AppTheme.textMuted,
          ),
          SizedBox(height: 10),
          Text(
            '保护效果预览',
            style: TextStyle(color: AppTheme.textSecondary, fontSize: 12),
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
            ? '人物 ${person.id + 1}，已保护'
            : '人物 ${person.id + 1}，未保护',
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

  Widget _buildDrawerSummary(
    PersonSelectionState selectionState,
    EffectEditorState effectState,
  ) {
    final selected = selectionState.privacyTargetIds.length;
    final total = selectionState.persons.length;
    final scope = selectionState.privacyMode == ProjectPrivacyMode.faceOnly
        ? '人脸'
        : '全身';
    final style = _fillModeLabel(
      effectState.effects.faceStickerEnabled
          ? FillMode.sticker
          : effectState.effects.fillMode,
    );

    return Container(
      key: const ValueKey('protection-editor-drawer-summary'),
      padding: const EdgeInsets.fromLTRB(18, 2, 18, 10),
      child: Row(
        children: [
          Expanded(
            child: Text(
              '保护 $selected/$total 人 · $scope · $style',
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(
                color: AppTheme.textPrimary,
                fontSize: 13,
                fontWeight: FontWeight.w600,
              ),
            ),
          ),
          const SizedBox(width: 10),
          const Text(
            '上拉调整',
            style: TextStyle(
              color: AppTheme.textMuted,
              fontSize: 12,
              fontWeight: FontWeight.w500,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildExportAction(bool nextEnabled) {
    return SizedBox(
      key: const ValueKey('protection-editor-export-action-slot'),
      height: 96,
      child: OverflowBox(
        alignment: Alignment.bottomCenter,
        minHeight: 0,
        maxHeight: 176,
        child: ImmersiveFlowAction(
          enabled: nextEnabled,
          onNext: _continueToExport,
          onReturn: _requestReturn,
          actionCaption: '导出',
          actionCaptionColor: AppTheme.textSecondary,
          returnTargetBackgroundColor: AppTheme.surfaceElevated,
          returnTargetBorderColor: AppTheme.surfaceBorder,
          returnTargetForegroundColor: AppTheme.textPrimary,
          nextSemanticsLabel: '导出视频，长按并上拉可返回',
        ),
      ),
    );
  }

  Widget _buildToolDeck(
    PersonSelectionState selectionState,
    PersonSelectionController selectionController,
    EffectEditorState effectState,
    EffectEditorController effectController,
  ) {
    final effects = effectState.effects;
    final faceMode = selectionState.privacyMode == ProjectPrivacyMode.faceOnly;
    final activeMode = effects.faceStickerEnabled
        ? FillMode.sticker
        : effects.fillMode;

    return Container(
      key: const ValueKey('protection-editor-tool-deck'),
      padding: const EdgeInsets.only(top: 4, bottom: 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _buildTargetSection(selectionState, selectionController),
          const SizedBox(height: 14),
          _buildProfileSection(
            selectionState,
            effectState,
            effectController,
            activeMode: activeMode,
            faceMode: faceMode,
          ),
          const SizedBox(height: 14),
          _buildTrimSection(),
          const SizedBox(height: 18),
        ],
      ),
    );
  }

  Widget _buildProfileSection(
    PersonSelectionState selectionState,
    EffectEditorState effectState,
    EffectEditorController effectController, {
    required FillMode activeMode,
    required bool faceMode,
  }) {
    final effects = effectState.effects;
    final project = effectState.project;
    final scopeLabel = faceMode ? '人脸' : '全身';
    final styleLabel = _fillModeLabel(activeMode);
    final frameLabel = project?.follow.enabled == true || _selectingFollowTarget
        ? '9:16'
        : '原画';
    final resolutionLabel = switch (project?.outputResolutionPreset ??
        OutputResolutionPreset.source) {
      OutputResolutionPreset.source => '原画',
      OutputResolutionPreset.fhd => 'FHD',
      OutputResolutionPreset.hd => 'HD',
    };
    final summary =
        '$scopeLabel · $styleLabel · $frameLabel · $resolutionLabel';

    return Container(
      key: const ValueKey('protection-profile-section'),
      decoration: BoxDecoration(
        color: AppTheme.surfaceElevated.withValues(alpha: 0.92),
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: AppTheme.surfaceBorder),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Semantics(
            button: true,
            expanded: _profileExpanded,
            label: _profileExpanded
                ? '收起默认 Profile 配置'
                : '调整默认 Profile，$summary',
            child: InkWell(
              key: const ValueKey('protection-profile-toggle'),
              borderRadius: BorderRadius.circular(18),
              onTap: () {
                HapticFeedback.selectionClick();
                setState(() => _profileExpanded = !_profileExpanded);
              },
              child: Padding(
                padding: const EdgeInsets.fromLTRB(14, 11, 12, 11),
                child: Row(
                  children: [
                    Container(
                      width: 36,
                      height: 36,
                      decoration: BoxDecoration(
                        color: AppTheme.surfaceHigh,
                        borderRadius: BorderRadius.circular(11),
                        border: Border.all(color: AppTheme.surfaceBorder),
                      ),
                      child: const Icon(
                        Icons.tune_rounded,
                        size: 19,
                        color: AppTheme.metalHigh,
                      ),
                    ),
                    const SizedBox(width: 11),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          const Text(
                            '默认 Profile',
                            style: TextStyle(
                              color: AppTheme.textPrimary,
                              fontSize: 13.5,
                              fontWeight: FontWeight.w700,
                            ),
                          ),
                          const SizedBox(height: 2),
                          Text(
                            summary,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(
                              color: AppTheme.textSecondary,
                              fontSize: 12,
                              fontWeight: FontWeight.w500,
                            ),
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(width: 8),
                    Text(
                      _profileExpanded ? '收起' : '调整',
                      style: const TextStyle(
                        color: AppTheme.metalHigh,
                        fontSize: 12,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                    const SizedBox(width: 4),
                    AnimatedRotation(
                      turns: _profileExpanded ? 0.5 : 0,
                      duration: const Duration(milliseconds: 160),
                      child: const Icon(
                        Icons.keyboard_arrow_down_rounded,
                        size: 20,
                        color: AppTheme.textMuted,
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
          AnimatedCrossFade(
            duration: const Duration(milliseconds: 180),
            crossFadeState: _profileExpanded
                ? CrossFadeState.showSecond
                : CrossFadeState.showFirst,
            firstChild: const SizedBox(width: double.infinity),
            secondChild: Padding(
              key: const ValueKey('protection-profile-config-body'),
              padding: const EdgeInsets.fromLTRB(14, 0, 14, 14),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  const Divider(height: 1, color: AppTheme.surfaceBorder),
                  const SizedBox(height: 14),
                  _buildSectionLabel('保护范围'),
                  const SizedBox(height: 8),
                  _buildPrivacyModeSwitch(selectionState, effectController),
                  const SizedBox(height: 16),
                  Row(
                    children: [
                      Expanded(child: _buildSectionLabel('遮挡样式')),
                      InkWell(
                        onTap: () => _resetCurrentEffect(
                          selectionState,
                          effectController,
                        ),
                        borderRadius: BorderRadius.circular(12),
                        child: const Padding(
                          padding: EdgeInsets.symmetric(
                            horizontal: 6,
                            vertical: 6,
                          ),
                          child: Row(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              Icon(
                                Icons.restart_alt_rounded,
                                size: 16,
                                color: AppTheme.textSecondary,
                              ),
                              SizedBox(width: 4),
                              Text(
                                '恢复默认',
                                style: TextStyle(
                                  color: AppTheme.textSecondary,
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
                        color: AppTheme.textPrimary,
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
                  const SizedBox(height: 18),
                  _buildReframeControls(effectState, effectController),
                  const SizedBox(height: 16),
                  _buildAdvancedEffectSection(effects, effectController),
                  const SizedBox(height: 12),
                  const Text(
                    'Profile 会自动应用到下个视频；9:16 只记住画幅偏好，主角仍按当前视频选择。',
                    style: TextStyle(
                      color: AppTheme.textMuted,
                      fontSize: 11.5,
                      height: 1.4,
                    ),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildTrimSection() {
    final duration = math.max(_trimEndMs - _trimStartMs, 0);
    final summary =
        '${_formatRangeTimestamp(_trimStartMs)}–${_formatRangeTimestamp(_trimEndMs)} · ${_formatRangeDuration(duration)}';

    return Container(
      key: const ValueKey('trim-range-section'),
      decoration: BoxDecoration(
        color: AppTheme.surfaceElevated.withValues(alpha: 0.72),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(
          color: AppTheme.surfaceBorder.withValues(alpha: 0.72),
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Semantics(
            button: true,
            expanded: _trimExpanded,
            label: _trimExpanded ? '收起舞段范围' : '展开舞段范围，当前 $summary',
            child: InkWell(
              key: const ValueKey('trim-range-toggle'),
              borderRadius: BorderRadius.circular(16),
              onTap: () {
                HapticFeedback.selectionClick();
                setState(() => _trimExpanded = !_trimExpanded);
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
                        Icons.content_cut_rounded,
                        size: 18,
                        color: AppTheme.textSecondary,
                      ),
                      const SizedBox(width: 8),
                      const Text(
                        '舞段范围',
                        style: TextStyle(
                          color: AppTheme.textPrimary,
                          fontSize: 13.5,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                      const SizedBox(width: 10),
                      Expanded(
                        child: Text(
                          summary,
                          textAlign: TextAlign.end,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(
                            color: AppTheme.textSecondary,
                            fontSize: 12,
                            fontWeight: FontWeight.w500,
                            fontFeatures: [FontFeature.tabularFigures()],
                          ),
                        ),
                      ),
                      const SizedBox(width: 6),
                      AnimatedRotation(
                        turns: _trimExpanded ? 0.5 : 0,
                        duration: const Duration(milliseconds: 160),
                        child: const Icon(
                          Icons.keyboard_arrow_down_rounded,
                          color: AppTheme.textMuted,
                          size: 20,
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
            crossFadeState: _trimExpanded
                ? CrossFadeState.showSecond
                : CrossFadeState.showFirst,
            firstChild: const SizedBox(width: double.infinity),
            secondChild: Padding(
              padding: const EdgeInsets.fromLTRB(10, 0, 10, 12),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  const Divider(height: 1, color: AppTheme.surfaceBorder),
                  const SizedBox(height: 10),
                  const Text(
                    '拖动两侧边缘选择保留舞段。修改起点后会重新识别人，并需要重新选择竖屏主角。',
                    style: TextStyle(
                      color: AppTheme.textSecondary,
                      fontSize: 12,
                      height: 1.4,
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
                        Expanded(
                          child: Text(
                            '正在按新舞段重新识别人…',
                            style: TextStyle(
                              color: AppTheme.textSecondary,
                              fontSize: 12,
                            ),
                          ),
                        ),
                      ],
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

  String _formatRangeTimestamp(int ms) {
    final totalSeconds = ms / 1000.0;
    final minutes = totalSeconds ~/ 60;
    final seconds = totalSeconds - minutes * 60;
    return '${minutes.toString().padLeft(2, '0')}:${seconds.toStringAsFixed(1).padLeft(4, '0')}';
  }

  String _formatRangeDuration(int ms) {
    final seconds = ms / 1000.0;
    if (seconds < 60) return '${seconds.toStringAsFixed(1)} 秒';
    final minutes = seconds ~/ 60;
    final remainder = seconds - minutes * 60;
    return '$minutes 分 ${remainder.toStringAsFixed(0)} 秒';
  }

  String _fillModeLabel(FillMode mode) {
    return switch (mode) {
      FillMode.sticker => '贴纸',
      FillMode.mosaic => '马赛克',
      FillMode.blur => '模糊',
      FillMode.solid => '色块',
      FillMode.gradient => '渐变',
    };
  }

  Widget _buildReframeControls(
    EffectEditorState state,
    EffectEditorController controller,
  ) {
    final project = state.project;
    final follow = project?.follow ?? const FollowConfig();
    final resolutionPreset =
        project?.outputResolutionPreset ?? OutputResolutionPreset.source;
    final resolutionPlan = project == null || _selectingFollowTarget
        ? null
        : ExportPlan.forProject(project);
    final resolutionSummary = _selectingFollowTarget
        ? '选择主角后计算尺寸'
        : resolutionPlan == null
        ? ''
        : '${resolutionPlan.width} × ${resolutionPlan.height}';
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _buildSectionLabel('输出画幅'),
        const SizedBox(height: 8),
        SegmentedButton<bool>(
          key: const ValueKey('reframe-mode'),
          showSelectedIcon: false,
          expandedInsets: EdgeInsets.zero,
          style: _darkSegmentedButtonStyle(),
          segments: const [
            ButtonSegment(
              value: false,
              label: Text('原画'),
              icon: Icon(Icons.crop_original, size: 18),
            ),
            ButtonSegment(
              value: true,
              label: Text('竖屏 9:16'),
              icon: Icon(Icons.crop_portrait, size: 18),
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
            _scheduleProfilePersist();
          },
        ),
        const SizedBox(height: 14),
        Row(
          children: [
            Expanded(child: _buildSectionLabel('分辨率')),
            if (resolutionSummary.isNotEmpty)
              Text(
                resolutionSummary,
                style: const TextStyle(
                  color: AppTheme.textSecondary,
                  fontSize: 12,
                  fontWeight: FontWeight.w500,
                  fontFeatures: [FontFeature.tabularFigures()],
                ),
              ),
          ],
        ),
        const SizedBox(height: 8),
        SegmentedButton<OutputResolutionPreset>(
          key: const ValueKey('output-resolution-preset'),
          showSelectedIcon: false,
          expandedInsets: EdgeInsets.zero,
          style: _darkSegmentedButtonStyle(),
          segments: const [
            ButtonSegment(
              value: OutputResolutionPreset.source,
              label: Text('原画'),
            ),
            ButtonSegment(
              value: OutputResolutionPreset.fhd,
              label: Text('FHD'),
            ),
            ButtonSegment(value: OutputResolutionPreset.hd, label: Text('HD')),
          ],
          selected: {resolutionPreset},
          onSelectionChanged: (values) {
            HapticFeedback.selectionClick();
            controller.updateOutputResolutionPreset(values.single);
          },
        ),
        const SizedBox(height: 4),
        const Text(
          'FHD / HD 只限制最大输出尺寸，不会放大低分辨率素材。',
          style: TextStyle(
            color: AppTheme.textSecondary,
            fontSize: 12,
            height: 1.35,
          ),
        ),
        if (_selectingFollowTarget) ...[
          const SizedBox(height: 8),
          const Text(
            '轻触画面选择主角；若主角已被保护，会自动取消其保护。',
            style: TextStyle(fontSize: 12, color: AppTheme.textSecondary),
          ),
          Align(
            alignment: Alignment.centerLeft,
            child: TextButton(
              key: const ValueKey('reframe-cancel-selection'),
              onPressed: () {
                setState(() => _selectingFollowTarget = false);
                controller.showSourceFrame(false);
                _scheduleProfilePersist();
              },
              child: const Text('取消选主角'),
            ),
          ),
        ] else if (follow.enabled) ...[
          const SizedBox(height: 8),
          Text(
            '主角：人物 ${(follow.targetPersonId ?? 0) + 1} · 自动平滑跟随',
            style: const TextStyle(fontSize: 12, color: AppTheme.textSecondary),
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
            style: TextStyle(fontSize: 12, color: AppTheme.textSecondary),
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
            _buildSectionLabel('保护对象'),
            const SizedBox(width: 7),
            Text(
              '$selected / $total',
              style: const TextStyle(
                color: AppTheme.textMuted,
                fontSize: 12,
                fontWeight: FontWeight.w500,
              ),
            ),
            const Spacer(),
            if (selected < total)
              _TargetTextAction(
                icon: Icons.done_all_rounded,
                label: '全选',
                onPressed: () {
                  controller.selectAll();
                  _syncSelectionToEffect(
                    ref.read(effectEditorControllerProvider.notifier),
                  );
                },
              ),
            PopupMenuButton<String>(
              key: const ValueKey('protection-target-more-actions'),
              tooltip: '更多保护对象操作',
              position: PopupMenuPosition.under,
              color: AppTheme.surfaceElevated,
              surfaceTintColor: Colors.transparent,
              shadowColor: const Color(0x24000000),
              elevation: 8,
              constraints: const BoxConstraints(minWidth: 184, maxWidth: 220),
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(16),
                side: const BorderSide(color: AppTheme.surfaceBorder),
              ),
              icon: const Icon(
                Icons.more_horiz_rounded,
                size: 20,
                color: AppTheme.textSecondary,
              ),
              onSelected: (value) {
                HapticFeedback.selectionClick();
                if (value == 'reset') {
                  controller.resetSelection();
                } else if (value == 'clear') {
                  controller.deselectAll();
                }
                _syncSelectionToEffect(
                  ref.read(effectEditorControllerProvider.notifier),
                );
              },
              itemBuilder: (context) => [
                const PopupMenuItem(
                  value: 'reset',
                  child: Row(
                    children: [
                      Icon(
                        Icons.refresh_rounded,
                        size: 18,
                        color: AppTheme.textSecondary,
                      ),
                      SizedBox(width: 10),
                      Text(
                        '恢复默认选择',
                        style: TextStyle(
                          color: AppTheme.textPrimary,
                          fontSize: 13,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ],
                  ),
                ),
                PopupMenuItem(
                  value: 'clear',
                  enabled: selected > 0,
                  child: Row(
                    children: [
                      Icon(
                        Icons.remove_done_rounded,
                        size: 18,
                        color: selected > 0
                            ? AppTheme.textSecondary
                            : AppTheme.textMuted,
                      ),
                      const SizedBox(width: 10),
                      Text(
                        '清空保护对象',
                        style: TextStyle(
                          color: selected > 0
                              ? AppTheme.textPrimary
                              : AppTheme.textMuted,
                          fontSize: 13,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ],
        ),
        const SizedBox(height: 2),
        Text(
          selected == 0 ? '选择保护对象，或开启竖屏跟随以仅调整画幅' : '轻触画面中的人物可调整保护对象',
          style: TextStyle(
            color: selected == 0 ? AppTheme.coral : AppTheme.textSecondary,
            fontSize: 12,
            height: 1.35,
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
        color: AppTheme.surfaceElevated.withValues(alpha: 0.72),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(
          color: AppTheme.surfaceBorder.withValues(alpha: 0.72),
        ),
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
                        color: AppTheme.textSecondary,
                      ),
                      const SizedBox(width: 8),
                      const Expanded(
                        child: Text(
                          '高级效果',
                          style: TextStyle(
                            color: AppTheme.textSecondary,
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
                              fontSize: 12,
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
                          color: AppTheme.textMuted,
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
                  const Divider(height: 1, color: AppTheme.surfaceBorder),
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
                        color: AppTheme.textPrimary,
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
        color: subdued ? AppTheme.textSecondary : AppTheme.textPrimary,
        fontSize: subdued ? 12.5 : 13.5,
        fontWeight: FontWeight.w700,
      ),
    );
  }

  ButtonStyle _darkSegmentedButtonStyle() {
    return ButtonStyle(
      minimumSize: WidgetStateProperty.all(
        const Size(0, AppTheme.minTouchTarget),
      ),
      padding: WidgetStateProperty.all(
        const EdgeInsets.symmetric(horizontal: 6, vertical: 8),
      ),
      visualDensity: const VisualDensity(horizontal: -2, vertical: -1),
      backgroundColor: WidgetStateProperty.resolveWith((states) {
        if (states.contains(WidgetState.selected)) return AppTheme.surfaceHigh;
        return AppTheme.surfaceElevated;
      }),
      foregroundColor: WidgetStateProperty.resolveWith((states) {
        if (states.contains(WidgetState.selected)) return AppTheme.coral;
        return AppTheme.textPrimary;
      }),
      iconColor: WidgetStateProperty.resolveWith((states) {
        if (states.contains(WidgetState.selected)) return AppTheme.coral;
        return AppTheme.textSecondary;
      }),
      side: WidgetStateProperty.resolveWith((states) {
        final selected = states.contains(WidgetState.selected);
        return BorderSide(
          color: selected ? AppTheme.coral : AppTheme.surfaceBorder,
          width: selected ? 1.4 : 1,
        );
      }),
      overlayColor: WidgetStateProperty.all(
        AppTheme.surfaceHigh.withValues(alpha: 0.45),
      ),
      textStyle: WidgetStateProperty.all(
        const TextStyle(fontSize: 12.5, fontWeight: FontWeight.w600),
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
        color: AppTheme.surfaceHigh,
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
                      ? AppTheme.surfaceHigh
                      : AppTheme.surfaceElevated,
                  borderRadius: BorderRadius.circular(14),
                  border: Border.all(
                    color: selected ? AppTheme.coral : AppTheme.surfaceBorder,
                    width: selected ? 1.5 : 1,
                  ),
                ),
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Icon(
                      item.$3,
                      size: 21,
                      color: selected ? AppTheme.coral : AppTheme.textSecondary,
                    ),
                    const SizedBox(height: 4),
                    Text(
                      item.$2,
                      style: TextStyle(
                        color: selected ? AppTheme.coral : AppTheme.textPrimary,
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
                    color: selected ? AppTheme.coral : AppTheme.surfaceBorder,
                    width: selected ? 2 : 1,
                  ),
                ),
                child: Stack(
                  alignment: Alignment.center,
                  children: [
                    Icon(item.$3, color: AppTheme.textPrimary, size: 24),
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
                  color: AppTheme.textPrimary,
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
                inactiveColor: AppTheme.surfaceHigh,
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
    const colors = <(int, String)>[
      (0xFF000000, '黑色'),
      (0xFFFF5E5B, '珊瑚红'),
      (0xFFFF9EAA, '粉色'),
      (0xFF7D9CFF, '蓝紫色'),
      (0xFF71C991, '绿色'),
      (0xFFFFFFFF, '白色'),
    ];

    return Wrap(
      spacing: 6,
      runSpacing: 6,
      children: colors.map((item) {
        final argb = item.$1;
        final name = item.$2;
        final selected = currentArgb == argb;
        return Semantics(
          button: true,
          selected: selected,
          label: '$name${selected ? '，已选择' : ''}',
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
                      color: selected ? AppTheme.coral : AppTheme.surfaceBorder,
                      width: selected ? 3 : 1,
                    ),
                  ),
                  child: selected
                      ? Icon(
                          Icons.check_rounded,
                          size: 20,
                          color: argb == 0xFFFFFFFF
                              ? AppTheme.canvas
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
    final selectionState = ref.read(personSelectionControllerProvider);
    final effectState = ref.read(effectEditorControllerProvider);
    if (effectState.project == null) return;

    // Target-only operations must never create an impossible cross-mode effect
    // state. In particular FULL_BODY + faceStickerEnabled makes the native
    // renderer draw both the body mask and a face sticker on the same person.
    final effects = selectionState.privacyMode == ProjectPrivacyMode.fullBody
        ? _normalizeFullBodyDraft(effectState.effects)
        : effectState.effects;
    if (selectionState.privacyMode == ProjectPrivacyMode.fullBody) {
      _fullBodyDraft = effects;
    }

    effectController.updateEditingContext(
      project: configured,
      effects: effects,
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
      outputResolutionPreset:
          effectState.project?.outputResolutionPreset ??
          base.outputResolutionPreset,
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
    unawaited(_persistProfileNow());
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
    await _persistProfileNow();
    if (!mounted) return;
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

  const _TargetTextAction({
    required this.icon,
    required this.label,
    required this.onPressed,
  });

  @override
  Widget build(BuildContext context) {
    return Semantics(
      button: true,
      label: label,
      child: InkWell(
        borderRadius: BorderRadius.circular(12),
        onTap: () {
          HapticFeedback.selectionClick();
          onPressed();
        },
        child: ConstrainedBox(
          constraints: const BoxConstraints(minHeight: AppTheme.minTouchTarget),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 6),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(icon, size: 15, color: AppTheme.textSecondary),
                const SizedBox(width: 3),
                Text(
                  label,
                  style: const TextStyle(
                    color: AppTheme.textSecondary,
                    fontSize: 12,
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
                color: selected ? Colors.white : AppTheme.textSecondary,
              ),
              const SizedBox(width: 6),
              Text(
                label,
                style: TextStyle(
                  fontSize: 13.5,
                  fontWeight: selected ? FontWeight.w600 : FontWeight.w500,
                  color: selected ? Colors.white : AppTheme.textSecondary,
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
                color: AppTheme.textPrimary,
                fontSize: 17,
                fontWeight: FontWeight.w700,
              ),
            ),
            const SizedBox(height: 7),
            Text(
              subtitle,
              textAlign: TextAlign.center,
              style: const TextStyle(
                color: AppTheme.textSecondary,
                fontSize: 13,
              ),
            ),
            if (actionLabel != null && onAction != null) ...[
              const SizedBox(height: 20),
              OutlinedButton(
                style: OutlinedButton.styleFrom(
                  foregroundColor: AppTheme.coral,
                  side: const BorderSide(color: AppTheme.surfaceBorder),
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
