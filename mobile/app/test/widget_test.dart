import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter/material.dart';
import 'package:app/app/app.dart';

void main() {
  testWidgets('App smoke test initializes correctly', (
    WidgetTester tester,
  ) async {
    await tester.pumpWidget(const ProviderScope(child: DanceAnonymizerApp()));

    expect(find.text('Woah'), findsOneWidget);
    expect(find.text('记录每一个舞动瞬间'), findsOneWidget);
    expect(find.text('导入舞段'), findsOneWidget);
    expect(find.byIcon(Icons.close_rounded), findsOneWidget);
    expect(find.text('隐私保护 · 本机处理'), findsNothing);
    expect(find.text('选择视频'), findsNothing);
  });
}
