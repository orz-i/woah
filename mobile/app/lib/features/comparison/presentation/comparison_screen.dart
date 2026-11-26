import 'dart:async';
import 'dart:math' as math;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:go_router/go_router.dart';
import 'package:video_player/video_player.dart';
import 'package:dance_domain/dance_domain.dart';
import 'package:dance_native/dance_native.dart';
import '../../../app/system_ui.dart';
import '../../../app/theme.dart';
import '../data/comparison_gateway.dart';
import 'comparison_controller.dart';
import 'comparison_cutout_gesture.dart';
import 'comparison_manual_session.dart';
import 'comparison_manual_view.dart';
import 'comparison_picture_controls.dart';
import 'comparison_markers_sheet.dart';
import 'comparison_timeline.dart';
import 'comparison_time.dart';
import 'comparison_export_dialog.dart';

enum _ComparisonProjectAction { create, save, swap, clearLoop, export }

enum _ComparisonViewAction {
  stacked,
  sideBySide,
  mirrorTeacher,
  mirrorLearner,
  adjust,
  rotate,
  cutout,
  reselectCutout,
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
  List<ComparisonMusicMapping>? _reviewOptions;
  ComparisonMusicMapping? _reviewSelection;
  bool _finishingManual = false, _orientationOverridden = false;
  bool _rotating = false;
  bool _pictureReference = true, _finishingPicture = false;
  ComparisonPicture? _gesturePicture;
  Offset? _gestureFocal;
  bool? _gestureReference;
  double? _referenceAspect, _targetAspect;
  ComparisonCutoutSeedDto? _cutoutReference, _cutoutTarget, _dragSeed;
  ComparisonCutoutPreviewDto? _referenceStill, _targetStill, _dragStill;
  bool _dropRequested = false;
  int _dragRevision = 0;
  Offset? _cutoutDragStart, _cutoutDragPosition;
  bool? _cutoutDragReference;
  bool _cutoutStarting = false;
  bool _canvasReference = true;
  bool _canvasEntering = false;
  ComparisonCanvasLayout? _canvasDraft;
  ComparisonCanvasLayer? _canvasGestureStart;
  Offset? _canvasFocal;

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
  Color get _canvasColor => Theme.of(context).brightness == Brightness.dark
      ? Colors.black
      : AppTheme.of(context).background;
  SystemUiOverlayStyle get _systemUiStyle =>
      appSystemUiStyleFor(Theme.of(context).brightness).copyWith(
        statusBarColor: _canvasColor,
        systemNavigationBarColor: _canvasColor,
      );
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
      if (controller.cutoutSelecting) setState(_resetCutoutSeeds);
      unawaited(controller.background());
    }
  }

  Future<void> _exit() async {
    if (_finishingManual || _finishingPicture) return;
    if (controller.cutoutMode) {
      if (controller.exporting) return;
      if (controller.cutoutApplying || _canvasEntering) return;
      if (controller.cutoutEditing) {
        _finishCanvasEditing();
        return;
      }
      await _exitCutout();
      return;
    }
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
    _resetCutoutSeeds();
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
            const ListTile(title: Text('对比历史')),
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

  Future<void> _manual({bool review = false}) async {
    final options = controller.alignmentOptions;
    if (review && options.isEmpty) return;
    if (_manualSession != null || !await controller.enterManualCalibration()) {
      return;
    }
    if (!mounted || _manualSession != null || !controller.calibrating) return;
    if (review && !identical(options, controller.alignmentOptions)) {
      await controller.leaveManualCalibration(applied: false);
      return;
    }
    final session = ComparisonManualSession(
      controller.project,
      factory: widget.manualVideoFactory,
    );
    setState(() {
      _manualSession = session;
      _reviewOptions = review ? options : null;
      _reviewSelection = null;
    });
    unawaited(session.initialize());
  }

  Future<void> _cueCandidate(ComparisonMusicMapping mapping) async {
    final session = _manualSession;
    final options = _reviewOptions;
    if (session == null ||
        options == null ||
        session.anchoring ||
        _finishingManual) {
      return;
    }
    setState(() => _reviewSelection = null);
    if (await session.cueCandidate(mapping, options) &&
        mounted &&
        identical(session, _manualSession)) {
      setState(() => _reviewSelection = mapping);
    }
  }

  Widget _reviewControls(BuildContext context) {
    final options = _reviewOptions!;
    final session = _manualSession!;
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text('选择候选后，先分别播放参考和目标。确认是同一拍后再采用。'),
        Wrap(
          spacing: 8,
          children: [
            for (var i = 0; i < options.length; i++)
              ChoiceChip(
                key: ValueKey('comparison-candidate-$i'),
                label: Text(
                  '${i + 1} · ${_signedOffset(options[i].offsetUs)}'
                  '${options[i].timeScale == 1 ? '' : ' · ${(1 / options[i].timeScale).toStringAsFixed(3)}×'}',
                ),
                selected: identical(_reviewSelection, options[i]),
                onSelected:
                    session.ready && !session.anchoring && !_finishingManual
                    ? (_) => _cueCandidate(options[i])
                    : null,
              ),
          ],
        ),
      ],
    );
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

  Widget _mainControls() => controller.cutoutEditing
      ? _canvasEditorControls()
      : controller.cutoutMode && !controller.cutoutActive
      ? _cutoutControls()
      : controller.adjusting
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
    if (session == null || _finishingManual || session.anchoring) return;
    setState(() => _finishingManual = true);
    try {
      if (apply && _reviewOptions != null) {
        final candidate = _reviewSelection;
        if (candidate == null || !session.candidateAuditioned) return;
        await session.pause();
        if (!mounted ||
            !session.candidateAuditioned ||
            !await controller.confirmAlignmentCandidate(
              candidate,
              session: session,
            )) {
          return;
        }
      } else if (apply) {
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
      if (!mounted) return;
      setState(() {
        _manualSession = null;
        _reviewOptions = null;
        _reviewSelection = null;
      });
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

  PopupMenuItem<_ComparisonViewAction> _viewOption(
    _ComparisonViewAction action,
    String label,
    IconData icon, {
    bool selected = false,
    bool enabled = true,
  }) {
    final colors = AppTheme.of(context);
    final color = !enabled
        ? colors.textMuted
        : selected
        ? colors.editorSelection
        : colors.textPrimary;
    return PopupMenuItem(
      value: action,
      height: 48,
      enabled: enabled,
      child: Semantics(
        selected: selected,
        child: Row(
          children: [
            Icon(icon, size: 20, color: color),
            const SizedBox(width: 12),
            Flexible(
              child: Text(
                label,
                style: TextStyle(
                  color: color,
                  fontSize: 13,
                  fontWeight: selected ? FontWeight.w600 : FontWeight.w500,
                ),
              ),
            ),
            if (selected) ...[
              const SizedBox(width: 6),
              Icon(
                Icons.check_rounded,
                key: const ValueKey('comparison-view-selected-mark'),
                size: 17,
                color: colors.editorSelection,
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _viewMenu() => PopupMenuButton<_ComparisonViewAction>(
    key: const ValueKey('comparison-view'),
    tooltip: '视图',
    constraints: const BoxConstraints.tightFor(width: _viewMenuWidth),
    offset: const Offset((_viewTriggerWidth - _viewMenuWidth) / 2, 0),
    color: AppTheme.of(context).flowSurface,
    surfaceTintColor: Colors.transparent,
    enabled:
        controller.initialized &&
        !controller.cutoutMode &&
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
              controller.cutoutMode
                  ? '抠像画布'
                  : _sideBySide
                  ? '左右'
                  : '上下',
              style: TextStyle(
                color: AppTheme.of(context).textPrimary,
                fontSize: 13.5,
                fontWeight: FontWeight.w500,
              ),
            ),
            const SizedBox(width: 1),
            Icon(
              Icons.arrow_drop_down_rounded,
              color: AppTheme.of(context).textSecondary,
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
        case _ComparisonViewAction.cutout:
          unawaited(_beginCutout());
        case _ComparisonViewAction.reselectCutout:
          unawaited(_beginCutout(reselect: true));
      }
    },
    itemBuilder: (_) => [
      if (controller.supportsCutout)
        _viewOption(
          _ComparisonViewAction.cutout,
          '抠像画布（实验）',
          Icons.content_cut_rounded,
          enabled: controller.canPlay && _manualSession == null,
        ),
      if (controller.supportsSavedCutout)
        _viewOption(
          _ComparisonViewAction.reselectCutout,
          '重新选人（清除缓存）',
          Icons.person_search_outlined,
          enabled: controller.canPlay && _manualSession == null,
        ),
      _viewOption(
        _ComparisonViewAction.stacked,
        '上下视图',
        Icons.splitscreen_rounded,
        selected: !_sideBySide,
      ),
      _viewOption(
        _ComparisonViewAction.sideBySide,
        '左右视图',
        Icons.vertical_split_rounded,
        selected: _sideBySide,
      ),
      _viewOption(
        _ComparisonViewAction.mirrorTeacher,
        '参考镜像',
        Icons.flip_rounded,
        selected: controller.project.mirrorTeacher,
        enabled: controller.project.teacher != null,
      ),
      _viewOption(
        _ComparisonViewAction.mirrorLearner,
        '目标镜像',
        Icons.flip_rounded,
        selected: controller.project.mirrorLearner,
        enabled: controller.project.learner != null,
      ),
      _viewOption(
        _ComparisonViewAction.adjust,
        '调整画面',
        Icons.crop_free_rounded,
        enabled: controller.canPlay && _manualSession == null,
      ),
      _viewOption(
        _ComparisonViewAction.rotate,
        _landscape ? '竖屏显示' : '横屏显示',
        _landscape
            ? Icons.stay_current_portrait_rounded
            : Icons.stay_current_landscape_rounded,
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
      builder: (context, _) => AnnotatedRegion<SystemUiOverlayStyle>(
        value: _systemUiStyle,
        child: Scaffold(
          resizeToAvoidBottomInset: false,
          backgroundColor: _canvasColor,
          appBar: AppBar(
            backgroundColor: _canvasColor,
            systemOverlayStyle: _systemUiStyle,
            scrolledUnderElevation: 0,
            title: _viewMenu(),
            leading: BackButton(onPressed: _exit),
            centerTitle: true,
            titleSpacing: 0,
            actions: [
              if (_manualSession == null) ...[
                IconButton(
                  key: const ValueKey('comparison-history'),
                  tooltip: '历史',
                  onPressed: controller.busy || controller.cutoutMode
                      ? null
                      : _history,
                  icon: const Icon(Icons.history_rounded, size: 20),
                ),
                PopupMenuButton<_ComparisonProjectAction>(
                  key: const ValueKey('comparison-project-menu'),
                  tooltip: '更多操作',
                  enabled:
                      !controller.busy &&
                      !controller.cutoutMode &&
                      !controller.saving,
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
                    reviewControls: _reviewOptions == null
                        ? null
                        : _reviewControls,
                    candidateSelected: _reviewSelection != null,
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
                            child: SingleChildScrollView(
                              child: _mainControls(),
                            ),
                          ),
                        ],
                      );
                    },
                  ),
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
                              color: AppTheme.of(context).flowTextMuted,
                            ),
                            const SizedBox(height: 8),
                            Text(
                              source == null ? '选择$label视频' : '$label视频已选择',
                              textAlign: TextAlign.center,
                              style: TextStyle(
                                fontSize: 13,
                                color: AppTheme.of(context).flowTextSecondary,
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
                          style: TextStyle(
                            fontSize: 12,
                            color: adjusting && _pictureReference == reference
                                ? AppTheme.of(context).brandAction
                                : Colors.white70,
                            fontWeight:
                                adjusting && _pictureReference == reference
                                ? FontWeight.w600
                                : FontWeight.w400,
                          ),
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

  void _evictStill(ComparisonCutoutPreviewDto? still) {
    if (still != null) unawaited(MemoryImage(still.png).evict());
  }

  void _cancelCutoutDrag() {
    _dragRevision++;
    _evictStill(_dragStill);
    _dragSeed = null;
    _dragStill = null;
    _dropRequested = false;
    _cutoutDragStart = null;
    _cutoutDragPosition = null;
    _cutoutDragReference = null;
  }

  void _resetCutoutSeeds() {
    _cancelCutoutDrag();
    _evictStill(_referenceStill);
    _evictStill(_targetStill);
    _cutoutReference = null;
    _cutoutTarget = null;
    _referenceStill = null;
    _targetStill = null;
  }

  Future<void> _inspectDrag(
    bool reference,
    ComparisonCutoutSeedDto seed,
    int revision,
  ) async {
    final still = await controller.inspectCutout(reference, seed);
    if (!mounted || revision != _dragRevision || !controller.cutoutSelecting) {
      return;
    }
    if (still == null) {
      setState(_cancelCutoutDrag);
      return;
    }
    setState(() => _dragStill = still);
    if (_dropRequested) _commitCutoutDrop();
  }

  void _commitCutoutDrop() {
    final reference = _cutoutDragReference;
    final seed = _dragSeed;
    final still = _dragStill;
    if (reference == null || seed == null || still == null || !_dropRequested) {
      return;
    }
    setState(() {
      // Transfer the still into the central canvas, without evicting it.
      _dragStill = null;
      if (reference) {
        _evictStill(_referenceStill);
        _cutoutReference = seed;
        _referenceStill = still;
      } else {
        _evictStill(_targetStill);
        _cutoutTarget = seed;
        _targetStill = still;
      }
      _cancelCutoutDrag();
    });
    final a = _cutoutReference, b = _cutoutTarget;
    if (a != null && b != null) unawaited(controller.prepareCutout(a, b));
  }

  Widget _subjectStill(
    ComparisonCutoutPreviewDto still,
    bool reference, {
    Key? key,
  }) {
    final mirror = reference
        ? controller.project.mirrorTeacher
        : controller.project.mirrorLearner;
    return Transform.flip(
      flipX: mirror,
      child: Image.memory(
        still.png,
        key: key,
        fit: BoxFit.contain,
        filterQuality: FilterQuality.medium,
        gaplessPlayback: false,
        errorBuilder: (_, _, _) => const SizedBox.shrink(),
      ),
    );
  }

  Future<void> _beginCutout({bool reselect = false}) async {
    setState(() {
      _resetCutoutSeeds();
      _cutoutStarting = true;
    });
    final ready = await controller.beginCutout(reselect: reselect);
    if (!mounted) return;
    setState(() => _cutoutStarting = false);
    if (!ready && controller.error == null) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('请在画面稳定后开启抠像画布。')));
    }
  }

  Future<void> _exitCutout() async {
    if (controller.cutoutApplying || _canvasEntering || controller.exporting) {
      return;
    }
    if (mounted) {
      setState(() {
        _resetCutoutSeeds();
        _canvasDraft = null;
        _canvasGestureStart = null;
        _canvasFocal = null;
      });
    }
    await controller.exitCutout();
  }

  Widget _cutoutControls() {
    final reference = _cutoutReference != null;
    final target = _cutoutTarget != null;
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 8, 12, 12),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(
            controller.cutoutRestoring
                ? '正在检查已保存画布…'
                : controller.cutoutSaving
                ? '正在保存画布缓存…'
                : controller.cutoutPreparing
                ? '正在分析参考与目标人物…'
                : controller.cutoutInspecting
                ? '正在提取所选人物…'
                : '长按人物身体中央，拖入中间的画布。',
            maxLines: 2,
          ),
          const SizedBox(height: 6),
          Text(
            controller.cutoutPracticeRange == null
                ? '分析共同区间；较长视频请先启用 A/B 选段。'
                : '仅分析当前 A/B 选段',
            style: const TextStyle(fontSize: 12),
          ),
          Text(
            '参考：${reference ? '已加入' : '未选择'}   目标：${target ? '已加入' : '未选择'}',
          ),
          SizedBox(
            height: 4,
            child: controller.cutoutPreparing || controller.cutoutInspecting
                ? const LinearProgressIndicator()
                : null,
          ),
          if (controller.error != null)
            Text(
              controller.error!,
              style: TextStyle(color: AppTheme.of(context).error, fontSize: 13),
            ),
          TextButton(
            key: const ValueKey('comparison-cutout-cancel'),
            onPressed: _exitCutout,
            child: const Text('退出抠像画布'),
          ),
        ],
      ),
    );
  }

  Widget _cutoutSourceTile(
    ComparisonCutoutStage stage,
    bool reference,
    int textureId,
  ) {
    final native = stage.nativePane(reference), fit = stage.fitted(reference);
    final sx = fit.width / native.width, sy = fit.height / native.height;
    return Positioned.fromRect(
      rect: fit,
      child: ClipRect(
        child: Stack(
          clipBehavior: Clip.hardEdge,
          children: [
            Positioned(
              left: -native.left * sx,
              top: -native.top * sy,
              width: stage.width * sx,
              height: stage.height * sy,
              child: Texture(
                textureId: textureId,
                filterQuality: FilterQuality.low,
              ),
            ),
            Positioned(
              left: 6,
              top: 6,
              child: DecoratedBox(
                decoration: BoxDecoration(
                  color: Colors.black54,
                  borderRadius: BorderRadius.circular(4),
                ),
                child: Padding(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 6,
                    vertical: 3,
                  ),
                  child: Text(
                    reference ? '参考' : '目标',
                    style: const TextStyle(color: Colors.white, fontSize: 12),
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _cutoutOverlay(Size size) {
    final state = controller.playback;
    final (w, h, nativeHorizontal) = controller.cutoutViewport;
    final expanded = _referenceStill != null || _targetStill != null;
    final stage = ComparisonCutoutStage(size, w, h, nativeHorizontal, expanded);
    final ready =
        !_cutoutStarting &&
        !controller.cutoutPreparing &&
        state?.phase == 'paused' &&
        w > 8 &&
        h > 8;
    final visible = _dragSeed != null || expanded;
    final target = stage.canvas;
    return Stack(
      fit: StackFit.expand,
      children: [
        if (expanded &&
            state != null &&
            state.textureId >= 0 &&
            w > 8 &&
            h > 8) ...[
          _cutoutSourceTile(stage, true, state.textureId),
          _cutoutSourceTile(stage, false, state.textureId),
        ],
        if (visible)
          Positioned.fromRect(
            rect: target,
            child: IgnorePointer(
              child: Container(
                key: const ValueKey('comparison-cutout-drop-canvas'),
                decoration: BoxDecoration(
                  color: expanded ? Colors.black : Colors.black87,
                  border: Border.all(
                    color: AppTheme.of(context).editorSelection,
                  ),
                  borderRadius: BorderRadius.circular(10),
                ),
                padding: const EdgeInsets.all(8),
                child: expanded
                    ? Row(
                        children: [
                          Expanded(
                            child: _referenceStill == null
                                ? const Center(
                                    child: Text(
                                      '参考待选',
                                      style: TextStyle(
                                        color: Colors.white54,
                                        fontSize: 11,
                                      ),
                                    ),
                                  )
                                : _subjectStill(
                                    _referenceStill!,
                                    true,
                                    key: const ValueKey(
                                      'comparison-cutout-reference-still',
                                    ),
                                  ),
                          ),
                          Expanded(
                            child: _targetStill == null
                                ? const Center(
                                    child: Text(
                                      '目标待选',
                                      style: TextStyle(
                                        color: Colors.white54,
                                        fontSize: 11,
                                      ),
                                    ),
                                  )
                                : _subjectStill(
                                    _targetStill!,
                                    false,
                                    key: const ValueKey(
                                      'comparison-cutout-target-still',
                                    ),
                                  ),
                          ),
                        ],
                      )
                    : const Center(
                        child: Text(
                          '拖入人物',
                          style: TextStyle(color: Colors.white, fontSize: 12),
                        ),
                      ),
              ),
            ),
          ),
        if (_cutoutDragPosition case final point?)
          Positioned(
            left: point.dx - 48,
            top: point.dy - 66,
            child: IgnorePointer(
              child: SizedBox(
                width: 96,
                height: 132,
                child: _dragStill != null && _cutoutDragReference != null
                    ? _subjectStill(
                        _dragStill!,
                        _cutoutDragReference!,
                        key: const ValueKey('comparison-cutout-drag-still'),
                      )
                    : const Center(
                        child: SizedBox(
                          width: 24,
                          height: 24,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        ),
                      ),
              ),
            ),
          ),
        GestureDetector(
          key: const ValueKey('comparison-cutout-gesture'),
          behavior: HitTestBehavior.opaque,
          onLongPressStart: !ready
              ? null
              : (details) {
                  if (controller.cutoutInspecting || _dragSeed != null) return;
                  final p = details.localPosition;
                  final reference = nativeHorizontal
                      ? p.dx < size.width / 2
                      : p.dy < size.height / 2;
                  final native = stage.nativePoint(p, reference);
                  if (native == null) return;
                  final aspect = reference
                      ? state!.teacherAspect!
                      : state!.learnerAspect!;
                  final pts = reference
                      ? state.teacherFrameUs
                      : state.learnerFrameUs;
                  final point = comparisonCutoutPoint(
                    viewport: Size(w.toDouble(), h.toDouble()),
                    point: native,
                    width: w,
                    height: h,
                    horizontal: nativeHorizontal,
                    reference: reference,
                    aspect: aspect,
                    picture: reference
                        ? controller.project.teacherPicture
                        : controller.project.learnerPicture,
                    mirrored: reference
                        ? controller.project.mirrorTeacher
                        : controller.project.mirrorLearner,
                  );
                  if (pts == null || point == null) return;
                  final seed = ComparisonCutoutSeedDto(
                    sourcePtsUs: pts,
                    x: point.dx,
                    y: point.dy,
                  );
                  final revision = ++_dragRevision;
                  setState(() {
                    _dragSeed = seed;
                    _cutoutDragStart = p;
                    _cutoutDragPosition = p;
                    _cutoutDragReference = reference;
                    _dropRequested = false;
                    _dragStill = null;
                  });
                  unawaited(_inspectDrag(reference, seed, revision));
                },
          onLongPressMoveUpdate: (details) {
            if (_dragSeed != null && !_dropRequested) {
              setState(() => _cutoutDragPosition = details.localPosition);
            }
          },
          onLongPressCancel: () => setState(_cancelCutoutDrag),
          onLongPressEnd: (details) {
            final seed = _dragSeed,
                start = _cutoutDragStart,
                reference = _cutoutDragReference;
            final accepted =
                seed != null &&
                start != null &&
                reference != null &&
                comparisonCutoutDrop(
                  size,
                  start,
                  details.localPosition,
                  horizontal: nativeHorizontal,
                  reference: reference,
                );
            if (!accepted) {
              setState(_cancelCutoutDrag);
              return;
            }
            setState(() {
              _dropRequested = true;
              _cutoutDragPosition = target.center;
            });
            _commitCutoutDrop();
          },
        ),
      ],
    );
  }

  Future<void> _beginCanvasEditing() async {
    if (_canvasEntering) return;
    setState(() {
      _canvasEntering = true;
      _canvasReference = true;
      _canvasDraft = null;
    });
    await controller.beginCanvasEditing();
    if (mounted) setState(() => _canvasEntering = false);
  }

  bool get _canvasEditEnabled =>
      controller.cutoutEditing &&
      !_canvasEntering &&
      !controller.cutoutApplying &&
      controller.playback?.phase == 'paused';
  ComparisonCanvasLayout get _canvasValue =>
      _canvasDraft ?? controller.canvasLayout;

  Future<void> _applyCanvas(ComparisonCanvasLayout value) async {
    if (!_canvasEditEnabled) return;
    setState(() => _canvasDraft = value);
    await controller.applyCanvasLayout(value);
    if (mounted) setState(() => _canvasDraft = null);
  }

  void _finishCanvasEditing() {
    if (controller.cutoutApplying || _canvasEntering) return;
    setState(() {
      _canvasDraft = null;
      _canvasGestureStart = null;
      _canvasFocal = null;
    });
    controller.finishCanvasEditing();
  }

  void _draftCanvasLayer(ComparisonCanvasLayer layer) {
    if (!_canvasEditEnabled) return;
    setState(
      () => _canvasDraft = _canvasValue.withLayer(_canvasReference, layer),
    );
  }

  Widget _canvasEditorControls() {
    final layout = _canvasValue;
    final layer = layout.layer(_canvasReference);
    final enabled = _canvasEditEnabled;
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 6, 12, 10),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  '编辑画布',
                  style: Theme.of(context).textTheme.titleSmall,
                ),
              ),
              TextButton(
                key: const ValueKey('comparison-canvas-done'),
                onPressed: enabled ? _finishCanvasEditing : null,
                child: const Text('完成'),
              ),
            ],
          ),
          Row(
            children: [
              Expanded(
                child: ChoiceChip(
                  key: const ValueKey('comparison-canvas-reference'),
                  label: const Text('参考人物'),
                  selected: _canvasReference,
                  onSelected: enabled
                      ? (_) => setState(() => _canvasReference = true)
                      : null,
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: ChoiceChip(
                  key: const ValueKey('comparison-canvas-target'),
                  label: const Text('目标人物'),
                  selected: !_canvasReference,
                  onSelected: enabled
                      ? (_) => setState(() => _canvasReference = false)
                      : null,
                ),
              ),
            ],
          ),
          Text(
            '拖动所选人物位置，双指缩放；松手应用。',
            style: Theme.of(context).textTheme.bodySmall,
          ),
          Row(
            children: [
              const Text('高度', style: TextStyle(fontSize: 12)),
              Expanded(
                child: Slider(
                  key: const ValueKey('comparison-canvas-height'),
                  min: .15,
                  max: 1.5,
                  value: layer.height.clamp(.15, 1.5),
                  onChanged: enabled
                      ? (v) => _draftCanvasLayer(layer.copyWith(height: v))
                      : null,
                  onChangeEnd: (v) => unawaited(
                    _applyCanvas(
                      _canvasValue.withLayer(
                        _canvasReference,
                        layer.copyWith(height: v),
                      ),
                    ),
                  ),
                ),
              ),
              SizedBox(
                width: 38,
                child: Text(
                  '${(layer.height * 100).round()}%',
                  style: const TextStyle(fontSize: 12),
                ),
              ),
            ],
          ),
          Row(
            children: [
              const Text('透明度', style: TextStyle(fontSize: 12)),
              Expanded(
                child: Slider(
                  key: const ValueKey('comparison-canvas-opacity'),
                  min: 0,
                  max: 1,
                  value: layer.opacity,
                  onChanged: enabled
                      ? (v) => _draftCanvasLayer(layer.copyWith(opacity: v))
                      : null,
                  onChangeEnd: (v) => unawaited(
                    _applyCanvas(
                      _canvasValue.withLayer(
                        _canvasReference,
                        layer.copyWith(opacity: v),
                      ),
                    ),
                  ),
                ),
              ),
              SizedBox(
                width: 38,
                child: Text(
                  '${(layer.opacity * 100).round()}%',
                  style: const TextStyle(fontSize: 12),
                ),
              ),
            ],
          ),
          Wrap(
            spacing: 6,
            runSpacing: 2,
            children: [
              FilterChip(
                key: const ValueKey('comparison-canvas-mirror'),
                label: const Text('镜像'),
                selected: layer.mirror,
                onSelected: enabled
                    ? (v) => unawaited(
                        _applyCanvas(
                          layout.withLayer(
                            _canvasReference,
                            layer.copyWith(mirror: v),
                          ),
                        ),
                      )
                    : null,
              ),
              ActionChip(
                key: const ValueKey('comparison-canvas-front'),
                label: const Text('置于顶层'),
                onPressed: enabled
                    ? () => unawaited(
                        _applyCanvas(
                          layout.copyWith(targetOnTop: !_canvasReference),
                        ),
                      )
                    : null,
              ),
              ActionChip(
                key: const ValueKey('comparison-canvas-parallel'),
                label: const Text('并排'),
                onPressed: enabled
                    ? () => unawaited(_applyCanvas(layout.resetPlacement()))
                    : null,
              ),
              ActionChip(
                key: const ValueKey('comparison-canvas-overlay'),
                label: const Text('重叠'),
                onPressed: enabled
                    ? () => unawaited(_applyCanvas(layout.overlay()))
                    : null,
              ),
              PopupMenuButton<int>(
                key: const ValueKey('comparison-canvas-background'),
                enabled: enabled,
                tooltip: '画布背景',
                onSelected: (v) =>
                    unawaited(_applyCanvas(layout.copyWith(background: v))),
                itemBuilder: (_) => const [
                  PopupMenuItem(value: 0, child: Text('黑色背景')),
                  PopupMenuItem(value: 1, child: Text('深色背景')),
                  PopupMenuItem(value: 2, child: Text('浅色背景')),
                ],
                child: const Padding(
                  padding: EdgeInsets.all(10),
                  child: Text('背景'),
                ),
              ),
            ],
          ),
          SizedBox(
            height: 4,
            child: controller.cutoutApplying || _canvasEntering
                ? const LinearProgressIndicator()
                : null,
          ),
          if (controller.error != null)
            Text(
              controller.error!,
              style: TextStyle(fontSize: 12, color: AppTheme.of(context).error),
            ),
        ],
      ),
    );
  }

  Widget _canvasEditSurface(Size available, int textureId) {
    final (w, h, _) = controller.cutoutViewport;
    final factor = math.min(
      available.width / math.max(1, w),
      available.height / math.max(1, h),
    );
    final size = Size(w * factor, h * factor);
    final layer = _canvasValue.layer(_canvasReference);
    return Center(
      child: SizedBox.fromSize(
        size: size,
        child: Stack(
          fit: StackFit.expand,
          children: [
            Texture(textureId: textureId, filterQuality: FilterQuality.low),
            if (size.width > 0 && size.height > 0)
              Positioned(
                left: layer.x * size.width - 11,
                top: layer.y * size.height - 11,
                child: IgnorePointer(
                  child: Container(
                    key: const ValueKey('comparison-canvas-anchor'),
                    width: 22,
                    height: 22,
                    decoration: BoxDecoration(
                      color: Colors.black54,
                      shape: BoxShape.circle,
                      border: Border.all(
                        color: AppTheme.of(context).editorSelection,
                        width: 2,
                      ),
                    ),
                    child: const Icon(Icons.add, size: 16, color: Colors.white),
                  ),
                ),
              ),
            GestureDetector(
              key: const ValueKey('comparison-canvas-edit-gesture'),
              behavior: HitTestBehavior.opaque,
              onScaleStart: !_canvasEditEnabled
                  ? null
                  : (details) {
                      _canvasGestureStart = _canvasValue.layer(
                        _canvasReference,
                      );
                      _canvasFocal = details.localFocalPoint;
                    },
              onScaleUpdate: !_canvasEditEnabled
                  ? null
                  : (details) {
                      final base = _canvasGestureStart, origin = _canvasFocal;
                      if (base == null || origin == null || size.isEmpty) {
                        return;
                      }
                      final delta = details.localFocalPoint - origin;
                      _draftCanvasLayer(
                        base.copyWith(
                          x: (base.x + delta.dx / size.width)
                              .clamp(0, 1)
                              .toDouble(),
                          y: (base.y + delta.dy / size.height)
                              .clamp(0, 1)
                              .toDouble(),
                          height: (base.height * details.scale)
                              .clamp(.15, 1.5)
                              .toDouble(),
                        ),
                      );
                    },
              onScaleEnd: !_canvasEditEnabled
                  ? null
                  : (_) {
                      final value = _canvasDraft;
                      _canvasGestureStart = null;
                      _canvasFocal = null;
                      if (value != null) unawaited(_applyCanvas(value));
                    },
            ),
          ],
        ),
      ),
    );
  }

  Widget _cutoutPlaybackInfo() {
    final result = controller.cutoutResult!;
    final partial = controller.cutoutPartial;
    final analyzed =
        (result.referenceAnalyzed ?? result.referenceFrames) +
        (result.targetAnalyzed ?? result.targetFrames);
    final total = result.referenceFrames + result.targetFrames;
    final missing = analyzed - result.referenceVisible - result.targetVisible;
    return Row(
      children: [
        Expanded(
          child: Text(
            controller.cutoutSaving
                ? '正在保存已完成的画布缓存…'
                : partial
                ? '已分析 $analyzed/$total 帧 · 正在分析剩余片段，暂不能导出'
                : '${controller.cutoutRestored
                          ? '已恢复画布'
                          : controller.cutoutArchived
                          ? '已缓存画布'
                          : '实验画布'} · 参考 ${result.referenceVisible}/${result.referenceFrames} · 目标 ${result.targetVisible}/${result.targetFrames}'
                      '${missing > 0 ? ' · 缺失帧已隐藏，暂不能导出' : ''}',
            style: const TextStyle(fontSize: 12),
          ),
        ),
        IconButton(
          key: const ValueKey('comparison-canvas-edit'),
          tooltip: '编辑画布',
          onPressed: controller.canEditCanvas ? _beginCanvasEditing : null,
          icon: const Icon(Icons.tune_rounded),
        ),
        IconButton(
          key: const ValueKey('comparison-cutout-export'),
          tooltip: '导出画布视频',
          onPressed: controller.canExportCanvas ? _export : null,
          icon: const Icon(Icons.ios_share_rounded),
        ),
        TextButton(
          onPressed: controller.exporting ? null : _exitCutout,
          child: const Text('退出画布'),
        ),
      ],
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
      final selecting = controller.cutoutSelecting;
      final expandedSelection =
          selecting && (_referenceStill != null || _targetStill != null);
      final sourceHorizontal = selecting
          ? controller.cutoutViewport.$3
          : horizontal;
      return Container(
        key: const ValueKey('comparison-stage'),
        color: Colors.black,
        child: Stack(
          fit: StackFit.expand,
          children: [
            if (controller.cutoutEditing &&
                state != null &&
                state.textureId >= 0)
              _canvasEditSurface(viewport.biggest, state.textureId)
            else if (state != null &&
                state.textureId >= 0 &&
                !expandedSelection)
              Texture(
                textureId: state.textureId,
                filterQuality: FilterQuality.low,
              ),
            if (controller.project.hasPair &&
                !controller.exporting &&
                (state == null || state.phase == 'opening'))
              Center(
                child: CircularProgressIndicator(
                  color: AppTheme.of(context).brandAction,
                ),
              ),
            if (!controller.cutoutActive && !expandedSelection)
              Flex(
                direction: sourceHorizontal ? Axis.horizontal : Axis.vertical,
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
            if (selecting) _cutoutOverlay(viewport.biggest),
          ],
        ),
      );
    },
  );
  static String _signedOffset(int offsetUs) =>
      '${offsetUs >= 0 ? '+' : ''}${(offsetUs / 1e6).toStringAsFixed(3)}s';

  String get _alignmentLabel => switch (controller.project.alignment) {
    'auto' => '自动校准',
    'manual' => '手动校准',
    'confirmed' => '已确认乐段',
    'candidate' || 'auto_review' => '上次校准 · 待复核',
    _ => '需手动校准',
  };

  Widget _musicSummary() {
    final paired = controller.project.hasPair;
    final aligned = controller.project.alignment != 'unmatched';
    final hasOptions = controller.alignmentOptions.isNotEmpty;
    final legacyReview = const [
      'candidate',
      'auto_review',
    ].contains(controller.project.alignment);
    final retry =
        paired &&
        !hasOptions &&
        controller.project.teacher!.hasAudio &&
        controller.project.learner!.hasAudio &&
        (!aligned || legacyReview || controller.alignmentNotice != null);
    return Row(
      children: [
        Icon(
          Icons.graphic_eq_rounded,
          size: 18,
          color: aligned && !hasOptions && !legacyReview
              ? AppTheme.of(context).editorSelection
              : AppTheme.of(context).flowTextSecondary,
        ),
        const SizedBox(width: 8),
        Expanded(
          child: Text(
            controller.matching
                ? '正在自动校准…'
                : !paired
                ? '先选择参考和目标视频'
                : hasOptions
                ? '音乐位置待确认'
                : _alignmentLabel +
                      (aligned
                          ? ' · ${_signedOffset(controller.project.offsetUs)}'
                          : '') +
                      (aligned &&
                              (controller.project.timeScale - 1).abs() > .0001
                          ? ' · 目标音乐 ${(1 / controller.project.timeScale).toStringAsFixed(3)}×'
                          : ''),
            maxLines: 2,
            style: TextStyle(
              fontSize: 13,
              color: AppTheme.of(context).flowTextPrimary,
            ),
          ),
        ),
        if (controller.matching)
          TextButton(
            onPressed: controller.cancelMatching,
            child: const Text('取消'),
          )
        else ...[
          if (hasOptions)
            IconButton(
              key: const ValueKey('comparison-review-music'),
              tooltip: '试听候选乐段',
              onPressed: controller.busy ? null : () => _manual(review: true),
              icon: const Icon(Icons.headphones_rounded, size: 20),
            )
          else if (retry)
            IconButton(
              tooltip: '重新匹配音乐',
              onPressed: controller.busy ? null : controller.matchMusic,
              icon: const Icon(Icons.refresh_rounded, size: 20),
            ),
          IconButton(
            key: const ValueKey('comparison-calibration'),
            tooltip: '手动校准',
            onPressed: paired && !controller.busy ? _manual : null,
            icon: const Icon(Icons.tune_rounded, size: 20),
          ),
        ],
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

  Widget _playButton(bool ended) => IconButton(
    key: const ValueKey('comparison-play'),
    onPressed: controller.canPlay ? controller.toggle : null,
    tooltip: controller.playing
        ? '暂停'
        : ended
        ? '重播'
        : '播放',
    style: IconButton.styleFrom(
      foregroundColor: AppTheme.of(context).editorSelection,
      minimumSize: const Size(56, 56),
    ),
    icon: Icon(
      controller.playing
          ? Icons.pause_rounded
          : ended
          ? Icons.replay_rounded
          : Icons.play_arrow_rounded,
      size: 40,
    ),
  );

  Widget _speedControl() {
    final value = Text(
      '${controller.project.speed.toString().replaceAll('.0', '')}×',
      maxLines: 1,
      style: const TextStyle(
        fontSize: 14,
        fontWeight: FontWeight.w600,
        height: 1.2,
      ),
    );
    return PopupMenuButton<double>(
      key: const ValueKey('comparison-speed'),
      tooltip: '播放速度',
      enabled: !controller.busy,
      onSelected: controller.setSpeed,
      itemBuilder: (_) => const [
        PopupMenuItem(value: 1, child: Text('1×')),
        PopupMenuItem(value: .75, child: Text('0.75×')),
        PopupMenuItem(value: .5, child: Text('0.5×')),
      ],
      child: Semantics(
        label: '播放速度',
        value: '${controller.project.speed.toString().replaceAll('.0', '')}倍',
        excludeSemantics: true,
        child: Container(
          constraints: const BoxConstraints(minHeight: 48),
          alignment: Alignment.center,
          child: value,
        ),
      ),
    );
  }

  Widget _controls() {
    final state = controller.playback;
    final duration = state?.durationUs ?? 0;
    final selected = controller.cutoutActive
        ? controller.cutoutPlayableRange
        : null;
    final start = selected?.$1 ?? 0, end = selected?.$2 ?? duration;
    final position =
        (_scrub ??
                (state?.positionUs.toDouble() ??
                    controller.project.positionUs.toDouble()))
            .clamp(start.toDouble(), math.max(start + 1, end).toDouble());
    final stepUs = end - start <= 30000000 ? 1000000 : 5000000;
    final ended = end > start && position >= end - 100000;
    return ColoredBox(
      key: const ValueKey('comparison-console'),
      color: _canvasColor,
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
                  style: TextStyle(
                    fontSize: 13,
                    color: AppTheme.of(context).error,
                  ),
                ),
              ),
            if (controller.cutoutActive)
              _cutoutPlaybackInfo()
            else if (controller.project.hasPair || controller.matching)
              _musicSummary(),
            if (!controller.cutoutActive &&
                !controller.matching &&
                controller.alignmentNotice != null)
              Semantics(
                liveRegion: true,
                child: Text(
                  controller.alignmentNotice!,
                  style: TextStyle(
                    fontSize: 12,
                    color: AppTheme.of(context).flowTextSecondary,
                  ),
                ),
              ),
            if (controller.cutoutActive)
              Column(
                children: [
                  Slider(
                    key: const ValueKey('comparison-cutout-timeline'),
                    min: start.toDouble(),
                    max: math.max(start + 1, end - 1).toDouble(),
                    value: position.clamp(
                      start.toDouble(),
                      math.max(start + 1, end - 1).toDouble(),
                    ),
                    onChanged: controller.canPlay
                        ? (v) => setState(() => _scrub = v)
                        : null,
                    onChangeEnd: (v) {
                      setState(() => _scrub = null);
                      unawaited(controller.seek(v.round()));
                    },
                  ),
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      Text(
                        comparisonTime(position.round()),
                        style: const TextStyle(fontSize: 12),
                      ),
                      Text(
                        controller.cutoutPartial
                            ? '预览 ${comparisonTime(start)}–${comparisonTime(end)}'
                            : selected != null
                            ? 'A/B ${comparisonTime(start)}–${comparisonTime(end)}'
                            : comparisonTime(end),
                        style: const TextStyle(fontSize: 12),
                      ),
                    ],
                  ),
                ],
              )
            else
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
            _transportControls(stepUs, position.round(), ended),
            if (!controller.cutoutActive) _practiceControls(),
          ],
        ),
      ),
    );
  }

  Widget _frameButton(bool forward) => IconButton(
    key: ValueKey(
      forward ? 'comparison-frame-forward' : 'comparison-frame-back',
    ),
    tooltip: forward ? '参考下一帧' : '参考上一帧',
    onPressed: controller.canStep ? () => controller.stepFrame(forward) : null,
    icon: Icon(forward ? Icons.skip_next_rounded : Icons.skip_previous_rounded),
  );

  Widget _transportControls(int stepUs, int positionUs, bool ended) {
    final actions = [
      _stepButton(false, stepUs, positionUs),
      _frameButton(false),
      _playButton(ended),
      _frameButton(true),
      _stepButton(true, stepUs, positionUs),
    ];
    Widget row(List<Widget> children) =>
        Row(children: children.map((child) => Expanded(child: child)).toList());
    return LayoutBuilder(
      builder: (context, bounds) => bounds.maxWidth >= 240
          ? row(actions)
          : Column(
              children: [
                row([actions[1], actions[2], actions[3]]),
                row([actions[0], actions[4]]),
              ],
            ),
    );
  }

  Widget _practiceControls() {
    final project = controller.project;
    return Row(
      key: const ValueKey('comparison-practice'),
      children: [
        Expanded(
          child: IconButton(
            key: const ValueKey('comparison-loop-toggle'),
            tooltip: project.loopEnabled ? '暂停 A/B 循环' : '启用 A/B 循环',
            isSelected: project.loopEnabled,
            color: project.loopEnabled
                ? AppTheme.of(context).editorSelection
                : AppTheme.of(context).flowTextSecondary,
            style: IconButton.styleFrom(
              backgroundColor: project.loopEnabled
                  ? AppTheme.of(context).editorSelectionSubtle
                  : Colors.transparent,
            ),
            onPressed:
                !controller.busy &&
                    project.loopStartUs != null &&
                    project.loopEndUs != null
                ? controller.toggleLoop
                : null,
            icon: const Icon(Icons.repeat_rounded),
          ),
        ),
        Expanded(
          child: IconButton(
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
        ),
        Expanded(child: _speedControl()),
      ],
    );
  }
}
