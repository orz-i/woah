import 'dart:async';
import 'dart:convert';
import 'package:app/app/theme.dart';
import 'package:app/features/comparison/presentation/comparison_controller.dart';
import 'package:app/features/comparison/presentation/comparison_screen.dart';
import 'package:app/features/comparison/presentation/comparison_picture_controls.dart';
import 'package:app/features/comparison/presentation/comparison_time.dart';
import 'package:dance_domain/dance_domain.dart';
import 'package:dance_native/dance_native.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'comparison_test.dart'
    show FakeComparisonGateway, FakeCueVideo, savedProject, teacher;

class DelayedPictureGateway extends FakeComparisonGateway {
  DelayedPictureGateway() : super(saved: [savedProject()]);
  final firstPicture = Completer<void>();
  final seen = <ComparisonPicture>[];
  @override
  Future<ComparisonPlaybackDto> setPractice(ComparisonProject project) async {
    seen.add(project.teacherPicture);
    if (seen.length == 1) await firstPicture.future;
    return super.setPractice(project);
  }
}

Future<ComparisonController> ready(FakeComparisonGateway gateway) async {
  final controller = ComparisonController(gateway);
  controller.setViewport(400, 600, false);
  await controller.initialize();
  return controller;
}

Future<void> enterAdjustment(WidgetTester tester) async {
  await tester.tap(find.byKey(const ValueKey('comparison-view')));
  await tester.pumpAndSettle();
  await tester.ensureVisible(find.text('调整画面'));
  await tester.tap(
    find
        .ancestor(
          of: find.text('调整画面'),
          matching: find.byWidgetPredicate((widget) => widget is PopupMenuItem),
        )
        .first,
  );
  await tester.pumpAndSettle();
}

void main() {
  for (final brightness in Brightness.values) {
    testWidgets('view options show check on active state ${brightness.name}', (
      tester,
    ) async {
      final gateway = FakeComparisonGateway(saved: [savedProject()]);
      await tester.pumpWidget(
        MaterialApp(
          theme: brightness == Brightness.dark
              ? AppTheme.darkTheme
              : AppTheme.lightTheme,
          home: ComparisonScreen(gateway: gateway),
        ),
      );
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('comparison-view')));
      await tester.pumpAndSettle();
      expect(
        find.byKey(const ValueKey('comparison-view-selected-mark')),
        findsOneWidget,
      );
      await tester.tap(find.text('左右视图'));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('comparison-view')));
      await tester.pumpAndSettle();
      expect(
        find.byKey(const ValueKey('comparison-view-selected-mark')),
        findsOneWidget,
      );
      expect(tester.takeException(), isNull);
    });
  }

  for (final brightness in Brightness.values) {
    testWidgets('picture target selection tonal cue ${brightness.name}', (
      tester,
    ) async {
      var reference = true;
      final colors = brightness == Brightness.dark
          ? AppTheme.darkColors
          : AppTheme.lightColors;
      await tester.pumpWidget(
        MaterialApp(
          theme: brightness == Brightness.dark
              ? AppTheme.darkTheme
              : AppTheme.lightTheme,
          home: Scaffold(
            body: StatefulBuilder(
              builder: (context, setState) => ComparisonPictureControls(
                project: savedProject(),
                reference: reference,
                onSelect: (v) => setState(() => reference = v),
                onChange: (_) {},
                onApply: () {},
                onCancel: () {},
              ),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      SegmentedButton<bool> segment() => tester.widget<SegmentedButton<bool>>(
        find.byType(SegmentedButton<bool>),
      );
      expect(segment().showSelectedIcon, isFalse);
      final background = segment().style!.backgroundColor!;
      final foreground = segment().style!.foregroundColor!;
      expect(
        background.resolve({WidgetState.selected}),
        colors.editorSelectionSubtle,
      );
      expect(background.resolve({}), Colors.transparent);
      expect(
        foreground.resolve({WidgetState.selected}),
        colors.editorSelection,
      );
      expect(foreground.resolve({}), colors.flowTextSecondary);
      expect(segment().selected, {true});

      await tester.tap(find.text('目标'));
      await tester.pumpAndSettle();
      expect(reference, isFalse);
      expect(segment().selected, {false});
      expect(
        background.resolve({WidgetState.selected}),
        colors.editorSelectionSubtle,
      );
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets(
    'large text keeps picture actions reachable and marker retry saves exactly once',
    (tester) async {
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      for (final size in [const Size(640, 320), const Size(320, 640)]) {
        tester.view.physicalSize = size;
        final gateway = FakeComparisonGateway(saved: [savedProject()]);
        await tester.pumpWidget(
          MaterialApp(
            theme: AppTheme.darkTheme,
            builder: (context, child) => MediaQuery(
              data: MediaQuery.of(
                context,
              ).copyWith(textScaler: TextScaler.linear(2)),
              child: child!,
            ),
            home: ComparisonScreen(gateway: gateway),
          ),
        );
        await tester.pump(const Duration(milliseconds: 400));
        await tester.pumpAndSettle();
        await enterAdjustment(tester);
        expect(
          tester.takeException(),
          isNull,
          reason: 'picture controls $size',
        );
        for (final label in [
          '重置参考画面',
          '放大参考',
          '参考画面左移',
          '参考画面右移',
          '参考画面上移',
          '参考画面下移',
        ]) {
          final target = find.byTooltip(label);
          await tester.ensureVisible(target);
          expect(tester.getSize(target).width, greaterThanOrEqualTo(48));
          expect(tester.getSize(target).height, greaterThanOrEqualTo(48));
        }
        await tester.ensureVisible(
          find.byKey(const ValueKey('comparison-picture-cancel')),
        );
        await tester.tap(
          find.byKey(const ValueKey('comparison-picture-cancel')),
        );
        await tester.pumpAndSettle();
        await tester.ensureVisible(
          find.byKey(const ValueKey('comparison-markers')),
        );
        await tester.tap(find.byKey(const ValueKey('comparison-markers')));
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const ValueKey('comparison-marker-add')));
        await tester.pumpAndSettle();
        await tester.enterText(
          find.byKey(const ValueKey('comparison-marker-start')),
          '1.000007',
        );
        await tester.enterText(
          find.byKey(const ValueKey('comparison-marker-end')),
          '2.000009',
        );
        await tester.enterText(
          find.byKey(const ValueKey('comparison-marker-note')),
          '转身后抬手偏晚',
        );
        gateway.failSave = true;
        await tester.tap(find.byKey(const ValueKey('comparison-marker-save')));
        await tester.pumpAndSettle();
        expect(
          find.byKey(const ValueKey('comparison-marker-save')),
          findsOneWidget,
        );
        expect(gateway.projects.first.markers, isEmpty);
        gateway.failSave = false;
        await tester.tap(find.byKey(const ValueKey('comparison-marker-save')));
        await tester.pumpAndSettle();
        expect(gateway.projects.first.markers.length, 1);
        expect(find.text('转身后抬手偏晚'), findsOneWidget);
        expect(tester.takeException(), isNull);
        await tester.pumpWidget(const SizedBox.shrink());
        await tester.pumpAndSettle();
      }
    },
  );
  test(
    'picture and source-anchored markers round trip, swap and accept old projects',
    () {
      const marker = ComparisonMarker(
        id: 'mark_one',
        sourceUri: 'content://test/teacher',
        startUs: 1000007,
        endUs: 2000009,
        note: '抬手偏晚',
      );
      final project = savedProject().copyWith(
        teacherPicture: const ComparisonPicture(zoom: 2, panX: .2, panY: -.3),
        learnerPicture: const ComparisonPicture(zoom: 1.5, panY: .4),
        markers: [marker],
      );
      final restored = ComparisonProject.fromJson(
        jsonDecode(jsonEncode(project.toJson())),
      );
      expect(restored.teacherPicture, project.teacherPicture);
      expect(restored.markers.single.startUs, 1000007);
      expect(restored.markers.single.note, '抬手偏晚');
      final swap = restored.swapSources();
      expect(swap.teacherPicture, project.learnerPicture);
      expect(swap.learnerPicture, project.teacherPicture);
      expect(swap.markers.single.sourceUri, teacher.uri);
      final old = project.toJson()
        ..remove('teacherPicture')
        ..remove('learnerPicture')
        ..remove('markers');
      expect(
        ComparisonProject.fromJson(old).teacherPicture,
        const ComparisonPicture(),
      );
      expect(ComparisonProject.fromJson(old).markers, isEmpty);
      final invalid = project.toJson()
        ..['teacherPicture'] = {'zoom': 5, 'panX': 0, 'panY': 0};
      expect(() => ComparisonProject.fromJson(invalid), throwsFormatException);
      final duplicate = project.toJson()
        ..['markers'] = [marker.toJson(), marker.toJson()];
      expect(
        () => ComparisonProject.fromJson(duplicate),
        throwsFormatException,
      );
      expect(ComparisonPicture.clamped(1, .5, -.8), const ComparisonPicture());
    },
  );

  test(
    'marker anchors survive negative offsets, swaps, partial overlap and replacement',
    () {
      const marker = ComparisonMarker(
        id: 'mark',
        sourceUri: 'content://test/teacher',
        startUs: 2600000,
        endUs: 3200000,
      );
      final p = savedProject().copyWith(offsetUs: -2000000, markers: [marker]);
      final range = p.markerRange(
        marker,
        teacherStartUs: 2500000,
        durationUs: 5000000,
      )!;
      expect(
        (range.startUs, range.endUs, range.clipped),
        (100000, 700000, false),
      );
      final swapped = p.swapSources().markerRange(
        marker,
        teacherStartUs: 500000,
        durationUs: 5000000,
      )!;
      expect((swapped.startUs, swapped.endUs), (100000, 700000));
      final partial = p.markerRange(
        marker,
        teacherStartUs: 2800000,
        durationUs: 5000000,
      )!;
      expect(
        (partial.startUs, partial.endUs, partial.clipped),
        (0, 400000, true),
      );
      final replacement = p.copyWith(
        teacher: const ComparisonSource(
          uri: 'content://new',
          name: '新参考',
          durationUs: 12000000,
          hasAudio: true,
        ),
      );
      expect(
        replacement.markerRange(
          marker,
          teacherStartUs: 0,
          durationUs: 10000000,
        ),
        isNull,
      );
      expect(replacement.markers.single.startUs, 2600000);
    },
  );

  test(
    'time input keeps microseconds and rejects invalid ranges or precision',
    () {
      for (final value in [0, 1000007, 59999999, 61033333]) {
        expect(parseComparisonTime(comparisonPreciseTime(value)), value);
      }
      expect(parseComparisonTime('2.000009'), 2000009);
      for (final text in [
        '-1',
        '00:60',
        '1.0000001',
        'NaN',
        '9999999999999999999999999999',
      ]) {
        expect(() => parseComparisonTime(text), throwsFormatException);
      }
    },
  );

  test(
    'picture preview coalesces to latest and only confirmation persists it',
    () async {
      final gateway = DelayedPictureGateway();
      final controller = await ready(gateway);
      addTearDown(controller.dispose);
      expect(await controller.beginPictureAdjustment(), true);
      controller.previewPicture(
        true,
        const ComparisonPicture(zoom: 1.5, panX: .2),
      );
      await Future<void>.delayed(const Duration(milliseconds: 40));
      controller.previewPicture(true, const ComparisonPicture(zoom: 2));
      controller.previewPicture(
        true,
        const ComparisonPicture(zoom: 3, panY: .5),
      );
      expect(gateway.projects.first.teacherPicture, const ComparisonPicture());
      final completed = controller.finishPictureAdjustment(applied: true);
      gateway.firstPicture.complete();
      expect(await completed, true);
      expect(gateway.seen.map((v) => v.zoom), [1.5, 3]);
      expect(gateway.projects.first.teacherPicture.zoom, 3);
      expect(gateway.projects.first.learnerPicture, const ComparisonPicture());
      expect(controller.project.positionUs, 1000000);
      expect(controller.project.offsetUs, 2000000);
    },
  );

  test(
    'cancel and background preserve committed pictures and save failures keep adjustment open',
    () async {
      final gateway = FakeComparisonGateway(saved: [savedProject()]);
      final controller = await ready(gateway);
      addTearDown(controller.dispose);
      await controller.beginPictureAdjustment();
      controller.previewPicture(
        false,
        const ComparisonPicture(zoom: 2, panX: .5),
      );
      await controller.background();
      expect(gateway.projects.first.learnerPicture, const ComparisonPicture());
      expect(await controller.finishPictureAdjustment(applied: false), true);
      expect(controller.project.learnerPicture, const ComparisonPicture());
      await controller.beginPictureAdjustment();
      controller.previewPicture(true, const ComparisonPicture(zoom: 2.5));
      gateway.failSave = true;
      expect(await controller.finishPictureAdjustment(applied: true), false);
      expect(controller.adjusting, true);
      gateway.failSave = false;
      expect(await controller.finishPictureAdjustment(applied: true), true);
      expect(gateway.projects.first.teacherPicture.zoom, 2.5);
    },
  );

  test(
    'markers edit, set A/B, jump out of old loops and retain inaccessible notes',
    () async {
      final gateway = FakeComparisonGateway(saved: [savedProject()]);
      final controller = await ready(gateway);
      addTearDown(controller.dispose);
      expect(
        await controller.saveMarker(
          startUs: 1000007,
          endUs: 2000009,
          note: '抬手',
        ),
        true,
      );
      var marker = controller.project.markers.single;
      expect(marker.sourceUri, teacher.uri);
      expect(
        await controller.saveMarker(
          existing: marker,
          startUs: 2000007,
          endUs: 3000009,
          note: '抬手偏晚',
        ),
        true,
      );
      marker = controller.project.markers.single;
      expect(await controller.openMarker(marker, repeat: true), true);
      expect(
        (controller.project.loopStartUs, controller.project.loopEndUs),
        (2000007, 3000009),
      );
      expect(controller.playing, false);
      expect(await controller.openMarker(marker), true);
      expect(controller.project.loopEnabled, false);
      await controller.manual(0, 2500000);
      expect(controller.project.markers.single.startUs, 2000007);
      controller.project = controller.project.copyWith(
        teacher: const ComparisonSource(
          uri: 'content://new',
          name: '新参考',
          durationUs: 12000000,
          hasAudio: true,
        ),
      );
      expect(controller.markerRange(marker), isNull);
      expect(
        await controller.saveMarker(
          existing: marker,
          startUs: 0,
          endUs: 1,
          note: '保留原位置',
        ),
        true,
      );
      expect(controller.project.markers.single.startUs, 2000007);
      expect(await controller.deleteMarker(marker.id), true);
      expect(gateway.projects.first.markers, isEmpty);
      expect(controller.project.offsetUs, 2500000);
    },
  );

  test(
    'failed marker saves do not duplicate on retry or discard a failed deletion',
    () async {
      final gateway = FakeComparisonGateway(saved: [savedProject()]);
      final controller = await ready(gateway);
      addTearDown(controller.dispose);
      gateway.failSave = true;
      expect(
        await controller.saveMarker(
          startUs: 1000000,
          endUs: 2000000,
          note: '问题',
        ),
        false,
      );
      expect(controller.project.markers, isEmpty);
      gateway.failSave = false;
      expect(
        await controller.saveMarker(
          startUs: 1000000,
          endUs: 2000000,
          note: '问题',
        ),
        true,
      );
      final marker = controller.project.markers.single;
      gateway.failSave = true;
      expect(await controller.deleteMarker(marker.id), false);
      expect(controller.project.markers.single.id, marker.id);
    },
  );

  testWidgets(
    'picture controls, drag and pinch affect only the selected video and save on done',
    (tester) async {
      final semantics = tester.ensureSemantics();
      tester.view.physicalSize = const Size(360, 800);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final gateway = FakeComparisonGateway(saved: [savedProject()]);
      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.darkTheme,
          home: ComparisonScreen(
            gateway: gateway,
            manualVideoFactory: (uri) => FakeCueVideo(uri, gateway.events),
          ),
        ),
      );
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();
      await enterAdjustment(tester);
      for (var i = 0; i < 4; i++) {
        await tester.tap(find.byTooltip('放大参考'));
        await tester.pump(const Duration(milliseconds: 40));
      }
      final pane = find.byKey(
        const ValueKey('comparison-picture-pane-teacher'),
      );
      final center = tester.getCenter(pane);
      final drag = await tester.startGesture(center);
      await drag.moveBy(const Offset(20, -10));
      await tester.pump();
      await drag.moveBy(const Offset(35, -25));
      await tester.pump(const Duration(milliseconds: 40));
      await drag.up();
      await tester.pump(const Duration(milliseconds: 40));
      expect(gateway.practice!.teacherPicture.panX, greaterThan(0));
      final before = gateway.practice!.teacherPicture.zoom;
      final one = await tester.startGesture(
        center - const Offset(25, 0),
        pointer: 1,
      );
      final two = await tester.startGesture(
        center + const Offset(25, 0),
        pointer: 2,
      );
      await tester.pump();
      await one.moveTo(center - const Offset(50, 0));
      await two.moveTo(center + const Offset(50, 0));
      await tester.pump(const Duration(milliseconds: 40));
      await one.up();
      await two.up();
      await tester.pump(const Duration(milliseconds: 40));
      expect(gateway.practice!.teacherPicture.zoom, greaterThan(before));
      expect(gateway.practice!.learnerPicture, const ComparisonPicture());
      expect(gateway.pickedRoles, isEmpty);
      await tester.tap(
        find.descendant(
          of: find.byType(SegmentedButton<bool>),
          matching: find.text('目标'),
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.byTooltip('放大目标'));
      await tester.pump(const Duration(milliseconds: 40));
      await tester.tap(find.byTooltip('目标画面下移'));
      await tester.pump(const Duration(milliseconds: 40));
      await tester.ensureVisible(
        find.byKey(const ValueKey('comparison-picture-apply')),
      );
      await tester.tap(find.byKey(const ValueKey('comparison-picture-apply')));
      await tester.pumpAndSettle();
      expect(gateway.projects.first.teacherPicture.zoom, greaterThan(2));
      expect(gateway.projects.first.learnerPicture.zoom, 1.25);
      expect(gateway.projects.first.learnerPicture.panY, .1);
      await tester.tap(find.byKey(const ValueKey('comparison-markers')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('comparison-marker-add')));
      await tester.pumpAndSettle();
      await tester.enterText(
        find.byKey(const ValueKey('comparison-marker-start')),
        '1.000007',
      );
      await tester.enterText(
        find.byKey(const ValueKey('comparison-marker-end')),
        '2.000009',
      );
      await tester.enterText(
        find.byKey(const ValueKey('comparison-marker-note')),
        '抬手偏晚',
      );
      await tester.tap(find.byKey(const ValueKey('comparison-marker-save')));
      await tester.pumpAndSettle();
      expect(gateway.projects.first.markers.single.startUs, 1000007);
      expect(find.text('抬手偏晚'), findsOneWidget);
      await tester.tap(find.byTooltip('将片段设为 A/B 循环'));
      await tester.pumpAndSettle();
      expect(gateway.projects.first.loopStartUs, 1000007);
      expect(gateway.projects.first.loopEndUs, 2000009);
      expect(find.byKey(const ValueKey('comparison-play')), findsOneWidget);
      expect(
        tester
            .getSemantics(find.byKey(const ValueKey('comparison-markers')))
            .label,
        contains('问题片段'),
      );
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump();
      semantics.dispose();
    },
  );
}
