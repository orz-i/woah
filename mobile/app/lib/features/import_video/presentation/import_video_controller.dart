import 'package:flutter_riverpod/legacy.dart';
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
  Future<bool> pickAndProbeVideo() async {
    try {
      state = state.copyWith(status: VideoImportStatus.picking);

      final files = await FilePickerPlatform.instance.pickFiles(
        type: FileType.video,
      );

      if (files.isEmpty || files.first.path == null) {
        state = state.copyWith(
          status: state.videoInfo != null
              ? VideoImportStatus.ready
              : VideoImportStatus.idle,
        );
        return false;
      }

      final file = files.first;
      final path = file.path!;
      final name = file.name;

      await probeVideoPath(path, name: name);
      return state.isReady;
    } catch (e, stack) {
      AppLogger.e('ImportVideoController', 'IMPORT_PICK_FAILED', e, stack);
      state = state.copyWith(
        status: VideoImportStatus.error,
        errorMessage: '选择视频失败，请重试（IMPORT_PICK_FAILED）。',
      );
      return false;
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

      AppLogger.d('ImportVideoController', 'VIDEO_PROBE_REQUEST');
      final videoInfo = await _repository.probeVideo(path);

      state = state.copyWith(
        status: VideoImportStatus.ready,
        videoInfo: videoInfo,
        errorMessage: null,
      );
    } catch (e, stack) {
      AppLogger.e('ImportVideoController', 'VIDEO_PROBE_FAILED', e, stack);
      state = state.copyWith(
        status: VideoImportStatus.error,
        errorMessage: '解析视频规格失败，请重试（VIDEO_PROBE_FAILED）。',
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
