import 'package:dance_domain/dance_domain.dart';

class EffectEditorState {
  final DanceProject? project;
  final EffectConfig effects;
  final String? previewThumbnailPath;
  final String? previewPath;
  final bool previewLoading;
  final String? previewError;
  final int previewRequestId;
  final bool showSourcePreview;

  const EffectEditorState({
    this.project,
    this.effects = const EffectConfig(),
    this.previewThumbnailPath,
    this.previewPath,
    this.previewLoading = false,
    this.previewError,
    this.previewRequestId = 0,
    this.showSourcePreview = false,
  });

  EffectEditorState copyWith({
    DanceProject? project,
    EffectConfig? effects,
    String? previewThumbnailPath,
    String? previewPath,
    bool? previewLoading,
    String? previewError,
    int? previewRequestId,
    bool? showSourcePreview,
    bool clearPreview = false,
    bool clearPreviewError = false,
  }) {
    return EffectEditorState(
      project: project ?? this.project,
      effects: effects ?? this.effects,
      previewThumbnailPath: clearPreview
          ? null
          : previewThumbnailPath ?? this.previewThumbnailPath,
      previewPath: clearPreview ? null : previewPath ?? this.previewPath,
      previewLoading: previewLoading ?? this.previewLoading,
      previewError: clearPreviewError
          ? null
          : previewError ?? this.previewError,
      previewRequestId: previewRequestId ?? this.previewRequestId,
      showSourcePreview: showSourcePreview ?? this.showSourcePreview,
    );
  }
}
