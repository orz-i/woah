import 'dart:async';
import 'dart:io';
import 'dart:math';
import 'package:flutter/foundation.dart';
import 'package:video_player/video_player.dart';
import 'package:dance_domain/dance_domain.dart';

/// Independent source previews exist only while the shared comparison is closed.
class ComparisonManualSession extends ChangeNotifier {
  ComparisonManualSession(
    this.project, {
    VideoPlayerController Function(String)? factory,
  }) : videos = [
         (factory ?? _create)(project.teacher!.uri),
         (factory ?? _create)(project.learner!.uri),
       ] {
    rateCalibration =
        project.teacherAnchor2Us != null && project.learnerAnchor2Us != null;
    if (rateCalibration) {
      firstAnchor = (project.teacherAnchorUs!, project.learnerAnchorUs!);
      secondAnchor = (project.teacherAnchor2Us!, project.learnerAnchor2Us!);
      anchorIndex = 1;
    }
    for (final video in videos) {
      video.addListener(_tick);
    }
  }
  final ComparisonProject project;
  final List<VideoPlayerController> videos;
  final List<int?> _scrub = [null, null], _pending = [null, null];
  final List<Timer?> _timers = [null, null];
  final List<Future<void>?> _workers = [null, null];
  final List<bool> _dragging = [false, false];
  final List<bool> _seekSucceeded = [false, false];
  bool ready = false, _closed = false;
  bool rateCalibration = false, anchoring = false;
  int _playRequest = 0;
  Future<void>? _playTail;
  bool _candidateCued = false;
  final List<bool> _auditioned = [false, false];
  bool get candidateAuditioned => _candidateCued && _auditioned.every((v) => v);
  int anchorIndex = 0;
  (int, int)? firstAnchor, secondAnchor;
  void setRateCalibration(bool value) {
    if (anchoring) return;
    rateCalibration = value;
    if (value && firstAnchor == null) anchorIndex = 0;
    _tick();
  }

  Future<void> selectAnchor(int index) async {
    if (anchoring) return;
    await pause();
    anchorIndex = index;
    final anchor = index == 0 ? firstAnchor : secondAnchor;
    if (anchor != null) {
      await Future.wait([seek(0, anchor.$1), seek(1, anchor.$2)]);
    }
    _tick();
  }

  Future<void> recordFirstAnchor() async {
    if (anchoring || _closed) return;
    anchoring = true;
    _tick();
    try {
      final value = await anchors();
      if (duration(0) - value.$1 < 2001000 ||
          duration(1) - value.$2 < 2001000) {
        error = '第一组音乐点太靠近片尾，请向前选择，给第二组留出至少 2 秒';
        _tick();
        return;
      }
      firstAnchor = value;
      anchorIndex = 1;
      final reference =
          secondAnchor?.$1 ??
          value.$1 + ((duration(0) - value.$1) * .6).round();
      final target =
          secondAnchor?.$2 ??
          ComparisonMusicMapping(
            value.$2,
            project.timeScale,
          ).target(reference - value.$1);
      await Future.wait([seek(0, reference), seek(1, target)]);
      _tick();
    } catch (_) {
      if (!_closed) error = '音乐点定位未完成，请重新预览后重试。';
    } finally {
      anchoring = false;
      _tick();
    }
  }

  String? error;
  static VideoPlayerController _create(String source) =>
      source.startsWith('content://')
      ? VideoPlayerController.contentUri(Uri.parse(source))
      : VideoPlayerController.file(
          File(
            source.startsWith('file://')
                ? Uri.parse(source).toFilePath()
                : source,
          ),
        );
  void _tick() {
    if (_closed) return;
    if (videos.any((v) => v.value.hasError)) error = '预览无法打开，请返回后重新关联视频。';
    notifyListeners();
  }

  int duration(int role) => min(
    videos[role].value.duration.inMicroseconds,
    (role == 0 ? project.teacher : project.learner)!.durationUs,
  );
  int position(int role) =>
      _scrub[role] ??
      videos[role].value.position.inMicroseconds.clamp(
        0,
        max(0, duration(role) - 1000),
      );
  Future<void> initialize() async {
    try {
      await Future.wait(videos.map((v) => v.initialize()));
      if (_closed) return;
      final start =
          project.musicMapping
              .overlap(project.teacher!.durationUs, project.learner!.durationUs)
              ?.$1 ??
          0;
      final teacherUs = secondAnchor?.$1 ?? start + project.positionUs;
      await Future.wait([
        seek(0, teacherUs),
        seek(1, secondAnchor?.$2 ?? project.musicMapping.target(teacherUs)),
      ]);
      if (_closed) return;
      ready = true;
      _tick();
    } catch (_) {
      if (!_closed) {
        error = '预览无法打开，请返回后重新关联视频。';
        _tick();
      }
    }
  }

  Future<void> pause() async {
    _playRequest++;
    if (_closed) return;
    final playing = _playTail;
    if (playing != null) await playing;
    if (_closed) return;
    await Future.wait(videos.map((v) => v.pause()));
  }

  Future<void> toggle(int role) {
    if (!ready || _closed || anchoring) return Future.value();
    final request = ++_playRequest;
    bool current() => !_closed && request == _playRequest;
    final previous = _playTail;
    final work = () async {
      if (previous != null) await previous;
      if (!current()) return;
      error = null;
      try {
        await videos[1 - role].pause();
        if (!current()) return;
        if (videos[role].value.isPlaying) {
          await videos[role].pause();
        } else {
          if (position(role) >= duration(role) - 100000) await seek(role, 0);
          if (current()) {
            await videos[role].play();
            if (current() && _candidateCued && !videos[role].value.hasError) {
              _auditioned[role] = true;
              _tick();
            }
          }
        }
      } catch (_) {
        if (current()) {
          error = '试听未完成，请重试。';
          _tick();
        }
      }
    }();
    late final Future<void> completion;
    completion = work.whenComplete(() {
      if (identical(_playTail, completion)) _playTail = null;
    });
    _playTail = completion;
    return completion;
  }

  /// Cue both sources, but play them one at a time so the user can hear each
  /// candidate. The shared session and saved mapping stay untouched.
  Future<bool> cueCandidate(
    ComparisonMusicMapping mapping,
    List<ComparisonMusicMapping> options,
  ) async {
    if (!ready || _closed || anchoring) return false;
    _candidateCued = false;
    _auditioned.fillRange(0, 2, false);
    final referenceEnd = max(0, duration(0) - 1000);
    final targetEnd = max(0, duration(1) - 1000);
    final range = mapping.overlap(referenceEnd, targetEnd);
    if (range == null) {
      error = '此候选没有可试听的共同区间。';
      _tick();
      return false;
    }
    var (start, end) = range;
    for (final option in options) {
      final other = option.overlap(referenceEnd, targetEnd);
      if (other == null) continue;
      start = max(start, other.$1);
      end = min(end, other.$2);
    }
    // Use the same reference phrase for all candidates when possible.
    if (start >= end) (start, end) = range;
    final cue = start + (end - start) ~/ 2;
    anchoring = true;
    _tick();
    try {
      await pause();
      await Future.wait([seek(0, cue), seek(1, mapping.target(cue))]);
      if (_closed) return false;
      if (!_seekSucceeded.every((succeeded) => succeeded) || error != null) {
        error = '候选乐段定位未完成，请重新选择后试听。';
        return false;
      }
      _candidateCued = true;
      return true;
    } catch (_) {
      if (!_closed) error = '候选乐段定位未完成，请重新选择后试听。';
      return false;
    } finally {
      anchoring = false;
      _tick();
    }
  }

  void scrub(int role, int targetUs) {
    if (!ready || _closed) return;
    _dragging[role] = true;
    _scrub[role] = targetUs.clamp(0, max(0, duration(role) - 1000));
    _tick();
    // One latest preview per 70ms, never a growing seek queue during a drag.
    _timers[role] ??= Timer(const Duration(milliseconds: 70), () {
      _timers[role] = null;
      if (!_closed && _scrub[role] != null) {
        unawaited(_seek(role, _scrub[role]!));
      }
    });
  }

  Future<void> seek(int role, int targetUs) {
    _dragging[role] = false;
    _timers[role]?.cancel();
    _timers[role] = null;
    return _seek(role, targetUs);
  }

  Future<void> _seek(int role, int targetUs) {
    if (_closed) return Future.value();
    _seekSucceeded[role] = false;
    error = null;
    _scrub[role] = targetUs.clamp(0, max(0, duration(role) - 1000));
    _pending[role] = _scrub[role];
    _tick();
    if (_workers[role] != null) return _workers[role]!;
    final work = () async {
      try {
        await videos[role].pause();
        while (!_closed && _pending[role] != null) {
          final next = _pending[role]!;
          _pending[role] = null;
          await videos[role].seekTo(Duration(microseconds: next));
        }
        _seekSucceeded[role] = !_closed;
      } catch (_) {
        if (!_closed) error = '此位置暂时无法预览，请再拖动一次。';
      } finally {
        _workers[role] = null;
        if (!_dragging[role]) _scrub[role] = null;
        _tick();
      }
    }();
    _workers[role] = work;
    return work;
  }

  Future<(int, int)> anchors() async {
    if (_closed || !ready) throw StateError('音乐点预览尚未就绪。');
    await pause();
    await Future.wait([for (var i = 0; i < 2; i++) seek(i, position(i))]);
    if (_closed ||
        !_seekSucceeded.every((succeeded) => succeeded) ||
        error != null ||
        videos.any((video) => video.value.hasError)) {
      throw StateError('音乐点定位未完成，请重试。');
    }
    return (position(0), position(1));
  }

  Future<void> close() async {
    if (_closed) return;
    _closed = true;
    for (var i = 0; i < 2; i++) {
      _timers[i]?.cancel();
      _pending[i] = null;
      videos[i].removeListener(_tick);
    }
    await Future.wait([for (final work in _workers) ?work]);
    final playing = _playTail;
    if (playing != null) await playing;
    await Future.wait(videos.map((v) => v.dispose()));
    super.dispose();
  }
}
