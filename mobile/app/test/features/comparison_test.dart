import 'dart:async';
import 'dart:convert';
import 'package:app/features/comparison/data/comparison_gateway.dart';
import 'package:app/features/comparison/presentation/comparison_controller.dart';
import 'package:app/features/comparison/presentation/comparison_screen.dart';
import 'package:dance_domain/dance_domain.dart';
import 'package:dance_native/dance_native.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

const teacher = ComparisonSource(
  uri: 'content://test/teacher',
  name: '老师.mp4',
  durationUs: 12000000,
  hasAudio: true,
);
const learner = ComparisonSource(
  uri: 'content://test/learner',
  name: '学生.mp4',
  durationUs: 15000000,
  hasAudio: true,
);
ComparisonProject savedProject() => const ComparisonProject(
  id: 'test_project',
  title: '我的练舞',
  teacher: teacher,
  learner: learner,
  offsetUs: 2000000,
  alignment: 'manual',
  teacherAnchorUs: 0,
  learnerAnchorUs: 2000000,
  positionUs: 1000000,
  updatedAtMs: 1,
);
MusicAlignmentDto matched() => MusicAlignmentDto(
  status: 'matched',
  message: '已按共同音乐对齐',
  candidates: [
    MusicCandidateDto(
      offsetUs: 3000000,
      score: .97,
      consistentWindows: 3,
      supportedSeconds: 10,
      residualMs: 0,
    ),
  ],
);

class FakeComparisonGateway implements ComparisonGateway {
  FakeComparisonGateway({List<ComparisonProject>? saved})
    : projects = saved ?? [];
  List<ComparisonProject> projects;
  ComparisonSource? picked;
  int opens = 0, cancels = 0, saves = 0;
  bool failSave = false;
  Completer<void>? firstSaveGate;
  Completer<MusicAlignmentDto>? pendingMatch;
  ComparisonPlaybackDto current = ComparisonPlaybackDto(
    textureId: 7,
    phase: 'paused',
    positionUs: 0,
    durationUs: 12000000,
    teacherStartUs: 0,
    offsetUs: 0,
    speed: 1,
  );
  @override
  Future<ComparisonSource?> pick(String role) async => picked;
  @override
  Future<ComparisonLibrary> load() async => ComparisonLibrary(projects, 0);
  @override
  Future<void> save(ComparisonProject project) async {
    saves++;
    if (saves == 1 && firstSaveGate != null) await firstSaveGate!.future;
    if (failSave) throw StateError('disk full');
    projects = [project, ...projects.where((p) => p.id != project.id)];
  }

  @override
  Future<ComparisonPlaybackDto> open(
    ComparisonProject p,
    int w,
    int h,
    bool horizontal,
  ) async {
    opens++;
    current.offsetUs = p.offsetUs;
    current.positionUs = p.positionUs;
    current.speed = p.speed;
    return current;
  }

  @override
  Future<ComparisonPlaybackDto> control(
    ComparisonCommand command,
    int valueUs,
    double speed,
  ) async {
    switch (command) {
      case ComparisonCommand.play:
        current.phase = 'playing';
      case ComparisonCommand.pause:
        current.phase = 'paused';
      case ComparisonCommand.seek:
        current.positionUs = valueUs;
      case ComparisonCommand.setOffset:
        current.offsetUs = valueUs;
      case ComparisonCommand.setSpeed:
        current.speed = speed;
    }
    return current;
  }

  @override
  Future<ComparisonPlaybackDto> resize(int w, int h, bool horizontal) async =>
      current;
  @override
  Future<ComparisonPlaybackDto> state() async => current;
  @override
  Future<MusicAlignmentDto> align(ComparisonProject p) =>
      pendingMatch?.future ?? Future.value(matched());
  @override
  Future<void> cancelAlignment() async {
    cancels++;
  }

  @override
  Future<void> close() async {}
}

void main() {
  test(
    'projects round trip, source swap preserves manual event and reverses offset',
    () {
      final source = savedProject();
      final restored = ComparisonProject.fromJson(
        jsonDecode(jsonEncode(source.toJson())) as Map<String, dynamic>,
      );
      expect(restored.offsetUs, 2000000);
      expect(restored.positionUs, 1000000);
      final swapped = restored.swapSources();
      expect(swapped.teacher!.uri, learner.uri);
      expect(swapped.offsetUs, -2000000);
      expect(swapped.teacherAnchorUs, 2000000);
      expect(swapped.learnerAnchorUs, 0);
      expect(swapped.positionUs, 1000000);
      final invalid = source.toJson()..['schemaVersion'] = 999;
      expect(() => ComparisonProject.fromJson(invalid), throwsFormatException);
    },
  );
  test('manual source times are exact and malformed values are rejected', () {
    expect(parseComparisonTime('1:05.123456'), 65123456);
    for (final value in [
      '-1',
      'NaN',
      '1e3',
      '1:60',
      '1.0000001',
      '999999999999999999999',
    ]) {
      expect(() => parseComparisonTime(value), throwsFormatException);
    }
  });
  test(
    'canceling a picker retains saved calibration and source references',
    () async {
      final gateway = FakeComparisonGateway(saved: [savedProject()]);
      final controller = ComparisonController(gateway);
      addTearDown(controller.dispose);
      await controller.initialize();
      await controller.pick('teacher');
      expect(controller.project.offsetUs, 2000000);
      expect(controller.project.teacher!.uri, teacher.uri);
      expect(controller.project.alignment, 'manual');
    },
  );
  test(
    'new source pair automatically matches and saves the resulting music offset',
    () async {
      final gateway = FakeComparisonGateway();
      final controller = ComparisonController(gateway);
      addTearDown(controller.dispose);
      controller.setViewport(640, 480, false);
      await controller.initialize();
      gateway.picked = teacher;
      await controller.pick('teacher');
      gateway.picked = learner;
      await controller.pick('learner');
      expect(controller.project.offsetUs, 3000000);
      expect(controller.project.alignment, 'auto');
      expect(gateway.projects.single.offsetUs, 3000000);
      expect(gateway.opens, 1);
    },
  );
  test(
    'a canceled alignment result cannot replace a later user decision',
    () async {
      final gateway = FakeComparisonGateway(saved: [savedProject()])
        ..pendingMatch = Completer<MusicAlignmentDto>();
      final controller = ComparisonController(gateway);
      addTearDown(controller.dispose);
      await controller.initialize();
      final running = controller.matchMusic();
      await Future<void>.delayed(Duration.zero);
      await controller.cancelMatching();
      gateway.pendingMatch!.complete(matched());
      await running;
      expect(controller.project.offsetUs, 2000000);
      expect(controller.project.alignment, 'manual');
    },
  );
  test(
    'serialized saves keep the latest manual calibration and failures prevent exit',
    () async {
      final gateway = FakeComparisonGateway(saved: [savedProject()])
        ..firstSaveGate = Completer<void>();
      final controller = ComparisonController(gateway);
      addTearDown(controller.dispose);
      await controller.initialize();
      final earlier = controller.save();
      final later = controller.manual(1000000, 4000000);
      await Future<void>.delayed(Duration.zero);
      gateway.firstSaveGate!.complete();
      await Future.wait([earlier, later]);
      expect(gateway.projects.single.offsetUs, 3000000);
      expect(gateway.projects.single.teacherAnchorUs, 1000000);
      gateway.failSave = true;
      expect(await controller.finish(), isFalse);
      expect(controller.error, contains('保存失败'));
    },
  );
  test(
    'a failed save keeps the current project when starting or switching',
    () async {
      final gateway = FakeComparisonGateway(saved: [savedProject()]);
      final controller = ComparisonController(gateway);
      addTearDown(controller.dispose);
      await controller.initialize();
      gateway.failSave = true;
      await controller.newProject();
      expect(controller.project.id, 'test_project');
      await controller.selectProject(
        const ComparisonProject(id: 'another', updatedAtMs: 2),
      );
      expect(controller.project.id, 'test_project');
      expect(controller.error, contains('保存失败'));
    },
  );
  testWidgets(
    'paused polling does not continually rebuild accessibility state',
    (tester) async {
      final gateway = FakeComparisonGateway(saved: [savedProject()]);
      final controller = ComparisonController(gateway);
      controller.setViewport(640, 480, false);
      await controller.initialize();
      await tester.pump(const Duration(milliseconds: 400));
      var updates = 0;
      controller.addListener(() => updates++);
      await tester.pump(const Duration(milliseconds: 800));
      expect(updates, 0);
      gateway.current = ComparisonPlaybackDto(
        textureId: 7,
        phase: 'playing',
        positionUs: 1500000,
        durationUs: 12000000,
        teacherStartUs: 0,
        offsetUs: 2000000,
        speed: 1,
      );
      await tester.pump(const Duration(milliseconds: 200));
      expect(updates, 1);
      expect(controller.project.positionUs, 1500000);
      controller.dispose();
    },
  );
  testWidgets(
    'formal page restores a project and keeps controls usable in a small portrait viewport',
    (tester) async {
      tester.view.physicalSize = const Size(320, 640);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final gateway = FakeComparisonGateway(saved: [savedProject()]);
      await tester.pumpWidget(
        MaterialApp(home: ComparisonScreen(gateway: gateway)),
      );
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pump();
      expect(find.text('舞蹈对比'), findsOneWidget);
      expect(find.text('老师（参考）'), findsOneWidget);
      expect(find.byKey(const ValueKey('comparison-stage')), findsOneWidget);
      expect(find.byKey(const ValueKey('comparison-manual')), findsOneWidget);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump();
    },
  );
  testWidgets('manual input applies both source times to the saved project', (
    tester,
  ) async {
    final gateway = FakeComparisonGateway(saved: [savedProject()]);
    await tester.pumpWidget(
      MaterialApp(home: ComparisonScreen(gateway: gateway)),
    );
    await tester.pump(const Duration(milliseconds: 400));
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('comparison-manual')));
    await tester.pumpAndSettle();
    await tester.enterText(
      find.byKey(const ValueKey('comparison-anchor-teacher')),
      '1.25',
    );
    await tester.enterText(
      find.byKey(const ValueKey('comparison-anchor-learner')),
      '3.5',
    );
    await tester.tap(find.text('应用校准'));
    await tester.pumpAndSettle();
    expect(gateway.projects.first.offsetUs, 2250000);
    expect(gateway.projects.first.teacherAnchorUs, 1250000);
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pump();
  });
}
