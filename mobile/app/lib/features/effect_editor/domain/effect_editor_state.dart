import 'package:dance_domain/dance_domain.dart';

class EffectEditorState {
  final DanceProject? project;
  final EffectConfig effects;
  final String? previewThumbnailPath;

  const EffectEditorState({
    this.project,
    this.effects = const EffectConfig(),
    this.previewThumbnailPath,
  });

  EffectEditorState copyWith({
    DanceProject? project,
    EffectConfig? effects,
    String? previewThumbnailPath,
  }) {
    return EffectEditorState(
      project: project ?? this.project,
      effects: effects ?? this.effects,
      previewThumbnailPath: previewThumbnailPath ?? this.previewThumbnailPath,
    );
  }
}
