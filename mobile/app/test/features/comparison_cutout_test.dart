import 'dart:async';
import 'dart:convert';
import 'package:app/app/theme.dart';
import 'package:app/features/comparison/data/comparison_gateway.dart';
import 'package:app/features/comparison/presentation/comparison_controller.dart';
import 'package:app/features/comparison/presentation/comparison_cutout_gesture.dart';
import 'package:app/features/comparison/presentation/comparison_screen.dart';
import 'package:dance_domain/dance_domain.dart';
import 'package:dance_native/dance_native.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'comparison_test.dart' show FakeComparisonGateway, savedProject;

class CutoutGateway extends FakeComparisonGateway
    implements ComparisonCutoutGateway {
  CutoutGateway() : super(saved: [savedProject()]) {
    current.teacherFrameUs = 1000000;
    current.learnerFrameUs = 3000000;
    current.teacherAspect = 1;
    current.learnerAspect = 1;
  }
  final inspections = <bool>[];
  Completer<ComparisonCutoutPreviewDto>? pendingInspection;
  bool failInspection = false;
  ComparisonCutoutPreviewDto still(
    ComparisonCutoutSeedDto seed,
  ) => ComparisonCutoutPreviewDto(
    sourcePtsUs: seed.sourcePtsUs,
    png: base64Decode(
      'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/a9sAAAAASUVORK5CYII=',
    ),
    width: 1,
    height: 1,
  );
  @override
  Future<ComparisonCutoutPreviewDto> inspectCutout(
    bool reference,
    ComparisonCutoutSeedDto seed,
  ) async {
    inspections.add(reference);
    if (failInspection) throw StateError('ambiguous');
    return pendingInspection == null ? still(seed) : pendingInspection!.future;
  }

  final seeds = <ComparisonCutoutSeedDto>[];
  Completer<ComparisonCutoutResultDto>? pending;
  int clears = 0, resumes = 0;
  bool completeMasks = false;
  ComparisonCutoutResultDto result() => ComparisonCutoutResultDto(
    playback: current,
    referenceVisible: 30,
    referenceFrames: 30,
    targetVisible: completeMasks ? 30 : 29,
    targetFrames: 30,
  );
  @override
  Future<ComparisonCutoutResultDto> prepareCutout(
    ComparisonCutoutSeedDto reference,
    ComparisonCutoutSeedDto target,
    ComparisonCanvasLayoutDto? layout,
  ) async {
    initialLayout = layout;
    seeds.addAll([reference, target]);
    return pending == null ? result() : pending!.future;
  }

  ComparisonCanvasLayoutDto? initialLayout;
  final edits = <ComparisonCanvasLayoutDto>[];
  @override
  Future<ComparisonPlaybackDto> setCutoutLayout(
    ComparisonCanvasLayoutDto layout,
  ) async {
    edits.add(layout);
    return current;
  }

  @override
  Future<ComparisonPlaybackDto> resumeCutoutPreview() async {
    resumes++;
    return current;
  }

  @override
  Future<ComparisonPlaybackDto> clearCutout() async {
    clears++;
    current.phase = 'paused';
    return current;
  }
}

class ProgressiveCutoutGateway extends CutoutGateway
    implements ComparisonProgressiveCutoutGateway {
  ComparisonCutoutResultDto? progress;
  @override
  Future<ComparisonCutoutResultDto?> cutoutStatus() async => progress;
}

void main() {
  test(
    'inverse native pane geometry rejects letterbox and divider, handles mirror',
    () {
      Offset? hit(
        Offset p, {
        bool reference = true,
        bool mirrored = false,
        bool horizontal = false,
        double aspect = 1,
        ComparisonPicture? picture,
      }) => comparisonCutoutPoint(
        viewport: const Size(400, 600),
        point: p,
        width: 400,
        height: 600,
        horizontal: horizontal,
        reference: reference,
        aspect: aspect,
        picture: picture ?? ComparisonPicture.clamped(1, 0, 0),
        mirrored: mirrored,
      );
      final a = hit(const Offset(126, 148))!;
      expect(a.dx, closeTo(.25, .00001));
      expect(a.dy, closeTo(.5, .00001));
      expect(
        hit(const Offset(126, 148), mirrored: true)!.dx,
        closeTo(.75, .00001),
      );
      expect(hit(const Offset(10, 148)), isNull);
      expect(hit(const Offset(200, 300)), isNull);
      expect(hit(const Offset(200, 300), reference: false), isNull);
      expect(
        hit(const Offset(200, 452), reference: false)!.dy,
        closeTo(.5, .00001),
      );
      expect(
        hit(const Offset(98, 300), horizontal: true)!.dx,
        closeTo(.5, .00001),
      );
      expect(
        hit(
          const Offset(200, 148),
          picture: ComparisonPicture.clamped(2, 1, 0),
        )!.dx,
        closeTo(.5 - (.48 / 2.96), .00001),
      );
    },
  );

  test('drag must move toward the common drop region from either source', () {
    const size = Size(400, 600);
    expect(
      comparisonCutoutDrop(
        size,
        const Offset(200, 130),
        const Offset(200, 300),
        horizontal: false,
        reference: true,
      ),
      isTrue,
    );
    expect(
      comparisonCutoutDrop(
        size,
        const Offset(200, 450),
        const Offset(200, 300),
        horizontal: false,
        reference: false,
      ),
      isTrue,
    );
    expect(
      comparisonCutoutDrop(
        size,
        const Offset(200, 130),
        const Offset(200, 100),
        horizontal: false,
        reference: true,
      ),
      isFalse,
    );
    expect(
      comparisonCutoutDrop(
        size,
        const Offset(100, 300),
        const Offset(200, 300),
        horizontal: true,
        reference: true,
      ),
      isTrue,
    );
  });

  testWidgets('paired long presses publish preview and keep export isolated', (
    tester,
  ) async {
    final gateway = CutoutGateway();
    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.darkTheme,
        home: ComparisonScreen(gateway: gateway),
      ),
    );
    await tester.pump(const Duration(milliseconds: 400));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('comparison-view')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('抠像画布（实验）'));
    await tester.pumpAndSettle();
    final finder = find.byKey(const ValueKey('comparison-cutout-gesture'));
    expect(finder, findsOneWidget);
    // Default widget viewport is landscape: reference left, target right.
    for (final x in [.25, .84]) {
      final rect = tester.getRect(finder);
      final g = await tester.startGesture(
        Offset(rect.left + rect.width * x, rect.center.dy),
      );
      await tester.pump(const Duration(milliseconds: 600));
      await tester.pump();
      expect(
        find.byKey(const ValueKey('comparison-cutout-drag-still')),
        findsOneWidget,
      );
      await g.moveTo(rect.center);
      await tester.pump();
      await g.up();
      await tester.pumpAndSettle();
      if (x == .25) {
        expect(
          find.byKey(const ValueKey('comparison-cutout-reference-still')),
          findsOneWidget,
        );
        expect(gateway.seeds, isEmpty);
      }
    }
    expect(gateway.inspections, [true, false]);
    expect(gateway.seeds.map((s) => s.sourcePtsUs), [1000000, 3000000]);
    expect(find.textContaining('缺失帧已隐藏'), findsOneWidget);
    expect(
      find.byKey(const ValueKey('comparison-cutout-gesture')),
      findsNothing,
    );
    final menu = tester.widget<PopupMenuButton>(
      find.byKey(const ValueKey('comparison-project-menu')),
    );
    expect(menu.enabled, isFalse);
    await tester.tap(find.byKey(const ValueKey('comparison-canvas-edit')));
    await tester.pumpAndSettle();
    expect(
      find.byKey(const ValueKey('comparison-canvas-edit-gesture')),
      findsOneWidget,
    );
    await tester.tap(find.byKey(const ValueKey('comparison-canvas-overlay')));
    await tester.pumpAndSettle();
    expect(gateway.edits.last.target.opacity, .5);
    await tester.tap(find.byKey(const ValueKey('comparison-canvas-target')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('comparison-canvas-mirror')));
    await tester.pumpAndSettle();
    expect(gateway.edits.last.target.mirror, isTrue);
    final gesture = find.byKey(
      const ValueKey('comparison-canvas-edit-gesture'),
    );
    final rect = tester.getRect(gesture);
    await tester.drag(gesture, Offset(rect.width * .10, -rect.height * .10));
    await tester.pumpAndSettle();
    expect(gateway.edits.last.target.x, greaterThan(.5));
    expect(gateway.edits.last.reference.x, .5);
    await tester.tap(find.byKey(const ValueKey('comparison-canvas-done')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('退出画布'));
    await tester.pumpAndSettle();
    expect(gateway.clears, 1);
    expect(
      find.byKey(const ValueKey('comparison-picture-pane-teacher')),
      findsOneWidget,
    );
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox());
  });

  test(
    'expanded stage keeps sources out of canvas and maps each fitted pane',
    () {
      const stage = ComparisonCutoutStage(
        Size(400, 600),
        400,
        600,
        false,
        true,
      );
      final r = stage.fitted(true), t = stage.fitted(false);
      expect(r.overlaps(stage.canvas), isFalse);
      expect(t.overlaps(stage.canvas), isFalse);
      expect(stage.nativePoint(stage.canvas.center, true), isNull);
      expect(stage.nativePoint(r.center, true), const Offset(200, 148));
      expect(stage.nativePoint(t.center, false), const Offset(200, 452));
    },
  );

  test('cancelling still inspection rejects late pixels', () async {
    final gateway = CutoutGateway()
      ..pendingInspection = Completer<ComparisonCutoutPreviewDto>();
    final controller = ComparisonController(gateway);
    controller.setViewport(400, 600, false);
    await controller.initialize();
    await Future<void>.delayed(const Duration(milliseconds: 220));
    expect(await controller.beginCutout(), isTrue);
    final seed = ComparisonCutoutSeedDto(sourcePtsUs: 1000000, x: .5, y: .5);
    final work = controller.inspectCutout(true, seed);
    await controller.exitCutout();
    gateway.pendingInspection!.complete(gateway.still(seed));
    expect(await work, isNull);
    expect(controller.cutoutMode, isFalse);
    expect(controller.cutoutInspecting, isFalse);
    controller.dispose();
  });

  test('long source practice selection clamps seek and locks range', () async {
    final gateway = CutoutGateway();
    gateway.projects = [
      savedProject().copyWith(
        loopStartUs: 500000,
        loopEndUs: 2000000,
        loopEnabled: true,
      ),
    ];
    final controller = ComparisonController(gateway);
    controller.setViewport(400, 600, false);
    await controller.initialize();
    await Future<void>.delayed(const Duration(milliseconds: 220));
    expect(await controller.beginCutout(), isTrue);
    await controller.prepareCutout(
      ComparisonCutoutSeedDto(sourcePtsUs: 1000000, x: .5, y: .5),
      ComparisonCutoutSeedDto(sourcePtsUs: 3000000, x: .5, y: .5),
    );
    expect(controller.cutoutPracticeRange, (500000, 2000000));
    await controller.seek(0);
    expect(gateway.current.positionUs, 500000);
    await controller.seek(4000000);
    expect(gateway.current.positionUs, 1999999);
    expect(await controller.setLoopRange(0, 1000000), isFalse);
    await controller.exitCutout();
    await controller.seek(0);
    expect(gateway.current.positionUs, 0);
    controller.dispose();
  });

  test(
    'canvas settings persist after explicit edit and reload for new analysis',
    () async {
      final gateway = CutoutGateway();
      final c = ComparisonController(gateway);
      c.setViewport(400, 600, false);
      await c.initialize();
      await Future<void>.delayed(const Duration(milliseconds: 220));
      expect(await c.beginCutout(), isTrue);
      final ra = ComparisonCutoutSeedDto(sourcePtsUs: 1000000, x: .5, y: .5);
      final ta = ComparisonCutoutSeedDto(sourcePtsUs: 3000000, x: .5, y: .5);
      await c.prepareCutout(ra, ta);
      expect(gateway.initialLayout!.reference.x, .27);
      expect(await c.beginCanvasEditing(), isTrue);
      final layout = c.canvasLayout.overlay().copyWith(
        background: 2,
        targetOnTop: false,
      );
      expect(await c.applyCanvasLayout(layout), isTrue);
      expect(gateway.edits.single.reference.x, .5);
      expect(gateway.edits.single.target.opacity, .5);
      expect(gateway.seeds.length, 2);
      expect(gateway.projects.first.canvasLayout!.background, 2);
      c.finishCanvasEditing();
      await c.exitCutout();
      expect(await c.beginCutout(), isTrue);
      await c.prepareCutout(ra, ta);
      expect(gateway.initialLayout!.background, 2);
      expect(gateway.initialLayout!.targetOnTop, isFalse);
      c.dispose();
    },
  );

  test(
    'old JSON stays compatible and canvas validation rejects invalid layout',
    () {
      final old = savedProject();
      expect(old.toJson().containsKey('canvasLayout'), isFalse);
      final layout = ComparisonCanvasLayout.sideBySide().overlay();
      final project = old.copyWith(canvasLayout: layout);
      final restored = ComparisonProject.fromJson(
        Map<String, dynamic>.from(project.toJson()),
      );
      expect(restored.canvasLayout!.toJson(), layout.toJson());
      expect(project.swapSources().canvasLayout, isNull);
      expect(
        project
            .copyWith(
              teacher: const ComparisonSource(
                uri: 'file:///new',
                name: 'new',
                durationUs: 1000000,
                hasAudio: false,
              ),
            )
            .canvasLayout,
        isNull,
      );
      expect(
        project.copyWith(positionUs: 2).canvasLayout!.toJson(),
        layout.toJson(),
      );
      final bad = layout.copyWith(target: layout.target.copyWith(opacity: 1.1));
      expect(bad.valid, isFalse);
      expect(
        () => ComparisonCanvasLayout.fromJson(
          Map<String, dynamic>.from(bad.toJson()),
        ),
        throwsFormatException,
      );
    },
  );

  test(
    'canvas export freezes only analyzed range and restores cache after dialog',
    () async {
      final gateway = CutoutGateway()..completeMasks = true;
      gateway.projects = [
        savedProject().copyWith(
          loopStartUs: 500000,
          loopEndUs: 2000000,
          loopEnabled: true,
        ),
      ];
      final c = ComparisonController(gateway);
      c.setViewport(400, 600, false);
      await c.initialize();
      await Future<void>.delayed(const Duration(milliseconds: 220));
      expect(await c.beginCutout(), isTrue);
      final a = ComparisonCutoutSeedDto(sourcePtsUs: 1000000, x: .5, y: .5);
      final b = ComparisonCutoutSeedDto(sourcePtsUs: 3000000, x: .5, y: .5);
      await c.prepareCutout(a, b);
      expect(c.cutoutExportReady, isTrue);
      final plans = await c.prepareExport();
      expect(plans!.$1.cutout, isTrue);
      expect(plans.$1.startUs, 500000);
      expect(plans.$1.endUs, 2000000);
      expect(plans.$2, isNull);
      expect(c.cutoutActive, isTrue);
      await c.finishExport();
      expect(gateway.resumes, 1);
      expect(c.cutoutActive, isTrue);
      expect(gateway.seeds.length, 2);
      await c.exitCutout();
      gateway.completeMasks = false;
      expect(await c.beginCutout(), isTrue);
      await c.prepareCutout(a, b);
      expect(c.cutoutExportReady, isFalse);
      expect(await c.prepareExport(), isNull);
      c.dispose();
    },
  );

  test(
    'progressive preview clamps seeks and blocks export until full result',
    () async {
      final gateway = ProgressiveCutoutGateway()
        ..pending = Completer<ComparisonCutoutResultDto>()
        ..completeMasks = true;
      final c = ComparisonController(gateway);
      c.setViewport(400, 600, false);
      await c.initialize();
      await Future<void>.delayed(const Duration(milliseconds: 220));
      expect(await c.beginCutout(), isTrue);
      final work = c.prepareCutout(
        ComparisonCutoutSeedDto(sourcePtsUs: 1000000, x: .5, y: .5),
        ComparisonCutoutSeedDto(sourcePtsUs: 3000000, x: .5, y: .5),
      );
      gateway.progress = ComparisonCutoutResultDto(
        playback: gateway.current,
        referenceVisible: 10,
        referenceFrames: 30,
        targetVisible: 9,
        targetFrames: 30,
        analyzing: true,
        previewStartUs: 500000,
        previewEndUs: 1500000,
        referenceAnalyzed: 10,
        targetAnalyzed: 10,
      );
      await Future<void>.delayed(const Duration(milliseconds: 240));
      expect(c.cutoutActive, isTrue);
      expect(c.cutoutPartial, isTrue);
      expect(c.cutoutPreparing, isFalse);
      expect(c.cutoutPlayableRange, (500000, 1500000));
      expect(c.cutoutExportReady, isFalse);
      expect(await c.beginCanvasEditing(), isFalse);
      await c.seek(4000000);
      expect(c.playback?.positionUs, 1499999);
      gateway.pending!.complete(gateway.result());
      await work;
      expect(c.cutoutPartial, isFalse);
      expect(c.cutoutAnalysisPending, isFalse);
      expect(c.cutoutExportReady, isTrue);
      expect(c.cutoutPlayableRange, isNull);
      expect(c.canEditCanvas, isFalse);
      expect(c.canExportCanvas, isFalse);
      c.setViewport(420, 620, false);
      expect(c.canEditCanvas, isFalse);
      expect(c.canExportCanvas, isFalse);
      expect(await c.beginCanvasEditing(), isFalse);
      await Future<void>.delayed(const Duration(milliseconds: 220));
      expect(c.canEditCanvas, isTrue);
      expect(c.canExportCanvas, isTrue);
      c.dispose();
    },
  );

  test('exit during partial preview discards late completion', () async {
    final gateway = ProgressiveCutoutGateway()
      ..pending = Completer<ComparisonCutoutResultDto>();
    final c = ComparisonController(gateway);
    c.setViewport(400, 600, false);
    await c.initialize();
    await Future<void>.delayed(const Duration(milliseconds: 220));
    expect(await c.beginCutout(), isTrue);
    final work = c.prepareCutout(
      ComparisonCutoutSeedDto(sourcePtsUs: 1000000, x: .5, y: .5),
      ComparisonCutoutSeedDto(sourcePtsUs: 3000000, x: .5, y: .5),
    );
    gateway.progress = ComparisonCutoutResultDto(
      playback: gateway.current,
      referenceVisible: 10,
      referenceFrames: 30,
      targetVisible: 10,
      targetFrames: 30,
      analyzing: true,
      previewStartUs: 500000,
      previewEndUs: 1500000,
      referenceAnalyzed: 10,
      targetAnalyzed: 10,
    );
    await Future<void>.delayed(const Duration(milliseconds: 240));
    expect(c.cutoutPartial, isTrue);
    await c.exitCutout();
    gateway.pending!.complete(gateway.result());
    await work;
    expect(c.cutoutMode, isFalse);
    expect(c.cutoutAnalysisPending, isFalse);
    expect(gateway.clears, 1);
    c.dispose();
  });

  test('cancelling analysis discards a late result', () async {
    final gateway = CutoutGateway()
      ..pending = Completer<ComparisonCutoutResultDto>();
    final controller = ComparisonController(gateway);
    controller.setViewport(400, 600, false);
    await controller.initialize();
    await Future<void>.delayed(const Duration(milliseconds: 220));
    expect(await controller.beginCutout(), isTrue);
    final work = controller.prepareCutout(
      ComparisonCutoutSeedDto(sourcePtsUs: 1000000, x: .5, y: .5),
      ComparisonCutoutSeedDto(sourcePtsUs: 3000000, x: .5, y: .5),
    );
    await controller.exitCutout();
    gateway.pending!.complete(gateway.result());
    await work;
    expect(controller.cutoutMode, isFalse);
    expect(gateway.clears, 1);
    controller.dispose();
  });
}
