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
      value.timeout(const Duration(seconds: 90));
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
    File('${folder.path}/report.json').writeAsStringSync(
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

  Future<void> probe(String id, String target, double x, int position) async {
    final row = <String, Object?>{'id': id};
    results.add(row);
    status = id;
    publish(false);
    try {
      final practice = ComparisonPracticeDto(
        mirrorTeacher: false,
        mirrorLearner: target == 'variant.mp4',
        loopEnabled: false,
        teacherPicture: ComparisonPictureDto(zoom: 1, panX: 0, panY: 0),
        learnerPicture: ComparisonPictureDto(zoom: 1, panX: 0, panY: 0),
      );
      var state = await ready(
        await host(
          api.open(
            '${folder.path}/source.mp4',
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
      row['ordinary'] = await capture('$id-ordinary');
      final watch = Stopwatch()..start();
      final result = await host(
        api.prepareCutout(
          ComparisonCutoutSeedDto(
            sourcePtsUs: state.teacherFrameUs!,
            x: .355,
            y: .855,
          ),
          ComparisonCutoutSeedDto(
            sourcePtsUs: state.learnerFrameUs!,
            x: x,
            y: .86,
          ),
        ),
      );
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
            result.referenceFrames >= 29 &&
            result.targetFrames >= 29,
        'Some tracked frames were missing',
      );
      for (final us in [0, 400000, 800000]) {
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
      require(playing.positionUs > 0, 'Cutout playback clock did not advance');
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
    await probe('mirrored', 'variant.mp4', .324, 0);
    await probe('middle', 'source.mp4', .676, 400000);
    status = 'finished';
    publish(true);
  }
}
