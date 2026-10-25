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
  List<MusicCandidateDto> candidates = [];
  String message = '选择老师和自己的视频，按共同音乐开始对比。';
  String? error;
  bool initialized = false, picking = false, matching = false, saving = false;
  bool _closed = false, _opening = false, _polling = false, _dirty = false;
  int _revision = 0, _width = 0, _height = 0;
  bool _horizontal = false;
  bool _lastSaveSucceeded = true;
  int _openRequest = 0;
  Timer? _poll, _autosave, _resize, _audition;
  Future<void> _writeTail = Future.value();
  bool get busy => picking || matching;
  bool get playing =>
      playback?.phase == 'playing' || playback?.phase == 'buffering';
  bool get canPlay =>
      project.hasPair &&
      !busy &&
      playback != null &&
      playback!.phase != 'error';
  void _notify() {
    if (!_closed) notifyListeners();
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
    initialized = true;
    _poll = Timer.periodic(
      const Duration(milliseconds: 200),
      (_) => _refresh(),
    );
    _autosave = Timer.periodic(const Duration(seconds: 3), (_) {
      if (_dirty && !busy) unawaited(save());
    });
    _notify();
    await _open();
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
    _resize?.cancel();
    _resize = Timer(const Duration(milliseconds: 180), () async {
      if (_closed || !initialized || !project.hasPair) return;
      if (playback == null) {
        await _open();
        return;
      }
      try {
        playback = await gateway.resize(_width, _height, _horizontal);
        _notify();
      } catch (_) {
        if (!_closed) {
          error = '画面调整失败，请重新打开此项目。';
          _notify();
        }
      }
    });
  }

  Future<void> _open() async {
    if (_closed ||
        !initialized ||
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
      if (!_closed && revision == _revision) {
        playback = state;
        error = state.error;
      }
    } catch (_) {
      if (!_closed && revision == _revision) {
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
    if (_closed || _polling || _opening || playback == null) return;
    _polling = true;
    final revision = _revision;
    try {
      final state = await gateway.state();
      if (_closed || revision != _revision) return;
      final previous = playback;
      final changed =
          previous == null ||
          previous.textureId != state.textureId ||
          previous.phase != state.phase ||
          previous.positionUs != state.positionUs ||
          previous.durationUs != state.durationUs ||
          previous.teacherStartUs != state.teacherStartUs ||
          previous.offsetUs != state.offsetUs ||
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
    _audition?.cancel();
    _notify();
    try {
      final source = await gateway.pick(role);
      if (_closed || source == null) return;
      final previous = role == 'teacher' ? project.teacher : project.learner;
      if (source.uri == previous?.uri) {
        await _open();
        return;
      }
      await gateway.cancelAlignment();
      _revision++;
      project = project.copyWith(
        teacher: role == 'teacher' ? source : null,
        learner: role == 'learner' ? source : null,
        offsetUs: 0,
        alignment: 'unmatched',
        clearAnchors: true,
        positionUs: 0,
      );
      candidates = [];
      playback = null;
      _dirty = true;
      await save();
      await _open();
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
    message = '正在设备上分析共同音乐…';
    candidates = [];
    _audition?.cancel();
    final revision = ++_revision;
    _notify();
    try {
      final result = await gateway.align(project);
      if (_closed || revision != _revision) return;
      candidates = result.candidates;
      message = result.message;
      if (result.status == 'matched' && candidates.isNotEmpty) {
        await applyCandidate(candidates.first, audition: false);
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
    _revision++;
    matching = false;
    await gateway.cancelAlignment();
    message = '已取消音乐匹配，可以手动校准。';
    _notify();
  }

  Future<void> applyCandidate(
    MusicCandidateDto candidate, {
    bool audition = true,
  }) async {
    final teacher = project.teacher, learner = project.learner;
    if (teacher == null ||
        learner == null ||
        min(teacher.durationUs, learner.durationUs - candidate.offsetUs) <=
            max(0, -candidate.offsetUs)) {
      error = '此音乐位置没有公共视频区间，请手动校准。';
      _notify();
      return;
    }
    project = project.copyWith(
      offsetUs: candidate.offsetUs,
      alignment: audition ? 'candidate' : 'auto',
      clearAnchors: true,
      positionUs: 0,
    );
    _dirty = true;
    await _control(ComparisonCommand.setOffset, candidate.offsetUs);
    await _control(ComparisonCommand.seek, 0);
    await save();
    if (audition && !_closed) {
      message = '已试用此音乐位置，可播放比较或手动微调。';
      await _control(ComparisonCommand.play, 0);
      _audition?.cancel();
      _audition = Timer(const Duration(seconds: 8), pause);
    }
    _notify();
  }

  Future<void> manual(int teacherUs, int learnerUs) async {
    if (!project.hasPair ||
        teacherUs < 0 ||
        learnerUs < 0 ||
        teacherUs >= project.teacher!.durationUs ||
        learnerUs >= project.learner!.durationUs) {
      throw const FormatException('时间必须位于各自视频内');
    }
    await cancelMatching();
    project = project.copyWith(
      offsetUs: learnerUs - teacherUs,
      alignment: 'manual',
      teacherAnchorUs: teacherUs,
      learnerAnchorUs: learnerUs,
      positionUs: 0,
    );
    _dirty = true;
    candidates = [];
    await _control(ComparisonCommand.setOffset, project.offsetUs);
    final start = playback?.teacherStartUs ?? 0;
    await _control(ComparisonCommand.seek, max(0, teacherUs - start));
    message = '已应用手动音乐锚点，人物动作的早晚差异会保留。';
    await save();
    _notify();
  }

  Future<void> toggle() async {
    _audition?.cancel();
    await _control(
      playing ? ComparisonCommand.pause : ComparisonCommand.play,
      0,
    );
  }

  Future<void> pause() async {
    _audition?.cancel();
    await _control(ComparisonCommand.pause, 0);
  }

  Future<void> seek(int positionUs) async {
    _audition?.cancel();
    await _control(ComparisonCommand.seek, positionUs);
  }

  Future<void> setSpeed(double speed) async {
    project = project.copyWith(speed: speed);
    _dirty = true;
    await _control(ComparisonCommand.setSpeed, 0);
    await save();
  }

  Future<void> _control(ComparisonCommand command, int value) async {
    if (_closed || playback == null) return;
    try {
      playback = await gateway.control(command, value, project.speed);
      project = project.copyWith(positionUs: playback!.positionUs);
      _dirty = true;
      error = playback!.error;
    } catch (_) {
      if (!_closed) error = '播放操作未完成，请重新打开项目或重新关联视频。';
    }
    _notify();
  }

  Future<void> swap() async {
    if (!project.hasPair || busy) return;
    await pause();
    await gateway.cancelAlignment();
    _revision++;
    project = project.swapSources();
    playback = null;
    candidates = [];
    _dirty = true;
    await _open();
    await save();
    _notify();
  }

  Future<void> selectProject(ComparisonProject value) async {
    await pause();
    await save();
    if (!_lastSaveSucceeded) return;
    await gateway.cancelAlignment();
    _revision++;
    await gateway.close();
    project = value;
    playback = null;
    candidates = [];
    message = '已恢复项目；源视频不可用时可点击重新关联。';
    error = null;
    await _open();
    _notify();
  }

  Future<void> newProject() async {
    await pause();
    await save();
    if (!_lastSaveSucceeded) return;
    await gateway.cancelAlignment();
    _revision++;
    await gateway.close();
    project = _newProject();
    playback = null;
    candidates = [];
    message = '选择老师和自己的视频，按共同音乐开始对比。';
    error = null;
    _notify();
  }

  Future<void> save({String? title}) async {
    if (_closed || (project.teacher == null && project.learner == null)) return;
    if (title != null && title.trim().isNotEmpty) {
      project = project.copyWith(title: title.trim());
    }
    final snapshot = project;
    saving = true;
    _notify();
    // Serialize writes so an old autosave cannot overwrite a newer manual calibration.
    final result = _writeTail
        .catchError((Object _) {})
        .then((_) => gateway.save(snapshot));
    _writeTail = result;
    try {
      await result;
      _lastSaveSucceeded = true;
      if (_closed) return;
      if (identical(snapshot, project)) _dirty = false;
      projects = [snapshot, ...projects.where((p) => p.id != snapshot.id)]
        ..sort((a, b) => b.updatedAtMs.compareTo(a.updatedAtMs));
    } catch (_) {
      _lastSaveSucceeded = false;
      if (!_closed) error = '项目保存失败，请重试后再离开。';
    } finally {
      saving = false;
      _notify();
    }
  }

  Future<void> background() async {
    await pause();
    await save();
  }

  Future<bool> finish() async {
    _revision++;
    matching = false;
    await pause();
    await gateway.cancelAlignment();
    await save();
    await _writeTail.catchError((Object _) {});
    if (!_lastSaveSucceeded) return false;
    await gateway.close();
    return true;
  }

  @override
  void dispose() {
    _closed = true;
    _revision++;
    _poll?.cancel();
    _autosave?.cancel();
    _resize?.cancel();
    _audition?.cancel();
    unawaited(gateway.cancelAlignment());
    unawaited(gateway.close());
    super.dispose();
  }
}
