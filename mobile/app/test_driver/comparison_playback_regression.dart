import 'dart:convert';
import 'dart:io';
import 'package:dance_native/dance_native.dart';
import 'package:flutter/material.dart';

// Run only in the isolated iOS comparison validation simulator. Copy the
// synthetic music-rate fixtures to Documents/comparison-fixtures first.
// This entry never reads or writes a user's project.
Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(
    const MaterialApp(
      home: Scaffold(body: Center(child: Text('播放边界回归'))),
    ),
  );
  await WidgetsBinding.instance.endOfFrame;
  final api = DanceComparisonApi();
  final cases = <Map<String, Object?>>[];
  final report = <String, Object?>{
    'platform': Platform.operatingSystem,
    'cases': cases,
  };
  try {
    if (!Platform.isIOS) {
      throw StateError('Use the isolated iOS comparison simulator');
    }
    final fixtures = Directory(
      '${Directory.systemTemp.parent.path}/Documents/comparison-fixtures',
    );
    const offsetUs = 1200000;
    const timeScale = .8;
    ComparisonPracticeDto practice([int? a, int? b]) => ComparisonPracticeDto(
      mirrorTeacher: false,
      mirrorLearner: false,
      loopEnabled: a != null,
      loopStartUs: a,
      loopEndUs: b,
    );
    final opened = await api.open(
      File('${fixtures.path}/teacher.mp4').uri.toString(),
      File('${fixtures.path}/learner.mp4').uri.toString(),
      offsetUs,
      0,
      1,
      320,
      240,
      true,
      practice(),
      timeScale,
    );
    final end = opened.durationUs;
    if (end < 4000000) {
      throw StateError('Fixture must contain at least four seconds');
    }
    report.addAll({
      'durationUs': end,
      'timeScale': timeScale,
      'offsetUs': offsetUs,
    });
    Future<void> check(
      String name,
      ComparisonPracticeDto selection,
      int position,
      int minimum,
      int maximum,
    ) async {
      await api.control(ComparisonCommand.pause, 0, 1);
      await api.setPractice(selection);
      await api.control(ComparisonCommand.seek, position, 1);
      final state = await api.control(ComparisonCommand.play, 0, 1);
      cases.add({
        'name': name,
        'initialPositionUs': position,
        'actualPositionUs': state.positionUs,
        'minimumUs': minimum,
        'exclusiveMaximumUs': maximum,
        'passed': state.positionUs >= minimum && state.positionUs < maximum,
      });
      await api.control(ComparisonCommand.pause, 0, 1);
    }

    await check(
      'final_100ms_loop_stays_in_selection',
      practice(end - 100000, end),
      end - 100000,
      end - 100000,
      end,
    );
    await check(
      'wider_tail_loop_resumes_current_position',
      practice(end - 3000000, end),
      end - 100000,
      end - 150000,
      end,
    );
    await check(
      'outside_loop_restarts_at_a',
      practice(end - 3000000, end),
      0,
      end - 3000000,
      end - 2700000,
    );
    await check(
      'full_clip_without_loop_replays_from_zero',
      practice(),
      end - 100000,
      0,
      300000,
    );
    report['passed'] =
        cases.length == 4 && cases.every((entry) => entry['passed'] == true);
  } catch (error, stack) {
    report.addAll({
      'passed': false,
      'error': error.toString(),
      'stack': stack.toString(),
    });
  } finally {
    await api.close();
    final file = File(
      '${Directory.systemTemp.parent.path}/Documents/comparison-playback-regression.json',
    );
    file.writeAsStringSync(const JsonEncoder.withIndent('  ').convert(report));
  }
}
