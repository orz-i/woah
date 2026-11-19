import 'dart:async';
import 'package:app/app/theme.dart';
import 'package:app/features/comparison/presentation/comparison_controller.dart';
import 'package:app/features/comparison/presentation/comparison_manual_session.dart';
import 'package:app/features/comparison/presentation/comparison_screen.dart';
import 'package:dance_domain/dance_domain.dart';
import 'package:dance_native/dance_native.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'comparison_test.dart'
    show FakeComparisonGateway, FakeCueVideo, savedProject;

MusicAlignmentDto ambiguous() => MusicAlignmentDto(
  status: 'ambiguous',
  message: '音乐位置待确认',
  candidates: [
    for (final offset in [1000000, 10000000])
      MusicCandidateDto(
        offsetUs: offset,
        timeScale: 1,
        score: .9,
        consistentWindows: 3,
        supportedSeconds: 8,
        residualMs: 0,
      ),
  ],
);

FakeComparisonGateway gatewayForReview() => FakeComparisonGateway(
  saved: [savedProject().copyWith(alignment: 'unmatched')],
)..alignmentResult = ambiguous();

class DelayedCueVideo extends FakeCueVideo {
  DelayedCueVideo(super.source, super.events);
  Completer<void>? pauseGate;
  Completer<void>? seekGate;
  bool failSeek = false;
  @override
  Future<void> pause() async {
    await pauseGate?.future;
    await super.pause();
  }

  @override
  Future<void> seekTo(Duration position) async {
    await seekGate?.future;
    if (failSeek) throw StateError('unavailable');
    await super.seekTo(position);
  }
}

class BufferingCueVideo extends FakeCueVideo {
  BufferingCueVideo(super.source, super.events);

  @override
  Future<void> play() async {
    value = value.copyWith(isPlaying: true, isBuffering: true);
  }
}

void advanceAudition(FakeCueVideo video) {
  for (var i = 0; i < 2; i++) {
    video.value = video.value.copyWith(
      position: video.value.position + const Duration(milliseconds: 100),
    );
  }
}

void main() {
  test(
    'cancelling candidate review never writes its mapping or loses saved anchors',
    () async {
      final gateway = FakeComparisonGateway(saved: [savedProject()])
        ..alignmentResult = ambiguous();
      final controller = ComparisonController(gateway);
      addTearDown(controller.dispose);
      await controller.initialize();
      await controller.matchMusic();
      final before = controller.project;
      expect(await controller.enterManualCalibration(), isTrue);
      final options = controller.alignmentOptions;
      final session = ComparisonManualSession(
        before,
        factory: (uri) => FakeCueVideo(uri, gateway.events),
      );
      await session.initialize();
      expect(await session.cueCandidate(options.last, options), isTrue);
      await session.toggle(1);
      await session.close();
      await controller.leaveManualCalibration(applied: false);
      expect(controller.project.offsetUs, before.offsetUs);
      expect(controller.project.teacherAnchorUs, before.teacherAnchorUs);
      expect(controller.project.learnerAnchorUs, before.learnerAnchorUs);
      expect(gateway.projects.first.offsetUs, before.offsetUs);
      expect(controller.alignmentOptions, same(options));
    },
  );

  test(
    'failed confirmation preserves choices and old mapping for retry',
    () async {
      final gateway = gatewayForReview();
      final controller = ComparisonController(gateway);
      addTearDown(controller.dispose);
      await controller.initialize();
      expect(await controller.enterManualCalibration(), isTrue);
      final candidate = controller.alignmentOptions.last;
      gateway.failSave = true;
      expect(await controller.confirmAlignmentCandidate(candidate), isFalse);
      expect(controller.project.offsetUs, 2000000);
      expect(controller.alignmentOptions, isNotEmpty);
      gateway.failSave = false;
      expect(await controller.confirmAlignmentCandidate(candidate), isTrue);
      expect(controller.project.offsetUs, 10000000);
      await controller.leaveManualCalibration(applied: true);
    },
  );

  test('candidates from another project cannot be confirmed', () async {
    final gateway = gatewayForReview();
    final controller = ComparisonController(gateway);
    addTearDown(controller.dispose);
    await controller.initialize();
    final old = controller.alignmentOptions.last;
    await controller.newProject();
    expect(controller.alignmentOptions, isEmpty);
    expect(await controller.confirmAlignmentCandidate(old), isFalse);
  });

  test(
    'an unusable best match never promotes a weaker alternative automatically',
    () async {
      final result = ambiguous()..status = 'matched';
      result.candidates.first.offsetUs = 100000000;
      final gateway = gatewayForReview()..alignmentResult = result;
      final controller = ComparisonController(gateway);
      addTearDown(controller.dispose);
      await controller.initialize();
      expect(controller.project.offsetUs, 2000000);
      expect(controller.alignmentOptions, isEmpty);
      expect(gateway.saves, 0);
      expect(controller.alignmentNotice, contains('共同区间'));
    },
  );

  test(
    'candidate cues use the same reference passage and never play both sources',
    () async {
      final videos = <DelayedCueVideo>[];
      final session = ComparisonManualSession(
        savedProject(),
        factory: (uri) {
          final video = DelayedCueVideo(uri, []);
          videos.add(video);
          return video;
        },
      );
      await session.initialize();
      final options = [
        const ComparisonMusicMapping(1000000),
        const ComparisonMusicMapping(10000000),
      ];
      expect(await session.cueCandidate(options.first, options), isTrue);
      final reference = session.position(0);
      expect(session.position(1), reference + 1000000);
      await session.toggle(0);
      expect(session.candidateAuditioned, isFalse);
      advanceAudition(videos[0]);
      await session.toggle(1);
      expect(session.candidateAuditioned, isFalse);
      advanceAudition(videos[1]);
      expect(session.candidateAuditioned, isTrue);
      await session.toggle(0);
      expect(videos[0].value.isPlaying, isTrue);
      expect(await session.cueCandidate(options.last, options), isTrue);
      expect(videos.every((v) => !v.value.isPlaying), isTrue);
      expect(session.candidateAuditioned, isFalse);
      expect(session.position(0), reference);
      expect(session.position(1), reference + 10000000);

      videos[1].pauseGate = Completer<void>();
      final stalePlay = session.toggle(0);
      await Future<void>.delayed(Duration.zero);
      final latestPlay = session.toggle(1);
      videos[1].pauseGate!.complete();
      await stalePlay;
      await latestPlay;
      expect(videos[0].value.isPlaying, isFalse);
      expect(videos[1].value.isPlaying, isTrue);
      advanceAudition(videos[1]);
      expect(session.candidateAuditioned, isFalse);
      await session.close();
    },
  );

  test(
    'failed candidate seek cannot be confirmed as a successful cue',
    () async {
      final videos = <DelayedCueVideo>[];
      final session = ComparisonManualSession(
        savedProject(),
        factory: (uri) {
          final video = DelayedCueVideo(uri, []);
          videos.add(video);
          return video;
        },
      );
      await session.initialize();
      videos[1].failSeek = true;
      final candidate = const ComparisonMusicMapping(1000000);
      expect(await session.cueCandidate(candidate, [candidate]), isFalse);
      expect(session.error, isNotNull);
      expect(videos.every((v) => !v.value.isPlaying), isTrue);
      await session.close();
    },
  );

  for (final size in [const Size(320, 640), const Size(640, 320)]) {
    testWidgets(
      'review requires explicit selection and closes previews before reopening $size',
      (tester) async {
        tester.view.physicalSize = size;
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);
        final gateway = gatewayForReview();
        final videos = <FakeCueVideo>[];
        await tester.pumpWidget(
          MaterialApp(
            theme: AppTheme.darkTheme,
            builder: (context, child) => MediaQuery(
              data: MediaQuery.of(
                context,
              ).copyWith(textScaler: TextScaler.linear(2)),
              child: child!,
            ),
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
        await tester.pumpAndSettle();
        final originalOffset = gateway.projects.first.offsetUs;
        await tester.ensureVisible(
          find.byKey(const ValueKey('comparison-review-music')),
        );
        await tester.tap(find.byKey(const ValueKey('comparison-review-music')));
        await tester.pumpAndSettle();
        final apply = find.byKey(const ValueKey('comparison-manual-apply'));
        expect(tester.widget<FilledButton>(apply).onPressed, isNull);
        await tester.ensureVisible(
          find.byKey(const ValueKey('comparison-candidate-1')),
        );
        await tester.tap(find.byKey(const ValueKey('comparison-candidate-1')));
        await tester.pumpAndSettle();
        expect(
          videos[1].value.position.inMicroseconds -
              videos[0].value.position.inMicroseconds,
          10000000,
        );
        expect(gateway.projects.first.offsetUs, originalOffset);
        expect(tester.widget<FilledButton>(apply).onPressed, isNull);
        await tester.ensureVisible(
          find.byKey(const ValueKey('comparison-manual-play-0')),
        );
        await tester.tap(
          find.byKey(const ValueKey('comparison-manual-play-0')),
        );
        await tester.pumpAndSettle();
        advanceAudition(videos[0]);
        await tester.pump();
        expect(tester.widget<FilledButton>(apply).onPressed, isNull);
        await tester.ensureVisible(
          find.byKey(const ValueKey('comparison-manual-play-1')),
        );
        await tester.tap(
          find.byKey(const ValueKey('comparison-manual-play-1')),
        );
        await tester.pumpAndSettle();
        expect(videos[1].value.isPlaying, isTrue);
        expect(tester.widget<FilledButton>(apply).onPressed, isNull);
        advanceAudition(videos[1]);
        await tester.pump();
        await tester.ensureVisible(apply);
        await tester.tap(apply);
        await tester.pumpAndSettle();
        expect(gateway.projects.first.offsetUs, 10000000);
        expect(gateway.projects.first.alignment, 'confirmed');
        final reopen = gateway.events.lastIndexOf('shared-open');
        expect(
          reopen,
          greaterThan(gateway.events.lastIndexOf('preview-close')),
        );
        expect(videos.every((v) => v.closed), isTrue);
        expect(tester.takeException(), isNull);
        await tester.pumpWidget(const SizedBox.shrink());
      },
    );
  }

  testWidgets('buffered playback cannot confirm a candidate', (tester) async {
    final gateway = gatewayForReview();
    final previews = <BufferingCueVideo>[];
    await tester.pumpWidget(
      MaterialApp(
        home: ComparisonScreen(
          gateway: gateway,
          manualVideoFactory: (uri) {
            final video = BufferingCueVideo(uri, gateway.events);
            previews.add(video);
            return video;
          },
        ),
      ),
    );
    await tester.pumpAndSettle();
    Future<void> tap(String key) async {
      final widget = find.byKey(ValueKey(key));
      await tester.ensureVisible(widget);
      await tester.tap(widget);
      await tester.pumpAndSettle();
    }

    final original = gateway.projects.first.offsetUs;
    await tap('comparison-review-music');
    await tap('comparison-candidate-1');
    final start = previews.map((video) => video.value.position).toList();
    await tap('comparison-manual-play-0');
    await tap('comparison-manual-play-1');
    final apply = find.byKey(const ValueKey('comparison-manual-apply'));
    expect(previews.every((video) => video.value.isBuffering), isTrue);
    expect(previews.map((video) => video.value.position), start);
    expect(tester.widget<FilledButton>(apply).onPressed, isNull);
    expect(gateway.projects.first.offsetUs, original);

    // A late tick from the stopped reference must not count as listening.
    advanceAudition(previews[0]);
    final learner = previews[1];
    learner.value = learner.value.copyWith(isBuffering: false);
    advanceAudition(learner);
    await tester.pump();
    expect(tester.widget<FilledButton>(apply).onPressed, isNull);

    await tap('comparison-manual-play-0');
    final reference = previews[0];
    advanceAudition(reference); // While still buffering.
    reference.value = reference.value.copyWith(isBuffering: false);
    reference.value = reference.value.copyWith(
      position: reference.value.position + const Duration(milliseconds: 250),
    );
    await tester.pump();
    expect(tester.widget<FilledButton>(apply).onPressed, isNull);
    reference.value = reference.value.copyWith(
      position: reference.value.position + const Duration(milliseconds: 100),
    );
    await tester.pump();
    expect(tester.widget<FilledButton>(apply).onPressed, isNotNull);
    await tap('comparison-manual-apply');
    expect(gateway.projects.first.alignment, 'confirmed');
    await tester.pumpWidget(const SizedBox.shrink());
  });

  test('completed candidate playback restarts at the selected cue', () async {
    final videos = <FakeCueVideo>[];
    final session = ComparisonManualSession(
      savedProject(),
      factory: (uri) {
        final video = FakeCueVideo(uri, []);
        videos.add(video);
        return video;
      },
    );
    await session.initialize();
    const candidate = ComparisonMusicMapping(1000000);
    expect(await session.cueCandidate(candidate, [candidate]), isTrue);
    final cue = videos[1].value.position;
    videos[1].value = videos[1].value.copyWith(
      position: videos[1].value.duration,
      isCompleted: true,
    );
    await session.toggle(1);
    expect(videos[1].value.position, cue);
    await session.close();
  });

  testWidgets(
    'inconsistent music shows a visible warning and does not replace calibration',
    (tester) async {
      final gateway = gatewayForReview()
        ..alignmentResult = MusicAlignmentDto(
          status: 'inconsistent',
          message: '检测到音乐跳变，请分段校准。',
          candidates: [],
        );
      await tester.pumpWidget(
        MaterialApp(home: ComparisonScreen(gateway: gateway)),
      );
      await tester.pumpAndSettle();
      expect(find.text('检测到音乐跳变，请分段校准。'), findsOneWidget);
      expect(gateway.projects.first.offsetUs, 2000000);
      expect(
        find.byKey(const ValueKey('comparison-review-music')),
        findsNothing,
      );
      expect(find.byTooltip('手动校准'), findsOneWidget);
    },
  );
}
