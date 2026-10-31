import 'dart:async';
import 'dart:io';
import 'package:flutter_riverpod/legacy.dart';

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
  bool _cancelRequested = false;
  JobStatusDto? _deferredTerminalStatus;

  ExportController(this._repository) : super(const ExportState()) {
    _subscribeProgress();
  }

  void _subscribeProgress() {
    _progressSub = _repository.progressStream.listen((statusDto) {
      if (state.jobId != null && statusDto.jobId != state.jobId) return;

      // Native teardown can publish a late failed/completed status after the
      // user's cancel request. Keep the cancelling view stable until the
      // cancellation RPC has succeeded or failed.
      if (_cancelRequested) {
        if (statusDto.state == 'failed' || statusDto.state == 'completed') {
          _deferredTerminalStatus = statusDto;
          AppLogger.w(
            'ExportController',
            'Deferred late ${statusDto.state} while cancellation is pending',
          );
        }
        return;
      }

      _applyProgress(statusDto);
    });
  }

  void _applyProgress(JobStatusDto statusDto) {
    final newPreviewPath = state.showLivePreview
        ? statusDto.currentPreviewPath
        : null;
    final jobState = ExportJobState.fromString(statusDto.state);
    state = state.copyWith(
      status: jobState,
      progress: statusDto.progress,
      currentFrame: statusDto.currentFrame,
      totalFrames: statusDto.totalFrames,
      fps: statusDto.fps,
      outputUri: statusDto.outputUri,
      currentPreviewPath: newPreviewPath,
      clearCurrentPreviewPath: !state.showLivePreview,
      errorMessage: statusDto.errorMessage,
    );
  }

  /// Toggle real-time rendered frame preview display
  void toggleLivePreview(bool enabled) {
    if (state.showLivePreview == enabled) return;

    final jobId = state.jobId;
    state = state.copyWith(
      showLivePreview: enabled,
      clearCurrentPreviewPath: true,
    );

    if (jobId != null) {
      unawaited(_setNativeLivePreview(jobId, enabled));
    }
  }

  Future<void> _setNativeLivePreview(String jobId, bool enabled) async {
    try {
      await _repository.setExportLivePreviewEnabled(
        jobId: jobId,
        enabled: enabled,
      );
    } catch (e, stack) {
      AppLogger.e(
        'ExportController',
        'Failed to toggle live preview for $jobId',
        e,
        stack,
      );
    }
  }

  /// Launch export pipeline
  Future<void> startExport(DanceProject project, String outputPath) async {
    _cancelRequested = false;
    _deferredTerminalStatus = null;
    try {
      state = state.copyWith(
        status: ExportJobState.preparing,
        project: project,
        progress: 0.0,
        clearErrorMessage: true,
      );

      final plan = await _resolveExportPlan(project);
      state = state.copyWith(exportPlan: plan);
      AppLogger.d(
        'ExportController',
        'Starting export for project ${project.id} with $plan (profile: quality)',
      );
      final livePreviewAtLaunch = state.showLivePreview;
      final jobId = await _repository.startExport(
        sourceUri: project.sourceUri,
        analysisCacheId: project.analysisCacheId ?? '',
        outputFilePath: outputPath,
        selectedPersonIds: project.selectedPersonIds.toList(),
        faceOnlyPersonIds: project.faceOnlyPersonIds.toList(),
        effects: project.effects,
        follow: project.follow,
        targetWidth: plan.width,
        targetHeight: plan.height,
        targetFps: plan.nominalFps,
        videoBitrate: plan.videoBitrate,
        processingProfile: 'quality',
        cropClarityScale: plan.cropClarityScale,
        // Android supports a runtime capture gate and starts with it disabled.
        // Other platforms keep the previous eager-capture behavior so their
        // UI toggle remains backwards-compatible.
        enableLivePreview: Platform.isAndroid ? livePreviewAtLaunch : true,
        trimStartMs: project.trimStartMs,
        trimEndMs: project.trimEndMs,
      );

      final desiredLivePreview = state.showLivePreview;
      state = state.copyWith(jobId: jobId, clearCurrentPreviewPath: true);
      if (Platform.isAndroid && desiredLivePreview != livePreviewAtLaunch) {
        unawaited(_setNativeLivePreview(jobId, desiredLivePreview));
      }
    } catch (e, stack) {
      AppLogger.e('ExportController', 'Failed to start export', e, stack);
      state = state.copyWith(
        status: ExportJobState.failed,
        errorMessage: '启动导出失败: $e',
      );
    }
  }

  Future<ExportPlan> _resolveExportPlan(DanceProject project) async {
    try {
      final capabilities = await _repository.getCapabilities();
      return ExportPlan.forProject(
        project,
        maxEncodeWidth: capabilities.maxEncodeWidth.toInt(),
        maxEncodeHeight: capabilities.maxEncodeHeight.toInt(),
      );
    } catch (error) {
      // Capability discovery is advisory. If it is unavailable, keep the ideal
      // source-derived contract and let the encoder fail explicitly rather than
      // silently degrading resolution or frame timing.
      AppLogger.w(
        'ExportController',
        'Encoder capability probe unavailable; preserving requested media contract: $error',
      );
      return ExportPlan.forProject(project);
    }
  }

  /// Cancel current export job
  Future<void> cancelExport() async {
    final jobId = state.jobId;
    if (jobId == null || _cancelRequested) return;

    _cancelRequested = true;
    AppLogger.d('ExportController', 'Cancelling job $jobId');
    try {
      await _repository.cancelJob(jobId);
      if (!mounted) return;
      state = state.copyWith(
        status: ExportJobState.cancelled,
        clearErrorMessage: true,
        clearCurrentPreviewPath: true,
      );
    } catch (_) {
      // If cancellation itself failed, a previously deferred native terminal
      // status is real and must be replayed rather than silently discarded.
      _cancelRequested = false;
      final pendingTerminalStatus = _deferredTerminalStatus;
      _deferredTerminalStatus = null;
      if (mounted && pendingTerminalStatus != null) {
        _applyProgress(pendingTerminalStatus);
      }
      rethrow;
    }
  }

  @override
  void dispose() {
    _progressSub?.cancel();
    super.dispose();
  }
}
