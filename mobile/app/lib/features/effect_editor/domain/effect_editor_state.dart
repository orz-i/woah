import 'package:dance_domain/dance_domain.dart';

class EffectEditorState {
  final DanceProject? project;
  final EffectConfig effects;
  final String? previewThumbnailPath;
  final String? previewPath;
  final bool previewLoading;
  final String? previewError;
  final int previewRequestId;

  const EffectEditorState({
    this.project,
    this.effects = const EffectConfig(),
    this.previewThumbnailPath,
    this.previewPath,
    this.previewLoading = false,
    this.previewError,
    this.previewRequestId = 0,
  });

  EffectEditorState copyWith({
    DanceProject? project,
    EffectConfig? effects,
    String? previewThumbnailPath,
    String? previewPath,
    bool? previewLoading,
    String? previewError,
    int? previewRequestId,
  }) {
    return EffectEditorState(
      project: project ?? this.project,
      effects: effects ?? this.effects,
      previewThumbnailPath: previewThumbnailPath ?? this.previewThumbnailPath,
      previewPath: previewPath ?? this.previewPath,
      previewLoading: previewLoading ?? this.previewLoading,
      previewError: previewError ?? this.previewError,
      previewRequestId: previewRequestId ?? this.previewRequestId,
    );
  }
}
