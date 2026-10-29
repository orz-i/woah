import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'package:app/features/comparison/data/comparison_gateway.dart';
import 'package:app/features/comparison/presentation/comparison_controller.dart';
import 'package:dance_domain/dance_domain.dart';
import 'package:dance_native/dance_native.dart';
import 'package:flutter/material.dart';

// Controlled reply timing over the real native player, using synthetic clips
// and an in-memory project store. Never load or save the user's project library.
class ProbeGateway extends NativeComparisonGateway {
  ProbeGateway(this.saved);
  ComparisonProject saved;
  final started = Completer<void>(), release = Completer<void>();
  String? hold;
  bool held = false;

  Future<ComparisonPlaybackDto> gate(
    String operation,
    Future<ComparisonPlaybackDto> work,
  ) async {
    final result = await work;
    if (!held && hold == operation) {
      held = true;
      started.complete();
      await release.future;
    }
    return result;
  }

  @override
  Future<ComparisonLibrary> load() async => ComparisonLibrary([saved], 0);
  @override
  Future<void> save(ComparisonProject project) async => saved = project;
  @override
  Future<ComparisonExportDto?> getExport() async => null;
  @override
  Future<MusicAlignmentDto> align(ComparisonProject project) async =>
      MusicAlignmentDto(
        status: 'matched',
        message: 'synthetic mapping',
        candidates: [
          MusicCandidateDto(
            offsetUs: 2000000,
            timeScale: .9,
            score: 1,
            consistentWindows: 3,
            supportedSeconds: 10,
            residualMs: 0,
          ),
        ],
      );

  @override
  Future<ComparisonPlaybackDto> open(
    ComparisonProject project,
    int width,
    int height,
    bool horizontal,
  ) async {
    await super.open(project, width, height, horizontal);
    return ready();
  }

  Future<ComparisonPlaybackDto> ready() async {
    final deadline = DateTime.now().add(const Duration(seconds: 30));
    while (DateTime.now().isBefore(deadline)) {
      final result = await state();
      if (result.phase == 'error') throw StateError(result.error ?? 'native');
      if (result.phase == 'paused') return result;
      await Future<void>.delayed(const Duration(milliseconds: 40));
    }
    throw StateError('Native player readiness timed out');
  }

  @override
  Future<ComparisonPlaybackDto> setPractice(ComparisonProject project) =>
      gate('practice', super.setPractice(project));
  @override
  Future<ComparisonPlaybackDto> setAlignment(int offset, double scale) =>
      gate('alignment', super.setAlignment(offset, scale));
  @override
  Future<ComparisonPlaybackDto> control(
    ComparisonCommand command,
    int value,
    double speed,
  ) => gate(
    command == ComparisonCommand.seek && value == 5000000 ? 'seek' : 'control',
    super.control(command, value, speed),
  );
}

void check(bool result, String message) {
  if (!result) throw StateError(message);
}

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(
    const MaterialApp(
      home: Scaffold(body: Center(child: Text('异步会话回归'))),
    ),
  );
  // Native offscreen textures and Pigeon do not require a visible Flutter frame.
  // Waiting for one would stall the probe on an otherwise usable locked device.
  if (Platform.isIOS) await WidgetsBinding.instance.endOfFrame;
  final fixtures = Directory(
    Platform.isIOS
        ? '${Directory.systemTemp.parent.path}/Documents/comparison-fixtures'
        : '${Directory.systemTemp.parent.path}/files/comparison-async-fixtures',
  );
  final cases = <Map<String, Object?>>[];
  final report = <String, Object?>{
    'platform': Platform.operatingSystem,
    'controlledDartReplyTiming': true,
    'nativePlayback': true,
    'userProjectStoreAccessed': false,
    'cases': cases,
  };
  final original = ComparisonProject(
    id: 'async_synthetic',
    teacher: ComparisonSource(
      uri: File('${fixtures.path}/teacher.mp4').uri.toString(),
      name: 'synthetic reference',
      durationUs: 24000000,
      hasAudio: true,
    ),
    learner: ComparisonSource(
      uri: File('${fixtures.path}/learner.mp4').uri.toString(),
      name: 'synthetic target',
      durationUs: 19600000,
      hasAudio: true,
    ),
    offsetUs: 1200000,
    timeScale: .8,
    alignment: 'manual',
    positionUs: 2000000,
    teacherAnchorUs: 0,
    learnerAnchorUs: 1200000,
    loopStartUs: 2000000,
    loopEndUs: 5000000,
    loopEnabled: true,
    mirrorLearner: true,
    teacherPicture: const ComparisonPicture(zoom: 1.25, panX: .1),
    updatedAtMs: 1,
  );
  Future<void> run(
    String name,
    Future<Map<String, Object?>> Function(ComparisonController, ProbeGateway)
    action,
  ) async {
    final gateway = ProbeGateway(original);
    final controller = ComparisonController(gateway);
    try {
      await controller.initialize();
      controller.setViewport(320, 240, true);
      final deadline = DateTime.now().add(const Duration(seconds: 30));
      while (!controller.canPlay && DateTime.now().isBefore(deadline)) {
        await Future<void>.delayed(const Duration(milliseconds: 40));
      }
      check(controller.canPlay, 'Controller preview did not become ready');
      cases.add({
        'name': name,
        ...await action(controller, gateway),
        'passed': true,
      });
    } finally {
      if (!gateway.release.isCompleted) gateway.release.complete();
      await controller.finish();
      controller.dispose();
    }
  }

  try {
    await run('late_practice_cannot_restore_closed_project', (c, g) async {
      g.hold = 'practice';
      final pending = c.setMirror(true);
      await g.started.future.timeout(const Duration(seconds: 30));
      await c.newProject();
      final createdId = c.project.id;
      g.release.complete();
      await pending;
      check(
        c.project.id == createdId && !c.project.hasPair,
        'Old project restored',
      );
      check(c.playback == null && c.error == null, 'Old preview restored');
      return {'newProjectRetained': true, 'oldPlaybackIgnored': true};
    });
    await run('late_seek_cannot_change_restored_position', (c, g) async {
      g.hold = 'seek';
      final pending = c.seek(5000000);
      await g.started.future.timeout(const Duration(seconds: 30));
      final next = ComparisonProject.fromJson({
        ...original.toJson(),
        'id': 'async_synthetic_two',
      });
      await c.selectProject(next);
      g.release.complete();
      await pending;
      await c.save();
      check(
        c.project.id == next.id && g.saved.positionUs == 2000000,
        'Old position saved',
      );
      final native = await g.ready();
      check(
        (native.positionUs - 2000000).abs() < 50000,
        'Native position changed',
      );
      return {
        'savedPositionUs': g.saved.positionUs,
        'nativePositionUs': native.positionUs,
      };
    });
    await run('cancelled_mapping_restores_native_and_project', (c, g) async {
      g.hold = 'alignment';
      final pending = c.matchMusic();
      await g.started.future.timeout(const Duration(seconds: 30));
      final cancelled = c.cancelMatching();
      g.release.complete();
      await Future.wait([pending, cancelled]);
      final native = await g.ready();
      check(
        c.project.offsetUs == original.offsetUs &&
            c.project.timeScale == original.timeScale &&
            g.saved.alignment == 'manual',
        'Cancelled mapping committed',
      );
      check(
        native.offsetUs == original.offsetUs &&
            (native.timeScale! - original.timeScale).abs() < .000001,
        'Native mapping was not restored',
      );
      check(
        (native.positionUs - original.positionUs).abs() < 50000,
        'Native source position was not restored',
      );
      check(
        c.project.loopStartUs == 2000000 &&
            c.project.mirrorLearner &&
            c.project.teacherPicture.zoom == 1.25,
        'Practice settings were not restored',
      );
      check(c.message.contains('已取消'), 'Cancellation message overwritten');
      final result = <String, Object?>{
        'restoredOffsetUs': native.offsetUs,
        'restoredTimeScale': native.timeScale,
        'restoredPositionUs': native.positionUs,
        'selectionAndPicturesRetained': true,
      };
      await c.matchMusic();
      check(
        c.project.offsetUs == 2000000 &&
            c.project.timeScale == .9 &&
            g.saved.alignment == 'auto',
        'Retry failed',
      );
      result['retryCommitted'] = true;
      return result;
    });
    report['passed'] = cases.length == 3;
  } catch (error, stack) {
    report.addAll({'passed': false, 'error': '$error', 'stack': '$stack'});
  } finally {
    File(
      '${fixtures.path}/comparison-async-regression.json',
    ).writeAsStringSync(const JsonEncoder.withIndent('  ').convert(report));
  }
}
