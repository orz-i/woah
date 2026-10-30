import 'package:app/app/theme.dart';
import 'package:app/features/import_video/presentation/import_video_screen.dart';
import 'package:app/features/import_video/presentation/woah_easter_egg_screen.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

/// The test uses MaterialApp's actual platform-brightness signal, not a
/// separate app setting. Routing/content must remain mounted on OS changes.
void main() {
  testWidgets('system appearance switches Home live without losing the workflow', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(360, 640);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    tester.platformDispatcher.platformBrightnessTestValue = Brightness.dark;
    addTearDown(tester.platformDispatcher.clearPlatformBrightnessTestValue);

    await tester.pumpWidget(
      ProviderScope(
        child: MaterialApp(
          theme: AppTheme.lightTheme,
          darkTheme: AppTheme.darkTheme,
          themeMode: ThemeMode.system,
          home: const ImportVideoScreen(),
        ),
      ),
    );
    await tester.pumpAndSettle();
    Scaffold scaffold() => tester.widget<Scaffold>(find.byType(Scaffold).first);
    expect(scaffold().backgroundColor, AppTheme.darkColors.flowBackground);
    expect(find.text('Woah'), findsOneWidget);
    expect(find.byKey(const ValueKey('home-import-card')), findsOneWidget);
    final darkStyle = tester.widget<AnnotatedRegion<SystemUiOverlayStyle>>(
      find.byType(AnnotatedRegion<SystemUiOverlayStyle>).first,
    );
    expect(darkStyle.value.systemNavigationBarIconBrightness, Brightness.light);

    // The system changes while Home is visible: no app restart or manual tap.
    tester.platformDispatcher.platformBrightnessTestValue = Brightness.light;
    await tester.pumpAndSettle();
    expect(scaffold().backgroundColor, AppTheme.lightColors.flowBackground);
    expect(find.text('Woah'), findsOneWidget);
    expect(find.byKey(const ValueKey('home-import-card')), findsOneWidget);
    final lightStyle = tester.widget<AnnotatedRegion<SystemUiOverlayStyle>>(
      find.byType(AnnotatedRegion<SystemUiOverlayStyle>).first,
    );
    expect(lightStyle.value.systemNavigationBarIconBrightness, Brightness.dark);
    final cardMaterial = tester.widget<Material>(
      find
          .descendant(
            of: find.byKey(const ValueKey('home-card-elevation')),
            matching: find.byType(Material),
          )
          .first,
    );
    expect(cardMaterial.color, AppTheme.lightColors.flowSurface);
    expect(find.byKey(const ValueKey('home-privacy-cover')), findsOneWidget);

    // Both independent workflows remain discoverable without horizontal paging.
    expect(find.byType(PageView), findsNothing);
    expect(find.byKey(const ValueKey('home-comparison-card')), findsOneWidget);
    await tester.ensureVisible(
      find.byKey(const ValueKey('home-comparison-card')),
    );
    await tester.pumpAndSettle();
    expect(
      find.byKey(const ValueKey('home-comparison-card')).hitTestable(),
      findsOneWidget,
    );

    // A wider viewport must preserve the secondary entry and leave it tappable.
    tester.view.physicalSize = const Size(390, 844);
    await tester.pumpAndSettle();
    expect(
      find.byKey(const ValueKey('home-comparison-card')).hitTestable(),
      findsOneWidget,
    );

    // Reversing the system mode restores the original dark baseline.
    tester.platformDispatcher.platformBrightnessTestValue = Brightness.dark;
    await tester.pumpAndSettle();
    expect(scaffold().backgroundColor, AppTheme.darkColors.flowBackground);
    expect(
      find.byKey(const ValueKey('home-comparison-card')).hitTestable(),
      findsOneWidget,
    );
  });

  testWidgets('hidden Wavi stage stays cinematic in both system modes', (
    tester,
  ) async {
    tester.platformDispatcher.platformBrightnessTestValue = Brightness.light;
    addTearDown(tester.platformDispatcher.clearPlatformBrightnessTestValue);
    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.lightTheme,
        darkTheme: AppTheme.darkTheme,
        themeMode: ThemeMode.system,
        home: const WoahEasterEggScreen(),
      ),
    );
    await tester.pump();
    expect(
      tester.widget<Scaffold>(find.byType(Scaffold)).backgroundColor,
      AppTheme.brandInk,
    );
    tester.platformDispatcher.platformBrightnessTestValue = Brightness.dark;
    await tester.pump();
    expect(
      tester.widget<Scaffold>(find.byType(Scaffold)).backgroundColor,
      AppTheme.brandInk,
    );
    expect(find.text('嘿，你找到这里了。'), findsOneWidget);
  });
}
