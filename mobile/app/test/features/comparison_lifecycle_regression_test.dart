import 'dart:async';
import 'package:app/features/comparison/presentation/comparison_controller.dart';
import 'package:app/features/comparison/presentation/comparison_manual_session.dart';
import 'package:app/features/comparison/presentation/comparison_screen.dart';
import 'package:flutter/material.dart';
import 'package:dance_native/dance_native.dart';
import 'package:flutter_test/flutter_test.dart';
import 'comparison_test.dart'
    show FakeComparisonGateway, FakeCueVideo, savedProject;

class DelayedResizeGateway extends FakeComparisonGateway {
  DelayedResizeGateway() : super(saved: [savedProject()]);
  final resizeResult = Completer<ComparisonPlaybackDto>();
  final resizing = Completer<void>();
  @override
  Future<ComparisonPlaybackDto> resize(int w, int h, bool horizontal) {
    if (!resizing.isCompleted) resizing.complete();
    return resizeResult.future;
  }
}

class FailingSeekVideo extends FakeCueVideo {
  FailingSeekVideo(super.source, super.events);
  bool failSeek = false;
  @override
  Future<void> seekTo(Duration position) async {
    if (failSeek) throw StateError('source seek failed');
    await super.seekTo(position);
  }
}

class QueuedResizeGateway extends FakeComparisonGateway {
  QueuedResizeGateway() : super(saved: [savedProject()]);
  final requests = <Completer<ComparisonPlaybackDto>>[];
  @override
  Future<ComparisonPlaybackDto> resize(int w, int h, bool horizontal) {
    final request = Completer<ComparisonPlaybackDto>();
    requests.add(request);
    return request.future;
  }
}

void main() {
  testWidgets(
    'finished sessions cannot overwrite the saved position while the route exits',
    (tester) async {
      final original = savedProject();
      final gateway = FakeComparisonGateway(saved: [original]);
      final controller = ComparisonController(gateway);
      addTearDown(controller.dispose);
      controller.setViewport(320, 240, false);
      await controller.initialize();
      await tester.pump(const Duration(milliseconds: 200));
      expect(await controller.finish(), isTrue);
      final saves = gateway.saves;
      final opens = gateway.opens;
      // A closed native player reports its idle position while rotation is restored.
      gateway.current = ComparisonPlaybackDto(
        textureId: -1,
        phase: 'idle',
        positionUs: 0,
        durationUs: 0,
        teacherStartUs: 0,
        offsetUs: 0,
        speed: 1,
      );
      controller.setViewport(640, 320, true);
      await tester.pump(const Duration(seconds: 4));
      await controller.background();
      expect(controller.project.positionUs, original.positionUs);
      expect(gateway.projects.first.positionUs, original.positionUs);
      expect(gateway.saves, saves);
      expect(gateway.opens, opens);
    },
  );

  testWidgets('a pending resize cannot restore a closed project texture', (
    tester,
  ) async {
    final gateway = DelayedResizeGateway();
    final controller = ComparisonController(gateway);
    try {
      controller.setViewport(320, 240, false);
      await controller.initialize();
      await tester.pump(const Duration(milliseconds: 185));
      expect(gateway.resizing.isCompleted, isTrue);
      await controller.newProject();
      expect(controller.project.hasPair, isFalse);
      expect(controller.playback, isNull);
      gateway.resizeResult.complete(
        ComparisonPlaybackDto(
          textureId: 7,
          phase: 'paused',
          positionUs: 1000000,
          durationUs: 12000000,
          teacherStartUs: 0,
          offsetUs: 2000000,
          speed: 1,
        ),
      );
      await tester.pump();
      expect(
        controller.playback,
        isNull,
        reason: 'The resize belongs to the closed session',
      );
    } finally {
      controller.dispose();
    }
  });

  test('failed manual seek must prevent capturing calibration anchors', () async {
    final videos = <FailingSeekVideo>[];
    final session = ComparisonManualSession(
      savedProject(),
      factory: (uri) {
        final video = FailingSeekVideo(uri, []);
        videos.add(video);
        return video;
      },
    );
    addTearDown(session.close);
    await session.initialize();
    expect(session.ready, isTrue);
    for (final video in videos) {
      video.failSeek = true;
    }
    await session.seek(0, 5000000);
    expect(session.error, isNotNull);
    await expectLater(
      session.anchors(),
      throwsA(isA<StateError>()),
      reason:
          'A failed preview seek must not silently supply the old source positions',
    );
  });

  testWidgets('an old resize cancellation cannot add an error to a new project', (
    tester,
  ) async {
    final gateway = DelayedResizeGateway();
    final controller = ComparisonController(gateway);
    try {
      controller.setViewport(320, 240, false);
      await controller.initialize();
      await tester.pump(const Duration(milliseconds: 185));
      expect(gateway.resizing.isCompleted, isTrue);
      await controller.newProject();
      expect(controller.error, isNull);
      gateway.resizeResult.completeError(
        StateError('old resize cancelled by native close'),
      );
      await tester.pump();
      expect(
        controller.error,
        isNull,
        reason:
            'Cancellation of the old iOS rebuild belongs to the closed session',
      );
    } finally {
      controller.dispose();
    }
  });

  testWidgets(
    'failed manual preview must stay open instead of reporting applied calibration',
    (tester) async {
      final gateway = FakeComparisonGateway(saved: [savedProject()]);
      final videos = <FailingSeekVideo>[];
      await tester.pumpWidget(
        MaterialApp(
          home: ComparisonScreen(
            gateway: gateway,
            manualVideoFactory: (uri) {
              final video = FailingSeekVideo(uri, []);
              videos.add(video);
              return video;
            },
          ),
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('comparison-calibration')));
      await tester.pumpAndSettle();
      expect(videos.length, 2);
      for (final video in videos) {
        video.failSeek = true;
      }
      // The source fails during the final anchor read, after Apply was enabled.
      expect(find.text('此位置暂时无法预览，请再拖动一次。'), findsNothing);
      final savesBefore = gateway.saves;
      await tester.tap(find.text('完成校准'));
      await tester.pumpAndSettle();
      expect(gateway.saves, savesBefore);
      expect(gateway.projects.first.teacherAnchorUs, 0);
      expect(
        find.byKey(const ValueKey('comparison-manual-seek-0')),
        findsOneWidget,
        reason:
            'Failed seeks must prevent persisting a calibration and closing the editor',
      );
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  test('failed first music anchor stays editable and can be retried', () async {
    final videos = <FailingSeekVideo>[];
    final session = ComparisonManualSession(
      savedProject(),
      factory: (uri) {
        final video = FailingSeekVideo(uri, []);
        videos.add(video);
        return video;
      },
    );
    addTearDown(session.close);
    await session.initialize();
    session.setRateCalibration(true);
    videos.first.failSeek = true;
    await session.recordFirstAnchor();
    expect(session.firstAnchor, isNull);
    expect(session.anchorIndex, 0);
    expect(session.anchoring, isFalse);
    expect(session.error, isNotNull);
    videos.first.failSeek = false;
    await session.recordFirstAnchor();
    expect(session.firstAnchor, (1000000, 3000000));
    expect(session.anchorIndex, 1);
    expect(session.error, isNull);
  });

  testWidgets('an older viewport reply cannot replace a newer viewport', (
    tester,
  ) async {
    final gateway = QueuedResizeGateway();
    final controller = ComparisonController(gateway);
    try {
      controller.setViewport(320, 240, false);
      await controller.initialize();
      await tester.pump(const Duration(milliseconds: 185));
      controller.setViewport(480, 240, true);
      await tester.pump(const Duration(milliseconds: 185));
      expect(gateway.requests.length, 2);
      ComparisonPlaybackDto state(int texture) => ComparisonPlaybackDto(
        textureId: texture,
        phase: 'paused',
        positionUs: 1000000,
        durationUs: 12000000,
        teacherStartUs: 0,
        offsetUs: 2000000,
        speed: 1,
      );
      gateway.requests.last.complete(state(9));
      await tester.pump();
      expect(controller.playback!.textureId, 9);
      gateway.requests.first.complete(state(8));
      await tester.pump();
      expect(controller.playback!.textureId, 9);
    } finally {
      controller.dispose();
    }
  });
}
