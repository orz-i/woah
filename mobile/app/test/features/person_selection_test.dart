import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:dance_domain/dance_domain.dart';
import 'package:app/features/person_selection/presentation/widgets/person_card.dart';

void main() {
  group('PersonCard Widget Tests', () {
    testWidgets('Displays person id, confidence, and responds to click', (tester) async {
      bool clicked = false;
      const person = PersonTrack(
        id: 0,
        normalizedInitialBox: NormalizedRect(left: 0.1, top: 0.1, right: 0.4, bottom: 0.9),
        thumbnailPath: '',
        confidence: 0.942,
        selected: true,
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: PersonCard(
              person: person,
              isSelected: true,
              onToggle: () {
                clicked = true;
              },
            ),
          ),
        ),
      );

      expect(find.text('人物 0'), findsOneWidget);
      expect(find.text('置信度: 94%'), findsOneWidget);
      expect(find.text('已选中（将应用特效）'), findsOneWidget);
      expect(find.byIcon(Icons.check), findsOneWidget);

      await tester.tap(find.byType(PersonCard));
      await tester.pump();

      expect(clicked, isTrue);
    });

    testWidgets('Displays unselected state with circle outline', (tester) async {
      const person = PersonTrack(
        id: 2,
        normalizedInitialBox: NormalizedRect(left: 0.5, top: 0.1, right: 0.8, bottom: 0.9),
        thumbnailPath: '',
        confidence: 0.887,
        selected: false,
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: PersonCard(
              person: person,
              isSelected: false,
              onToggle: () {},
            ),
          ),
        ),
      );

      expect(find.text('人物 2'), findsOneWidget);
      expect(find.text('置信度: 88%'), findsOneWidget);
      expect(find.text('未选中（直通原画）'), findsOneWidget);
      expect(find.byIcon(Icons.circle_outlined), findsOneWidget);
    });
  });
}
