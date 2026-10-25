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
  bool ready = false, _closed = false;
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
      final teacherUs = max(0, -project.offsetUs) + project.positionUs;
      await Future.wait([
        seek(0, teacherUs),
        seek(1, teacherUs + project.offsetUs),
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
    if (_closed) return;
    await Future.wait(videos.map((v) => v.pause()));
  }

  Future<void> toggle(int role) async {
    if (!ready || _closed) return;
    await videos[1 - role].pause();
    if (videos[role].value.isPlaying) {
      await videos[role].pause();
    } else {
      if (position(role) >= duration(role) - 100000) await seek(role, 0);
      await videos[role].play();
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
    await pause();
    await Future.wait([for (var i = 0; i < 2; i++) seek(i, position(i))]);
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
    await Future.wait(videos.map((v) => v.dispose()));
    super.dispose();
  }
}
