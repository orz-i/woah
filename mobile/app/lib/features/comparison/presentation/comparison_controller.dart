import 'dart:async';
import 'dart:math';
import 'package:flutter/foundation.dart';
import 'package:dance_domain/dance_domain.dart';
import 'package:dance_native/dance_native.dart';
import '../data/comparison_gateway.dart';

class ComparisonController extends ChangeNotifier {
  final ComparisonGateway gateway;
  ComparisonController(this.gateway) : project = _newProject();
  static ComparisonProject _newProject() => ComparisonProject(
    id: '${DateTime.now().microsecondsSinceEpoch}_${Random().nextInt(1 << 32)}',
    updatedAtMs: DateTime.now().millisecondsSinceEpoch,
  );
  ComparisonProject project;
  List<ComparisonProject> projects = [];
  ComparisonPlaybackDto? playback;
  ComparisonExportDto? pendingExport;
  String message = '选择参考和目标视频，按共同音乐开始对比。';
  String? error;
  bool initialized = false, picking = false, matching = false, saving = false;
  bool calibrating = false, exporting = false;
  bool stepping = false;
  bool adjusting = false;
  bool enteringPractice = false;
  bool _settingLoopRange = false;
  bool _settingPractice = false;
  Future<bool>? _mappingWork;
  ComparisonProject? _pictureOriginal, _picturePending;
  Timer? _pictureTimer;
  Future<void>? _pictureWorker;
  bool _pictureApplyFailed = false;
  ComparisonProject? _manualOriginal;
  bool _closed = false, _opening = false, _polling = false, _dirty = false;
  int _revision = 0, _width = 0, _height = 0;
  bool _horizontal = false;
  bool _lastSaveSucceeded = true;
  int _openRequest = 0, _resizeRequest = 0;
  int _practiceRequest = 0, _controlRequest = 0, _pendingSaves = 0;
  Timer? _poll, _autosave, _resize;
  Future<void> _writeTail = Future.value();
  bool get busy =>
      picking ||
      matching ||
      calibrating ||
      exporting ||
      stepping ||
      adjusting ||
      enteringPractice ||
      _settingLoopRange ||
      _settingPractice ||
      _mappingWork != null;
  bool get playing =>
      playback?.phase == 'playing' || playback?.phase == 'buffering';
  bool get canPlay =>
      project.hasPair &&
      !busy &&
      playback != null &&
      playback!.phase != 'error' &&
      playback!.phase != 'opening';
  bool get canStep =>
      canPlay && (playback!.phase == 'paused' || playback!.phase == 'playing');
  void _notify() {
    if (!_closed) notifyListeners();
  }

  bool _ownsPlayer(int request, String projectId) =>
      !_closed && request == _openRequest && project.id == projectId;

  void _invalidatePlayer() {
    _revision++;
    _openRequest++;
    _practiceRequest++;
    _controlRequest++;
    _settingPractice = false;
  }

  Future<void> initialize() async {
    try {
      final library = await gateway.load();
      if (_closed) return;
      projects = library.projects;
      if (projects.isNotEmpty) {
        project = projects.first;
        message = project.alignment == 'manual' ? '已恢复手动校准，可继续对比。' : '已恢复上次对比。';
      }
      if (library.unreadableCount > 0) error = '部分本地项目无法读取，原数据已保留。';
    } catch (_) {
      error = '项目读取失败，请重试；现有项目不会被覆盖。';
    }
    if (_closed) return;
    try {
      final job = await gateway.getExport();
      if (job != null &&
          const [
            'queued',
            'encoding',
            'publishing',
            'cancelling',
          ].contains(job.state)) {
        pendingExport = job;
        exporting = true;
      }
    } catch (_) {
      /* Preview remains usable if there is no readable export record. */
    }
    if (_closed) return;
    initialized = true;
    _poll = Timer.periodic(
      const Duration(milliseconds: 200),
      (_) => _refresh(),
    );
    _autosave = Timer.periodic(const Duration(seconds: 3), (_) {
      if (_dirty && !busy) unawaited(save());
    });
    _notify();
    if (exporting) return;
    await _open();
    if (project.hasPair && project.alignment == 'unmatched') await matchMusic();
  }

  void setViewport(int width, int height, bool horizontal) {
    if (_closed ||
        width < 2 ||
        height < 2 ||
        (_width == width && _height == height && _horizontal == horizontal)) {
      return;
    }
    _width = width;
    _height = height;
    _horizontal = horizontal;
    final request = ++_resizeRequest;
    _resize?.cancel();
    _resize = Timer(const Duration(milliseconds: 180), () async {
      if (_closed ||
          calibrating ||
          exporting ||
          enteringPractice ||
          _mappingWork != null ||
          _settingPractice ||
          !initialized ||
          !project.hasPair) {
        return;
      }
      if (playback == null) {
        await _open();
        return;
      }
      final revision = _revision;
      final openRequest = _openRequest;
      bool isCurrent() =>
          !_closed &&
          revision == _revision &&
          request == _resizeRequest &&
          openRequest == _openRequest &&
          !calibrating &&
          !exporting;
      try {
        final state = await gateway.resize(_width, _height, _horizontal);
        if (!isCurrent()) return;
        playback = state;
        _notify();
      } catch (_) {
        if (isCurrent()) {
          error = '画面调整失败，请重新打开此项目。';
          _notify();
        }
      }
    });
  }

  Future<void> _open() async {
    if (_closed ||
        !initialized ||
        calibrating ||
        exporting ||
        !project.hasPair ||
        _width < 2 ||
        _height < 2) {
      return;
    }
    final revision = _revision;
    final request = ++_openRequest;
    _opening = true;
    _notify();
    try {
      final state = await gateway.open(project, _width, _height, _horizontal);
      if (!_closed && revision == _revision && request == _openRequest) {
        playback = state;
        error = state.error;
      }
    } catch (_) {
      if (!_closed && revision == _revision && request == _openRequest) {
        playback = null;
        error = '视频无法读取，请点击对应视频重新关联。';
      }
    } finally {
      if (!_closed && request == _openRequest) {
        _opening = false;
        _notify();
      }
    }
  }

  Future<void> _refresh() async {
    if (_closed ||
        calibrating ||
        exporting ||
        enteringPractice ||
        matching ||
        _mappingWork != null ||
        _settingPractice ||
        stepping ||
        _settingLoopRange ||
        _polling ||
        _opening ||
        playback == null) {
      return;
    }
    _polling = true;
    final revision = _revision;
    final request = _openRequest;
    final control = _controlRequest;
    final projectId = project.id;
    try {
      final state = await gateway.state();
      if (!_ownsPlayer(request, projectId) ||
          revision != _revision ||
          control != _controlRequest) {
        return;
      }
      final previous = playback;
      final changed =
          previous == null ||
          previous.textureId != state.textureId ||
          previous.phase != state.phase ||
          previous.positionUs != state.positionUs ||
          previous.durationUs != state.durationUs ||
          previous.teacherStartUs != state.teacherStartUs ||
          previous.offsetUs != state.offsetUs ||
          previous.timeScale != state.timeScale ||
          previous.speed != state.speed ||
          previous.error != state.error;
      playback = state;
      if (state.error != null) error = state.error;
      if (project.positionUs != state.positionUs) {
        project = project.copyWith(positionUs: state.positionUs);
        _dirty = true;
      }
      if (changed) _notify();
    } catch (_) {
      /* A detached route is closed by its owner; preserve the project. */
    } finally {
      _polling = false;
    }
  }

  Future<void> pick(String role) async {
    if (busy || _closed) return;
    picking = true;
    error = null;
    _notify();
    try {
      final source = await gateway.pick(role);
      if (_closed || source == null) return;
      final previous = role == 'teacher' ? project.teacher : project.learner;
      if (source.uri == previous?.uri) {
        await _open();
      } else {
        await gateway.cancelAlignment();
        _invalidatePlayer();
        project = project.copyWith(
          teacher: role == 'teacher' ? source : null,
          learner: role == 'learner' ? source : null,
          offsetUs: 0,
          timeScale: 1,
          alignment: 'unmatched',
          clearAnchors: true,
          clearLoop: true,
          mirrorTeacher: role == 'teacher' ? false : null,
          mirrorLearner: role == 'learner' ? false : null,
          teacherPicture: role == 'teacher' ? const ComparisonPicture() : null,
          learnerPicture: role == 'learner' ? const ComparisonPicture() : null,
          positionUs: 0,
        );
        playback = null;
        _dirty = true;
        await save();
        await _open();
      }
    } catch (_) {
      error = '选择视频失败，请从系统文件选择器选择可读取的本地视频。';
    } finally {
      picking = false;
      _notify();
    }
    if (!_closed && project.hasPair && project.alignment == 'unmatched') {
      await matchMusic();
    }
  }

  Future<void> matchMusic() async {
    if (_closed || !project.hasPair || busy) return;
    matching = true;
    error = null;
    message = '正在自动校准音乐…';
    final revision = ++_revision;
    _notify();
    try {
      final result = await gateway.align(project);
      if (_closed || revision != _revision) return;
      final available =
          result.candidates
              .where(
                (item) =>
                    ComparisonMusicMapping(
                      item.offsetUs,
                      item.timeScale ?? 1,
                    ).overlap(
                      project.teacher!.durationUs,
                      project.learner!.durationUs,
                    ) !=
                    null,
              )
              .toList()
            ..sort((a, b) => b.score.compareTo(a.score));
      if (available.isNotEmpty &&
          (result.status == 'matched' || result.status == 'ambiguous')) {
        final applied = await _applyMusicMapping(
          ComparisonMusicMapping(
            available.first.offsetUs,
            available.first.timeScale ?? 1,
          ),
          alignment: result.status == 'matched' ? 'auto' : 'auto_review',
        );
        if (!_closed && revision == _revision && applied) {
          message = result.status == 'matched'
              ? '已自动完成音乐速率和位置校准。'
              : '已采用最佳音乐乐段，可手动校准。';
        }
      } else {
        message = result.message;
      }
    } catch (_) {
      if (!_closed && revision == _revision) message = '本次音乐匹配未完成，可以重试或手动校准。';
    } finally {
      if (!_closed && revision == _revision) {
        matching = false;
        _notify();
      }
    }
  }

  Future<void> cancelMatching() async {
    final revision = ++_revision;
    final applying = _mappingWork;
    matching = false;
    message = '已取消音乐匹配，可以手动校准。';
    _notify();
    await gateway.cancelAlignment();
    // Analysis cancellation cannot undo a player rebuild or an in-flight write.
    // Keep editing disabled until that transaction has restored its snapshot.
    await applying;
    if (!_closed && revision == _revision) _notify();
  }

  Future<bool> _applyMusicMapping(
    ComparisonMusicMapping mapping, {
    required String alignment,
    int? referenceAnchor,
    int? targetAnchor,
    int? referenceAnchor2,
    int? targetAnchor2,
  }) {
    final projectId = project.id;
    final viewport = (_width, _height, _horizontal);
    final work = _applyMusicMappingTransaction(
      mapping,
      alignment: alignment,
      referenceAnchor: referenceAnchor,
      targetAnchor: targetAnchor,
      referenceAnchor2: referenceAnchor2,
      targetAnchor2: targetAnchor2,
    );
    late final Future<bool> completion;
    completion = work.whenComplete(() async {
      if (!_closed &&
          project.id == projectId &&
          (playback == null || viewport != (_width, _height, _horizontal))) {
        await _open();
      }
      if (identical(_mappingWork, completion)) _mappingWork = null;
      _notify();
    });
    _mappingWork = completion;
    return completion;
  }

  Future<bool> _applyMusicMappingTransaction(
    ComparisonMusicMapping mapping, {
    required String alignment,
    int? referenceAnchor,
    int? targetAnchor,
    int? referenceAnchor2,
    int? targetAnchor2,
  }) async {
    final previous = project;
    final revision = _revision;
    final request = _openRequest;
    final wasCalibrating = calibrating;
    bool ownsPlayer() =>
        _ownsPlayer(request, previous.id) && calibrating == wasCalibrating;
    bool isCurrent() => ownsPlayer() && revision == _revision;
    var changedPlayer = false, written = false, committed = false;
    String? failure;
    error = null;
    final before = playback?.teacherStartUs;
    try {
      var state = playback;
      if (!calibrating && playback != null) {
        changedPlayer = true;
        state = await gateway.setAlignment(mapping.offsetUs, mapping.timeScale);
        if (!isCurrent()) return false;
        if (state.error != null) throw StateError(state.error!);
      }
      final next = previous
          .withMusicMapping(
            mapping,
            oldReferenceStartUs: before,
            newReferenceStartUs: (state?.durationUs ?? 0) > 0
                ? state!.teacherStartUs
                : null,
            newDurationUs: (state?.durationUs ?? 0) > 0
                ? state!.durationUs
                : null,
            referencePositionUs: referenceAnchor,
          )
          .copyWith(alignment: alignment, clearAnchors: true)
          .copyWith(
            teacherAnchorUs: referenceAnchor,
            learnerAnchorUs: targetAnchor,
            teacherAnchor2Us: referenceAnchor2,
            learnerAnchor2Us: targetAnchor2,
          );
      if (!calibrating && playback != null) {
        state = await gateway.setPractice(next);
        if (!isCurrent()) return false;
        if (state.error != null) throw StateError(state.error!);
        state = await gateway.control(
          ComparisonCommand.seek,
          next.positionUs,
          next.speed,
        );
        if (!isCurrent()) return false;
        if (state.error != null) throw StateError(state.error!);
      }
      if (!isCurrent()) return false;
      written = await _persist(next, isCurrent: isCurrent);
      if (!isCurrent()) return false;
      if (!written) {
        failure = error;
        return false;
      }
      project = next;
      playback = state;
      _dirty = false;
      committed = true;
      _notify();
      return true;
    } catch (_) {
      if (isCurrent()) failure = '音乐校准未应用，请检查对应位置后重试。';
      return false;
    } finally {
      if (!committed && written) {
        // A native save already dispatched cannot be cancelled. Put the durable
        // snapshot back in the same write queue before releasing the transaction.
        if (!await _persist(previous)) {
          _dirty = true;
          failure = '项目保存失败，请重试后再离开。';
        }
      }
      if (!committed && ownsPlayer()) {
        // Reopen the complete snapshot: mapping, selection, pictures and source
        // position must agree even if cancellation happened after native apply.
        if (changedPlayer) await _open();
        if (failure != null && !_closed && project.id == previous.id) {
          error = failure;
        }
        _notify();
      }
    }
  }

  Future<(ComparisonExportPlan, ComparisonExportPlan?)?> prepareExport() async {
    if (!canPlay || _closed) return null;
    exporting = true;
    _invalidatePlayer();
    _resize?.cancel();
    _notify();
    try {
      await pause();
      await save();
      if (!_lastSaveSucceeded || _closed) {
        exporting = false;
        _notify();
        return null;
      }
      final full = ComparisonExportPlan(
        project: project,
        durationUs: playback!.durationUs,
        previewWidth: _width,
        previewHeight: _height,
        sideBySide: _horizontal,
      );
      ComparisonExportPlan? selection;
      try {
        selection = ComparisonExportPlan(
          project: project,
          durationUs: playback!.durationUs,
          previewWidth: _width,
          previewHeight: _height,
          sideBySide: _horizontal,
          selection: true,
        );
      } on FormatException {
        /* An incomplete A/B selection cannot be exported. */
      }
      await gateway.cancelAlignment();
      await gateway.close();
      playback = null;
      _notify();
      return (full, selection);
    } catch (_) {
      exporting = false;
      error = '导出准备未完成，请等待画面就绪后重试。';
      await _open();
      _notify();
      return null;
    }
  }

  Future<void> finishExport() async {
    if (_closed || !exporting) return;
    pendingExport = null;
    exporting = false;
    await _open();
    _notify();
  }

  Future<bool> enterManualCalibration() => _enterPractice(manual: true);

  Future<bool> _enterPractice({required bool manual}) async {
    if (_closed || busy || !project.hasPair || (!manual && !canPlay)) {
      return false;
    }
    // Reserve both practice entry points before pause/save can yield to a tap.
    enteringPractice = true;
    final projectId = project.id;
    final viewport = (_width, _height, _horizontal);
    var appliedViewport = viewport;
    var revision = _revision, request = _openRequest;
    bool isCurrent() =>
        _ownsPlayer(request, projectId) && revision == _revision;
    _notify();
    try {
      await pause();
      if (!isCurrent()) return false;
      await save();
      if (!isCurrent() || !_lastSaveSucceeded) return false;
      if (manual) {
        _invalidatePlayer();
        revision = _revision;
        request = _openRequest;
        await gateway.cancelAlignment();
        if (!isCurrent()) return false;
        await gateway.close();
        if (!isCurrent()) return false;
        playback = null;
        _manualOriginal = project;
        calibrating = true;
      } else {
        while (appliedViewport != (_width, _height, _horizontal)) {
          appliedViewport = (_width, _height, _horizontal);
          final opening = _open();
          request = _openRequest;
          await opening;
          if (!isCurrent() || playback == null || playback!.phase == 'error') {
            return false;
          }
        }
        _pictureOriginal = project;
        _pictureApplyFailed = false;
        adjusting = true;
      }
      return true;
    } catch (_) {
      if (isCurrent()) {
        appliedViewport = (_width, _height, _horizontal);
        await _open();
        if (!_closed && revision == _revision && project.id == projectId) {
          error = '练习页面暂时无法打开，请重试。';
        }
      }
      return false;
    } finally {
      if (!_closed &&
          project.id == projectId &&
          !calibrating &&
          !adjusting &&
          appliedViewport != (_width, _height, _horizontal)) {
        await _open();
      }
      enteringPractice = false;
      _notify();
    }
  }

  Future<void> leaveManualCalibration({required bool applied}) async {
    if (_closed || !calibrating) return;
    final layoutChanged =
        project.sideBySide != _manualOriginal?.sideBySide ||
        project.mirrorTeacher != _manualOriginal?.mirrorTeacher ||
        project.mirrorLearner != _manualOriginal?.mirrorLearner;
    if (!applied && _manualOriginal != null) {
      project = _manualOriginal!.copyWith(
        sideBySide: project.sideBySide,
        mirrorTeacher: project.mirrorTeacher,
        mirrorLearner: project.mirrorLearner,
      );
      _dirty = layoutChanged;
    }
    _manualOriginal = null;
    calibrating = false;
    if (!applied && layoutChanged) await save();
    await _open();
    _notify();
  }

  Future<bool> manual(
    int teacherUs,
    int learnerUs, {
    int? teacher2Us,
    int? learner2Us,
  }) async {
    bool inside(int? value, int duration) =>
        value != null && value >= 0 && value < duration;
    if (!project.hasPair ||
        !inside(teacherUs, project.teacher!.durationUs) ||
        !inside(learnerUs, project.learner!.durationUs) ||
        ((teacher2Us != null || learner2Us != null) &&
            (!inside(teacher2Us, project.teacher!.durationUs) ||
                !inside(learner2Us, project.learner!.durationUs)))) {
      throw const FormatException('音乐点必须位于各自视频内');
    }
    final mapping = teacher2Us == null
        ? ComparisonMusicMapping(
            (learnerUs - teacherUs * project.timeScale).round(),
            project.timeScale,
          )
        : ComparisonMusicMapping.anchors(
            teacherUs,
            learnerUs,
            teacher2Us,
            learner2Us!,
          );
    await cancelMatching();
    final saved = await _applyMusicMapping(
      mapping,
      alignment: 'manual',
      referenceAnchor: teacherUs,
      targetAnchor: learnerUs,
      referenceAnchor2: teacher2Us,
      targetAnchor2: learner2Us,
    );
    if (saved) message = '已保存手动音乐校准。';
    return saved;
  }

  Future<void> toggle() async {
    await _control(
      playing ? ComparisonCommand.pause : ComparisonCommand.play,
      0,
    );
  }

  Future<void> pause() async {
    await _control(ComparisonCommand.pause, 0);
  }

  Future<void> seek(int positionUs) async {
    if (stepping) return;
    await _control(ComparisonCommand.seek, positionUs);
  }

  Future<void> stepFrame(bool forward) async {
    if (!canStep || _closed) return;
    final revision = ++_revision;
    stepping = true;
    error = null;
    _notify();
    try {
      final state = await gateway.stepFrame(forward);
      if (_closed || revision != _revision) return;
      playback = state;
      project = project.copyWith(positionUs: state.positionUs);
      _dirty = true;
      await save();
    } catch (_) {
      if (!_closed) error = '逐帧未完成，请等待画面就绪后重试。';
    } finally {
      stepping = false;
      _notify();
    }
  }

  Future<void> setMirror(bool reference) async {
    if (_closed ||
        picking ||
        matching ||
        stepping ||
        adjusting ||
        enteringPractice ||
        exporting ||
        _settingPractice ||
        _mappingWork != null) {
      return;
    }
    await _setPractice(
      project.copyWith(
        mirrorTeacher: reference ? !project.mirrorTeacher : null,
        mirrorLearner: reference ? null : !project.mirrorLearner,
      ),
    );
  }

  Future<bool> beginPictureAdjustment() => _enterPractice(manual: false);

  void previewPicture(bool reference, ComparisonPicture value) {
    if (!adjusting || _closed) return;
    final picture = ComparisonPicture.clamped(
      value.zoom,
      value.panX,
      value.panY,
    );
    if (picture ==
        (reference ? project.teacherPicture : project.learnerPicture)) {
      return;
    }
    project = project.copyWith(
      teacherPicture: reference ? picture : null,
      learnerPicture: reference ? null : picture,
    );
    _picturePending = project;
    error = null;
    _notify();
    _pictureTimer ??= Timer(const Duration(milliseconds: 32), () {
      _pictureTimer = null;
      unawaited(_flushPicture().then((_) {}));
    });
  }

  Future<bool> _flushPicture() async {
    _pictureTimer?.cancel();
    _pictureTimer = null;
    if (_pictureWorker != null) {
      await _pictureWorker;
      if (_picturePending != null && !_closed) return _flushPicture();
      return !_pictureApplyFailed;
    }
    if (_picturePending == null || _closed) return !_pictureApplyFailed;
    final work = () async {
      while (!_closed && _picturePending != null) {
        final snapshot = _picturePending!;
        _picturePending = null;
        try {
          await gateway.setPractice(snapshot);
          _pictureApplyFailed = false;
        } catch (_) {
          _pictureApplyFailed = true;
          if (!_closed) {
            error = '画面调整未应用，请重试。';
            _notify();
          }
          break;
        }
      }
    }();
    _pictureWorker = work;
    try {
      await work;
    } finally {
      _pictureWorker = null;
    }
    return !_pictureApplyFailed;
  }

  Future<bool> finishPictureAdjustment({required bool applied}) async {
    if (!adjusting || _closed) return false;
    if (!applied) project = _pictureOriginal!;
    _picturePending = project;
    if (!await _flushPicture() || _closed) return false;
    if (applied) {
      _dirty = true;
      await save();
      if (!_lastSaveSucceeded || _closed) return false;
    } else {
      _dirty = false;
      error = null;
    }
    error = null;
    _pictureOriginal = null;
    adjusting = false;
    _notify();
    return true;
  }

  ComparisonMarkerRange? markerRange(ComparisonMarker marker) {
    final state = playback;
    if (state == null || state.durationUs <= 0 || state.phase == 'error') {
      return null;
    }
    return project.markerRange(
      marker,
      teacherStartUs: state.teacherStartUs,
      durationUs: state.durationUs,
    );
  }

  Future<bool> saveMarker({
    ComparisonMarker? existing,
    required int startUs,
    required int endUs,
    required String note,
  }) async {
    if (_closed || busy) return false;
    error = null;
    final range = existing == null ? null : markerRange(existing);
    final ComparisonMarker marker;
    if (existing != null && (range == null || range.clipped)) {
      // Preserve inaccessible source anchors when editing the note.
      marker = existing.withNote(note.trim());
    } else {
      final duration = playback?.durationUs ?? 0;
      if (!project.hasPair ||
          startUs < 0 ||
          endUs <= startUs ||
          endUs > duration) {
        throw const FormatException('片段必须位于共同时间内，结束时间需晚于开始时间');
      }
      final uri = existing?.sourceUri ?? project.teacher!.uri;
      final origin = playback!.teacherStartUs;
      int sourceTime(int common) => uri == project.teacher!.uri
          ? origin + common
          : project.musicMapping.target(origin + common);
      marker = ComparisonMarker(
        id:
            existing?.id ??
            '${DateTime.now().microsecondsSinceEpoch}_${Random().nextInt(1 << 32)}',
        sourceUri: uri,
        startUs: sourceTime(startUs),
        endUs: sourceTime(endUs),
        note: note.trim(),
      );
    }
    final previousMarkers = project.markers;
    project = project.copyWith(
      markers: [
        for (final old in project.markers)
          if (old.id != marker.id) old,
        marker,
      ],
    );
    final nextMarkers = project.markers;
    _dirty = true;
    await save();
    if (!_lastSaveSucceeded && identical(project.markers, nextMarkers)) {
      project = project.copyWith(markers: previousMarkers);
    }
    _notify();
    return _lastSaveSucceeded;
  }

  Future<bool> deleteMarker(String id) async {
    if (_closed || busy) return false;
    error = null;
    final previousMarkers = project.markers;
    project = project.copyWith(
      markers: project.markers.where((marker) => marker.id != id).toList(),
    );
    final nextMarkers = project.markers;
    _dirty = true;
    await save();
    if (!_lastSaveSucceeded && identical(project.markers, nextMarkers)) {
      project = project.copyWith(markers: previousMarkers);
    }
    _notify();
    return _lastSaveSucceeded;
  }

  Future<bool> openMarker(
    ComparisonMarker marker, {
    bool repeat = false,
  }) async {
    if (_closed || busy) return false;
    final range = markerRange(marker);
    if (range == null) return false;
    await pause();
    if (repeat) {
      if (!await _setPractice(
        project.copyWith(
          loopStartUs: range.startUs,
          loopEndUs: range.endUs,
          loopEnabled: true,
        ),
      )) {
        return false;
      }
    } else if (project.loopEnabled) {
      if (!await _setPractice(project.copyWith(loopEnabled: false))) {
        return false;
      }
    }
    await seek(range.startUs);
    await save();
    // A selection opens paused; the existing play button starts its A/B loop.
    return _lastSaveSucceeded && error == null;
  }

  Future<bool> setLoopRange(int startUs, int endUs) async {
    if (!canPlay || _closed) return false;
    final duration = playback!.durationUs;
    if (startUs < 0 || endUs <= startUs || endUs > duration) {
      error = '选段必须位于共同时间内，B 点需晚于 A 点。';
      _notify();
      return false;
    }
    if (endUs - startUs < min(100000, duration)) {
      error = '选段过短，请至少保留 0.1 秒。';
      _notify();
      return false;
    }
    _settingLoopRange = true;
    _revision++;
    _notify();
    try {
      await pause();
      if (_closed || error != null) return false;
      if (!await _setPractice(
            project.copyWith(
              loopStartUs: startUs,
              loopEndUs: endUs,
              loopEnabled: true,
            ),
          ) ||
          _closed) {
        return false;
      }
      await seek(startUs);
      if (_closed || error != null) return false;
      await save();
      return _lastSaveSucceeded;
    } finally {
      _settingLoopRange = false;
      _notify();
    }
  }

  Future<void> toggleLoop() async {
    if (busy || project.loopStartUs == null || project.loopEndUs == null) {
      return;
    }
    await _setPractice(project.copyWith(loopEnabled: !project.loopEnabled));
  }

  Future<void> clearLoop() async {
    if (busy) return;
    await _setPractice(project.copyWith(clearLoop: true));
  }

  Future<bool> _setPractice(ComparisonProject next) async {
    if (_closed || _settingPractice) return false;
    final previous = project;
    final revision = ++_revision;
    final request = _openRequest;
    final viewport = (_width, _height, _horizontal);
    final operation = ++_practiceRequest;
    bool isCurrent() =>
        _ownsPlayer(request, previous.id) &&
        revision == _revision &&
        operation == _practiceRequest;
    _settingPractice = true;
    error = null;
    _notify();
    try {
      var state = playback;
      if (!calibrating && playback != null) {
        state = await gateway.setPractice(next);
        if (!isCurrent()) return false;
        if (state.error != null) throw StateError(state.error!);
      }
      if (!isCurrent()) return false;
      project = next.copyWith(positionUs: state?.positionUs);
      playback = state;
      _dirty = true;
      await save();
      return isCurrent() && _lastSaveSucceeded;
    } catch (_) {
      if (isCurrent()) {
        if (!calibrating && playback != null) await _open();
        if (!_closed &&
            revision == _revision &&
            operation == _practiceRequest &&
            project.id == previous.id) {
          error = '练习设置未应用，请重试。';
        }
      }
      return false;
    } finally {
      if (!_closed &&
          project.id == previous.id &&
          viewport != (_width, _height, _horizontal)) {
        await _open();
      }
      if (operation == _practiceRequest) _settingPractice = false;
      _notify();
    }
  }

  Future<void> setSideBySide(bool value) async {
    if (_closed ||
        picking ||
        matching ||
        enteringPractice ||
        _settingPractice ||
        _mappingWork != null ||
        project.sideBySide == value) {
      return;
    }
    project = project.copyWith(sideBySide: value);
    _dirty = true;
    _notify();
    if (!calibrating) await save();
  }

  Future<void> setSpeed(double speed) async {
    if (_closed || busy) return;
    project = project.copyWith(speed: speed);
    _dirty = true;
    if (await _control(ComparisonCommand.setSpeed, 0)) await save();
  }

  Future<bool> _control(ComparisonCommand command, int value) async {
    if (_closed || playback == null || _mappingWork != null) return false;
    final revision = _revision;
    final request = _openRequest;
    final projectId = project.id;
    final operation = ++_controlRequest;
    bool isCurrent() =>
        _ownsPlayer(request, projectId) &&
        revision == _revision &&
        operation == _controlRequest;
    try {
      final state = await gateway.control(command, value, project.speed);
      if (!isCurrent()) return false;
      playback = state;
      project = project.copyWith(positionUs: state.positionUs);
      _dirty = true;
      error = state.error;
      _notify();
      return error == null;
    } catch (_) {
      if (isCurrent()) {
        error = '播放操作未完成，请重新打开项目或重新关联视频。';
        _notify();
      }
      return false;
    }
  }

  Future<void> swap() async {
    if (!project.hasPair || busy) return;
    await pause();
    await gateway.cancelAlignment();
    _invalidatePlayer();
    project = project.swapSources(referenceStartUs: playback?.teacherStartUs);
    final expectedStart = project.musicMapping
        .overlap(project.teacher!.durationUs, project.learner!.durationUs)
        ?.$1;
    playback = null;
    _dirty = true;
    await _open();
    if (playback != null &&
        playback!.durationUs > 0 &&
        expectedStart != playback!.teacherStartUs) {
      project = project.withMusicMapping(
        project.musicMapping,
        oldReferenceStartUs: expectedStart,
        newReferenceStartUs: playback!.teacherStartUs,
        newDurationUs: playback!.durationUs,
      );
      playback = await gateway.setPractice(project);
      await _control(ComparisonCommand.seek, project.positionUs);
    }
    await save();
    _notify();
  }

  Future<void> selectProject(ComparisonProject value) async {
    await cancelMatching();
    await pause();
    await save();
    if (!_lastSaveSucceeded) return;
    _invalidatePlayer();
    await gateway.close();
    project = value;
    playback = null;
    message = '已恢复项目；源视频不可用时可点击重新关联。';
    error = null;
    await _open();
    if (project.hasPair && project.alignment == 'unmatched') await matchMusic();
    _notify();
  }

  Future<void> newProject() async {
    await cancelMatching();
    await pause();
    await save();
    if (!_lastSaveSucceeded) return;
    _invalidatePlayer();
    await gateway.close();
    project = _newProject();
    playback = null;
    message = '选择参考和目标视频，按共同音乐开始对比。';
    error = null;
    _notify();
  }

  Future<void> save({String? title}) async {
    final projectId = project.id;
    await _mappingWork;
    if (project.id != projectId) return;
    if (_closed || (project.teacher == null && project.learner == null)) return;
    if (title != null && title.trim().isNotEmpty) {
      project = project.copyWith(title: title.trim());
    }
    final snapshot = project;
    if (await _persist(snapshot) && identical(snapshot, project)) {
      _dirty = false;
    }
  }

  Future<bool> _persist(
    ComparisonProject snapshot, {
    bool Function()? isCurrent,
  }) async {
    _pendingSaves++;
    saving = true;
    _notify();
    var written = false;
    // Serialize writes so an old autosave cannot overwrite a newer manual calibration.
    final result = _writeTail.catchError((Object _) {}).then((_) async {
      if (isCurrent != null && !isCurrent()) return;
      await gateway.save(snapshot);
      written = true;
    });
    _writeTail = result;
    try {
      await result;
      if (!written) return false;
      _lastSaveSucceeded = true;
      if (_closed) return true;
      projects = [snapshot, ...projects.where((p) => p.id != snapshot.id)]
        ..sort((a, b) => b.updatedAtMs.compareTo(a.updatedAtMs));
      return true;
    } catch (_) {
      _lastSaveSucceeded = false;
      if (!_closed &&
          project.id == snapshot.id &&
          (isCurrent == null || isCurrent())) {
        error = '项目保存失败，请重试后再离开。';
      }
      return false;
    } finally {
      saving = --_pendingSaves > 0;
      _notify();
    }
  }

  Future<void> background() async {
    await pause();
    if (!adjusting) await save();
  }

  Future<bool> finish() async {
    await cancelMatching();
    await pause();
    await gateway.cancelAlignment();
    await save();
    await _writeTail.catchError((Object _) {});
    if (!_lastSaveSucceeded) return false;
    _invalidatePlayer();
    await gateway.close();
    return true;
  }

  @override
  void dispose() {
    _closed = true;
    _invalidatePlayer();
    _poll?.cancel();
    _autosave?.cancel();
    _resize?.cancel();
    _pictureTimer?.cancel();
    _picturePending = null;
    unawaited(gateway.cancelAlignment());
    unawaited(gateway.close());
    super.dispose();
  }
}
