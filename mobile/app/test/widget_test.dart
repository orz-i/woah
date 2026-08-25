import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:app/app/app.dart';

void main() {
  testWidgets('App smoke test initializes correctly', (WidgetTester tester) async {
    await tester.pumpWidget(
      const ProviderScope(
        child: DanceAnonymizerApp(),
      ),
    );

    expect(find.text('Dance Anonymizer'), findsOneWidget);
    expect(find.text('原生引擎状态 (Native Engine)'), findsOneWidget);
  });
}
