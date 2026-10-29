import 'package:app/app/theme.dart';
import 'package:app/features/comparison/presentation/comparison_controller.dart';
import 'package:app/features/comparison/presentation/comparison_screen.dart';
import 'package:app/features/comparison/presentation/comparison_export_dialog.dart';
import 'package:dance_domain/dance_domain.dart';
import 'package:dance_native/dance_native.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'comparison_test.dart' show FakeComparisonGateway, savedProject;

ComparisonProject exportProject() => savedProject().copyWith(
  timeScale: .8,
  offsetUs: 1200000,
  speed: .5,
  sideBySide: true,
  mirrorTeacher: true,
  mirrorLearner: false,
  teacherPicture: const ComparisonPicture(zoom: 1.8, panX: -.4, panY: .2),
  learnerPicture: const ComparisonPicture(zoom: 2.2, panX: .7, panY: -.3),
  loopStartUs: 1234567,
  loopEndUs: 4567891,
  loopEnabled: false,
);
ComparisonExportPlan plan(ComparisonProject p, {bool selection = false}) =>
    ComparisonExportPlan(
      project: p,
      durationUs: 12000000,
      previewWidth: 1080,
      previewHeight: 1801,
      sideBySide: true,
      selection: selection,
    );
ComparisonExportDto job(String state) => ComparisonExportDto(
  jobId: 'job-1',
  projectId: 'test_project',
  state: state,
  stage: state,
  progress: state == 'completed' ? 1 : .3,
  startUs: 1234567,
  endUs: 4567891,
  width: 1080,
  height: 1800,
);

class ExportGateway extends FakeComparisonGateway {
  ExportGateway() : super(saved: [exportProject()]);
  ComparisonExportDto? status;
  ComparisonExportPlan? submitted;
  bool replacementConfirmed = false;
  int cancelled = 0, retried = 0, published = 0, viewed = 0;
  @override
  Future<ComparisonExportDto?> getExport() async => status;
  @override
  Future<ComparisonExportDto> startExport(
    ComparisonExportPlan p, {
    bool replacePreviousUnsaved = false,
  }) async {
    submitted = p;
    replacementConfirmed = replacePreviousUnsaved;
    return status = job('encoding');
  }

  @override
  Future<ComparisonExportDto> cancelExport(String id) async {
    cancelled++;
    return status = job('cancelled');
  }

  @override
  Future<ComparisonExportDto> retryExport(String id) async {
    retried++;
    return status = job('encoding');
  }

  @override
  Future<ComparisonExportDto> saveExport(String id) async {
    published++;
    status!.savedUri = 'content://media/external_primary/video/media/42';
    return status!;
  }

  @override
  Future<void> openExport(String id) async {
    viewed++;
  }
}

class _PhotosDeniedExportGateway extends ExportGateway {
  @override
  Future<ComparisonExportDto> saveExport(String id) async {
    published++;
    status!.error = '视频已导出；未获得添加相册权限，可授权后再次保存';
    return status!;
  }
}

Future<void> showExport(
  WidgetTester tester,
  ExportGateway gateway, {
  bool selection = true,
  TargetPlatform platform = TargetPlatform.android,
}) async {
  await tester.pumpWidget(
    MaterialApp(
      theme: AppTheme.darkTheme.copyWith(platform: platform),
      home: Scaffold(
        body: Builder(
          builder: (context) => TextButton(
            onPressed: () => showDialog<void>(
              context: context,
              barrierDismissible: false,
              builder: (_) => ComparisonExportDialog(
                gateway: gateway,
                full: plan(exportProject()),
                selection: selection
                    ? plan(exportProject(), selection: true)
                    : null,
              ),
            ),
            child: const Text('open'),
          ),
        ),
      ),
    ),
  );
  await tester.tap(find.text('open'));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('iOS export explains the system background time limit', (
    tester,
  ) async {
    final g = ExportGateway()..status = job('encoding');
    await showExport(tester, g, platform: TargetPlatform.iOS);
    expect(find.text('iOS 后台运行时间由系统限制，建议保持应用在前台；中断后可返回重试。'), findsOneWidget);
    expect(find.text('切到后台后会继续导出'), findsNothing);
    await tester.tap(find.text('取消导出'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('完成'));
    await tester.pumpAndSettle();
  });

  testWidgets('iOS Photos denial keeps the private movie viewable', (
    tester,
  ) async {
    final g = _PhotosDeniedExportGateway()
      ..status = (job('completed')..outputPath = '/private/exports/job-1.mp4');
    await showExport(tester, g, platform: TargetPlatform.iOS);
    await tester.tap(find.text('查看上次导出'));
    await tester.pumpAndSettle();
    expect(find.text('查看视频'), findsOneWidget);
    expect(find.text('保存到照片'), findsOneWidget);
    expect(find.text('保存视频'), findsNothing);
    expect(find.text('重新导出'), findsNothing);
    await tester.tap(find.text('查看视频'));
    await tester.pumpAndSettle();
    expect(g.viewed, 1);
    expect(g.published, 0);
    await tester.tap(find.text('保存到照片'));
    await tester.pumpAndSettle();
    expect(g.published, 1);
    expect(g.status!.state, 'completed');
    expect(g.status!.savedUri, isNull);
    expect(g.status!.outputPath, '/private/exports/job-1.mp4');
    expect(find.textContaining('未获得添加相册权限'), findsOneWidget);
    expect(find.text('保存到照片'), findsOneWidget);
    await tester.tap(find.text('查看视频'));
    await tester.pumpAndSettle();
    expect(g.viewed, 2);
    await tester.tap(find.text('完成'));
    await tester.pumpAndSettle();
    expect(find.byType(ComparisonExportDialog), findsNothing);
  });

  testWidgets(
    'unsaved result requires replacement consent, including a different project',
    (tester) async {
      final prior = job('completed')
        ..projectId = 'another_project'
        ..outputPath = '/private/exports/old.mp4';
      final g = ExportGateway()..status = prior;
      await showExport(tester, g, platform: TargetPlatform.iOS);
      expect(find.textContaining('上次导出尚未保存，开始新导出需要确认替换'), findsOneWidget);
      await tester.tap(find.text('开始导出'));
      await tester.pumpAndSettle();
      expect(find.text('替换未保存的导出？'), findsOneWidget);
      await tester.tap(find.text('返回保存'));
      await tester.pumpAndSettle();
      expect(g.submitted, isNull);
      expect(g.replacementConfirmed, false);
      await tester.tap(find.text('开始导出'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('替换旧成片'));
      await tester.pumpAndSettle();
      expect(g.submitted, isNotNull);
      expect(g.replacementConfirmed, true);
      await tester.tap(find.text('取消导出'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('完成'));
      await tester.pumpAndSettle();
    },
  );

  testWidgets('saved result starts a new export without replacement consent', (
    tester,
  ) async {
    final g = ExportGateway()
      ..status = (job('completed')
        ..outputPath = '/private/exports/old.mp4'
        ..savedUri = 'ph://already-saved');
    await showExport(tester, g, platform: TargetPlatform.iOS);
    await tester.tap(find.text('开始导出'));
    await tester.pumpAndSettle();
    expect(find.text('替换未保存的导出？'), findsNothing);
    expect(g.submitted, isNotNull);
    expect(g.replacementConfirmed, false);
    await tester.tap(find.text('取消导出'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('完成'));
    await tester.pumpAndSettle();
  });

  testWidgets('iOS saved export opens without another Photos request', (
    tester,
  ) async {
    final g = ExportGateway()
      ..status = (job('completed')..savedUri = 'ph://saved-asset');
    await showExport(tester, g, platform: TargetPlatform.iOS);
    await tester.tap(find.text('查看上次导出'));
    await tester.pumpAndSettle();
    expect(find.text('保存到照片'), findsNothing);
    await tester.tap(find.text('查看视频'));
    await tester.pumpAndSettle();
    expect(g.viewed, 1);
    expect(g.published, 0);
    await tester.tap(find.text('完成'));
    await tester.pumpAndSettle();
  });

  testWidgets(
    'an active background export resumes progress before opening preview decoders',
    (tester) async {
      final g = ExportGateway()..status = job('encoding');
      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.darkTheme,
          home: ComparisonScreen(gateway: g),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.byType(ComparisonExportDialog), findsOneWidget);
      expect(g.opens, 0);
      g.status = job('completed');
      await tester.pump(const Duration(milliseconds: 500));
      await tester.pumpAndSettle();
      expect(find.text('重新导出'), findsNothing);
      await tester.tap(find.text('完成'));
      await tester.pumpAndSettle();
      expect(find.byType(ComparisonExportDialog), findsNothing);
      expect(g.opens, 1);
      expect(g.current.phase, 'paused');
      expect(g.practice!.timeScale, .8);
      expect(g.practice!.speed, .5);
      await tester.pumpWidget(const SizedBox());
      await tester.pumpAndSettle();
    },
  );

  test(
    'export range ignores practice speed and loop repetition and freezes both pictures',
    () {
      final p = exportProject();
      final full = plan(p);
      final ab = plan(p, selection: true);
      expect((full.startUs, full.endUs), (0, 12000000));
      expect((ab.startUs, ab.endUs), (1234567, 4567891));
      expect(p.loopEnabled, false);
      expect(ab.durationUs, 3333324);
      expect(ab.project.musicMapping.target(2000000), 2800000);
      expect(ab.project.teacherPicture, p.teacherPicture);
      expect(ab.project.learnerPicture, p.learnerPicture);
      expect(ab.project.mirrorTeacher, true);
      expect(ab.project.mirrorLearner, false);
      expect(ab.sideBySide, true);
      expect((ab.width, ab.height), (1080, 1800));
      final later = p.copyWith(
        timeScale: 1,
        mirrorTeacher: false,
        loopEndUs: 6000000,
      );
      expect(ab.project.timeScale, .8);
      expect(ab.endUs, 4567891);
      expect(later.timeScale, 1);
    },
  );
  test('encoder canvas stays even and bounded without upscaling', () {
    final p = exportProject();
    final bounded = ComparisonExportPlan(
      project: p,
      durationUs: 12000000,
      previewWidth: 3841,
      previewHeight: 2161,
      sideBySide: false,
    );
    expect((bounded.width, bounded.height), (1920, 1080));
    expect(bounded.gap, 4);
    expect(
      () => plan(p.copyWith(clearLoop: true), selection: true),
      throwsFormatException,
    );
    expect(
      () => ComparisonExportPlan(
        project: p,
        durationUs: 3000000,
        previewWidth: 1080,
        previewHeight: 1800,
        sideBySide: false,
        selection: true,
      ),
      throwsFormatException,
    );
  });
  test(
    'preparing export releases decoders and closing restores the paused project',
    () async {
      final g = ExportGateway();
      final c = ComparisonController(g)..setViewport(1080, 1800, true);
      await c.initialize();
      await c.toggle();
      await c.seek(2345678);
      final result = await c.prepareExport();
      expect(result, isNotNull);
      expect(c.exporting, true);
      expect(c.playback, isNull);
      expect(g.events.last, 'shared-close');
      expect(result!.$1.project.positionUs, 2345678);
      expect(result.$1.project.speed, .5);
      expect(result.$2!.startUs, 1234567);
      expect(c.canPlay, false);
      await c.finishExport();
      expect(c.exporting, false);
      expect(c.playback!.phase, 'paused');
      expect(c.project.positionUs, 2345678);
      expect(c.project.timeScale, .8);
      expect(g.opens, 2);
      c.dispose();
    },
  );
  test(
    'failed project save leaves preview available and never starts an export',
    () async {
      final g = ExportGateway();
      final c = ComparisonController(g)..setViewport(1080, 1800, true);
      await c.initialize();
      g.failSave = true;
      expect(await c.prepareExport(), isNull);
      expect(c.exporting, false);
      expect(c.playback, isNotNull);
      expect(g.events.where((e) => e == 'shared-close'), isEmpty);
      expect(g.submitted, isNull);
      g.failSave = false;
      c.dispose();
    },
  );
  testWidgets(
    'A/B export uses selected bounds and cancel returns a terminal result',
    (tester) async {
      final g = ExportGateway();
      await showExport(tester, g);
      await tester.tap(find.text('A/B 选段'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('开始导出'));
      await tester.pumpAndSettle();
      expect(g.submitted!.startUs, 1234567);
      expect(g.submitted!.endUs, 4567891);
      expect(find.text('切到后台后会继续导出'), findsOneWidget);
      await tester.tap(find.text('取消导出'));
      await tester.pumpAndSettle();
      expect(g.cancelled, 1);
      expect(find.text('重试导出'), findsOneWidget);
      await tester.tap(find.text('完成'));
      await tester.pumpAndSettle();
      expect(find.byType(ComparisonExportDialog), findsNothing);
    },
  );
  testWidgets(
    'back waits for cancellation before dismissing the active dialog',
    (tester) async {
      final g = ExportGateway()..status = job('encoding');
      await showExport(tester, g);
      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();
      expect(g.cancelled, 1);
      expect(find.byType(ComparisonExportDialog), findsNothing);
    },
  );
  testWidgets(
    'missing selection is disabled and completed result can be saved and opened',
    (tester) async {
      final g = ExportGateway();
      await showExport(tester, g, selection: false);
      expect(
        tester
            .widget<RadioListTile<bool>>(
              find.byWidgetPredicate(
                (w) => w is RadioListTile<bool> && w.value,
              ),
            )
            .enabled,
        false,
      );
      g.status = job('completed');
      await tester.tap(find.text('开始导出'));
      await tester.pumpAndSettle();
      g.status = job('completed');
      await tester.pump(const Duration(milliseconds: 500));
      await tester.pumpAndSettle();
      await tester.tap(find.text('保存视频'));
      await tester.pumpAndSettle();
      expect(g.published, 1);
      await tester.tap(find.text('查看视频'));
      await tester.pumpAndSettle();
      expect(g.viewed, 1);
      await tester.tap(find.text('完成'));
      await tester.pumpAndSettle();
    },
  );
  testWidgets('interrupted frozen export is offered for retry', (tester) async {
    final g = ExportGateway()..status = job('interrupted');
    await showExport(tester, g);
    await tester.tap(find.text('重试上次中断或失败的导出'));
    await tester.pumpAndSettle();
    expect(g.retried, 1);
    expect(find.text('取消导出'), findsOneWidget);
    await tester.tap(find.text('取消导出'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('完成'));
    await tester.pumpAndSettle();
  });
}
