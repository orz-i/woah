import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;
import 'dart:ui' as ui;
import 'package:app/app/theme.dart';
import 'package:app/features/comparison/data/comparison_gateway.dart';
import 'package:app/features/comparison/presentation/comparison_cutout_gesture.dart';
import 'package:app/features/comparison/presentation/comparison_screen.dart';
import 'package:dance_domain/dance_domain.dart';
import 'package:dance_native/dance_native.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';

/// Debug-only QA entry. Uses the real screen and native gateway, with only
/// fixture project loading/saving isolated from the user's project store.
class FixtureGateway extends NativeComparisonGateway {
  FixtureGateway(this.project);
  final ComparisonProject project;
  ComparisonProject? lastSaved;
  @override
  Future<ComparisonLibrary> load() async => ComparisonLibrary([project], 0);
  @override
  Future<void> save(ComparisonProject value) async {
    lastSaved = value;
  }

  @override
  Future<ComparisonExportDto?> getExport() async => null;
}

final rootKey = GlobalKey();
final api = DanceComparisonApi();
int pointer = 15000;

Element? findElement(bool Function(Widget) predicate) {
  Element? result;
  void visit(Element element) {
    if (result != null) return;
    if (predicate(element.widget)) {
      result = element;
      return;
    }
    element.visitChildren(visit);
  }

  final root = WidgetsBinding.instance.rootElement;
  if (root != null) visit(root);
  return result;
}

Element? byKey(String key) => findElement((w) => w.key == ValueKey(key));
Element? byText(String value) =>
    findElement((w) => w is Text && w.data == value);
Future<void> until(
  bool Function() check,
  String description, {
  int seconds = 25,
}) async {
  final deadline = DateTime.now().add(Duration(seconds: seconds));
  while (!check()) {
    if (DateTime.now().isAfter(deadline)) throw StateError(description);
    await Future<void>.delayed(const Duration(milliseconds: 50));
  }
}

void require(bool valid, String reason) {
  if (!valid) throw StateError(reason);
}

RenderBox box(Element e) => e.findRenderObject() as RenderBox;
Offset center(Element e) =>
    box(e).localToGlobal(box(e).size.center(Offset.zero));
void send(PointerEvent event) =>
    GestureBinding.instance.handlePointerEvent(event);
Future<void> tap(Element e) async {
  final p = center(e), id = ++pointer;
  send(PointerDownEvent(pointer: id, position: p));
  await Future<void>.delayed(const Duration(milliseconds: 80));
  send(PointerUpEvent(pointer: id, position: p));
}

Future<void> drag(
  bool reference,
  int nativeW,
  int nativeH,
  double aspect,
  double x,
  double y,
  bool expanded,
) async {
  final e = byKey('comparison-cutout-gesture')!;
  final b = box(e);
  final stage = ComparisonCutoutStage(
    b.size,
    nativeW,
    nativeH,
    false,
    expanded,
  );
  final pane = stage.nativePane(reference), fit = stage.fitted(reference);
  final fw = math.max(1, math.min(pane.width, pane.height * aspect).toInt());
  final fh = math.max(1, math.min(pane.height, pane.width / aspect).toInt());
  final nx = (pane.width - fw) ~/ 2 + x * fw;
  final ny = (pane.height - fh) ~/ 2 + y * fh;
  final start = b.localToGlobal(
    Offset(
      fit.left + nx / pane.width * fit.width,
      fit.top + ny / pane.height * fit.height,
    ),
  );
  final end = b.localToGlobal(stage.canvas.center);
  final id = ++pointer;
  send(PointerDownEvent(pointer: id, position: start));
  try {
    await until(
      () => byKey('comparison-cutout-drag-still') != null,
      'Real drag still did not appear',
    );
    send(PointerMoveEvent(pointer: id, position: end, delta: end - start));
    await Future<void>.delayed(const Duration(milliseconds: 120));
  } finally {
    send(PointerUpEvent(pointer: id, position: end));
  }
}

Future<Map<String, int>> capture(Directory folder, String name) async {
  await Future<void>.delayed(const Duration(milliseconds: 200));
  await WidgetsBinding.instance.endOfFrame;
  final boundary =
      rootKey.currentContext!.findRenderObject() as RenderRepaintBoundary;
  final b = box(byKey('comparison-stage')!);
  final origin = boundary.globalToLocal(b.localToGlobal(Offset.zero));
  final image = await boundary.toImage(pixelRatio: 1);
  try {
    final png = (await image.toByteData(format: ui.ImageByteFormat.png))!;
    File('${folder.path}/$name.png').writeAsBytesSync(png.buffer.asUint8List());
    final rgba = (await image.toByteData(
      format: ui.ImageByteFormat.rawRgba,
    ))!.buffer.asUint8List();
    final counts = [0, 0, 0];
    for (var row = 0; row < b.size.height.toInt(); row++) {
      final y = (origin.dy + row).round();
      if (y < 0 || y >= image.height) continue;
      final region = (row / b.size.height < .32)
          ? 0
          : (row / b.size.height >= .68 ? 2 : 1);
      for (var col = 0; col < b.size.width.toInt(); col++) {
        final x = (origin.dx + col).round();
        if (x < 0 || x >= image.width) continue;
        final i = (y * image.width + x) * 4;
        if (rgba[i] > 32 || rgba[i + 1] > 32 || rgba[i + 2] > 32) {
          counts[region]++;
        }
      }
    }
    return {'reference': counts[0], 'canvas': counts[1], 'target': counts[2]};
  } finally {
    image.dispose();
  }
}

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final folder = Directory(
    '${Directory.systemTemp.parent.path}/Documents/comparison-cutout-fixtures',
  );
  final file = File('${folder.path}/gesture-report.json');
  final report = <String, Object?>{};
  try {
    require(File('${folder.path}/source.mp4').existsSync(), 'Fixture missing');
    final project = ComparisonProject(
      id: 'qa_cutout_gesture',
      title: 'Cutout gesture QA',
      teacher: ComparisonSource(
        uri: '${folder.path}/source.mp4',
        name: 'Reference',
        durationUs: 1000000,
        hasAudio: false,
      ),
      learner: ComparisonSource(
        uri: '${folder.path}/source.mp4',
        name: 'Target',
        durationUs: 1000000,
        hasAudio: false,
      ),
      offsetUs: 0,
      alignment: 'manual',
      positionUs: 0,
      sideBySide: false,
      updatedAtMs: DateTime.now().millisecondsSinceEpoch,
    );
    // Keep this QA entry independent of archives left by previous runs.
    await api.forgetCutout(project.id);
    final gateway = FixtureGateway(project);
    runApp(
      MaterialApp(
        theme: AppTheme.darkTheme,
        home: RepaintBoundary(
          key: rootKey,
          child: ComparisonScreen(gateway: gateway),
        ),
      ),
    );
    await until(
      () => byKey('comparison-stage') != null,
      'Comparison stage missing',
    );
    ComparisonPlaybackDto state = await api.getState();
    final deadline = DateTime.now().add(const Duration(seconds: 30));
    while (state.phase != 'paused' ||
        state.teacherFrameUs == null ||
        state.learnerFrameUs == null) {
      require(
        state.error == null && DateTime.now().isBefore(deadline),
        'Native pair did not open',
      );
      await Future<void>.delayed(const Duration(milliseconds: 100));
      state = await api.getState();
    }
    await Future<void>.delayed(const Duration(milliseconds: 500));
    final initial = box(byKey('comparison-stage')!);
    final dpr = View.of(byKey('comparison-stage')!).devicePixelRatio;
    final ratio = math.min(
      dpr,
      1920 / math.max(initial.size.width, initial.size.height),
    );
    final w = (initial.size.width * ratio).round(),
        h = (initial.size.height * ratio).round();
    await tap(byKey('comparison-view')!);
    await until(() => byText('抠像画布') != null, 'Cutout menu missing');
    await Future<void>.delayed(const Duration(milliseconds: 250));
    await tap(byText('抠像画布')!);
    await until(
      () => byKey('comparison-cutout-gesture') != null,
      'Selection overlay missing',
    );
    await Future<void>.delayed(const Duration(milliseconds: 600));
    state = await api.getState();
    await drag(true, w, h, state.teacherAspect!, .355, .855, false);
    await until(
      () => byKey('comparison-cutout-reference-still') != null,
      'First subject did not enter canvas',
    );
    final counts = await capture(folder, 'gesture-first-drop');
    report['firstDropPixels'] = counts;
    require(
      counts.values.every((v) => v > 200),
      'Expanded canvas or a clipped source was blank: $counts',
    );
    await drag(false, w, h, state.learnerAspect!, .676, .86, true);
    await until(
      () => byKey('comparison-cutout-timeline') != null,
      'Dynamic canvas not published',
      seconds: 60,
    );
    await until(
      () => byKey('comparison-cutout-gesture') == null,
      'Selection overlay not removed',
    );
    report['dynamicPixels'] = await capture(folder, 'gesture-dynamic');
    final firstEdit = byKey('comparison-canvas-edit')?.widget as IconButton?;
    if (findElement(
          (w) => w is Text && (w.data?.startsWith('已分析 ') ?? false),
        ) !=
        null) {
      require(
        firstEdit != null && firstEdit.onPressed == null,
        'Partial editing is enabled',
      );
      final export = byKey('comparison-cutout-export')?.widget as IconButton?;
      require(
        export != null && export.onPressed == null,
        'Partial export is enabled or missing',
      );
      report['partialVisibleBeforeEditing'] = true;
    }
    await until(
      () =>
          (byKey('comparison-canvas-edit')?.widget as IconButton?)?.onPressed !=
          null,
      'Full analysis did not enable editing',
      seconds: 60,
    );
    await tap(byKey('comparison-canvas-edit')!);
    await until(
      () =>
          byKey('comparison-canvas-edit-gesture') != null &&
          (byKey('comparison-canvas-done')!.widget as TextButton).onPressed !=
              null,
      'Canvas edit not ready',
    );
    await tap(byKey('comparison-canvas-overlay')!);
    await until(
      () =>
          gateway.lastSaved?.canvasLayout?.target.opacity == .5 &&
          (byKey('comparison-canvas-done')!.widget as TextButton).onPressed !=
              null,
      'Overlay not applied/saved',
    );
    await tap(byKey('comparison-canvas-target')!);
    await tap(byKey('comparison-canvas-mirror')!);
    await until(
      () =>
          gateway.lastSaved?.canvasLayout?.target.mirror == true &&
          (byKey('comparison-canvas-done')!.widget as TextButton).onPressed !=
              null,
      'Independent mirror not saved',
    );
    final surface = box(byKey('comparison-canvas-edit-gesture')!);
    final start = surface.localToGlobal(surface.size.center(Offset.zero));
    final end =
        start + Offset(surface.size.width * .18, -surface.size.height * .13);
    final id = ++pointer;
    send(PointerDownEvent(pointer: id, position: start));
    await Future<void>.delayed(const Duration(milliseconds: 100));
    send(
      PointerMoveEvent(
        pointer: id,
        position: start + (end - start) * .5,
        delta: (end - start) * .5,
      ),
    );
    await Future<void>.delayed(const Duration(milliseconds: 100));
    send(
      PointerMoveEvent(pointer: id, position: end, delta: (end - start) * .5),
    );
    await Future<void>.delayed(const Duration(milliseconds: 100));
    send(PointerUpEvent(pointer: id, position: end));
    await until(
      () =>
          (gateway.lastSaved?.canvasLayout?.target.x ?? 0) > .5 &&
          (byKey('comparison-canvas-done')!.widget as TextButton).onPressed !=
              null,
      'Canvas move not saved',
    );
    final saved = gateway.lastSaved!.canvasLayout!;
    require(
      saved.reference.x == .5 && saved.target.y < .88,
      'Moving target changed reference or failed vertical move',
    );
    report['editedLayout'] = saved.toJson();
    report['editedPixels'] = await capture(folder, 'gesture-edited');
    await tap(byKey('comparison-canvas-done')!);
    await until(
      () => byKey('comparison-cutout-timeline') != null,
      'Edit did not finish',
    );
    await tap(byText('退出画布')!);
    await until(
      () => byKey('comparison-picture-pane-teacher') != null,
      'Ordinary view not restored',
    );
    // Enter through the actual view menu; restoration must skip long-press selection.
    await until(
      () =>
          (byKey('comparison-view')?.widget as PopupMenuButton?)?.enabled ==
          true,
      'Ordinary view menu unavailable',
    );
    await Future<void>.delayed(const Duration(milliseconds: 500));
    await tap(byKey('comparison-view')!);
    await until(() => byText('抠像画布') != null, 'Restore menu missing');
    await Future<void>.delayed(const Duration(milliseconds: 250));
    await tap(byText('抠像画布')!);
    await until(
      () =>
          findElement(
            (w) => w is Text && (w.data?.startsWith('已恢复画布') ?? false),
          ) !=
          null,
      'Saved canvas was not restored',
    );
    await until(
      () =>
          (byKey('comparison-canvas-edit')?.widget as IconButton?)?.onPressed !=
          null,
      'Restored canvas never became editable',
    );
    require(
      byKey('comparison-cutout-gesture') == null,
      'Restore reopened selection overlay',
    );
    report['restoredPixels'] = await capture(folder, 'gesture-restored');
    await tap(byKey('comparison-canvas-edit')!);
    await until(
      () =>
          byKey('comparison-canvas-edit-gesture') != null &&
          (byKey('comparison-canvas-done')!.widget as TextButton).onPressed !=
              null,
      'Restored canvas edit not ready',
    );
    require(
      gateway.lastSaved?.canvasLayout?.target.x == saved.target.x,
      'Restored project lost edited layout',
    );
    await tap(byKey('comparison-canvas-done')!);
    await until(() => byText('退出画布') != null, 'Restored edit did not finish');
    await until(
      () =>
          (byKey('comparison-cutout-repair')?.widget as IconButton?)
              ?.onPressed !=
          null,
      'Repair button not ready',
    );
    // Native viewport is frozen at the pre-repair canvas geometry, just
    // as it is when first entering person selection.
    final repairStage = box(byKey('comparison-stage')!);
    final repairRatio = math.min(
      dpr,
      1920 / math.max(repairStage.size.width, repairStage.size.height),
    );
    final repairW = (repairStage.size.width * repairRatio).round(),
        repairH = (repairStage.size.height * repairRatio).round();
    await tap(byKey('comparison-cutout-repair')!);
    await until(
      () => byKey('comparison-repair-enter') != null,
      'Repair range dialog absent',
    );
    await Future<void>.delayed(const Duration(milliseconds: 300));
    await tap(byKey('comparison-repair-enter')!);
    await until(
      () => byKey('comparison-cutout-gesture') != null,
      'Repair selection absent',
    );
    await Future<void>.delayed(const Duration(milliseconds: 350));
    state = await api.getState();
    await drag(true, repairW, repairH, state.teacherAspect!, .355, .855, false);
    await until(
      () =>
          (byKey('comparison-repair-apply')?.widget as FilledButton?)
              ?.onPressed !=
          null,
      'Repair selection not confirmed',
    );
    await tap(byKey('comparison-repair-apply')!);
    await until(
      () =>
          byKey('comparison-cutout-timeline') != null &&
          byKey('comparison-cutout-gesture') == null &&
          (byKey('comparison-canvas-edit')?.widget as IconButton?)?.onPressed !=
              null,
      'Applied repair did not return to canvas',
      seconds: 60,
    );
    report['repairedViaGesture'] = true;
    report['repairedPixels'] = await capture(folder, 'gesture-repaired');
    await tap(byKey('comparison-cutout-repair')!);
    await until(
      () => byKey('comparison-repair-enter') != null,
      'Cancel repair range dialog absent',
    );
    await Future<void>.delayed(const Duration(milliseconds: 300));
    await tap(byKey('comparison-repair-enter')!);
    await until(
      () => byText('取消纠错') != null && byKey('comparison-repair-enter') == null,
      'Cancel repair selection absent',
    );
    // The dialog's reverse route transition can still intercept a tap
    // during the first selection frame. Wait for its removal and layout.
    await Future<void>.delayed(const Duration(milliseconds: 350));
    await tap(byKey('comparison-cutout-cancel')!);
    await until(
      () =>
          byKey('comparison-cutout-timeline') != null &&
          byKey('comparison-cutout-gesture') == null,
      'Cancelled repair did not restore canvas',
    );
    report['cancelledRepairViaGesture'] = true;
    await tap(byText('退出画布')!);
    await until(
      () => byKey('comparison-picture-pane-teacher') != null,
      'Restored exit failed',
    );
    report['restoredViaMenu'] = true;
    await Future<void>.delayed(const Duration(milliseconds: 500));
    await tap(byKey('comparison-view')!);
    await until(() => byText('重新选人（清除缓存）') != null, 'Reselect menu missing');
    await Future<void>.delayed(const Duration(milliseconds: 250));
    await tap(byText('重新选人（清除缓存）')!);
    await until(
      () => byKey('comparison-cutout-gesture') != null,
      'Reselect did not reset canvas',
    );
    report['reselectViaMenu'] = true;
    await api.clearCutout();
    report['passed'] = true;
  } catch (error) {
    report['passed'] = false;
    report['error'] = '$error';
  } finally {
    file.writeAsStringSync(jsonEncode(report), flush: true);
  }
}
