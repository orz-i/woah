import 'dart:async';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:dance_domain/dance_domain.dart';
import '../../../repositories/native_processing_repository.dart';
import '../domain/effect_editor_state.dart';

final effectEditorControllerProvider =
    StateNotifierProvider.autoDispose<
      EffectEditorController,
      EffectEditorState
    >((ref) {
      final repository = ref.watch(nativeRepositoryProvider);
      return EffectEditorController(repository: repository);
    });

class EffectEditorController extends StateNotifier<EffectEditorState> {
  final NativeProcessingRepository? _repository;
  Timer? _debounceTimer;
  int _nextRequestId = 0;

  EffectEditorController({NativeProcessingRepository? repository})
    // ignore: prefer_initializing_formals
    : _repository = repository,
      super(const EffectEditorState());

  void init(DanceProject project, {String? initialPreviewPath}) {
    _debounceTimer?.cancel();
    ++_nextRequestId;
    state = EffectEditorState(
      project: project,
      effects: project.effects,
      previewThumbnailPath: initialPreviewPath,
    );
    _requestPreview(debounce: false);
  }

  void updateEditingContext({
    required DanceProject project,
    EffectConfig? effects,
    bool debounce = false,
  }) {
    final activeEffects = effects ?? state.effects;
    state = state.copyWith(
      project: project.copyWith(
        effects: activeEffects,
        follow: state.project?.id == project.id
            ? state.project!.follow
            : project.follow,
      ),
      effects: activeEffects,
    );
    _requestPreview(debounce: debounce);
  }

  void updateFillMode(FillMode mode) {
    state = state.copyWith(effects: state.effects.copyWith(fillMode: mode));
    _requestPreview(debounce: true);
  }

  void updateProtectionStyle(FillMode mode) {
    final sticker = mode == FillMode.sticker;
    final currentAsset = state.effects.stickerAssetId;
    state = state.copyWith(
      effects: state.effects.copyWith(
        fillMode: mode,
        faceStickerEnabled: sticker,
        stickerAssetId: sticker
            ? ((currentAsset == null || currentAsset == 'disabled')
                  ? 'builtin:sunglasses'
                  : currentAsset)
            : 'disabled',
      ),
    );
    _requestPreview(debounce: true);
  }

  void updateStickerAsset(String assetId) {
    state = state.copyWith(
      effects: state.effects.copyWith(
        fillMode: FillMode.sticker,
        faceStickerEnabled: true,
        stickerAssetId: assetId,
      ),
    );
    _requestPreview(debounce: true);
  }

  void updateStickerScale(double scale) {
    state = state.copyWith(
      effects: state.effects.copyWith(stickerScale: scale),
    );
    _requestPreview(debounce: true);
  }

  void updateOpacity(double opacity) {
    state = state.copyWith(effects: state.effects.copyWith(opacity: opacity));
    _requestPreview(debounce: true);
  }

  void updateFillColor(int argb) {
    state = state.copyWith(
      effects: state.effects.copyWith(fillColorArgb: argb),
    );
    _requestPreview(debounce: true);
  }

  void updateBorderColor(int argb) {
    state = state.copyWith(
      effects: state.effects.copyWith(borderColorArgb: argb),
    );
    _requestPreview(debounce: true);
  }

  void updateBorderWidth(double width) {
    state = state.copyWith(effects: state.effects.copyWith(borderWidth: width));
    _requestPreview(debounce: true);
  }

  void updateBlurStrength(double strength) {
    state = state.copyWith(
      effects: state.effects.copyWith(blurStrength: strength),
    );
    _requestPreview(debounce: true);
  }

  void updateSkinWhiten(double strength) {
    state = state.copyWith(
      effects: state.effects.copyWith(skinWhiten: strength),
    );
    _requestPreview(debounce: true);
  }

  void updateLegStretch({required bool enabled, double stretch = 0.15}) {
    state = state.copyWith(
      effects: state.effects.copyWith(
        legStretchEnabled: enabled,
        legStretch: stretch,
      ),
    );
    _requestPreview(debounce: true);
  }

  void updateFollowConfig({
    bool? enabled,
    int? targetPersonId,
    double? zoom,
    double? smoothFactor,
    double? outputAspectRatio,
  }) {
    final proj = state.project;
    if (proj == null) return;

    final currentFollow = proj.follow;
    final requestedEnabled = enabled ?? currentFollow.enabled;
    final resolvedTargetId = targetPersonId ?? currentFollow.targetPersonId;
    if (requestedEnabled &&
        (resolvedTargetId == null ||
            !proj.persons.any((person) => person.id == resolvedTargetId))) {
      return;
    }

    final updatedFollow = currentFollow.copyWith(
      enabled: requestedEnabled,
      targetPersonId: resolvedTargetId,
      zoom: zoom ?? currentFollow.zoom,
      smoothFactor: smoothFactor ?? currentFollow.smoothFactor,
      outputAspectRatio: outputAspectRatio,
    );

    state = state.copyWith(
      project: proj.copyWith(follow: updatedFollow),
      showSourcePreview: false,
      clearPreview: true,
    );
    _requestPreview(debounce: false);
  }

  /// Source-space hit targets must never be overlaid on a cropped image.
  void showSourceFrame(bool enabled) {
    if (state.showSourcePreview == enabled) return;
    state = state.copyWith(showSourcePreview: enabled, clearPreview: true);
    _requestPreview(debounce: false);
  }

  void refreshPreview() {
    _requestPreview(debounce: false);
  }

  void _requestPreview({bool debounce = true}) {
    final repo = _repository;
    if (repo == null) return;
    final project = state.project;
    if (project == null) return;
    final cacheId = project.analysisCacheId;
    if (cacheId == null || cacheId.isEmpty) return;

    _debounceTimer?.cancel();
    // Invalidate immediately, including while the next request is debounced.
    final currentRequestId = ++_nextRequestId;
    state = state.copyWith(previewRequestId: currentRequestId);

    void executeRequest() async {
      if (!mounted) return;
      state = state.copyWith(
        previewLoading: true,
        clearPreviewError: true,
        previewRequestId: currentRequestId,
      );

      try {
        final currentProj = state.project ?? project;
        // Keep preview on the stable first frame of the selected subclip to
        // avoid identity instability while respecting the temporal trim.
        final result = await repo.getPreviewFrame(
          analysisCacheId: cacheId,
          timestampMs: currentProj.trimStartMs,
          selectedPersonIds: currentProj.selectedPersonIds.toList(),
          faceOnlyPersonIds: currentProj.faceOnlyPersonIds.toList(),
          effects: state.effects,
          follow: state.showSourcePreview
              ? currentProj.follow.copyWith(enabled: false)
              : currentProj.follow,
          // Effect editing uses the same tight full-body contour as selection
          // and final export so the rendered shape does not change at handoff.
          tightMaskPreview: true,
        );

        if (!mounted) return;

        // Discard outdated responses
        if (state.previewRequestId == currentRequestId) {
          state = state.copyWith(
            previewPath: result.thumbnailPath,
            previewLoading: false,
            clearPreviewError: true,
          );
        }
      } catch (e) {
        if (!mounted) return;
        if (state.previewRequestId == currentRequestId) {
          state = state.copyWith(
            previewLoading: false,
            previewError: e.toString(),
          );
        }
      }
    }

    if (debounce) {
      _debounceTimer = Timer(const Duration(milliseconds: 200), executeRequest);
    } else {
      executeRequest();
    }
  }

  DanceProject? buildConfiguredProject() {
    final proj = state.project;
    if (proj == null) return null;

    return proj.copyWith(effects: state.effects, updatedAt: DateTime.now());
  }

  @override
  void dispose() {
    _debounceTimer?.cancel();
    super.dispose();
  }
}
