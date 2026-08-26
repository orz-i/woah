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
    expect(find.text('选择舞蹈视频'), findsOneWidget);
    expect(find.text('支持常见视频格式，自动校准画面方向'), findsOneWidget);
  });
}
