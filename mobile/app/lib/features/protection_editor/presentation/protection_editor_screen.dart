import 'dart:async';
import 'dart:io';
import 'dart:math' as math;

import 'package:dance_domain/dance_domain.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:video_player/video_player.dart';

import '../../../app/system_ui.dart';
import '../../../app/theme.dart';
import '../../../repositories/native_processing_repository.dart';
import '../../effect_editor/domain/effect_editor_state.dart';
import '../../effect_editor/presentation/effect_editor_controller.dart';
import '../../export/presentation/export_screen.dart';
import '../../import_video/domain/video_import_state.dart';
import '../../import_video/presentation/import_video_controller.dart';
import '../../person_selection/domain/person_selection_state.dart';
import '../../person_selection/presentation/person_selection_controller.dart';
import '../data/protection_profile_store.dart';
import '../data/sticker_asset_store.dart';
import '../domain/protection_profile.dart';

enum _EditorTool { trim, protect, mask, frame, adjust }

enum _AdjustProperty {
  opacity('强度', Icons.opacity_rounded),
  blur('模糊', Icons.blur_on_rounded),
  color('颜色', Icons.palette_rounded),
  border('描边', Icons.line_weight_rounded),
  skinWhiten('美肤', Icons.face_rounded),
  legStretch('拉腿', Icons.height_rounded);

  final String label;
  final IconData icon;
  const _AdjustProperty(this.label, this.icon);
}

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
  static const double _toolNavigationHeight = 64;

  EffectConfig? _fullBodyDraft;
  EffectConfig? _faceOnlyDraft;
  late int _trimStartMs;
  late int _trimEndMs;
  int _committedTrimStartMs = 0;
  int _committedTrimEndMs = 0;
  bool _trimApplying = false;
  bool _replacingVideo = false;
  String? _recoveryPreviewPath;
  bool _selectionNeedsReview = false;
  bool _trimResetFollow = false;
  bool _allowRoutePop = false;
  bool _returnRequested = false;
  _EditorTool? _activeTool;
  _AdjustProperty _activeAdjustProperty = _AdjustProperty.opacity;
  bool _isPlaying = false;
  int _currentPlaybackMs = 0;
  Timer? _playbackTimer;
  VideoPlayerController? _videoController;
  bool _videoInitialized = false;
  bool _originalAudioEnabled = true;
  bool _selectingFollowTarget = false;
  bool _profileReady = false;
  bool _stickerImporting = false;
  Timer? _profileSaveDebounce;
  String? _trimDragMode;

  Color get _canvasColor => Theme.of(context).brightness == Brightness.dark
      ? Colors.black
      : AppTheme.of(context).background;

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
      unawaited(_initVideoPlayer());
    });
  }

  Future<void> _initializeEditor({
    DanceProject? project,
    bool forceAnalyze = false,
  }) async {
    _recoveryPreviewPath = null;
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
    if (forceAnalyze) {
      await selectionController.analyzeProject(
        initialProject,
        selectionPreviewEnabled: false,
      );
    } else {
      await selectionController.prepareProject(
        initialProject,
        selectionPreviewEnabled: false,
      );
    }
    if (!mounted) return;

    if (savedProfile != null) {
      selectionController.setProjectPrivacyMode(savedProfile.privacyMode);
    }

    final selectionState = ref.read(personSelectionControllerProvider);
    final configured = selectionController.buildConfiguredProject();
    if (configured == null || selectionState.persons.isEmpty) {
      await _loadRecoveryPreview();
      return;
    }

    final isFaceMode =
        selectionState.privacyMode == ProjectPrivacyMode.faceOnly;
    if (savedProfile != null) {
      _fullBodyDraft = _normalizeFullBodyDraft(
        _profileSafeEffects(savedProfile.fullBodyEffects),
      );
      _faceOnlyDraft = _normalizeFaceDraft(
        _profileSafeEffects(savedProfile.faceOnlyEffects),
      );
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
    if (!_profileReady || _trimApplying) return;
    _profileSaveDebounce?.cancel();
    _profileSaveDebounce = Timer(
      const Duration(milliseconds: 450),
      () => unawaited(_persistProfileNow()),
    );
  }

  Future<void> _persistProfileNow() async {
    if (!_profileReady || !mounted || _trimApplying) return;
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
      fullBodyEffects: _profileSafeEffects(
        _fullBodyDraft ?? _defaultFullBodyDraft(effectState.effects),
      ),
      faceOnlyEffects: _profileSafeEffects(
        _faceOnlyDraft ?? _defaultFaceDraft(effectState.effects),
      ),
      outputResolutionPreset: project.outputResolutionPreset,
      portraitReframe: project.follow.enabled || _selectingFollowTarget,
    );
    await ref.read(protectionProfileStoreProvider).save(profile);
  }

  EffectConfig _profileSafeEffects(EffectConfig effects) {
    return effects.copyWith(legStretchEnabled: false);
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

  void _togglePlayback() {
    HapticFeedback.selectionClick();
    final controller = _videoController;
    if (controller != null && controller.value.isInitialized) {
      if (controller.value.isPlaying) {
        controller.pause();
      } else {
        if (_currentPlaybackMs >= _trimEndMs ||
            _currentPlaybackMs < _trimStartMs) {
          controller.seekTo(Duration(milliseconds: _trimStartMs));
        }
        controller.play();
      }
      return;
    }
    setState(() {
      _isPlaying = !_isPlaying;
    });
    if (_isPlaying) {
      _playbackTimer?.cancel();
      _playbackTimer = Timer.periodic(const Duration(milliseconds: 100), (
        timer,
      ) {
        if (!mounted) {
          timer.cancel();
          return;
        }
        setState(() {
          _currentPlaybackMs += 100;
          if (_currentPlaybackMs > _trimEndMs) {
            _currentPlaybackMs = _trimStartMs;
          }
        });
      });
    } else {
      _playbackTimer?.cancel();
      _playbackTimer = null;
    }
  }

  Future<void> _initVideoPlayer() async {
    try {
      final source = widget.project.sourceUri;
      if (source.isEmpty) return;
      final controller = source.startsWith('content://')
          ? VideoPlayerController.contentUri(Uri.parse(source))
          : VideoPlayerController.file(
              File(source.startsWith('file://') ? source.substring(7) : source),
            );
      await controller.initialize();
      controller.setLooping(false);
      controller.setVolume(_originalAudioEnabled ? 1.0 : 0.0);
      controller.addListener(_onVideoTick);
      await controller.seekTo(Duration(milliseconds: _trimStartMs));
      if (!mounted) {
        await controller.dispose();
        return;
      }
      setState(() {
        _videoController = controller;
        _videoInitialized = true;
      });
    } catch (_) {
      // Test environment or unsupported video uri fallback
    }
  }

  void _onVideoTick() {
    final controller = _videoController;
    if (controller == null || !controller.value.isInitialized || !mounted) {
      return;
    }
    final posMs = controller.value.position.inMilliseconds.clamp(
      0,
      _sourceDurationMs,
    );
    final playing = controller.value.isPlaying;
    if (playing != _isPlaying) {
      setState(() => _isPlaying = playing);
    }
    if (playing && posMs >= _trimEndMs) {
      controller.pause();
      controller.seekTo(Duration(milliseconds: _trimStartMs));
      setState(() {
        _isPlaying = false;
        _currentPlaybackMs = _trimStartMs;
      });
    } else {
      setState(() => _currentPlaybackMs = posMs);
    }
  }

  @override
  void dispose() {
    _playbackTimer?.cancel();
    _profileSaveDebounce?.cancel();
    _videoController?.removeListener(_onVideoTick);
    _videoController?.dispose();
    super.dispose();
  }

  int get _sourceDurationMs => math.max(widget.project.videoInfo.durationMs, 1);

  void _setTrimStart(int valueMs) {
    final maxStart = (_trimEndMs - _minimumClipMs).clamp(0, _sourceDurationMs);
    final value = valueMs.clamp(0, maxStart);
    if (value == _trimStartMs) return;
    setState(() {
      _trimStartMs = value;
      if (_currentPlaybackMs < _trimStartMs) {
        _currentPlaybackMs = _trimStartMs;
      }
    });
    _videoController?.seekTo(Duration(milliseconds: value));
  }

  void _setTrimEnd(int valueMs) {
    final minEnd = (_trimStartMs + _minimumClipMs).clamp(0, _sourceDurationMs);
    final value = valueMs.clamp(minEnd, _sourceDurationMs);
    if (value == _trimEndMs) return;
    setState(() {
      _trimEndMs = value;
      if (_currentPlaybackMs > _trimEndMs) {
        _currentPlaybackMs = _trimEndMs;
      }
    });
    _videoController?.seekTo(Duration(milliseconds: value));
  }

  Future<void> _applyTrimChange() async {
    if (_trimApplying ||
        (_trimStartMs == _committedTrimStartMs &&
            _trimEndMs == _committedTrimEndMs)) {
      return;
    }

    _captureActiveDraft();
    final currentProject = _buildCurrentProject();
    final selectionController = ref.read(
      personSelectionControllerProvider.notifier,
    );
    final privacyMode = ref.read(personSelectionControllerProvider).privacyMode;
    final trimmedProject = currentProject.copyWith(
      trimStartMs: _trimStartMs,
      trimEndMs: _trimEndMs,
      updatedAt: DateTime.now(),
    );

    // Changing only the end does not change the analyzed first frame or IDs.
    if (_trimStartMs == _committedTrimStartMs) {
      selectionController.updateProject(trimmedProject);
      selectionController.setProjectPrivacyMode(privacyMode);
      ref
          .read(effectEditorControllerProvider.notifier)
          .updateEditingContext(
            project: trimmedProject,
            effects: currentProject.effects,
          );
      setState(() => _committedTrimEndMs = _trimEndMs);
      return;
    }

    final analysisProject = trimmedProject.copyWith(
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
      _recoveryPreviewPath = null;
    });
    _profileSaveDebounce?.cancel();
    await selectionController.analyzeProject(
      analysisProject,
      selectionPreviewEnabled: false,
      privacyMode: privacyMode,
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
          .init(
            configured.copyWith(
              effects: activeEffects.copyWith(legStretchEnabled: false),
            ),
          );
      setState(() {
        _committedTrimStartMs = _trimStartMs;
        _committedTrimEndMs = _trimEndMs;
        _trimApplying = false;
        _selectionNeedsReview = true;
        _trimResetFollow = currentProject.follow.enabled;
        _activeTool = _EditorTool.mask;
      });
      _scheduleProfilePersist();
      return;
    }

    await _loadRecoveryPreview();
    if (mounted) setState(() => _trimApplying = false);
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
        !_trimApplying &&
        !_selectionNeedsReview &&
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
        value: appSystemUiStyleFor(Theme.of(context).brightness).copyWith(
          statusBarColor: _canvasColor,
          systemNavigationBarColor: _canvasColor,
        ),
        child: Scaffold(
          backgroundColor: _canvasColor,
          resizeToAvoidBottomInset: false,
          body: SafeArea(
            bottom: false,
            child: LayoutBuilder(
              builder: (context, constraints) {
                final panelHeight = showControls
                    ? (_activeTool == null
                          ? _toolNavigationHeight +
                                MediaQuery.paddingOf(context).bottom
                          : (constraints.maxHeight * 0.39).clamp(286.0, 356.0))
                    : 0.0;
                return Column(
                  children: [
                    SizedBox(
                      height: 56,
                      child: _buildTopActions(
                        nextEnabled,
                        project,
                        effectController,
                      ),
                    ),
                    Expanded(
                      child: _buildStage(
                        selectionState,
                        selectionController,
                        effectState,
                        effectController,
                        aspectRatio: aspectRatio,
                      ),
                    ),
                    if (showControls)
                      SizedBox(
                        height: panelHeight,
                        child: _buildWorkspacePanel(
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
      ),
    );
  }

  Widget _buildTopActions(
    bool nextEnabled,
    DanceProject project,
    EffectEditorController effectController,
  ) {
    final preset = project.outputResolutionPreset;
    final resLabel = switch (preset) {
      OutputResolutionPreset.source => '原始',
      OutputResolutionPreset.fhd => '1080p',
      OutputResolutionPreset.hd => '720p',
    };

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      child: Row(
        children: [
          // Left: Cancel icon button
          GestureDetector(
            key: const ValueKey('protection-editor-cancel-action'),
            onTap: _requestReturn,
            behavior: HitTestBehavior.opaque,
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 6),
              child: Semantics(
                label: '取消',
                button: true,
                child: Icon(
                  Icons.close_rounded,
                  color: AppTheme.of(context).textPrimary,
                  size: 24,
                ),
              ),
            ),
          ),
          const Spacer(),
          // Middle: Resolution trigger (pure text + dropdown arrow, no capsule)
          GestureDetector(
            key: const ValueKey('protection-editor-resolution-trigger'),
            onTap: () =>
                _showResolutionDialog(context, project, effectController),
            behavior: HitTestBehavior.opaque,
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 6),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    resLabel,
                    style: TextStyle(
                      color: AppTheme.of(context).textPrimary,
                      fontSize: 13.5,
                      fontWeight: FontWeight.w500,
                    ),
                  ),
                  const SizedBox(width: 1),
                  Icon(
                    Icons.arrow_drop_down_rounded,
                    color: AppTheme.of(context).textSecondary,
                    size: 18,
                  ),
                ],
              ),
            ),
          ),
          const Spacer(),
          // Right: Export icon action (brand-accent icon, no capsule background)
          GestureDetector(
            key: const ValueKey('protection-editor-export-action'),
            onTap: nextEnabled ? _continueToExport : null,
            behavior: HitTestBehavior.opaque,
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 6),
              child: Semantics(
                label: '导出',
                button: true,
                child: Icon(
                  Icons.file_upload_outlined,
                  color: nextEnabled
                      ? AppTheme.of(context).editorSelection
                      : AppTheme.of(context).textMuted,
                  size: 24,
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  void _showResolutionDialog(
    BuildContext context,
    DanceProject project,
    EffectEditorController effectController,
  ) {
    final current = project.outputResolutionPreset;
    showGeneralDialog(
      context: context,
      barrierDismissible: true,
      barrierLabel: '关闭清晰度选择',
      barrierColor: const Color(0x66000000),
      transitionDuration: const Duration(milliseconds: 180),
      pageBuilder: (dialogContext, anim1, anim2) {
        return SafeArea(
          child: Align(
            alignment: Alignment.topCenter,
            child: Padding(
              padding: const EdgeInsets.only(top: 48),
              child: Material(
                color: Colors.transparent,
                child: Container(
                  width: 248,
                  decoration: BoxDecoration(
                    color: AppTheme.of(context).surfaceElevated,
                    borderRadius: BorderRadius.circular(AppTheme.radiusMedium),
                    border: Border.all(
                      color: AppTheme.of(context).surfaceBorder,
                    ),
                    boxShadow: const [
                      BoxShadow(
                        color: Color(0x7F000000),
                        blurRadius: 24,
                        offset: Offset(0, 10),
                      ),
                    ],
                  ),
                  clipBehavior: Clip.antiAlias,
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      _buildResolutionDialogItem(
                        dialogContext,
                        OutputResolutionPreset.source,
                        '原始',
                        '原始素材清晰度',
                        current,
                        effectController,
                      ),
                      Divider(
                        height: 1,
                        color: AppTheme.of(context).surfaceBorder,
                      ),
                      _buildResolutionDialogItem(
                        dialogContext,
                        OutputResolutionPreset.fhd,
                        '1080p',
                        '全高清 FHD',
                        current,
                        effectController,
                      ),
                      Divider(
                        height: 1,
                        color: AppTheme.of(context).surfaceBorder,
                      ),
                      _buildResolutionDialogItem(
                        dialogContext,
                        OutputResolutionPreset.hd,
                        '720p',
                        '高清 HD',
                        current,
                        effectController,
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
        );
      },
      transitionBuilder: (context, anim1, anim2, child) {
        return FadeTransition(
          opacity: CurvedAnimation(parent: anim1, curve: Curves.easeOut),
          child: ScaleTransition(
            scale: Tween<double>(begin: 0.95, end: 1.0).animate(
              CurvedAnimation(parent: anim1, curve: Curves.easeOutCubic),
            ),
            child: child,
          ),
        );
      },
    );
  }

  Widget _buildResolutionDialogItem(
    BuildContext dialogContext,
    OutputResolutionPreset value,
    String label,
    String desc,
    OutputResolutionPreset current,
    EffectEditorController effectController,
  ) {
    final isSelected = value == current;
    return InkWell(
      onTap: () {
        HapticFeedback.selectionClick();
        effectController.updateOutputResolutionPreset(value);
        Navigator.of(dialogContext).pop();
      },
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
        child: Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    label,
                    style: TextStyle(
                      color: isSelected
                          ? AppTheme.of(context).editorSelection
                          : AppTheme.of(context).textPrimary,
                      fontSize: 14,
                      fontWeight: isSelected
                          ? FontWeight.w700
                          : FontWeight.w500,
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    desc,
                    style: TextStyle(
                      color: AppTheme.of(context).textMuted,
                      fontSize: 11,
                    ),
                  ),
                ],
              ),
            ),
            if (isSelected)
              Icon(
                Icons.check_rounded,
                color: AppTheme.of(context).editorSelection,
                size: 18,
              ),
          ],
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
  }) {
    return LayoutBuilder(
      builder: (context, constraints) {
        if (selectionState.isAnalyzing) {
          return _EditorStatus(
            icon: Icons.person_search_rounded,
            title: _trimApplying ? '裁剪后重新识别人…' : '正在识别人…',
            subtitle: _trimApplying
                ? '保护范围与样式会保留，完成后请确认人物'
                : '完成后即可直接在画面中选择保护对象',
            loading: true,
          );
        }

        if (selectionState.status == PersonSelectionStatus.error) {
          return _EditorStatus(
            icon: Icons.error_outline_rounded,
            title: '人物识别失败',
            subtitle: selectionState.errorMessage ?? '请稍后重试',
            actionLabel: '重新识别',
            onAction: _replacingVideo
                ? null
                : () => _reanalyze(selectionController),
            secondaryActionLabel: _replacingVideo ? '正在读取视频…' : '重新选择视频',
            onSecondaryAction: _replacingVideo ? null : _replaceVideo,
            preview: _buildRecoveryPreview(),
          );
        }

        if (selectionState.persons.isEmpty) {
          return _EditorStatus(
            icon: Icons.person_off_outlined,
            title: '没有找到可保护的人物',
            subtitle: '当前起始画面未识别到人物，请选择人物清晰可见的视频，或重新识别。',
            actionLabel: _replacingVideo ? '正在读取视频…' : '重新选择视频',
            onAction: _replacingVideo ? null : _replaceVideo,
            secondaryActionLabel: '重新识别',
            onSecondaryAction: _replacingVideo
                ? null
                : () => _reanalyze(selectionController),
            preview: _buildRecoveryPreview(),
          );
        }

        return Column(
          children: [
            _buildSelectionToolbar(
              selectionState,
              selectionController,
              effectState,
              effectController,
            ),
            Expanded(
              child: SizedBox.expand(
                key: const ValueKey('protection-editor-media-stage'),
                child: _buildInteractivePreview(
                  selectionState,
                  selectionController,
                  effectState,
                  effectController,
                  aspectRatio: aspectRatio,
                ),
              ),
            ),
          ],
        );
      },
    );
  }

  Widget _buildSelectionToolbar(
    PersonSelectionState selectionState,
    PersonSelectionController selectionController,
    EffectEditorState effectState,
    EffectEditorController effectController,
  ) {
    final colors = AppTheme.of(context);
    final follow = effectState.project?.follow ?? const FollowConfig();
    final count = selectionState.privacyTargetIds.length;
    final title = _selectingFollowTarget
        ? '轻触人物选择主角'
        : _selectionNeedsReview
        ? '已重新识别 · 保护 $count 人'
        : _activeTool == _EditorTool.trim
        ? '原片预览'
        : follow.enabled
        ? '主角：人物 ${(follow.targetPersonId ?? 0) + 1} · 保护 $count 人'
        : '保护 $count 人';
    final canToggleSource =
        follow.enabled &&
        !_selectingFollowTarget &&
        !_selectionNeedsReview &&
        _activeTool != _EditorTool.trim;
    final hint = _selectingFollowTarget
        ? '主角保留清晰，并自动跟随'
        : _selectionNeedsReview
        ? (_trimResetFollow ? '主角需重选，范围与样式已保留' : '范围与样式已保留，请确认保护对象')
        : canToggleSource
        ? (effectState.showSourcePreview ? '裁切预览' : '查看原片')
        : null;
    final status = Padding(
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(
            title,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(
              color: colors.textPrimary,
              fontSize: 13,
              fontWeight: FontWeight.w600,
            ),
          ),
          if (hint != null) ...[
            const SizedBox(height: 3),
            Text(
              hint,
              maxLines: 2,
              style: TextStyle(
                color: canToggleSource
                    ? colors.editorSelection
                    : colors.textSecondary,
                fontSize: 11.5,
              ),
            ),
          ],
        ],
      ),
    );

    return Material(
      key: ValueKey(
        _selectingFollowTarget
            ? 'reframe-subject-prompt'
            : 'protection-stage-prompt',
      ),
      color: Colors.transparent,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 2),
        child: Row(
          children: [
            Expanded(
              child: Semantics(
                liveRegion: true,
                child: canToggleSource
                    ? InkWell(
                        onTap: () => effectController.showSourceFrame(
                          !effectState.showSourcePreview,
                        ),
                        child: status,
                      )
                    : status,
              ),
            ),
            if (_selectingFollowTarget) ...[
              TextButton(
                key: const ValueKey('reframe-cancel-selection'),
                onPressed: () {
                  setState(() => _selectingFollowTarget = false);
                  effectController.showSourceFrame(false);
                  _scheduleProfilePersist();
                },
                child: const Text('取消'),
              ),
              TextButton(
                key: const ValueKey('reframe-protect-all'),
                onPressed: () {
                  selectionController.selectAll();
                  effectController.updateFollowConfig(enabled: false);
                  _syncSelectionToEffect(effectController);
                  setState(() {
                    _selectingFollowTarget = false;
                    _selectionNeedsReview = false;
                    _trimResetFollow = false;
                  });
                },
                child: const Text('保护全部'),
              ),
            ] else if (_selectionNeedsReview) ...[
              if (_trimResetFollow)
                TextButton(
                  key: const ValueKey('trim-reselect-subject'),
                  onPressed: () {
                    setState(() => _selectingFollowTarget = true);
                    effectController.showSourceFrame(true);
                  },
                  child: const Text('重选主角'),
                ),
              TextButton(
                key: const ValueKey('trim-confirm-selection'),
                onPressed: () => setState(() {
                  _selectionNeedsReview = false;
                  _trimResetFollow = false;
                }),
                child: const Text('确认保护'),
              ),
            ] else ...[
              _buildStageCircleButton(
                key: const ValueKey('protection-restore-default-action'),
                tooltip: '恢复默认选择',
                icon: Icons.refresh_rounded,
                iconSize: 17,
                enabled: true,
                onTap: () {
                  selectionController.resetSelection();
                  _syncSelectionToEffect(effectController);
                },
              ),
              _buildStageCircleButton(
                key: const ValueKey('protection-clear-target-action'),
                tooltip: '清空保护对象',
                icon: Icons.person_off_rounded,
                iconSize: 16,
                enabled: count > 0,
                onTap: () {
                  selectionController.deselectAll();
                  _syncSelectionToEffect(effectController);
                },
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildInteractivePreview(
    PersonSelectionState selectionState,
    PersonSelectionController selectionController,
    EffectEditorState effectState,
    EffectEditorController effectController, {
    required double aspectRatio,
  }) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final stageWidth = constraints.maxWidth;
        final stageHeight = constraints.maxHeight;
        final stageAspect = stageWidth / math.max(stageHeight, 1.0);
        final mediaWidth = stageAspect > aspectRatio
            ? stageHeight * aspectRatio
            : stageWidth;
        final mediaHeight = mediaWidth / aspectRatio;
        final mediaLeft = (stageWidth - mediaWidth) / 2;
        final mediaTop = (stageHeight - mediaHeight) / 2;

        return Stack(
          fit: StackFit.expand,
          children: [
            ColoredBox(color: _canvasColor),
            Positioned(
              key: const ValueKey('protection-editor-media-frame'),
              left: mediaLeft,
              top: mediaTop,
              width: mediaWidth,
              height: mediaHeight,
              child: ColoredBox(
                color: _canvasColor,
                child: _buildMediaContent(
                  selectionState,
                  selectionController,
                  effectState,
                  effectController,
                ),
              ),
            ),
          ],
        );
      },
    );
  }

  Widget _buildStageCircleButton({
    required Key key,
    required String tooltip,
    required IconData icon,
    required double iconSize,
    required bool enabled,
    required VoidCallback onTap,
  }) {
    return Tooltip(
      message: tooltip,
      child: GestureDetector(
        key: key,
        behavior: HitTestBehavior.opaque,
        onTap: enabled ? onTap : null,
        child: SizedBox(
          width: AppTheme.minTouchTarget,
          height: AppTheme.minTouchTarget,
          child: Center(
            child: Icon(
              icon,
              size: iconSize + 3,
              color: enabled
                  ? AppTheme.of(context).textPrimary
                  : AppTheme.of(context).textMuted.withAlpha(120),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildPlaybackBar() {
    final totalDurationMs = math.max(_sourceDurationMs, 1000);
    final currentMs = _currentPlaybackMs.clamp(0, totalDurationMs);
    final currentStr = _formatTrimTime(currentMs);

    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        DefaultTextStyle(
          style: TextStyle(
            color: AppTheme.of(context).textSecondary,
            fontSize: 11.5,
            fontFeatures: const [FontFeature.tabularFigures()],
          ),
          child: Wrap(
            key: const ValueKey('trim-selection-summary'),
            alignment: WrapAlignment.spaceBetween,
            spacing: 12,
            runSpacing: 6,
            children: [
              Text(
                '起点 ${_formatTrimTime(_trimStartMs)}',
                key: const ValueKey('trim-start-time'),
              ),
              Text(
                '终点 ${_formatTrimTime(_trimEndMs)}',
                key: const ValueKey('trim-end-time'),
              ),
              Text(
                '保留 ${((_trimEndMs - _trimStartMs) / 1000).toStringAsFixed(1)} 秒',
                key: const ValueKey('trim-kept-duration'),
                style: TextStyle(
                  color: AppTheme.of(context).editorSelection,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: 8),
        Row(
          children: [
            SizedBox(
              width: 100,
              child: Text(
                '播放 $currentStr',
                style: TextStyle(
                  color: AppTheme.of(context).textSecondary,
                  fontSize: 12,
                  fontWeight: FontWeight.w500,
                  fontFeatures: [FontFeature.tabularFigures()],
                ),
              ),
            ),
            const Spacer(),
            IconButton(
              tooltip: _isPlaying ? '暂停预览' : '播放预览',
              onPressed: _togglePlayback,
              icon: Icon(
                _isPlaying ? Icons.pause_rounded : Icons.play_arrow_rounded,
                color: AppTheme.of(context).textPrimary,
                size: 28,
              ),
            ),
            const Spacer(),
            // Right: Original audio toggle button (aligned to right, balanced width to keep play button centered)
            Container(
              width: 100,
              alignment: Alignment.centerRight,
              child: Tooltip(
                message: _originalAudioEnabled ? '关闭原声' : '开启原声',
                child: GestureDetector(
                  key: const ValueKey('trim-media-audio-toggle'),
                  behavior: HitTestBehavior.opaque,
                  onTap: () {
                    HapticFeedback.selectionClick();
                    setState(
                      () => _originalAudioEnabled = !_originalAudioEnabled,
                    );
                    _videoController?.setVolume(
                      _originalAudioEnabled ? 1.0 : 0.0,
                    );
                  },
                  child: SizedBox(
                    width: AppTheme.minTouchTarget,
                    height: AppTheme.minTouchTarget,
                    child: Center(
                      child: Icon(
                        _originalAudioEnabled
                            ? Icons.volume_up_rounded
                            : Icons.volume_off_rounded,
                        size: 22,
                        color: _originalAudioEnabled
                            ? AppTheme.of(context).editorSelection
                            : AppTheme.of(context).textMuted,
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ],
        ),
        const SizedBox(height: 6),
        // Integrated slim trim range slider & playhead
        _buildIntegratedTrimTrack(),
        ExcludeSemantics(
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 8),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                for (var i = 0; i <= 4; i++)
                  Text(
                    _formatTimestamp((totalDurationMs * i / 4).round()),
                    style: TextStyle(
                      color: AppTheme.of(context).textMuted,
                      fontSize: 10.5,
                    ),
                  ),
              ],
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildIntegratedTrimTrack() {
    final durationMs = math.max(_sourceDurationMs, 1000);
    return SizedBox(
      key: const ValueKey('integrated-video-trim-control'),
      height: AppTheme.minTouchTarget,
      child: LayoutBuilder(
        builder: (context, constraints) {
          final width = constraints.maxWidth;
          const trackInset = 8.0;
          final trackWidth = math.max(width - 2 * trackInset, 1.0);

          double xForMs(int ms) =>
              trackInset + trackWidth * (ms.clamp(0, durationMs) / durationMs);
          int msForX(double x) =>
              (((x - trackInset) / trackWidth).clamp(0.0, 1.0) * durationMs)
                  .round();

          final startX = xForMs(_trimStartMs);
          final endX = xForMs(_trimEndMs);
          final currentX = xForMs(_currentPlaybackMs);
          const handleWidth = 10.0;
          const trackHeight = 14.0;
          const bgHeight = 6.0;

          return GestureDetector(
            behavior: HitTestBehavior.opaque,
            onHorizontalDragStart: (details) {
              final x = details.localPosition.dx;
              final startDistance = (x - startX).abs();
              final endDistance = (x - endX).abs();
              const hitRadius = 24.0;
              if (startDistance <= hitRadius && startDistance <= endDistance) {
                _trimDragMode = 'start';
              } else if (endDistance <= hitRadius) {
                _trimDragMode = 'end';
              } else {
                _trimDragMode = 'scrub';
                final ms = msForX(x).clamp(_trimStartMs, _trimEndMs);
                setState(() => _currentPlaybackMs = ms);
                _videoController?.seekTo(Duration(milliseconds: ms));
              }
            },
            onHorizontalDragUpdate: (details) {
              final ms = msForX(details.localPosition.dx);
              if (_trimDragMode == 'start') {
                _setTrimStart(ms);
              } else if (_trimDragMode == 'end') {
                _setTrimEnd(ms);
              } else if (_trimDragMode == 'scrub') {
                final target = ms.clamp(_trimStartMs, _trimEndMs);
                setState(() => _currentPlaybackMs = target);
                _videoController?.seekTo(Duration(milliseconds: target));
              }
            },
            onHorizontalDragEnd: (_) {
              final changed =
                  _trimDragMode == 'start' || _trimDragMode == 'end';
              _trimDragMode = null;
              if (changed) {
                unawaited(_applyTrimChange());
              }
            },
            onHorizontalDragCancel: () => _trimDragMode = null,
            child: Stack(
              clipBehavior: Clip.none,
              children: [
                // Thin background track (centered vertically)
                Positioned(
                  left: trackInset,
                  right: trackInset,
                  top: (AppTheme.minTouchTarget - bgHeight) / 2,
                  height: bgHeight,
                  child: Container(
                    decoration: BoxDecoration(
                      color: AppTheme.of(context).surfaceElevated,
                      borderRadius: BorderRadius.circular(3),
                      border: Border.all(
                        color: AppTheme.of(context).surfaceBorder,
                        width: 0.8,
                      ),
                    ),
                  ),
                ),
                // Dimmed left region (before trimStart)
                if (startX > trackInset)
                  Positioned(
                    left: trackInset,
                    width: startX - trackInset,
                    top: (AppTheme.minTouchTarget - bgHeight) / 2,
                    height: bgHeight,
                    child: Container(
                      decoration: const BoxDecoration(
                        color: Color(0x99000000),
                        borderRadius: BorderRadius.horizontal(
                          left: Radius.circular(3),
                        ),
                      ),
                    ),
                  ),
                // Dimmed right region (after trimEnd)
                if (endX < width - trackInset)
                  Positioned(
                    left: endX,
                    right: trackInset,
                    top: (AppTheme.minTouchTarget - bgHeight) / 2,
                    height: bgHeight,
                    child: Container(
                      decoration: const BoxDecoration(
                        color: Color(0x99000000),
                        borderRadius: BorderRadius.horizontal(
                          right: Radius.circular(3),
                        ),
                      ),
                    ),
                  ),
                // Editor selection highlight over the trim timeline
                Positioned(
                  left: startX,
                  width: (endX - startX).clamp(0.0, trackWidth),
                  top: (AppTheme.minTouchTarget - trackHeight) / 2,
                  height: trackHeight,
                  child: Container(
                    decoration: BoxDecoration(
                      color: AppTheme.of(context).editorSelection.withAlpha(25),
                      border: Border.all(
                        color: AppTheme.of(context).editorSelection,
                        width: 1.2,
                      ),
                      borderRadius: BorderRadius.circular(4),
                    ),
                  ),
                ),
                // Left start handle
                Positioned(
                  left: startX - handleWidth / 2,
                  top: (AppTheme.minTouchTarget - trackHeight) / 2,
                  child: const _IntegratedTrimHandle(
                    key: ValueKey('trim-start-handle'),
                    isLeft: true,
                  ),
                ),
                // Right end handle
                Positioned(
                  left: endX - handleWidth / 2,
                  top: (AppTheme.minTouchTarget - trackHeight) / 2,
                  child: const _IntegratedTrimHandle(
                    key: ValueKey('trim-end-handle'),
                    isLeft: false,
                  ),
                ),
                // Current playback needle indicator
                Positioned(
                  left: currentX - 1,
                  top: (AppTheme.minTouchTarget - (trackHeight + 4)) / 2,
                  child: Container(
                    width: 2,
                    height: trackHeight + 4,
                    decoration: BoxDecoration(
                      color: Colors.white,
                      borderRadius: BorderRadius.circular(1),
                      boxShadow: const [
                        BoxShadow(
                          color: Color(0x66000000),
                          blurRadius: 2,
                          offset: Offset(0, 1),
                        ),
                      ],
                    ),
                  ),
                ),
                for (final isStart in [true, false])
                  Positioned(
                    left: ((isStart ? startX : endX) - 24).clamp(
                      0.0,
                      math.max(width - 48, 0.0),
                    ),
                    top: 0,
                    child: Semantics(
                      key: ValueKey(
                        isStart ? 'trim-start-control' : 'trim-end-control',
                      ),
                      slider: true,
                      label: isStart ? '裁剪起点' : '裁剪终点',
                      value: _formatTrimTime(
                        isStart ? _trimStartMs : _trimEndMs,
                      ),
                      increasedValue: _trimBoundaryTime(isStart, 100),
                      decreasedValue: _trimBoundaryTime(isStart, -100),
                      onIncrease:
                          (isStart
                              ? _trimStartMs < _trimEndMs - _minimumClipMs
                              : _trimEndMs < _sourceDurationMs)
                          ? () => _nudgeTrim(isStart, 100)
                          : null,
                      onDecrease:
                          (isStart
                              ? _trimStartMs > 0
                              : _trimEndMs > _trimStartMs + _minimumClipMs)
                          ? () => _nudgeTrim(isStart, -100)
                          : null,
                      child: const SizedBox(
                        width: AppTheme.minTouchTarget,
                        height: AppTheme.minTouchTarget,
                      ),
                    ),
                  ),
              ],
            ),
          );
        },
      ),
    );
  }

  String _formatTrimTime(int ms) {
    final rounded = (ms / 100).round() * 100;
    return '${_formatTimestamp(rounded)}.${(rounded % 1000) ~/ 100}';
  }

  void _nudgeTrim(bool isStart, int deltaMs) {
    if (_trimApplying) return;
    if (isStart) {
      _setTrimStart(_trimStartMs + deltaMs);
    } else {
      _setTrimEnd(_trimEndMs + deltaMs);
    }
    unawaited(_applyTrimChange());
  }

  String _trimBoundaryTime(bool isStart, int deltaMs) {
    final value = (isStart ? _trimStartMs : _trimEndMs) + deltaMs;
    final minimum = isStart
        ? 0
        : (_trimStartMs + _minimumClipMs).clamp(0, _sourceDurationMs);
    final maximum = isStart
        ? (_trimEndMs - _minimumClipMs).clamp(0, _sourceDurationMs)
        : _sourceDurationMs;
    return _formatTrimTime(value.clamp(minimum, maximum));
  }

  String _formatTimestamp(int ms) {
    final totalSec = ms ~/ 1000;
    final m = totalSec ~/ 60;
    final s = totalSec % 60;
    return '${m.toString().padLeft(2, '0')}:${s.toString().padLeft(2, '0')}';
  }

  Widget _buildMediaContent(
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
            ColoredBox(color: _canvasColor),
            if (_activeTool == _EditorTool.trim &&
                _videoController != null &&
                _videoInitialized)
              Positioned.fill(
                child: GestureDetector(
                  behavior: HitTestBehavior.opaque,
                  onTap: _togglePlayback,
                  child: Center(
                    child: AspectRatio(
                      aspectRatio: _videoController!.value.aspectRatio > 0
                          ? _videoController!.value.aspectRatio
                          : (stageHeight > 0
                                ? stageWidth / stageHeight
                                : 9 / 16),
                      child: VideoPlayer(_videoController!),
                    ),
                  ),
                ),
              )
            else if (hasImage)
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
            if (_activeTool != _EditorTool.trim &&
                (effectState.project?.follow.enabled != true ||
                    effectState.showSourcePreview))
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
                      setState(() {
                        _selectingFollowTarget = false;
                        _selectionNeedsReview = false;
                        _trimResetFollow = false;
                      });
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
                bottom: 12,
                child: IgnorePointer(
                  child: Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 11,
                      vertical: 7,
                    ),
                    decoration: BoxDecoration(
                      color: AppTheme.of(
                        context,
                      ).surfaceElevated.withValues(alpha: 0.94),
                      borderRadius: BorderRadius.circular(18),
                      border: Border.all(
                        color: AppTheme.of(context).surfaceBorder,
                      ),
                    ),
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        SizedBox(
                          width: 13,
                          height: 13,
                          child: CircularProgressIndicator(
                            strokeWidth: 1.7,
                            color: AppTheme.of(context).editorSelection,
                          ),
                        ),
                        SizedBox(width: 7),
                        Text(
                          '更新预览…',
                          style: TextStyle(
                            color: AppTheme.of(context).textSecondary,
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
                      color: AppTheme.of(context).surfaceElevated,
                      borderRadius: BorderRadius.circular(12),
                      border: Border.all(
                        color: AppTheme.of(
                          context,
                        ).editorSelection.withAlpha(100),
                      ),
                    ),
                    child: Text(
                      '预览暂时无法更新，选择与参数仍会保留。',
                      textAlign: TextAlign.center,
                      style: TextStyle(
                        color: AppTheme.of(context).textSecondary,
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
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(
            Icons.movie_filter_outlined,
            size: 36,
            color: AppTheme.of(context).textMuted,
          ),
          SizedBox(height: 10),
          Text(
            '保护效果预览',
            style: TextStyle(
              color: AppTheme.of(context).textSecondary,
              fontSize: 12,
            ),
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

    final minHitSize = math.min(
      48.0,
      math.max(20.0, math.min(stageWidth, stageHeight) * 0.42),
    );
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

  Widget _buildWorkspacePanel(
    PersonSelectionState selectionState,
    PersonSelectionController selectionController,
    EffectEditorState effectState,
    EffectEditorController effectController,
  ) {
    final activeTool = _activeTool;
    return ColoredBox(
      key: const ValueKey('protection-editor-workspace-panel'),
      color: _canvasColor,
      child: Column(
        children: [
          if (activeTool != null)
            Expanded(
              child: AnimatedSwitcher(
                duration: const Duration(milliseconds: 160),
                child: SingleChildScrollView(
                  key: ValueKey('editor-tool-content-${activeTool.name}'),
                  physics: const BouncingScrollPhysics(),
                  padding:
                      activeTool == _EditorTool.mask ||
                          activeTool == _EditorTool.protect
                      ? const EdgeInsets.symmetric(vertical: 10)
                      : const EdgeInsets.fromLTRB(20, 10, 20, 10),
                  child: _buildActiveTool(
                    selectionState,
                    selectionController,
                    effectState,
                    effectController,
                  ),
                ),
              ),
            ),
          SafeArea(
            top: false,
            child: SizedBox(
              height: _toolNavigationHeight,
              child: _buildToolNavigation(),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildActiveTool(
    PersonSelectionState selectionState,
    PersonSelectionController selectionController,
    EffectEditorState effectState,
    EffectEditorController effectController,
  ) {
    return switch (_activeTool) {
      null => const SizedBox.shrink(),
      _EditorTool.trim => _buildTrimSection(),
      _EditorTool.protect => _buildProtectTool(
        selectionState,
        selectionController,
        effectController,
        effectState,
      ),
      _EditorTool.mask => _buildMaskTool(
        selectionState,
        effectState,
        effectController,
      ),
      _EditorTool.frame => _buildReframeControls(effectState, effectController),
      _EditorTool.adjust => _buildAdjustTool(effectState, effectController),
    };
  }

  Widget _buildToolNavigation() {
    const tools = <(_EditorTool, String, IconData)>[
      (_EditorTool.trim, '剪辑', Icons.content_cut_rounded),
      (_EditorTool.mask, '遮挡', Icons.blur_on_rounded),
      (_EditorTool.frame, '画幅', Icons.crop_rounded),
      (_EditorTool.adjust, '调节', Icons.tune_rounded),
    ];
    return Row(
      children: tools.map((item) {
        final selected = _activeTool == item.$1;
        void toggleTool() {
          final nextTool = selected ? null : item.$1;
          HapticFeedback.selectionClick();
          if (nextTool != _EditorTool.trim) {
            _videoController?.pause();
            _playbackTimer?.cancel();
            _playbackTimer = null;
            _isPlaying = false;
          }
          _scheduleProfilePersist();
          setState(() => _activeTool = nextTool);
        }

        return Expanded(
          child: Semantics(
            key: ValueKey('editor-tool-${item.$1.name}'),
            button: true,
            selected: selected,
            label: item.$2,
            excludeSemantics: true,
            onTap: toggleTool,
            child: InkWell(
              onTap: toggleTool,
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Icon(
                    item.$3,
                    size: 22,
                    color: selected
                        ? AppTheme.of(context).editorSelection
                        : AppTheme.of(context).textMuted,
                  ),
                  const SizedBox(height: 3),
                  Text(
                    item.$2,
                    style: TextStyle(
                      color: selected
                          ? AppTheme.of(context).editorSelection
                          : AppTheme.of(context).textMuted,
                      fontSize: 11,
                      fontWeight: selected ? FontWeight.w700 : FontWeight.w500,
                    ),
                  ),
                ],
              ),
            ),
          ),
        );
      }).toList(),
    );
  }

  Widget _buildAdjustTool(
    EffectEditorState effectState,
    EffectEditorController effectController,
  ) {
    final effects = effectState.effects;
    final hasProtagonist = effectState.project?.hasFollowTarget ?? false;
    final properties = _AdjustProperty.values
        .where(
          (property) =>
              property != _AdjustProperty.legStretch || hasProtagonist,
        )
        .toList(growable: false);
    final activeProperty =
        !hasProtagonist && _activeAdjustProperty == _AdjustProperty.legStretch
        ? _AdjustProperty.opacity
        : _activeAdjustProperty;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        SizedBox(
          height: 76,
          child: ListView.separated(
            scrollDirection: Axis.horizontal,
            physics: const BouncingScrollPhysics(),
            itemCount: properties.length,
            separatorBuilder: (_, _) => const SizedBox(width: 14),
            itemBuilder: (context, index) {
              final prop = properties[index];
              final isSelected = activeProperty == prop;
              return GestureDetector(
                onTap: () {
                  HapticFeedback.selectionClick();
                  setState(() => _activeAdjustProperty = prop);
                },
                behavior: HitTestBehavior.opaque,
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    SizedBox(
                      width: AppTheme.minTouchTarget,
                      height: AppTheme.minTouchTarget,
                      child: Icon(
                        prop.icon,
                        color: isSelected
                            ? AppTheme.of(context).editorSelection
                            : AppTheme.of(context).textMuted,
                        size: 22,
                      ),
                    ),
                    const SizedBox(height: 5),
                    Text(
                      prop.label,
                      style: TextStyle(
                        color: isSelected
                            ? AppTheme.of(context).editorSelection
                            : AppTheme.of(context).textMuted,
                        fontSize: 11,
                        fontWeight: isSelected
                            ? FontWeight.w600
                            : FontWeight.w500,
                      ),
                    ),
                  ],
                ),
              );
            },
          ),
        ),
        const SizedBox(height: 12),
        _buildAdjustSliderSection(
          effects,
          effectController,
          activeProperty: activeProperty,
        ),
      ],
    );
  }

  Widget _buildAdjustSliderSection(
    EffectConfig effects,
    EffectEditorController effectController, {
    required _AdjustProperty activeProperty,
  }) {
    return switch (activeProperty) {
      _AdjustProperty.opacity => _buildYellowSlider(
        label: '遮挡强度',
        value: effects.opacity,
        min: 0.1,
        max: 1.0,
        step: 0.05,
        displayValue: '${(effects.opacity * 100).round()}%',
        onChanged: effectController.updateOpacity,
      ),
      _AdjustProperty.blur => _buildYellowSlider(
        label: effects.fillMode == FillMode.mosaic ? '马赛克颗粒' : '模糊程度',
        value: effects.blurStrength,
        min: 1,
        max: 30,
        step: 1,
        displayValue: '${effects.blurStrength.round()}',
        onChanged: effectController.updateBlurStrength,
      ),
      _AdjustProperty.color => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _buildSectionLabel('遮挡填充颜色', subdued: true),
          const SizedBox(height: 8),
          _buildColorPalette(
            effects.fillColorArgb,
            effectController.updateFillColor,
          ),
        ],
      ),
      _AdjustProperty.border => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _buildYellowSlider(
            label: '描边粗细',
            value: effects.borderWidth,
            min: 0,
            max: 20,
            step: 1,
            displayValue: '${effects.borderWidth.round()} px',
            onChanged: effectController.updateBorderWidth,
          ),
          if (effects.borderWidth > 0) ...[
            const SizedBox(height: 10),
            _buildSectionLabel('描边颜色', subdued: true),
            const SizedBox(height: 6),
            _buildColorPalette(
              effects.borderColorArgb,
              effectController.updateBorderColor,
            ),
          ],
        ],
      ),
      _AdjustProperty.skinWhiten => _buildYellowSlider(
        label: '美肤提亮',
        value: effects.skinWhiten,
        min: 0.0,
        max: 1.0,
        step: 0.05,
        displayValue: '${(effects.skinWhiten * 100).round()}%',
        onChanged: effectController.updateSkinWhiten,
      ),
      _AdjustProperty.legStretch => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  '智能拉腿',
                  style: TextStyle(
                    color: AppTheme.of(context).textPrimary,
                    fontSize: 13,
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ),
              Switch(
                value: effects.legStretchEnabled,
                activeThumbColor: AppTheme.of(context).editorSelection,
                activeTrackColor: AppTheme.of(
                  context,
                ).editorSelection.withAlpha(120),
                onChanged: (val) {
                  effectController.updateLegStretch(
                    enabled: val,
                    stretch: val && effects.legStretch <= 0
                        ? 0.15
                        : effects.legStretch,
                  );
                },
              ),
            ],
          ),
          if (effects.legStretchEnabled)
            _buildYellowSlider(
              label: '拉伸强度',
              value: effects.legStretch,
              min: 0.0,
              max: 0.35,
              step: 0.02,
              displayValue: '+${(effects.legStretch * 100).round()}%',
              onChanged: (val) {
                effectController.updateLegStretch(enabled: true, stretch: val);
              },
            ),
        ],
      ),
    };
  }

  Widget _buildYellowSlider({
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
                style: TextStyle(
                  color: AppTheme.of(context).textPrimary,
                  fontSize: 12.5,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ),
            Text(
              displayValue,
              style: TextStyle(
                color: AppTheme.of(context).editorSelection,
                fontSize: 12.5,
                fontWeight: FontWeight.w700,
                fontFeatures: [FontFeature.tabularFigures()],
              ),
            ),
          ],
        ),
        const SizedBox(height: 4),
        SliderTheme(
          data: SliderThemeData(
            activeTrackColor: AppTheme.of(context).editorSelection,
            inactiveTrackColor: AppTheme.of(context).sliderTrackInactive,
            thumbColor: AppTheme.of(context).editorSelection,
            trackHeight: 5,
            thumbShape: RoundSliderThumbShape(enabledThumbRadius: 8),
          ),
          child: Slider(
            value: value.clamp(min, max),
            min: min,
            max: max,
            onChanged: onChanged,
          ),
        ),
      ],
    );
  }

  Widget _buildProtectTool(
    PersonSelectionState selectionState,
    PersonSelectionController selectionController,
    EffectEditorController effectController,
    EffectEditorState effectState,
  ) {
    return _buildMaskTool(selectionState, effectState, effectController);
  }

  Widget _buildMaskTool(
    PersonSelectionState selectionState,
    EffectEditorState effectState,
    EffectEditorController effectController,
  ) {
    final effects = effectState.effects;
    final faceMode = selectionState.privacyMode == ProjectPrivacyMode.faceOnly;
    final activeMode = effects.faceStickerEnabled
        ? FillMode.sticker
        : effects.fillMode;
    return Column(
      key: const ValueKey('protection-editor-tool-mask'),
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _buildPrivacyModeSwitch(selectionState, effectController),
        const SizedBox(height: 8),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 20),
          child: Row(
            children: [
              Expanded(child: _buildSectionLabel('遮挡样式')),
              if (faceMode && effects.faceStickerEnabled)
                TextButton.icon(
                  key: const ValueKey('protection-sticker-options'),
                  onPressed: () => _showStickerOptions(effectController),
                  icon: const Icon(Icons.expand_more_rounded, size: 18),
                  label: Text(_stickerName(effects.stickerAssetId)),
                ),
              IconButton(
                tooltip: '恢复默认样式',
                onPressed: () =>
                    _resetCurrentEffect(selectionState, effectController),
                icon: const Icon(Icons.restart_alt_rounded, size: 20),
              ),
            ],
          ),
        ),
        const SizedBox(height: 6),
        _buildModeChips(activeMode, effectController, faceMode: faceMode),
      ],
    );
  }

  Widget _buildTrimSection() {
    return Column(
      key: const ValueKey('trim-range-section'),
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [_buildPlaybackBar()],
    );
  }

  Widget _buildReframeControls(
    EffectEditorState state,
    EffectEditorController controller,
  ) {
    final project = state.project;
    final follow = project?.follow ?? const FollowConfig();
    final isVertical = follow.enabled || _selectingFollowTarget;

    return Column(
      key: const ValueKey('reframe-controls-section'),
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _buildSectionLabel('比例'),
        const SizedBox(height: 10),
        _buildAspectRatioCards(isVertical, (setVer) {
          if (setVer) {
            setState(() => _selectingFollowTarget = true);
            controller.showSourceFrame(true);
          } else {
            setState(() => _selectingFollowTarget = false);
            controller.updateFollowConfig(enabled: false);
          }
          _scheduleProfilePersist();
        }),
        if (_selectingFollowTarget) ...[
          const SizedBox(height: 14),
          Text(
            '轻触画面选择主角；若主角已被保护，会自动取消其保护。',
            style: TextStyle(
              fontSize: 12,
              color: AppTheme.of(context).textSecondary,
            ),
          ),
        ] else if (follow.enabled) ...[
          const SizedBox(height: 14),
          Text(
            '主角：人物 ${(follow.targetPersonId ?? 0) + 1} · 自动平滑跟随',
            style: TextStyle(
              fontSize: 12,
              color: AppTheme.of(context).textSecondary,
            ),
          ),
          const SizedBox(height: 6),
          Wrap(
            spacing: 8,
            children: [
              TextButton.icon(
                key: const ValueKey('reframe-change-subject'),
                onPressed: () {
                  setState(() => _selectingFollowTarget = true);
                  controller.showSourceFrame(true);
                  _scheduleProfilePersist();
                },
                icon: const Icon(Icons.person_search_outlined, size: 18),
                label: const Text('更换主角'),
              ),
              TextButton.icon(
                key: const ValueKey('reframe-source-toggle'),
                onPressed: () {
                  final showSource = !state.showSourcePreview;
                  controller.showSourceFrame(showSource);
                },
                icon: Icon(
                  state.showSourcePreview
                      ? Icons.crop_portrait
                      : Icons.people_outline,
                  size: 18,
                ),
                label: Text(state.showSourcePreview ? '裁切预览' : '原始画面选保护对象'),
              ),
            ],
          ),
          const SizedBox(height: 4),
          Text(
            '预览为首帧，导出时跟随主角',
            style: TextStyle(
              fontSize: 12,
              color: AppTheme.of(context).textSecondary,
            ),
          ),
        ],
      ],
    );
  }

  Widget _buildAspectRatioCards(bool isVertical, ValueChanged<bool> onChanged) {
    const ratios = [('原始', false), ('9:16', true)];

    return SizedBox(
      key: const ValueKey('reframe-mode'),
      height: 64,
      child: Row(
        children: [
          for (var i = 0; i < ratios.length; i++) ...[
            if (i > 0) const SizedBox(width: 10),
            _buildRatioCard(
              label: ratios[i].$1,
              isVerticalRatio: ratios[i].$2,
              isSelected: ratios[i].$2 ? isVertical : !isVertical,
              onTap: () {
                HapticFeedback.selectionClick();
                onChanged(ratios[i].$2);
              },
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildRatioCard({
    required String label,
    required bool isVerticalRatio,
    required bool isSelected,
    required VoidCallback onTap,
  }) {
    return GestureDetector(
      onTap: onTap,
      behavior: HitTestBehavior.opaque,
      child: SizedBox(
        width: 68,
        height: 64,
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Container(
              width: 18,
              height: isVerticalRatio ? 22 : 14,
              decoration: BoxDecoration(
                border: Border.all(
                  color: isSelected
                      ? AppTheme.of(context).editorSelection
                      : AppTheme.of(context).textMuted,
                  width: 1.3,
                ),
                borderRadius: BorderRadius.circular(2),
              ),
            ),
            const SizedBox(height: 6),
            Text(
              label,
              style: TextStyle(
                color: isSelected
                    ? AppTheme.of(context).editorSelection
                    : AppTheme.of(context).textMuted,
                fontSize: 11.5,
                fontWeight: isSelected ? FontWeight.w700 : FontWeight.w500,
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildSectionLabel(String text, {bool subdued = false}) {
    return Text(
      text,
      style: TextStyle(
        color: subdued
            ? AppTheme.of(context).textSecondary
            : AppTheme.of(context).textPrimary,
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
    final options = <(ProjectPrivacyMode, String, IconData, ValueKey<String>)>[
      (
        ProjectPrivacyMode.fullBody,
        '全身保护',
        Icons.accessibility_new_rounded,
        const ValueKey('privacy-mode-full-body'),
      ),
      (
        ProjectPrivacyMode.faceOnly,
        '人脸保护',
        Icons.face_retouching_off_rounded,
        const ValueKey('privacy-mode-face-only'),
      ),
    ];

    return SizedBox(
      height: AppTheme.minTouchTarget,
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          for (var i = 0; i < options.length; i++) ...[
            Expanded(
              child: _PrivacySlot(
                key: options[i].$4,
                label: options[i].$2,
                icon: options[i].$3,
                selected: options[i].$1 == ProjectPrivacyMode.faceOnly
                    ? isFaceOnly
                    : !isFaceOnly,
                onTap: () =>
                    _switchPrivacyMode(options[i].$1, effectController),
              ),
            ),
          ],
        ],
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
      height: 64,
      child: Row(
        children: modes.map((item) {
          final selected = current == item.$1;
          void selectStyle() {
            HapticFeedback.selectionClick();
            controller.updateProtectionStyle(item.$1);
          }

          return Expanded(
            child: Semantics(
              button: true,
              selected: selected,
              label: item.$2,
              excludeSemantics: true,
              onTap: selectStyle,
              child: GestureDetector(
                key: ValueKey('protection-style-${item.$1.name}'),
                behavior: HitTestBehavior.opaque,
                onTap: selectStyle,
                child: Padding(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 6,
                    vertical: 7,
                  ),
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      Icon(
                        item.$3,
                        size: 21,
                        color: selected
                            ? AppTheme.of(context).editorSelection
                            : AppTheme.of(context).textMuted,
                      ),
                      const SizedBox(height: 4),
                      Text(
                        item.$2,
                        style: TextStyle(
                          color: selected
                              ? AppTheme.of(context).editorSelection
                              : AppTheme.of(context).textMuted,
                          fontSize: 12,
                          fontWeight: selected
                              ? FontWeight.w600
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
    );
  }

  String _stickerName(String? assetId) => switch (assetId) {
    null || 'builtin:sunglasses' => '酷脸贴纸',
    'builtin:blush' => '微笑贴纸',
    'builtin:panda' => '熊猫贴纸',
    'builtin:cat' => '猫咪贴纸',
    'builtin:bear' => '小熊贴纸',
    _ => '自定义贴纸',
  };

  Future<void> _showStickerOptions(EffectEditorController controller) async {
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      backgroundColor: Colors.transparent,
      builder: (sheetContext) => StatefulBuilder(
        builder: (context, setSheetState) => Consumer(
          builder: (context, ref, _) {
            final effects = ref.watch(effectEditorControllerProvider).effects;
            return Material(
              color: AppTheme.of(context).flowSurface,
              borderRadius: const BorderRadius.vertical(
                top: Radius.circular(24),
              ),
              child: SafeArea(
                top: false,
                child: SingleChildScrollView(
                  padding: const EdgeInsets.fromLTRB(20, 8, 20, 20),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Row(
                        children: [
                          Expanded(child: _buildSectionLabel('贴纸设置')),
                          IconButton(
                            tooltip: '关闭贴纸设置',
                            onPressed: () => Navigator.of(sheetContext).pop(),
                            icon: const Icon(Icons.close_rounded),
                          ),
                        ],
                      ),
                      const SizedBox(height: 8),
                      _buildStickerPicker(
                        effects,
                        controller,
                        onImport: () async {
                          final pending = _importCustomSticker(controller);
                          setSheetState(() {});
                          await pending;
                          if (sheetContext.mounted) setSheetState(() {});
                        },
                      ),
                    ],
                  ),
                ),
              ),
            );
          },
        ),
      ),
    );
  }

  Widget _buildStickerPicker(
    EffectConfig effects,
    EffectEditorController controller, {
    VoidCallback? onImport,
  }) {
    const stickers = <(String, String, IconData, Color)>[
      ('builtin:sunglasses', '酷脸', Icons.dark_mode_rounded, Color(0xFFFFD84D)),
      ('builtin:blush', '微笑', Icons.favorite_rounded, Color(0xFFFFA3AA)),
      ('builtin:panda', '熊猫', Icons.circle_rounded, Color(0xFFF5F5F2)),
      ('builtin:cat', '猫咪', Icons.pets_rounded, Color(0xFFFFD7A1)),
      ('builtin:bear', '小熊', Icons.pets_outlined, Color(0xFFC58B62)),
    ];
    final current = effects.stickerAssetId ?? 'builtin:sunglasses';
    final customFile = !current.startsWith('builtin:') && current != 'disabled'
        ? File(current)
        : null;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        SizedBox(
          height: 68,
          child: ListView(
            scrollDirection: Axis.horizontal,
            physics: const BouncingScrollPhysics(),
            children: [
              Semantics(
                button: true,
                label: '导入自定义贴纸',
                child: GestureDetector(
                  key: const ValueKey('sticker-import'),
                  onTap: _stickerImporting
                      ? null
                      : onImport ?? () => _importCustomSticker(controller),
                  child: Container(
                    width: 58,
                    decoration: BoxDecoration(
                      color: AppTheme.of(context).surfaceElevated,
                      borderRadius: BorderRadius.circular(18),
                      border: Border.all(
                        color: AppTheme.of(context).surfaceBorder,
                      ),
                    ),
                    child: Center(
                      child: _stickerImporting
                          ? SizedBox(
                              width: 20,
                              height: 20,
                              child: CircularProgressIndicator(
                                strokeWidth: 2,
                                color: AppTheme.of(context).editorSelection,
                              ),
                            )
                          : Icon(
                              Icons.add_photo_alternate_outlined,
                              color: AppTheme.of(context).editorSelection,
                              size: 25,
                            ),
                    ),
                  ),
                ),
              ),
              if (customFile != null) ...[
                const SizedBox(width: 10),
                Semantics(
                  button: true,
                  selected: true,
                  label: '自定义贴纸，已选择',
                  child: Container(
                    key: const ValueKey('sticker-custom-current'),
                    width: 58,
                    decoration: BoxDecoration(
                      color: AppTheme.of(context).surfaceHigh,
                      borderRadius: BorderRadius.circular(18),
                      border: Border.all(
                        color: AppTheme.of(context).editorSelection,
                        width: 2,
                      ),
                    ),
                    clipBehavior: Clip.antiAlias,
                    child: Stack(
                      fit: StackFit.expand,
                      children: [
                        Image.file(
                          customFile,
                          fit: BoxFit.cover,
                          errorBuilder: (_, _, _) => Icon(
                            Icons.broken_image_outlined,
                            color: AppTheme.of(context).textSecondary,
                          ),
                        ),
                        Positioned(
                          right: 4,
                          bottom: 4,
                          child: Icon(
                            Icons.check_circle_rounded,
                            color: AppTheme.of(context).editorSelection,
                            size: 18,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ],
              const SizedBox(width: 10),
              for (var index = 0; index < stickers.length; index++) ...[
                if (index > 0) const SizedBox(width: 10),
                Builder(
                  builder: (context) {
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
                              color: selected
                                  ? AppTheme.of(context).editorSelection
                                  : AppTheme.of(context).surfaceBorder,
                              width: selected ? 2 : 1,
                            ),
                          ),
                          child: Stack(
                            alignment: Alignment.center,
                            children: [
                              Icon(
                                item.$3,
                                color: AppTheme.of(context).textPrimary,
                                size: 24,
                              ),
                              if (selected)
                                Positioned(
                                  right: 4,
                                  bottom: 4,
                                  child: Icon(
                                    Icons.check_circle_rounded,
                                    color: AppTheme.of(context).editorSelection,
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
              ],
            ],
          ),
        ),
        const SizedBox(height: 12),
        _buildYellowSlider(
          label: '贴纸大小',
          value: effects.stickerScale,
          min: 0.5,
          max: 3.0,
          step: 0.1,
          displayValue: '${effects.stickerScale.toStringAsFixed(1)}×',
          onChanged: controller.updateStickerScale,
        ),
      ],
    );
  }

  Future<void> _importCustomSticker(EffectEditorController controller) async {
    if (_stickerImporting) return;
    HapticFeedback.selectionClick();
    setState(() => _stickerImporting = true);
    try {
      final path = await ref.read(stickerAssetStoreProvider).pickAndImport();
      if (!mounted || path == null) return;
      controller.updateStickerAsset(path);
    } on FormatException catch (error) {
      if (!mounted) return;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(error.message)));
    } catch (_) {
      if (!mounted) return;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('导入贴纸失败，请换一张图片重试')));
    } finally {
      if (mounted) {
        setState(() => _stickerImporting = false);
      }
    }
  }

  Widget _buildColorPalette(int currentArgb, ValueChanged<int> onSelect) {
    const colors = <(int, String)>[
      (0xFF000000, '黑色'),
      (0xFFF5A623, '琥珀金'),
      (0xFFFF5E5B, '珊瑚红'),
      (0xFFFF9EAA, '粉色'),
      (0xFF7D9CFF, '蓝紫色'),
      (0xFF71C991, '绿色'),
      (0xFFFFFFFF, '白色'),
    ];

    return Wrap(
      spacing: 4,
      runSpacing: 4,
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
              width: 44,
              height: 44,
              child: Center(
                child: AnimatedContainer(
                  duration: const Duration(milliseconds: 160),
                  width: 36,
                  height: 36,
                  decoration: BoxDecoration(
                    color: Color(argb),
                    shape: BoxShape.circle,
                    border: Border.all(
                      color: selected
                          ? AppTheme.of(context).editorSelection
                          : AppTheme.of(context).surfaceBorder,
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

  Future<void> _loadRecoveryPreview() async {
    final selection = ref.read(personSelectionControllerProvider);
    final cacheId = selection.analysisCacheId;
    if (selection.status != PersonSelectionStatus.ready ||
        cacheId == null ||
        cacheId.isEmpty ||
        selection.persons.isNotEmpty) {
      return;
    }
    try {
      final preview = await ref
          .read(nativeRepositoryProvider)
          .getPreviewFrame(
            analysisCacheId: cacheId,
            timestampMs: _trimStartMs,
            selectedPersonIds: const [],
            faceOnlyPersonIds: const [],
            effects: const EffectConfig(),
          );
      if (!mounted ||
          ref.read(personSelectionControllerProvider).analysisCacheId !=
              cacheId ||
          preview.thumbnailPath.isEmpty) {
        return;
      }
      setState(() => _recoveryPreviewPath = preview.thumbnailPath);
    } catch (_) {
      // Recovery actions remain available when a source frame is unavailable.
    }
  }

  Widget? _buildRecoveryPreview() {
    final path = _recoveryPreviewPath;
    if (path != null) {
      return Semantics(
        label: '原片起始画面',
        image: true,
        child: SizedBox(
          height: 144,
          child: Image.file(
            File(path),
            fit: BoxFit.contain,
            errorBuilder: (_, _, _) => const SizedBox.shrink(),
          ),
        ),
      );
    }
    final controller = _videoController;
    if (controller == null || !_videoInitialized) return null;
    return Semantics(
      label: '原片起始画面',
      image: true,
      child: SizedBox(
        height: 144,
        child: Center(
          child: AspectRatio(
            aspectRatio: controller.value.aspectRatio,
            child: ClipRRect(
              borderRadius: BorderRadius.circular(12),
              child: VideoPlayer(controller),
            ),
          ),
        ),
      ),
    );
  }

  Future<void> _replaceVideo() async {
    if (_replacingVideo) return;
    setState(() => _replacingVideo = true);
    final controller = ref.read(importVideoControllerProvider.notifier);
    final selected = await controller.pickAndProbeVideo();
    if (!mounted) return;
    setState(() => _replacingVideo = false);
    if (!selected) {
      if (ref.read(importVideoControllerProvider).status ==
          VideoImportStatus.error) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(const SnackBar(content: Text('无法读取所选视频，请换一个重试')));
      }
      return;
    }
    final project = controller.createProject();
    if (project == null) return;
    context.pushReplacement(
      '/protection_editor',
      extra: ProtectionEditorArgs(project: project),
    );
  }

  Future<void> _reanalyze(PersonSelectionController selectionController) async {
    if (_trimStartMs != _committedTrimStartMs) {
      await _applyTrimChange();
      return;
    }
    if (!_profileReady) {
      await _initializeEditor(forceAnalyze: true);
      return;
    }
    final project = ref.read(personSelectionControllerProvider).project;
    final privacyMode = ref.read(personSelectionControllerProvider).privacyMode;
    await selectionController.analyzeProject(
      project ?? widget.project,
      selectionPreviewEnabled: false,
      privacyMode: privacyMode,
    );
    if (!mounted) return;
    final configured = selectionController.buildConfiguredProject();
    if (configured == null) return;
    final state = ref.read(personSelectionControllerProvider);
    if (state.persons.isEmpty) {
      await _loadRecoveryPreview();
      return;
    }
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
    final currentEffects = ref.read(effectEditorControllerProvider).effects;
    _captureActiveDraft();

    final selectionController = ref.read(
      personSelectionControllerProvider.notifier,
    );
    selectionController.setProjectPrivacyMode(mode);
    final configured = selectionController.buildConfiguredProject();
    if (configured == null) return;

    final nextBase = mode == ProjectPrivacyMode.faceOnly
        ? (_faceOnlyDraft ??= _defaultFaceDraft(configured.effects))
        : (_fullBodyDraft ??= _defaultFullBodyDraft(configured.effects));
    final nextEffects = nextBase.copyWith(
      legStretchEnabled: currentEffects.legStretchEnabled,
      legStretch: currentEffects.legStretch,
      legZoneTop: currentEffects.legZoneTop,
      legZoneBottom: currentEffects.legZoneBottom,
    );
    if (mode == ProjectPrivacyMode.faceOnly) {
      _faceOnlyDraft = nextEffects;
    } else {
      _fullBodyDraft = nextEffects;
    }
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
        _trimApplying ||
        _selectionNeedsReview ||
        _selectingFollowTarget ||
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

class _EditorStatus extends StatelessWidget {
  final IconData icon;
  final String title;
  final String subtitle;
  final bool loading;
  final String? actionLabel;
  final VoidCallback? onAction;
  final String? secondaryActionLabel;
  final VoidCallback? onSecondaryAction;
  final Widget? preview;

  const _EditorStatus({
    required this.icon,
    required this.title,
    required this.subtitle,
    this.loading = false,
    this.actionLabel,
    this.onAction,
    this.secondaryActionLabel,
    this.onSecondaryAction,
    this.preview,
  });

  @override
  Widget build(BuildContext context) {
    return Center(
      child: SingleChildScrollView(
        padding: const EdgeInsets.symmetric(horizontal: 28, vertical: 20),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            if (preview != null) ...[
              preview!,
              const SizedBox(height: 18),
            ] else if (loading)
              const SizedBox(
                width: 40,
                height: 40,
                child: CircularProgressIndicator(strokeWidth: 2.5),
              )
            else
              Icon(icon, size: 42, color: AppTheme.of(context).editorSelection),
            const SizedBox(height: 18),
            Text(
              title,
              textAlign: TextAlign.center,
              style: TextStyle(
                color: AppTheme.of(context).textPrimary,
                fontSize: 17,
                fontWeight: FontWeight.w700,
              ),
            ),
            const SizedBox(height: 7),
            Text(
              subtitle,
              textAlign: TextAlign.center,
              style: TextStyle(
                color: AppTheme.of(context).textSecondary,
                fontSize: 13,
              ),
            ),
            if (actionLabel != null) ...[
              const SizedBox(height: 20),
              FilledButton(
                style: FilledButton.styleFrom(
                  minimumSize: const Size(0, AppTheme.minTouchTarget),
                ),
                onPressed: onAction,
                child: Text(actionLabel!),
              ),
            ],
            if (secondaryActionLabel != null) ...[
              const SizedBox(height: 4),
              TextButton(
                style: TextButton.styleFrom(
                  minimumSize: const Size(0, AppTheme.minTouchTarget),
                ),
                onPressed: onSecondaryAction,
                child: Text(secondaryActionLabel!),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _PrivacySlot extends StatelessWidget {
  final String label;
  final IconData icon;
  final bool selected;
  final VoidCallback onTap;

  const _PrivacySlot({
    super.key,
    required this.label,
    required this.icon,
    required this.selected,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    void selectScope() {
      HapticFeedback.selectionClick();
      onTap();
    }

    return Semantics(
      button: true,
      selected: selected,
      label: label,
      excludeSemantics: true,
      onTap: selectScope,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: selectScope,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 6),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(
                icon,
                size: 17,
                color: selected
                    ? AppTheme.of(context).editorSelection
                    : AppTheme.of(context).textMuted,
              ),
              const SizedBox(width: 6),
              Text(
                label,
                style: TextStyle(
                  color: selected
                      ? AppTheme.of(context).editorSelection
                      : AppTheme.of(context).textMuted,
                  fontSize: 12,
                  fontWeight: selected ? FontWeight.w600 : FontWeight.w500,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _IntegratedTrimHandle extends StatelessWidget {
  final bool isLeft;
  const _IntegratedTrimHandle({super.key, required this.isLeft});

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 10,
      height: 14,
      decoration: BoxDecoration(
        color: AppTheme.of(context).editorSelection,
        borderRadius: BorderRadius.horizontal(
          left: isLeft ? const Radius.circular(3) : Radius.zero,
          right: !isLeft ? const Radius.circular(3) : Radius.zero,
        ),
      ),
      child: Center(
        child: Container(
          width: 1.5,
          height: 8,
          decoration: BoxDecoration(
            color: Colors.black87,
            borderRadius: BorderRadius.circular(1),
          ),
        ),
      ),
    );
  }
}
