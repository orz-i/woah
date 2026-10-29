import 'dart:async';
import 'dart:math' as math;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:go_router/go_router.dart';
import 'package:video_player/video_player.dart';
import 'package:dance_domain/dance_domain.dart';
import '../../../app/theme.dart';
import '../data/comparison_gateway.dart';
import 'comparison_controller.dart';
import 'comparison_manual_session.dart';
import 'comparison_manual_view.dart';
import 'comparison_picture_controls.dart';
import 'comparison_markers_sheet.dart';
import 'comparison_timeline.dart';
import 'comparison_export_dialog.dart';

enum _ComparisonProjectAction { create, save, swap, clearLoop, export }

enum _ComparisonViewAction {
  stacked,
  sideBySide,
  mirrorTeacher,
  mirrorLearner,
  adjust,
  rotate,
}

class ComparisonScreen extends StatefulWidget {
  final ComparisonGateway? gateway;
  final VideoPlayerController Function(String)? manualVideoFactory;
  const ComparisonScreen({super.key, this.gateway, this.manualVideoFactory});
  @override
  State<ComparisonScreen> createState() => _ComparisonScreenState();
}

class _ComparisonScreenState extends State<ComparisonScreen>
    with WidgetsBindingObserver {
  late final ComparisonController controller;
  bool _allowPop = false, _exiting = false;
  double? _scrub;
  ComparisonManualSession? _manualSession;
  bool _finishingManual = false, _orientationOverridden = false;
  bool _rotating = false;
  bool _pictureReference = true, _finishingPicture = false;
  ComparisonPicture? _gesturePicture;
  Offset? _gestureFocal;
  bool? _gestureReference;
  double? _referenceAspect, _targetAspect;
  static const _viewTriggerWidth = 104.0, _viewMenuWidth = 200.0;
  static const _orientationChannel = MethodChannel(
    'art.gaoge.dance/orientation',
  );
  DeviceOrientation? _returnOrientation;
  int? _returnRotation;
  Future<void>? _restoringOrientation;
  bool get _landscape =>
      MediaQuery.orientationOf(context) == Orientation.landscape;
  bool get _sideBySide => controller.project.sideBySide ?? _landscape;
  @override
  void initState() {
    super.initState();
    controller = ComparisonController(
      widget.gateway ?? NativeComparisonGateway(),
    );
    WidgetsBinding.instance.addObserver(this);
    unawaited(_initialize());
  }

  Future<void> _initialize() async {
    await controller.initialize();
    final job = controller.pendingExport;
    if (!mounted || job == null) return;
    try {
      await showDialog<void>(
        context: context,
        barrierDismissible: false,
        builder: (_) => ComparisonExportDialog(
          gateway: controller.gateway,
          full: null,
          selection: null,
          existingJob: job,
        ),
      );
    } finally {
      if (mounted) await controller.finishExport();
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.inactive ||
        state == AppLifecycleState.paused) {
      if (_manualSession != null) unawaited(_manualSession!.pause());
      unawaited(controller.background());
    }
  }

  Future<void> _exit() async {
    if (_finishingManual || _finishingPicture) return;
    if (controller.adjusting) {
      await _finishPicture(false);
      return;
    }
    if (_manualSession != null) {
      await _finishManual(false);
      return;
    }
    if (_exiting) return;
    _exiting = true;
    final saved = await controller.finish();
    if (!mounted) return;
    if (!saved) {
      _exiting = false;
      return;
    }
    await _restoreOrientation();
    if (!mounted) return;
    setState(() => _allowPop = true);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) context.pop();
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    if (_orientationOverridden) unawaited(_restoreOrientation());
    if (_manualSession != null) unawaited(_manualSession!.close());
    controller.dispose();
    super.dispose();
  }

  Future<void> _export() async {
    final plans = await controller.prepareExport();
    if (!mounted || plans == null) return;
    try {
      await showDialog<void>(
        context: context,
        barrierDismissible: false,
        builder: (_) => ComparisonExportDialog(
          gateway: controller.gateway,
          full: plans.$1,
          selection: plans.$2,
        ),
      );
    } finally {
      if (mounted) await controller.finishExport();
    }
  }

  Future<void> _save() async {
    var title = controller.project.title;
    final result = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('保存对比项目'),
        content: TextFormField(
          initialValue: title,
          maxLength: 80,
          decoration: const InputDecoration(labelText: '项目名称'),
          onChanged: (value) => title = value,
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, title),
            child: const Text('保存'),
          ),
        ],
      ),
    );
    if (result != null) await controller.save(title: result);
  }

  Future<void> _history() async {
    await controller.pause();
    if (!mounted) return;
    await showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      builder: (context) => SafeArea(
        child: ListView(
          shrinkWrap: true,
          children: [
            const ListTile(
              title: Text('对比历史'),
              subtitle: Text('视频位置和音乐校准会自动保存'),
            ),
            if (controller.projects.isEmpty)
              const ListTile(title: Text('还没有对比历史')),
            for (final project in controller.projects)
              ListTile(
                leading: const Icon(Icons.video_library_outlined),
                title: Text(project.title),
                subtitle: Text(
                  '${project.teacher?.name ?? '待选参考'} · ${project.learner?.name ?? '待选目标'}',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
                onTap: () {
                  Navigator.pop(context);
                  unawaited(controller.selectProject(project));
                },
              ),
          ],
        ),
      ),
    );
  }

  Future<void> _manual() async {
    if (_manualSession != null || !await controller.enterManualCalibration()) {
      return;
    }
    if (!mounted || _manualSession != null || !controller.calibrating) return;
    final session = ComparisonManualSession(
      controller.project,
      factory: widget.manualVideoFactory,
    );
    setState(() => _manualSession = session);
    unawaited(session.initialize());
  }

  Future<void> _adjustPicture() async {
    _referenceAspect = controller.playback?.teacherAspect;
    _targetAspect = controller.playback?.learnerAspect;
    if (!await controller.beginPictureAdjustment() || !mounted) return;
    setState(() => _pictureReference = true);
  }

  Future<void> _finishPicture(bool apply) async {
    if (_finishingPicture) return;
    setState(() => _finishingPicture = true);
    await controller.finishPictureAdjustment(applied: apply);
    if (mounted) setState(() => _finishingPicture = false);
  }

  Future<void> _markers() async {
    await controller.pause();
    await controller.save();
    if (!mounted) return;
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      builder: (_) => FractionallySizedBox(
        heightFactor: .78,
        child: ComparisonMarkersSheet(controller: controller),
      ),
    );
  }

  void _startPictureGesture(bool reference, ScaleStartDetails details) {
    if (!controller.adjusting || _finishingPicture) return;
    setState(() => _pictureReference = reference);
    _gestureReference = reference;
    _gesturePicture = reference
        ? controller.project.teacherPicture
        : controller.project.learnerPicture;
    _gestureFocal = details.localFocalPoint;
  }

  void _updatePictureGesture(
    bool reference,
    ScaleUpdateDetails details,
    Size size,
  ) {
    final initial = _gesturePicture, focal = _gestureFocal;
    if (!controller.adjusting ||
        _finishingPicture ||
        initial == null ||
        focal == null ||
        _gestureReference != reference) {
      return;
    }
    final zoom = (initial.zoom * details.scale).clamp(1.0, 4.0).toDouble();
    if (zoom == 1) {
      controller.previewPicture(reference, const ComparisonPicture());
      return;
    }
    final center = Offset(size.width / 2, size.height / 2);
    final aspect =
        (reference ? _referenceAspect : _targetAspect) ??
        size.width / size.height;
    final fit = Size(
      math.min(size.width, size.height * aspect),
      math.min(size.height, size.width / aspect),
    );
    Offset travel(double z) => z == 1
        ? Offset.zero
        : Offset(
            (fit.width * z - size.width).abs() / 2,
            (fit.height * z - size.height).abs() / 2,
          );
    final initialTravel = travel(initial.zoom), nextTravel = travel(zoom);
    final translation = Offset(
      initial.panX * initialTravel.dx,
      initial.panY * initialTravel.dy,
    );
    final next =
        details.localFocalPoint -
        center -
        (focal - center - translation) * (zoom / initial.zoom);
    controller.previewPicture(
      reference,
      ComparisonPicture.clamped(
        zoom,
        nextTravel.dx < .001 ? 0 : next.dx / nextTravel.dx,
        nextTravel.dy < .001 ? 0 : next.dy / nextTravel.dy,
      ),
    );
  }

  Widget _mainControls() => controller.adjusting
      ? ComparisonPictureControls(
          project: controller.project,
          reference: _pictureReference,
          saving: _finishingPicture,
          error: controller.error,
          onSelect: (value) => setState(() => _pictureReference = value),
          onChange: (value) =>
              controller.previewPicture(_pictureReference, value),
          onApply: () => _finishPicture(true),
          onCancel: () => _finishPicture(false),
        )
      : _controls();

  Future<void> _finishManual(bool apply) async {
    final session = _manualSession;
    if (session == null || _finishingManual) return;
    setState(() => _finishingManual = true);
    try {
      if (apply) {
        final anchors = await session.anchors();
        if (!mounted) return;
        final first = session.rateCalibration ? session.firstAnchor : anchors;
        if (first == null) throw const FormatException('请先记录前段音乐点');
        if (!await controller.manual(
          first.$1,
          first.$2,
          teacher2Us: session.rateCalibration ? anchors.$1 : null,
          learner2Us: session.rateCalibration ? anchors.$2 : null,
        )) {
          return;
        }
      }
      // Remove both preview Texture widgets and their listeners before releasing
      // their producers. The shared GL surface is opened only after that frame.
      setState(() => _manualSession = null);
      await WidgetsBinding.instance.endOfFrame;
      await session.close();
      if (!mounted) return;
      await controller.leaveManualCalibration(applied: apply);
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              error is FormatException ? error.message : '校准未完成，请重试。',
            ),
          ),
        );
      }
    } finally {
      if (mounted) setState(() => _finishingManual = false);
    }
  }

  Future<void> _rotate() async {
    if (_rotating || _finishingManual) return;
    final landscape = _landscape;
    setState(() => _rotating = true);
    try {
      if (_returnOrientation == null) {
        try {
          _returnRotation = await _orientationChannel.invokeMethod<int>(
            'getRotation',
          );
        } on MissingPluginException {
          // Other platforms can still restore the entry orientation class.
        }
        if (!mounted) return;
        final reversed = (_returnRotation ?? 0) >= 2;
        _returnOrientation = landscape
            ? (reversed
                  ? DeviceOrientation.landscapeRight
                  : DeviceOrientation.landscapeLeft)
            : (reversed
                  ? DeviceOrientation.portraitDown
                  : DeviceOrientation.portraitUp);
      }
      _orientationOverridden = true;
      await SystemChrome.setPreferredOrientations(
        landscape
            ? [DeviceOrientation.portraitUp]
            : [DeviceOrientation.landscapeLeft],
      );
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(const SnackBar(content: Text('屏幕方向切换未完成，请重试。')));
      }
    } finally {
      if (mounted) setState(() => _rotating = false);
    }
  }

  Future<void> _restoreOrientation() {
    if (!_orientationOverridden) return Future.value();
    return _restoringOrientation ??= () async {
      try {
        if (_returnOrientation != null) {
          // Returning directly to OS policy can retain the forced angle on the
          // current Android device. Restore its entry angle first, then release.
          await SystemChrome.setPreferredOrientations([_returnOrientation!]);
          if (_returnRotation != null) {
            try {
              await _orientationChannel.invokeMethod<void>(
                'waitForRotation',
                _returnRotation,
              );
            } on PlatformException {
              // Release the override even when a device refuses rotation.
            } on MissingPluginException {
              // The platform may not expose an angle acknowledgement.
            }
          }
        }
      } finally {
        await SystemChrome.setPreferredOrientations([]);
        _orientationOverridden = false;
      }
    }();
  }

  Widget _viewMenu() => PopupMenuButton<_ComparisonViewAction>(
    key: const ValueKey('comparison-view'),
    tooltip: '视图',
    constraints: const BoxConstraints.tightFor(width: _viewMenuWidth),
    offset: const Offset((_viewTriggerWidth - _viewMenuWidth) / 2, 0),
    enabled:
        controller.initialized &&
        !controller.picking &&
        !controller.matching &&
        !controller.stepping &&
        !controller.adjusting &&
        !controller.enteringPractice &&
        !_finishingManual &&
        !_rotating,
    child: SizedBox(
      width: _viewTriggerWidth,
      height: 48,
      child: Center(
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              _sideBySide ? '左右' : '上下',
              style: const TextStyle(
                color: AppTheme.textPrimary,
                fontSize: 13.5,
                fontWeight: FontWeight.w500,
              ),
            ),
            const SizedBox(width: 1),
            const Icon(
              Icons.arrow_drop_down_rounded,
              color: AppTheme.textSecondary,
              size: 18,
            ),
          ],
        ),
      ),
    ),
    onSelected: (action) {
      switch (action) {
        case _ComparisonViewAction.stacked:
          unawaited(controller.setSideBySide(false));
        case _ComparisonViewAction.sideBySide:
          unawaited(controller.setSideBySide(true));
        case _ComparisonViewAction.mirrorTeacher:
          unawaited(controller.setMirror(true));
        case _ComparisonViewAction.mirrorLearner:
          unawaited(controller.setMirror(false));
        case _ComparisonViewAction.adjust:
          unawaited(_adjustPicture());
        case _ComparisonViewAction.rotate:
          unawaited(_rotate());
      }
    },
    itemBuilder: (_) => [
      CheckedPopupMenuItem(
        value: _ComparisonViewAction.stacked,
        checked: !_sideBySide,
        child: const Text('上下视图'),
      ),
      CheckedPopupMenuItem(
        value: _ComparisonViewAction.sideBySide,
        checked: _sideBySide,
        child: const Text('左右视图'),
      ),
      const PopupMenuDivider(),
      CheckedPopupMenuItem(
        value: _ComparisonViewAction.mirrorTeacher,
        checked: controller.project.mirrorTeacher,
        enabled: controller.project.teacher != null,
        child: const Text('参考镜像'),
      ),
      CheckedPopupMenuItem(
        value: _ComparisonViewAction.mirrorLearner,
        checked: controller.project.mirrorLearner,
        enabled: controller.project.learner != null,
        child: const Text('目标镜像'),
      ),
      const PopupMenuDivider(),
      PopupMenuItem(
        value: _ComparisonViewAction.adjust,
        enabled: controller.canPlay && _manualSession == null,
        child: const Text('调整画面'),
      ),
      PopupMenuItem(
        value: _ComparisonViewAction.rotate,
        child: ListTile(
          contentPadding: EdgeInsets.zero,
          leading: Icon(
            _landscape
                ? Icons.stay_current_portrait
                : Icons.stay_current_landscape,
          ),
          title: Text(_landscape ? '竖屏显示' : '横屏显示'),
        ),
      ),
    ],
  );

  @override
  Widget build(BuildContext context) => PopScope(
    canPop: _allowPop,
    onPopInvokedWithResult: (popped, _) {
      if (!popped) unawaited(_exit());
    },
    child: AnimatedBuilder(
      animation: controller,
      builder: (context, _) => Scaffold(
        resizeToAvoidBottomInset: false,
        backgroundColor: AppTheme.flowBackground,
        appBar: AppBar(
          title: _viewMenu(),
          leading: BackButton(onPressed: _exit),
          centerTitle: true,
          titleSpacing: 0,
          actions: [
            if (_manualSession == null) ...[
              IconButton(
                key: const ValueKey('comparison-history'),
                tooltip: '历史',
                onPressed: controller.busy ? null : _history,
                icon: const Icon(Icons.history_rounded, size: 20),
              ),
              PopupMenuButton<_ComparisonProjectAction>(
                key: const ValueKey('comparison-project-menu'),
                tooltip: '更多操作',
                enabled: !controller.busy && !controller.saving,
                onSelected: (action) {
                  switch (action) {
                    case _ComparisonProjectAction.export:
                      unawaited(_export());
                    case _ComparisonProjectAction.create:
                      unawaited(controller.newProject());
                    case _ComparisonProjectAction.save:
                      unawaited(_save());
                    case _ComparisonProjectAction.swap:
                      unawaited(controller.swap());
                    case _ComparisonProjectAction.clearLoop:
                      unawaited(controller.clearLoop());
                  }
                },
                itemBuilder: (_) => [
                  PopupMenuItem(
                    value: _ComparisonProjectAction.export,
                    enabled: controller.canPlay,
                    child: const Text('导出对比视频'),
                  ),
                  const PopupMenuItem(
                    value: _ComparisonProjectAction.save,
                    child: Text('保存并重命名'),
                  ),
                  const PopupMenuItem(
                    value: _ComparisonProjectAction.create,
                    child: Text('新建对比'),
                  ),
                  PopupMenuItem(
                    value: _ComparisonProjectAction.swap,
                    enabled: controller.project.hasPair,
                    child: const Text('交换参考和目标'),
                  ),
                  PopupMenuItem(
                    value: _ComparisonProjectAction.clearLoop,
                    enabled:
                        controller.project.loopStartUs != null ||
                        controller.project.loopEndUs != null,
                    child: const Text('清除 A/B 循环'),
                  ),
                ],
              ),
            ],
          ],
        ),
        body: SafeArea(
          child: !controller.initialized
              ? const Center(child: CircularProgressIndicator())
              : _manualSession != null
              ? ComparisonManualView(
                  session: _manualSession!,
                  sideBySide: _sideBySide,
                  mirrorTeacher: controller.project.mirrorTeacher,
                  mirrorLearner: controller.project.mirrorLearner,
                  teacherPicture: controller.project.teacherPicture,
                  learnerPicture: controller.project.learnerPicture,
                  saving: _finishingManual,
                  error: controller.error,
                  onApply: () => _finishManual(true),
                  onCancel: () => _finishManual(false),
                )
              : LayoutBuilder(
                  builder: (context, viewport) {
                    final horizontal = viewport.maxWidth > viewport.maxHeight;
                    if (horizontal) {
                      return Row(
                        children: [
                          Expanded(child: _preview(_sideBySide)),
                          SizedBox(
                            width: math.min(340, viewport.maxWidth * .43),
                            child: SingleChildScrollView(
                              padding: const EdgeInsets.all(8),
                              child: _mainControls(),
                            ),
                          ),
                        ],
                      );
                    }
                    return Column(
                      children: [
                        Expanded(child: _preview(_sideBySide)),
                        ConstrainedBox(
                          constraints: BoxConstraints(
                            maxHeight: viewport.maxHeight * .46,
                          ),
                          child: SingleChildScrollView(child: _mainControls()),
                        ),
                      ],
                    );
                  },
                ),
        ),
      ),
    ),
  );
  Widget _sourceSlot(String role, String label, ComparisonSource? source) {
    final reference = role == 'teacher';
    final adjusting = controller.adjusting;
    final VoidCallback? onSelect = adjusting
        ? (_finishingPicture
              ? null
              : () => setState(() => _pictureReference = reference))
        : (controller.busy ? null : () => unawaited(controller.pick(role)));
    return LayoutBuilder(
      builder: (context, bounds) => GestureDetector(
        key: ValueKey('comparison-picture-pane-$role'),
        behavior: HitTestBehavior.opaque,
        excludeFromSemantics: true,
        onScaleStart: adjusting && !_finishingPicture
            ? (details) => _startPictureGesture(reference, details)
            : null,
        onScaleUpdate: adjusting && !_finishingPicture
            ? (details) =>
                  _updatePictureGesture(reference, details, bounds.biggest)
            : null,
        child: Semantics(
          key: ValueKey('comparison-pick-$role'),
          label: adjusting
              ? '调整$label画面'
              : '${source == null ? '选择' : '更换'}$label视频',
          button: true,
          enabled: onSelect != null,
          onTap: onSelect,
          excludeSemantics: true,
          child: Material(
            type: MaterialType.transparency,
            child: InkWell(
              onTap: onSelect,
              excludeFromSemantics: true,
              child: Stack(
                fit: StackFit.expand,
                children: [
                  if (adjusting && _pictureReference == reference)
                    Positioned.fill(
                      child: IgnorePointer(
                        child: DecoratedBox(
                          decoration: BoxDecoration(
                            border: Border.all(
                              color: AppTheme.gold,
                              width: 1.5,
                            ),
                          ),
                        ),
                      ),
                    ),
                  if (!controller.project.hasPair)
                    Center(
                      child: Padding(
                        padding: const EdgeInsets.all(20),
                        child: Column(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Icon(
                              source == null
                                  ? Icons.add_rounded
                                  : Icons.check_circle_outline_rounded,
                              size: 28,
                              color: AppTheme.flowTextMuted,
                            ),
                            const SizedBox(height: 8),
                            Text(
                              source == null ? '选择$label视频' : '$label视频已选择',
                              textAlign: TextAlign.center,
                              style: const TextStyle(
                                fontSize: 13,
                                color: AppTheme.flowTextSecondary,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                  Positioned(
                    top: 8,
                    left: 8,
                    child: DecoratedBox(
                      decoration: BoxDecoration(
                        color: Colors.black54,
                        borderRadius: BorderRadius.circular(5),
                      ),
                      child: Padding(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 8,
                          vertical: 4,
                        ),
                        child: Text(
                          label,
                          style: const TextStyle(fontSize: 12),
                        ),
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _preview(bool horizontal) => LayoutBuilder(
    builder: (context, viewport) {
      final ratio = math.min(
        MediaQuery.devicePixelRatioOf(context),
        1920 / math.max(viewport.maxWidth, viewport.maxHeight),
      );
      final width = math.max(2, (viewport.maxWidth * ratio).round());
      final height = math.max(2, (viewport.maxHeight * ratio).round());
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) controller.setViewport(width, height, horizontal);
      });
      final state = controller.playback;
      return Container(
        key: const ValueKey('comparison-stage'),
        color: Colors.black,
        child: Stack(
          fit: StackFit.expand,
          children: [
            if (state != null && state.textureId >= 0)
              Texture(
                textureId: state.textureId,
                filterQuality: FilterQuality.low,
              ),
            if (controller.project.hasPair &&
                !controller.exporting &&
                (state == null || state.phase == 'opening'))
              const Center(
                child: CircularProgressIndicator(color: AppTheme.gold),
              ),
            Flex(
              direction: horizontal ? Axis.horizontal : Axis.vertical,
              children: [
                Expanded(
                  child: _sourceSlot(
                    'teacher',
                    '参考',
                    controller.project.teacher,
                  ),
                ),
                Expanded(
                  child: _sourceSlot(
                    'learner',
                    '目标',
                    controller.project.learner,
                  ),
                ),
              ],
            ),
          ],
        ),
      );
    },
  );
  String get _offsetLabel =>
      '${controller.project.offsetUs >= 0 ? '+' : ''}${(controller.project.offsetUs / 1e6).toStringAsFixed(3)}s';

  String get _alignmentLabel => switch (controller.project.alignment) {
    'auto' => '自动校准',
    'manual' => '手动校准',
    'candidate' || 'auto_review' => '自动校准 · 可微调',
    _ => '需手动校准',
  };

  Widget _musicSummary() {
    final paired = controller.project.hasPair;
    final aligned = controller.project.alignment != 'unmatched';
    return Row(
      children: [
        Icon(
          Icons.graphic_eq_rounded,
          size: 18,
          color: aligned ? AppTheme.gold : AppTheme.flowTextSecondary,
        ),
        const SizedBox(width: 8),
        Expanded(
          child: Text(
            controller.matching
                ? '正在自动校准…'
                : !paired
                ? '先选择参考和目标视频'
                : _alignmentLabel +
                      (aligned ? ' · $_offsetLabel' : '') +
                      (aligned &&
                              (controller.project.timeScale - 1).abs() > .0001
                          ? ' · 目标音乐 ${(1 / controller.project.timeScale).toStringAsFixed(3)}×'
                          : ''),
            maxLines: 2,
            style: const TextStyle(
              fontSize: 13,
              color: AppTheme.flowTextPrimary,
            ),
          ),
        ),
        if (controller.matching)
          TextButton(
            onPressed: controller.cancelMatching,
            child: const Text('取消'),
          )
        else
          TextButton(
            key: const ValueKey('comparison-calibration'),
            onPressed: paired && !controller.busy ? _manual : null,
            child: const Text('手动校准'),
          ),
      ],
    );
  }

  Widget _stepButton(bool forward, int stepUs, int positionUs) => Tooltip(
    message: '${forward ? '前进' : '后退'}${stepUs ~/ 1000000}秒',
    child: TextButton(
      key: ValueKey(
        forward ? 'comparison-step-forward' : 'comparison-step-back',
      ),
      onPressed: controller.canPlay
          ? () => controller.seek(
              math.max(0, positionUs + (forward ? stepUs : -stepUs)),
            )
          : null,
      style: TextButton.styleFrom(
        minimumSize: const Size(48, 56),
        padding: const EdgeInsets.symmetric(horizontal: 6),
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(
            forward ? Icons.forward_rounded : Icons.replay_rounded,
            size: 22,
          ),
          Text('${stepUs ~/ 1000000}秒', style: const TextStyle(fontSize: 11)),
        ],
      ),
    ),
  );

  Widget _playButton(bool ended) => FilledButton.icon(
    key: const ValueKey('comparison-play'),
    onPressed: controller.canPlay ? controller.toggle : null,
    style: FilledButton.styleFrom(
      backgroundColor: AppTheme.gold,
      foregroundColor: AppTheme.textOnAccent,
      minimumSize: const Size(48, 56),
      padding: const EdgeInsets.symmetric(horizontal: 12),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
    ),
    icon: Icon(
      controller.playing
          ? Icons.pause_rounded
          : ended
          ? Icons.replay_rounded
          : Icons.play_arrow_rounded,
      size: 26,
    ),
    label: Text(
      controller.playing
          ? '暂停'
          : ended
          ? '重播'
          : '播放',
    ),
  );

  Widget _speedControl({bool wide = false}) {
    final value = Text(
      '${controller.project.speed.toString().replaceAll('.0', '')}×',
      maxLines: 1,
      style: const TextStyle(
        fontSize: 14,
        fontWeight: FontWeight.w600,
        height: 1.2,
      ),
    );
    const label = Text(
      '倍速',
      style: TextStyle(
        fontSize: 11,
        color: AppTheme.flowTextSecondary,
        height: 1.3,
      ),
    );
    return PopupMenuButton<double>(
      key: const ValueKey('comparison-speed'),
      tooltip: '播放速度',
      enabled: !controller.busy,
      onSelected: controller.setSpeed,
      itemBuilder: (_) => const [
        PopupMenuItem(value: 1, child: Text('1× · 正常')),
        PopupMenuItem(value: .75, child: Text('0.75×')),
        PopupMenuItem(value: .5, child: Text('0.5×')),
      ],
      child: Container(
        width: wide ? double.infinity : 64,
        constraints: const BoxConstraints(minHeight: 56),
        alignment: Alignment.center,
        child: wide
            ? Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [value, const SizedBox(width: 8), label],
              )
            : Column(mainAxisSize: MainAxisSize.min, children: [value, label]),
      ),
    );
  }

  Widget _controls() {
    final state = controller.playback;
    final duration = state?.durationUs ?? 0;
    final position =
        (_scrub ??
                (state?.positionUs.toDouble() ??
                    controller.project.positionUs.toDouble()))
            .clamp(0.0, math.max(1, duration).toDouble());
    final stepUs = duration <= 30000000 ? 1000000 : 5000000;
    final ended = duration > 0 && position >= duration - 100000;
    return DecoratedBox(
      key: const ValueKey('comparison-console'),
      decoration: const BoxDecoration(
        color: AppTheme.flowSurface,
        border: Border(top: BorderSide(color: AppTheme.flowBorder)),
      ),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(12, 0, 12, 8),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            if (controller.error != null)
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: Text(
                  controller.error!,
                  style: const TextStyle(fontSize: 13, color: AppTheme.error),
                ),
              ),
            _musicSummary(),
            ComparisonTimeline(
              durationUs: duration,
              positionUs: position.round(),
              startUs: controller.project.loopStartUs,
              endUs: controller.project.loopEndUs,
              loopEnabled: controller.project.loopEnabled,
              enabled: controller.canPlay,
              onScrub: (value) => setState(() => _scrub = value?.toDouble()),
              onSeek: (value) => unawaited(controller.seek(value)),
              onRangeStart: controller.pause,
              onRange: controller.setLoopRange,
            ),
            _practiceControls(),
            Row(
              children: [
                _stepButton(false, stepUs, position.round()),
                const SizedBox(width: 8),
                Expanded(child: _playButton(ended)),
                const SizedBox(width: 8),
                _stepButton(true, stepUs, position.round()),
                if (MediaQuery.textScalerOf(context).scale(14) <= 19) ...[
                  const SizedBox(width: 8),
                  _speedControl(),
                ],
              ],
            ),
            if (MediaQuery.textScalerOf(context).scale(14) > 19)
              _speedControl(wide: true),
          ],
        ),
      ),
    );
  }

  Widget _practiceControls() {
    final project = controller.project;
    final actions = <Widget>[
      IconButton(
        key: const ValueKey('comparison-frame-back'),
        tooltip: '参考上一帧',
        onPressed: controller.canStep
            ? () => controller.stepFrame(false)
            : null,
        icon: const Icon(Icons.skip_previous_rounded),
      ),
      IconButton(
        key: const ValueKey('comparison-loop-toggle'),
        tooltip: project.loopEnabled ? '暂停 A/B 循环' : '启用 A/B 循环',
        isSelected: project.loopEnabled,
        color: project.loopEnabled ? AppTheme.gold : AppTheme.flowTextSecondary,
        onPressed:
            !controller.busy &&
                project.loopStartUs != null &&
                project.loopEndUs != null
            ? controller.toggleLoop
            : null,
        icon: const Icon(Icons.repeat_rounded),
      ),
      IconButton(
        key: const ValueKey('comparison-frame-forward'),
        tooltip: '参考下一帧',
        onPressed: controller.canStep ? () => controller.stepFrame(true) : null,
        icon: const Icon(Icons.skip_next_rounded),
      ),
      IconButton(
        key: const ValueKey('comparison-markers'),
        tooltip: '问题片段',
        onPressed: controller.busy ? null : _markers,
        icon: Badge(
          isLabelVisible: project.markers.isNotEmpty,
          label: ExcludeSemantics(child: Text('${project.markers.length}')),
          child: const Icon(
            Icons.bookmark_outline_rounded,
            semanticLabel: '问题片段',
          ),
        ),
      ),
    ];
    return LayoutBuilder(
      key: const ValueKey('comparison-practice'),
      builder: (context, bounds) => bounds.maxWidth >= 192
          ? Row(
              children: [
                actions[0],
                const Spacer(),
                actions[1],
                actions[3],
                const Spacer(),
                actions[2],
              ],
            )
          : Wrap(
              alignment: WrapAlignment.spaceBetween,
              children: [actions[0], actions[1], actions[3], actions[2]],
            ),
    );
  }
}
