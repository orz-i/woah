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

enum _ExportPhase { starting, running, cancelling, terminal }

// Object identity is the generation token. This owner outlives the notifier
// while a start/cancel RPC is pending, so late native IDs reach cleanup even
// after route exit. Persistent platform failures are logged, not called success.
class _ExportOperation {
  _ExportPhase phase = _ExportPhase.starting;
  final startSettled = Completer<void>();
  final earlyStatuses = <String, JobStatusDto>{};
  String? jobId;
  bool submitted = false;
  Future<void>? cancellation;
  Future<void>? cleanup;
  JobStatusDto? deferredTerminal;
}

class ExportController extends StateNotifier<ExportState> {
  final NativeProcessingRepository _repository;
  StreamSubscription<JobStatusDto>? _progressSub;
  _ExportOperation? _operation;

  ExportController(this._repository) : super(const ExportState()) {
    _progressSub = _repository.progressStream.listen(_onProgress);
  }

  bool _owns(_ExportOperation operation) =>
      mounted && identical(_operation, operation);

  static bool _isTerminal(String status) =>
      status == 'failed' || status == 'completed' || status == 'cancelled';

  void _onProgress(JobStatusDto status) {
    final operation = _operation;
    if (!mounted ||
        operation == null ||
        operation.phase == _ExportPhase.terminal) {
      return;
    }
    if (operation.jobId == null) {
      // Native events can arrive before the start RPC replies. Buffer only
      // while submitted and only apply the returned ID, never unrelated jobs.
      if (operation.submitted) {
        final previous = operation.earlyStatuses[status.jobId];
        if (previous == null || !_isTerminal(previous.state)) {
          operation.earlyStatuses[status.jobId] = status;
        }
      }
      return;
    }
    if (status.jobId != operation.jobId) return;
    if (operation.phase == _ExportPhase.cancelling) {
      if (_isTerminal(status.state)) {
        operation.deferredTerminal ??= status;
      }
      return;
    }
    _applyProgress(operation, status);
  }

  void _applyProgress(_ExportOperation operation, JobStatusDto statusDto) {
    if (!_owns(operation) || operation.phase == _ExportPhase.terminal) return;
    if (_isTerminal(statusDto.state)) operation.phase = _ExportPhase.terminal;
    state = state.copyWith(
      status: ExportJobState.fromString(statusDto.state),
      progress: statusDto.progress,
      currentFrame: statusDto.currentFrame,
      totalFrames: statusDto.totalFrames,
      fps: statusDto.fps,
      outputUri: statusDto.outputUri,
      currentPreviewPath: state.showLivePreview
          ? statusDto.currentPreviewPath
          : null,
      clearCurrentPreviewPath: !state.showLivePreview,
      errorMessage: statusDto.errorMessage,
    );
  }

  /// Toggle real-time rendered frame preview display.
  void toggleLivePreview(bool enabled) {
    if (!mounted || state.showLivePreview == enabled) return;
    final jobId = state.jobId;
    state = state.copyWith(
      showLivePreview: enabled,
      clearCurrentPreviewPath: true,
    );
    if (jobId != null && _operation?.phase == _ExportPhase.running) {
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
        'Failed to toggle live preview',
        e,
        stack,
      );
    }
  }

  /// Launch once; repeated starts cannot replace an owned native operation.
  Future<void> startExport(DanceProject project, String outputPath) async {
    if (!mounted ||
        (_operation != null && _operation!.phase != _ExportPhase.terminal)) {
      return;
    }
    final operation = _ExportOperation();
    _operation = operation;
    // Construct a fresh state: nullable copyWith fields cannot clear old IDs,
    // output, preview, plan, counters or errors from a previous terminal job.
    state = ExportState(
      project: project,
      showLivePreview: state.showLivePreview,
    );
    try {
      final plan = await _resolveExportPlan(project);
      if (!_owns(operation) || operation.phase != _ExportPhase.starting) return;
      state = state.copyWith(exportPlan: plan);
      // A synchronous state listener can cancel or dispose during publication.
      if (!_owns(operation) || operation.phase != _ExportPhase.starting) return;
      final livePreviewAtLaunch = state.showLivePreview;
      operation.submitted = true;
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
        enableLivePreview: Platform.isAndroid ? livePreviewAtLaunch : true,
        trimStartMs: project.trimStartMs,
        trimEndMs: project.trimEndMs,
      );
      operation.jobId = jobId;
      final earlyStatus = operation.earlyStatuses[jobId];
      operation.earlyStatuses.clear();
      if (!_owns(operation) || operation.phase == _ExportPhase.cancelling) {
        if (earlyStatus != null && _isTerminal(earlyStatus.state)) {
          operation.deferredTerminal ??= earlyStatus;
        }
      } else {
        operation.phase = _ExportPhase.running;
        final desiredLivePreview = state.showLivePreview;
        state = state.copyWith(jobId: jobId, clearCurrentPreviewPath: true);
        // Publication may synchronously request cancellation or dispose.
        // Replay through the same phase checks as a live native event.
        if (earlyStatus != null) _onProgress(earlyStatus);
        if (operation.phase == _ExportPhase.running &&
            Platform.isAndroid &&
            desiredLivePreview != livePreviewAtLaunch) {
          unawaited(_setNativeLivePreview(jobId, desiredLivePreview));
        }
      }
    } catch (e, stack) {
      AppLogger.e('ExportController', 'Failed to start export', e, stack);
      if (_owns(operation) && operation.phase != _ExportPhase.cancelling) {
        operation.phase = _ExportPhase.terminal;
        state = state.copyWith(
          status: ExportJobState.failed,
          errorMessage: '启动导出失败: $e',
        );
      }
    } finally {
      operation.earlyStatuses.clear();
      operation.startSettled.complete();
    }
    // A disposed owner still finishes cleanup; cancellation errors are delivered
    // to cancelExport's caller (or logged by dispose), not this ignored UI Future.
    try {
      await (operation.cleanup ?? operation.cancellation);
    } catch (_) {}
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
      AppLogger.w(
        'ExportController',
        'Encoder capability probe unavailable; preserving requested media contract: $error',
      );
      return ExportPlan.forProject(project);
    }
  }

  /// Pending startup cancellation waits for native ownership before reporting
  /// success. Repeated requests share the same in-flight cancellation.
  Future<void> cancelExport() {
    final operation = _operation;
    if (!mounted ||
        operation == null ||
        operation.phase == _ExportPhase.terminal) {
      return Future<void>.value();
    }
    return _cancelOperation(operation);
  }

  Future<void> _cancelOperation(_ExportOperation operation) {
    if (operation.cancellation != null) return operation.cancellation!;
    operation.phase = _ExportPhase.cancelling;
    final future = _performCancellation(operation);
    operation.cancellation = future;
    return future;
  }

  Future<void> _performCancellation(_ExportOperation operation) async {
    // Install the shared Future before a synchronous repository error can reset it.
    await Future<void>.value();
    try {
      if (operation.submitted) await operation.startSettled.future;
      final jobId = operation.jobId;
      if (jobId != null) await _repository.cancelJob(jobId);
      operation.phase = _ExportPhase.terminal;
      operation.deferredTerminal = null;
      if (_owns(operation)) {
        state = state.copyWith(
          status: ExportJobState.cancelled,
          clearErrorMessage: true,
          clearCurrentPreviewPath: true,
        );
      }
    } catch (_) {
      operation.cancellation = null;
      operation.phase = _ExportPhase.running;
      final deferred = operation.deferredTerminal;
      operation.deferredTerminal = null;
      if (_owns(operation)) {
        // In a cancelled startup window the ID was never published to state.
        state = state.copyWith(jobId: operation.jobId);
        if (deferred != null) _applyProgress(operation, deferred);
      }
      rethrow;
    }
  }

  Future<void> _cleanupDisposedOperation(_ExportOperation operation) async {
    // Both native coordinators accept repeated cancellation of the same ID.
    // Retry a transient channel failure once, without touching notifier state.
    for (var attempt = 0; attempt < 2; attempt++) {
      try {
        await _cancelOperation(operation);
        return;
      } catch (error, stack) {
        if (attempt == 1) {
          AppLogger.e(
            'ExportController',
            'Export cleanup failed for job ${operation.jobId}',
            error,
            stack,
          );
        }
      }
    }
  }

  @override
  void dispose() {
    final operation = _operation;
    _progressSub?.cancel();
    if (operation != null && operation.phase != _ExportPhase.terminal) {
      operation.cleanup = _cleanupDisposedOperation(operation);
      unawaited(operation.cleanup!);
    }
    super.dispose();
  }
}
