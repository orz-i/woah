import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:app/app/app.dart';

void main() {
  testWidgets('App smoke test initializes correctly', (
    WidgetTester tester,
  ) async {
    await tester.pumpWidget(const ProviderScope(child: DanceAnonymizerApp()));

    expect(find.text('Woah'), findsOneWidget);
    expect(find.text('隐私保护 · 本机处理'), findsOneWidget);
    expect(find.text('视频仅在本机处理'), findsOneWidget);
    expect(find.text('不会上传任何内容'), findsOneWidget);
    expect(find.text('选择视频'), findsOneWidget);
  });
}
