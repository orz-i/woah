import 'dart:async';
import 'package:app/features/comparison/presentation/comparison_controller.dart';
import 'package:app/features/comparison/presentation/comparison_screen.dart';
import 'package:dance_domain/dance_domain.dart';
import 'package:dance_native/dance_native.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'comparison_test.dart' show FakeComparisonGateway, savedProject, matched;

ComparisonPlaybackDto state(int position, {int offset = 2000000}) =>
    ComparisonPlaybackDto(
      textureId: 7,
      phase: 'paused',
      positionUs: position,
      durationUs: 12000000,
      teacherStartUs: 0,
      offsetUs: offset,
      speed: 1,
      timeScale: 1,
    );

class HeldPracticeGateway extends FakeComparisonGateway {
  HeldPracticeGateway() : super(saved: [savedProject()]);
  final result = Completer<ComparisonPlaybackDto>();
  final started = Completer<void>();
  @override
  Future<ComparisonPlaybackDto> setPractice(ComparisonProject project) {
    if (!started.isCompleted) {
      started.complete();
      return result.future;
    }
    return super.setPractice(project);
  }
}

class HeldSeekGateway extends FakeComparisonGateway {
  HeldSeekGateway() : super(saved: [savedProject()]);
  final result = Completer<ComparisonPlaybackDto>();
  @override
  Future<ComparisonPlaybackDto> control(
    ComparisonCommand command,
    int value,
    double speed,
  ) {
    if (command == ComparisonCommand.seek && value == 5000000) {
      return result.future;
    }
    return super.control(command, value, speed);
  }
}

enum ApplyStage { alignment, practice, seek, save }

class HeldMappingGateway extends FakeComparisonGateway {
  HeldMappingGateway(this.stage)
    : super(
        saved: [
          savedProject().copyWith(
            offsetUs: 0,
            alignment: 'unmatched',
            clearAnchors: true,
            loopStartUs: 2000000,
            loopEndUs: 5000000,
            loopEnabled: true,
            mirrorLearner: true,
            teacherPicture: const ComparisonPicture(zoom: 1.5, panX: .1),
          ),
        ],
      ) {
    pendingMatch = Completer<MusicAlignmentDto>();
  }
  final ApplyStage stage;
  final applying = Completer<void>();
  final release = Completer<void>();
  bool held = false;
  Future<void> hold(ApplyStage current) async {
    if (held || current != stage) return;
    held = true;
    applying.complete();
    await release.future;
  }

  @override
  Future<ComparisonPlaybackDto> setAlignment(int offset, double scale) async {
    final result = await super.setAlignment(offset, scale);
    await hold(ApplyStage.alignment);
    return result;
  }

  @override
  Future<ComparisonPlaybackDto> setPractice(ComparisonProject project) async {
    final result = await super.setPractice(project);
    await hold(ApplyStage.practice);
    return result;
  }

  @override
  Future<ComparisonPlaybackDto> control(
    ComparisonCommand command,
    int value,
    double speed,
  ) async {
    final result = await super.control(command, value, speed);
    if (command == ComparisonCommand.seek) await hold(ApplyStage.seek);
    return result;
  }

  @override
  Future<void> save(ComparisonProject project) async {
    await hold(ApplyStage.save);
    await super.save(project);
  }
}

class FailedRollbackGateway extends HeldMappingGateway {
  FailedRollbackGateway() : super(ApplyStage.save);
  bool rejectRollback = true, candidateWritten = false;
  @override
  Future<void> save(ComparisonProject project) async {
    if (candidateWritten && rejectRollback) throw StateError('disk full');
    await super.save(project);
    candidateWritten = true;
  }
}

ComparisonController screenController(WidgetTester tester) =>
    (tester.state(find.byType(ComparisonScreen)) as dynamic).controller
        as ComparisonController;

void main() {
  testWidgets('a failed old practice reopen cannot report into a new session', (
    tester,
  ) async {
    final gateway = HeldPracticeGateway();
    final controller = ComparisonController(gateway);
    try {
      controller.setViewport(320, 240, false);
      await controller.initialize();
      final pending = controller.setMirror(true);
      final oldReopen = Completer<ComparisonPlaybackDto>();
      gateway.pendingOpens.add(oldReopen);
      gateway.result.completeError(StateError('practice rebuild failed'));
      await tester.pump();
      expect(gateway.pendingOpens, isEmpty);
      // Reopening the same saved ID is still a different player session.
      await controller.selectProject(
        savedProject().copyWith(positionUs: 2000000),
      );
      oldReopen.complete(state(1000000));
      await pending;
      expect(controller.project.positionUs, 2000000);
      expect(controller.error, isNull);
    } finally {
      controller.dispose();
    }
  });
  testWidgets('failed durable rollback remains visible and can be retried', (
    tester,
  ) async {
    final gateway = FailedRollbackGateway()..projects = [savedProject()];
    final controller = ComparisonController(gateway);
    try {
      controller.setViewport(320, 240, false);
      await controller.initialize();
      final pending = controller.matchMusic();
      gateway.pendingMatch!.complete(matched());
      await tester.pump();
      expect(gateway.applying.isCompleted, isTrue);
      final cancelled = controller.cancelMatching();
      gateway.release.complete();
      await Future.wait([pending, cancelled]);
      expect(controller.error, contains('保存失败'));
      expect(controller.project.offsetUs, 2000000);
      expect(gateway.current.offsetUs, 2000000);
      gateway.rejectRollback = false;
      await controller.save();
      expect(gateway.projects.single.alignment, 'manual');
      expect(gateway.projects.single.offsetUs, 2000000);
    } finally {
      controller.dispose();
    }
  });
  testWidgets('mirror edits keep project actions disabled until settled', (
    tester,
  ) async {
    final gateway = HeldPracticeGateway();
    await tester.pumpWidget(
      MaterialApp(home: ComparisonScreen(gateway: gateway)),
    );
    try {
      await tester.pumpAndSettle();
      final controller = screenController(tester);
      await tester.tap(find.byKey(const ValueKey('comparison-view')));
      await tester.pumpAndSettle();
      await tester.tap(
        find
            .ancestor(
              of: find.text('参考镜像'),
              matching: find.byWidgetPredicate(
                (widget) => widget is CheckedPopupMenuItem,
              ),
            )
            .first,
      );
      await tester.pumpAndSettle();
      expect(gateway.started.isCompleted, isTrue);
      expect(controller.project.mirrorTeacher, isFalse);
      expect(
        tester
            .widget<PopupMenuButton>(
              find.byKey(const ValueKey('comparison-project-menu')),
            )
            .enabled,
        isFalse,
      );
      // Lifecycle callers can still replace a session without using the menu.
      await controller.newProject();
      final createdId = controller.project.id;
      gateway.result.completeError(StateError('old rebuild cancelled'));
      await tester.pump();
      expect(controller.project.id, createdId);
      expect(controller.project.hasPair, isFalse);
      expect(controller.playback, isNull);
      expect(controller.error, isNull);
      expect(controller.busy, isFalse);
    } finally {
      if (!gateway.result.isCompleted) gateway.result.complete(state(0));
      await tester.pumpWidget(const SizedBox.shrink());
    }
  });

  testWidgets(
    'a late mirror success cannot modify or save a restored project',
    (tester) async {
      final gateway = HeldPracticeGateway();
      final controller = ComparisonController(gateway);
      try {
        controller.setViewport(320, 240, false);
        await controller.initialize();
        final pending = controller.setMirror(true);
        await gateway.started.future;
        final next = ComparisonProject.fromJson({
          ...savedProject().toJson(),
          'id': 'project_two',
          'positionUs': 2000000,
        });
        await controller.selectProject(next);
        final saves = gateway.saves;
        gateway.result.complete(state(5000000));
        await pending;
        expect(controller.project.id, next.id);
        expect(controller.project.positionUs, 2000000);
        expect(controller.project.mirrorTeacher, isFalse);
        expect(gateway.saves, saves);
      } finally {
        controller.dispose();
      }
    },
  );

  for (final fail in [false, true]) {
    testWidgets(
      'an obsolete seek reply ($fail) leaves the new project intact',
      (tester) async {
        final gateway = HeldSeekGateway();
        final controller = ComparisonController(gateway);
        try {
          controller.setViewport(320, 240, false);
          await controller.initialize();
          final pending = controller.seek(5000000);
          final next = ComparisonProject.fromJson({
            ...savedProject().toJson(),
            'id': 'project_two',
            'positionUs': 2000000,
          });
          await controller.selectProject(next);
          if (fail) {
            gateway.result.completeError(StateError('old seek cancelled'));
          } else {
            gateway.result.complete(state(5000000));
          }
          await pending;
          await controller.save();
          final saved = gateway.projects.firstWhere((p) => p.id == next.id);
          expect(saved.positionUs, 2000000);
          expect(controller.error, isNull);
        } finally {
          controller.dispose();
        }
      },
    );
  }

  testWidgets('an older seek cannot replace a newer seek in the same session', (
    tester,
  ) async {
    final gateway = HeldSeekGateway();
    final controller = ComparisonController(gateway);
    try {
      controller.setViewport(320, 240, false);
      await controller.initialize();
      final pending = controller.seek(5000000);
      await controller.seek(2000000);
      gateway.result.complete(state(5000000));
      await pending;
      expect(controller.project.positionUs, 2000000);
    } finally {
      controller.dispose();
    }
  });

  for (final stage in ApplyStage.values) {
    testWidgets(
      'visible Cancel restores native and saved state during $stage',
      (tester) async {
        final gateway = HeldMappingGateway(stage);
        final original = gateway.projects.single;
        await tester.pumpWidget(
          MaterialApp(home: ComparisonScreen(gateway: gateway)),
        );
        try {
          await tester.pumpAndSettle();
          final controller = screenController(tester);
          expect(controller.playback, isNotNull);
          gateway.pendingMatch!.complete(matched());
          await tester.pump();
          expect(gateway.applying.isCompleted, isTrue);
          expect(controller.matching, isTrue);
          await tester.tap(find.text('取消'));
          await tester.pump();
          expect(controller.matching, isFalse);
          expect(controller.busy, isTrue);
          expect(controller.project.offsetUs, 0);
          gateway.release.complete();
          await tester.pumpAndSettle();
          expect(controller.busy, isFalse);
          expect(controller.message, contains('已取消'));
          expect(controller.error, isNull);
          expect(controller.project.toJson(), original.toJson());
          expect(gateway.projects.single.toJson(), original.toJson());
          expect(gateway.current.offsetUs, 0);
          expect(gateway.current.timeScale ?? 1, 1);
          expect(gateway.current.positionUs, original.positionUs);
          expect(gateway.practice!.loopStartUs, original.loopStartUs);
          expect(gateway.practice!.loopEndUs, original.loopEndUs);
          expect(gateway.practice!.mirrorLearner, isTrue);
          expect(gateway.practice!.teacherPicture, original.teacherPicture);
          // No cancelled candidate may enter persistence before the save stage.
          expect(gateway.saves, stage == ApplyStage.save ? 2 : 0);
          // A retry must still commit and persist a valid result.
          await controller.matchMusic();
          expect(controller.project.offsetUs, 3000000);
          expect(gateway.projects.single.alignment, 'auto');
          expect(controller.message, contains('已自动完成'));
        } finally {
          if (!gateway.release.isCompleted) gateway.release.complete();
          await tester.pumpWidget(const SizedBox.shrink());
        }
      },
    );
  }

  testWidgets('cancellation skips a candidate waiting behind an earlier save', (
    tester,
  ) async {
    final gateway = FakeComparisonGateway(saved: [savedProject()])
      ..firstSaveGate = Completer<void>();
    final controller = ComparisonController(gateway);
    try {
      controller.setViewport(320, 240, false);
      await controller.initialize();
      final earlier = controller.save();
      await tester.pump();
      final pending = controller.matchMusic();
      await tester.pump();
      final cancelled = controller.cancelMatching();
      await tester.pump();
      gateway.firstSaveGate!.complete();
      await Future.wait([earlier, pending, cancelled]);
      expect(gateway.saves, 1);
      expect(gateway.projects.single.alignment, 'manual');
      expect(controller.project.teacherAnchorUs, 0);
      expect(gateway.current.offsetUs, 2000000);
      expect(controller.message, contains('已取消'));
    } finally {
      controller.dispose();
    }
  });

  testWidgets('a concurrent save cannot overwrite a newly committed mapping', (
    tester,
  ) async {
    final gateway = HeldMappingGateway(ApplyStage.save)
      ..projects = [savedProject()];
    final controller = ComparisonController(gateway);
    try {
      controller.setViewport(320, 240, false);
      await controller.initialize();
      final pending = controller.matchMusic();
      gateway.pendingMatch!.complete(matched());
      await tester.pump();
      expect(gateway.applying.isCompleted, isTrue);
      final concurrentSave = controller.save();
      gateway.release.complete();
      await Future.wait([pending, concurrentSave]);
      expect(gateway.projects.single.alignment, 'auto');
      expect(gateway.projects.single.offsetUs, 3000000);
      expect(controller.project.offsetUs, 3000000);
    } finally {
      controller.dispose();
    }
  });

  testWidgets('a cancelled rebuild restores the latest viewport', (
    tester,
  ) async {
    final gateway = HeldMappingGateway(ApplyStage.alignment)
      ..projects = [savedProject()];
    final controller = ComparisonController(gateway);
    try {
      controller.setViewport(320, 240, false);
      await controller.initialize();
      final pending = controller.matchMusic();
      gateway.pendingMatch!.complete(matched());
      await tester.pump();
      expect(gateway.applying.isCompleted, isTrue);
      controller.setViewport(480, 240, true);
      await tester.pump(const Duration(milliseconds: 185));
      final cancelled = controller.cancelMatching();
      gateway.release.complete();
      await Future.wait([pending, cancelled]);
      expect(gateway.layouts.last, isTrue);
      expect(controller.playback!.offsetUs, 2000000);
      expect(controller.busy, isFalse);
    } finally {
      controller.dispose();
    }
  });
}
