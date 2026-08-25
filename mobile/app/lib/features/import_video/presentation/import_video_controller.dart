import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:file_picker/file_picker.dart';
import 'package:dance_domain/dance_domain.dart';
import '../../../core/logging/app_logger.dart';
import '../../../repositories/native_processing_repository.dart';
import '../domain/video_import_state.dart';

final importVideoControllerProvider =
    StateNotifierProvider<ImportVideoController, VideoImportState>((ref) {
  final repo = ref.watch(nativeRepositoryProvider);
  return ImportVideoController(repo);
});

class ImportVideoController extends StateNotifier<VideoImportState> {
  final NativeProcessingRepository _repository;

  ImportVideoController(this._repository) : super(const VideoImportState());

  /// Pick video from device filesystem / gallery and probe metadata
  Future<void> pickAndProbeVideo() async {
    try {
      state = state.copyWith(status: VideoImportStatus.picking);

      final result = await FilePicker.platform.pickFiles(
        type: FileType.video,
        allowMultiple: false,
      );

      if (result == null || result.files.isEmpty || result.files.single.path == null) {
        state = state.copyWith(status: state.videoInfo != null ? VideoImportStatus.ready : VideoImportStatus.idle);
        return;
      }

      final file = result.files.single;
      final path = file.path!;
      final name = file.name;

      await probeVideoPath(path, name: name);
    } catch (e, stack) {
      AppLogger.e('ImportVideoController', 'Failed to pick video', e, stack);
      state = state.copyWith(
        status: VideoImportStatus.error,
        errorMessage: '选择视频失败: $e',
      );
    }
  }

  /// Probe a specified video file path
  Future<void> probeVideoPath(String path, {String? name}) async {
    try {
      state = state.copyWith(
        status: VideoImportStatus.probing,
        videoPath: path,
        videoName: name ?? path.split(RegExp(r'[\\/]')).last,
      );

      AppLogger.d('ImportVideoController', 'Probing video at $path');
      final videoInfo = await _repository.probeVideo(path);

      state = state.copyWith(
        status: VideoImportStatus.ready,
        videoInfo: videoInfo,
        errorMessage: null,
      );
    } catch (e, stack) {
      AppLogger.e('ImportVideoController', 'Failed to probe video at $path', e, stack);
      state = state.copyWith(
        status: VideoImportStatus.error,
        errorMessage: '解析视频规格失败: $e',
      );
    }
  }

  /// Build a initial DanceProject model from the probed video
  DanceProject? createProject() {
    if (state.videoPath == null || state.videoInfo == null) return null;

    final now = DateTime.now();
    return DanceProject(
      id: 'proj_${now.millisecondsSinceEpoch}',
      sourceUri: state.videoPath!,
      videoInfo: state.videoInfo!,
      createdAt: now,
      updatedAt: now,
    );
  }

  void reset() {
    state = const VideoImportState();
  }
}
