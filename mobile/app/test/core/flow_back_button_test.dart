import 'package:app/core/widgets/flow_back_button.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets('FlowBackButton renders properly and fires callback on tap', (
    tester,
  ) async {
    var pressed = false;

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: FlowBackButton(
            onPressed: () {
              pressed = true;
            },
          ),
        ),
      ),
    );

    expect(find.byKey(FlowBackButton.backButtonKey), findsOneWidget);
    expect(find.byIcon(Icons.arrow_back_ios_new_rounded), findsOneWidget);
    expect(find.byTooltip('返回上一步'), findsOneWidget);
    expect(find.bySemanticsLabel('返回上一步'), findsOneWidget);

    await tester.tap(find.byKey(FlowBackButton.backButtonKey));
    await tester.pump();

    expect(pressed, isTrue);
  });
}
