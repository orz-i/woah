import 'dart:async';
import 'dart:convert';
import 'package:app/app/theme.dart';
import 'package:app/features/comparison/data/comparison_gateway.dart';
import 'package:app/features/comparison/presentation/comparison_controller.dart';
import 'package:app/features/comparison/presentation/comparison_screen.dart';
import 'package:dance_domain/dance_domain.dart';
import 'package:dance_native/dance_native.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:video_player/video_player.dart';
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
  final pickedRoles = <String>[];
  int opens = 0, cancels = 0, saves = 0, alignCalls = 0;
  final List<String> events = [];
  final layouts = <bool>[];
  MusicAlignmentDto? alignmentResult;
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
  Future<ComparisonSource?> pick(String role) async {
    pickedRoles.add(role);
    return picked;
  }

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
    events.add('shared-open');
    layouts.add(horizontal);
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
  Future<ComparisonPlaybackDto> resize(int w, int h, bool horizontal) async {
    layouts.add(horizontal);
    return current;
  }

  @override
  Future<ComparisonPlaybackDto> state() async => current;
  @override
  Future<MusicAlignmentDto> align(ComparisonProject p) {
    alignCalls++;
    return pendingMatch?.future ?? Future.value(alignmentResult ?? matched());
  }

  @override
  Future<void> cancelAlignment() async {
    cancels++;
  }

  @override
  Future<void> close() async {
    events.add('shared-close');
  }
}

class FakeCueVideo extends VideoPlayerController {
  FakeCueVideo(String source, this.events, {this.onDispose})
    : super.contentUri(Uri.parse(source)) {
    events.add('preview-create');
  }
  final List<String> events;
  final VoidCallback? onDispose;
  bool closed = false;
  @override
  Future<void> initialize() async {
    value = const VideoPlayerValue(
      duration: Duration(seconds: 15),
      size: Size(640, 480),
      isInitialized: true,
    );
  }

  @override
  Future<void> pause() async {
    value = value.copyWith(isPlaying: false);
  }

  @override
  Future<void> play() async {
    value = value.copyWith(isPlaying: true);
  }

  @override
  Future<void> seekTo(Duration position) async {
    value = value.copyWith(position: position);
  }

  @override
  Future<void> dispose() async {
    onDispose?.call();
    closed = true;
    events.add('preview-close');
    await super.dispose();
  }
}

void main() {
  test(
    'projects round trip, source swap preserves manual event and reverses offset',
    () {
      final source = savedProject().copyWith(sideBySide: true);
      final restored = ComparisonProject.fromJson(
        jsonDecode(jsonEncode(source.toJson())) as Map<String, dynamic>,
      );
      expect(restored.offsetUs, 2000000);
      expect(restored.positionUs, 1000000);
      expect(restored.sideBySide, true);
      final legacy = source.toJson()..remove('sideBySide');
      expect(ComparisonProject.fromJson(legacy).sideBySide, isNull);
      final swapped = restored.swapSources();
      expect(swapped.teacher!.uri, learner.uri);
      expect(swapped.offsetUs, -2000000);
      expect(swapped.teacherAnchorUs, 2000000);
      expect(swapped.learnerAnchorUs, 0);
      expect(swapped.positionUs, 1000000);
      expect(swapped.sideBySide, true);
      final invalid = source.toJson()..['schemaVersion'] = 999;
      expect(() => ComparisonProject.fromJson(invalid), throwsFormatException);
    },
  );
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
    'embedded source controls, history and swap stay usable in a small portrait viewport',
    (tester) async {
      tester.view.physicalSize = const Size(320, 640);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final gateway = FakeComparisonGateway(saved: [savedProject()]);
      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.darkTheme,
          home: ComparisonScreen(gateway: gateway),
        ),
      );
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pump();
      expect(find.text('舞蹈对比'), findsNothing);
      expect(find.text('参考'), findsOneWidget);
      expect(find.text('目标'), findsOneWidget);
      expect(find.byIcon(Icons.expand_more), findsNothing);
      expect(find.byKey(const ValueKey('comparison-stage')), findsOneWidget);
      expect(
        find.byKey(const ValueKey('comparison-calibration')),
        findsOneWidget,
      );
      expect(find.byKey(const ValueKey('comparison-manual')), findsNothing);
      for (final key in [
        'comparison-pick-teacher',
        'comparison-pick-learner',
        'comparison-history',
        'comparison-step-back',
        'comparison-step-forward',
        'comparison-play',
        'comparison-speed',
        'comparison-calibration',
      ]) {
        final rect = tester.getRect(find.byKey(ValueKey(key)));
        expect(rect.width, greaterThanOrEqualTo(48), reason: key);
        expect(rect.height, greaterThanOrEqualTo(48), reason: key);
        expect(rect.bottom, lessThanOrEqualTo(640), reason: key);
      }
      final stage = tester.getRect(
        find.byKey(const ValueKey('comparison-stage')),
      );
      for (final role in ['teacher', 'learner']) {
        final source = find.byKey(ValueKey('comparison-pick-$role'));
        final rect = tester.getRect(source);
        expect(stage.contains(rect.center), isTrue);
        await tester.tapAt(rect.bottomRight - const Offset(12, 12));
        await tester.pumpAndSettle();
        expect(gateway.pickedRoles.last, role);
      }
      expect(
        tester
            .getTopLeft(find.byKey(const ValueKey('comparison-pick-learner')))
            .dy,
        greaterThanOrEqualTo(stage.center.dy),
      );
      expect(
        tester.getCenter(find.byKey(const ValueKey('comparison-view'))).dx,
        closeTo(160, .1),
      );
      await tester.tap(find.byKey(const ValueKey('comparison-view')));
      await tester.pumpAndSettle();
      final menuItem = find.ancestor(
        of: find.text('左右视图'),
        matching: find.byWidgetPredicate(
          (widget) => widget is CheckedPopupMenuItem,
        ),
      );
      expect(tester.getCenter(menuItem).dx, closeTo(160, .1));
      await tester.tapAt(const Offset(12, 60));
      await tester.pumpAndSettle();
      expect(find.text('项目'), findsNothing);
      await tester.tap(find.byKey(const ValueKey('comparison-history')));
      await tester.pumpAndSettle();
      expect(find.text('对比历史'), findsOneWidget);
      await tester.tap(find.text('我的练舞'));
      await tester.pumpAndSettle();
      for (final expected in [-2000000, 2000000]) {
        await tester.tap(find.byKey(const ValueKey('comparison-project-menu')));
        await tester.pumpAndSettle();
        await tester.tap(find.text('交换参考和目标'));
        await tester.pumpAndSettle();
        expect(gateway.projects.first.offsetUs, expected);
      }
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump();
    },
  );
  test(
    'automatic calibration chooses the best usable result without presenting alternatives',
    () async {
      final gateway = FakeComparisonGateway(saved: [savedProject()])
        ..alignmentResult = MusicAlignmentDto(
          status: 'ambiguous',
          message: 'internal alternatives',
          candidates: [
            MusicCandidateDto(
              offsetUs: 1000000,
              score: .7,
              consistentWindows: 3,
              supportedSeconds: 10,
              residualMs: 0,
            ),
            MusicCandidateDto(
              offsetUs: 3000000,
              score: .9,
              consistentWindows: 3,
              supportedSeconds: 10,
              residualMs: 0,
            ),
          ],
        );
      final controller = ComparisonController(gateway);
      addTearDown(controller.dispose);
      await controller.initialize();
      expect(gateway.alignCalls, 0);
      await controller.matchMusic();
      expect(controller.project.offsetUs, 3000000);
      expect(controller.project.alignment, 'auto_review');
      expect(gateway.projects.first.offsetUs, 3000000);
    },
  );

  test(
    'restored uncalibrated pair calibrates once and existing calibration is retained on reselection',
    () async {
      final gateway = FakeComparisonGateway(
        saved: [
          savedProject().copyWith(
            alignment: 'unmatched',
            offsetUs: 0,
            clearAnchors: true,
          ),
        ],
      );
      final controller = ComparisonController(gateway);
      addTearDown(controller.dispose);
      await controller.initialize();
      expect(gateway.alignCalls, 1);
      gateway.picked = teacher;
      await controller.pick('teacher');
      expect(gateway.alignCalls, 1);
      expect(controller.project.offsetUs, 3000000);
    },
  );

  testWidgets(
    'short-clip stepping and speed selection remain usable in the persistent console',
    (tester) async {
      final gateway = FakeComparisonGateway(saved: [savedProject()]);
      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.darkTheme,
          home: ComparisonScreen(gateway: gateway),
        ),
      );
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('comparison-step-forward')));
      await tester.pump();
      expect(gateway.current.positionUs, 2000000);
      await tester.tap(find.byKey(const ValueKey('comparison-step-back')));
      await tester.pump();
      expect(gateway.current.positionUs, 1000000);
      await tester.tap(find.byKey(const ValueKey('comparison-speed')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('0.5×'));
      await tester.pumpAndSettle();
      expect(gateway.projects.first.speed, .5);
      await tester.tap(find.byKey(const ValueKey('comparison-play')));
      await tester.pump();
      expect(gateway.current.phase, 'playing');
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump();
    },
  );

  testWidgets(
    'large text and landscape keep primary controls visible without overflow',
    (tester) async {
      tester.view.physicalSize = const Size(800, 360);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
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
          home: ComparisonScreen(
            gateway: gateway,
            manualVideoFactory: (uri) => FakeCueVideo(uri, gateway.events),
          ),
        ),
      );
      await tester.pump(const Duration(milliseconds: 400));
      expect(tester.takeException(), isNull);
      expect(
        tester.getCenter(find.byKey(const ValueKey('comparison-view'))).dx,
        closeTo(400, .1),
      );
      await tester.tap(find.byKey(const ValueKey('comparison-view')));
      await tester.pumpAndSettle();
      expect(
        tester
            .getCenter(
              find.ancestor(
                of: find.text('左右视图'),
                matching: find.byWidgetPredicate(
                  (widget) => widget is CheckedPopupMenuItem,
                ),
              ),
            )
            .dx,
        closeTo(400, .1),
      );
      expect(tester.takeException(), isNull);
      await tester.tapAt(const Offset(12, 300));
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.byKey(const ValueKey('comparison-play')));
      expect(
        tester.getRect(find.byKey(const ValueKey('comparison-play'))).right,
        lessThanOrEqualTo(800),
      );
      tester.view.physicalSize = const Size(320, 640);
      await tester.pump(const Duration(milliseconds: 400));
      expect(tester.takeException(), isNull);
      expect(
        tester.getCenter(find.byKey(const ValueKey('comparison-view'))).dx,
        closeTo(160, .1),
      );
      await tester.ensureVisible(
        find.byKey(const ValueKey('comparison-speed')),
      );
      expect(
        tester.getRect(find.byKey(const ValueKey('comparison-speed'))).bottom,
        lessThanOrEqualTo(640),
      );
      await tester.tap(find.byKey(const ValueKey('comparison-calibration')));
      await tester.pumpAndSettle();
      for (final size in [const Size(320, 640), const Size(800, 360)]) {
        tester.view.physicalSize = size;
        await tester.pumpAndSettle();
        for (var role = 0; role < 2; role++) {
          final controls = tester.getRect(
            find.byKey(ValueKey('comparison-manual-controls-$role')),
          );
          final slider = tester.getRect(
            find.byKey(ValueKey('comparison-manual-seek-$role')),
          );
          expect(controls.height, 48);
          expect(slider.height, greaterThanOrEqualTo(48));
          expect(slider.width, greaterThanOrEqualTo(120));
          expect(controls.bottom, lessThanOrEqualTo(size.height));
          for (final label in [
            role == 0 ? '播放参考' : '播放目标',
            role == 0 ? '参考后退0.1秒' : '目标后退0.1秒',
            role == 0 ? '参考前进0.1秒' : '目标前进0.1秒',
          ]) {
            final target = tester.getSize(find.byTooltip(label));
            expect(target.width, greaterThanOrEqualTo(48));
            expect(target.height, greaterThanOrEqualTo(48));
          }
        }
        expect(tester.takeException(), isNull);
      }
      await tester.tap(find.text('取消'));
      await tester.pumpAndSettle();
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump();
    },
  );

  testWidgets(
    'manual mode exposes independent source sliders, hides shared controls and saves both anchors',
    (tester) async {
      final gateway = FakeComparisonGateway(saved: [savedProject()]);
      final previews = <FakeCueVideo>[];
      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.darkTheme,
          home: ComparisonScreen(
            gateway: gateway,
            manualVideoFactory: (uri) {
              final video = FakeCueVideo(
                uri,
                gateway.events,
                onDispose: () => expect(find.byType(VideoPlayer), findsNothing),
              );
              previews.add(video);
              return video;
            },
          ),
        ),
      );
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();
      expect(find.textContaining('候选'), findsNothing);
      await tester.tap(find.byKey(const ValueKey('comparison-calibration')));
      await tester.pumpAndSettle();
      expect(find.textContaining('分别播放或拖动'), findsNothing);
      expect(find.byKey(const ValueKey('comparison-seek')), findsNothing);
      expect(find.byKey(const ValueKey('comparison-play')), findsNothing);
      expect(
        find.byKey(const ValueKey('comparison-manual-seek-0')),
        findsOneWidget,
      );
      expect(
        find.byKey(const ValueKey('comparison-manual-seek-1')),
        findsOneWidget,
      );
      expect(
        gateway.events.indexOf('shared-close'),
        lessThan(gateway.events.indexOf('preview-create')),
      );
      for (final entry in [(0, 1250000.0), (1, 3500000.0)]) {
        final slider = tester.widget<Slider>(
          find.byKey(ValueKey('comparison-manual-seek-${entry.$1}')),
        );
        slider.onChanged!(entry.$2);
        slider.onChangeEnd!(entry.$2);
        await tester.pumpAndSettle();
      }
      expect(previews[0].value.position.inMicroseconds, 1250000);
      expect(previews[1].value.position.inMicroseconds, 3500000);
      await tester.tap(find.byKey(const ValueKey('comparison-manual-play-0')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('comparison-manual-play-1')));
      await tester.pumpAndSettle();
      expect(previews[0].value.isPlaying, false);
      expect(previews[1].value.isPlaying, true);
      await tester.tap(find.byKey(const ValueKey('comparison-manual-apply')));
      await tester.pumpAndSettle();
      expect(gateway.projects.first.offsetUs, 2250000);
      expect(gateway.projects.first.teacherAnchorUs, 1250000);
      expect(gateway.projects.first.learnerAnchorUs, 3500000);
      expect(previews.every((v) => v.closed), true);
      expect(
        gateway.events.lastIndexOf('preview-close'),
        lessThan(gateway.events.lastIndexOf('shared-open')),
      );
      expect(find.byKey(const ValueKey('comparison-seek')), findsOneWidget);
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump();
    },
  );

  testWidgets(
    'failed manual saving keeps editing open and cancel restores the saved calibration',
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
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('comparison-calibration')));
      await tester.pumpAndSettle();
      final slider = tester.widget<Slider>(
        find.byKey(const ValueKey('comparison-manual-seek-1')),
      );
      slider.onChangeEnd!(7000000);
      await tester.pumpAndSettle();
      gateway.failSave = true;
      await tester.tap(find.byKey(const ValueKey('comparison-manual-apply')));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('comparison-seek')), findsNothing);
      expect(find.textContaining('保存失败'), findsOneWidget);
      gateway.failSave = false;
      await tester.tap(find.text('取消'));
      await tester.pumpAndSettle();
      expect(gateway.projects.first.offsetUs, 2000000);
      expect(gateway.current.positionUs, 1000000);
      expect(find.byKey(const ValueKey('comparison-seek')), findsOneWidget);
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump();
    },
  );

  testWidgets(
    'view choices preserve playback and rotation override is released on exit',
    (tester) async {
      tester.view.physicalSize = const Size(360, 800);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final directions = <List<dynamic>>[];
      final restoredAngles = <int>[];
      const angleChannel = MethodChannel('art.gaoge.dance/orientation');
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(angleChannel, (call) async {
            if (call.method == 'getRotation') return 0;
            if (call.method == 'waitForRotation') {
              restoredAngles.add(call.arguments as int);
            }
            return null;
          });
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(SystemChannels.platform, (call) async {
            if (call.method == 'SystemChrome.setPreferredOrientations') {
              directions.add(List<dynamic>.from(call.arguments as List));
            }
            return null;
          });
      addTearDown(() {
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
            .setMockMethodCallHandler(SystemChannels.platform, null);
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
            .setMockMethodCallHandler(angleChannel, null);
      });
      final gateway = FakeComparisonGateway(saved: [savedProject()]);
      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.darkTheme,
          home: ComparisonScreen(gateway: gateway),
        ),
      );
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('comparison-play')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('comparison-view')));
      await tester.pumpAndSettle();
      await tester.tap(
        find.ancestor(
          of: find.text('左右视图'),
          matching: find.byWidgetPredicate(
            (widget) => widget is CheckedPopupMenuItem,
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(gateway.layouts.last, true);
      expect(gateway.projects.first.sideBySide, true);
      expect(gateway.current.phase, 'playing');
      expect(gateway.current.positionUs, 1000000);
      expect(gateway.projects.first.offsetUs, 2000000);
      final teacherRect = tester.getRect(
        find.byKey(const ValueKey('comparison-pick-teacher')),
      );
      final learnerRect = tester.getRect(
        find.byKey(const ValueKey('comparison-pick-learner')),
      );
      expect(learnerRect.left, greaterThanOrEqualTo(teacherRect.right));
      expect(learnerRect.top, teacherRect.top);
      await tester.tap(find.byKey(const ValueKey('comparison-view')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('横屏显示'));
      await tester.pumpAndSettle();
      expect(directions.last, ['DeviceOrientation.landscapeLeft']);
      tester.view.physicalSize = const Size(800, 360);
      await tester.pumpAndSettle();
      expect(gateway.layouts.last, true);
      await tester.tap(find.byKey(const ValueKey('comparison-view')));
      await tester.pumpAndSettle();
      await tester.tap(
        find.ancestor(
          of: find.text('上下视图'),
          matching: find.byWidgetPredicate(
            (widget) => widget is CheckedPopupMenuItem,
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(gateway.layouts.last, false);
      expect(gateway.current.phase, 'playing');
      await tester.tap(find.byKey(const ValueKey('comparison-view')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('竖屏显示'));
      await tester.pumpAndSettle();
      expect(directions.last, ['DeviceOrientation.portraitUp']);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump();
      expect(directions[directions.length - 2], [
        'DeviceOrientation.portraitUp',
      ]);
      expect(directions.last, isEmpty);
      expect(restoredAngles, [0]);
      final restored = ComparisonController(gateway);
      await restored.initialize();
      expect(restored.project.sideBySide, false);
      expect(restored.project.offsetUs, 2000000);
      restored.dispose();
    },
  );

  testWidgets(
    'portrait side-by-side manual calibration keeps sliders usable and cancel only discards cue changes',
    (tester) async {
      tester.view.physicalSize = const Size(320, 640);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final gateway = FakeComparisonGateway(
        saved: [savedProject().copyWith(sideBySide: true)],
      );
      final previews = <FakeCueVideo>[];
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
              previews.add(video);
              return video;
            },
          ),
        ),
      );
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('comparison-calibration')));
      await tester.pumpAndSettle();
      for (var role = 0; role < 2; role++) {
        final slider = find.byKey(ValueKey('comparison-manual-seek-$role'));
        expect(tester.getSize(slider).width, greaterThanOrEqualTo(96));
        expect(tester.getSize(slider).height, 48);
        expect(
          tester
              .getSize(find.byKey(ValueKey('comparison-manual-controls-$role')))
              .height,
          96,
        );
        tester.widget<Slider>(slider).onChangeEnd!((role + 1) * 2000000.0);
      }
      await tester.pumpAndSettle();
      expect(previews[0].value.position.inMicroseconds, 2000000);
      expect(previews[1].value.position.inMicroseconds, 4000000);
      await tester.tap(find.byKey(const ValueKey('comparison-view')));
      await tester.pumpAndSettle();
      await tester.tap(
        find.ancestor(
          of: find.text('上下视图'),
          matching: find.byWidgetPredicate(
            (widget) => widget is CheckedPopupMenuItem,
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(previews[0].value.position.inMicroseconds, 2000000);
      expect(previews[1].value.position.inMicroseconds, 4000000);
      await tester.tap(find.text('取消'));
      await tester.pumpAndSettle();
      expect(gateway.projects.first.teacherAnchorUs, 0);
      expect(gateway.projects.first.learnerAnchorUs, 2000000);
      expect(gateway.projects.first.sideBySide, false);
      expect(gateway.projects.first.offsetUs, 2000000);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump();
    },
  );

  testWidgets(
    'empty reference and target slots select their own source and start automatic calibration',
    (tester) async {
      tester.view.physicalSize = const Size(320, 640);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final gateway = FakeComparisonGateway();
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
      expect(find.text('舞蹈对比'), findsNothing);
      expect(find.text('选择参考视频'), findsOneWidget);
      expect(find.text('选择目标视频'), findsOneWidget);
      gateway.picked = teacher;
      await tester.tap(find.byKey(const ValueKey('comparison-pick-teacher')));
      await tester.pumpAndSettle();
      expect(find.text('参考视频已选择'), findsOneWidget);
      expect(find.text('选择目标视频'), findsOneWidget);
      expect(gateway.alignCalls, 0);
      gateway.picked = learner;
      await tester.tap(find.byKey(const ValueKey('comparison-pick-learner')));
      await tester.pumpAndSettle();
      expect(gateway.pickedRoles, ['teacher', 'learner']);
      expect(gateway.alignCalls, 1);
      expect(gateway.projects.first.offsetUs, 3000000);
      expect(gateway.projects.first.alignment, 'auto');
      expect(find.text('参考'), findsOneWidget);
      expect(find.text('目标'), findsOneWidget);
      expect(find.byIcon(Icons.expand_more), findsNothing);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump();
    },
  );
}
