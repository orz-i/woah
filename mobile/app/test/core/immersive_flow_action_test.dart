import 'package:app/core/widgets/immersive_flow_action.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets('tap advances without showing the return target', (tester) async {
    var nextCount = 0;
    var returnCount = 0;

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: Align(
            alignment: Alignment.bottomCenter,
            child: ImmersiveFlowAction(
              enabled: true,
              onNext: () => nextCount++,
              onReturn: () => returnCount++,
            ),
          ),
        ),
      ),
    );

    await tester.tap(find.byKey(ImmersiveFlowAction.nextControlKey));
    await tester.pump();

    expect(nextCount, 1);
    expect(returnCount, 0);
    expect(find.byKey(ImmersiveFlowAction.exitTargetKey), findsNothing);
  });

  testWidgets('hold then drag upward reveals and activates return target', (
    tester,
  ) async {
    var nextCount = 0;
    var returnCount = 0;

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: Align(
            alignment: Alignment.bottomCenter,
            child: ImmersiveFlowAction(
              enabled: true,
              onNext: () => nextCount++,
              onReturn: () => returnCount++,
            ),
          ),
        ),
      ),
    );

    final nextFinder = find.byKey(ImmersiveFlowAction.nextControlKey);
    final gesture = await tester.startGesture(tester.getCenter(nextFinder));
    await tester.pump(const Duration(milliseconds: 650));
    expect(find.byKey(ImmersiveFlowAction.exitTargetKey), findsNothing);

    await gesture.moveBy(const Offset(0, -108));
    await tester.pump();
    expect(find.byKey(ImmersiveFlowAction.exitTargetKey), findsOneWidget);
    expect(find.bySemanticsLabel('松开返回'), findsOneWidget);

    await gesture.up();
    await tester.pumpAndSettle();
    expect(returnCount, 1);
    expect(nextCount, 0);
  });

  testWidgets('disabled next still allows drag-to-return', (tester) async {
    var nextCount = 0;
    var returnCount = 0;

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: Align(
            alignment: Alignment.bottomCenter,
            child: ImmersiveFlowAction(
              enabled: false,
              onNext: () => nextCount++,
              onReturn: () => returnCount++,
            ),
          ),
        ),
      ),
    );

    final nextFinder = find.byKey(ImmersiveFlowAction.nextControlKey);
    await tester.tap(nextFinder);
    expect(nextCount, 0);

    final gesture = await tester.startGesture(tester.getCenter(nextFinder));
    await tester.pump(const Duration(milliseconds: 650));
    await gesture.moveBy(const Offset(0, -108));
    await tester.pump();
    expect(find.bySemanticsLabel('松开返回'), findsOneWidget);
    await gesture.up();
    await tester.pumpAndSettle();

    expect(nextCount, 0);
    expect(returnCount, 1);
    expect(find.byKey(ImmersiveFlowAction.exitTargetKey), findsNothing);
  });
}
