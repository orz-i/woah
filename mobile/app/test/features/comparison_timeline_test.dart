import 'dart:async';
import 'dart:ui' show SemanticsAction, SemanticsActionEvent;
import 'package:app/app/theme.dart';
import 'package:app/features/comparison/presentation/comparison_controller.dart';
import 'package:app/features/comparison/presentation/comparison_timeline.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'comparison_test.dart' show FakeComparisonGateway, savedProject;

class _TimelineHost extends StatefulWidget {
  const _TimelineHost({
    super.key,
    this.start,
    this.end,
    this.duration = 12000000,
  });
  final int? start, end;
  final int duration;
  @override
  State<_TimelineHost> createState() => _HostState();
}

class _HostState extends State<_TimelineHost> {
  late int? start = widget.start, end = widget.end;
  final ranges = <(int, int)>[], seeks = <int>[], previews = <int?>[];
  int pauses = 0;
  @override
  Widget build(BuildContext context) => MaterialApp(
    theme: AppTheme.darkTheme,
    home: Scaffold(
      body: Center(
        child: SizedBox(
          width: 320,
          child: ComparisonTimeline(
            durationUs: widget.duration,
            positionUs: 1000000,
            startUs: start,
            endUs: end,
            onScrub: previews.add,
            onSeek: seeks.add,
            onRangeStart: () async {
              pauses++;
            },
            onRange: (a, b) async {
              ranges.add((a, b));
              setState(() {
                start = a;
                end = b;
              });
              return true;
            },
          ),
        ),
      ),
    ),
  );
}

Future<_HostState> _host(
  WidgetTester tester, {
  int? start,
  int? end,
  int duration = 12000000,
}) async {
  final key = GlobalKey<_HostState>();
  await tester.pumpWidget(
    _TimelineHost(key: key, start: start, end: end, duration: duration),
  );
  await tester.pumpAndSettle();
  return key.currentState!;
}

Future<void> close(WidgetTester tester) async {
  await tester.pumpWidget(const SizedBox.shrink());
  await tester.pumpAndSettle();
}

void main() {
  testWidgets(
    'one contextual clock shows playback normally and only the edited endpoint while dragging',
    (tester) async {
      await _host(tester);
      final readout = find.byKey(const ValueKey('comparison-time-readout'));
      expect(tester.widget<Text>(readout).data, '00:01.000');
      expect(find.text('00:12.000'), findsNothing);
      expect(
        find.byWidgetPredicate(
          (widget) =>
              widget is Text &&
              widget.data != null &&
              RegExp(r'^\d+:\d{2}\.\d{3}$').hasMatch(widget.data!),
        ),
        findsOneWidget,
      );
      final drag = await tester.startGesture(
        tester.getCenter(find.byKey(const ValueKey('comparison-loop-A'))),
      );
      await drag.moveBy(const Offset(30, 0));
      await tester.pump();
      await drag.moveBy(const Offset(20, 0));
      await tester.pump();
      expect(tester.widget<Text>(readout).data, startsWith('A '));
      await drag.cancel();
      await tester.pumpAndSettle();
      expect(tester.widget<Text>(readout).data, '00:01.000');
      await close(tester);
    },
  );
  testWidgets(
    'dragging the shared rail away from trim grips scrubs without editing A/B',
    (tester) async {
      final state = await _host(tester, start: 1000000, end: 3000000);
      final rail = tester.getRect(
        find.byKey(const ValueKey('comparison-seek')),
      );
      final drag = await tester.startGesture(
        Offset(rail.left + 24 + (rail.width - 48) * .6, rail.center.dy),
      );
      await drag.moveBy(const Offset(20, 0));
      await tester.pump();
      await drag.moveBy(const Offset(20, 0));
      await tester.pump();
      expect(state.seeks, isEmpty);
      expect(state.ranges, isEmpty);
      await drag.up();
      await tester.pumpAndSettle();
      expect(state.seeks.single, greaterThan(7200000));
      expect((state.start, state.end), (1000000, 3000000));
      expect(state.pauses, 0);
      await close(tester);
    },
  );
  testWidgets(
    'dragging each handle commits both bounds only after release; tap no longer sets a point',
    (tester) async {
      final state = await _host(tester);
      final a = find.byKey(const ValueKey('comparison-loop-A'));
      final b = find.byKey(const ValueKey('comparison-loop-B'));
      await tester.tap(a);
      await tester.pumpAndSettle();
      expect(state.ranges, isEmpty);
      expect(state.seeks, [0]);
      state.seeks.clear();
      final rail = tester.getRect(
        find.byKey(const ValueKey('comparison-seek')),
      );
      for (final letter in ['A', 'B']) {
        expect(
          tester
              .getCenter(find.byKey(ValueKey('comparison-loop-grip-$letter')))
              .dy,
          rail.center.dy,
        );
      }
      final drag = await tester.startGesture(tester.getCenter(a));
      await drag.moveBy(const Offset(30, 0));
      await tester.pump();
      await drag.moveBy(const Offset(40, 0));
      await tester.pump();
      expect(state.ranges, isEmpty);
      expect(state.pauses, 1);
      await drag.up();
      await tester.pumpAndSettle();
      expect(state.ranges.length, 1);
      expect(state.start, greaterThan(0));
      expect(state.end, 12000000);
      final first = state.start!;
      await tester.drag(b, const Offset(-90, 0));
      await tester.pumpAndSettle();
      expect(state.ranges.length, 2);
      expect(state.start, first);
      expect(state.end, lessThan(12000000));
      expect(state.end, greaterThan(first));
      expect(state.seeks, isEmpty);
      expect(tester.takeException(), isNull);
      await close(tester);
    },
  );

  testWidgets(
    'seeking the shared track leaves A/B untouched; cancelled selection leaves the saved bounds',
    (tester) async {
      final state = await _host(tester, start: 2000000, end: 6000000);
      final track = tester.getRect(
        find.byKey(const ValueKey('comparison-seek')),
      );
      await tester.tapAt(
        Offset(track.left + 24 + (track.width - 48) * .75, track.top + 24),
      );
      await tester.pumpAndSettle();
      expect(state.seeks.single, closeTo(9000000, 1000));
      expect(state.previews.last, isNull);
      expect(state.ranges, isEmpty);
      final drag = await tester.startGesture(
        tester.getCenter(find.byKey(const ValueKey('comparison-loop-A'))),
      );
      await drag.moveBy(const Offset(30, 0));
      await tester.pump();
      await drag.moveBy(const Offset(40, 0));
      await tester.pump();
      await drag.cancel();
      await tester.pumpAndSettle();
      expect((state.start, state.end), (2000000, 6000000));
      expect(state.ranges, isEmpty);
      await close(tester);
    },
  );

  testWidgets(
    'very close endpoints remain separately draggable and never reverse or leave the duration',
    (tester) async {
      final state = await _host(tester, start: 6000000, end: 6000001);
      final a = find.byKey(const ValueKey('comparison-loop-A'));
      final b = find.byKey(const ValueKey('comparison-loop-B'));
      expect(
        tester.getCenter(b).dx - tester.getCenter(a).dx,
        greaterThanOrEqualTo(32),
      );
      await tester.drag(a, const Offset(-2000, 0));
      await tester.pumpAndSettle();
      expect(state.start, 0);
      expect(state.end, 6000001);
      await tester.drag(b, const Offset(-2000, 0));
      await tester.pumpAndSettle();
      expect(state.end, 100000);
      expect(state.start, 0);
      await tester.drag(b, const Offset(2000, 0));
      await tester.pumpAndSettle();
      expect(state.end, 12000000);
      expect(tester.takeException(), isNull);
      await close(tester);
    },
  );

  testWidgets(
    'accessible A/B sliders adjust by 100ms and empty media stays disabled',
    (tester) async {
      final semantics = tester.ensureSemantics();
      final state = await _host(tester, start: 1000007, end: 3000009);
      final node = tester.getSemantics(
        find.byKey(const ValueKey('comparison-loop-A')),
      );
      expect(node.label, 'A 起点');
      expect(node.value, '00:01.000007');
      tester.binding.platformDispatcher.onSemanticsActionEvent!(
        SemanticsActionEvent(
          type: SemanticsAction.increase,
          viewId: tester.view.viewId,
          nodeId: node.id,
        ),
      );
      await tester.pumpAndSettle();
      expect((state.start, state.end), (1100007, 3000009));
      await close(tester);
      final empty = await _host(tester, duration: 0);
      await tester.drag(
        find.byKey(const ValueKey('comparison-loop-B')),
        const Offset(60, 0),
      );
      await tester.pumpAndSettle();
      expect(empty.ranges, isEmpty);
      expect(empty.pauses, 0);
      expect(tester.takeException(), isNull);
      await close(tester);
      semantics.dispose();
    },
  );

  test(
    'range commits pause, save both endpoints, preserve other practice fields, and guard duplicates',
    () async {
      final original = savedProject().copyWith(mirrorLearner: true, speed: .5);
      final gateway = FakeComparisonGateway(saved: [original]);
      final controller = ComparisonController(gateway);
      addTearDown(controller.dispose);
      controller.setViewport(320, 480, false);
      await controller.initialize();
      gateway.firstSaveGate = Completer<void>();
      final pending = controller.setLoopRange(2000007, 4000009);
      await Future<void>.delayed(Duration.zero);
      expect(controller.busy, true);
      expect(await controller.setLoopRange(1000000, 5000000), false);
      gateway.firstSaveGate!.complete();
      expect(await pending, true);
      expect(controller.busy, false);
      final saved = gateway.projects.first;
      expect(
        (saved.loopStartUs, saved.loopEndUs, saved.loopEnabled),
        (2000007, 4000009, true),
      );
      expect(saved.positionUs, 2000007);
      expect(saved.mirrorLearner, true);
      expect(saved.speed, .5);
      expect(saved.offsetUs, original.offsetUs);
      expect(gateway.current.phase, 'paused');
      expect(await controller.setLoopRange(-1, 4000000), false);
      expect(await controller.setLoopRange(1, 12000001), false);
      expect(controller.project.loopStartUs, 2000007);
    },
  );

  test(
    'failed range saving is reported and a subsequent adjustment can retry',
    () async {
      final gateway = FakeComparisonGateway(saved: [savedProject()]);
      final controller = ComparisonController(gateway);
      addTearDown(controller.dispose);
      controller.setViewport(320, 480, false);
      await controller.initialize();
      gateway.failSave = true;
      expect(await controller.setLoopRange(1000000, 3000000), false);
      expect(controller.error, isNotNull);
      expect(controller.busy, false);
      expect(gateway.projects.first.loopStartUs, isNull);
      gateway.failSave = false;
      expect(await controller.setLoopRange(1000000, 3000000), true);
      expect(controller.error, isNull);
      expect(gateway.projects.first.loopEnabled, true);
    },
  );
}
