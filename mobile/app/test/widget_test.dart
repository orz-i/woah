import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter/material.dart';
import 'package:app/app/app.dart';

void main() {
  testWidgets('App smoke test initializes correctly', (
    WidgetTester tester,
  ) async {
    await tester.pumpWidget(const ProviderScope(child: WoahApp()));

    expect(find.text('Woah'), findsOneWidget);
    expect(find.text('记录每一个闪闪发光的你'), findsNothing);
    expect(find.text('视频保护'), findsOneWidget);
    expect(find.text('保留主角，遮挡其他人物'), findsOneWidget);
    expect(find.byKey(const ValueKey('home-import-card')), findsOneWidget);
    expect(find.byIcon(Icons.close_rounded), findsNothing);
    expect(find.text('隐私保护 · 本机处理'), findsNothing);
  });
}
