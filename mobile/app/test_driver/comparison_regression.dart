import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;
import 'package:dance_domain/dance_domain.dart';
import 'package:dance_native/dance_native.dart';
import 'package:flutter/material.dart';
import 'package:video_player/video_player.dart';

/// Same real bridge/media/renderer/encoder test on Android and iOS, without mocks.
/// Install manifest + media in Documents/comparison-core-corpus before launch.
Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final qa = ComparisonRegression();
  runApp(
    MaterialApp(
      home: Scaffold(
        body: SafeArea(
          child: AnimatedBuilder(
            animation: qa,
            builder: (_, _) => Column(
              children: [
                Text('${Platform.operatingSystem}: ${qa.current}'),
                SizedBox(
                  width: 320,
                  height: 240,
                  child: qa.texture == null
                      ? const ColoredBox(color: Colors.black)
                      : Texture(textureId: qa.texture!),
                ),
                Expanded(
                  child: ListView(
                    children: [
                      for (final r in qa.results)
                        Text('${r['status']}: ${r['id']}'),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    ),
  );
  await WidgetsBinding.instance.endOfFrame;
  await qa.run();
}

class ComparisonRegression extends ChangeNotifier {
  static const selection = String.fromEnvironment('COMPARISON_QA_FILTER');
  final api = DanceComparisonApi();
  final results = <Map<String, Object?>>[];
  final exports = <Map<String, Object?>>[];
  late Directory documents, corpus, outputs;
  late Map<String, dynamic> manifest;
  final uris = <String, String>{};
  String current = 'waiting_corpus';
  int? texture;
  bool finished = false;

  void require(bool condition, String message) {
    if (!condition) {
      throw StateError(message);
    }
  }

  Future<T> host<T>(Future<T> value, {int seconds = 40}) =>
      value.timeout(Duration(seconds: seconds));
  void publish() {
    final file = File('${documents.path}/comparison-core-report.json');
    final temporary = File('${file.path}.tmp');
    temporary.writeAsStringSync(
      jsonEncode({
        'schema': 'woah.comparison.core-runtime.v1',
        'platform': Platform.operatingSystem,
        'selection': selection,
        'partial': selection.isNotEmpty,
        'current': current,
        'finished': finished,
        'passed': finished && results.every((r) => r['status'] == 'passed'),
        'results': results,
        'exports': exports,
      }),
      flush: true,
    );
    temporary.renameSync(file.path);
    notifyListeners();
  }

  Future<void> stage(
    String id,
    Future<Map<String, Object?>> Function() body,
  ) async {
    if (selection.isNotEmpty && !RegExp(selection).hasMatch(id)) {
      return;
    }
    current = id;
    final row = <String, Object?>{'id': id, 'status': 'running'};
    results.add(row);
    publish();
    final watch = Stopwatch()..start();
    try {
      row.addAll(await body());
      row['status'] = 'passed';
    } catch (error, stack) {
      row.addAll({'status': 'failed', 'error': '$error', 'stack': '$stack'});
    }
    row['elapsedMs'] = watch.elapsedMilliseconds;
    publish();
  }

  Map<String, Object?> state(ComparisonPlaybackDto s) => {
    'phase': s.phase,
    'positionUs': s.positionUs,
    'durationUs': s.durationUs,
    'referenceStartUs': s.teacherStartUs,
    'referenceFrameUs': s.teacherFrameUs,
    'targetFrameUs': s.learnerFrameUs,
    'scale': s.timeScale,
    'offsetUs': s.offsetUs,
    'speed': s.speed,
    'error': s.error,
  };
  Map<String, dynamic> source(String key) =>
      Map<String, dynamic>.from(manifest['sources'][key] as Map);
  Map<String, dynamic> session(String key) => Map<String, dynamic>.from(
    (manifest['sessions'] as List).firstWhere((v) => v['id'] == key) as Map,
  );
  ComparisonProject project(
    Map<String, dynamic> s, {
    bool picture = false,
    bool horizontal = true,
  }) {
    ComparisonSource media(String key) {
      final value = source(key);
      return ComparisonSource(
        uri: uris[key]!,
        name: key,
        durationUs: value['durationUs'] as int,
        hasAudio: value['hasAudio'] as bool,
      );
    }

    return ComparisonProject(
      id: 'qa_core_${s['id']}',
      title: 'Shared core regression',
      teacher: media(s['reference'] as String),
      learner: media(s['target'] as String),
      offsetUs: s['offset'] as int,
      timeScale: (s['scale'] as num).toDouble(),
      alignment: 'manual',
      speed: picture ? .5 : 1,
      sideBySide: horizontal,
      mirrorTeacher: picture,
      teacherPicture: picture
          ? const ComparisonPicture(zoom: 1.4, panX: .2, panY: -.3)
          : const ComparisonPicture(),
      learnerPicture: picture
          ? const ComparisonPicture(zoom: 1.8, panX: -.25, panY: .4)
          : const ComparisonPicture(),
      loopEnabled: picture,
      loopStartUs: picture ? 1000000 : null,
      loopEndUs: picture ? 2000000 : null,
      updatedAtMs: DateTime.now().millisecondsSinceEpoch,
    );
  }

  ComparisonPracticeDto practice(ComparisonProject p) => ComparisonPracticeDto(
    mirrorTeacher: p.mirrorTeacher,
    mirrorLearner: p.mirrorLearner,
    loopStartUs: p.loopStartUs,
    loopEndUs: p.loopEndUs,
    loopEnabled: p.loopEnabled,
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
  );

  Future<ComparisonPlaybackDto> ready(ComparisonPlaybackDto s) async {
    final until = DateTime.now().add(const Duration(seconds: 40));
    while (s.phase != 'paused' ||
        s.teacherFrameUs == null ||
        s.learnerFrameUs == null) {
      require(
        s.error == null && DateTime.now().isBefore(until),
        'Not ready: ${state(s)}',
      );
      await Future<void>.delayed(const Duration(milliseconds: 30));
      s = await host(api.getState());
    }
    return s;
  }

  Future<ComparisonPlaybackDto> open(ComparisonProject p) async {
    var s = await host(
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
    s = await ready(s);
    require(s.textureId >= 0 && s.durationUs > 0, 'Missing texture or overlap');
    return s;
  }

  Future<void> close() async {
    await host(api.close());
    texture = null;
    notifyListeners();
  }

  Future<ComparisonPlaybackDto> seekProbe(Map<String, dynamic> probe) async {
    var s = await host(
      api.control(ComparisonCommand.seek, probe['positionUs'] as int, 1),
    );
    final until = DateTime.now().add(const Duration(seconds: 12));
    bool correct(int? actual, Object? expected) =>
        actual != null && (actual - (expected as int)).abs() <= 1;
    while (!correct(s.teacherFrameUs, probe['referenceFrameUs']) ||
        !correct(s.learnerFrameUs, probe['targetFrameUs'])) {
      require(
        s.error == null && DateTime.now().isBefore(until),
        'Wrong source pair: expected=$probe actual=${state(s)}',
      );
      await Future<void>.delayed(const Duration(milliseconds: 25));
      s = await host(api.getState());
    }
    return s;
  }

  Future<ComparisonExportDto> terminal(ComparisonExportDto job) async {
    final until = DateTime.now().add(const Duration(minutes: 8));
    while (![
      'completed',
      'failed',
      'cancelled',
      'interrupted',
    ].contains(job.state)) {
      require(DateTime.now().isBefore(until), 'Export timeout: ${job.state}');
      await Future<void>.delayed(const Duration(milliseconds: 60));
      job = (await host(api.getExport()))!;
    }
    return job;
  }

  Future<void> retain(
    String id,
    ComparisonExportDto job,
    ComparisonProject p,
    int start,
    int end,
    int width,
    int height,
    bool horizontal,
    Map<String, dynamic> s,
  ) async {
    require(
      job.state == 'completed' && job.outputPath != null,
      'Export failed: ${job.state}: ${job.error}',
    );
    final file = File(job.outputPath!);
    require(file.existsSync() && file.lengthSync() > 1000, 'Missing movie');
    await file.copy('${outputs.path}/$id.mp4');
    final saved = await host(api.saveExport(job.jobId));
    final again = await host(api.saveExport(job.jobId));
    require(
      saved.savedUri != null && saved.savedUri == again.savedUri,
      'Save did not deduplicate',
    );
    exports.add({
      'id': id,
      'file': '$id.mp4',
      'jobId': job.jobId,
      'savedUri': saved.savedUri,
      'reference': s['reference'],
      'target': s['target'],
      'coded30':
          source(s['reference'] as String)['coded30'] == true &&
          source(s['target'] as String)['coded30'] == true,
      'spec': {
        'startUs': start,
        'endUs': end,
        'width': width,
        'height': height,
        'gap': 8,
        'sideBySide': horizontal,
        'offsetUs': p.offsetUs,
        'timeScale': p.timeScale,
        'mirrorTeacher': p.mirrorTeacher,
        'mirrorLearner': p.mirrorLearner,
        'teacherPicture': {
          'zoom': p.teacherPicture.zoom,
          'panX': p.teacherPicture.panX,
          'panY': p.teacherPicture.panY,
        },
        'learnerPicture': {
          'zoom': p.learnerPicture.zoom,
          'panX': p.learnerPicture.panX,
          'panY': p.learnerPicture.panY,
        },
      },
    });
  }

  Future<void> run() async {
    final home = Directory.systemTemp.parent.path;
    documents = Directory('$home/Documents')..createSync(recursive: true);
    corpus = Directory('${documents.path}/comparison-core-corpus');
    outputs = Directory('${documents.path}/comparison-core-outputs')
      ..createSync(recursive: true);
    publish();
    try {
      final until = DateTime.now().add(const Duration(minutes: 3));
      while (!File('${corpus.path}/manifest.json').existsSync()) {
        require(DateTime.now().isBefore(until), 'Missing frozen corpus');
        await Future<void>.delayed(const Duration(milliseconds: 100));
      }
      manifest = Map<String, dynamic>.from(
        jsonDecode(File('${corpus.path}/manifest.json').readAsStringSync())
            as Map,
      );
      final owned = Directory(
        Platform.isIOS
            ? '$home/Library/Application Support/WoahComparisonMedia'
            : '$home/files/qa-comparison-media',
      )..createSync(recursive: true);
      var index = 1000;
      for (final key in (manifest['sources'] as Map).keys.cast<String>()) {
        final value = source(key);
        final input = File('${corpus.path}/${value['file']}');
        require(
          input.lengthSync() == value['bytes'],
          'Wrong corpus file size: $key',
        );
        final copied = await input.copy(
          '${owned.path}/00000000-0000-4000-8000-${(index++).toString().padLeft(12, '0')}.mp4',
        );
        uris[key] = copied.uri.toString();
      }
      await stage('picker/close_pending', () async {
        final pending = host(api.pickVideo('teacher'));
        await Future<void>.delayed(const Duration(milliseconds: 500));
        await close();
        require(await pending == null, 'Closing route retained picker reply');
        return {'cancelled': true};
      });
      for (final raw in manifest['alignments'] as List) {
        final a = Map<String, dynamic>.from(raw as Map);
        await stage('music/${a['id']}', () async {
          final r = await host(
            api.alignMusic(uris[a['reference']]!, uris[a['target']]!),
            seconds: 180,
          );
          require(
            r.status == (a['status'] ?? 'matched'),
            'Unexpected music status: ${r.status}: ${r.message}',
          );
          if (a['scale'] != null) {
            require(r.candidates.isNotEmpty, 'Missing music evidence');
            final c = r.candidates.first;
            final error = [3000000, 6000000, 9000000]
                .map(
                  (t) =>
                      ((c.timeScale ?? 1) * t +
                              c.offsetUs -
                              ((a['scale'] as num) * t + (a['offset'] as int)))
                          .abs(),
                )
                .reduce(math.max);
            require(error < 35000, 'Music truth error: $error us');
            return {
              'status': r.status,
              'scale': c.timeScale,
              'offsetUs': c.offsetUs,
              'maxTruthErrorUs': error,
            };
          }
          return {'status': r.status};
        });
      }
      await stage('music/cancel_overlap', () async {
        final first = host(
          api.alignMusic(uris['rate_reference']!, uris['rate_target']!),
        );
        await Future<void>.delayed(const Duration(milliseconds: 10));
        await host(api.cancelAlignment());
        final r = await first;
        require(r.status == 'cancelled', 'Cancellation published ${r.status}');
        return {'status': r.status};
      });
      for (final raw in manifest['sessions'] as List) {
        final s = Map<String, dynamic>.from(raw as Map);
        await stage('playback/${s['id']}', () async {
          try {
            final p = project(s);
            final opened = await open(p);
            require(
              (opened.durationUs - (s['durationUs'] as int)).abs() <= 2,
              'Common interval differs from frozen truth',
            );
            final evidence = <Object?>[];
            for (final probe in s['probes'] as List) {
              evidence.add(
                state(await seekProbe(Map<String, dynamic>.from(probe as Map))),
              );
            }
            final probe = Map<String, dynamic>.from(
              (s['probes'] as List).first as Map,
            );
            await seekProbe(probe);
            final forward = await host(api.stepFrame(true));
            final backward = await host(api.stepFrame(false));
            require(
              (forward.teacherFrameUs! - (probe['nextReferenceFrameUs'] as int))
                      .abs() <=
                  1,
              'Forward is not adjacent source PTS',
            );
            require(
              (backward.teacherFrameUs! - (probe['referenceFrameUs'] as int))
                      .abs() <=
                  1,
              'Backward is not adjacent source PTS',
            );
            await ready(await host(api.resize(320, 240, false)));
            await ready(await host(api.resize(320, 240, true)));
            await ready(
              await host(api.setPractice(practice(project(s, picture: true)))),
            );
            await host(api.control(ComparisonCommand.seek, 1250000, 1));
            await host(api.control(ComparisonCommand.setSpeed, 0, .5));
            await host(api.control(ComparisonCommand.play, 0, .5));
            await Future<void>.delayed(const Duration(milliseconds: 650));
            final paused = await host(
              api.control(ComparisonCommand.pause, 0, .5),
            );
            require(
              paused.error == null &&
                  paused.speed == .5 &&
                  paused.timeScale == p.timeScale,
              'Practice changed music correspondence',
            );
            return {
              'opened': state(opened),
              'sourcePairs': evidence,
              'forward': state(forward),
              'backward': state(backward),
              'paused': state(paused),
            };
          } finally {
            await close();
          }
        });
      }
      await stage('project/anchors_swap_marker_restore', () async {
        var p = project(session('base')).copyWith(
          positionUs: 1250000,
          teacherAnchorUs: 1000000,
          learnerAnchorUs: 4000000,
          teacherAnchor2Us: 4000000,
          learnerAnchor2Us: 7000000,
          markers: [
            ComparisonMarker(
              id: 'qa_marker',
              sourceUri: uris['base_target']!,
              startUs: 4200000,
              endUs: 4600000,
              note: 'regression',
            ),
          ],
        );
        await host(api.saveProject(jsonEncode(p.toJson())));
        final loaded = (await host(api.loadProjects()))
            .map(
              (j) => ComparisonProject.fromJson(
                Map<String, dynamic>.from(jsonDecode(j) as Map),
              ),
            )
            .firstWhere((v) => v.id == p.id);
        require(
          jsonEncode(loaded.toJson()) == jsonEncode(p.toJson()),
          'Project changed during native persistence',
        );
        p = p.swapSources();
        require(
          p.markers.single.sourceUri == uris['base_target'],
          'Swap rewrote source marker',
        );
        final state = await open(p);
        await close();
        return {'restored': true, 'swapped': this.state(state)};
      });
      await stage('manual/independent_players', () async {
        final a = VideoPlayerController.file(
          File.fromUri(Uri.parse(uris['rate_reference']!)),
        );
        final b = VideoPlayerController.file(
          File.fromUri(Uri.parse(uris['rate_target']!)),
        );
        try {
          await host(Future.wait([a.initialize(), b.initialize()]));
          await host(
            Future.wait([
              a.seekTo(const Duration(seconds: 1)),
              b.seekTo(const Duration(seconds: 2)),
            ]),
          );
          final x = await a.position, y = await b.position;
          require(
            (x!.inMicroseconds - 1000000).abs() < 100000 &&
                (y!.inMicroseconds - 2000000).abs() < 100000,
            'Independent music anchors failed',
          );
          return {
            'referenceUs': x.inMicroseconds,
            'targetUs': y!.inMicroseconds,
          };
        } finally {
          await a.dispose();
          await b.dispose();
        }
      });
      for (final raw in manifest['exports'] as List) {
        final e = Map<String, dynamic>.from(raw as Map);
        await stage('export/${e['id']}', () async {
          final s = session(e['session'] as String);
          final horizontal = e['horizontal'] != false;
          final p = project(
            s,
            picture: e['picture'] == true,
            horizontal: horizontal,
          );
          final end = e['end'] as int? ?? s['durationUs'] as int;
          final start = e['tail'] == true
              ? end - 1234567
              : e['start'] as int? ?? 0;
          final width = e['width'] as int, height = e['height'] as int;
          await open(p);
          final previous = await host(api.getExport());
          require(
            previous == null ||
                previous.projectId.startsWith('qa_core_') ||
                previous.savedUri != null,
            'Unrelated unsaved movie must be preserved',
          );
          final job = await terminal(
            await host(
              api.startExport(
                jsonEncode(p.toJson()),
                start,
                end,
                width,
                height,
                8,
                horizontal,
                true,
              ),
            ),
          );
          await retain(
            e['id'] as String,
            job,
            p,
            start,
            end,
            width,
            height,
            horizontal,
            s,
          );
          await close();
          return {
            'jobId': job.jobId,
            'state': job.state,
            'durationUs': end - start,
            'width': width,
            'height': height,
          };
        });
      }
      await stage('export/cancel_immediate_retry_4k', () async {
        final s = session('landscape4k');
        final p = project(s);
        await open(p);
        var job = await host(
          api.startExport(
            jsonEncode(p.toJson()),
            0,
            s['durationUs'] as int,
            1920,
            1080,
            8,
            true,
            true,
          ),
        );
        job = await terminal(await host(api.cancelExport(job.jobId)));
        require(
          job.state == 'cancelled',
          'Immediate cancel became ${job.state}',
        );
        final retry = await terminal(await host(api.retryExport(job.jobId)));
        await retain(
          'landscape4k_retry',
          retry,
          p,
          0,
          s['durationUs'] as int,
          1920,
          1080,
          true,
          s,
        );
        await close();
        return {
          'cancelled': true,
          'retryJobId': retry.jobId,
          'retryState': retry.state,
        };
      });
      await stage('invalid/corrupt_source', () async {
        var rejected = false;
        try {
          final invalid = await host(
            api.open(
              File('${corpus.path}/corrupt.mp4').uri.toString(),
              uris['base_target']!,
              0,
              0,
              1,
              320,
              240,
              true,
              ComparisonPracticeDto(
                mirrorTeacher: false,
                mirrorLearner: false,
                loopEnabled: false,
              ),
              1,
            ),
          );
          await ready(invalid);
        } catch (_) {
          rejected = true;
        } finally {
          await close();
        }
        require(rejected, 'Corrupt video was accepted');
        return {'rejected': true};
      });
      await stage('gallery/open_completed_movie', () async {
        final job = (await host(api.getExport()))!;
        require(
          job.state == 'completed' && job.savedUri != null,
          'Missing saved output',
        );
        await host(api.openExport(job.jobId));
        return {'opened': true};
      });
    } catch (error, stack) {
      results.add({
        'id': 'runner',
        'status': 'failed',
        'error': '$error',
        'stack': '$stack',
      });
    }
    current = 'complete';
    finished = true;
    publish();
  }
}
