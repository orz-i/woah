import 'dart:async';
import 'dart:math' as math;
import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:dance_domain/dance_domain.dart';
import '../../../app/theme.dart';
import '../data/comparison_gateway.dart';
import 'comparison_controller.dart';

int parseComparisonTime(String value) {
  final match = RegExp(
    r'^(?:(\d+):)?(\d+)(?:\.(\d{1,6}))?$',
  ).firstMatch(value.trim());
  if (match == null) throw const FormatException('请输入秒或分:秒');
  final minutes = int.tryParse(match.group(1) ?? '0');
  final seconds = int.tryParse(match.group(2)!);
  if (minutes == null ||
      seconds == null ||
      minutes > 1440 ||
      seconds > 86400 ||
      (match.group(1) != null && seconds >= 60)) {
    throw const FormatException('时间无效');
  }
  final result =
      (minutes * 60 + seconds) * 1000000 +
      int.parse((match.group(3) ?? '').padRight(6, '0'));
  if (result > 86400000000) throw const FormatException('时间过大');
  return result;
}

String comparisonTime(int value) {
  final ms = value ~/ 1000;
  return '${(ms ~/ 60000).toString().padLeft(2, '0')}:${((ms ~/ 1000) % 60).toString().padLeft(2, '0')}.${(ms % 1000).toString().padLeft(3, '0')}';
}

enum _ComparisonProjectAction { create, save }

class ComparisonScreen extends StatefulWidget {
  final ComparisonGateway? gateway;
  const ComparisonScreen({super.key, this.gateway});
  @override
  State<ComparisonScreen> createState() => _ComparisonScreenState();
}

class _ComparisonScreenState extends State<ComparisonScreen>
    with WidgetsBindingObserver {
  late final ComparisonController controller;
  bool _allowPop = false, _exiting = false;
  double? _scrub;
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
      unawaited(controller.background());
    }
  }

  Future<void> _exit() async {
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
              title: Text('本机对比项目'),
              subtitle: Text('视频位置和音乐校准会自动保存'),
            ),
            if (controller.projects.isEmpty)
              const ListTile(title: Text('还没有保存的对比')),
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
    await controller.pause();
    if (!mounted || !controller.project.hasPair) return;
    final values = await showDialog<List<int>>(
      context: context,
      builder: (_) => _MusicAnchorDialog(project: controller.project),
    );
    if (values != null) await controller.manual(values[0], values[1]);
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
          title: const Text(
            '舞蹈对比',
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
          ),
          leading: BackButton(onPressed: _exit),
          centerTitle: false,
          actions: [
            TextButton.icon(
              key: const ValueKey('comparison-projects'),
              onPressed: controller.busy ? null : _history,
              icon: const Icon(Icons.folder_open_outlined, size: 20),
              label: const Text('项目'),
            ),
            PopupMenuButton<_ComparisonProjectAction>(
              key: const ValueKey('comparison-project-menu'),
              tooltip: '更多项目操作',
              enabled: !controller.busy && !controller.saving,
              onSelected: (action) {
                switch (action) {
                  case _ComparisonProjectAction.create:
                    unawaited(controller.newProject());
                  case _ComparisonProjectAction.save:
                    unawaited(_save());
                }
              },
              itemBuilder: (_) => const [
                PopupMenuItem(
                  value: _ComparisonProjectAction.save,
                  child: Text('保存并重命名'),
                ),
                PopupMenuItem(
                  value: _ComparisonProjectAction.create,
                  child: Text('新建对比'),
                ),
              ],
            ),
          ],
        ),
        body: SafeArea(
          child: !controller.initialized
              ? const Center(child: CircularProgressIndicator())
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
                              child: Column(
                                children: [
                                  _sources(),
                                  const SizedBox(height: 12),
                                  _controls(),
                                ],
                              ),
                            ),
                          ),
                        ],
                      );
                    }
                    return Column(
                      children: [
                        Padding(
                          padding: const EdgeInsets.fromLTRB(12, 8, 12, 8),
                          child: _sources(),
                        ),
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
  Widget _sources() => Row(
    children: [
      Expanded(
        child: _source('teacher', '老师', '参考', controller.project.teacher),
      ),
      IconButton(
        tooltip: '交换老师和学生',
        onPressed: controller.busy || !controller.project.hasPair
            ? null
            : controller.swap,
        icon: const Icon(Icons.swap_horiz, size: 20),
      ),
      Expanded(
        child: _source('learner', '学生', '自己', controller.project.learner),
      ),
    ],
  );

  Widget _source(
    String role,
    String label,
    String purpose,
    ComparisonSource? source,
  ) => Tooltip(
    message: source == null ? '选择$label视频' : source.name,
    child: OutlinedButton(
      key: ValueKey('comparison-pick-$role'),
      onPressed: controller.busy ? null : () => controller.pick(role),
      style: OutlinedButton.styleFrom(
        minimumSize: const Size(48, 48),
        padding: const EdgeInsets.symmetric(horizontal: 10),
        side: BorderSide(
          color: source == null ? AppTheme.gold : AppTheme.flowBorder,
        ),
      ),
      child: LayoutBuilder(
        builder: (context, bounds) => Row(
          children: [
            Text(label, style: const TextStyle(fontSize: 14)),
            if (bounds.maxWidth > 110 &&
                MediaQuery.textScalerOf(context).scale(14) < 19) ...[
              const SizedBox(width: 5),
              Text(
                source == null ? '选视频' : purpose,
                style: const TextStyle(
                  fontSize: 11,
                  color: AppTheme.flowTextSecondary,
                ),
              ),
            ],
            const Spacer(),
            Icon(
              source == null ? Icons.add : Icons.expand_more,
              size: 18,
              semanticLabel: source == null ? '选择视频' : '更换视频',
            ),
          ],
        ),
      ),
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
              const Center(
                child: Padding(
                  padding: EdgeInsets.all(24),
                  child: Text(
                    '选择两段视频\n按同一段音乐观察动作差异',
                    textAlign: TextAlign.center,
                    style: TextStyle(color: AppTheme.flowTextSecondary),
                  ),
                ),
              ),
            if (controller.project.hasPair &&
                (state == null || state.phase == 'opening'))
              const Center(
                child: CircularProgressIndicator(color: AppTheme.gold),
              ),
            if (controller.project.hasPair) ...[
              Positioned(top: 8, left: 8, child: _badge('老师')),
              Positioned(
                top: horizontal ? 8 : viewport.maxHeight / 2 + 8,
                left: horizontal ? viewport.maxWidth / 2 + 8 : 8,
                child: _badge('学生'),
              ),
            ],
          ],
        ),
      );
    },
  );
  Widget _badge(String label) => DecoratedBox(
    decoration: BoxDecoration(
      color: Colors.black54,
      borderRadius: BorderRadius.circular(5),
    ),
    child: Padding(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      child: Text(label, style: const TextStyle(fontSize: 12)),
    ),
  );
  String get _offsetLabel =>
      '${controller.project.offsetUs >= 0 ? '+' : ''}${(controller.project.offsetUs / 1e6).toStringAsFixed(3)}s';

  String get _alignmentLabel => switch (controller.project.alignment) {
    'auto' => '音乐已对齐',
    'manual' => '手动校准',
    'candidate' => '候选校准',
    _ => controller.candidates.isEmpty ? '尚未对齐音乐' : '请选择音乐位置',
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
                ? '正在匹配音乐…'
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
            onPressed: paired && !controller.picking ? _calibration : null,
            child: const Text('校准'),
          ),
      ],
    );
  }

  Future<void> _calibration() async {
    await controller.pause();
    if (!mounted) return;
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      showDragHandle: true,
      backgroundColor: AppTheme.flowSurface,
      constraints: BoxConstraints(
        maxHeight: MediaQuery.sizeOf(context).height * .82,
      ),
      builder: (sheetContext) => AnimatedBuilder(
        animation: controller,
        builder: (context, _) => SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(20, 0, 20, 24),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              Row(
                children: [
                  const Expanded(
                    child: Text(
                      '音乐校准',
                      style: TextStyle(
                        fontSize: 20,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ),
                  IconButton(
                    key: const ValueKey('comparison-calibration-close'),
                    tooltip: '关闭校准',
                    onPressed: () => Navigator.pop(sheetContext),
                    icon: const Icon(Icons.close, size: 22),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              Text(
                '$_alignmentLabel · $_offsetLabel',
                style: const TextStyle(
                  color: AppTheme.gold,
                  fontWeight: FontWeight.w600,
                ),
              ),
              const SizedBox(height: 8),
              Text(
                '老师 ${comparisonTime(controller.project.teacherAnchorUs ?? math.max(0, -controller.project.offsetUs))}'
                ' 对应学生 ${comparisonTime(controller.project.learnerAnchorUs ?? math.max(0, controller.project.offsetUs))}',
                style: const TextStyle(
                  fontSize: 12,
                  color: AppTheme.flowTextSecondary,
                ),
              ),
              const SizedBox(height: 8),
              Text(
                controller.message,
                style: const TextStyle(
                  fontSize: 13,
                  color: AppTheme.flowTextSecondary,
                ),
              ),
              const SizedBox(height: 16),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  OutlinedButton.icon(
                    key: const ValueKey('comparison-match'),
                    onPressed: controller.matching
                        ? controller.cancelMatching
                        : controller.matchMusic,
                    icon: Icon(
                      controller.matching
                          ? Icons.close
                          : Icons.music_note_outlined,
                      size: 18,
                    ),
                    label: Text(controller.matching ? '取消匹配' : '匹配音乐'),
                  ),
                  FilledButton.tonalIcon(
                    key: const ValueKey('comparison-manual'),
                    onPressed: controller.busy
                        ? null
                        : () {
                            Navigator.pop(sheetContext);
                            unawaited(_manual());
                          },
                    icon: const Icon(Icons.tune, size: 18),
                    label: const Text('手动校准'),
                  ),
                ],
              ),
              if (!controller.matching && controller.candidates.isNotEmpty) ...[
                const Divider(height: 32),
                const Text(
                  '匹配位置',
                  style: TextStyle(fontWeight: FontWeight.w600),
                ),
                const SizedBox(height: 4),
                const Text(
                  '试听会回到对应起点，播放约 8 秒后暂停。',
                  style: TextStyle(
                    fontSize: 12,
                    color: AppTheme.flowTextSecondary,
                  ),
                ),
                for (var i = 0; i < controller.candidates.length; i++)
                  Padding(
                    padding: const EdgeInsets.only(top: 12),
                    child: DecoratedBox(
                      decoration: AppTheme.panelDecoration(radius: 12),
                      child: Padding(
                        padding: const EdgeInsets.fromLTRB(12, 4, 12, 12),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Row(
                              children: [
                                Expanded(
                                  child: Text(
                                    controller.candidates[i].offsetUs ==
                                            controller.project.offsetUs
                                        ? '候选 ${i + 1} · 当前'
                                        : '候选 ${i + 1}',
                                  ),
                                ),
                                TextButton(
                                  onPressed: () {
                                    Navigator.pop(sheetContext);
                                    unawaited(
                                      controller.applyCandidate(
                                        controller.candidates[i],
                                      ),
                                    );
                                  },
                                  child: const Text('试听 8 秒'),
                                ),
                              ],
                            ),
                            Text(
                              '音乐偏移 ${(controller.candidates[i].offsetUs / 1e6).toStringAsFixed(3)} 秒',
                            ),
                            const SizedBox(height: 4),
                            Text(
                              '${controller.candidates[i].consistentWindows}/3 段一致 · 共同段约 ${controller.candidates[i].supportedSeconds.toStringAsFixed(1)} 秒',
                              style: const TextStyle(
                                fontSize: 12,
                                color: AppTheme.flowTextSecondary,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
              ],
              const Divider(height: 32),
              const Text('所用视频', style: TextStyle(fontWeight: FontWeight.w600)),
              const SizedBox(height: 8),
              for (final entry in [
                ('老师（参考）', controller.project.teacher),
                ('学生（自己）', controller.project.learner),
              ])
                Padding(
                  padding: const EdgeInsets.only(bottom: 8),
                  child: Text(
                    '${entry.$1} · ${entry.$2?.name ?? '未选择'}',
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      fontSize: 12,
                      color: AppTheme.flowTextSecondary,
                    ),
                  ),
                ),
            ],
          ),
        ),
      ),
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

class _MusicAnchorDialog extends StatefulWidget {
  final ComparisonProject project;
  const _MusicAnchorDialog({required this.project});
  @override
  State<_MusicAnchorDialog> createState() => _MusicAnchorDialogState();
}

class _MusicAnchorDialogState extends State<_MusicAnchorDialog> {
  late final TextEditingController teacher;
  late final TextEditingController learner;
  @override
  void initState() {
    super.initState();
    teacher = TextEditingController(
      text: widget.project.teacherAnchorUs == null
          ? ''
          : (widget.project.teacherAnchorUs! / 1e6).toStringAsFixed(6),
    );
    learner = TextEditingController(
      text: widget.project.learnerAnchorUs == null
          ? ''
          : (widget.project.learnerAnchorUs! / 1e6).toStringAsFixed(6),
    );
  }

  @override
  void dispose() {
    teacher.dispose();
    learner.dispose();
    super.dispose();
  }

  List<int>? _values() {
    try {
      final t = parseComparisonTime(teacher.text),
          l = parseComparisonTime(learner.text);
      if (t < widget.project.teacher!.durationUs &&
          l < widget.project.learner!.durationUs) {
        return [t, l];
      }
    } on FormatException {
      /* Incomplete input keeps the Apply action disabled. */
    }
    return null;
  }

  @override
  Widget build(BuildContext context) {
    final values = _values();
    return AlertDialog(
      scrollable: true,
      title: const Text('手动音乐校准'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const Text('找到同一歌词或鼓点，填入它在两段视频中的时间。支持秒或分:秒。'),
          const SizedBox(height: 16),
          TextField(
            key: const ValueKey('comparison-anchor-teacher'),
            controller: teacher,
            textInputAction: TextInputAction.next,
            decoration: const InputDecoration(
              labelText: '老师源时间',
              hintText: '例如 0 或 1:05.125',
            ),
            onChanged: (_) => setState(() {}),
          ),
          const SizedBox(height: 12),
          TextField(
            key: const ValueKey('comparison-anchor-learner'),
            controller: learner,
            textInputAction: TextInputAction.done,
            decoration: const InputDecoration(
              labelText: '学生同一音乐事件源时间',
              hintText: '例如 2',
            ),
            onChanged: (_) => setState(() {}),
          ),
          const SizedBox(height: 16),
          Text(
            values == null
                ? '请填写两段视频内的有效时间。'
                : '学生 − 老师 = ${((values[1] - values[0]) / 1e6).toStringAsFixed(3)} 秒',
          ),
        ],
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('取消'),
        ),
        FilledButton(
          onPressed: values == null
              ? null
              : () {
                  final current = _values();
                  if (current != null) Navigator.pop(context, current);
                },
          child: const Text('应用校准'),
        ),
      ],
    );
  }
}
