import 'package:app/app/theme.dart';
import 'package:app/features/comparison/presentation/comparison_controller.dart';
import 'package:app/features/comparison/presentation/comparison_screen.dart';
import 'package:app/features/comparison/presentation/comparison_manual_session.dart';
import 'package:dance_domain/dance_domain.dart';
import 'package:dance_native/dance_native.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'comparison_test.dart'
    show teacher, learner, savedProject, FakeComparisonGateway, FakeCueVideo;

void main() {
  test(
    'a repeated first-anchor action cannot recapture the suggested second position',
    () async {
      final session = ComparisonManualSession(
        savedProject(),
        factory: (uri) => FakeCueVideo(uri, []),
      );
      await session.initialize();
      await session.seek(0, 2000000);
      await session.seek(1, 3600000);
      session.setRateCalibration(true);
      await Future.wait([
        session.recordFirstAnchor(),
        session.recordFirstAnchor(),
      ]);
      expect(session.firstAnchor, (2000000, 3600000));
      expect(session.anchorIndex, 1);
      expect(session.anchoring, false);
      await session.close();
    },
  );

  testWidgets(
    'failed dual-anchor save stays open and cancel restores the saved rate',
    (tester) async {
      final project = savedProject().copyWith(
        timeScale: .8,
        teacherAnchorUs: 2000000,
        learnerAnchorUs: 3600000,
        teacherAnchor2Us: 10000000,
        learnerAnchor2Us: 10000000,
      );
      final gateway = FakeComparisonGateway(saved: [project]);
      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.darkTheme,
          home: ComparisonScreen(
            gateway: gateway,
            manualVideoFactory: (uri) => FakeCueVideo(uri, gateway.events),
          ),
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('comparison-calibration')));
      await tester.pumpAndSettle();
      tester
          .widget<Slider>(
            find.byKey(const ValueKey('comparison-manual-seek-1')),
          )
          .onChangeEnd!(11000000);
      await tester.pumpAndSettle();
      gateway.failSave = true;
      await tester.tap(find.text('完成校准'));
      await tester.pumpAndSettle();
      expect(find.text('校准速度差'), findsOneWidget);
      expect(gateway.projects.first.timeScale, .8);
      gateway.failSave = false;
      await tester.tap(find.text('取消'));
      await tester.pumpAndSettle();
      expect(gateway.projects.first.timeScale, .8);
      expect(gateway.projects.first.learnerAnchor2Us, 10000000);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox());
      await tester.pumpAndSettle();
    },
  );
  for (final size in [const Size(320, 640), const Size(640, 320)]) {
    testWidgets('dual-anchor controls remain reachable at large text $size', (
      tester,
    ) async {
      tester.view.physicalSize = size;
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final gateway = FakeComparisonGateway(
        saved: [savedProject().copyWith(sideBySide: false)],
      );
      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.darkTheme,
          builder: (context, child) => MediaQuery(
            data: MediaQuery.of(
              context,
            ).copyWith(textScaler: const TextScaler.linear(2)),
            child: child!,
          ),
          home: ComparisonScreen(
            gateway: gateway,
            manualVideoFactory: (uri) => FakeCueVideo(uri, gateway.events),
          ),
        ),
      );
      await tester.pumpAndSettle();
      await tester.ensureVisible(
        find.byKey(const ValueKey('comparison-calibration')),
      );
      await tester.tap(find.byKey(const ValueKey('comparison-calibration')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('校准速度差'));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      for (var role = 0; role < 2; role++) {
        final pane = tester.getSize(
          find.byKey(ValueKey('comparison-manual-pane-$role')),
        );
        final controls = tester.getSize(
          find.byKey(ValueKey('comparison-manual-controls-$role')),
        );
        expect(
          pane.height - controls.height,
          greaterThanOrEqualTo(48),
          reason: 'Dual-anchor actions must leave a visible source preview',
        );
      }

      expect(
        tester
            .getSize(find.byKey(const ValueKey('comparison-manual-apply')))
            .height,
        greaterThanOrEqualTo(48),
      );
      await tester.pumpWidget(const SizedBox());
      await tester.pumpAndSettle();
    });
  }
  test(
    'old projects default to same speed, affine mapping and both anchors round trip',
    () {
      final old = savedProject().toJson()..remove('timeScale');
      expect(ComparisonProject.fromJson(old).timeScale, 1);
      final p = savedProject().copyWith(
        timeScale: .8,
        teacherAnchorUs: 2000000,
        learnerAnchorUs: 3600000,
        teacherAnchor2Us: 10000000,
        learnerAnchor2Us: 10000000,
      );
      final restored = ComparisonProject.fromJson(p.toJson());
      expect(restored.toJson(), p.toJson());
      expect(restored.musicMapping.target(10000000), 10000000);
      for (final value in [double.nan, double.infinity, .49, 2.01]) {
        expect(
          () => ComparisonProject.fromJson({...old, 'timeScale': value}),
          throwsFormatException,
        );
      }
    },
  );

  test(
    'two music events solve rate and offset, reversed or close anchors are rejected',
    () {
      final m = ComparisonMusicMapping.anchors(
        2000000,
        3600000,
        10000000,
        10000000,
      );
      expect(m.timeScale, .8);
      expect(m.offsetUs, 2000000);
      expect(m.inverse.target(m.target(2033333)), closeTo(2033333, 1));
      expect(
        () => ComparisonMusicMapping.anchors(
          10000000,
          10000000,
          2000000,
          3600000,
        ),
        throwsFormatException,
      );
      expect(
        () => ComparisonMusicMapping.anchors(0, 0, 1000000, 1000000),
        throwsFormatException,
      );
    },
  );

  test(
    'swapping inverts mapping, rescales practice time and keeps source markers',
    () {
      final marker = ComparisonMarker(
        id: 'm',
        sourceUri: teacher.uri,
        startUs: 3000000,
        endUs: 7000000,
        note: '目标拖拍',
      );
      final p = savedProject().copyWith(
        timeScale: .8,
        positionUs: 5000000,
        loopStartUs: 3000000,
        loopEndUs: 7000000,
        loopEnabled: true,
        markers: [marker],
      );
      final swapped = p.swapSources();
      expect(swapped.timeScale, 1.25);
      expect(swapped.offsetUs, -2500000);
      expect(swapped.positionUs, 4000000);
      expect(swapped.loopStartUs, 2400000);
      expect(swapped.loopEndUs, 5600000);
      final range = swapped.musicMapping.overlap(
        learner.durationUs,
        teacher.durationUs,
      )!;
      final segment = swapped.markerRange(
        marker,
        teacherStartUs: range.$1,
        durationUs: range.$2 - range.$1,
      )!;
      expect(segment.startUs, 2400000);
      expect(segment.endUs, 5600000);
      expect(swapped.swapSources().loopEndUs, p.loopEndUs);
      expect(swapped.swapSources().markers.single, same(marker));
    },
  );

  test(
    'recalibration rebases loop and position to preserve the reference-source segment',
    () {
      final p = savedProject().copyWith(
        offsetUs: 0,
        positionUs: 6000000,
        loopStartUs: 4000000,
        loopEndUs: 8000000,
        loopEnabled: true,
      );
      final next = p.withMusicMapping(
        const ComparisonMusicMapping(-2000000, .8),
      );
      expect(next.positionUs, 3500000);
      expect(next.loopStartUs, 1500000);
      expect(next.loopEndUs, 5500000);
      expect(next.loopEnabled, true);
      final unavailable = p
          .copyWith(loopStartUs: 0, loopEndUs: 1000000)
          .withMusicMapping(const ComparisonMusicMapping(-2000000, .8));
      expect(unavailable.loopEnabled, false);
      expect(unavailable.loopStartUs, isNull);
    },
  );

  test(
    'automatic music rate is persisted independently of viewing speed and PTS stepping',
    () async {
      final gateway = FakeComparisonGateway(
        saved: [savedProject().copyWith(speed: .75)],
      );
      gateway.alignmentResult = MusicAlignmentDto(
        status: 'matched',
        message: 'ok',
        candidates: [
          MusicCandidateDto(
            offsetUs: 2000000,
            timeScale: .8,
            score: .95,
            consistentWindows: 5,
            supportedSeconds: 12,
            residualMs: 5,
          ),
        ],
      );
      final c = ComparisonController(gateway)..setViewport(500, 800, false);
      await c.initialize();
      await c.matchMusic();
      expect(c.project.timeScale, .8);
      expect(gateway.projects.first.timeScale, .8);
      expect(c.project.speed, .75);
      await c.stepFrame(true);
      expect(c.playback!.teacherFrameUs, 1041007);
      expect(c.playback!.learnerFrameUs, 2832806);
      await c.setSpeed(.5);
      expect(c.project.timeScale, .8);
      c.dispose();
    },
  );

  test(
    'inconsistent rate evidence never replaces saved music calibration',
    () async {
      final gateway = FakeComparisonGateway(saved: [savedProject()]);
      gateway.alignmentResult = MusicAlignmentDto(
        status: 'inconsistent',
        message: '请手动校准',
        candidates: [
          MusicCandidateDto(
            offsetUs: -1000000,
            timeScale: .7,
            score: .8,
            consistentWindows: 2,
            supportedSeconds: 12,
            residualMs: 90,
          ),
        ],
      );
      final c = ComparisonController(gateway);
      await c.initialize();
      await c.matchMusic();
      expect(c.project.offsetUs, 2000000);
      expect(c.project.timeScale, 1);
      c.dispose();
    },
  );

  test(
    'dual manual anchors and target-source marker edits survive reopening',
    () async {
      final marker = ComparisonMarker(
        id: 'target_marker',
        sourceUri: learner.uri,
        startUs: 2800000,
        endUs: 4400000,
        note: '抢拍',
      );
      final gateway = FakeComparisonGateway(
        saved: [
          savedProject().copyWith(markers: [marker]),
        ],
      );
      final c = ComparisonController(gateway)..setViewport(500, 800, false);
      await c.initialize();
      expect(
        await c.manual(
          2000000,
          3600000,
          teacher2Us: 10000000,
          learner2Us: 10000000,
        ),
        true,
      );
      expect(c.project.timeScale, .8);
      expect(c.project.teacherAnchor2Us, 10000000);
      expect(c.markerRange(marker)!.startUs, 1000000);
      expect(
        await c.saveMarker(
          existing: marker,
          startUs: 2000000,
          endUs: 4000000,
          note: '仍抢拍',
        ),
        true,
      );
      expect(c.project.markers.single.startUs, 3600000);
      expect(c.project.markers.single.endUs, 5200000);
      final restored = ComparisonProject.fromJson(
        gateway.projects.first.toJson(),
      );
      expect(restored.timeScale, .8);
      expect(restored.learnerAnchor2Us, 10000000);
      c.dispose();
      final reopened = ComparisonController(
        FakeComparisonGateway(saved: [restored]),
      );
      await reopened.initialize();
      expect(reopened.project.timeScale, .8);
      expect(reopened.project.markers.single.note, '仍抢拍');
      reopened.dispose();
    },
  );

  testWidgets(
    'two-point calibration records first event, then saves second event',
    (tester) async {
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
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('comparison-calibration')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('校准速度差'));
      await tester.pumpAndSettle();
      Future<void> cue(int role, int value) async {
        tester
            .widget<Slider>(
              find.byKey(ValueKey('comparison-manual-seek-$role')),
            )
            .onChangeEnd!(value.toDouble());
        await tester.pumpAndSettle();
      }

      await cue(0, 2000000);
      await cue(1, 3600000);
      await tester.tap(find.text('记录前段音乐点'));
      await tester.pumpAndSettle();
      expect(find.text('前段音乐点 ✓'), findsOneWidget);
      await cue(0, 10000000);
      await cue(1, 10000000);
      await tester.tap(find.text('完成校准'));
      await tester.pumpAndSettle();
      expect(gateway.projects.first.timeScale, .8);
      expect(gateway.projects.first.offsetUs, 2000000);
      expect(gateway.projects.first.teacherAnchor2Us, 10000000);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox());
      await tester.pumpAndSettle();
    },
  );
}
