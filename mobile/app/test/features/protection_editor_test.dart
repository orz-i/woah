import 'dart:async';
import 'dart:ui' show SemanticsAction, SemanticsActionEvent, Tristate;

import 'package:app/app/theme.dart';
import 'package:app/features/effect_editor/presentation/effect_editor_controller.dart';
import 'package:app/features/export/presentation/export_screen.dart';
import 'package:app/features/person_selection/domain/person_selection_state.dart';
import 'package:app/features/person_selection/presentation/person_selection_controller.dart';
import 'package:app/features/protection_editor/data/protection_profile_store.dart';
import 'package:app/features/protection_editor/domain/protection_profile.dart';
import 'package:app/features/protection_editor/presentation/protection_editor_screen.dart';
import 'package:app/repositories/native_processing_repository.dart';
import 'package:dance_domain/dance_domain.dart';
import 'package:dance_native/dance_native.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';

Future<void> _selectTool(WidgetTester tester, String tool) async {
  if (find.byKey(ValueKey('editor-tool-content-$tool')).evaluate().isNotEmpty) {
    return;
  }
  final button = find.byKey(ValueKey('editor-tool-$tool'));
  expect(button, findsOneWidget);
  await tester.tap(button);
  await tester.pumpAndSettle();
}

void main() {
  setUp(() {
    SharedPreferences.setMockInitialValues(<String, Object>{});
  });

  for (final brightness in Brightness.values) {
    testWidgets(
      'preview error stays readable in ${brightness.name} appearance',
      (tester) async {
        final repository = _FakeProtectionRepository()..failPreview = true;
        final container = ProviderContainer(
          overrides: [nativeRepositoryProvider.overrideWithValue(repository)],
        );
        addTearDown(container.dispose);
        final now = DateTime.now();
        await tester.pumpWidget(
          UncontrolledProviderScope(
            container: container,
            child: MaterialApp(
              theme: brightness == Brightness.dark
                  ? AppTheme.darkTheme
                  : AppTheme.lightTheme,
              home: ProtectionEditorScreen(
                project: DanceProject(
                  id: 'preview-error-contrast',
                  sourceUri: 'file:///preview-error.mp4',
                  videoInfo: const VideoInfo(
                    codedWidth: 720,
                    codedHeight: 1280,
                    displayWidth: 720,
                    displayHeight: 1280,
                    fps: 30,
                    durationMs: 4000,
                    rotation: 0,
                    videoCodec: 'h264',
                    hasAudio: false,
                  ),
                  trimStartMs: 400,
                  trimEndMs: 3200,
                  createdAt: now,
                  updatedAt: now,
                ),
              ),
            ),
          ),
        );
        await tester.pumpAndSettle();
        final message = find.text('预览暂时无法更新，选择与参数仍会保留。');
        expect(message, findsOneWidget);
        final text = tester.widget<Text>(message);
        final panel = tester.widget<Container>(
          find.ancestor(of: message, matching: find.byType(Container)).first,
        );
        final color = (panel.decoration! as BoxDecoration).color!;
        final foreground = text.style!.color!;
        for (final videoColor in [Colors.black, Colors.white]) {
          final background = Color.alphaBlend(color, videoColor);
          final a = Color.alphaBlend(foreground, background).computeLuminance();
          final b = background.computeLuminance();
          final contrast = a > b
              ? (a + .05) / (b + .05)
              : (b + .05) / (a + .05);
          expect(contrast, greaterThanOrEqualTo(4.5));
        }
        expect(repository.lastSelectedPersonIds, isNotEmpty);
        expect(tester.takeException(), isNull);
      },
    );
  }

  final now = DateTime.utc(2026, 9, 14);
  final project = DanceProject(
    id: 'unified-protection-test',
    sourceUri: 'file:///unified-protection.mp4',
    videoInfo: const VideoInfo(
      codedWidth: 720,
      codedHeight: 1280,
      displayWidth: 720,
      displayHeight: 1280,
      fps: 30,
      durationMs: 4000,
      rotation: 0,
      videoCodec: 'h264',
      hasAudio: false,
    ),
    trimStartMs: 400,
    trimEndMs: 3200,
    createdAt: now,
    updatedAt: now,
  );

  testWidgets('tools start closed and can be deselected without losing edits', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(360, 640);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final container = ProviderContainer(
      overrides: [
        nativeRepositoryProvider.overrideWithValue(_FakeProtectionRepository()),
      ],
    );
    addTearDown(container.dispose);
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: MaterialApp(home: ProtectionEditorScreen(project: project)),
      ),
    );
    await tester.pumpAndSettle();
    const tools = ['trim', 'mask', 'frame', 'adjust'];
    void expectSelected(String? selectedTool) {
      for (final tool in tools) {
        final data = tester
            .getSemantics(find.byKey(ValueKey('editor-tool-$tool')))
            .getSemanticsData();
        expect(
          data.flagsCollection.isSelected,
          tool == selectedTool ? Tristate.isTrue : Tristate.isFalse,
        );
        expect(
          find.byKey(ValueKey('editor-tool-content-$tool')),
          tool == selectedTool ? findsOneWidget : findsNothing,
        );
      }
    }

    expectSelected(null);
    final closedHeight = tester
        .getSize(find.byKey(const ValueKey('protection-editor-media-stage')))
        .height;
    await _selectTool(tester, 'mask');
    expectSelected('mask');
    expect(
      tester
          .getSize(find.byKey(const ValueKey('protection-editor-media-stage')))
          .height,
      lessThan(closedHeight),
    );
    await tester.tap(find.byKey(const ValueKey('protection-style-blur')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('editor-tool-mask')));
    await tester.pumpAndSettle();
    expectSelected(null);
    expect(
      tester
          .getSize(find.byKey(const ValueKey('protection-editor-media-stage')))
          .height,
      closedHeight,
    );
    expect(
      container.read(effectEditorControllerProvider).effects.fillMode,
      FillMode.blur,
    );
    for (final tool in tools) {
      await _selectTool(tester, tool);
      expectSelected(tool);
      await tester.tap(find.byKey(ValueKey('editor-tool-$tool')));
      await tester.pumpAndSettle();
      expectSelected(null);
    }
    await _selectTool(tester, 'trim');
    await tester.tap(find.byTooltip('播放预览'));
    await tester.pump(const Duration(milliseconds: 100));
    await tester.tap(find.byKey(const ValueKey('editor-tool-trim')));
    await tester.pump(const Duration(milliseconds: 100));
    expectSelected(null);
    await tester.tap(find.byKey(const ValueKey('editor-tool-trim')));
    await tester.pump(const Duration(milliseconds: 100));
    expect(find.byTooltip('播放预览'), findsOneWidget);
    expect(find.byTooltip('暂停预览'), findsNothing);
    await tester.tap(find.byKey(const ValueKey('editor-tool-trim')));
    await tester.pumpAndSettle();
    expect(
      container.read(effectEditorControllerProvider).effects.fillMode,
      FillMode.blur,
    );
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'empty recognition offers local recovery and retry opens editor',
    (tester) async {
      final repository = _FakeProtectionRepository()..personIds = const [];
      final container = ProviderContainer(
        overrides: [nativeRepositoryProvider.overrideWithValue(repository)],
      );
      addTearDown(container.dispose);
      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: MaterialApp(home: ProtectionEditorScreen(project: project)),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('没有找到可保护的人物'), findsOneWidget);
      expect(find.widgetWithText(FilledButton, '重新选择视频'), findsOneWidget);
      expect(find.widgetWithText(TextButton, '重新识别'), findsOneWidget);
      repository.personIds = const [0, 1];
      await tester.tap(find.text('重新识别'));
      await tester.pumpAndSettle();
      expect(repository.analyzeRequestCount, 2);
      expect(
        find.byKey(const ValueKey('protection-editor-media-stage')),
        findsOneWidget,
      );
      expect(
        container.read(personSelectionControllerProvider).privacyTargetIds,
        {0, 1},
      );
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'trim distinguishes output bounds and supports precise end adjustment',
    (tester) async {
      tester.view.physicalSize = const Size(360, 640);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final repository = _FakeProtectionRepository();
      final container = ProviderContainer(
        overrides: [nativeRepositoryProvider.overrideWithValue(repository)],
      );
      addTearDown(container.dispose);
      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: MaterialApp(home: ProtectionEditorScreen(project: project)),
        ),
      );
      await tester.pumpAndSettle();
      await _selectTool(tester, 'trim');
      expect(find.text('起点 00:00.4'), findsOneWidget);
      expect(find.text('终点 00:03.2'), findsOneWidget);
      expect(find.text('保留 2.8 秒'), findsOneWidget);
      for (final boundary in ['start', 'end']) {
        final size = tester.getSize(
          find.byKey(ValueKey('trim-$boundary-control')),
        );
        expect(size.width, greaterThanOrEqualTo(48));
        expect(size.height, greaterThanOrEqualTo(48));
      }
      final end = tester.getSemantics(
        find.byKey(const ValueKey('trim-end-control')),
      );
      tester.binding.performSemanticsAction(
        SemanticsActionEvent(
          nodeId: end.id,
          type: SemanticsAction.decrease,
          viewId: tester.view.viewId,
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('终点 00:03.1'), findsOneWidget);
      expect(find.text('保留 2.7 秒'), findsOneWidget);
      expect(repository.analyzeRequestCount, 1);
      expect(
        container.read(effectEditorControllerProvider).project!.trimEndMs,
        3100,
      );
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('unified editor uses fixed media workspace and four real tools', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(360, 640);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(() {
      tester.view.resetPhysicalSize();
      tester.view.resetDevicePixelRatio();
    });

    final repository = _FakeProtectionRepository();
    final container = ProviderContainer(
      overrides: [nativeRepositoryProvider.overrideWithValue(repository)],
    );
    addTearDown(container.dispose);

    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: MaterialApp(home: ProtectionEditorScreen(project: project)),
      ),
    );
    await tester.pumpAndSettle();
    await _selectTool(tester, 'mask');

    expect(
      find.byKey(const ValueKey('protection-editor-media-stage')),
      findsOneWidget,
    );
    final stageSize = tester.getSize(
      find.byKey(const ValueKey('protection-editor-media-stage')),
    );
    expect(stageSize.width, closeTo(360, 0.01));
    expect(stageSize.height, greaterThan(150));
    expect(
      find.byKey(const ValueKey('protection-editor-workspace-panel')),
      findsOneWidget,
    );
    expect(
      find.byKey(const ValueKey('protection-editor-tool-mask')),
      findsOneWidget,
    );
    expect(
      find.byKey(const ValueKey('privacy-mode-full-body')),
      findsOneWidget,
    );
    expect(
      find.byKey(const ValueKey('privacy-mode-face-only')),
      findsOneWidget,
    );
    expect(find.text('遮挡样式'), findsOneWidget);
    expect(
      find.byKey(const ValueKey('protection-editor-cancel-action')),
      findsOneWidget,
    );
    expect(
      find.byKey(const ValueKey('protection-editor-export-action')),
      findsOneWidget,
    );

    for (final tool in ['trim', 'mask', 'frame', 'adjust']) {
      expect(find.byKey(ValueKey('editor-tool-$tool')), findsOneWidget);
    }
    expect(find.byKey(const ValueKey('editor-tool-protect')), findsNothing);
    expect(find.byKey(const ValueKey('editor-tool-filter')), findsNothing);

    expect(
      find.byKey(const ValueKey('bottom_control_drawer_handle')),
      findsNothing,
    );
    expect(
      find.byKey(const ValueKey('protection-profile-section')),
      findsNothing,
    );
    expect(
      find.byKey(const ValueKey('protection-profile-toggle')),
      findsNothing,
    );
    expect(find.text('默认 Profile'), findsNothing);

    await _selectTool(tester, 'frame');
    expect(find.byKey(const ValueKey('reframe-mode')), findsOneWidget);
    expect(
      find.byKey(const ValueKey('output-resolution-preset')),
      findsNothing,
    );
    expect(find.text('9:16'), findsOneWidget);
    expect(find.text('背景'), findsNothing);
    expect(find.text('输出跟随模式'), findsNothing);

    await _selectTool(tester, 'trim');
    expect(find.byKey(const ValueKey('trim-range-section')), findsOneWidget);
    expect(
      find.byKey(const ValueKey('integrated-video-trim-control')),
      findsOneWidget,
    );
    expect(find.byTooltip('撤销'), findsNothing);
    expect(find.byTooltip('重做'), findsNothing);
    expect(find.text('开始时间'), findsNothing);
    expect(find.text('结束时间'), findsNothing);
    expect(find.text('截取时长'), findsNothing);
    expect(find.text('舞段截取'), findsNothing);
    final audioBtn = find.byKey(const ValueKey('trim-media-audio-toggle'));
    expect(audioBtn, findsOneWidget);
    await tester.tap(audioBtn);
    await tester.pump();
    await tester.tap(audioBtn);
    await tester.pump();
    final playBtn = find.byIcon(Icons.play_arrow_rounded);
    expect(playBtn, findsOneWidget);
    await tester.tap(playBtn);
    await tester.pump(const Duration(milliseconds: 100));
    expect(find.byIcon(Icons.pause_rounded), findsOneWidget);
    await tester.tap(find.byIcon(Icons.pause_rounded));
    await tester.pump();
    expect(find.byIcon(Icons.play_arrow_rounded), findsOneWidget);
    expect(find.text('质量'), findsNothing);
    expect(find.text('均衡'), findsNothing);
    expect(find.text('快速'), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('face sticker tool exposes custom import and size control', (
    tester,
  ) async {
    final repository = _FakeProtectionRepository();
    final container = ProviderContainer(
      overrides: [nativeRepositoryProvider.overrideWithValue(repository)],
    );
    addTearDown(container.dispose);

    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: MaterialApp(home: ProtectionEditorScreen(project: project)),
      ),
    );
    await tester.pumpAndSettle();
    await _selectTool(tester, 'mask');

    await tester.tap(find.byKey(const ValueKey('privacy-mode-face-only')));
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const ValueKey('protection-sticker-options')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('sticker-import')), findsOneWidget);
    expect(find.text('贴纸大小'), findsOneWidget);
    expect(find.byType(Slider), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  for (final brightness in Brightness.values) {
    testWidgets(
      'compact ${brightness.name} editor shows all protection styles',
      (tester) async {
        tester.view.physicalSize = const Size(360, 640);
        tester.view.devicePixelRatio = 1;
        addTearDown(() {
          tester.view.resetPhysicalSize();
          tester.view.resetDevicePixelRatio();
        });
        final container = ProviderContainer(
          overrides: [
            nativeRepositoryProvider.overrideWithValue(
              _FakeProtectionRepository(),
            ),
          ],
        );
        addTearDown(container.dispose);
        await tester.pumpWidget(
          UncontrolledProviderScope(
            container: container,
            child: MaterialApp(
              theme: ThemeData(brightness: brightness, useMaterial3: true),
              home: ProtectionEditorScreen(project: project),
            ),
          ),
        );
        await tester.pumpAndSettle();
        await _selectTool(tester, 'mask');
        await tester.tap(find.byKey(const ValueKey('privacy-mode-face-only')));
        await tester.pumpAndSettle();

        final viewport = tester.getRect(
          find.byKey(const ValueKey('editor-tool-content-mask')),
        );
        for (final mode in ['sticker', 'mosaic', 'blur', 'solid', 'gradient']) {
          final option = find.byKey(ValueKey('protection-style-$mode'));
          expect(option.hitTestable(), findsOneWidget);
          final rect = tester.getRect(option);
          expect(rect.top, greaterThanOrEqualTo(viewport.top));
          expect(rect.bottom, lessThanOrEqualTo(viewport.bottom));
          expect(
            tester
                .getSemantics(option)
                .getSemanticsData()
                .hasAction(SemanticsAction.tap),
            isTrue,
          );
        }
        expect(
          tester
              .getSemantics(
                find.byKey(const ValueKey('privacy-mode-face-only')),
              )
              .getSemanticsData()
              .hasAction(SemanticsAction.tap),
          isTrue,
        );
        for (final scope in ['full-body', 'face-only']) {
          final size = tester.getSize(
            find.byKey(ValueKey('privacy-mode-$scope')),
          );
          expect(size.width, greaterThanOrEqualTo(48));
          expect(size.height, greaterThanOrEqualTo(48));
        }
        expect(find.text('保护 2 人'), findsOneWidget);
        expect(tester.takeException(), isNull);
      },
    );
  }

  testWidgets('leg stretch appears only after selecting a protagonist', (
    tester,
  ) async {
    final repository = _FakeProtectionRepository();
    final container = ProviderContainer(
      overrides: [nativeRepositoryProvider.overrideWithValue(repository)],
    );
    addTearDown(container.dispose);

    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: MaterialApp(home: ProtectionEditorScreen(project: project)),
      ),
    );
    await tester.pumpAndSettle();

    await _selectTool(tester, 'adjust');
    expect(find.text('拉腿'), findsNothing);

    await _selectTool(tester, 'frame');
    await tester.ensureVisible(find.byKey(const ValueKey('reframe-mode')));
    await tester.tap(find.text('9:16'));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('protection-person-target-1')));
    await tester.pumpAndSettle();

    expect(
      container.read(effectEditorControllerProvider).project!.hasFollowTarget,
      isTrue,
    );
    await _selectTool(tester, 'adjust');
    expect(find.text('拉腿'), findsOneWidget);

    await tester.tap(find.text('拉腿'));
    await tester.pump();
    final toggle = tester.widget<Switch>(find.byType(Switch));
    expect(toggle.value, isFalse);
    toggle.onChanged!(true);
    await tester.pump(const Duration(milliseconds: 250));
    expect(
      container.read(effectEditorControllerProvider).effects.legStretchEnabled,
      isTrue,
    );

    await _selectTool(tester, 'frame');
    final reframeMode = find.byKey(const ValueKey('reframe-mode'));
    await tester.tap(
      find.descendant(of: reframeMode, matching: find.text('原始')),
    );
    await tester.pumpAndSettle();
    expect(
      container.read(effectEditorControllerProvider).effects.legStretchEnabled,
      isFalse,
    );
    await _selectTool(tester, 'adjust');
    expect(find.text('拉腿'), findsNothing);
  });

  testWidgets('saved Profile applies to a fresh video and stays collapsed', (
    tester,
  ) async {
    final repository = _FakeProtectionRepository();
    final profileStore = _MemoryProtectionProfileStore(
      profile: const ProtectionProfile(
        privacyMode: ProjectPrivacyMode.faceOnly,
        fullBodyEffects: EffectConfig(fillMode: FillMode.mosaic, opacity: 0.7),
        faceOnlyEffects: EffectConfig(
          fillMode: FillMode.blur,
          blurStrength: 9,
          faceStickerEnabled: false,
        ),
        outputResolutionPreset: OutputResolutionPreset.fhd,
        portraitReframe: true,
      ),
    );
    final container = ProviderContainer(
      overrides: [
        nativeRepositoryProvider.overrideWithValue(repository),
        protectionProfileStoreProvider.overrideWithValue(profileStore),
      ],
    );
    addTearDown(container.dispose);

    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: MaterialApp(home: ProtectionEditorScreen(project: project)),
      ),
    );
    await tester.pumpAndSettle();

    final selection = container.read(personSelectionControllerProvider);
    final effectState = container.read(effectEditorControllerProvider);
    expect(profileStore.loadCount, 1);
    expect(selection.privacyMode, ProjectPrivacyMode.faceOnly);
    expect(selection.selectedPersonIds, isEmpty);
    expect(selection.faceOnlyPersonIds, {0, 1});
    expect(effectState.effects.fillMode, FillMode.blur);
    expect(effectState.effects.blurStrength, 9);
    expect(
      effectState.project!.outputResolutionPreset,
      OutputResolutionPreset.fhd,
    );
    expect(
      find.byKey(const ValueKey('reframe-subject-prompt')),
      findsOneWidget,
    );
    expect(
      find.byKey(const ValueKey('protection-profile-section')),
      findsNothing,
    );
    expect(
      find.byKey(const ValueKey('protection-profile-toggle')),
      findsNothing,
    );
    await _selectTool(tester, 'frame');
    expect(find.byKey(const ValueKey('reframe-mode')), findsOneWidget);
    expect(
      find.byKey(const ValueKey('output-resolution-preset')),
      findsNothing,
    );
    expect(tester.takeException(), isNull);
  });

  testWidgets('Profile changes are persisted for the next video', (
    tester,
  ) async {
    final repository = _FakeProtectionRepository();
    final profileStore = _MemoryProtectionProfileStore();
    final container = ProviderContainer(
      overrides: [
        nativeRepositoryProvider.overrideWithValue(repository),
        protectionProfileStoreProvider.overrideWithValue(profileStore),
      ],
    );
    addTearDown(container.dispose);

    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: MaterialApp(home: ProtectionEditorScreen(project: project)),
      ),
    );
    await tester.pumpAndSettle();

    container
        .read(personSelectionControllerProvider.notifier)
        .setProjectPrivacyMode(ProjectPrivacyMode.faceOnly);
    final effectController = container.read(
      effectEditorControllerProvider.notifier,
    );
    effectController.updateProtectionStyle(FillMode.blur);
    effectController.updateBlurStrength(11);
    effectController.updateOutputResolutionPreset(OutputResolutionPreset.hd);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 600));

    expect(profileStore.saveCount, greaterThanOrEqualTo(1));
    final saved = profileStore.profile!;
    expect(saved.privacyMode, ProjectPrivacyMode.faceOnly);
    expect(saved.faceOnlyEffects.fillMode, FillMode.blur);
    expect(saved.faceOnlyEffects.blurStrength, 11);
    expect(saved.outputResolutionPreset, OutputResolutionPreset.hd);
    expect(tester.takeException(), isNull);
  });

  testWidgets('saved Profile never overrides an existing configured project', (
    tester,
  ) async {
    final repository = _FakeProtectionRepository();
    final profileStore = _MemoryProtectionProfileStore(
      profile: const ProtectionProfile(
        privacyMode: ProjectPrivacyMode.faceOnly,
        fullBodyEffects: EffectConfig(fillMode: FillMode.blur),
        faceOnlyEffects: EffectConfig(
          fillMode: FillMode.sticker,
          faceStickerEnabled: true,
          stickerAssetId: 'builtin:panda',
        ),
        outputResolutionPreset: OutputResolutionPreset.fhd,
      ),
    );
    final cachedProject = project.copyWith(
      analysisCacheId: 'existing-cache',
      persons: const [
        PersonTrack(
          id: 0,
          normalizedInitialBox: NormalizedRect(
            left: .1,
            top: .1,
            right: .4,
            bottom: .9,
          ),
          thumbnailPath: '',
          confidence: .95,
        ),
      ],
      selectedPersonIds: const {0},
      effects: const EffectConfig(fillMode: FillMode.mosaic, opacity: .6),
      outputResolutionPreset: OutputResolutionPreset.source,
    );
    final container = ProviderContainer(
      overrides: [
        nativeRepositoryProvider.overrideWithValue(repository),
        protectionProfileStoreProvider.overrideWithValue(profileStore),
      ],
    );
    addTearDown(container.dispose);

    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: MaterialApp(
          home: ProtectionEditorScreen(project: cachedProject),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(profileStore.loadCount, 0);
    final selection = container.read(personSelectionControllerProvider);
    final effectState = container.read(effectEditorControllerProvider);
    expect(selection.privacyMode, ProjectPrivacyMode.fullBody);
    expect(selection.selectedPersonIds, {0});
    expect(effectState.effects.fillMode, FillMode.mosaic);
    expect(effectState.effects.opacity, .6);
    expect(
      effectState.project!.outputResolutionPreset,
      OutputResolutionPreset.source,
    );
    expect(tester.takeException(), isNull);
  });

  testWidgets('tool navigation keeps the media workspace stable', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(() {
      tester.view.resetPhysicalSize();
      tester.view.resetDevicePixelRatio();
    });

    final repository = _FakeProtectionRepository();
    final container = ProviderContainer(
      overrides: [nativeRepositoryProvider.overrideWithValue(repository)],
    );
    addTearDown(container.dispose);
    final landscapeProject = project.copyWith(
      videoInfo: const VideoInfo(
        codedWidth: 1920,
        codedHeight: 1080,
        displayWidth: 1920,
        displayHeight: 1080,
        fps: 30,
        durationMs: 4000,
        rotation: 0,
        videoCodec: 'h264',
        hasAudio: false,
      ),
      analysisCacheId: 'cached-landscape',
      persons: const [
        PersonTrack(
          id: 0,
          normalizedInitialBox: NormalizedRect(
            left: 0.12,
            top: 0.12,
            right: 0.36,
            bottom: 0.90,
          ),
          thumbnailPath: '',
          confidence: 0.94,
        ),
        PersonTrack(
          id: 1,
          normalizedInitialBox: NormalizedRect(
            left: 0.62,
            top: 0.12,
            right: 0.86,
            bottom: 0.90,
          ),
          thumbnailPath: '',
          confidence: 0.92,
        ),
      ],
      selectedPersonIds: const {0, 1},
    );

    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: MaterialApp(
          home: ProtectionEditorScreen(project: landscapeProject),
        ),
      ),
    );
    await tester.pumpAndSettle();
    await _selectTool(tester, 'mask');

    expect(
      find.byKey(const ValueKey('bottom_control_drawer_handle')),
      findsNothing,
    );
    final initialFrame = tester.getRect(
      find.byKey(const ValueKey('protection-editor-media-frame')),
    );
    expect(initialFrame.width / initialFrame.height, closeTo(16 / 9, .001));

    await _selectTool(tester, 'mask');
    expect(
      find.byKey(const ValueKey('protection-editor-tool-mask')),
      findsOneWidget,
    );
    expect(
      find.descendant(
        of: find.byKey(const ValueKey('editor-tool-content-mask')),
        matching: find.text('遮挡'),
      ),
      findsNothing,
    );
    expect(
      tester.getRect(
        find.byKey(const ValueKey('protection-editor-media-frame')),
      ),
      initialFrame,
    );

    await _selectTool(tester, 'frame');
    expect(find.byKey(const ValueKey('reframe-mode')), findsOneWidget);
    expect(
      find.descendant(
        of: find.byKey(const ValueKey('editor-tool-content-frame')),
        matching: find.text('画幅'),
      ),
      findsNothing,
    );
    expect(
      tester.getRect(
        find.byKey(const ValueKey('protection-editor-media-frame')),
      ),
      initialFrame,
    );

    await _selectTool(tester, 'trim');
    expect(find.byKey(const ValueKey('trim-range-section')), findsOneWidget);
    expect(
      find.descendant(
        of: find.byKey(const ValueKey('editor-tool-content-trim')),
        matching: find.text('剪辑'),
      ),
      findsNothing,
    );
    expect(
      tester.getRect(
        find.byKey(const ValueKey('protection-editor-media-frame')),
      ),
      initialFrame,
    );

    await _selectTool(tester, 'adjust');
    expect(
      find.descendant(
        of: find.byKey(const ValueKey('editor-tool-content-adjust')),
        matching: find.text('调节'),
      ),
      findsNothing,
    );
    expect(
      find.descendant(
        of: find.byKey(const ValueKey('editor-tool-content-adjust')),
        matching: find.text('取消'),
      ),
      findsNothing,
    );
    expect(find.text('完成'), findsNothing);
    expect(
      tester.getRect(
        find.byKey(const ValueKey('protection-editor-media-frame')),
      ),
      initialFrame,
    );
    for (final label in ['剪辑', '遮挡', '画幅', '调节']) {
      expect(find.text(label), findsOneWidget);
    }
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'resolution preset updates project and survives trim reanalysis',
    (tester) async {
      final repository = _FakeProtectionRepository();
      final container = ProviderContainer(
        overrides: [nativeRepositoryProvider.overrideWithValue(repository)],
      );
      addTearDown(container.dispose);

      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: MaterialApp(home: ProtectionEditorScreen(project: project)),
        ),
      );
      await tester.pumpAndSettle();
      final resTrigger = find.byKey(
        const ValueKey('protection-editor-resolution-trigger'),
      );
      expect(resTrigger, findsOneWidget);
      await tester.tap(resTrigger);
      await tester.pumpAndSettle();
      await tester.tap(find.text('1080p'));
      await tester.pumpAndSettle();
      expect(
        container
            .read(effectEditorControllerProvider)
            .project!
            .outputResolutionPreset,
        OutputResolutionPreset.fhd,
      );

      await _selectTool(tester, 'trim');
      final startHandle = find.byKey(const ValueKey('trim-start-handle'));
      await tester.ensureVisible(startHandle);
      await tester.drag(startHandle, const Offset(48, 0), warnIfMissed: false);
      await tester.pumpAndSettle();

      expect(
        container
            .read(effectEditorControllerProvider)
            .project!
            .outputResolutionPreset,
        OutputResolutionPreset.fhd,
      );
      expect(
        container
            .read(personSelectionControllerProvider)
            .project!
            .outputResolutionPreset,
        OutputResolutionPreset.fhd,
      );
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('temporal trim change reanalyzes from the new first frame', (
    tester,
  ) async {
    final repository = _FakeProtectionRepository();
    final container = ProviderContainer(
      overrides: [nativeRepositoryProvider.overrideWithValue(repository)],
    );
    addTearDown(container.dispose);

    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: MaterialApp(home: ProtectionEditorScreen(project: project)),
      ),
    );
    await tester.pumpAndSettle();

    expect(repository.analyzeRequestCount, 1);
    expect(repository.lastAnalyzeTrimStartMs, 400);

    await _selectTool(tester, 'trim');
    expect(
      find.byKey(const ValueKey('integrated-video-trim-control')),
      findsOneWidget,
    );

    final startHandle = find.byKey(const ValueKey('trim-start-handle'));
    await tester.ensureVisible(startHandle);
    await tester.pumpAndSettle();
    await tester.drag(startHandle, const Offset(48, 0), warnIfMissed: false);
    await tester.pumpAndSettle();

    expect(repository.analyzeRequestCount, 2);
    expect(repository.lastAnalyzeTrimStartMs, greaterThan(400));
    final selectionState = container.read(personSelectionControllerProvider);
    expect(
      selectionState.project?.trimStartMs,
      repository.lastAnalyzeTrimStartMs,
    );
    expect(selectionState.selectedPersonIds, equals({0, 1}));
    expect(tester.takeException(), isNull);
  });

  testWidgets('queued trim accessibility changes cannot outrun reanalysis', (
    tester,
  ) async {
    final repository = _FakeProtectionRepository();
    final container = ProviderContainer(
      overrides: [nativeRepositoryProvider.overrideWithValue(repository)],
    );
    addTearDown(container.dispose);
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: MaterialApp(home: ProtectionEditorScreen(project: project)),
      ),
    );
    await tester.pumpAndSettle();
    await _selectTool(tester, 'trim');
    final gate = Completer<void>();
    repository.analyzeGate = gate;
    final start = tester.getSemantics(
      find.byKey(const ValueKey('trim-start-control')),
    );
    for (var event = 0; event < 2; event++) {
      tester.binding.performSemanticsAction(
        SemanticsActionEvent(
          nodeId: start.id,
          type: SemanticsAction.increase,
          viewId: tester.view.viewId,
        ),
      );
    }
    expect(repository.lastAnalyzeTrimStartMs, 500);
    gate.complete();
    await tester.pumpAndSettle();
    await _selectTool(tester, 'trim');
    expect(find.text('起点 00:00.5'), findsOneWidget);
    expect(
      container.read(personSelectionControllerProvider).project!.trimStartMs,
      500,
    );
    expect(
      container.read(effectEditorControllerProvider).project!.trimStartMs,
      500,
    );
    expect(repository.analyzeRequestCount, 2);
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'shortening the end preserves subject, face style and analyzed IDs',
    (tester) async {
      final repository = _FakeProtectionRepository();
      final analysis = await repository.analyzeVideo(
        videoUri: project.sourceUri,
      );
      final configured = project.copyWith(
        analysisCacheId: analysis.analysisCacheId,
        persons: analysis.persons.map((person) => person.toDomain()).toList(),
        faceOnlyPersonIds: const {0},
        follow: const FollowConfig(
          enabled: true,
          targetPersonId: 1,
          outputAspectRatio: 9 / 16,
        ),
        effects: const EffectConfig(
          fillMode: FillMode.sticker,
          faceStickerEnabled: true,
          stickerAssetId: 'builtin:panda',
          stickerScale: 1.4,
        ),
        outputResolutionPreset: OutputResolutionPreset.fhd,
      );
      final container = ProviderContainer(
        overrides: [nativeRepositoryProvider.overrideWithValue(repository)],
      );
      addTearDown(container.dispose);
      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: MaterialApp(home: ProtectionEditorScreen(project: configured)),
        ),
      );
      await tester.pumpAndSettle();
      await _selectTool(tester, 'trim');
      await tester.drag(
        find.byKey(const ValueKey('trim-end-handle')),
        const Offset(-60, 0),
        warnIfMissed: false,
      );
      await tester.pumpAndSettle();

      final selection = container.read(personSelectionControllerProvider);
      final effects = container.read(effectEditorControllerProvider);
      expect(repository.analyzeRequestCount, 1);
      expect(selection.privacyMode, ProjectPrivacyMode.faceOnly);
      expect(selection.faceOnlyPersonIds, {0});
      expect(effects.project!.follow.targetPersonId, 1);
      expect(effects.project!.follow.enabled, isTrue);
      expect(effects.project!.trimEndMs, lessThan(configured.trimEndMs!));
      expect(
        effects.project!.outputResolutionPreset,
        OutputResolutionPreset.fhd,
      );
      expect(effects.effects.stickerAssetId, 'builtin:panda');
      expect(effects.effects.stickerScale, 1.4);
      expect(
        find.byKey(const ValueKey('trim-confirm-selection')),
        findsNothing,
      );
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'new trim start keeps face style and requires review of new IDs',
    (tester) async {
      final repository = _FakeProtectionRepository();
      final analysis = await repository.analyzeVideo(
        videoUri: project.sourceUri,
      );
      final configured = project.copyWith(
        analysisCacheId: analysis.analysisCacheId,
        persons: analysis.persons.map((person) => person.toDomain()).toList(),
        faceOnlyPersonIds: const {0},
        follow: const FollowConfig(
          enabled: true,
          targetPersonId: 1,
          outputAspectRatio: 9 / 16,
        ),
        effects: const EffectConfig(
          fillMode: FillMode.sticker,
          faceStickerEnabled: true,
          stickerAssetId: 'builtin:panda',
          stickerScale: 1.4,
        ),
      );
      final container = ProviderContainer(
        overrides: [nativeRepositoryProvider.overrideWithValue(repository)],
      );
      addTearDown(container.dispose);
      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: MaterialApp(home: ProtectionEditorScreen(project: configured)),
        ),
      );
      await tester.pumpAndSettle();
      repository.personIds = [10, 11];
      await _selectTool(tester, 'trim');
      await tester.drag(
        find.byKey(const ValueKey('trim-start-handle')),
        const Offset(48, 0),
        warnIfMissed: false,
      );
      await tester.pumpAndSettle();

      final selection = container.read(personSelectionControllerProvider);
      final effects = container.read(effectEditorControllerProvider);
      expect(repository.analyzeRequestCount, 2);
      expect(selection.privacyMode, ProjectPrivacyMode.faceOnly);
      expect(selection.faceOnlyPersonIds, {10, 11});
      expect(effects.project!.follow.enabled, isFalse);
      expect(effects.effects.stickerAssetId, 'builtin:panda');
      expect(effects.effects.stickerScale, 1.4);
      expect(find.text('主角需重选，范围与样式已保留'), findsOneWidget);
      final export = find.byKey(
        const ValueKey('protection-editor-export-action'),
      );
      expect(tester.widget<GestureDetector>(export).onTap, isNull);
      expect(
        find.byKey(const ValueKey('trim-confirm-selection')),
        findsOneWidget,
      );

      await tester.tap(find.byKey(const ValueKey('trim-reselect-subject')));
      await tester.pumpAndSettle();
      expect(find.text('主角保留清晰，并自动跟随'), findsOneWidget);
      await tester.tap(
        find.byKey(const ValueKey('protection-person-target-10')),
      );
      await tester.pumpAndSettle();
      expect(
        container
            .read(effectEditorControllerProvider)
            .project!
            .follow
            .targetPersonId,
        10,
      );
      expect(
        container.read(personSelectionControllerProvider).faceOnlyPersonIds,
        {11},
      );
      expect(tester.widget<GestureDetector>(export).onTap, isNotNull);
      expect(
        find.byKey(const ValueKey('trim-confirm-selection')),
        findsNothing,
      );
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('reopening with cached analysis does not rerun person analysis', (
    tester,
  ) async {
    final repository = _FakeProtectionRepository();
    final container = ProviderContainer(
      overrides: [nativeRepositoryProvider.overrideWithValue(repository)],
    );
    addTearDown(container.dispose);

    final cachedProject = project.copyWith(
      analysisCacheId: 'cached-analysis',
      persons: const [
        PersonTrack(
          id: 0,
          normalizedInitialBox: NormalizedRect(
            left: 0.08,
            top: 0.12,
            right: 0.42,
            bottom: 0.88,
          ),
          thumbnailPath: '',
          confidence: 0.94,
          selected: true,
        ),
        PersonTrack(
          id: 1,
          normalizedInitialBox: NormalizedRect(
            left: 0.55,
            top: 0.14,
            right: 0.88,
            bottom: 0.90,
          ),
          thumbnailPath: '',
          confidence: 0.91,
          selected: true,
        ),
      ],
      selectedPersonIds: const {0, 1},
    );

    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: MaterialApp(
          home: ProtectionEditorScreen(project: cachedProject),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(repository.analyzeRequestCount, 0);
    expect(repository.previewRequestCount, greaterThanOrEqualTo(1));
    expect(
      container.read(personSelectionControllerProvider).selectedPersonIds,
      equals({0, 1}),
    );
    expect(tester.takeException(), isNull);
  });

  testWidgets('stage target changes feed the real effect preview request', (
    tester,
  ) async {
    final repository = _FakeProtectionRepository();
    final container = ProviderContainer(
      overrides: [nativeRepositoryProvider.overrideWithValue(repository)],
    );
    addTearDown(container.dispose);

    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: MaterialApp(home: ProtectionEditorScreen(project: project)),
      ),
    );
    await tester.pumpAndSettle();

    expect(repository.lastSelectedPersonIds, equals({0, 1}));
    expect(repository.lastPreviewTimestampMs, 400);
    expect(repository.lastTightMaskPreview, isTrue);

    final firstTarget = find.byKey(
      const ValueKey('protection-person-target-0'),
    );
    expect(firstTarget, findsOneWidget);
    final firstTargetWidget = tester.widget<GestureDetector>(firstTarget);
    expect(firstTargetWidget.child, isA<SizedBox>());
    expect(
      find.descendant(
        of: firstTarget,
        matching: find.byIcon(Icons.check_rounded),
      ),
      findsNothing,
    );

    expect(find.bySemanticsLabel('人物 1，已保护'), findsOneWidget);
    await tester.tap(firstTarget);
    await tester.pump(const Duration(milliseconds: 250));
    await tester.pumpAndSettle();

    expect(
      container.read(personSelectionControllerProvider).selectedPersonIds,
      hasLength(1),
    );
    expect(repository.lastSelectedPersonIds, hasLength(1));
    expect(repository.previewRequestCount, greaterThanOrEqualTo(2));
    expect(tester.takeException(), isNull);
  });

  testWidgets('full-body and face effect drafts survive mode switching', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(() {
      tester.view.resetPhysicalSize();
      tester.view.resetDevicePixelRatio();
    });

    final repository = _FakeProtectionRepository();
    final container = ProviderContainer(
      overrides: [nativeRepositoryProvider.overrideWithValue(repository)],
    );
    addTearDown(container.dispose);

    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: MaterialApp(home: ProtectionEditorScreen(project: project)),
      ),
    );
    await tester.pumpAndSettle();
    await _selectTool(tester, 'mask');

    final effectController = container.read(
      effectEditorControllerProvider.notifier,
    );
    effectController.updateProtectionStyle(FillMode.mosaic);
    effectController.updateOpacity(0.55);
    await tester.pump(const Duration(milliseconds: 250));

    await tester.ensureVisible(find.text('人脸保护'));
    await tester.tap(find.byKey(const ValueKey('privacy-mode-face-only')));
    await tester.pumpAndSettle();
    expect(
      container.read(personSelectionControllerProvider).faceOnlyPersonIds,
      equals({0, 1}),
    );
    expect(
      container.read(effectEditorControllerProvider).effects.fillMode,
      FillMode.sticker,
    );

    effectController.updateStickerAsset('builtin:panda');
    effectController.updateStickerScale(1.4);
    await tester.pump(const Duration(milliseconds: 250));

    final fullBodyOption = find.byKey(const ValueKey('privacy-mode-full-body'));
    await tester.ensureVisible(fullBodyOption);
    final fullBodyGesture = find.descendant(
      of: fullBodyOption,
      matching: find.byType(GestureDetector),
    );
    await tester.tap(fullBodyGesture, warnIfMissed: false);
    await tester.pumpAndSettle();
    var effects = container.read(effectEditorControllerProvider).effects;
    expect(effects.fillMode, FillMode.mosaic);
    expect(effects.opacity, 0.55);

    await tester.ensureVisible(find.text('人脸保护'));
    await tester.tap(find.byKey(const ValueKey('privacy-mode-face-only')));
    await tester.pumpAndSettle();
    effects = container.read(effectEditorControllerProvider).effects;
    expect(effects.fillMode, FillMode.sticker);
    expect(effects.stickerAssetId, 'builtin:panda');
    expect(effects.stickerScale, 1.4);
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'restore default targets preserves face-only scope without mixed rendering',
    (tester) async {
      tester.view.physicalSize = const Size(390, 844);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(() {
        tester.view.resetPhysicalSize();
        tester.view.resetDevicePixelRatio();
      });

      final repository = _FakeProtectionRepository();
      final container = ProviderContainer(
        overrides: [nativeRepositoryProvider.overrideWithValue(repository)],
      );
      addTearDown(container.dispose);

      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: MaterialApp(home: ProtectionEditorScreen(project: project)),
        ),
      );
      await tester.pumpAndSettle();
      await _selectTool(tester, 'mask');

      await tester.ensureVisible(find.text('人脸保护'));
      await tester.tap(find.byKey(const ValueKey('privacy-mode-face-only')));
      await tester.pumpAndSettle();
      var selection = container.read(personSelectionControllerProvider);
      expect(selection.privacyMode, ProjectPrivacyMode.faceOnly);
      expect(selection.selectedPersonIds, isEmpty);
      expect(selection.faceOnlyPersonIds, equals({0, 1}));
      expect(
        container
            .read(effectEditorControllerProvider)
            .effects
            .faceStickerEnabled,
        isTrue,
      );

      await tester.tap(
        find.byKey(const ValueKey('protection-person-target-0')),
      );
      await tester.pumpAndSettle();
      selection = container.read(personSelectionControllerProvider);
      expect(selection.faceOnlyPersonIds, equals({1}));

      final restoreAction = find.byKey(
        const ValueKey('protection-restore-default-action'),
      );
      await tester.ensureVisible(restoreAction);
      await tester.tap(restoreAction);
      await tester.pumpAndSettle();
      await tester.pump(const Duration(milliseconds: 300));

      selection = container.read(personSelectionControllerProvider);
      final effects = container.read(effectEditorControllerProvider).effects;
      expect(selection.privacyMode, ProjectPrivacyMode.faceOnly);
      expect(selection.selectedPersonIds, isEmpty);
      expect(selection.faceOnlyPersonIds, equals({0, 1}));
      expect(effects.faceStickerEnabled, isTrue);
      expect(effects.fillMode, FillMode.sticker);
      expect(repository.lastSelectedPersonIds, isEmpty);
      expect(repository.lastFaceOnlyPersonIds, equals({0, 1}));
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'portrait subject auto-removes full-body privacy and survives export',
    (tester) async {
      tester.view.physicalSize = const Size(390, 844);
      tester.view.devicePixelRatio = 1;
      addTearDown(() {
        tester.view.resetPhysicalSize();
        tester.view.resetDevicePixelRatio();
      });
      final repository = _FakeProtectionRepository();
      final container = ProviderContainer(
        overrides: [nativeRepositoryProvider.overrideWithValue(repository)],
      );
      addTearDown(container.dispose);
      final landscape = project.copyWith(
        videoInfo: const VideoInfo(
          codedWidth: 1920,
          codedHeight: 1080,
          displayWidth: 1920,
          displayHeight: 1080,
          fps: 30,
          durationMs: 4000,
          rotation: 0,
          videoCodec: 'h264',
          hasAudio: true,
        ),
        analysisCacheId: 'cached',
        selectedPersonIds: {0, 1},
        persons: const [
          PersonTrack(
            id: 0,
            normalizedInitialBox: NormalizedRect(
              left: .1,
              top: .1,
              right: .3,
              bottom: .9,
            ),
            thumbnailPath: '',
            confidence: .95,
          ),
          PersonTrack(
            id: 1,
            normalizedInitialBox: NormalizedRect(
              left: .65,
              top: .1,
              right: .85,
              bottom: .9,
            ),
            thumbnailPath: '',
            confidence: .95,
          ),
        ],
      );
      ExportArgs? exported;
      final router = GoRouter(
        initialLocation: '/protect',
        routes: [
          GoRoute(
            path: '/protect',
            builder: (_, state) => ProtectionEditorScreen(project: landscape),
          ),
          GoRoute(
            path: '/export',
            builder: (_, state) {
              exported = state.extra as ExportArgs;
              return const Scaffold(body: Text('portrait-export'));
            },
          ),
        ],
      );
      addTearDown(router.dispose);
      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: MaterialApp.router(routerConfig: router),
        ),
      );
      await tester.pumpAndSettle();
      await _selectTool(tester, 'frame');
      await tester.ensureVisible(find.byKey(const ValueKey('reframe-mode')));
      await tester.tap(find.text('9:16'));
      await tester.pumpAndSettle();
      expect(
        find.byKey(const ValueKey('reframe-subject-prompt')),
        findsOneWidget,
      );
      expect(find.text('轻触人物选择主角'), findsOneWidget);
      expect(find.bySemanticsLabel('选择人物 2 为主角'), findsOneWidget);
      await tester.tap(
        find.byKey(const ValueKey('protection-person-target-1')),
      );
      await tester.pumpAndSettle();
      expect(repository.lastFollow.enabled, isTrue);
      expect(repository.lastFollow.targetPersonId, 1);
      expect(repository.lastFollow.outputAspectRatio, 9 / 16);
      expect(repository.lastSelectedPersonIds, {0});
      var selection = container.read(personSelectionControllerProvider);
      expect(selection.selectedPersonIds, {0});
      expect(selection.faceOnlyPersonIds, isEmpty);
      expect(
        container
            .read(effectEditorControllerProvider)
            .project!
            .selectedPersonIds,
        {0},
      );
      expect(
        find.byKey(const ValueKey('protection-person-target-0')),
        findsNothing,
      );
      var stageSize = tester.getSize(
        find.byKey(const ValueKey('protection-editor-media-frame')),
      );
      expect(stageSize.width / stageSize.height, closeTo(9 / 16, .001));

      // Source preview lives in the tool drawer. Subject selection collapses
      // the drawer so the media can dominate the screen, therefore reopen the
      // tools before toggling back to the source view.
      await tester.ensureVisible(
        find.byKey(const ValueKey('reframe-source-toggle')),
      );
      await tester.tap(find.byKey(const ValueKey('reframe-source-toggle')));
      await tester.pumpAndSettle();
      expect(repository.lastFollow.enabled, isFalse);
      expect(
        container.read(effectEditorControllerProvider).project!.follow.enabled,
        isTrue,
      );
      stageSize = tester.getSize(
        find.byKey(const ValueKey('protection-editor-media-frame')),
      );
      expect(stageSize.width / stageSize.height, closeTo(16 / 9, .001));
      await _selectTool(tester, 'mask');
      final faceModeOption = find.byKey(
        const ValueKey('privacy-mode-face-only'),
      );
      await tester.ensureVisible(faceModeOption);
      final faceModeGesture = find.descendant(
        of: faceModeOption,
        matching: find.byType(GestureDetector),
      );
      await tester.tap(faceModeGesture);
      await tester.pumpAndSettle();
      expect(
        container.read(personSelectionControllerProvider).privacyMode,
        ProjectPrivacyMode.faceOnly,
      );
      expect(
        container
            .read(effectEditorControllerProvider)
            .project!
            .follow
            .targetPersonId,
        1,
      );
      final clearAction = find.byKey(
        const ValueKey('protection-clear-target-action'),
      );
      await tester.ensureVisible(clearAction);
      await tester.tap(clearAction);
      await tester.pumpAndSettle();
      await tester.tap(
        find.byKey(const ValueKey('protection-editor-export-action')),
      );
      await tester.pumpAndSettle();
      expect(exported, isNotNull);
      expect(exported!.project.follow.enabled, isTrue);
      expect(exported!.project.follow.targetPersonId, 1);
      expect(exported!.project.selectedPersonIds, isEmpty);
      expect(exported!.project.faceOnlyPersonIds, isEmpty);
      expect(exported!.project.outputSize, (width: 594, height: 1056));
      expect(exported!.project.trimStartMs, 400);
      expect(exported!.initialPreviewPath, isNull);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('portrait subject auto-removes face-only privacy', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(() {
      tester.view.resetPhysicalSize();
      tester.view.resetDevicePixelRatio();
    });

    final repository = _FakeProtectionRepository();
    final container = ProviderContainer(
      overrides: [nativeRepositoryProvider.overrideWithValue(repository)],
    );
    addTearDown(container.dispose);
    final faceOnlyProject = project.copyWith(
      videoInfo: const VideoInfo(
        codedWidth: 1920,
        codedHeight: 1080,
        displayWidth: 1920,
        displayHeight: 1080,
        fps: 30,
        durationMs: 4000,
        rotation: 0,
        videoCodec: 'h264',
        hasAudio: true,
      ),
      analysisCacheId: 'cached-face-only',
      selectedPersonIds: const {},
      faceOnlyPersonIds: const {0, 1},
      persons: const [
        PersonTrack(
          id: 0,
          normalizedInitialBox: NormalizedRect(
            left: .1,
            top: .1,
            right: .3,
            bottom: .9,
          ),
          thumbnailPath: '',
          confidence: .95,
        ),
        PersonTrack(
          id: 1,
          normalizedInitialBox: NormalizedRect(
            left: .65,
            top: .1,
            right: .85,
            bottom: .9,
          ),
          thumbnailPath: '',
          confidence: .95,
        ),
      ],
    );

    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: MaterialApp(
          home: ProtectionEditorScreen(project: faceOnlyProject),
        ),
      ),
    );
    await tester.pumpAndSettle();

    var selection = container.read(personSelectionControllerProvider);
    expect(selection.privacyMode, ProjectPrivacyMode.faceOnly);
    expect(selection.faceOnlyPersonIds, {0, 1});

    await _selectTool(tester, 'frame');
    await tester.ensureVisible(find.byKey(const ValueKey('reframe-mode')));
    await tester.tap(find.text('9:16'));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('protection-person-target-0')));
    await tester.pumpAndSettle();

    selection = container.read(personSelectionControllerProvider);
    expect(selection.privacyMode, ProjectPrivacyMode.faceOnly);
    expect(selection.selectedPersonIds, isEmpty);
    expect(selection.faceOnlyPersonIds, {1});
    final effectProject = container
        .read(effectEditorControllerProvider)
        .project!;
    expect(effectProject.faceOnlyPersonIds, {1});
    expect(effectProject.follow.enabled, isTrue);
    expect(effectProject.follow.targetPersonId, 0);
    expect(repository.lastSelectedPersonIds, isEmpty);
    expect(repository.lastFaceOnlyPersonIds, {1});
    expect(repository.lastFollow.targetPersonId, 0);
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'cancel subject selection leaves original privacy and framing intact',
    (tester) async {
      final repository = _FakeProtectionRepository();
      final container = ProviderContainer(
        overrides: [nativeRepositoryProvider.overrideWithValue(repository)],
      );
      addTearDown(container.dispose);
      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: MaterialApp(home: ProtectionEditorScreen(project: project)),
        ),
      );
      await tester.pumpAndSettle();
      await _selectTool(tester, 'frame');
      await tester.ensureVisible(find.byKey(const ValueKey('reframe-mode')));
      await tester.tap(find.text('9:16'));
      await tester.pumpAndSettle();
      await tester.ensureVisible(
        find.byKey(const ValueKey('reframe-cancel-selection')),
      );
      await tester.tap(find.byKey(const ValueKey('reframe-cancel-selection')));
      await tester.pumpAndSettle();
      expect(
        container.read(effectEditorControllerProvider).project!.follow.enabled,
        isFalse,
      );
      expect(repository.lastSelectedPersonIds, {0, 1});
      expect(find.bySemanticsLabel('选择人物 2 为主角'), findsNothing);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'reopening a crop-only project does not silently restore privacy targets',
    (tester) async {
      final repository = _FakeProtectionRepository();
      final container = ProviderContainer(
        overrides: [nativeRepositoryProvider.overrideWithValue(repository)],
      );
      addTearDown(container.dispose);
      final cropOnlyProject = project.copyWith(
        videoInfo: const VideoInfo(
          codedWidth: 1920,
          codedHeight: 1080,
          displayWidth: 1920,
          displayHeight: 1080,
          fps: 30,
          durationMs: 4000,
          rotation: 0,
          videoCodec: 'h264',
          hasAudio: true,
        ),
        analysisCacheId: 'crop-only-cache',
        persons: const [
          PersonTrack(
            id: 0,
            normalizedInitialBox: NormalizedRect(
              left: .1,
              top: .1,
              right: .3,
              bottom: .9,
            ),
            thumbnailPath: '',
            confidence: .95,
          ),
          PersonTrack(
            id: 1,
            normalizedInitialBox: NormalizedRect(
              left: .65,
              top: .1,
              right: .85,
              bottom: .9,
            ),
            thumbnailPath: '',
            confidence: .95,
          ),
        ],
        selectedPersonIds: <int>{},
        faceOnlyPersonIds: <int>{},
        follow: const FollowConfig(
          enabled: true,
          targetPersonId: 1,
          outputAspectRatio: 9 / 16,
        ),
      );
      ExportArgs? exported;
      final router = GoRouter(
        initialLocation: '/protect',
        routes: [
          GoRoute(
            path: '/protect',
            builder: (_, state) =>
                ProtectionEditorScreen(project: cropOnlyProject),
          ),
          GoRoute(
            path: '/export',
            builder: (_, state) {
              exported = state.extra as ExportArgs;
              return const Scaffold(body: Text('crop-only-export'));
            },
          ),
        ],
      );
      addTearDown(router.dispose);

      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: MaterialApp.router(routerConfig: router),
        ),
      );
      await tester.pumpAndSettle();

      expect(repository.analyzeRequestCount, 0);
      expect(
        container.read(personSelectionControllerProvider).privacyTargetIds,
        isEmpty,
      );
      expect(
        container
            .read(effectEditorControllerProvider)
            .project!
            .follow
            .targetPersonId,
        1,
      );
      final stageSize = tester.getSize(
        find.byKey(const ValueKey('protection-editor-media-frame')),
      );
      expect(stageSize.width / stageSize.height, closeTo(9 / 16, .001));

      await tester.tap(
        find.byKey(const ValueKey('protection-editor-export-action')),
      );
      await tester.pumpAndSettle();
      expect(exported, isNotNull);
      expect(exported!.project.selectedPersonIds, isEmpty);
      expect(exported!.project.faceOnlyPersonIds, isEmpty);
      expect(exported!.project.follow.targetPersonId, 1);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('single next action goes directly to export', (tester) async {
    final repository = _FakeProtectionRepository();
    final container = ProviderContainer(
      overrides: [nativeRepositoryProvider.overrideWithValue(repository)],
    );
    addTearDown(container.dispose);

    final router = GoRouter(
      initialLocation: '/protect',
      routes: [
        GoRoute(
          path: '/protect',
          builder: (context, state) => ProtectionEditorScreen(project: project),
        ),
        GoRoute(
          path: '/export',
          builder: (context, state) {
            final args = state.extra! as ExportArgs;
            return Scaffold(
              body: Center(child: Text('export-${args.project.id}')),
            );
          },
        ),
      ],
    );
    addTearDown(router.dispose);

    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: MaterialApp.router(routerConfig: router),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(
      find.byKey(const ValueKey('protection-editor-export-action')),
    );
    await tester.pumpAndSettle();

    expect(find.text('export-unified-protection-test'), findsOneWidget);
    expect(find.text('编辑效果'), findsNothing);
    expect(tester.takeException(), isNull);
  });
}

class _MemoryProtectionProfileStore implements ProtectionProfileStore {
  ProtectionProfile? profile;
  int loadCount = 0;
  int saveCount = 0;

  _MemoryProtectionProfileStore({this.profile});

  @override
  Future<ProtectionProfile?> load() async {
    loadCount += 1;
    return profile;
  }

  @override
  Future<void> save(ProtectionProfile profile) async {
    saveCount += 1;
    this.profile = profile;
  }
}

class _FakeProtectionRepository implements NativeProcessingRepository {
  List<int> personIds = const [0, 1];
  bool failPreview = false;
  Completer<void>? analyzeGate;
  int analyzeRequestCount = 0;
  int? lastAnalyzeTrimStartMs;
  Set<int> lastSelectedPersonIds = const {};
  Set<int> lastFaceOnlyPersonIds = const {};
  int? lastPreviewTimestampMs;
  bool? lastTightMaskPreview;
  int previewRequestCount = 0;
  FollowConfig lastFollow = const FollowConfig();

  @override
  Future<AnalyzeResultDto> analyzeVideo({
    required String videoUri,
    String modelProfile = 'balanced',
    int trimStartMs = 0,
  }) async {
    analyzeRequestCount += 1;
    lastAnalyzeTrimStartMs = trimStartMs;
    if (analyzeGate != null) await analyzeGate!.future;
    return AnalyzeResultDto(
      analysisCacheId: 'unified-cache',
      videoInfo: VideoInfoDto(
        codedWidth: 720,
        codedHeight: 1280,
        displayWidth: 720,
        displayHeight: 1280,
        fps: 30,
        durationMs: 4000,
        rotation: 0,
        videoCodec: 'h264',
        audioCodec: null,
        hasAudio: false,
      ),
      persons: [
        if (personIds.isNotEmpty)
          DetectedPersonDto(
            id: personIds[0],
            x1: 0.08,
            y1: 0.12,
            x2: 0.42,
            y2: 0.88,
            thumbnailPath: '',
            confidence: 0.94,
          ),
        if (personIds.length > 1)
          DetectedPersonDto(
            id: personIds[1],
            x1: 0.55,
            y1: 0.14,
            x2: 0.88,
            y2: 0.90,
            thumbnailPath: '',
            confidence: 0.91,
          ),
      ],
    );
  }

  @override
  Future<PreviewFrameDto> getPreviewFrame({
    required String analysisCacheId,
    required int timestampMs,
    required List<int> selectedPersonIds,
    List<int> faceOnlyPersonIds = const [],
    required EffectConfig effects,
    FollowConfig follow = const FollowConfig(),
    bool tightMaskPreview = false,
  }) async {
    previewRequestCount += 1;
    lastSelectedPersonIds = selectedPersonIds.toSet();
    lastFaceOnlyPersonIds = faceOnlyPersonIds.toSet();
    lastPreviewTimestampMs = timestampMs;
    lastTightMaskPreview = tightMaskPreview;
    lastFollow = follow;
    if (failPreview) throw StateError('preview rendering failed');
    return PreviewFrameDto(
      thumbnailPath: '',
      renderTimeMs: 2,
      timestampMs: timestampMs,
    );
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}
