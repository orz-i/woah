import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:dance_domain/dance_domain.dart';
import '../domain/effect_editor_state.dart';

final effectEditorControllerProvider = StateNotifierProvider.autoDispose<
    EffectEditorController, EffectEditorState>((ref) {
  return EffectEditorController();
});

class EffectEditorController extends StateNotifier<EffectEditorState> {
  EffectEditorController() : super(const EffectEditorState());

  void init(DanceProject project) {
    state = state.copyWith(
      project: project,
      effects: project.effects,
      previewThumbnailPath: project.persons.isNotEmpty
          ? project.persons.first.thumbnailPath
          : null,
    );
  }

  void updateFillMode(FillMode mode) {
    state = state.copyWith(
      effects: state.effects.copyWith(fillMode: mode),
    );
  }

  void updateOpacity(double opacity) {
    state = state.copyWith(
      effects: state.effects.copyWith(opacity: opacity),
    );
  }

  void updateFillColor(int argb) {
    state = state.copyWith(
      effects: state.effects.copyWith(fillColorArgb: argb),
    );
  }

  void updateBorderColor(int argb) {
    state = state.copyWith(
      effects: state.effects.copyWith(borderColorArgb: argb),
    );
  }

  void updateBorderWidth(double width) {
    state = state.copyWith(
      effects: state.effects.copyWith(borderWidth: width),
    );
  }

  void updateBlurStrength(double strength) {
    state = state.copyWith(
      effects: state.effects.copyWith(blurStrength: strength),
    );
  }

  void updateSkinWhiten(double strength) {
    state = state.copyWith(
      effects: state.effects.copyWith(skinWhiten: strength),
    );
  }

  void updateLegStretch({required bool enabled, double stretch = 0.15}) {
    state = state.copyWith(
      effects: state.effects.copyWith(
        legStretchEnabled: enabled,
        legStretch: stretch,
      ),
    );
  }

  void updateFollowConfig({
    bool? enabled,
    int? targetPersonId,
    double? zoom,
    double? smoothFactor,
  }) {
    final proj = state.project;
    if (proj == null) return;

    final currentFollow = proj.follow;
    final updatedFollow = currentFollow.copyWith(
      enabled: enabled ?? currentFollow.enabled,
      targetPersonId: targetPersonId ?? currentFollow.targetPersonId,
      zoom: zoom ?? currentFollow.zoom,
      smoothFactor: smoothFactor ?? currentFollow.smoothFactor,
    );

    state = state.copyWith(
      project: proj.copyWith(follow: updatedFollow),
    );
  }

  DanceProject? buildConfiguredProject() {
    final proj = state.project;
    if (proj == null) return null;

    return proj.copyWith(
      effects: state.effects,
      updatedAt: DateTime.now(),
    );
  }
}
