import 'dart:async';
import 'dart:math' as math;
import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:video_player/video_player.dart';
import 'package:dance_domain/dance_domain.dart';
import '../../../app/theme.dart';
import '../data/comparison_gateway.dart';
import 'comparison_controller.dart';
import 'comparison_manual_session.dart';
import 'comparison_manual_view.dart';
import 'comparison_time.dart';

enum _ComparisonProjectAction { create, save, swap }

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
  bool _finishingManual = false;
  @override
  void initState() {
    super.initState();
    controller = ComparisonController(
      widget.gateway ?? NativeComparisonGateway(),
    );
    WidgetsBinding.instance.addObserver(this);
    unawaited(controller.initialize());
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
    if (_finishingManual) return;
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
    setState(() => _allowPop = true);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) context.pop();
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    if (_manualSession != null) unawaited(_manualSession!.close());
    controller.dispose();
    super.dispose();
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
                  '${project.teacher?.name ?? '待选老师'} · ${project.learner?.name ?? '待选学生'}',
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
    if (!mounted) return;
    final session = ComparisonManualSession(
      controller.project,
      factory: widget.manualVideoFactory,
    );
    setState(() => _manualSession = session);
    unawaited(session.initialize());
  }

  Future<void> _finishManual(bool apply) async {
    final session = _manualSession;
    if (session == null || _finishingManual) return;
    setState(() => _finishingManual = true);
    try {
      if (apply) {
        final anchors = await session.anchors();
        if (!mounted) return;
        if (!await controller.manual(anchors.$1, anchors.$2)) return;
      }
      // Remove both preview Texture widgets and their listeners before releasing
      // their producers. The shared GL surface is opened only after that frame.
      setState(() => _manualSession = null);
      await WidgetsBinding.instance.endOfFrame;
      await session.close();
      if (!mounted) return;
      await controller.leaveManualCalibration(applied: apply);
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(const SnackBar(content: Text('校准未完成，请重试。')));
      }
    } finally {
      if (mounted) setState(() => _finishingManual = false);
    }
  }

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
          title: Text(
            _manualSession != null ? '手动校准' : '舞蹈对比',
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
          ),
          leading: BackButton(onPressed: _exit),
          centerTitle: false,
          actions: _manualSession != null
              ? []
              : [
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
                        case _ComparisonProjectAction.create:
                          unawaited(controller.newProject());
                        case _ComparisonProjectAction.save:
                          unawaited(_save());
                        case _ComparisonProjectAction.swap:
                          unawaited(controller.swap());
                      }
                    },
                    itemBuilder: (_) => [
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
                        child: const Text('交换老师和学生'),
                      ),
                    ],
                  ),
                ],
        ),
        body: SafeArea(
          child: !controller.initialized
              ? const Center(child: CircularProgressIndicator())
              : _manualSession != null
              ? ComparisonManualView(
                  session: _manualSession!,
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
                          Expanded(child: _preview(true)),
                          SizedBox(
                            width: math.min(340, viewport.maxWidth * .43),
                            child: SingleChildScrollView(
                              padding: const EdgeInsets.all(8),
                              child: _controls(),
                            ),
                          ),
                        ],
                      );
                    }
                    return Column(
                      children: [
                        Expanded(child: _preview(false)),
                        ConstrainedBox(
                          constraints: BoxConstraints(
                            maxHeight: viewport.maxHeight * .46,
                          ),
                          child: SingleChildScrollView(child: _controls()),
                        ),
                      ],
                    );
                  },
                ),
        ),
      ),
    ),
  );
  Widget _source(String role, String label, ComparisonSource? source) =>
      Tooltip(
        message: source == null ? '选择$label视频' : source.name,
        child: OutlinedButton.icon(
          key: ValueKey('comparison-pick-$role'),
          onPressed: controller.busy ? null : () => controller.pick(role),
          style: OutlinedButton.styleFrom(
            backgroundColor: Colors.black.withValues(alpha: .72),
            foregroundColor: AppTheme.flowTextPrimary,
            minimumSize: const Size(48, 48),
            padding: const EdgeInsets.symmetric(horizontal: 12),
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(8),
            ),
            side: BorderSide.none,
          ),
          iconAlignment: IconAlignment.end,
          icon: Icon(
            source == null ? Icons.add : Icons.expand_more,
            size: 16,
            semanticLabel: source == null ? '选择视频' : '更换视频',
          ),
          label: Text(label, style: const TextStyle(fontSize: 12)),
        ),
      );

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
            if (!controller.project.hasPair)
              Flex(
                direction: horizontal ? Axis.horizontal : Axis.vertical,
                children: [
                  for (final entry in [
                    ('老师', controller.project.teacher),
                    ('学生', controller.project.learner),
                  ])
                    Expanded(
                      child: Center(
                        child: Padding(
                          padding: const EdgeInsets.all(24),
                          child: Text(
                            entry.$2 == null
                                ? '待选择${entry.$1}视频'
                                : '已选择${entry.$1}视频',
                            textAlign: TextAlign.center,
                            style: const TextStyle(
                              color: AppTheme.flowTextSecondary,
                            ),
                          ),
                        ),
                      ),
                    ),
                ],
              ),
            if (controller.project.hasPair &&
                (state == null || state.phase == 'opening'))
              const Center(
                child: CircularProgressIndicator(color: AppTheme.gold),
              ),
            Positioned(
              top: 8,
              left: 8,
              child: _source('teacher', '老师', controller.project.teacher),
            ),
            Positioned(
              top: horizontal ? 8 : viewport.maxHeight / 2 + 8,
              left: horizontal ? viewport.maxWidth / 2 + 8 : 8,
              child: _source('learner', '学生', controller.project.learner),
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
                ? '先选择老师和学生视频'
                : _alignmentLabel + (aligned ? ' · $_offsetLabel' : ''),
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

  String _displayTime(int value) {
    final formatted = comparisonTime(value);
    return MediaQuery.textScalerOf(context).scale(13) > 18
        ? formatted.split('.').first
        : formatted;
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
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Flexible(
                  child: Text(
                    _displayTime(position.round()),
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      fontSize: 13,
                      fontFeatures: [FontFeature.tabularFigures()],
                    ),
                  ),
                ),
                Flexible(
                  child: Text(
                    _displayTime(duration),
                    textAlign: TextAlign.end,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      fontSize: 13,
                      color: AppTheme.flowTextSecondary,
                      fontFeatures: [FontFeature.tabularFigures()],
                    ),
                  ),
                ),
              ],
            ),
            Semantics(
              label: '共同进度',
              child: Slider(
                key: const ValueKey('comparison-seek'),
                min: 0,
                max: math.max(1, duration).toDouble(),
                value: position,
                semanticFormatterCallback: (value) =>
                    comparisonTime(value.round()),
                onChanged: duration > 0 && !controller.busy
                    ? (value) => setState(() => _scrub = value)
                    : null,
                onChangeEnd: duration > 0 && !controller.busy
                    ? (value) {
                        setState(() => _scrub = null);
                        unawaited(controller.seek(value.round()));
                      }
                    : null,
              ),
            ),
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
}
