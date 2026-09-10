import 'package:app/features/export/domain/export_state.dart';
import 'package:app/features/export/presentation/result_screen.dart';
import 'package:app/repositories/native_processing_repository.dart';
import 'package:dance_domain/dance_domain.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';

void main() {
  testWidgets(
    'success result prioritizes share with only user-facing surrounding actions',
    (tester) async {
      final repository = _ResultRepository();
      final container = ProviderContainer(
        overrides: [nativeRepositoryProvider.overrideWithValue(repository)],
      );
      addTearDown(container.dispose);

      final router = _buildRouter(_completedState());
      addTearDown(router.dispose);

      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: MaterialApp.router(routerConfig: router),
        ),
      );
      await tester.pump();
      await tester.pump();

      expect(find.text('舞段已完成'), findsNothing);
      expect(find.text('分享视频'), findsNothing);
      expect(find.text('制作下一个'), findsNothing);
      expect(find.text('更多选项'), findsNothing);
      expect(find.text('视频已安全保存到你的设备'), findsNothing);

      expect(find.byKey(const ValueKey('result-share-action')), findsOneWidget);
      expect(find.byKey(const ValueKey('result-next-action')), findsOneWidget);
      expect(find.byKey(const ValueKey('result-open-action')), findsOneWidget);
      expect(find.byKey(const ValueKey('result-copy-action')), findsNothing);
      expect(
        find.byKey(const ValueKey('result-diagnostics-action')),
        findsOneWidget,
      );

      final shareCenter = tester.getCenter(
        find.byKey(const ValueKey('result-share-action')),
      );
      expect(
        tester.getCenter(find.byKey(const ValueKey('result-open-action'))).dx,
        greaterThan(shareCenter.dx),
      );
      expect(
        tester
            .getCenter(find.byKey(const ValueKey('result-diagnostics-action')))
            .dy,
        lessThan(shareCenter.dy),
      );
      expect(
        tester.getCenter(find.byKey(const ValueKey('result-next-action'))).dx,
        lessThan(shareCenter.dx),
      );
    },
  );

  testWidgets(
    'success result keeps share open diagnostics and next actions',
    (tester) async {
      final repository = _ResultRepository();
      final container = ProviderContainer(
        overrides: [nativeRepositoryProvider.overrideWithValue(repository)],
      );
      addTearDown(container.dispose);

      final router = _buildRouter(_completedState());
      addTearDown(router.dispose);

      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: MaterialApp.router(routerConfig: router),
        ),
      );
      await tester.pump();
      await tester.pump();

      expect(repository.savedPaths, contains('/tmp/result.mp4'));

      await tester.tap(find.byKey(const ValueKey('result-share-action')));
      await tester.pump();
      expect(repository.sharedUris, contains('content://gallery/result.mp4'));

      await tester.tap(find.byKey(const ValueKey('result-open-action')));
      await tester.pump();
      expect(repository.openedUris, contains('content://gallery/result.mp4'));

      expect(find.byKey(const ValueKey('result-copy-action')), findsNothing);

      await tester.tap(find.byKey(const ValueKey('result-diagnostics-action')));
      await tester.pump();
      await tester.pump();
      expect(repository.diagnosticShareCount, 1);

      await tester.tap(find.byKey(const ValueKey('result-next-action')));
      await tester.pump();
      expect(router.routerDelegate.currentConfiguration.uri.path, '/');
    },
  );

  testWidgets('success result remains stable on a compact phone screen', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(360, 640);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(() {
      tester.view.resetPhysicalSize();
      tester.view.resetDevicePixelRatio();
    });

    final repository = _ResultRepository();
    final container = ProviderContainer(
      overrides: [nativeRepositoryProvider.overrideWithValue(repository)],
    );
    addTearDown(container.dispose);

    final router = _buildRouter(_completedState());
    addTearDown(router.dispose);

    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: MaterialApp.router(routerConfig: router),
      ),
    );
    await tester.pump();
    await tester.pump();

    expect(find.byKey(const ValueKey('result-share-action')), findsOneWidget);
    expect(find.byKey(const ValueKey('result-next-action')), findsOneWidget);
    expect(find.byKey(const ValueKey('result-open-action')), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}

GoRouter _buildRouter(ExportState state) {
  return GoRouter(
    initialLocation: '/result',
    routes: [
      GoRoute(
        path: '/',
        builder: (context, routeState) =>
            const Scaffold(body: Center(child: Text('home-marker'))),
      ),
      GoRoute(
        path: '/result',
        builder: (context, routeState) => ResultScreen(exportState: state),
      ),
    ],
  );
}

ExportState _completedState() {
  final now = DateTime.utc(2026, 9, 5);
  return ExportState(
    status: ExportJobState.completed,
    outputUri: '/tmp/result.mp4',
    project: DanceProject(
      id: 'result-project',
      sourceUri: '/tmp/source.mp4',
      videoInfo: const VideoInfo(
        codedWidth: 1280,
        codedHeight: 720,
        displayWidth: 1280,
        displayHeight: 720,
        fps: 30,
        durationMs: 5000,
        rotation: 0,
        videoCodec: 'h264',
        hasAudio: true,
      ),
      createdAt: now,
      updatedAt: now,
    ),
  );
}

class _ResultRepository implements NativeProcessingRepository {
  final List<String> savedPaths = [];
  final List<String> sharedUris = [];
  final List<String> openedUris = [];
  int diagnosticShareCount = 0;

  @override
  Future<String?> saveVideoToGallery(String filePath) async {
    savedPaths.add(filePath);
    return 'content://gallery/result.mp4';
  }

  @override
  Future<void> shareVideo(String publicUri) async {
    sharedUris.add(publicUri);
  }

  @override
  Future<void> openVideo(String publicUri) async {
    openedUris.add(publicUri);
  }

  @override
  Future<Map<dynamic, dynamic>?> createDiagnosticBundle() async {
    return <dynamic, dynamic>{
      'filePath': '/tmp/diag.zip',
      'publicUri': 'content://diag.zip',
    };
  }

  @override
  Future<Map<dynamic, dynamic>?> shareDiagnosticBundle({
    String? filePath,
    String? publicUri,
  }) async {
    diagnosticShareCount++;
    return <dynamic, dynamic>{'shared': true};
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}
