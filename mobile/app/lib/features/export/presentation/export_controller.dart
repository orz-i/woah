import 'dart:async';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:dance_domain/dance_domain.dart';
import 'package:dance_native/dance_native.dart';
import '../../../core/logging/app_logger.dart';
import '../../../repositories/native_processing_repository.dart';
import '../domain/export_state.dart';

final exportControllerProvider =
    StateNotifierProvider.autoDispose<ExportController, ExportState>((ref) {
  final repo = ref.watch(nativeRepositoryProvider);
  return ExportController(repo);
});

class ExportController extends StateNotifier<ExportState> {
  final NativeProcessingRepository _repository;
  StreamSubscription<JobStatusDto>? _progressSub;

  ExportController(this._repository) : super(const ExportState()) {
    _subscribeProgress();
  }

  void _subscribeProgress() {
    _progressSub = _repository.progressStream.listen((statusDto) {
      if (state.jobId != null && statusDto.jobId != state.jobId) return;

      final jobState = ExportJobState.fromString(statusDto.state);
      state = state.copyWith(
        status: jobState,
        progress: statusDto.progress,
        currentFrame: statusDto.currentFrame,
        totalFrames: statusDto.totalFrames,
        fps: statusDto.fps,
        outputUri: statusDto.outputUri,
        errorMessage: statusDto.errorMessage,
      );
    });
  }

  /// Launch export pipeline
  Future<void> startExport(
    DanceProject project,
    String outputPath, {
    String processingProfile = 'quality',
  }) async {
    try {
      state = state.copyWith(
        status: ExportJobState.preparing,
        project: project,
        progress: 0.0,
        errorMessage: null,
      );

      AppLogger.d('ExportController', 'Starting export for project ${project.id} (profile: $processingProfile)');
      final jobId = await _repository.startExport(
        sourceUri: project.sourceUri,
        analysisCacheId: project.analysisCacheId ?? '',
        outputFilePath: outputPath,
        selectedPersonIds: project.selectedPersonIds.toList(),
        effects: project.effects,
        follow: project.follow,
        targetWidth: project.videoInfo.width,
        targetHeight: project.videoInfo.height,
        targetFps: project.videoInfo.fps,
        processingProfile: processingProfile,
      );


      state = state.copyWith(jobId: jobId);
    } catch (e, stack) {
      AppLogger.e('ExportController', 'Failed to start export', e, stack);
      state = state.copyWith(
        status: ExportJobState.failed,
        errorMessage: '启动导出失败: $e',
      );
    }
  }

  /// Cancel current export job
  Future<void> cancelExport() async {
    final jobId = state.jobId;
    if (jobId != null) {
      AppLogger.d('ExportController', 'Cancelling job $jobId');
      await _repository.cancelJob(jobId);
      state = state.copyWith(status: ExportJobState.cancelled);
    }
  }

  @override
  void dispose() {
    _progressSub?.cancel();
    super.dispose();
  }
}
