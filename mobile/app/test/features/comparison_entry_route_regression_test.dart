import 'dart:async';
import 'package:app/features/comparison/presentation/comparison_controller.dart';
import 'package:app/features/comparison/presentation/comparison_export_dialog.dart';
import 'package:app/features/comparison/presentation/comparison_screen.dart';
import 'package:dance_native/dance_native.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'comparison_export_test.dart' show ExportGateway, job;
import 'comparison_test.dart'
    show FakeComparisonGateway, FakeCueVideo, savedProject;

class _FailingCloseGateway extends FakeComparisonGateway {
  _FailingCloseGateway() : super(saved: [savedProject()]);
  bool failClose = true;
  @override
  Future<void> close() async {
    if (failClose) {
      failClose = false;
      throw StateError('native close failed');
    }
    await super.close();
  }
}

class _HeldExportGateway extends ExportGateway {
  bool holdPoll = false, pollStarted = false;
  int cancelCalls = 0;
  final pollResult = Completer<ComparisonExportDto>();
  var cancelResult = Completer<ComparisonExportDto>();
  @override
  Future<ComparisonExportDto?> getExport() {
    if (holdPoll) {
      pollStarted = true;
      return pollResult.future;
    }
    return super.getExport();
  }

  @override
  Future<ComparisonExportDto> cancelExport(String id) {
    cancelCalls++;
    return cancelResult.future;
  }
}

class _PopObserver extends NavigatorObserver {
  final pops = <String?>[];
  @override
  void didPop(Route<dynamic> route, Route<dynamic>? previousRoute) {
    pops.add(route.settings.name);
    super.didPop(route, previousRoute);
  }
}

Future<void> _showActiveExport(
  WidgetTester tester,
  _HeldExportGateway gateway,
  _PopObserver observer,
) async {
  gateway.status = job('encoding');
  await tester.pumpWidget(
    MaterialApp(
      navigatorObservers: [observer],
      home: Builder(
        builder: (context) => Scaffold(
          body: TextButton(
            onPressed: () => Navigator.of(context).push(
              MaterialPageRoute<void>(
                settings: const RouteSettings(name: 'comparison'),
                builder: (_) => ComparisonScreen(gateway: gateway),
              ),
            ),
            child: const Text('comparison entry'),
          ),
        ),
      ),
    ),
  );
  await tester.tap(find.text('comparison entry'));
  await tester.pumpAndSettle();
  expect(find.byType(ComparisonExportDialog), findsOneWidget);
}

Future<void> _closeExportTest(
  WidgetTester tester,
  _HeldExportGateway gateway,
) async {
  if (!gateway.pollResult.isCompleted) {
    gateway.pollResult.complete(job('cancelled'));
  }
  if (!gateway.cancelResult.isCompleted) {
    gateway.cancelResult.complete(job('cancelled'));
  }
  await tester.pumpWidget(const SizedBox.shrink());
  await tester.pumpAndSettle();
}

void main() {
  testWidgets(
    'picture entry applies a viewport change deferred during saving',
    (tester) async {
      final gateway = FakeComparisonGateway(saved: [savedProject()]);
      final controller = ComparisonController(gateway);
      try {
        controller.setViewport(320, 240, false);
        await controller.initialize();
        gateway.firstSaveGate = Completer<void>();
        final entering = controller.beginPictureAdjustment();
        await tester.pump();
        controller.setViewport(480, 240, true);
        await tester.pump(const Duration(milliseconds: 185));
        expect(gateway.layouts.last, isFalse);
        gateway.firstSaveGate!.complete();
        expect(await entering, isTrue);
        expect(gateway.layouts.last, isTrue);
        expect(controller.adjusting, isTrue);
        expect(controller.enteringPractice, isFalse);
      } finally {
        controller.dispose();
      }
    },
  );
  testWidgets(
    'repeated manual entry creates and releases exactly two previews',
    (tester) async {
      final gateway = FakeComparisonGateway(saved: [savedProject()]);
      final videos = <FakeCueVideo>[];
      await tester.pumpWidget(
        MaterialApp(
          home: ComparisonScreen(
            gateway: gateway,
            manualVideoFactory: (uri) {
              final video = FakeCueVideo(uri, gateway.events);
              videos.add(video);
              return video;
            },
          ),
        ),
      );
      try {
        await tester.pumpAndSettle();
        gateway.firstSaveGate = Completer<void>();
        await tester.tap(find.byKey(const ValueKey('comparison-calibration')));
        // Also invoke the already-dispatched callback before the next rebuild.
        tester
            .widget<IconButton>(
              find.byKey(const ValueKey('comparison-calibration')),
            )
            .onPressed!();
        await tester.pump();
        expect(gateway.saves, 1);
        expect(
          tester
              .widget<IconButton>(
                find.byKey(const ValueKey('comparison-calibration')),
              )
              .onPressed,
          isNull,
        );
        gateway.firstSaveGate!.complete();
        await tester.pumpAndSettle();
        expect(
          find.byKey(const ValueKey('comparison-manual-seek-0')),
          findsOneWidget,
        );
        expect(videos.length, 2);
        await tester.pumpWidget(const SizedBox.shrink());
        await tester.pumpAndSettle();
        expect(videos.every((video) => video.closed), isTrue);
      } finally {
        if (gateway.firstSaveGate?.isCompleted == false) {
          gateway.firstSaveGate!.complete();
        }
        await tester.pumpWidget(const SizedBox.shrink());
      }
    },
  );

  testWidgets('failed entry save keeps the shared player and can be retried', (
    tester,
  ) async {
    final gateway = FakeComparisonGateway(saved: [savedProject()]);
    final videos = <FakeCueVideo>[];
    await tester.pumpWidget(
      MaterialApp(
        home: ComparisonScreen(
          gateway: gateway,
          manualVideoFactory: (uri) {
            final video = FakeCueVideo(uri, gateway.events);
            videos.add(video);
            return video;
          },
        ),
      ),
    );
    try {
      await tester.pumpAndSettle();
      gateway.failSave = true;
      await tester.tap(find.byKey(const ValueKey('comparison-calibration')));
      await tester.pumpAndSettle();
      expect(videos, isEmpty);
      expect(gateway.events.where((e) => e == 'shared-close'), isEmpty);
      expect(find.textContaining('保存失败'), findsWidgets);
      gateway.failSave = false;
      await tester.tap(find.byKey(const ValueKey('comparison-calibration')));
      await tester.pumpAndSettle();
      expect(videos.length, 2);
    } finally {
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pumpAndSettle();
      expect(videos.every((v) => v.closed), isTrue);
    }
  });

  for (final manualFirst in [true, false]) {
    testWidgets('practice entry is exclusive across modes ($manualFirst)', (
      tester,
    ) async {
      final gateway = FakeComparisonGateway(saved: [savedProject()]);
      final controller = ComparisonController(gateway);
      try {
        controller.setViewport(320, 240, false);
        await controller.initialize();
        gateway.firstSaveGate = Completer<void>();
        final first = manualFirst
            ? controller.enterManualCalibration()
            : controller.beginPictureAdjustment();
        expect(controller.busy, isTrue);
        expect(
          await (manualFirst
              ? controller.beginPictureAdjustment()
              : controller.enterManualCalibration()),
          isFalse,
        );
        await tester.pump();
        gateway.firstSaveGate!.complete();
        expect(await first, isTrue);
        expect(controller.calibrating, manualFirst);
        expect(controller.adjusting, !manualFirst);
        expect(controller.enteringPractice, isFalse);
      } finally {
        controller.dispose();
      }
    });
  }

  testWidgets('failed native close releases manual entry for retry', (
    tester,
  ) async {
    final gateway = _FailingCloseGateway();
    final controller = ComparisonController(gateway);
    try {
      controller.setViewport(320, 240, false);
      await controller.initialize();
      expect(await controller.enterManualCalibration(), isFalse);
      expect(controller.busy, isFalse);
      expect(controller.calibrating, isFalse);
      expect(controller.playback, isNotNull);
      expect(controller.error, contains('暂时无法打开'));
      expect(await controller.enterManualCalibration(), isTrue);
    } finally {
      controller.dispose();
    }
  });

  testWidgets(
    'leaving during pending entry cannot start a preview afterwards',
    (tester) async {
      final gateway = FakeComparisonGateway(saved: [savedProject()]);
      final controller = ComparisonController(gateway);
      try {
        controller.setViewport(320, 240, false);
        await controller.initialize();
        gateway.firstSaveGate = Completer<void>();
        final entry = controller.enterManualCalibration();
        await tester.pump();
        final leaving = controller.finish();
        await tester.pump();
        gateway.firstSaveGate!.complete();
        expect(await entry, isFalse);
        expect(await leaving, isTrue);
        expect(controller.calibrating, isFalse);
        expect(controller.busy, isFalse);
      } finally {
        controller.dispose();
      }
    },
  );

  for (final cancelFirst in [false, true]) {
    testWidgets('export exit pops only its dialog ($cancelFirst)', (
      tester,
    ) async {
      final gateway = _HeldExportGateway();
      final observer = _PopObserver();
      await _showActiveExport(tester, gateway, observer);
      try {
        gateway.holdPoll = true;
        await tester.binding.handlePopRoute();
        await tester.pump(const Duration(milliseconds: 450));
        expect(gateway.pollStarted, isTrue);
        await tester.binding.handlePopRoute();
        expect(gateway.cancelCalls, 1);
        if (cancelFirst) {
          gateway.cancelResult.complete(job('cancelled'));
          await tester.pump();
          gateway.pollResult.complete(job('cancelled'));
        } else {
          gateway.pollResult.complete(job('cancelled'));
          gateway.cancelResult.complete(job('cancelled'));
        }
        await tester.pumpAndSettle();
        expect(find.byType(ComparisonExportDialog), findsNothing);
        expect(find.byType(ComparisonScreen), findsOneWidget);
        expect(observer.pops, [null]);
        expect(tester.takeException(), isNull);
      } finally {
        await _closeExportTest(tester, gateway);
      }
    });
  }

  testWidgets('Back during a button cancellation closes once it finishes', (
    tester,
  ) async {
    final gateway = _HeldExportGateway();
    final observer = _PopObserver();
    await _showActiveExport(tester, gateway, observer);
    try {
      await tester.tap(find.text('取消导出'));
      await tester.pump();
      await tester.binding.handlePopRoute();
      expect(gateway.cancelCalls, 1);
      gateway.cancelResult.complete(job('cancelled'));
      await tester.pumpAndSettle();
      expect(find.byType(ComparisonScreen), findsOneWidget);
      expect(find.byType(ComparisonExportDialog), findsNothing);
      expect(observer.pops, [null]);
    } finally {
      await _closeExportTest(tester, gateway);
    }
  });

  testWidgets('a failed cancellation stays open and allows retry', (
    tester,
  ) async {
    final gateway = _HeldExportGateway();
    final observer = _PopObserver();
    await _showActiveExport(tester, gateway, observer);
    try {
      await tester.binding.handlePopRoute();
      gateway.cancelResult.completeError(
        StateError('native cancellation failed'),
      );
      await tester.pumpAndSettle();
      expect(find.byType(ComparisonExportDialog), findsOneWidget);
      expect(find.text('操作未完成，请重试。'), findsOneWidget);
      expect(observer.pops, isEmpty);
      gateway.cancelResult = Completer<ComparisonExportDto>();
      await tester.tap(find.text('取消导出'));
      gateway.cancelResult.complete(job('cancelled'));
      await tester.pumpAndSettle();
      expect(find.byType(ComparisonScreen), findsOneWidget);
      expect(observer.pops, [null]);
    } finally {
      await _closeExportTest(tester, gateway);
    }
  });
}
