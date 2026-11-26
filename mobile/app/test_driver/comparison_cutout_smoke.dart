import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:ui' as ui;
import 'package:dance_native/dance_native.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';

/// Real native comparison bridge + LiteRT + shared tracking + texture smoke.
/// Build this debug entry and install the two documented fixtures in the app's
/// Documents/comparison-cutout-fixtures folder before launch. Local QA only.
Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final smoke = _CutoutSmoke();
  runApp(
    MaterialApp(
      home: Scaffold(
        body: SafeArea(
          child: AnimatedBuilder(
            animation: smoke,
            builder: (_, _) => Column(
              children: [
                Text(smoke.status),
                RepaintBoundary(
                  key: smoke.previewKey,
                  child: SizedBox(
                    width: 320,
                    height: 240,
                    child: smoke.texture == null
                        ? const ColoredBox(color: Colors.black)
                        : Texture(textureId: smoke.texture!),
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
  await smoke.run();
}

class _CutoutSmoke extends ChangeNotifier {
  static const extended = bool.fromEnvironment('WOAH_CUTOUT_EXTENDED_QA');
  static const fullLength = bool.fromEnvironment('WOAH_CUTOUT_FULL_QA');
  static const cancelPartial = bool.fromEnvironment('WOAH_CUTOUT_CANCEL_QA');
  static const persistQa = bool.fromEnvironment('WOAH_CUTOUT_PERSIST_QA');
  static const realQa = bool.fromEnvironment('WOAH_CUTOUT_REAL_QA');
  static const repairQa = bool.fromEnvironment('WOAH_CUTOUT_REPAIR_QA');
  static const perfQa = bool.fromEnvironment('WOAH_CUTOUT_PERF_QA');
  final api = DanceComparisonApi();
  final previewKey = GlobalKey();
  String status = 'waiting';
  int? texture;
  late Directory folder;
  final results = <Map<String, Object?>>[];

  void require(bool value, String reason) {
    if (!value) throw StateError(reason);
  }

  Future<T> host<T>(Future<T> value) =>
      value.timeout(Duration(seconds: fullLength || realQa ? 600 : 90));
  Future<ComparisonPlaybackDto> ready(ComparisonPlaybackDto s) async {
    final until = DateTime.now().add(const Duration(seconds: 30));
    while (s.phase != 'paused' ||
        s.teacherFrameUs == null ||
        s.learnerFrameUs == null) {
      require(
        s.error == null && DateTime.now().isBefore(until),
        'Not ready: ${s.phase} ${s.error}',
      );
      await Future<void>.delayed(const Duration(milliseconds: 50));
      s = await host(api.getState());
    }
    texture = s.textureId;
    notifyListeners();
    return s;
  }

  void publish(bool finished) {
    File(
      '${folder.path}/${fullLength
          ? 'full-report'
          : extended
          ? 'extended-report'
          : 'report'}.json',
    ).writeAsStringSync(
      jsonEncode({
        'finished': finished,
        'passed': finished && results.every((r) => r['passed'] == true),
        'status': status,
        'results': results,
      }),
      flush: true,
    );
    notifyListeners();
  }

  Future<Map<String, Object?>> capture(String name) async {
    await Future<void>.delayed(const Duration(milliseconds: 160));
    await WidgetsBinding.instance.endOfFrame;
    final boundary =
        previewKey.currentContext!.findRenderObject() as RenderRepaintBoundary;
    final image = await boundary.toImage(pixelRatio: 1);
    try {
      final raw = (await image.toByteData(format: ui.ImageByteFormat.rawRgba))!;
      final png = (await image.toByteData(format: ui.ImageByteFormat.png))!;
      File(
        '${folder.path}/$name.png',
      ).writeAsBytesSync(png.buffer.asUint8List());
      final colors = raw.buffer.asUint8List();
      int left = 0, right = 0, top = 0;
      for (var y = 0; y < image.height; y++) {
        for (var x = 0; x < image.width; x++) {
          final i = (y * image.width + x) * 4;
          if (colors[i] > 24 || colors[i + 1] > 24 || colors[i + 2] > 24) {
            if (x < image.width / 2) {
              left++;
            } else {
              right++;
            }
            if (y < image.height / 10) top++;
          }
        }
      }
      return {'leftPixels': left, 'rightPixels': right, 'topPixels': top};
    } finally {
      image.dispose();
    }
  }

  Future<Map<String, Object?>> inspect(
    bool reference,
    ComparisonCutoutSeedDto seed,
    String name,
  ) async {
    final watch = Stopwatch()..start();
    final result = await host(api.inspectCutout(reference, seed));
    final elapsed = watch.elapsedMilliseconds;
    require(result.sourcePtsUs == seed.sourcePtsUs, 'Still PTS mismatch');
    final codec = await ui.instantiateImageCodec(result.png);
    try {
      final image = (await codec.getNextFrame()).image;
      try {
        require(
          image.width == result.width && image.height == result.height,
          'Still dimensions mismatch',
        );
        final bytes = (await image.toByteData(
          format: ui.ImageByteFormat.rawRgba,
        ))!.buffer.asUint8List();
        int transparent = 0, opaque = 0, soft = 0;
        for (var i = 3; i < bytes.length; i += 4) {
          if (bytes[i] == 0) {
            transparent++;
          } else if (bytes[i] == 255) {
            opaque++;
          } else {
            soft++;
          }
        }
        require(
          transparent > 0 && opaque > 0 && soft > 0,
          'Still is not a real alpha cutout',
        );
        File('${folder.path}/$name.png').writeAsBytesSync(result.png);
        return {
          'pts': result.sourcePtsUs,
          'size': [result.width, result.height],
          'bytes': result.png.length,
          'transparent': transparent,
          'opaque': opaque,
          'soft': soft,
          'inspectionMs': elapsed,
        };
      } finally {
        image.dispose();
      }
    } finally {
      codec.dispose();
    }
  }

  Future<Map<String, Object?>> editCanvas(
    String id,
    int start,
    bool targetMirror,
  ) async {
    final state = await ready(
      await host(api.control(ComparisonCommand.seek, start, 1)),
    );
    ComparisonCanvasLayerDto layer(
      double x,
      double opacity, {
      bool mirror = false,
      double height = .70,
    }) => ComparisonCanvasLayerDto(
      x: x,
      y: .88,
      height: height,
      opacity: opacity,
      mirror: mirror,
    );
    ComparisonCanvasLayoutDto layout(
      ComparisonCanvasLayerDto a,
      ComparisonCanvasLayerDto b, {
      bool top = true,
      int background = 0,
    }) => ComparisonCanvasLayoutDto(
      reference: a,
      target: b,
      targetOnTop: top,
      background: background,
    );
    final evidence = <String, Object?>{};
    Future<void> apply(ComparisonCanvasLayoutDto value, String name) async {
      final watch = Stopwatch()..start();
      final after = await ready(await host(api.setCutoutLayout(value)));
      require(
        after.teacherFrameUs == state.teacherFrameUs &&
            after.learnerFrameUs == state.learnerFrameUs,
        'Layout edit changed source frame',
      );
      evidence[name] = {
        'ms': watch.elapsedMilliseconds,
        ...await capture('$id-edit-$name'),
      };
    }

    final a = layer(.20, 1, height: .45),
        hidden = layer(.73, 0, mirror: targetMirror);
    await apply(layout(a, hidden), 'hidden');
    final isolated = evidence['hidden'] as Map;
    require(
      (isolated['leftPixels'] as int) > 80 &&
          (isolated['rightPixels'] as int) < 80,
      'Moving reference and hiding target did not change output: $isolated',
    );
    await apply(
      layout(layer(.5, 1), layer(.5, 1, mirror: targetMirror), top: true),
      'target-front',
    );
    await apply(
      layout(layer(.5, 1), layer(.5, 1, mirror: targetMirror), top: false),
      'reference-front',
    );
    String png(String name) => base64Encode(
      File('${folder.path}/$id-edit-$name.png').readAsBytesSync(),
    );
    require(
      png('target-front') != png('reference-front'),
      'Layer order had no effect',
    );
    await apply(layout(layer(.5, 0), hidden, background: 2), 'light');
    final light = evidence['light'] as Map;
    require(
      light['leftPixels'] == 38400 &&
          light['rightPixels'] == 38400 &&
          light['topPixels'] == 7680,
      'Background did not fill the canvas: $light',
    );
    await apply(
      layout(layer(.27, 1), layer(.73, 1, mirror: targetMirror)),
      'reset',
    );
    final reset = evidence['reset'] as Map;
    require(
      (reset['leftPixels'] as int) > 80 &&
          (reset['rightPixels'] as int) > 80 &&
          (reset['topPixels'] as int) < 20,
      'Parallel reset failed',
    );
    var rejected = false;
    try {
      await host(api.setCutoutLayout(layout(layer(.5, 1.1), hidden)));
    } catch (_) {
      rejected = true;
    }
    require(rejected, 'Invalid opacity was accepted');
    return evidence;
  }

  Future<ComparisonExportDto> exportTerminal(ComparisonExportDto job) async {
    final deadline = DateTime.now().add(const Duration(seconds: 90));
    while (const [
      'queued',
      'encoding',
      'publishing',
      'cancelling',
    ].contains(job.state)) {
      require(DateTime.now().isBefore(deadline), 'Export timed out');
      await Future<void>.delayed(const Duration(milliseconds: 100));
      final next = await host(api.getExport());
      require(next?.jobId == job.jobId, 'Export task replaced');
      job = next!;
    }
    return job;
  }

  Future<Map<String, Object?>> exportCanvas(
    String id,
    String source,
    String target,
    int start,
    int end, {
    bool cancelFirst = false,
    int exportWidth = 320,
    int exportHeight = 240,
  }) async {
    final result = <String, Object?>{};
    final reference = ComparisonCanvasLayerDto(
      x: .43,
      y: .88,
      height: .66,
      opacity: 1,
      mirror: false,
    );
    final learner = ComparisonCanvasLayerDto(
      x: .57,
      y: .82,
      height: .58,
      opacity: .55,
      mirror: true,
    );
    final layout = ComparisonCanvasLayoutDto(
      reference: reference,
      target: learner,
      targetOnTop: false,
      background: 1,
    );
    await ready(await host(api.setCutoutLayout(layout)));
    final project = jsonEncode({
      'schemaVersion': 1,
      'id': 'cutout_qa_$id',
      'teacher': {'uri': '${folder.path}/$source'},
      'learner': {'uri': '${folder.path}/$target'},
      'offsetUs': 0,
      'timeScale': 1,
      'mirrorTeacher': false,
      'mirrorLearner': target.contains('variant'),
    });
    for (final us in [
      start,
      start + 400000,
      start + 800000,
      if (fullLength) ...[start + 1066667, start + 5000000, end - 100000],
    ]) {
      await ready(await host(api.control(ComparisonCommand.seek, us, 1)));
      await capture('$id-export-preview-${us - start}');
    }
    final before = await ready(
      await host(api.control(ComparisonCommand.seek, start + 400000, 1)),
    );
    final old = await host(api.getExport());
    var rejected = false;
    try {
      await host(api.startExport(project, start, end, 320, 240, 8, true, true));
    } catch (_) {
      rejected = true;
    }
    require(
      rejected && (await host(api.getExport()))?.jobId == old?.jobId,
      'Ordinary export admitted active cutout',
    );
    rejected = false;
    try {
      await host(
        api.startCutoutExport(
          project,
          start,
          end + 33333,
          320,
          240,
          8,
          true,
          true,
        ),
      );
    } catch (_) {
      rejected = true;
    }
    require(
      rejected && (await host(api.getExport()))?.jobId == old?.jobId,
      'Unanalyzed export range admitted',
    );
    final watch = Stopwatch()..start();
    var job = await host(
      api.startCutoutExport(
        project,
        start,
        end,
        exportWidth,
        exportHeight,
        8,
        true,
        true,
      ),
    );
    require(
      job.cutout == true && job.startUs == start && job.endUs == end,
      'Wrong task type or range',
    );
    if (cancelFirst) {
      await host(api.cancelExport(job.jobId));
      job = await exportTerminal(job);
      require(
        job.state == 'cancelled' &&
            job.outputPath == null &&
            job.retryAvailable == true,
        'Cutout cancellation did not retain retry: ${job.state}',
      );
      result['cancelled'] = true;
      job = await host(api.retryExport(job.jobId));
      require(job.cutout == true, 'Retry lost canvas mode');
    }
    job = await exportTerminal(job);
    require(
      job.state == 'completed' && job.outputPath != null && job.cutout == true,
      'Cutout export failed: ${job.state} ${job.error}',
    );
    final movie = File(job.outputPath!);
    require(
      movie.existsSync() && movie.lengthSync() > 0,
      'Encoded movie absent',
    );
    await movie.copy('${folder.path}/$id-cutout.mp4');
    result['bytes'] = movie.lengthSync();
    result['elapsedMs'] = watch.elapsedMilliseconds;
    result['jobState'] = job.state;
    result['savedToGallery'] = job.savedUri != null;
    if (Platform.isAndroid) {
      require(job.savedUri != null, 'Android MediaStore publication failed');
    }
    result['startUs'] = job.startUs;
    result['endUs'] = job.endUs;
    final restored = await ready(await host(api.resumeCutoutPreview()));
    require(
      restored.teacherFrameUs == before.teacherFrameUs &&
          restored.learnerFrameUs == before.learnerFrameUs,
      'Resumed canvas changed selected playback time',
    );
    await capture('$id-export-resumed');
    result['resumed'] = true;
    return result;
  }

  Future<ComparisonCutoutResultDto> waitForPartial() async {
    final deadline = DateTime.now().add(const Duration(seconds: 45));
    while (DateTime.now().isBefore(deadline)) {
      final status = await host(api.getCutoutStatus());
      if (status != null &&
          status.analyzing == true &&
          status.playback.phase == 'paused' &&
          status.playback.teacherFrameUs != null &&
          status.playback.learnerFrameUs != null) {
        return status;
      }
      await Future<void>.delayed(const Duration(milliseconds: 100));
    }
    throw StateError('Progressive preview never became playable');
  }

  Future<void> probe(
    String id,
    String target,
    double x,
    int position, {
    String source = 'source.mp4',
    (int, int)? window,
    int exportWidth = 320,
    int exportHeight = 240,
  }) async {
    final row = <String, Object?>{'id': id};
    results.add(row);
    status = id;
    publish(false);
    try {
      final practice = ComparisonPracticeDto(
        mirrorTeacher: false,
        mirrorLearner: target.contains('variant'),
        loopEnabled: window != null,
        loopStartUs: window?.$1,
        loopEndUs: window?.$2,
        teacherPicture: ComparisonPictureDto(zoom: 1, panX: 0, panY: 0),
        learnerPicture: ComparisonPictureDto(zoom: 1, panX: 0, panY: 0),
      );
      var state = await ready(
        await host(
          api.open(
            '${folder.path}/$source',
            '${folder.path}/$target',
            0,
            position,
            1,
            320,
            240,
            true,
            practice,
            1,
          ),
        ),
      );
      state = await ready(
        await host(api.control(ComparisonCommand.pause, 0, 1)),
      );
      row['anchor'] = [state.teacherFrameUs, state.learnerFrameUs];
      row['commonDurationUs'] = state.durationUs;
      row['window'] = window == null ? null : [window.$1, window.$2];
      row['ordinary'] = await capture('$id-ordinary');
      final ra = ComparisonCutoutSeedDto(
        sourcePtsUs: state.teacherFrameUs!,
        x: .355,
        y: .855,
      );
      final ta = ComparisonCutoutSeedDto(
        sourcePtsUs: state.learnerFrameUs!,
        x: x,
        y: .86,
      );
      var rejected = false;
      try {
        await host(
          api.inspectCutout(
            true,
            ComparisonCutoutSeedDto(
              sourcePtsUs: ra.sourcePtsUs,
              x: .01,
              y: .01,
            ),
          ),
        );
      } catch (_) {
        rejected = true;
      }
      require(rejected, 'Background incorrectly selected');
      row['backgroundRejected'] = true;
      row['referenceStill'] = await inspect(true, ra, '$id-reference-still');
      row['targetStill'] = await inspect(false, ta, '$id-target-still');
      final watch = Stopwatch()..start();
      final pending = host(api.prepareCutout(ra, ta, null));
      // Install an error listener immediately while exercising the live preview.
      unawaited(
        pending.then<void>((_) {}, onError: (Object _, StackTrace _) {}),
      );
      if (fullLength) {
        final partial = await waitForPartial();
        final firstMs = watch.elapsedMilliseconds;
        final a = partial.previewStartUs!, b = partial.previewEndUs!;
        require(a == 9500000 && b == 10500000, 'Wrong initial range: $a–$b');
        require(
          partial.referenceFrames == 360 &&
              partial.targetFrames == 360 &&
              partial.referenceAnalyzed! < 360 &&
              partial.targetAnalyzed! < 360,
          'Initial coverage is not partial',
        );
        await ready(partial.playback);
        final anchor = await capture('$id-partial-anchor');
        require(
          (anchor['leftPixels'] as int) > 80 &&
              (anchor['rightPixels'] as int) > 80 &&
              (anchor['topPixels'] as int) < 20,
          'Initial dynamic canvas missing: $anchor',
        );
        var seeked = await ready(
          await host(api.control(ComparisonCommand.seek, 0, 1)),
        );
        require(
          seeked.positionUs >= a && seeked.positionUs < b,
          'Preview escaped before A',
        );
        seeked = await ready(
          await host(api.control(ComparisonCommand.seek, 12000000, 1)),
        );
        require(
          seeked.positionUs >= a && seeked.positionUs < b,
          'Preview escaped after B',
        );
        await ready(await host(api.control(ComparisonCommand.seek, a, 1)));
        await host(api.control(ComparisonCommand.play, 0, 1));
        await Future<void>.delayed(const Duration(milliseconds: 1600));
        final playing = await host(api.getState());
        require(
          playing.positionUs >= a && playing.positionUs < b,
          'Preview loop escaped: ${playing.positionUs}',
        );
        final paused = await ready(
          await host(api.control(ComparisonCommand.pause, 0, 1)),
        );
        final live = await capture('$id-partial-playing');
        require(
          (live['leftPixels'] as int) > 80 &&
              (live['rightPixels'] as int) > 80 &&
              (live['topPixels'] as int) < 20,
          'Partial playback missing subject: $live',
        );
        final spec = jsonEncode({
          'schemaVersion': 1,
          'id': 'partial_probe',
          'teacher': {'uri': '${folder.path}/$source'},
          'learner': {'uri': '${folder.path}/$target'},
          'offsetUs': 0,
          'timeScale': 1,
        });
        final prior = (await host(api.getExport()))?.jobId;
        var blocked = false;
        try {
          await host(
            api.startCutoutExport(spec, 0, 12000000, 320, 240, 8, true, true),
          );
        } catch (_) {
          blocked = true;
        }
        require(
          blocked && (await host(api.getExport()))?.jobId == prior,
          'Partial export admitted',
        );
        final progress = await host(api.getCutoutStatus());
        require(
          progress != null &&
              progress.referenceAnalyzed! >= partial.referenceAnalyzed! &&
              progress.targetAnalyzed! >= partial.targetAnalyzed!,
          'Analysis progress regressed',
        );
        row['progressive'] = {
          'firstPlayableMs': firstMs,
          'range': [a, b],
          'initialAnalyzed': [
            partial.referenceAnalyzed,
            partial.targetAnalyzed,
          ],
          'laterAnalyzed': [
            progress!.referenceAnalyzed,
            progress.targetAnalyzed,
          ],
          'playedPositionUs': paused.positionUs,
          'exportRejected': blocked,
          'anchorPixels': anchor,
          'livePixels': live,
        };
        publish(false);
      }
      final result = await pending;
      row['analysisMs'] = watch.elapsedMilliseconds;
      row['visibility'] = [
        result.referenceVisible,
        result.referenceFrames,
        result.targetVisible,
        result.targetFrames,
      ];
      state = await ready(result.playback);
      require(
        result.referenceVisible == result.referenceFrames &&
            result.targetVisible == result.targetFrames &&
            result.referenceFrames >= (fullLength ? 360 : 29) &&
            result.targetFrames >= (fullLength ? 360 : 29),
        'Some tracked frames were missing',
      );
      if (fullLength) {
        await ready(
          await host(api.control(ComparisonCommand.seek, 10000000, 1)),
        );
        await capture('$id-final-anchor');
        final partialBytes = File(
          '${folder.path}/$id-partial-anchor.png',
        ).readAsBytesSync();
        final finalBytes = File(
          '${folder.path}/$id-final-anchor.png',
        ).readAsBytesSync();
        require(
          partialBytes.length == finalBytes.length &&
              List.generate(
                partialBytes.length,
                (i) => partialBytes[i] == finalBytes[i],
              ).every((x) => x),
          'Initial/final anchor changed',
        );
        (row['progressive'] as Map<String, Object?>)['anchorIdentical'] = true;
      }
      final start = window?.$1 ?? 0;
      if (window != null) {
        require(
          state.durationUs > 10000000,
          'Long-source fixture was not long',
        );
        final clamped = await ready(
          await host(api.control(ComparisonCommand.seek, 0, 1)),
        );
        require(clamped.positionUs >= window.$1, 'Cutout seek escaped below A');
        var rejected = false;
        try {
          await host(
            api.setPractice(
              ComparisonPracticeDto(
                mirrorTeacher: false,
                mirrorLearner: true,
                loopEnabled: false,
              ),
            ),
          );
        } catch (_) {
          rejected = true;
        }
        require(rejected, 'Cutout allowed changing the analyzed A/B window');
        row['windowLocked'] = true;
      }
      for (final us in [
        start,
        start + 400000,
        start + 800000,
        if (extended && window != null) window.$2 - 100000,
      ]) {
        state = await ready(
          await host(api.control(ComparisonCommand.seek, us, 1)),
        );
        final pixels = await capture('$id-$us');
        row['frame_$us'] = {
          'pts': [state.teacherFrameUs, state.learnerFrameUs],
          ...pixels,
        };
        require(
          (pixels['leftPixels'] as int) > 80 &&
              (pixels['rightPixels'] as int) > 80,
          'Cutout not visible on both sides at $us: $pixels',
        );
        require(
          (pixels['topPixels'] as int) < 20,
          'Unexpected source background at top: $pixels',
        );
      }
      state = await ready(
        await host(api.control(ComparisonCommand.seek, 0, 1)),
      );
      await host(api.control(ComparisonCommand.play, 0, 1));
      await Future<void>.delayed(const Duration(milliseconds: 300));
      final playing = await host(api.getState());
      require(
        playing.positionUs > start,
        'Cutout playback clock did not advance',
      );
      state = await ready(
        await host(api.control(ComparisonCommand.pause, 0, 1)),
      );
      final during = await capture('$id-played');
      row['played'] = {
        'positionUs': state.positionUs,
        'pts': [state.teacherFrameUs, state.learnerFrameUs],
        ...during,
      };
      require(
        (during['leftPixels'] as int) > 80 &&
            (during['rightPixels'] as int) > 80 &&
            (during['topPixels'] as int) < 20,
        'Cutout missing after actual playback',
      );
      if (id == 'middle') {
        row['canvasEdits'] = await editCanvas(
          id,
          start,
          target.contains('variant'),
        );
      }
      row['export'] = await exportCanvas(
        id,
        source,
        target,
        start,
        window?.$2 ?? state.durationUs,
        cancelFirst: id == 'middle',
        exportWidth: exportWidth,
        exportHeight: exportHeight,
      );
      state = await ready(await host(api.clearCutout()));
      row['restored'] = await capture('$id-restored');
      row['passed'] = true;
    } catch (error) {
      row['passed'] = false;
      row['error'] = '$error';
    } finally {
      try {
        await host(api.close());
      } catch (_) {}
      texture = null;
      publish(false);
    }
  }

  Future<Map<String, Object?>> mutationProbe() async {
    // Only mutate a disposable fixture; the user's sources are never touched.
    final copy = File('${folder.path}/mutation-target.mp4');
    await File('${folder.path}/variant.mp4').copy(copy.path);
    final reference = '${folder.path}/source.mp4';
    try {
      final state = await ready(
        await host(
          api.open(
            reference,
            copy.path,
            0,
            0,
            1,
            320,
            240,
            true,
            ComparisonPracticeDto(
              mirrorTeacher: false,
              mirrorLearner: true,
              loopEnabled: false,
            ),
            1,
          ),
        ),
      );
      final a = ComparisonCutoutSeedDto(
        sourcePtsUs: state.teacherFrameUs!,
        x: .355,
        y: .855,
      );
      final b = ComparisonCutoutSeedDto(
        sourcePtsUs: state.learnerFrameUs!,
        x: .324,
        y: .86,
      );
      await ready((await host(api.prepareCutout(a, b, null))).playback);
      await copy.writeAsBytes([0x77], mode: FileMode.append, flush: true);
      final project = jsonEncode({
        'schemaVersion': 1,
        'id': 'cutout_mutation_qa',
        'teacher': {'uri': reference},
        'learner': {'uri': copy.path},
        'offsetUs': 0,
        'timeScale': 1,
      });
      final job = await exportTerminal(
        await host(
          api.startCutoutExport(project, 0, 1000000, 320, 240, 8, true, true),
        ),
      );
      require(
        job.state == 'failed' &&
            job.cutout == true &&
            job.outputPath == null &&
            job.retryAvailable == false,
        'Changed source did not block publication: ${job.state} ${job.retryAvailable}',
      );
      var rejected = false;
      try {
        await host(api.retryExport(job.jobId));
      } catch (_) {
        rejected = true;
      }
      require(rejected, 'Changed source retry was admitted');
      return {
        'passed': true,
        'state': job.state,
        'retryAvailable': job.retryAvailable,
        'outputPath': job.outputPath,
      };
    } finally {
      await host(api.close());
      await copy.delete();
      texture = null;
    }
  }

  Future<Map<String, Object?>> cancellationProbe() async {
    final reference = '${folder.path}/long-source.mp4';
    final target = '${folder.path}/long-variant.mp4';
    final practice = ComparisonPracticeDto(
      mirrorTeacher: false,
      mirrorLearner: true,
      loopEnabled: false,
    );
    Future<ComparisonPlaybackDto> open(int position) async => ready(
      await host(
        api.open(
          reference,
          target,
          0,
          position,
          1,
          320,
          240,
          true,
          practice,
          1,
        ),
      ),
    );
    try {
      final state = await open(10000000);
      final ra = ComparisonCutoutSeedDto(
        sourcePtsUs: state.teacherFrameUs!,
        x: .355,
        y: .855,
      );
      final ta = ComparisonCutoutSeedDto(
        sourcePtsUs: state.learnerFrameUs!,
        x: .324,
        y: .86,
      );
      await inspect(true, ra, 'cancel-reference-still');
      await inspect(false, ta, 'cancel-target-still');
      final before = Stopwatch()..start();
      // Attach terminal handling immediately. Cancellation must reject this
      // request rather than silently publish a completed cache afterward.
      final pending = host(
        api.prepareCutout(ra, ta, null),
      ).then<bool>((_) => false, onError: (Object _, StackTrace _) => true);
      final partial = await waitForPartial();
      final firstPlayableMs = before.elapsedMilliseconds;
      await ready(partial.playback);
      final partialPixels = await capture('cancel-partial');
      require(
        (partialPixels['leftPixels'] as int) > 80 &&
            (partialPixels['rightPixels'] as int) > 80,
        'Partial canvas missing',
      );
      final cancelWatch = Stopwatch()..start();
      final ordinary = await ready(await host(api.clearCutout()));
      final cancelMs = cancelWatch.elapsedMilliseconds;
      require(
        await pending,
        'Cancelled analysis completed instead of rejecting',
      );
      require(
        await host(api.getCutoutStatus()) == null,
        'Cancelled progress still exposed',
      );
      final pixels = await capture('cancel-restored');
      require(
        (pixels['topPixels'] as int) > 200,
        'Ordinary source not restored: $pixels',
      );
      await ready(await host(api.control(ComparisonCommand.seek, 0, 1)));
      await Future<void>.delayed(const Duration(milliseconds: 400));
      require(
        await host(api.getCutoutStatus()) == null,
        'Late partial work resurrected',
      );
      // Opening again must not reuse a cancelled partial cache.
      final reopened = await open(0);
      require(
        reopened.teacherFrameUs == 0 &&
            reopened.learnerFrameUs == 0 &&
            await host(api.getCutoutStatus()) == null,
        'Reopen reused cancelled analysis',
      );
      return {
        'passed': true,
        'firstPlayableMs': firstPlayableMs,
        'cancelMs': cancelMs,
        'restoredPositionUs': ordinary.positionUs,
        'restoredPixels': pixels,
        'lateResultRejected': true,
        'reopened': true,
      };
    } finally {
      await host(api.close());
      texture = null;
    }
  }

  Future<void> archiveProbe() async {
    final reportFile = File('${folder.path}/archive-report.json');
    final previous = reportFile.existsSync()
        ? Map<String, dynamic>.from(
            jsonDecode(reportFile.readAsStringSync()) as Map,
          )
        : <String, dynamic>{};
    final restore = previous['phase'] == 'saved';
    final report = <String, Object?>{...previous, 'pid': pid, 'passed': false};
    const id = 'qa_archive_restore';
    const corrupt = 'qa_archive_corrupt',
        mapping = 'qa_archive_mapping',
        mutated = 'qa_archive_mutated';
    final source = File('${folder.path}/archive-source.mp4');
    final target = File('${folder.path}/archive-variant.mp4');
    final layout = ComparisonCanvasLayoutDto(
      reference: ComparisonCanvasLayerDto(
        x: .31,
        y: .86,
        height: .67,
        opacity: 1,
        mirror: false,
      ),
      target: ComparisonCanvasLayerDto(
        x: .69,
        y: .82,
        height: .61,
        opacity: .72,
        mirror: true,
      ),
      targetOnTop: true,
      background: 0,
    );
    Future<ComparisonPlaybackDto> open(int position, {int offset = 0}) async {
      await host(api.close());
      return ready(
        await host(
          api.open(
            source.path,
            target.path,
            offset,
            position,
            1,
            320,
            240,
            true,
            ComparisonPracticeDto(
              mirrorTeacher: false,
              mirrorLearner: true,
              loopEnabled: true,
              loopStartUs: 6000000,
              loopEndUs: 9000000,
            ),
            1,
          ),
        ),
      );
    }

    Future<void> seekAnchor() async {
      final frame = await ready(
        await host(api.control(ComparisonCommand.seek, 7000000, 1)),
      );
      require(
        frame.teacherFrameUs == 7000000 && frame.learnerFrameUs == 7000000,
        'Archive anchor differs',
      );
    }

    bool all(ComparisonCutoutResultDto value) =>
        value.referenceFrames == 90 &&
        value.referenceVisible == 90 &&
        value.targetFrames == 90 &&
        value.targetVisible == 90;
    try {
      if (!restore) {
        await File('${folder.path}/long-source.mp4').copy(source.path);
        await File('${folder.path}/long-variant.mp4').copy(target.path);
        for (final key in [id, corrupt, mapping, mutated]) {
          await host(api.forgetCutout(key));
        }
        final frame = await open(7000000);
        final r = ComparisonCutoutSeedDto(
          sourcePtsUs: frame.teacherFrameUs!,
          x: .355,
          y: .855,
        );
        final t = ComparisonCutoutSeedDto(
          sourcePtsUs: frame.learnerFrameUs!,
          x: .324,
          y: .86,
        );
        await inspect(true, r, 'archive-reference-still');
        await inspect(false, t, 'archive-target-still');
        final watch = Stopwatch()..start();
        final analyzed = await host(api.prepareCutout(r, t, layout));
        require(all(analyzed), 'Archive analysis incomplete');
        report['analysisMs'] = watch.elapsedMilliseconds;
        await ready(analyzed.playback);
        await seekAnchor();
        final pixels = await capture('archive-original-anchor');
        require(
          (pixels['leftPixels'] as int) > 80 &&
              (pixels['rightPixels'] as int) > 80,
          'No canvas',
        );
        final save = Stopwatch()..start();
        require(await host(api.saveCutout(id)), 'Archive save failed');
        report['saveMs'] = save.elapsedMilliseconds;
        report['firstPid'] = pid;
        report['phase'] = 'saved';
        report['saved'] = true;
      } else {
        require(
          previous['firstPid'] != pid,
          'Expected a new process, not just a new player',
        );
        await open(7400000);
        final watch = Stopwatch()..start();
        final restored = await host(api.restoreCutout(id, layout));
        require(restored != null && all(restored), 'No complete saved archive');
        await ready(restored!.playback);
        report['restoreMs'] = watch.elapsedMilliseconds;
        require(
          restored.playback.positionUs == 7400000,
          'Restore changed selected playhead',
        );
        await seekAnchor();
        await capture('archive-restored-anchor');
        require(
          base64Encode(
                File(
                  '${folder.path}/archive-original-anchor.png',
                ).readAsBytesSync(),
              ) ==
              base64Encode(
                File(
                  '${folder.path}/archive-restored-anchor.png',
                ).readAsBytesSync(),
              ),
          'Restored anchor pixel mismatch',
        );
        report['anchorIdentical'] = true;
        report['coverage'] = [
          restored.referenceVisible,
          restored.targetVisible,
        ];
        for (final key in [corrupt, mapping, mutated]) {
          require(
            await host(api.saveCutout(key)),
            'Negative fixture save failed',
          );
        }
        report['export'] = await exportCanvas(
          'archive-restored',
          'archive-source.mp4',
          'archive-variant.mp4',
          6000000,
          9000000,
        );
        // Corrupt a disposable test-only archive. Production UI has no raw file API.
        final archiveRoot = Directory(
          Platform.isAndroid
              ? '${folder.parent.parent.path}/cache/comparison-cutout-v1'
              : '${Directory.systemTemp.parent.path}/Library/Caches/comparison-cutout-v1',
        );
        final corruptFile = File('${archiveRoot.path}/$corrupt.cutout');
        require(corruptFile.existsSync(), 'QA archive location absent');
        final writer = corruptFile.openSync(mode: FileMode.append);
        writer.writeByteSync(77);
        writer.closeSync();
        await open(7400000);
        require(
          await host(api.restoreCutout(corrupt, layout)) == null,
          'Corrupt archive was restored',
        );
        report['corruptionRejected'] = true;
        await open(7400000, offset: 33333);
        require(
          await host(api.restoreCutout(mapping, layout)) == null,
          'Changed mapping archive was restored',
        );
        report['mappingRejected'] = true;
        await open(7400000);
        // Hash must detect modified bytes even with identical URI and dimensions.
        await host(api.close());
        final sink = source.openSync(mode: FileMode.append);
        sink.writeByteSync(119);
        sink.closeSync();
        await open(7400000);
        require(
          await host(api.restoreCutout(mutated, layout)) == null,
          'Changed source archive was restored',
        );
        report['sourceMutationRejected'] = true;
        await host(api.forgetCutout(id));
        require(
          await host(api.restoreCutout(id, layout)) == null,
          'Forgotten archive was restored',
        );
        report['forgetPassed'] = true;
        report['phase'] = 'finished';
        report['passed'] = true;
      }
    } catch (error) {
      report['phase'] = 'failed';
      report['error'] = '$error';
    } finally {
      await host(api.close());
      texture = null;
      reportFile.writeAsStringSync(jsonEncode(report), flush: true);
      status = 'archive-${report['phase']}';
      notifyListeners();
    }
  }

  // Real-scene probe: incomplete tracking is an observed quality result,
  // not a passing release gate. Verify the exact native export admission.
  Future<void> realDanceProbe() async {
    final reportFile = File('${folder.path}/real-dance-report.json');
    final row = <String, Object?>{
      'finished': false,
      'passed': false,
      'pid': pid,
    };
    void save() => reportFile.writeAsStringSync(jsonEncode(row), flush: true);
    const projectId = 'qa_independent_dance';
    final source = '${folder.path}/real-reference.mp4';
    final target = '${folder.path}/real-target.mp4';
    try {
      final configFile = File('${folder.path}/real-dance-config.json');
      final config = configFile.existsSync()
          ? Map<String, dynamic>.from(
              jsonDecode(configFile.readAsStringSync()) as Map,
            )
          : <String, dynamic>{};
      final duration = (config['durationUs'] as int?) ?? 30000000;
      final cadence = (config['cadence'] as int?) ?? 25;
      final anchor = (config['anchorUs'] as int?) ?? 3000000;
      final ref = (config['referenceSeed'] as List?) ?? [.42, .29];
      final tar = (config['targetSeed'] as List?) ?? [.46, .56];
      final times =
          (config['samplesUs'] as List?)?.cast<int>() ??
          [
            0,
            1,
            2,
            3,
            5,
            8,
            10,
            12,
            15,
            18,
            20,
            22,
            25,
            28,
            29,
          ].map((v) => v * 1000000).toList();
      require(
        duration > 0 &&
            duration <= 60000000 &&
            cadence > 0 &&
            cadence <= 60 &&
            anchor >= 0 &&
            anchor < duration &&
            times.every((v) => v >= 0 && v < duration),
        'Invalid real-dance QA config',
      );
      row['config'] = {
        'durationUs': duration,
        'cadence': cadence,
        'anchorUs': anchor,
        'referenceSeed': ref,
        'targetSeed': tar,
        'samplesUs': times,
      };
      await host(api.forgetCutout(projectId));
      final before = await ready(
        await host(
          api.open(
            source,
            target,
            0,
            anchor,
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
        ),
      );
      require(before.durationUs == duration, 'Unexpected real-pair duration');
      row['anchor'] = [before.teacherFrameUs, before.learnerFrameUs];
      final ra = ComparisonCutoutSeedDto(
        sourcePtsUs: before.teacherFrameUs!,
        x: (ref[0] as num).toDouble(),
        y: (ref[1] as num).toDouble(),
      );
      final ta = ComparisonCutoutSeedDto(
        sourcePtsUs: before.learnerFrameUs!,
        x: (tar[0] as num).toDouble(),
        y: (tar[1] as num).toDouble(),
      );
      row['referenceStill'] = await inspect(true, ra, 'real-reference-still');
      row['targetStill'] = await inspect(false, ta, 'real-target-still');
      save();
      final watch = Stopwatch()..start();
      final pending = host(api.prepareCutout(ra, ta, null));
      unawaited(
        pending.then<void>((_) {}, onError: (Object _, StackTrace _) {}),
      );
      final partial = await waitForPartial();
      row['firstPlayableMs'] = watch.elapsedMilliseconds;
      row['partialRange'] = [partial.previewStartUs, partial.previewEndUs];
      await ready(partial.playback);
      await capture('real-partial-anchor');
      save();
      final result = await pending;
      row['analysisMs'] = watch.elapsedMilliseconds;
      row['visibility'] = [
        result.referenceVisible,
        result.referenceFrames,
        result.targetVisible,
        result.targetFrames,
      ];
      await ready(result.playback);
      require(
        result.referenceFrames == duration * cadence ~/ 1000000 &&
            result.targetFrames == duration * cadence ~/ 1000000,
        'Incomplete frame indexing',
      );
      final samples = <Map<String, Object?>>[];
      for (final us in times) {
        final frame = await ready(
          await host(api.control(ComparisonCommand.seek, us, 1)),
        );
        samples.add({
          'positionUs': us,
          'pts': [frame.teacherFrameUs, frame.learnerFrameUs],
          ...await capture(
            config.isEmpty
                ? 'real-sample-${us ~/ 1000000}'
                : 'real-sample-us-$us',
          ),
        });
      }
      row['samples'] = samples;
      final reviewLayout = config['referenceReviewLayout'];
      if (reviewLayout != null) {
        require(
          reviewLayout is List &&
              reviewLayout.length == 3 &&
              reviewLayout.every((v) => v is num && v.isFinite),
          'Invalid reference-only review layout',
        );
        final values = (reviewLayout as List).cast<num>();
        await ready(
          await host(
            api.setCutoutLayout(
              ComparisonCanvasLayoutDto(
                reference: ComparisonCanvasLayerDto(
                  x: values[0].toDouble(),
                  y: values[1].toDouble(),
                  height: values[2].toDouble(),
                  opacity: 1,
                  mirror: false,
                ),
                target: ComparisonCanvasLayerDto(
                  x: .5,
                  y: .85,
                  height: .3,
                  opacity: 0,
                  mirror: false,
                ),
                targetOnTop: false,
                background: 0,
              ),
            ),
          ),
        );
        final review = <Map<String, Object?>>[];
        for (final us in times) {
          final frame = await ready(
            await host(api.control(ComparisonCommand.seek, us, 1)),
          );
          review.add({
            'positionUs': us,
            'pts': [frame.teacherFrameUs, frame.learnerFrameUs],
            ...await capture('real-reference-review-us-$us'),
          });
        }
        row['referenceReviewLayout'] = values;
        row['referenceReviewSamples'] = review;
      }
      final full =
          result.referenceVisible == result.referenceFrames &&
          result.targetVisible == result.targetFrames;
      row['allVisible'] = full;
      if (!full) {
        require(
          !await host(api.saveCutout(projectId)),
          'Incomplete archive saved',
        );
        final beforeJob = (await host(api.getExport()))?.jobId;
        final spec = jsonEncode({
          'schemaVersion': 1,
          'id': projectId,
          'teacher': {'uri': source},
          'learner': {'uri': target},
          'offsetUs': 0,
          'timeScale': 1,
          'mirrorTeacher': false,
          'mirrorLearner': false,
        });
        bool rejected = false;
        try {
          await host(
            api.startCutoutExport(spec, 0, duration, 1440, 1080, 8, true, true),
          );
        } catch (_) {
          rejected = true;
        }
        require(
          rejected && (await host(api.getExport()))?.jobId == beforeJob,
          'Incomplete export admitted',
        );
        row['incompleteArchiveRejected'] = true;
        row['incompleteExportRejected'] = true;
      }
      await ready(await host(api.clearCutout()));
      row['ordinaryRestored'] = true;
      row['passed'] = true;
    } catch (error) {
      row['error'] = error.toString();
    } finally {
      await host(api.close());
      texture = null;
      row['finished'] = true;
      save();
      status = 'real-dance-finished';
      notifyListeners();
    }
  }

  Future<void> repairProbe() async {
    final report = <String, Object?>{'finished': false, 'passed': false};
    final file = File('${folder.path}/repair-report.json');
    void save() => file.writeAsStringSync(jsonEncode(report), flush: true);
    Future<ComparisonPlaybackDto> open(
      String r,
      String t,
      int start,
      int end,
      int position,
    ) async {
      await host(api.close());
      return ready(
        await host(
          api.open(
            '${folder.path}/$r',
            '${folder.path}/$t',
            0,
            position,
            1,
            320,
            240,
            true,
            ComparisonPracticeDto(
              mirrorTeacher: false,
              mirrorLearner: false,
              loopEnabled: true,
              loopStartUs: start,
              loopEndUs: end,
            ),
            1,
          ),
        ),
      );
    }

    Future<ComparisonPlaybackDto> seek(int pts) async =>
        ready(await host(api.control(ComparisonCommand.seek, pts, 1)));
    ComparisonCutoutSeedDto seed(
      ComparisonPlaybackDto s,
      bool ref,
      double x,
      double y,
    ) => ComparisonCutoutSeedDto(
      sourcePtsUs: (ref ? s.teacherFrameUs : s.learnerFrameUs)!,
      x: x,
      y: y,
    );
    Future<ComparisonCutoutResultDto> prepare(
      ComparisonPlaybackDto s,
      double rx,
      double ry,
      double tx,
      double ty,
    ) async {
      final r = seed(s, true, rx, ry), t = seed(s, false, tx, ty);
      await inspect(true, r, 'repair-initial-r');
      await inspect(false, t, 'repair-initial-t');
      final value = await host(api.prepareCutout(r, t, null));
      await ready(value.playback);
      return value;
    }

    Future<String> picture(int us, String name) async {
      await seek(us);
      await capture(name);
      return base64Encode(File('${folder.path}/$name.png').readAsBytesSync());
    }

    Future<ComparisonCutoutResultDto> repair(
      bool ref,
      int start,
      int end,
      int anchor,
      double x,
      double y,
      String id,
    ) async {
      await seek(anchor);
      final s = await ready(await host(api.beginCutoutRepair(ref, start, end)));
      final selected = seed(s, ref, x, y);
      await inspect(ref, selected, 'repair-selected-still');
      final v = await host(api.repairCutout(selected, id));
      await ready(v.playback);
      return v;
    }

    try {
      const id = 'qa_local_repair';
      await host(api.forgetCutout(id));
      final begin = await open(
        'long-source.mp4',
        'long-variant.mp4',
        6000000,
        9000000,
        7000000,
      );
      final initial = await prepare(begin, .355, .855, .324, .86);
      require(
        initial.referenceVisible == 90 && initial.targetVisible == 90,
        'Stable fixture not complete',
      );
      require(await host(api.saveCutout(id)), 'Initial archive save failed');
      final left = await picture(7000000, 'repair-before-left');
      final right = await picture(8800000, 'repair-before-right');
      final inside = await picture(8000000, 'repair-before-inside');
      var original = await ready(
        await host(api.beginCutoutRepair(true, 7600000, 8400000)),
      );
      final s = seed(original, true, .355, .855);
      await inspect(true, s, 'repair-cancel-still');
      final cancelled = await host(api.cancelCutoutRepair());
      await ready(cancelled.playback);
      require(
        cancelled.referenceVisible == 90 &&
            cancelled.targetVisible == 90 &&
            await picture(8000000, 'repair-cancelled') == inside,
        'Cancel changed original canvas',
      );
      report['cancelPreserved'] = true;
      save();
      final watch = Stopwatch()..start();
      final patched = await repair(
        true,
        7600000,
        8400000,
        8000000,
        .355,
        .855,
        id,
      );
      report['patchElapsedMs'] = watch.elapsedMilliseconds;
      require(
        patched.referenceFrames == 90 &&
            patched.targetFrames == 90 &&
            patched.referenceVisible == 90 &&
            patched.targetVisible == 90,
        'Repair lost stable frames',
      );
      require(
        await picture(7000000, 'repair-after-left') == left &&
            await picture(8800000, 'repair-after-right') == right,
        'Outside patch changed',
      );
      report['outsideIdentical'] = true;
      require(await host(api.saveCutout(id)), 'Repaired archive save failed');
      await open(
        'long-source.mp4',
        'long-variant.mp4',
        6000000,
        9000000,
        8000000,
      );
      final restored = await host(api.restoreCutout(id, null));
      require(
        restored != null &&
            restored.referenceVisible == 90 &&
            restored.targetVisible == 90,
        'Repaired archive not restorable',
      );
      await ready(restored!.playback);
      require(
        await picture(7000000, 'repair-restored-left') == left,
        'Restored seam changed',
      );
      report['archiveRestored'] = true;
      report['export'] = await exportCanvas(
        'repair-restored',
        'long-source.mp4',
        'long-variant.mp4',
        6000000,
        9000000,
      );
      await host(api.forgetCutout(id));
      save();

      // Actual two-person visual correction. Temporarily insert the clearly
      // distinct red-shirt spectator into a short interval in the QA canvas,
      // then re-anchor the black-clad dancer at the identical PTS.
      const realId = 'qa_real_local_repair';
      await host(api.forgetCutout(realId));
      final real = await open(
        'real-reference.mp4',
        'real-target.mp4',
        2000000,
        5000000,
        3000000,
      );
      final realInitial = await prepare(real, .42, .29, .46, .56);
      report['realInitial'] = [
        realInitial.referenceVisible,
        realInitial.targetVisible,
      ];
      final outside = await picture(3000000, 'repair-real-original-outside');
      final before = await picture(4000000, 'repair-real-original-anchor');
      final wrong = await repair(
        true,
        3800000,
        4240000,
        4000000,
        .80,
        .23,
        realId,
      );
      final wrongImage = await picture(4000000, 'repair-real-wrong');
      require(
        wrongImage != before,
        'Distinct-person patch did not change the canvas',
      );
      require(
        await picture(3000000, 'repair-real-wrong-outside') == outside,
        'Wrong patch escaped range',
      );
      final fixed = await repair(
        true,
        3800000,
        4240000,
        4000000,
        .47,
        .28,
        realId,
      );
      final fixedImage = await picture(4000000, 'repair-real-corrected');
      require(
        fixedImage != wrongImage,
        'Re-anchor did not change wrong person',
      );
      require(
        await picture(3000000, 'repair-real-corrected-outside') == outside,
        'Corrected patch escaped range',
      );
      require(
        fixed.referenceFrames == realInitial.referenceFrames &&
            fixed.targetFrames == realInitial.targetFrames &&
            fixed.targetVisible == realInitial.targetVisible &&
            wrong.targetVisible == realInitial.targetVisible,
        'Other role changed',
      );
      report['realDistinctSubjectReplaced'] = true;
      report['realOutsideIdentical'] = true;
      report['realCorrectedMatchesOriginalAnchor'] = fixedImage == before;
      report['realCoverageAfter'] = [
        fixed.referenceVisible,
        fixed.targetVisible,
      ];
      await host(api.forgetCutout(realId));
      report['passed'] = true;
    } catch (e) {
      report['error'] = '$e';
    } finally {
      await host(api.close());
      texture = null;
      report['finished'] = true;
      save();
      status = 'repair-finished';
      notifyListeners();
    }
  }

  Future<void> run() async {
    folder = Directory(
      '${Directory.systemTemp.parent.path}/Documents/comparison-cutout-fixtures',
    )..createSync(recursive: true);
    final until = DateTime.now().add(const Duration(seconds: 45));
    while (!File('${folder.path}/source.mp4').existsSync() ||
        !File('${folder.path}/variant.mp4').existsSync()) {
      if (DateTime.now().isAfter(until)) {
        status = 'fixtures_missing';
        results.add({'passed': false});
        publish(true);
        return;
      }
      await Future<void>.delayed(const Duration(milliseconds: 200));
    }
    if (perfQa) {
      final file = File('${folder.path}/cancel-report.json');
      try {
        file.writeAsStringSync(
          jsonEncode(await cancellationProbe()),
          flush: true,
        );
      } catch (error) {
        file.writeAsStringSync(
          jsonEncode({'passed': false, 'error': '$error'}),
          flush: true,
        );
      }
      await repairProbe();
      return;
    }
    if (repairQa) {
      await repairProbe();
      return;
    }
    if (realQa) {
      await realDanceProbe();
      return;
    }
    if (persistQa) {
      await archiveProbe();
      return;
    }
    if (cancelPartial) {
      status = 'cancel-partial';
      final file = File('${folder.path}/cancel-report.json');
      try {
        file.writeAsStringSync(
          jsonEncode(await cancellationProbe()),
          flush: true,
        );
      } catch (error) {
        file.writeAsStringSync(
          jsonEncode({'passed': false, 'error': '$error'}),
          flush: true,
        );
      }
      status = 'finished';
      notifyListeners();
      return;
    }
    if (fullLength) {
      require(
        File('${folder.path}/long-source.mp4').existsSync() &&
            File('${folder.path}/long-variant.mp4').existsSync(),
        'Full fixtures missing',
      );
      await probe(
        'full-length',
        'long-variant.mp4',
        .324,
        10000000,
        source: 'long-source.mp4',
        exportWidth: 1440,
        exportHeight: 1080,
      );
      status = 'finished';
      publish(true);
      return;
    }
    if (extended) {
      require(
        File('${folder.path}/long-source.mp4').existsSync() &&
            File('${folder.path}/long-variant.mp4').existsSync(),
        'Extended fixtures missing',
      );
      await probe(
        'extended-ab',
        'long-variant.mp4',
        .324,
        10000000,
        source: 'long-source.mp4',
        window: (6000000, 11000000),
        exportWidth: 1440,
        exportHeight: 1080,
      );
      status = 'finished';
      publish(true);
      return;
    }
    await probe('mirrored', 'variant.mp4', .324, 0);
    await probe('middle', 'source.mp4', .676, 400000);
    if (File('${folder.path}/long-source.mp4').existsSync() &&
        File('${folder.path}/long-variant.mp4').existsSync()) {
      await probe(
        'long-ab',
        'long-variant.mp4',
        .324,
        6000000,
        source: 'long-source.mp4',
        window: (6000000, 7000000),
      );
    }
    try {
      final result = await mutationProbe();
      File(
        '${folder.path}/mutation-report.json',
      ).writeAsStringSync(jsonEncode(result), flush: true);
    } catch (error) {
      File('${folder.path}/mutation-report.json').writeAsStringSync(
        jsonEncode({'passed': false, 'error': '$error'}),
        flush: true,
      );
    }
    status = 'finished';
    publish(true);
  }
}
