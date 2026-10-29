import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:app/app/app.dart';
import 'package:app/app/router.dart';
import 'package:app/app/system_ui.dart';
import 'package:dance_domain/dance_domain.dart';
import 'package:dance_native/dance_native.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:video_player/video_player.dart';

// Real iOS host integration entry, with no channel mocks or test dependencies.
// Build with --target=test_driver/comparison_ios_smoke.dart. Place the two
// music-rate VFR fixtures in the app's Documents/comparison-fixtures directory.
// This entry only creates its own project/job in the isolated validation app.
Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  if (const bool.fromEnvironment('COMPARISON_SMOKE_RESTORE_ONLY')) {
    await applyAppImmersiveMode();
    runApp(const ProviderScope(child: WoahApp()));
    await WidgetsBinding.instance.endOfFrame;
    appRouter.go('/comparison');
    return;
  }
  final smoke = _ComparisonSmoke();
  runApp(_SmokeView(smoke));
  await WidgetsBinding.instance.endOfFrame;
  await smoke.run();
}

class _ComparisonSmoke extends ChangeNotifier {
  final api = DanceComparisonApi();
  final stages = <Map<String, Object?>>[];
  String current = 'waiting_fixtures';
  int? texture;
  bool finished = false;
  ComparisonExportDto? exported;
  late final File report;
  late ComparisonProject project;

  Future<T> host<T>(Future<T> value, {int seconds = 20}) =>
      value.timeout(Duration(seconds: seconds));

  void require(bool value, String message) {
    if (!value) throw StateError(message);
  }

  List<String> differences(
    Object? expected,
    Object? actual, [
    String path = r'$',
  ]) {
    if (expected is Map && actual is Map) {
      final found = <String>[];
      for (final key in {...expected.keys, ...actual.keys}) {
        if (!expected.containsKey(key) || !actual.containsKey(key)) {
          found.add('$path.$key missing key');
        } else {
          found.addAll(differences(expected[key], actual[key], '$path.$key'));
        }
      }
      return found;
    }
    if (expected is List && actual is List) {
      if (expected.length != actual.length) {
        return ['$path list length changed'];
      }
      return [
        for (var i = 0; i < expected.length; i++)
          ...differences(expected[i], actual[i], '$path[$i]'),
      ];
    }
    // Foundation may standardize /private/tmp to /tmp. Require the same
    // existing file identity for source URI aliases, keeping other fields exact.
    if (expected is String &&
        actual is String &&
        (path.endsWith('.uri') || path.endsWith('.sourceUri')) &&
        expected.startsWith('file://') &&
        actual.startsWith('file://')) {
      try {
        if (FileSystemEntity.identicalSync(
          Uri.parse(expected).toFilePath(),
          Uri.parse(actual).toFilePath(),
        )) {
          return [];
        }
      } on FileSystemException {
        // Unavailable or different files still fail the restoration assertion.
      }
    }
    return expected == actual
        ? []
        : ['$path expected=$expected actual=$actual'];
  }

  void checkpoint(String key, Object? value) {
    stages.last[key] = value;
    publish();
  }

  Map<String, Object?> state(ComparisonPlaybackDto s) => {
    'phase': s.phase,
    'textureId': s.textureId,
    'positionUs': s.positionUs,
    'durationUs': s.durationUs,
    'referenceStartUs': s.teacherStartUs,
    'referenceFrameUs': s.teacherFrameUs,
    'targetFrameUs': s.learnerFrameUs,
    'offsetUs': s.offsetUs,
    'timeScale': s.timeScale,
    'speed': s.speed,
    'error': s.error,
  };

  void publish() {
    report.writeAsStringSync(
      const JsonEncoder.withIndent('  ').convert({
        'platform': Platform.operatingSystem,
        'tempPath': Directory.systemTemp.path,
        'updatedAt': DateTime.now().toUtc().toIso8601String(),
        'current': current,
        'finished': finished,
        'passed': finished && stages.every((s) => s['status'] == 'passed'),
        'stages': stages,
      }),
      flush: true,
    );
    notifyListeners();
  }

  Future<void> stage(
    String name,
    Future<Map<String, Object?>> Function() body,
  ) async {
    current = name;
    final entry = <String, Object?>{'name': name, 'status': 'running'};
    stages.add(entry);
    publish();
    final watch = Stopwatch()..start();
    try {
      entry.addAll(await body());
      entry['status'] = 'passed';
    } catch (error, stack) {
      entry.addAll({
        'status': 'failed',
        'error': error.toString(),
        'stack': stack.toString(),
      });
    }
    entry['elapsedMs'] = watch.elapsedMilliseconds;
    publish();
  }

  ComparisonPracticeDto practice(ComparisonProject p) => ComparisonPracticeDto(
    mirrorTeacher: p.mirrorTeacher,
    mirrorLearner: p.mirrorLearner,
    teacherPicture: ComparisonPictureDto(
      zoom: p.teacherPicture.zoom,
      panX: p.teacherPicture.panX,
      panY: p.teacherPicture.panY,
    ),
    learnerPicture: ComparisonPictureDto(
      zoom: p.learnerPicture.zoom,
      panX: p.learnerPicture.panX,
      panY: p.learnerPicture.panY,
    ),
    loopStartUs: p.loopStartUs,
    loopEndUs: p.loopEndUs,
    loopEnabled: p.loopEnabled,
  );

  Future<ComparisonPlaybackDto> open(ComparisonProject p) async {
    final s = await host(
      api.open(
        p.teacher!.uri,
        p.learner!.uri,
        p.offsetUs,
        p.positionUs,
        p.speed,
        320,
        240,
        p.sideBySide ?? true,
        practice(p),
        p.timeScale,
      ),
    );
    texture = s.textureId;
    notifyListeners();
    await WidgetsBinding.instance.endOfFrame;
    checkpoint('opened', state(s));
    require(
      s.textureId >= 0 && s.phase == 'paused',
      'Open did not become ready',
    );
    require(
      s.teacherFrameUs != null && s.learnerFrameUs != null && s.error == null,
      'Open returned without composed source-frame evidence',
    );
    require((s.timeScale! - p.timeScale).abs() < 1e-9, 'Rate mapping changed');
    require(
      (s.positionUs - p.positionUs).abs() <= 2,
      'Open did not restore requested position',
    );
    return s;
  }

  Future<ComparisonPlaybackDto> command(
    ComparisonCommand command, {
    int position = 0,
    double speed = 1,
  }) => host(api.control(command, position, speed));

  Future<ComparisonPlaybackDto> playing() async {
    final deadline = DateTime.now().add(const Duration(seconds: 10));
    while (true) {
      final s = await host(api.getState());
      require(s.error == null, 'Playback error: ${s.error}');
      if (s.phase == 'playing') return s;
      require(DateTime.now().isBefore(deadline), 'Audio clock never started');
      await Future<void>.delayed(const Duration(milliseconds: 50));
    }
  }

  Future<void> run() async {
    // iOS Dart has no HOME environment variable. Its NSTemporaryDirectory is
    // the app container's tmp directory, alongside Documents.
    final home = Directory.systemTemp.parent.path;
    final documents = Directory('$home/Documents')..createSync(recursive: true);
    report = File('${documents.path}/comparison-ios-report.json');
    final referenceFixture = File(
      '${documents.path}/comparison-fixtures/teacher.mp4',
    );
    final targetFixture = File(
      '${documents.path}/comparison-fixtures/learner.mp4',
    );
    publish();
    final deadline = DateTime.now().add(const Duration(seconds: 60));
    while (!referenceFixture.existsSync() || !targetFixture.existsSync()) {
      if (DateTime.now().isAfter(deadline)) {
        await stage('fixtures', () async {
          throw StateError('Required teacher.mp4 / learner.mp4 not supplied');
        });
        finished = true;
        publish();
        return;
      }
      await Future<void>.delayed(const Duration(milliseconds: 250));
    }
    // Mirror the picker's owned-file namespace. This also exercises stable
    // media/marker references when installing a new build relocates the container.
    final media = Directory(
      '$home/Library/Application Support/WoahComparisonMedia',
    )..createSync(recursive: true);
    final reference = await referenceFixture.copy(
      '${media.path}/00000000-0000-4000-8000-000000000001.mp4',
    );
    final target = await targetFixture.copy(
      '${media.path}/00000000-0000-4000-8000-000000000002.mp4',
    );
    final referenceUri = reference.uri.toString();
    final targetUri = target.uri.toString();
    project = ComparisonProject(
      id: 'ios_native_smoke',
      title: 'iOS 原生集成回归',
      teacher: ComparisonSource(
        uri: referenceUri,
        name: 'VFR reference',
        durationUs: 23000000,
        hasAudio: true,
      ),
      learner: ComparisonSource(
        uri: targetUri,
        name: 'rate target',
        durationUs: 20000000,
        hasAudio: true,
      ),
      offsetUs: 1200000,
      timeScale: .8,
      alignment: 'manual',
      sideBySide: true,
      updatedAtMs: DateTime.now().millisecondsSinceEpoch,
    );

    await stage('picker_open_cancel', () async {
      final selection = host(api.pickVideo('teacher'));
      await Future<void>.delayed(const Duration(milliseconds: 600));
      await host(api.close());
      require(await selection == null, 'Cancelled picker returned a source');
      return {'cancelled': true};
    });
    await stage('music_alignment', () async {
      final match = await host(
        api.alignMusic(referenceUri, targetUri),
        seconds: 60,
      );
      require(
        match.status == 'matched',
        'Music status: ${match.status}: ${match.message}',
      );
      require(match.candidates.isNotEmpty, 'Music did not return evidence');
      final best = match.candidates.first;
      require((best.timeScale! - .8).abs() <= .01, 'Wrong music rate');
      require((best.offsetUs - 1200000).abs() <= 100000, 'Wrong music offset');
      return {
        'statusFromNative': match.status,
        'offsetUs': best.offsetUs,
        'timeScale': best.timeScale,
        'residualMs': best.residualMs,
        'windows': best.consistentWindows,
      };
    });
    await stage('overlapping_alignment_cancel', () async {
      final first = host(api.alignMusic(referenceUri, targetUri), seconds: 60);
      await Future<void>.delayed(const Duration(milliseconds: 20));
      final replacement = host(
        api.alignMusic(referenceUri, targetUri),
        seconds: 60,
      );
      await host(api.cancelAlignment());
      final results = await Future.wait([first, replacement]);
      require(
        results.every((r) => r.status == 'cancelled' && r.candidates.isEmpty),
        'Overlapping music request revived after cancellation',
      );
      await Future<void>.delayed(const Duration(milliseconds: 100));
      final idle = await host(api.getState());
      require(idle.phase == 'closed', 'Cancelled matching opened playback');
      return {'statuses': results.map((r) => r.status).toList()};
    });
    await stage('open_texture', () async {
      final s = await open(project);
      require((s.durationUs - 23000000).abs() < 100000, 'Wrong shared overlap');
      return state(s);
    });
    await stage('play_half_speed_pause', () async {
      await command(ComparisonCommand.setSpeed, speed: .5);
      await command(ComparisonCommand.play);
      final before = await playing();
      await Future<void>.delayed(const Duration(milliseconds: 600));
      final after = await command(ComparisonCommand.pause);
      final advance = after.positionUs - before.positionUs;
      require(
        after.phase == 'paused' && after.speed == .5,
        'Pause/speed state wrong',
      );
      require(
        advance >= 150000 && advance <= 650000,
        'Wrong half-speed clock advance: $advance',
      );
      require(after.timeScale == .8, 'Practice speed changed music mapping');
      await command(ComparisonCommand.setSpeed);
      return {...state(after), 'clockAdvanceUs': advance};
    });
    await stage('seek_real_pts_step', () async {
      final seek = await command(ComparisonCommand.seek, position: 280000);
      checkpoint('seek', state(seek));
      final forward = await host(api.stepFrame(true));
      checkpoint('forward', state(forward));
      final backward = await host(api.stepFrame(false));
      checkpoint('backward', state(backward));
      require(
        (seek.teacherFrameUs! - 266667).abs() <= 2,
        'Seek did not show actual source PTS',
      );
      require(
        (forward.teacherFrameUs! - 333333).abs() <= 2,
        'Step crossed a missing VFR sample incorrectly',
      );
      require(
        (backward.teacherFrameUs! - 266667).abs() <= 2,
        'Backward step was not adjacent PTS',
      );
      require(
        (forward.positionUs - backward.positionUs).abs() > 50000,
        'Step used nominal frame duration',
      );
      return {
        'seek': state(seek),
        'forward': state(forward),
        'backward': state(backward),
      };
    });
    await stage('layout_resize', () async {
      final stacked = await host(api.resize(320, 240, false));
      checkpoint('stacked', state(stacked));
      require(
        stacked.phase == 'paused' && stacked.teacherFrameUs != null,
        'Stacked rendering failed',
      );
      final horizontal = await host(api.resize(320, 240, true));
      checkpoint('sideBySide', state(horizontal));
      require(
        horizontal.phase == 'paused' && horizontal.learnerFrameUs != null,
        'Side-by-side rendering failed',
      );
      require(
        (horizontal.positionUs - stacked.positionUs).abs() < 2,
        'Layout changed source position',
      );
      return {'stacked': state(stacked), 'sideBySide': state(horizontal)};
    });
    await stage('independent_picture_and_loop', () async {
      project = project.copyWith(
        mirrorTeacher: true,
        mirrorLearner: false,
        teacherPicture: const ComparisonPicture(zoom: 1.4, panX: .2, panY: -.3),
        learnerPicture: const ComparisonPicture(
          zoom: 1.8,
          panX: -.25,
          panY: .4,
        ),
        loopStartUs: 1000000,
        loopEndUs: 2000000,
        loopEnabled: true,
      );
      final referenceMirror = await host(api.setPractice(practice(project)));
      checkpoint('referenceMirror', state(referenceMirror));
      require(
        referenceMirror.teacherFrameUs != null,
        'Reference picture update has no frame',
      );
      project = project.copyWith(mirrorTeacher: false, mirrorLearner: true);
      final targetMirror = await host(api.setPractice(practice(project)));
      checkpoint('targetMirror', state(targetMirror));
      require(
        targetMirror.learnerFrameUs != null && targetMirror.phase == 'paused',
        'Target picture update failed',
      );
      await command(ComparisonCommand.seek, position: 1850000);
      await command(ComparisonCommand.play);
      await playing();
      final positions = <int>[];
      var jumped = false;
      try {
        for (var i = 0; i < 22; i++) {
          await Future<void>.delayed(const Duration(milliseconds: 80));
          final s = await host(api.getState());
          if (positions.isNotEmpty && positions.last - s.positionUs > 500000) {
            jumped = true;
          }
          positions.add(s.positionUs);
          checkpoint('positionsUs', positions);
          checkpoint('loopLatest', state(s));
          require(s.error == null, 'Loop produced playback error');
          require(
            s.positionUs >= 1000000 && s.positionUs < 2000000,
            'Clock escaped A/B range',
          );
        }
      } finally {
        await command(ComparisonCommand.pause);
      }
      require(jumped, 'A/B never jumped back to A');
      return {
        'positionsUs': positions,
        'referenceMirror': state(referenceMirror),
        'targetMirror': state(targetMirror),
      };
    });
    await stage('alignment_mapping', () async {
      final changed = await host(api.setAlignment(1100000, .81));
      require(
        changed.offsetUs == 1100000 && changed.timeScale == .81,
        'Native mapping update lost parameters',
      );
      final restored = await host(api.setAlignment(1200000, .8));
      require(
        restored.offsetUs == 1200000 && restored.timeScale == .8,
        'Mapping restore failed',
      );
      return {'changed': state(changed), 'restored': state(restored)};
    });
    await stage('save_load_project_marker', () async {
      project = project.copyWith(
        positionUs: 1250000,
        markers: [
          ComparisonMarker(
            id: 'late_motion',
            sourceUri: targetUri,
            startUs: 2100000,
            endUs: 2500000,
            note: '动作晚于音乐，保留差异',
          ),
        ],
        teacherAnchorUs: 1000000,
        learnerAnchorUs: 2000000,
        teacherAnchor2Us: 10000000,
        learnerAnchor2Us: 9200000,
      );
      await host(api.saveProject(jsonEncode(project.toJson())));
      final loaded = (await host(api.loadProjects()))
          .map(
            (s) => ComparisonProject.fromJson(
              Map<String, dynamic>.from(jsonDecode(s) as Map),
            ),
          )
          .firstWhere((p) => p.id == project.id);
      final mismatch = differences(project.toJson(), loaded.toJson());
      checkpoint('expectedProject', project.toJson());
      checkpoint('actualProject', loaded.toJson());
      checkpoint('differences', mismatch);
      require(
        mismatch.isEmpty,
        'Project/marker/picture/anchors changed on restoration',
      );
      return {'project': loaded.toJson()};
    });
    await stage('manual_dual_video_backend', () async {
      await host(api.close());
      texture = null;
      final left = VideoPlayerController.file(reference);
      final right = VideoPlayerController.file(target);
      try {
        await host(Future.wait([left.initialize(), right.initialize()]));
        require(
          left.value.isInitialized && right.value.isInitialized,
          'Manual video backends did not initialize',
        );
        await host(
          Future.wait([
            left.seekTo(const Duration(seconds: 1)),
            right.seekTo(const Duration(seconds: 2)),
          ]),
        );
        final leftPosition = await host(left.position);
        final rightPosition = await host(right.position);
        require(
          leftPosition != null &&
              (leftPosition.inMicroseconds - 1000000).abs() < 100000,
          'Reference manual seek did not reach its own anchor',
        );
        require(
          rightPosition != null &&
              (rightPosition.inMicroseconds - 2000000).abs() < 100000,
          'Target manual seek did not reach its independent anchor',
        );
        return {
          'referencePositionUs': leftPosition!.inMicroseconds,
          'targetPositionUs': rightPosition!.inMicroseconds,
          'referenceDurationUs': left.value.duration.inMicroseconds,
          'targetDurationUs': right.value.duration.inMicroseconds,
        };
      } finally {
        await host(left.dispose());
        await host(right.dispose());
      }
    });
    await stage('close_reopen', () async {
      await host(api.close());
      texture = null;
      final s = await open(project);
      require(
        (s.positionUs - 1250000).abs() <= 2,
        'Reopen lost saved position',
      );
      return state(s);
    });
    await stage('export_ab_h264_aac', () async {
      var job = await host(
        api.startExport(
          jsonEncode(project.toJson()),
          1000000,
          2000000,
          320,
          240,
          8,
          true,
        ),
      );
      texture = null;
      final end = DateTime.now().add(const Duration(seconds: 60));
      while (job.state != 'completed') {
        require(
          !['failed', 'cancelled', 'interrupted'].contains(job.state),
          'Export failed: ${job.state}: ${job.error}',
        );
        require(DateTime.now().isBefore(end), 'Export did not complete in 60s');
        await Future<void>.delayed(const Duration(milliseconds: 200));
        final latest = await host(api.getExport());
        require(latest?.jobId == job.jobId, 'Export job identity changed');
        job = latest!;
        stages.last['latest'] = {
          'jobId': job.jobId,
          'state': job.state,
          'stage': job.stage,
          'progress': job.progress,
        };
        publish();
      }
      require(
        job.startUs == 1000000 &&
            job.endUs == 2000000 &&
            job.width == 320 &&
            job.height == 240,
        'Export range/size changed',
      );
      require(job.outputPath != null, 'Completed export has no private output');
      final output = job.outputPath!.startsWith('file:')
          ? File.fromUri(Uri.parse(job.outputPath!))
          : File(job.outputPath!);
      require(
        output.existsSync() && output.lengthSync() > 1024,
        'Completed export file missing/empty',
      );
      exported = job;
      return {
        'jobId': job.jobId,
        'outputPath': output.path,
        'bytes': output.lengthSync(),
        'startUs': job.startUs,
        'endUs': job.endUs,
      };
    });
    await stage('reopen_after_export', () async => state(await open(project)));
    await stage('close_final', () async {
      await host(api.close());
      texture = null;
      return {'closed': true};
    });
    if (const bool.fromEnvironment('COMPARISON_SMOKE_PHOTOS')) {
      await stage('photos_save_deduplicate_open', () async {
        require(exported != null, 'No completed job for Photos');
        final first = await host(api.saveExport(exported!.jobId));
        require(
          first.savedUri?.startsWith('ph://') == true,
          'Photos save did not publish asset identity',
        );
        final second = await host(api.saveExport(exported!.jobId));
        require(
          second.savedUri == first.savedUri,
          'Repeated save created another Photos identity',
        );
        await host(api.openExport(exported!.jobId));
        return {
          'savedUri': first.savedUri,
          'deduplicated': true,
          'localPlaybackOpened': true,
        };
      });
    }
    current = 'complete';
    finished = true;
    publish();
  }
}

class _SmokeView extends StatelessWidget {
  final _ComparisonSmoke smoke;
  const _SmokeView(this.smoke);

  @override
  Widget build(BuildContext context) => MaterialApp(
    home: AnimatedBuilder(
      animation: smoke,
      builder: (context, _) {
        final failed = smoke.stages.any((s) => s['status'] == 'failed');
        return Scaffold(
          backgroundColor: smoke.finished
              ? (failed ? const Color(0xff7f1d1d) : const Color(0xff14532d))
              : const Color(0xff111827),
          body: SafeArea(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Text(
                    smoke.finished
                        ? (failed ? 'iOS SMOKE FAILED' : 'iOS SMOKE PASSED')
                        : 'iOS native: ${smoke.current}',
                    style: const TextStyle(color: Colors.white, fontSize: 20),
                  ),
                  const SizedBox(height: 12),
                  SizedBox(
                    height: 240,
                    child: smoke.texture == null
                        ? const ColoredBox(color: Colors.black)
                        : Texture(textureId: smoke.texture!),
                  ),
                  const SizedBox(height: 12),
                  Expanded(
                    child: ListView(
                      children: [
                        for (final s in smoke.stages)
                          Text(
                            '${s['status']}: ${s['name']}${s['error'] == null ? '' : '\n${s['error']}'}',
                            style: const TextStyle(color: Colors.white),
                          ),
                      ],
                    ),
                  ),
                  if (smoke.finished)
                    TextButton(
                      onPressed: () {
                        runApp(const ProviderScope(child: WoahApp()));
                        appRouter.go('/comparison');
                      },
                      child: const Text(
                        '打开正式对比页',
                        style: TextStyle(color: Colors.white),
                      ),
                    ),
                ],
              ),
            ),
          ),
        );
      },
    ),
  );
}
